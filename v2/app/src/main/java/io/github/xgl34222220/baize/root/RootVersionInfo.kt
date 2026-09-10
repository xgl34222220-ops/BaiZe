package io.github.xgl34222220.baize.root

import io.github.xgl34222220.baize.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.Properties

internal object RootVersionInfo {
    fun putInto(json: JSONObject, moduleProp: File = File(RootPaths.MODULE_DIR, "module.prop")): JSONObject {
        val properties = runCatching {
            Properties().apply { moduleProp.reader().use { load(it) } }
        }.getOrNull()
        return json
            .put("rootVersionName", BuildConfig.VERSION_NAME)
            .put("rootVersionCode", BuildConfig.VERSION_CODE)
            .put("moduleName", properties?.getProperty("name")?.take(128) ?: JSONObject.NULL)
            .put("moduleVersionName", properties?.getProperty("version")?.take(128) ?: JSONObject.NULL)
            .put("moduleVersionCode", properties?.getProperty("versionCode")?.take(32) ?: JSONObject.NULL)
    }
}
