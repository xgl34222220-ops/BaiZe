package io.github.xgl34222220.baize

import android.content.Context
import android.content.SharedPreferences
import android.app.Activity
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * All UI writers of baize_v2 go through this accessor. Android's empty-on-corrupt-XML fallback
 * must never let a theme/default/plan write erase protection evidence before recovery sees it.
 * A quarantine is permanent until a separate, explicit repair workflow is implemented.
 */
internal object LegacyPreferencesAccess {
    const val BLOCKED_MESSAGE = "旧版保护原始记录损坏或写入未完成，已停止写入并暂停清理。请勿清空应用数据；检查旧版保护后联系支持处理。"
    private const val LEGACY = "baize_v2"
    private const val OBSERVED = "legacy-protection-recovery-v1"
    private const val QUARANTINE = "legacy-protection-integrity-v1"
    private val failedPreservation = mutableSetOf<String>()
    private val handles = WeakHashMap<Context, WeakReference<SharedPreferences>>()

    /** Must run in Application.attachBaseContext, before providers or any startup preference writer. */
    @Synchronized fun initialize(context: Context) {
        if (isBlocked(context)) {
            verifyArchive(context)
            return
        }
        try {
            for (name in listOf(LEGACY, OBSERVED)) {
                val data = CheckedLegacyPreferences.readDisk(source(context, name))
                if (name == OBSERVED) check("observed" !in data || data["observed"] is Boolean)
            }
        } catch (failure: Exception) {
            preserve(context, failure)
        }
    }

    fun requireHealthy(context: Context) {
        check(!isBlocked(context)) { BLOCKED_MESSAGE }
    }

    @Synchronized fun isBlocked(context: Context): Boolean =
        context.dataDir.absolutePath in failedPreservation || attributes(quarantineDirectory(context)) != null

    internal fun quarantineDirectory(context: Context): File = File(context.noBackupFilesDir, QUARANTINE)
    private fun source(context: Context, name: String) = File(context.dataDir, "shared_prefs/$name.xml")

    @Synchronized fun preferences(context: Context): SharedPreferences {
        // Activity handles use the application, while explicit ContextWrapper test/storage routing
        // is preserved. Weak keys AND values avoid retaining a Context through its guarded handle.
        val stable = if (context is Activity) context.applicationContext else context
        return handles[stable]?.get() ?: GuardedPreferences(stable).also {
            handles[stable] = WeakReference(it)
        }
    }

    /** No ApkProtectionStore lock here: appearance DataStore initialization also calls this path. */
    @Synchronized private fun writable(context: Context): Boolean {
        if (isBlocked(context)) return false
        return try {
            CheckedLegacyPreferences.read(context, LEGACY)
            true
        } catch (failure: Exception) {
            // A queued apply is retryable, not evidence of permanent corruption. Its writer remains
            // blocked for this attempt; no editor is allowed to turn the mismatch into fresh defaults.
            if (!CheckedLegacyPreferences.isPendingWrite(failure)) preserve(context, failure)
            false
        }
    }

