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
                JsonFileTransport.call(directory, JSONArray().put(profile.orEmpty()).put(optionsJson.orEmpty())) { request ->
                    remote.exchangeJson("scanProfile", request)
                }
            override fun getProfilePage(snapshotId: String?, offset: Int, limit: Int): String =
                JsonFileTransport.call(directory, JSONArray().put(snapshotId.orEmpty()).put(offset).put(limit)) { request ->
                    remote.exchangeJson("getProfilePage", request)
                }
            override fun cleanProfileSelected(snapshotId: String?, selectionJson: String?, optionsJson: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(snapshotId.orEmpty()).put(selectionJson.orEmpty()).put(optionsJson.orEmpty())) { request ->
                    remote.exchangeJson("cleanProfileSelected", request)
                }
            override fun quarantineProfileSelected(snapshotId: String?, selectionJson: String?, optionsJson: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(snapshotId.orEmpty()).put(selectionJson.orEmpty()).put(optionsJson.orEmpty())) { request ->
                    remote.exchangeJson("quarantineProfileSelected", request)
                }
            override fun prepareCacheSelection(snapshotId: String?, selectionJson: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(snapshotId.orEmpty()).put(selectionJson.orEmpty())) { request ->
                    remote.exchangeJson("prepareCacheSelection", request)
                }
            override fun getQuarantinePage(offset: Int, limit: Int): String =
                JsonFileTransport.call(directory, JSONArray().put(offset).put(limit)) { request ->
                    remote.exchangeJson("getQuarantinePage", request)
                }
            override fun getModuleState(): String =
                JsonFileTransport.call(directory, JSONArray()) { request ->
                    remote.exchangeJson("getModuleState", request)
                }
            override fun getTaskHistory(limit: Int): String =
                JsonFileTransport.call(directory, JSONArray().put(limit)) { request ->
                    remote.exchangeJson("getTaskHistory", request)
                }
            override fun getTaskHistoryPage(offset: Int, limit: Int): String =
                JsonFileTransport.call(directory, JSONArray().put(offset).put(limit)) { request ->
                    remote.exchangeJson("getTaskHistoryPage", request)
                }
            override fun getAuditTimelinePage(offset: Int, limit: Int): String =
                JsonFileTransport.call(directory, JSONArray().put(offset).put(limit)) { request ->
                    remote.exchangeJson("getAuditTimelinePage", request)
                }
            override fun getScanCoverage(): String =
                JsonFileTransport.call(directory, JSONArray()) { request ->
                    remote.exchangeJson("getScanCoverage", request)
                }
            override fun clearPackageCaches(requestJson: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(requestJson.orEmpty())) { request ->
                    remote.exchangeJson("clearPackageCaches", request)
                }
            override fun scanFileOrganizer(): String =
                JsonFileTransport.call(directory, JSONArray()) { request ->
                    remote.exchangeJson("scanFileOrganizer", request)
                }
            override fun applyFileOrganizer(snapshotId: String?, selectionJson: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(snapshotId.orEmpty()).put(selectionJson.orEmpty())) { request ->
                    remote.exchangeJson("applyFileOrganizer", request)
                }
            override fun undoFileOrganizer(): String =
                JsonFileTransport.call(directory, JSONArray()) { request ->
                    remote.exchangeJson("undoFileOrganizer", request)
                }
            override fun getInstalledPackageCatalog(): String =
                JsonFileTransport.call(directory, JSONArray()) { request ->
                    remote.exchangeJson("getInstalledPackageCatalog", request)
                }
            override fun getWhitelistPackages(): String =
                JsonFileTransport.call(directory, JSONArray()) { request ->
                    remote.exchangeJson("getWhitelistPackages", request)
                }
            override fun saveWhitelistPackages(packagesJson: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(packagesJson.orEmpty())) { request ->
                    remote.exchangeJson("saveWhitelistPackages", request)
                }
            override fun getWhitelistPaths(): String =
                JsonFileTransport.call(directory, JSONArray()) { request ->
                    remote.exchangeJson("getWhitelistPaths", request)
                }
        }
    }
    fun cache(binder: IBinder?, directory: File): IBaiZeRootService {
        val remote = requireNotNull(IBaiZeRootService.Stub.asInterface(binder))
        return object : IBaiZeRootService by remote {
            override fun scanCandidates(whitelistJson: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(whitelistJson.orEmpty())) { request ->
                    remote.exchangeJson("scanCandidates", request)
                }
            override fun getResultPage(snapshotId: String?, offset: Int, limit: Int): String =
                JsonFileTransport.call(directory, JSONArray().put(snapshotId.orEmpty()).put(offset).put(limit)) { request ->
                    remote.exchangeJson("getResultPage", request)
                }
            override fun cleanSelected(snapshotId: String?, selectionJson: String?, whitelistJson: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(snapshotId.orEmpty()).put(selectionJson.orEmpty()).put(whitelistJson.orEmpty())) { request ->
                    remote.exchangeJson("cleanSelected", request)
                }
        }
    }
    fun persistent(binder: IBinder?, directory: File): IPersistentCleanPlanService {
        val remote = requireNotNull(IPersistentCleanPlanService.Stub.asInterface(binder))
        return object : IPersistentCleanPlanService by remote {
            override fun scanSafe(optionsJson: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(optionsJson.orEmpty())) { request ->
                    remote.exchangeJson("scanSafe", request)
                }
            override fun getPage(snapshotId: String?, offset: Int, limit: Int): String =
                JsonFileTransport.call(directory, JSONArray().put(snapshotId.orEmpty()).put(offset).put(limit)) { request ->
                    remote.exchangeJson("getPage", request)
                }
            override fun cleanSafe(snapshotId: String?, selectionJson: String?, optionsJson: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(snapshotId.orEmpty()).put(selectionJson.orEmpty()).put(optionsJson.orEmpty())) { request ->
                    remote.exchangeJson("cleanSafe", request)
                }
        }
    }
    fun resume(binder: IBinder?, directory: File): ICleanPlanResumeService {
        val remote = requireNotNull(ICleanPlanResumeService.Stub.asInterface(binder))
        return object : ICleanPlanResumeService by remote {
            override fun begin(planId: String?, cacheSnapshotId: String?, safeSnapshotId: String?, cacheCount: Int, safeCount: Int): String =
                JsonFileTransport.call(directory, JSONArray().put(planId.orEmpty()).put(cacheSnapshotId.orEmpty()).put(safeSnapshotId.orEmpty()).put(cacheCount).put(safeCount)) { request ->
                    remote.exchangeJson("begin", request)
                }
            override fun checkpointCache(planId: String?, resultJson: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(planId.orEmpty()).put(resultJson.orEmpty())) { request ->
                    remote.exchangeJson("checkpointCache", request)
                }
            override fun checkpointSafe(planId: String?, resultJson: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(planId.orEmpty()).put(resultJson.orEmpty())) { request ->
                    remote.exchangeJson("checkpointSafe", request)
                }
            override fun recover(planId: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(planId.orEmpty())) { request ->
                    remote.exchangeJson("recover", request)
                }
            override fun finish(planId: String?): String =
                JsonFileTransport.call(directory, JSONArray().put(planId.orEmpty())) { request ->
                    remote.exchangeJson("finish", request)
                }
        }
    }
}
