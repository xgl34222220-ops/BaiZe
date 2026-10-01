package io.github.xgl34222220.baize.root

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Debug-only entry point for a disposable adb-root emulator; never packaged in a release APK. */
object CacheRootDeviceProbe {
    private var stage = "startup"

    @JvmStatic fun main(args: Array<String>) {
        val code = try {
            runProbe()
            0
        } catch (error: Throwable) {
            println(JSONObject().put("passed", false).put("stage", stage)
                .put("error", error.javaClass.name).put("message", error.message.orEmpty()).toString())
            error.printStackTrace(System.err)
            1
        }
        System.out.flush()
        System.err.flush()
        // Avoid Android's uncaught-exception handler hiding the original failure behind SIGKILL.
        kotlin.system.exitProcess(code)
    }

    private fun markStage(value: String) {
        stage = value
        println(JSONObject().put("event", "stage").put("stage", value).toString())
        System.out.flush()
    }

    private fun runProbe() {
        check(Process.myUid() == 0) { "This test requires the disposable emulator's adb root" }
        check(File("/system/bin/getprop").isFile)
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {
            "Synthetic root probe is restricted to the CI emulator"
        }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("systemMain").invoke(null)
        val system = threadClass.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        val context = system.createPackageContext("io.github.xgl34222220.baize", Context.CONTEXT_IGNORE_SECURITY)
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val first = File("/data/user/0/io.github.baize.synthetic.p$suffix.first")
        val second = File("/data/user/0/io.github.baize.synthetic.p$suffix.second")
        check(!first.exists() && !second.exists())
        val firstCache = File(first, "cache").apply { check(mkdirs()) }
        val secondCache = File(second, "cache").apply { check(mkdirs()) }
        val selectedFile = File(firstCache, "selected.tmp").apply { writeBytes(ByteArray(4096) { 7 }) }
        val unselectedFile = File(secondCache, "unselected.tmp").apply { writeBytes(ByteArray(8192) { 9 }) }
        val personalFile = File(first, "files/personal.txt").apply { parentFile!!.mkdirs(); writeText("preserve-personal") }
        val transport = File("/data/local/tmp/baize-probe-$suffix").apply { mkdirs() }
        val evidenceFixture = File("/data/media/0/Download/baize-file-evidence-$suffix")
        var evidenceFixtureOwned = false
        fun service(): IBaiZeRootService {
            val root = BaiZeRootService()
            // Attach only the Context surface; the production service and FD request dispatcher
            // run unchanged, while the test owns launching app_process as uid 0.
            ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(root, context)
            val delegate = IBaiZeRootService.Stub.asInterface(root.onBind(Intent()))
            // A local AIDL call does not parcel/dup descriptors like cross-process Binder.
            // Give the service its own descriptor objects so closing a received FD cannot
            // consume the client's still-owned request or response handles.
            val copiedDescriptors = object : IBaiZeRootService.Stub() {
                override fun ping() = delegate.ping()
                override fun scanCandidates(whitelistJson: String?) = delegate.scanCandidates(whitelistJson)
                override fun getResultPage(snapshotId: String?, offset: Int, limit: Int) = delegate.getResultPage(snapshotId, offset, limit)
                override fun cleanSelected(snapshotId: String?, selectionJson: String?, whitelistJson: String?) =
                    delegate.cleanSelected(snapshotId, selectionJson, whitelistJson)
                override fun getTaskState() = delegate.getTaskState()
                override fun cancelCurrentTask() = delegate.cancelCurrentTask()
                override fun exchangeJson(operation: String?, request: ParcelFileDescriptor?): ParcelFileDescriptor =
                    ParcelFileDescriptor.dup(requireNotNull(request).fileDescriptor).use { received ->
                        delegate.exchangeJson(operation, received).use { returned -> ParcelFileDescriptor.dup(returned.fileDescriptor) }
                    }
                override fun exchangeJsonInto(operation: String?, request: ParcelFileDescriptor?, response: ParcelFileDescriptor?): Int {
                    val source = requireNotNull(request)
                    val target = requireNotNull(response)
                    val acknowledgement = ParcelFileDescriptor.dup(source.fileDescriptor).use { receivedRequest ->
                        ParcelFileDescriptor.dup(target.fileDescriptor).use { receivedResponse ->
                            delegate.exchangeJsonInto(operation, receivedRequest, receivedResponse)
                        }
                    }
                    check(source.fileDescriptor.valid() && target.fileDescriptor.valid()) {
                        "Local receiver consumed client-owned descriptors"
                    }
                    return acknowledgement
                }
            }
            return RootServiceClients.cache(copiedDescriptors, transport)
        }
        try {
            var root = service()
            markStage("cache-scan")
            val scan = JSONObject(root.scanCandidates("[]"))
            check(!scan.has("error") && scan.optString("snapshotId").isNotBlank()) { scan.toString() }
            val token = scan.getString("snapshotId")
            val firstPath = firstCache.canonicalPath
            val secondPath = secondCache.canonicalPath
            val paths = linkedSetOf<String>()
            var offset = 0
            markStage("cache-pages")
            do {
                val page = JSONObject(root.getResultPage(token, offset, 100))
                val items = page.getJSONArray("items")
                for (index in 0 until items.length()) paths += items.getJSONObject(index).getString("path")
                offset += items.length()
            } while (items.length() > 0 && offset < page.getInt("total"))
            check(firstPath in paths && secondPath in paths) { "Synthetic candidates missing: $paths" }
            // The former Workbench route reads the module namespace and deterministically fails.
            markStage("legacy-snapshot-rejection")
            val former = JSONObject(CacheSelectionRepository().prepare(token, JSONObject().put(firstPath, true).toString()))
            check(!former.optBoolean("success")) { "Fixture unexpectedly has a matching legacy module snapshot" }
            check(former.optString("error") in setOf("snapshot_missing", "snapshot_changed")) { former.toString() }
            markStage("unknown-path-rejection")
            val unauthorized = JSONObject(root.cleanSelected(token, JSONObject().put(personalFile.canonicalPath, true).toString(), "[]"))
            check(!unauthorized.optBoolean("success") && personalFile.readText() == "preserve-personal")
            markStage("selected-cache-clean")
            val result = JSONObject(root.cleanSelected(token, JSONObject().put(firstPath, true).toString(), "[]"))
            check(result.optBoolean("success") && result.optLong("deletedBytes") == 4096L && result.optLong("deletedFiles") == 1L) { result.toString() }
            check(!selectedFile.exists() && unselectedFile.length() == 8192L && personalFile.readText() == "preserve-personal")
            check(firstCache.isDirectory) { "Cache root must survive contents cleaning" }
            markStage("service-recreation")
            root = service()
            val restored = JSONObject(root.getResultPage(token, 0, 100)).getJSONArray("items")
            check((0 until restored.length()).any { restored.getJSONObject(it).optString("path") == secondPath })
            markStage("remaining-cache-clean")
            val afterRestart = JSONObject(root.cleanSelected(token, JSONObject().put(secondPath, true).toString(), "[]"))
            check(afterRestart.optBoolean("success") && afterRestart.optLong("deletedBytes") == 8192L && !unselectedFile.exists()) { afterRestart.toString() }
            check(personalFile.readText() == "preserve-personal")
            markStage("apk-protection-fd-snapshot")
            val profileService = BaiZeProfileRootService()
            ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(profileService, context)
            val profileDelegate = IProfileRootService.Stub.asInterface(profileService.onBind(Intent()))
            val profileTransport = object : IProfileRootService by profileDelegate {
                override fun exchangeJsonInto(operation: String?, request: ParcelFileDescriptor?, response: ParcelFileDescriptor?): Int {
                    val source = requireNotNull(request)
                    val target = requireNotNull(response)
                    return ParcelFileDescriptor.dup(source.fileDescriptor).use { receivedRequest ->
                        ParcelFileDescriptor.dup(target.fileDescriptor).use { receivedResponse ->
                            profileDelegate.exchangeJsonInto(operation, receivedRequest, receivedResponse)
                        }
                    }.also { check(source.fileDescriptor.valid() && target.fileDescriptor.valid()) }
                }
            }
            val protectedFixture = first.canonicalPath
            markStage("apk-file-evidence-current-user")
            check(!evidenceFixture.exists() && evidenceFixture.mkdirs())
            evidenceFixtureOwned = true
            val evidenceFile = File(evidenceFixture, "proof.apk").apply { writeBytes(ByteArray(128) { 4 }) }
            val evidencePath = evidenceFile.path.replaceFirst("/data/media/0/", "/storage/emulated/0/")
            check(JSONObject(profileDelegate.ping()).getInt("apkFileEvidenceVersion") == 1)
            val evidence = JSONObject(RootServiceClients.profileExchange(profileTransport, transport, "getApkFileEvidence",
                org.json.JSONArray().put(evidencePath)))
            val proof = evidence.getJSONObject("identity")
            val actual = android.system.Os.lstat(evidenceFile.path)
            check(evidence.getBoolean("success") && evidence.getInt("uid") == 0 && evidence.getInt("requesterUid") == 0)
            check(evidence.getString("requestedPath") == evidencePath && proof.getLong("inode") == actual.st_ino &&
                proof.getLong("device") == actual.st_dev && proof.getLong("bytes") == 128L)
            val otherUser = JSONObject(RootServiceClients.profileExchange(profileTransport, transport, "getApkFileEvidence",
                org.json.JSONArray().put(evidencePath.replace("/emulated/0/", "/emulated/10/"))))
            check(!otherUser.getBoolean("success") && otherUser.getString("reason") == "outside_current_user_storage")
            check(evidenceFile.length() == 128L) { "Identity diagnostics must not mutate the APK" }
            markStage("media-refresh-public-path")
            val retainedMediaFile = File(evidenceFixture, "retained.apk").apply { writeBytes(ByteArray(64) { 8 }) }
            val retainedMediaPath = retainedMediaFile.path.replaceFirst("/data/media/0/", "/storage/emulated/0/")
            fun indexed(path: String): Boolean {
                check(path.startsWith("/storage/emulated/0/Download/baize-file-evidence-") && '\'' !in path)
                val output = File.createTempFile("media-query-", ".log", transport)
                val process = ProcessBuilder("/system/bin/content", "query", "--user", "0", "--uri", "content://media/external/file",
                    "--projection", "_id:_data", "--where", "_data='$path'").redirectErrorStream(true).redirectOutput(output).start()
                try {
                    check(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) { "Synthetic index query timed out" }
                    val text = output.readText()
                    check(process.exitValue() == 0 && ("Row:" in text || "No result found." in text)) { text }
                    return "Row:" in text
                } finally { if (process.isAlive) process.destroyForcibly(); output.delete() }
            }
            check(RootMediaScanCommand.scan(evidencePath, transport) && RootMediaScanCommand.scan(retainedMediaPath, transport))
            check(indexed(evidencePath) && indexed(retainedMediaPath)) { "Owned APK fixtures were not indexed" }
            android.system.Os.remove(evidenceFile.path)
            check(indexed(evidencePath)) { "Fixture must actually reproduce a stale index before refresh" }
            check(RootMediaScanCommand.scan(evidenceFile.path, transport)) { "Raw path refresh failed" }
            check(!indexed(evidencePath) && indexed(retainedMediaPath) && retainedMediaFile.length() == 64L) {
                "Refresh must remove only the deleted file's old index entry"
            }

            check(JSONObject(profileDelegate.addWhitelistPath(protectedFixture)).getBoolean("success"))
            try {
                val protection = JSONObject(RootServiceClients.profileExchange(profileTransport, transport, "getApkProtection"))
                check(protection.getInt("uid") == 0 && protection.getBoolean("root") && protection.getInt("version") == 1)
                val decoded = io.github.xgl34222220.baize.ApkProtectionStore.parse(
                    protection.getJSONArray("packages").toString(), protection.getJSONArray("paths").toString())
                check(protectedFixture in decoded.paths && personalFile.readText() == "preserve-personal")
            } finally {
                val removed = JSONObject(RootServiceClients.profileExchange(profileTransport, transport, "removeWhitelistPath",
                    org.json.JSONArray().put(protectedFixture)))
                check(removed.getBoolean("success"))
            }
            val rules = File(first, "synthetic-rules").apply { mkdir() }
            val low = File(first, "ordinary").apply { mkdir(); resolve("small.bin").writeBytes(ByteArray(128)) }
            val high = File(first, "user_data").apply { mkdir(); resolve("review.bin").writeBytes(ByteArray(256)) }
            File(rules, "deep.rules").writeText("${low.canonicalPath}\n${high.canonicalPath}\n")
            val engine = NativeProfileEngine(context, java.util.concurrent.atomic.AtomicBoolean(), ruleDirectory = rules)
            markStage("profile-scan")
            val profileScan = JSONObject(engine.scan("deep", "{}") {})
            val profileToken = profileScan.getString("snapshotId")
            val profileItems = JSONObject(engine.page(profileToken, 0, 20)).getJSONArray("items")
            val lowItem = (0 until profileItems.length()).map { profileItems.getJSONObject(it) }.single { it.getString("path") == low.canonicalPath }
            val highItem = (0 until profileItems.length()).map { profileItems.getJSONObject(it) }.single { it.getString("path") == high.canonicalPath }
            check(lowItem.getString("risk") == "low" && highItem.getString("risk") == "high")
            markStage("low-risk-clean")
            val lowResult = JSONObject(engine.clean(profileToken, JSONObject().put(lowItem.getString("id"), true).toString(), "{}") {})
            check(lowResult.getLong("deletedBytes") == 128L && lowResult.getString("remainingSnapshotId") == profileToken) { lowResult.toString() }
            check(high.resolve("review.bin").length() == 256L)
            markStage("high-risk-clean")
            val highResult = JSONObject(engine.clean(profileToken, JSONObject().put(highItem.getString("id"), true).toString(), "{\"allowHighRisk\":true}") {})
            check(highResult.getLong("deletedBytes") == 256L && !high.resolve("review.bin").exists()) { highResult.toString() }
            markStage("profile-frozen-file-manifest")
            val frozenRoot = File(first, "frozen-review").apply { check(mkdir()) }
            val reviewed = File(frozenRoot, "nested/old.bin").apply { parentFile.mkdirs(); writeBytes(ByteArray(96)) }
            File(rules, "deep.rules").writeText("${frozenRoot.canonicalPath}\n")
            val frozenEngine = NativeProfileEngine(context, java.util.concurrent.atomic.AtomicBoolean(), ruleDirectory = rules)
            val frozenScan = JSONObject(frozenEngine.scan("deep", "{}") {})
            val frozenToken = frozenScan.getString("snapshotId")
            val frozenItem = JSONObject(frozenEngine.page(frozenToken, 0, 20)).getJSONArray("items").getJSONObject(0)
            check(frozenItem.optString("blockedReason").isBlank()) { frozenItem.toString() }
            val savedTree = frozenEngine.persistentItems(frozenToken)!!.getJSONObject(0).getJSONObject("frozenTree")
            val added = File(frozenRoot, "nested/added-after-review.bin").apply { writeBytes(ByteArray(97)); setLastModified(1_500_000_000_000L) }
            val frozenSelected = JSONObject().put(frozenItem.getString("id"), true).toString()
            val frozenClean = JSONObject(frozenEngine.clean(frozenToken, frozenSelected, "{}") {})
            check(!reviewed.exists() && added.length() == 97L && frozenClean.getLong("deletedFiles") == 1L &&
                frozenClean.getInt("remainingCandidates") == 1) { frozenClean.toString() }
            val retried = JSONObject(frozenEngine.clean(frozenToken, frozenSelected, "{}") {})
            check(retried.getLong("deletedFiles") == 0L && added.length() == 97L) { retried.toString() }
            val restoredTree = FrozenReviewTree.fromJson(savedTree)
            val restoredClean = FrozenReviewTree.delete(restoredTree, false, 1024, java.util.concurrent.atomic.AtomicBoolean(), 5_000) { _, _ -> true }
            check(restoredClean.files == 0L && added.length() == 97L)
            val rewritten = File(first, "rewrite-mtime.bin").apply { writeBytes(ByteArray(128)) }
            val rewriteTree = FrozenReviewTree.capture(rewritten.toPath(), java.util.concurrent.atomic.AtomicBoolean(), 5_000)
            check(rewriteTree.complete) { rewriteTree.reason }
            val originalTime = rewritten.lastModified()
            val rewriteBefore = io.github.xgl34222220.baize.ApkFileReadDiagnostics.stat(rewritten.path)
            rewritten.writeBytes(ByteArray(128) { 9 }); check(rewritten.setLastModified(originalTime))
            val rewriteAfter = io.github.xgl34222220.baize.ApkFileReadDiagnostics.stat(rewritten.path)
            val rewriteClean = FrozenReviewTree.delete(rewriteTree, true, 1024, java.util.concurrent.atomic.AtomicBoolean(), 5_000) { _, _ -> true }
            check(rewriteClean.files == 0L && rewritten.readBytes().all { it == 9.toByte() }) {
                JSONObject().put("originalManifest", FrozenReviewTree.toJson(rewriteTree))
                    .put("before", rewriteBefore).put("after", rewriteAfter)
                    .put("deletedFiles", rewriteClean.files).put("reason", rewriteClean.reason).toString()
            }
            markStage("persisted-frozen-plan-service-recreation")
            val persistedRoot = File(first, "persisted-review").apply { check(mkdir()) }
            val persistedOld = File(persistedRoot, "reviewed.bin").apply { writeBytes(ByteArray(65)) }
            val persistedRules = File(first, "persisted-rules").apply { check(mkdir()) }
            File(persistedRules, "custom.rules").writeText("${persistedRoot.canonicalPath}|0\n")
            fun persistentService(testEngine: NativeProfileEngine? = null): IPersistentCleanPlanService {
                val service = PersistentCleanPlanRootService()
                ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(service, context)
                if (testEngine != null) PersistentCleanPlanRootService::class.java.getDeclaredField("engine\$delegate")
                    .apply { isAccessible = true }.set(service, lazyOf(testEngine))
                return IPersistentCleanPlanService.Stub.asInterface(service.onBind(Intent()))
            }
            val planningService = persistentService(NativeProfileEngine(context, java.util.concurrent.atomic.AtomicBoolean(),
                ruleDirectory = persistedRules, sharedRootOverride = emptyList()))
            val persistentScan = JSONObject(planningService.scanSafe("{}"))
            check(persistentScan.optBoolean("persisted")) { persistentScan.toString() }
            val persistentToken = persistentScan.getString("snapshotId")
            val ownedPlan = File(RootPaths.STATE_DIR, "profile-snapshots/$persistentToken.json")
            try {
                val persistentItem = JSONObject(planningService.getPage(persistentToken, 0, 20)).getJSONArray("items").getJSONObject(0)
                val unreviewed = File(persistedRoot, "after-restart.bin").apply { writeBytes(ByteArray(66)); setLastModified(1_500_000_000_000L) }
                val restoredService = persistentService()
                val visibleRestored = JSONObject(restoredService.getPage(persistentToken, 0, 20)).getJSONArray("items").getJSONObject(0)
                check(!visibleRestored.has("frozenTree"))
                val restoredResult = JSONObject(restoredService.cleanSafe(persistentToken,
                    JSONObject().put(persistentItem.getString("id"), true).toString(), "{}"))
                check(restoredResult.optBoolean("persistedFallback") && restoredResult.getLong("deletedFiles") == 1L &&
                    !persistedOld.exists() && unreviewed.length() == 66L && restoredResult.getInt("remainingCandidates") == 1) { restoredResult.toString() }
            } finally { ownedPlan.delete() }
            markStage("corpse-package-inventory-safety")
            val corpseRoot = File(first, "synthetic-corpse-guard").apply { mkdir() }
            val keptCorpse = File(corpseRoot, "Android/data/io.baize.synthetic.gone/keep.txt").apply {
                parentFile.mkdirs(); writeText("synthetic ownership must be verified")
            }
            val verifiedInventory = InstalledPackageInventory.fromEntries(listOf("android" to 1000, "installed.synthetic" to 10123))
            var unavailable = false
            var corpseUser = 0
            val corpseEngine = NativeProfileEngine(context, java.util.concurrent.atomic.AtomicBoolean(),
                ruleDirectory = rules, sharedRootOverride = listOf(corpseRoot),
                packageInventory = { if (unavailable) error("synthetic inventory loss") else verifiedInventory },
                corpseStorageUser = { corpseUser })
            val corpseScan = JSONObject(corpseEngine.scan("corpses", "{}") {})
            check(corpseScan.getInt("totalCandidates") == 1)
            val corpseToken = corpseScan.getString("snapshotId")
            val corpseItem = JSONObject(corpseEngine.page(corpseToken, 0, 20)).getJSONArray("items").getJSONObject(0)
            val corpseSelection = JSONObject().put(corpseItem.getString("id"), true).toString()
            unavailable = true
            check(JSONObject(corpseEngine.scan("corpses", "{}") {}).getString("error") == "package_inventory_unavailable")
            check(JSONObject(corpseEngine.clean(corpseToken, corpseSelection, "{\"allowHighRisk\":true}") {}).getString("error") == "package_inventory_unavailable")
            check(JSONObject(corpseEngine.quarantine(corpseToken, corpseSelection, "{}") {}).getString("error") == "package_inventory_unavailable")
            check(JSONObject(corpseEngine.page(corpseToken, 0, 20)).getJSONArray("items").length() == 1)
            unavailable = false
            corpseUser = 10
            check(JSONObject(corpseEngine.scan("corpses", "{}") {}).getString("error") == "package_inventory_unavailable")
            check(JSONObject(corpseEngine.clean(corpseToken, corpseSelection, "{\"allowHighRisk\":true}") {}).getString("error") == "package_inventory_unavailable")
            check(keptCorpse.readText() == "synthetic ownership must be verified")
            check(runCatching { InstalledPackageInventory.fromEntries(emptyList()) }.isFailure)
            val realInventory = runCatching { InstalledPackageInventory.read(context) }.getOrNull()
            markStage("empty-directory-selected-clean")
            val emptyRoot = File(first, "synthetic-empty-guard").apply { check(mkdir()) }
            val boundary = File(emptyRoot, (1..8).joinToString("/") { "level$it" }).apply { check(mkdirs()) }
            val nestedLeaf = File(emptyRoot, "nested/leaf").apply { check(mkdirs()) }
            val changed = File(emptyRoot, "changed-after-scan").apply { check(mkdir()) }
            val marker = File(emptyRoot, "keep/.nomedia").apply { parentFile.mkdirs(); writeText("") }
            val linkedDirectory = File(second, "link-target").apply { check(mkdir()) }
            val link = File(emptyRoot, "shortcut")
            java.nio.file.Files.createSymbolicLink(link.toPath(), linkedDirectory.toPath())
            val emptyEngine = NativeProfileEngine(context, java.util.concurrent.atomic.AtomicBoolean(),
                ruleDirectory = rules, sharedRootOverride = listOf(emptyRoot))
            val emptyScan = JSONObject(emptyEngine.scan("empty", "{}") {})
            check(!emptyScan.getBoolean("partial") && emptyScan.getInt("emptyDirs") == 3) { emptyScan.toString() }
            val emptyToken = emptyScan.getString("snapshotId")
            val emptyItems = JSONObject(emptyEngine.page(emptyToken, 0, 20)).getJSONArray("items")
            val emptySelection = JSONObject()
            for (index in 0 until emptyItems.length()) emptySelection.put(emptyItems.getJSONObject(index).getString("id"), true)
            val newContent = File(changed, "keep.txt").apply { writeText("created after preview") }
            val emptyClean = JSONObject(emptyEngine.clean(emptyToken, emptySelection.toString(), "{}") {})
            check(emptyClean.getLong("deletedDirectories") == 2L && emptyClean.getLong("deletedFiles") == 0L &&
                emptyClean.getLong("deletedBytes") == 0L) { emptyClean.toString() }
            check(!boundary.exists() && !nestedLeaf.exists() && boundary.parentFile.isDirectory && nestedLeaf.parentFile.isDirectory)
            check(newContent.readText() == "created after preview" && marker.isFile && link.exists() && linkedDirectory.isDirectory)
            val nextEmptyScan = JSONObject(emptyEngine.scan("empty", "{}") {})
            check(nextEmptyScan.getInt("emptyDirs") == 2) { nextEmptyScan.toString() }
            println(JSONObject().put("passed", true).put("uid", Process.myUid()).put("api", android.os.Build.VERSION.SDK_INT)
                .put("formerWorkbenchError", former.optString("error")).put("scanThenSelectedClean", true)
                .put("fdTransport", true).put("unselectedPreserved", true).put("unknownPathRejected", true)
                .put("localBinderDescriptorCopies", true).put("crossUidBinderValidated", false)
                .put("apkProtectionFdSnapshot", true)
                .put("rawPathMediaRefreshRemovesOnlyDeletedIndex", true)
                .put("apkReadOnlyEvidenceMatchesRootStat", true).put("apkEvidenceOtherUserRejected", true)
                .put("corpseUnknownInventoryPreserved", true).put("corpseCrossUserRejected", true)
                .put("corpseEmptyInventoryRejected", true).put("corpseReviewRetained", true)
                .put("actualRootPackageInventoryReadable", realInventory != null)
                .put("actualRootPackageInventoryUser", realInventory?.userId ?: -1)
                .put("emptyBoundarySelectedClean", true).put("emptyDirectoryCountSeparateFromFiles", true)
                .put("emptyChangedContentPreserved", true).put("emptyUnreviewedParentsPreserved", true)
                .put("emptyPlaceholderAndSymlinkPreserved", true).put("emptyParentsRequireNewPreview", true)
                .put("profileFrozenManifestRejectsNewBackdatedContent", true)
                .put("profilePartialReviewAndRetryRetained", true).put("persistedFrozenManifestPreservesScope", true)
                .put("profileRestoredMtimeRewritePreserved", true).put("persistedServiceRecreationKeepsOriginalFileScope", true)
                .put("serviceRecreationSelectedClean", true).put("deletedBytes", 12288)
                .put("lowThenHighSameSnapshot", true).put("profileDeletedBytes", 384).toString())
        } finally {
            // These three random, newly-created namespaces contain only this probe's fixtures.
            first.deleteRecursively(); second.deleteRecursively(); transport.deleteRecursively()
            if (evidenceFixtureOwned) evidenceFixture.deleteRecursively()
        }
    }
}