    @Synchronized internal fun preserve(context: Context, failure: Exception) {
        if (isBlocked(context)) return
        val root = quarantineDirectory(context)
        failedPreservation += context.dataDir.absolutePath
        // Creating the directory is itself the fail-closed marker. Even an interrupted archive is
        // blocked on the next process start; never automatically clear or overwrite this directory.
        check(root.mkdirs() || root.isDirectory) { "无法保留旧版保护原始记录，已停止启动。" }
        syncDirectory(requireNotNull(root.parentFile))
        val records = JSONArray()
        for (name in listOf(LEGACY, OBSERVED)) {
            for (suffix in listOf("", ".bak", ".tmp")) {
                val original = File(source(context, name).path + suffix)
                val stat = attributes(original)
                if (stat == null) {
                    records.put(JSONObject().put("name", original.name).put("missing", true))
                    continue
                }
                check(stat.isRegularFile && !stat.isSymbolicLink && original.canRead()) {
                    "旧版保护原始记录不可复制，已停止写入。"
                }
                val archive = File(root, original.name)
                check(archive.createNewFile()) { "旧版保护原始记录已保留，禁止覆盖。" }
                original.inputStream().use { input ->
                    FileOutputStream(archive).use { output -> input.copyTo(output); output.fd.sync() }
                }
                val hash = sha256(archive)
                check(archive.length() == stat.size() && original.length() == stat.size() && sha256(original) == hash) {
                    "旧版保护原始记录保存后复核失败，已停止启动。"
                }
                records.put(JSONObject().put("name", original.name).put("missing", false)
                    .put("bytes", archive.length()).put("sha256", hash))
            }
        }
        val manifest = JSONObject().put("version", 1).put("complete", true).put("files", records)
            .put("failure", failure.javaClass.simpleName).toString()
        FileOutputStream(File(root, "manifest.json")).use {
            it.write(manifest.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        syncDirectory(root)
        verifyArchive(context)
        failedPreservation.remove(context.dataDir.absolutePath)
    }

    /** An incomplete/corrupt archive must halt startup; a directory alone never means safe to run. */
    private fun verifyArchive(context: Context) {
        val root = quarantineDirectory(context)
        val rootStat = requireNotNull(attributes(root)) { "旧版保护原始记录未能保留，已停止启动。" }
        check(rootStat.isDirectory && !rootStat.isSymbolicLink)
        val file = File(root, "manifest.json")
        val stat = requireNotNull(attributes(file)) { "旧版保护原始记录保留未完成，已停止启动。" }
        check(stat.isRegularFile && !stat.isSymbolicLink && stat.size() in 1..16_384L)
        val manifest = JSONObject(file.readText())
        check(manifest.get("version") == 1 && manifest.get("complete") == true)
        val expected = listOf(LEGACY, OBSERVED).flatMap { name ->
            listOf("", ".bak", ".tmp").map { suffix -> "$name.xml$suffix" }
        }.toSet()
        val records = manifest.getJSONArray("files")
        check(records.length() == expected.size)
        val seen = mutableSetOf<String>()
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            val name = record.getString("name")
            check(name in expected && seen.add(name))
            val archive = File(root, name)
            val archiveStat = attributes(archive)
            check(record.get("missing") is Boolean)
            if (record.get("missing") == true) check(archiveStat == null)
            else {
                check(archiveStat != null && archiveStat.isRegularFile && !archiveStat.isSymbolicLink)
                val length = record.get("bytes")
                check(length is Int || length is Long)
                check(archiveStat.size() == (length as Number).toLong() && sha256(archive) == record.getString("sha256"))
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    private class GuardedPreferences(private val context: Context) : SharedPreferences {
        private var delegate: SharedPreferences? = null
        private fun readable(): SharedPreferences = synchronized(LegacyPreferencesAccess) {
            if (isBlocked(context)) DisplayDefaults
            else delegate ?: if (writable(context)) {
                context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).also { delegate = it }
            } else DisplayDefaults
        }
        override fun getAll(): MutableMap<String, *> = readable().all
        override fun getString(key: String?, defValue: String?): String? = readable().getString(key, defValue)
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = readable().getStringSet(key, defValues)
        override fun getInt(key: String?, defValue: Int) = readable().getInt(key, defValue)
        override fun getLong(key: String?, defValue: Long) = readable().getLong(key, defValue)
        override fun getFloat(key: String?, defValue: Float) = readable().getFloat(key, defValue)
        override fun getBoolean(key: String?, defValue: Boolean) = readable().getBoolean(key, defValue)
        override fun contains(key: String?) = readable().contains(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = readable().registerOnSharedPreferenceChangeListener(listener)
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = readable().unregisterOnSharedPreferenceChangeListener(listener)
        override fun edit(): SharedPreferences.Editor = GuardedEditor(context)
    }

    /** Stage edits, then recheck at commit/apply, including editors captured before corruption. */
    private class GuardedEditor(private val context: Context) : SharedPreferences.Editor {
        private val changes = mutableListOf<(SharedPreferences.Editor) -> Unit>()
        override fun putString(key: String?, value: String?) = apply { changes += { it.putString(key, value) } }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
            val copy = values?.toSet()
            changes += { it.putStringSet(key, copy) }
        }
        override fun putInt(key: String?, value: Int) = apply { changes += { it.putInt(key, value) } }
        override fun putLong(key: String?, value: Long) = apply { changes += { it.putLong(key, value) } }
        override fun putFloat(key: String?, value: Float) = apply { changes += { it.putFloat(key, value) } }
        override fun putBoolean(key: String?, value: Boolean) = apply { changes += { it.putBoolean(key, value) } }
        override fun remove(key: String?) = apply { changes += { it.remove(key) } }
        override fun clear() = apply { changes += { it.clear() } }
        override fun commit(): Boolean = synchronized(LegacyPreferencesAccess) {
            if (!writable(context)) return@synchronized false
            val pending = changes.toList().also { changes.clear() }
            context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit()
                .also { editor -> pending.forEach { it(editor) } }.commit()
        }
        override fun apply() = synchronized(LegacyPreferencesAccess) {
            if (writable(context)) {
                val pending = changes.toList().also { changes.clear() }
                context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit()
                    .also { editor -> pending.forEach { it(editor) } }.apply()
            }
        }
    }

    /** Rendering defaults only. Protection never reads this object and writes never reach disk. */
    private object DisplayDefaults : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues?.toMutableSet()
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = false
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = this
            override fun clear() = this
            override fun commit() = false
            override fun apply() = Unit
        }
    }
}
