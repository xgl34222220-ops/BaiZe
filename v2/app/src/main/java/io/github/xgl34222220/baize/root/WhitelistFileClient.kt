package io.github.xgl34222220.baize.root

import org.json.JSONArray
import java.io.File

/** New operations use the existing caller-owned FD protocol; AIDL transaction IDs stay unchanged. */
internal object WhitelistFileClient {
    fun removePath(service: IProfileRootService, directory: File, path: String): String =
        JsonFileTransport.callInto(directory, JSONArray().put(path)) { request, response ->
            service.exchangeJsonInto("removeWhitelistPath", request, response)
        }

    fun updatePackages(service: IProfileRootService, directory: File, added: Set<String>, removed: Set<String>): String =
        JsonFileTransport.callInto(directory, JSONArray().put(JSONArray(added.sorted()).toString())
            .put(JSONArray(removed.sorted()).toString())) { request, response ->
            service.exchangeJsonInto("updateWhitelistPackages", request, response)
        }
}
