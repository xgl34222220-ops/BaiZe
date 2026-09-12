package io.github.xgl34222220.baize.root

import android.os.IBinder
import org.json.JSONArray
import java.io.File

/** Keeps UI entry points on descriptor transport without changing their service contracts. */
internal object RootServiceClients {
    fun profile(binder: IBinder?, directory: File): IProfileRootService {
        val remote = requireNotNull(IProfileRootService.Stub.asInterface(binder))
        return object : IProfileRootService by remote {
            override fun scanProfile(profile: String?, optionsJson: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(profile.orEmpty()).put(optionsJson.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("scanProfile", request, response)
                }
            override fun getProfilePage(snapshotId: String?, offset: Int, limit: Int): String =
                JsonFileTransport.callInto(directory, JSONArray().put(snapshotId.orEmpty()).put(offset).put(limit)) { request, response ->
                    remote.exchangeJsonInto("getProfilePage", request, response)
                }
            override fun cleanProfileSelected(snapshotId: String?, selectionJson: String?, optionsJson: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(snapshotId.orEmpty()).put(selectionJson.orEmpty()).put(optionsJson.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("cleanProfileSelected", request, response)
                }
            override fun quarantineProfileSelected(snapshotId: String?, selectionJson: String?, optionsJson: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(snapshotId.orEmpty()).put(selectionJson.orEmpty()).put(optionsJson.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("quarantineProfileSelected", request, response)
                }
            override fun prepareCacheSelection(snapshotId: String?, selectionJson: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(snapshotId.orEmpty()).put(selectionJson.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("prepareCacheSelection", request, response)
                }
            override fun getQuarantinePage(offset: Int, limit: Int): String =
                JsonFileTransport.callInto(directory, JSONArray().put(offset).put(limit)) { request, response ->
                    remote.exchangeJsonInto("getQuarantinePage", request, response)
                }
            override fun getModuleState(): String =
                JsonFileTransport.callInto(directory, JSONArray()) { request, response ->
                    remote.exchangeJsonInto("getModuleState", request, response)
                }
            override fun getTaskHistory(limit: Int): String =
                JsonFileTransport.callInto(directory, JSONArray().put(limit)) { request, response ->
                    remote.exchangeJsonInto("getTaskHistory", request, response)
                }
            override fun getTaskHistoryPage(offset: Int, limit: Int): String =
                JsonFileTransport.callInto(directory, JSONArray().put(offset).put(limit)) { request, response ->
                    remote.exchangeJsonInto("getTaskHistoryPage", request, response)
                }
            override fun getAuditTimelinePage(offset: Int, limit: Int): String =
                JsonFileTransport.callInto(directory, JSONArray().put(offset).put(limit)) { request, response ->
                    remote.exchangeJsonInto("getAuditTimelinePage", request, response)
                }
            override fun getScanCoverage(): String =
                JsonFileTransport.callInto(directory, JSONArray()) { request, response ->
                    remote.exchangeJsonInto("getScanCoverage", request, response)
                }
            override fun clearPackageCaches(requestJson: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(requestJson.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("clearPackageCaches", request, response)
                }
            override fun scanFileOrganizer(): String =
                JsonFileTransport.callInto(directory, JSONArray()) { request, response ->
                    remote.exchangeJsonInto("scanFileOrganizer", request, response)
                }
            override fun applyFileOrganizer(snapshotId: String?, selectionJson: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(snapshotId.orEmpty()).put(selectionJson.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("applyFileOrganizer", request, response)
                }
            override fun undoFileOrganizer(): String =
                JsonFileTransport.callInto(directory, JSONArray()) { request, response ->
                    remote.exchangeJsonInto("undoFileOrganizer", request, response)
                }
            override fun getInstalledPackageCatalog(): String =
                JsonFileTransport.callInto(directory, JSONArray()) { request, response ->
                    remote.exchangeJsonInto("getInstalledPackageCatalog", request, response)
                }
            override fun getWhitelistPackages(): String =
                JsonFileTransport.callInto(directory, JSONArray()) { request, response ->
                    remote.exchangeJsonInto("getWhitelistPackages", request, response)
                }
            override fun saveWhitelistPackages(packagesJson: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(packagesJson.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("saveWhitelistPackages", request, response)
                }
            override fun getWhitelistPaths(): String =
                JsonFileTransport.callInto(directory, JSONArray()) { request, response ->
                    remote.exchangeJsonInto("getWhitelistPaths", request, response)
                }
        }
    }
    fun cache(binder: IBinder?, directory: File): IBaiZeRootService {
        val remote = requireNotNull(IBaiZeRootService.Stub.asInterface(binder))
        return object : IBaiZeRootService by remote {
            override fun scanCandidates(whitelistJson: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(whitelistJson.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("scanCandidates", request, response)
                }
            override fun getResultPage(snapshotId: String?, offset: Int, limit: Int): String =
                JsonFileTransport.callInto(directory, JSONArray().put(snapshotId.orEmpty()).put(offset).put(limit)) { request, response ->
                    remote.exchangeJsonInto("getResultPage", request, response)
                }
            override fun cleanSelected(snapshotId: String?, selectionJson: String?, whitelistJson: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(snapshotId.orEmpty()).put(selectionJson.orEmpty()).put(whitelistJson.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("cleanSelected", request, response)
                }
        }
    }
    fun persistent(binder: IBinder?, directory: File): IPersistentCleanPlanService {
        val remote = requireNotNull(IPersistentCleanPlanService.Stub.asInterface(binder))
        return object : IPersistentCleanPlanService by remote {
            override fun scanSafe(optionsJson: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(optionsJson.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("scanSafe", request, response)
                }
            override fun getPage(snapshotId: String?, offset: Int, limit: Int): String =
                JsonFileTransport.callInto(directory, JSONArray().put(snapshotId.orEmpty()).put(offset).put(limit)) { request, response ->
                    remote.exchangeJsonInto("getPage", request, response)
                }
            override fun cleanSafe(snapshotId: String?, selectionJson: String?, optionsJson: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(snapshotId.orEmpty()).put(selectionJson.orEmpty()).put(optionsJson.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("cleanSafe", request, response)
                }
        }
    }
    fun resume(binder: IBinder?, directory: File): ICleanPlanResumeService {
        val remote = requireNotNull(ICleanPlanResumeService.Stub.asInterface(binder))
        return object : ICleanPlanResumeService by remote {
            override fun begin(planId: String?, cacheSnapshotId: String?, safeSnapshotId: String?, cacheCount: Int, safeCount: Int): String =
                JsonFileTransport.callInto(directory, JSONArray().put(planId.orEmpty()).put(cacheSnapshotId.orEmpty()).put(safeSnapshotId.orEmpty()).put(cacheCount).put(safeCount)) { request, response ->
                    remote.exchangeJsonInto("begin", request, response)
                }
            override fun checkpointCache(planId: String?, resultJson: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(planId.orEmpty()).put(resultJson.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("checkpointCache", request, response)
                }
            override fun checkpointSafe(planId: String?, resultJson: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(planId.orEmpty()).put(resultJson.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("checkpointSafe", request, response)
                }
            override fun recover(planId: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(planId.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("recover", request, response)
                }
            override fun finish(planId: String?): String =
                JsonFileTransport.callInto(directory, JSONArray().put(planId.orEmpty())) { request, response ->
                    remote.exchangeJsonInto("finish", request, response)
                }
        }
    }
}
