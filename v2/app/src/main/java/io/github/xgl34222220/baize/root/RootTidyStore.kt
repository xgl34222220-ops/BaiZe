package io.github.xgl34222220.baize.root

import io.github.xgl34222220.baize.RootDirectoryOrganizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 根目录整理规则与模块执行记录（固定文件名，记录由模块按 100 行上限轮转）。 */
internal object RootTidyStore {
    const val RULES = "root-tidy.rules"
    const val RESULT = "root-tidy.env"
    const val LOG = "logs/root-tidy.log"

    fun read(stateDir: File): String {
        val rules = RootDirectoryOrganizer.decodeRules(File(stateDir, RULES).takeIf { it.isFile }?.readText())
        val log = File(stateDir, LOG).takeIf { it.isFile && it.length() < 256 * 1024 }?.readLines().orEmpty().takeLast(30)
        return JSONObject().put("success", true)
            .put("rules", RootDirectoryOrganizer.encodeRules(rules))
            .put("result", File(stateDir, RESULT).takeIf { it.isFile && it.length() < 4096 }?.readText().orEmpty())
            .put("log", JSONArray(log)).toString()
    }

    /** 重新解析并重写，越界或格式不对的行直接丢弃；自动开关不在规则文件里生效。 */
    fun write(stateDir: File, raw: String): String {
        require(raw.length <= 64 * 1024) { "rules_too_large" }
        val rules = RootDirectoryOrganizer.decodeRules(raw).copy(auto = false)
        RootFileStore.writeAtomic(File(stateDir, RULES), RootDirectoryOrganizer.encodeRules(rules))
        return JSONObject().put("success", true).put("allow", rules.allow.size).put("block", rules.block.size).toString()
    }
}
