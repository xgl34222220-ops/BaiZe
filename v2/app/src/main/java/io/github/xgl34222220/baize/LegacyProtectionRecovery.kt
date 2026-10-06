package io.github.xgl34222220.baize

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.xgl34222220.baize.ui.appearance.appearanceDataStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray

/**
 * Old appearance versions moved ALL baize_v2 keys, then cleared their source. Those copies may
 * already be stale. Never silently restore them, and never use the appearance UI's empty fallback.
 * All protection reads, edits and recovery transactions share ApkProtectionStore's monitor.
 */
internal object LegacyProtectionRecovery {
    const val REVIEW_MESSAGE = "旧版保护待确认，请打开保护名单 → 检查旧版保护；核对前已暂停清理。"
    private const val TIMEOUT_MS = 2_000L
    private const val OBSERVED_PREFS = "legacy-protection-recovery-v1"
    internal const val PACKAGES = "package_whitelist"
    internal const val PATHS = "path_whitelist"
    private const val PREFIX = "baize.protection.recovery.v1."

    data class RecoverySnapshot(
        val currentPackages: Set<String>,
        val currentPaths: Set<String>,
        val pendingPackages: Set<String>,
        val pendingPaths: Set<String>,
        val packageReviewRequired: Boolean,
        val pathReviewRequired: Boolean,
        val fingerprint: String,
        val currentPackagesPresent: Boolean = false,
        val currentPathsPresent: Boolean = false
    ) {
        val pending: Boolean get() = packageReviewRequired || pathReviewRequired
    }

    fun read(context: Context): RecoverySnapshot = synchronized(ApkProtectionStore) {
        val checked = CheckedLegacyPreferences.read(context, "baize_v2")
        if (!existingStore(context)) snapshot(emptyPreferences(), checked)
        else bridge { engine(context).read() }
    }

    fun requireReviewed(context: Context) {
        check(!read(context).pending) { REVIEW_MESSAGE }
    }

    fun resolve(
        context: Context,
        expected: RecoverySnapshot,
        selectedPackages: Set<String>,
        selectedPaths: Set<String>
    ): RecoverySnapshot = synchronized(ApkProtectionStore) {
        check(existingStore(context)) { "旧版保护记录已变化，请刷新后重新核对。" }
        CheckedLegacyPreferences.read(context, "baize_v2")
        bridge { engine(context).resolve(expected, selectedPackages, selectedPaths) }
    }

    private fun local(context: Context) = LegacyPreferencesAccess.preferences(context)
    private fun engine(context: Context) = Engine(context.appearanceDataStore, local(context)) {
        CheckedLegacyPreferences.read(context, "baize_v2")
    }

    private fun <T> bridge(block: suspend () -> T): T = try {
        runBlocking(Dispatchers.IO) { withTimeout(TIMEOUT_MS) { block() } }
    } catch (failure: Exception) {
        throw IllegalStateException("旧版保护暂不可核对，已暂停清理。${failure.message.orEmpty().take(180)}", failure)
    }

    /** A confirmed fresh installation can avoid creating a DataStore just to read local rules. */
    private fun existingStore(context: Context): Boolean {
        val file = context.applicationContext.preferencesDataStoreFile("appearance")
        val observedValues = CheckedLegacyPreferences.read(context, OBSERVED_PREFS)
        check("observed" !in observedValues || observedValues["observed"] is Boolean) {
            "旧版保护存储标记格式无效，已暂停清理。"
        }
        val observed = context.getSharedPreferences(OBSERVED_PREFS, Context.MODE_PRIVATE)
        val attributes = attributesOrMissing(file)
        if (attributes == null) {
            check(!(observedValues["observed"] == true)) { "旧版保护存储文件丢失，已暂停清理。" }
            // Missing under an unreadable or non-directory parent is not a fresh installation.
            var parent = file.parentFile
            while (parent != null) {
                val parentAttributes = attributesOrMissing(parent)
                if (parentAttributes != null) {
                    check(parentAttributes.isDirectory && parent.canRead() && parent.canExecute()) {
                        "旧版保护存储目录不可读，已暂停清理。"
                    }
                    break
                }
                parent = parent.parentFile
            }
            check(!File(file.path + ".tmp").exists() && !File(file.path + ".bak").exists()) {
                "旧版保护存储尚未完成写入，已暂停清理。"
            }
            return false
        }
        check(attributes.isRegularFile && !attributes.isSymbolicLink && file.canRead()) {
            "旧版保护存储不可读，已暂停清理。"
        }
        if (!(observedValues["observed"] == true)) {
            check(observed.edit().putBoolean("observed", true).commit()) { "无法确认旧版保护存储状态，已暂停清理。" }
            check(CheckedLegacyPreferences.read(context, OBSERVED_PREFS)["observed"] == true) {
                "旧版保护存储标记保存后核对失败，已暂停清理。"
            }
        }
        return true
    }

