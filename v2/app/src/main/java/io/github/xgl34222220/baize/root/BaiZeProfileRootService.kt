package io.github.xgl34222220.baize.root

import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONObject
import java.io.File

/**
 * Thin Binder facade for BaiZe's persistent Root process.
 *
 * Task locking/state lives in [TaskCoordinator]. Scheduler/configuration, history, diagnostics,
 * package catalog, whitelist and organizer logic are isolated so Binder routing stays stable.
 */
class BaiZeProfileRootService : RootService() {
    private val coordinator = TaskCoordinator()
    private val schedulerRepository = SchedulerRepository()
    private val diagnostics = DiagnosticRepository()
    private val historyRepository = HistoryRepository()
    private val packageCatalog = PackageCatalog()
    private val whitelistRepository = WhitelistRepository()
    private val cacheSelectionRepository = CacheSelectionRepository()
    private val moduleTasks = ModuleTaskController(coordinator, schedulerRepository, diagnostics)

    private val profileEngine by lazy { NativeProfileEngine(this, coordinator.cancelled) }
    private val instantCacheEngine by lazy {
        InstantCacheEngine(coordinator.cancelled) { coordinator.publishExternal(it) }
    }
    private val organizerController by lazy { OrganizerController(coordinator.cancelled) }

    private val binder = object : IProfileRootService.Stub() {
        override fun ping(): String = JSONObject()
            .put("uid", Process.myUid())
            .put("root", Process.myUid() == 0)
            .put("module", File(RootPaths.MODULE_DIR, "module.prop").isFile)
            .put("cleaner", File(RootPaths.MODULE_DIR, "cleaner.sh").isFile)
            .put("deepRules", File(RootPaths.MODULE_DIR, "config/deep.rules").isFile)
            .put("scheduler", File(RootPaths.MODULE_DIR, "scheduler.sh").isFile)
            .put("engine", "unified-root-task-coordinator-v2")
            .toString()

        override fun getProfileCatalog(): String = profileEngine.catalog()

        override fun scanProfile(profile: String?, optionsJson: String?): String =
            coordinator.runExclusive(
                operation = "profile-scan",
                phase = "正在扫描保护项",
                failureCode = "profile_scan_failed"
            ) { started ->
                profileEngine.scan(profile.orEmpty(), optionsJson.orEmpty()) { progress ->
                    coordinator.update(
                        operation = "profile-scan",
                        phase = progress.phase,
                        current = progress.current,
                        total = progress.total,
                        currentPath = progress.path,
                        startedRealtime = started,
                        deletedBytes = progress.bytes,
                        deletedFiles = progress.files,
                        failures = progress.failures
                    )
                }
            }

        override fun getProfilePage(snapshotId: String?, offset: Int, limit: Int): String {
            if (coordinator.isBusy()) return coordinator.busy("profile-page")
            coordinator.cancelled.set(false)
            return profileEngine.page(snapshotId.orEmpty(), offset, limit)
        }

        override fun cleanProfileSelected(
            snapshotId: String?,
            selectionJson: String?,
            optionsJson: String?
        ): String = coordinator.runExclusive(
            operation = "profile-clean",
            phase = "正在清理已选择项目",
            failureCode = "profile_clean_failed"
        ) { started ->
            profileEngine.clean(snapshotId.orEmpty(), selectionJson.orEmpty(), optionsJson.orEmpty()) { progress ->
                coordinator.update(
                    operation = "profile-clean",
                    phase = progress.phase,
                    current = progress.current,
                    total = progress.total,
                    currentPath = progress.path,
                    startedRealtime = started,
                    deletedBytes = progress.bytes,
                    deletedFiles = progress.files,
                    failures = progress.failures
                )
            }
        }

        override fun prepareCacheSelection(snapshotId: String?, selectionJson: String?): String =
            cacheSelectionRepository.prepare(snapshotId, selectionJson)

        override fun runMaintenanceTool(tool: String?, optionsJson: String?): String =
            diagnostics.runMaintenanceTool(tool, optionsJson)

        override fun runModuleTask(mode: String?): String {
            val normalized = mode.orEmpty().trim().lowercase()
            if (normalized.startsWith("scheduler-")) {
                return schedulerRepository.control(normalized)
            }
            if (normalized !in MODULE_TASKS) {
                return JSONObject().put("error", "unsupported_mode").put("mode", normalized).toString()
            }
            val phase = when (normalized) {
                "scan" -> "正在执行安全扫描"
                "organize" -> "正在启动文件归类"
                else -> "正在执行 Root 任务"
            }
            return coordinator.runExclusive(
                operation = "module-$normalized",
                phase = phase,
                failureCode = "module_task_failed"
            ) { started ->
                if (normalized in DETACHED_TASKS) {
                    moduleTasks.startDetachedModuleTask(normalized, started)
                } else {
                    moduleTasks.executeModuleTask(normalized, started)
                }
            }
        }

        override fun getModuleState(): String = moduleTasks.moduleState()
        override fun getTaskHistory(limit: Int): String = historyRepository.taskHistoryJson(limit)
        override fun getTaskHistoryPage(offset: Int, limit: Int): String =
            historyRepository.taskHistoryPageJson(offset, limit)
        override fun getScanCoverage(): String = diagnostics.scanCoverageJson()
        override fun clearTaskHistory(): String = historyRepository.clearTaskHistoryJson()
        override fun getRawLog(maxChars: Int): String = diagnostics.rawLogJson(maxChars)
        override fun clearRawLogs(): String = diagnostics.clearRawLogsJson()
        override fun recordNativeTask(taskJson: String?): String =
            historyRepository.recordNativeTaskJson(taskJson.orEmpty())
        override fun getSchedulerConfig(): String = schedulerRepository.configJson()
        override fun saveSchedulerConfig(configJson: String?): String =
            schedulerRepository.saveConfig(configJson.orEmpty())
        override fun resetScanWorkerProfile(): String = diagnostics.resetScanWorkerProfileJson()

        override fun clearPackageCaches(requestJson: String?): String = coordinator.runExclusive(
            operation = "instant-cache",
            phase = "正在清理应用缓存",
            failureCode = "instant_cache_failed"
        ) { started ->
            instantCacheEngine.run(requestJson.orEmpty(), started)
        }

        override fun scanFileOrganizer(): String = coordinator.runExclusive(
            operation = "file-organizer-scan",
            phase = "正在扫描可归类文件",
            failureCode = "file_organizer_scan_failed"
        ) { started ->
            organizerController.scan { progress ->
                coordinator.update(
                    operation = "file-organizer-scan",
                    phase = progress.phase,
                    current = progress.current,
                    total = progress.total,
                    currentPath = progress.path,
                    startedRealtime = started
                )
            }
        }

        override fun applyFileOrganizer(snapshotId: String?, selectionJson: String?): String =
            coordinator.runExclusive(
                operation = "file-organizer-apply",
                phase = "正在归类文件",
                failureCode = "file_organizer_apply_failed"
            ) { started ->
                organizerController.apply(snapshotId.orEmpty(), selectionJson.orEmpty()) { progress ->
                    coordinator.update(
                        operation = "file-organizer-apply",
                        phase = progress.phase,
                        current = progress.current,
                        total = progress.total,
                        currentPath = progress.path,
                        startedRealtime = started
                    )
                }
            }

        override fun undoFileOrganizer(): String = coordinator.runExclusive(
            operation = "file-organizer-undo",
            phase = "正在撤销上次归类",
            failureCode = "file_organizer_undo_failed"
        ) { started ->
            organizerController.undo { progress ->
                coordinator.update(
                    operation = "file-organizer-undo",
                    phase = progress.phase,
                    current = progress.current,
                    total = progress.total,
                    currentPath = progress.path,
                    startedRealtime = started
                )
            }
        }

        override fun getInstalledPackageCatalog(): String = packageCatalog.installedPackagesJson()
        override fun getWhitelistPackages(): String = whitelistRepository.packagesJson()
        override fun saveWhitelistPackages(packagesJson: String?): String =
            whitelistRepository.savePackages(packagesJson.orEmpty())
        override fun getWhitelistPaths(): String = whitelistRepository.pathsJson()
        override fun addWhitelistPath(path: String?): String = whitelistRepository.addPath(path)

        override fun getTaskState(): String = coordinator.currentState()
        override fun registerTaskProgressCallback(callback: ITaskProgressCallback?) = coordinator.register(callback)
        override fun unregisterTaskProgressCallback(callback: ITaskProgressCallback?) = coordinator.unregister(callback)
        override fun cancelCurrentTask() = coordinator.cancelCurrentTask()
    }

    override fun onBind(intent: Intent): IBinder = binder

    companion object {
        private val MODULE_TASKS = setOf(
            "scan", "clean", "cache-clean", "empty-clean", "rules-clean", "fragment-scan",
            "fragment-clean", "deep-scan", "deep-clean", "corpse-scan", "corpse-clean",
            "apk-scan", "apk-clean", "organize"
        )
        private val DETACHED_TASKS = setOf("clean", "organize")
    }
}
