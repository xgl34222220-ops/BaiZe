package io.github.xgl34222220.baize.root

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Rule-only updates from BaiZe's own repository, with bounded reads, digest validation and rollback. */
internal class ToolboxRuleUpdates(private val context: Context) {
    fun state(): JSONObject {
        val dir = AppRuleStore.ensure(context)
        return JSONObject().put("metadata", RootFileStore.readEnv(File(dir, "rules.meta.env")))
            .put("override", ToolboxConfig.read(File(dir, "override.json")))
            .put("rollbackAvailable", File(dir, "deep.rules.previous").isFile)
    }
    fun update(): JSONObject = synchronized(AppRuleStore) {
        val root = AppRuleStore.ensure(context)
        val base = "https://raw.githubusercontent.com/xgl34222220-ops/BaiZe/main/config/"
        val metadata = fetch(base + "rules.meta.env", 16_000).toString(Charsets.UTF_8)
        val digest = metadata.lineSequence().firstOrNull { it.startsWith("rules_sha256=") }?.substringAfter('=')?.trim().orEmpty()
        require(Regex("[a-fA-F0-9]{64}").matches(digest)) { "云端规则缺少有效 SHA-256" }
        val bytes = fetch(base + "deep.rules", 3_000_000)
        require(sha(bytes).equals(digest, true)) { "云端规则校验失败，当前规则未替换" }
        val text = bytes.toString(Charsets.UTF_8)
        require(!text.contains('\u0000') && text.lineSequence().count { it.trim().startsWith('/') } in 100..20000) { "规则格式无效" }
        val current = File(root, "deep.rules")
        if (sha(current.readBytes()) == sha(bytes)) return JSONObject().put("success", true).put("message", "已是当前规则，无需更新")
        RootFileStore.writeAtomic(File(root, "deep.rules.previous"), current.readText())
        RootFileStore.writeAtomic(File(root, "rules.meta.previous"), File(root, "rules.meta.env").readText())
        RootFileStore.writeAtomic(File(root, "deep.rules"), text)
        RootFileStore.writeAtomic(File(root, "override.json"), JSONObject().put("sha256", sha(bytes))
            .put("updatedAt", System.currentTimeMillis()).put("source", base).toString())
        RootFileStore.writeAtomic(File(root, "rules.meta.env"), metadata)
        return JSONObject().put("success", true).put("message", "前台规则已更新，重新扫描即可生效")
    }
    fun rollback(): JSONObject = synchronized(AppRuleStore) {
        val root = AppRuleStore.ensure(context)
        val previous = File(root, "deep.rules.previous")
        require(previous.isFile) { "没有可回退的规则版本" }
        val bytes = previous.readBytes()
        RootFileStore.writeAtomic(File(root, "deep.rules"), bytes.toString(Charsets.UTF_8))
        RootFileStore.writeAtomic(File(root, "override.json"), JSONObject().put("sha256", sha(bytes))
            .put("updatedAt", System.currentTimeMillis()).put("source", "rollback").toString())
        val meta = File(root, "rules.meta.previous")
        if (meta.isFile) { RootFileStore.writeAtomic(File(root, "rules.meta.env"), meta.readText()); meta.delete() }
        previous.delete()
        return JSONObject().put("success", true).put("message", "已回退规则，重新扫描即可生效")
    }
    private fun fetch(address: String, limit: Int): ByteArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000; connection.readTimeout = 15000
        connection.instanceFollowRedirects = false
        return try {
            require(connection.responseCode == 200) { "规则下载失败：HTTP ${connection.responseCode}" }
            connection.inputStream.use { input -> input.readBytesLimited(limit) }
        } finally { connection.disconnect() }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer)
            if (read < 0) return output.toByteArray()
            require(output.size() + read <= limit) { "规则文件超过大小限制" }
            output.write(buffer, 0, read)
        }
    }
    companion object {
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun overrideValid(root: File): Boolean = runCatching {
            val expected = ToolboxConfig.read(File(root, "override.json")).optString("sha256")
            expected.length == 64 && File(root, "deep.rules").let { it.length() <= 3_000_000 && sha(it.readBytes()) == expected }
        }.getOrDefault(false)
    }
}