    private fun attributesOrMissing(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    } catch (failure: IOException) {
        throw IllegalStateException("旧版保护存储不可读，已暂停清理。", failure)
    }

    /** Injectable only to allow real, isolated DataStore regression tests. Production uses the singleton above. */
    internal class Engine(
        private val store: DataStore<Preferences>,
        private val local: SharedPreferences,
        private val readLocal: () -> Map<String, *> = { local.all }
    ) {
        suspend fun read(): RecoverySnapshot = snapshot(store.data.first(), readLocal())

        suspend fun resolve(
            expected: RecoverySnapshot,
            selectedPackages: Set<String>,
            selectedPaths: Set<String>
        ): RecoverySnapshot {
            val original = store.data.first()
            val before = readLocal().toMap()
            val current = snapshot(original, before)
            check(current.fingerprint == expected.fingerprint) { "保护记录已变化，请刷新后重新核对。" }
            require(current.pending) { "没有待确认的旧版保护，请刷新。" }
            require(selectedPackages.all { it in current.pendingPackages } && selectedPaths.all { it in current.pendingPaths }) {
                "所选项目不在当前待确认记录中，请刷新。"
            }
            // Durable write-ahead guard: failed/partial copies can never become known protection.
            val journal = store.updateData { data ->
                check(snapshot(data, readLocal()).fingerprint == current.fingerprint) { "保护记录已变化，请刷新后重新核对。" }
                data.toMutablePreferences().apply {
                    if (current.packageReviewRequired) this[journalKey(PACKAGES)] = true
                    if (current.pathReviewRequired) this[journalKey(PATHS)] = true
                }.toPreferences()
            }
            val target = before.toMutableMap()
            if (current.packageReviewRequired && PACKAGES !in before) target[PACKAGES] = selectedPackages.toSet()
            if (current.pathReviewRequired && PATHS !in before) target[PATHS] = selectedPaths.toSet()
            // Include both known fields if present to make an interrupted retry durable again.
            var commitAttempted = false
            try {
                check(localFingerprint(readLocal()) == localFingerprint(before)) { "本地保护已变化，请刷新后重新核对。" }
                val editor = local.edit()
                for (name in listOf(PACKAGES, PATHS)) {
                    if (name in target) editor.putStringSet(name, stringSet(target, name)!!)
                }
                commitAttempted = true
                check(editor.commit()) { "旧版保护保存失败，仍需重新确认。" }
                check(localFingerprint(readLocal()) == localFingerprint(target)) { "旧版保护保存后核对失败，仍需重新确认。" }
            } catch (failure: Exception) {
                // commit(false) may still change SharedPreferences' in-memory map. Restore exactly,
                // including absent versus explicit empty, while the durable journal remains pending.
                // Do not roll back an edit made after this snapshot or overwrite a newer writer.
                if (commitAttempted && localFingerprint(local.all) == localFingerprint(target)) {
                    val rollback = local.edit()
                    for (name in listOf(PACKAGES, PATHS)) {
                        if (name in before) rollback.putStringSet(name, stringSet(before, name)!!)
                        else rollback.remove(name)
                    }
                    rollback.commit()
                }
                throw failure
            }
            val finished = store.updateData { data ->
                check(fingerprint(data, readLocal()) == fingerprint(journal, target)) {
                    "保护记录在保存时发生变化，仍需刷新并确认。"
                }
                data.toMutablePreferences().apply {
                    for (name in listOf(PACKAGES, PATHS)) {
                        if (data[journalKey(name)] == true) {
                            // Keep the historical value in place as its immutable recovery archive.
                            this[reviewedKey(name)] = historicalFingerprint(data, name)
                            remove(journalKey(name))
                        }
                    }
                }.toPreferences()
            }
            // updateData returning is the durable commit point. Local commit + disk readback
            // and this transaction's fingerprint were already checked. An unrelated theme apply
            // after this point must not turn a successfully committed review into a false failure.
            return snapshot(finished, target)
        }
    }

