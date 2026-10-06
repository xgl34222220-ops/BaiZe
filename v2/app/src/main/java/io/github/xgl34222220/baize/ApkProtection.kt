package io.github.xgl34222220.baize

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException
import io.github.xgl34222220.baize.root.IProfileRootService
import io.github.xgl34222220.baize.root.RootServiceClients

internal data class ApkProtectionRules(val packages: Set<String>, val paths: Set<String>) {
    fun plus(other: ApkProtectionRules) = ApkProtectionRules(packages + other.packages, paths + other.paths)
}

internal sealed interface ApkProtectionState {
    val rules: ApkProtectionRules?
    data class KnownRoot(override val rules: ApkProtectionRules) : ApkProtectionState
    data class LocalOnly(override val rules: ApkProtectionRules) : ApkProtectionState
    data class Unknown(val reason: String, override val rules: ApkProtectionRules? = null) : ApkProtectionState
}

/** Optional module installation is deliberately not part of this contract. */
internal interface ApkProtectionSource {
    fun snapshot(): String
}

/** A failed or partial read never replaces a previously verified tuple with an empty list. */
internal object ApkProtectionStore {
    private const val PREFS = "apk-protection-v1"
    private const val MAX_ENTRIES = 1_000
    private const val MAX_JSON_CHARS = 4 * 1024 * 1024
    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun source(context: Context, remote: IProfileRootService?): ApkProtectionSource? = remote?.let { service ->
        object : ApkProtectionSource {
            override fun snapshot() = RootServiceClients.profileExchange(service, context.cacheDir, "getApkProtection")
        }
    }

    fun parse(packagesJson: String, pathsJson: String): ApkProtectionRules = ApkProtectionRules(
        parseStrings(packagesJson) { item ->
            require(item.length <= 255 && item.isNotBlank() && item.none { it == '/' || it == '\\' || it.isWhitespace() })
            require(item.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
        },
        parseStrings(pathsJson) { item ->
            require(item.startsWith('/') && item.length <= 4_096)
            require(item.none { it == '\u0000' || it == '\n' || it == '\r' })
            require(item.split('/').none { it == "." || it == ".." })
        }
    )

    private fun parseStrings(raw: String, validate: (String) -> Unit): Set<String> {
        require(raw.length <= MAX_JSON_CHARS)
        val array = JSONArray(raw)
        require(array.length() <= MAX_ENTRIES)
        return buildSet {
            for (index in 0 until array.length()) {
                val item = array.get(index) as? String ?: error("保护名单包含无效项目")
                require(item.none { it == '\u0000' || it == '\n' || it == '\r' })
                validate(item)
                add(item)
            }
        }
    }

    @Synchronized fun rememberRoot(context: Context, rules: ApkProtectionRules) {
        // Validate both halves together, even when a caller already decoded the response.
        val verified = parse(JSONArray(rules.packages.toList()).toString(), JSONArray(rules.paths.toList()).toString())
        if (preferences(context).getBoolean("rootEverUsed", false) &&
            !preferences(context).getBoolean("localOnly", false) && cached(context) == verified) return
        val cache = JSONObject().put("packages", JSONArray(verified.packages.sorted()))
            .put("paths", JSONArray(verified.paths.sorted())).put("observedAt", System.currentTimeMillis())
        check(preferences(context).edit().putBoolean("rootEverUsed", true).putBoolean("localOnly", false)
            .putString("verifiedRootRules", cache.toString()).commit()) { "无法保存保护设置状态" }
    }

    @Synchronized fun markRootUsed(context: Context) {
        if (preferences(context).getBoolean("rootEverUsed", false)) return
        check(preferences(context).edit().putBoolean("rootEverUsed", true).commit()) { "无法保存保护设置状态" }
    }

    fun rootWasUsed(context: Context): Boolean {
        val prefs = preferences(context)
        return prefs.getBoolean("rootEverUsed", false) || prefs.contains("verifiedRootRules") ||
            ConnectionDiagnostics.lastVersions(context)?.root?.let { it.name != null || it.code != null } == true
    }

    fun cached(context: Context): ApkProtectionRules? = runCatching {
        preferences(context).getString("verifiedRootRules", null)?.let { raw ->
            require(raw.length <= MAX_JSON_CHARS)
            val json = JSONObject(raw)
            parse(json.getJSONArray("packages").toString(), json.getJSONArray("paths").toString())
        }
    }.getOrNull()

    @Synchronized fun legacyRules(context: Context): ApkProtectionRules {
        LegacyProtectionRecovery.requireReviewed(context)
        val prefs = context.getSharedPreferences("baize_v2", Context.MODE_PRIVATE)
        return parse(JSONArray(prefs.getStringSet("package_whitelist", emptySet()).orEmpty().toList()).toString(),
            JSONArray(prefs.getStringSet("path_whitelist", emptySet()).orEmpty().toList()).toString())
    }

    /** Remove only explicitly selected legacy records. Never replace them with the Root snapshot. */
    @Synchronized fun removeLegacyRules(context: Context, packages: Set<String> = emptySet(), paths: Set<String> = emptySet()) {
        val before = legacyRules(context)
        val next = ApkProtectionRules(before.packages - packages, before.paths - paths)
        if (next == before) return
        val prefs = LegacyPreferencesAccess.preferences(context)
        if (!prefs.edit().putStringSet("package_whitelist", next.packages).putStringSet("path_whitelist", next.paths).commit()) {
            // SharedPreferences can update its in-memory map before reporting a disk failure.
            prefs.edit().putStringSet("package_whitelist", before.packages).putStringSet("path_whitelist", before.paths).commit()
            error("旧版保护保存失败，未确认移除，请刷新核对")
        }
    }

    fun readRoot(source: ApkProtectionSource): ApkProtectionRules {
        val response = JSONObject(source.snapshot())
        val uid = response.opt("uid")
        check(response.opt("root") == true && (uid == 0 || uid == 0L)) { "Root 保护服务未就绪" }
        check(response.optInt("version") == 1) { "Root 保护服务需要重新加载，请重连或重启手机后再试" }
        return parse(response.getJSONArray("packages").toString(), response.getJSONArray("paths").toString())
    }

    /** Explicit opt-in for a standalone user; never silently downgrade a historical Root user. */
    @Synchronized fun enableLocalOnly(context: Context): Boolean {
        if (rootWasUsed(context)) return false
        legacyRules(context) // Refuse unreadable local rules before changing the mode.
        return preferences(context).edit().putBoolean("localOnly", true).commit()
    }

    fun fallback(context: Context): ApkProtectionState = runCatching {
        val local = legacyRules(context)
        if (!rootWasUsed(context) && preferences(context).getBoolean("localOnly", false)) {
            ApkProtectionState.LocalOnly(local)
        } else {
            ApkProtectionState.Unknown("保护名单尚未核对，未删除文件。请连接 Root 后重试。", cached(context)?.plus(local))
        }
    }.getOrElse { ApkProtectionState.Unknown("保护设置暂不可读，已保留文件。${it.message.orEmpty().take(180)}", cached(context)) }

    /** Called on IO. Both rule halves are read under the Root repository lock; no module is required. */
    fun refresh(context: Context, source: ApkProtectionSource?): ApkProtectionState {
        val fallback = fallback(context)
        if (source == null) return fallback
        return try {
            markRootUsed(context)
            val rules = readRoot(source)
            rememberRoot(context, rules)
            ApkProtectionState.KnownRoot(rules.plus(legacyRules(context)))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            ApkProtectionState.Unknown("无法核对保护名单，已保留所选文件。${error.message.orEmpty().take(160)}", cached(context))
        }
    }
}

