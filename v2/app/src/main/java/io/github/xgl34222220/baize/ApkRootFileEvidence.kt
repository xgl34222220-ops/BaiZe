package io.github.xgl34222220.baize

import org.json.JSONObject

/** App-owned Root service capability; independent of the installed scheduling module version. */
internal object ApkRootFileEvidence {
    fun supported(raw: String): Boolean = runCatching { JSONObject(raw).let {
        it.optInt("uid", -1) == 0 && it.optBoolean("root") && it.optInt("apkFileEvidenceVersion") == 1
    } }.getOrDefault(false)
}