    private fun snapshot(data: Preferences, local: Map<String, *>): RecoverySnapshot {
        val history = data.asMap().entries.associate { it.key.name to it.value }
        val localPackages = stringSet(local, PACKAGES)
        val localPaths = stringSet(local, PATHS)
        val oldPackages = stringSet(history, PACKAGES)
        val oldPaths = stringSet(history, PATHS)
        // Even invalid historical records must not be hidden behind an otherwise valid local field.
        validate(localPackages.orEmpty(), localPaths.orEmpty())
        validate(oldPackages.orEmpty(), oldPaths.orEmpty())
        fun needsReview(name: String, present: Boolean, old: Set<String>?): Boolean {
            val journal = history[PREFIX + name + ".pending"]
            check(journal == null || journal is Boolean) { "旧版保护恢复状态格式无效，已暂停清理。" }
            val reviewed = history[PREFIX + name + ".reviewed"]
            check(reviewed == null || reviewed is String) { "旧版保护确认记录格式无效，已暂停清理。" }
            if (journal == true) {
                check(old != null) { "旧版保护恢复记录缺失，已暂停清理。" }
                return true
            }
            return !present && old != null && reviewed != historicalFingerprint(data, name)
        }
        val packagesPending = needsReview(PACKAGES, localPackages != null, oldPackages)
        val pathsPending = needsReview(PATHS, localPaths != null, oldPaths)
        return RecoverySnapshot(localPackages.orEmpty(), localPaths.orEmpty(),
            if (packagesPending && localPackages == null) oldPackages.orEmpty() else emptySet(),
            if (pathsPending && localPaths == null) oldPaths.orEmpty() else emptySet(),
            packagesPending, pathsPending, fingerprint(data, local), localPackages != null, localPaths != null)
    }

    private fun stringSet(map: Map<String, *>, name: String): Set<String>? {
        if (!map.containsKey(name)) return null
        val value = map[name]
        check(value is Set<*> && value.all { it is String }) { "旧版保护记录格式无效，已暂停清理。" }
        return value.mapTo(linkedSetOf()) { it as String }
    }

    private fun validate(packages: Set<String>, paths: Set<String>) {
        ApkProtectionStore.parse(JSONArray(packages.sorted()).toString(), JSONArray(paths.sorted()).toString())
    }

    private fun journalKey(name: String) = booleanPreferencesKey(PREFIX + name + ".pending")
    private fun reviewedKey(name: String) = stringPreferencesKey(PREFIX + name + ".reviewed")
    private fun historicalFingerprint(data: Preferences, name: String): String =
        digest(canonical(data.asMap().entries.firstOrNull { it.key.name == name }?.value))
    private fun localFingerprint(local: Map<String, *>): String = digest(listOf(PACKAGES, PATHS).joinToString("|") {
        "$it:${if (local.containsKey(it)) canonical(local[it]) else "absent"}"
    })
    private fun fingerprint(data: Preferences, local: Map<String, *>): String = digest(
        data.asMap().entries.sortedBy { it.key.name }.joinToString("|") {
            "${canonical(it.key.name)}:${canonical(it.value)}"
        } + "|local:" + localFingerprint(local)
    )
    private fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is String -> "s:" + JSONArray().put(value).toString()
        is Set<*> -> {
            check(value.all { it is String }) { "旧版保护存储格式无效，已暂停清理。" }
            "set:" + JSONArray(value.map { it as String }.sorted()).toString()
        }
        is Boolean -> "b:$value"
        is Int -> "i:$value"
        is Long -> "l:$value"
        is Float -> "f:${value.toRawBits()}"
        is Double -> "d:${value.toRawBits()}"
        is ByteArray -> "bytes:" + value.joinToString("") { "%02x".format(it) }
        else -> error("旧版保护存储格式未知，已暂停清理。")
    }
    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
