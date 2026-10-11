package io.github.xgl34222220.baize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** 根目录整理：归属判断、标准目录保护、规则文件与“禁止重建”占位。 */
class RootDirectoryOrganizerTest {
    @get:Rule val temp = TemporaryFolder()
    private fun dir(name: String, empty: Boolean = false, bytes: Long = 10) = RootEntry(name, true, empty, if (empty) 0 else 1, if (empty) 0 else bytes)
    private val installed = setOf("com.tencent.mm", "com.sina.weibo", "com.example.notes")

    @Test fun standardDirectoriesAreAlwaysProtectedInAnyCase() {
        for (name in RootDirectoryOrganizer.STANDARD + listOf("dcim", "DOWNLOAD", "android")) {
            val review = RootDirectoryOrganizer.classify(dir(name, empty = true), installed, RootTidyRules(allow = setOf(name)))
            assertEquals(name, RootEntryKind.STANDARD, review.kind)
            assertFalse(review.removable); assertFalse(review.canBlock)
        }
        // 白泽自己的回收站 / 隔离区是内部目录：既不是整理候选，也不归入「受保护」。
        val trash = RootDirectoryOrganizer.classify(dir(".baize-file-trash"), installed, RootTidyRules())
        assertEquals(RootEntryKind.INTERNAL, trash.kind); assertEquals("白泽回收站（内部）", trash.ownerLabel)
        assertEquals(RootEntryKind.INTERNAL, RootDirectoryOrganizer.classify(dir(".BAIZE-QUARANTINE"), installed, RootTidyRules()).kind)
        assertEquals(RootEntryKind.PROTECTED, RootDirectoryOrganizer.classify(RootEntry("link", false, false, link = true), installed, RootTidyRules()).kind)
    }

    @Test fun ownershipComesFromRuleDbPackageNamesAndInstalledApps() {
        val wechat = RootDirectoryOrganizer.classify(dir("Tencent"), installed, RootTidyRules())
        assertEquals(RootEntryKind.OWNED, wechat.kind); assertTrue(wechat.sensitive)
        val weibo = RootDirectoryOrganizer.classify(dir("sina"), installed, RootTidyRules())
        assertEquals(RootEntryKind.OWNED, weibo.kind); assertEquals(listOf("com.sina.weibo"), weibo.installedOwners)
        val baidu = RootDirectoryOrganizer.classify(dir("baidu"), installed, RootTidyRules())
        assertEquals(RootEntryKind.ORPHAN, baidu.kind); assertTrue(baidu.removable)
        assertEquals(RootEntryKind.ORPHAN, RootDirectoryOrganizer.classify(dir("com.old.app"), installed, RootTidyRules()).kind)
        assertEquals(RootEntryKind.OWNED, RootDirectoryOrganizer.classify(dir("com.example.notes"), installed, RootTidyRules()).kind)
        // 包名末段匹配（至少 4 个字符）。
        assertEquals(RootEntryKind.OWNED, RootDirectoryOrganizer.classify(dir("Notes"), installed, RootTidyRules()).kind)
        assertEquals(RootEntryKind.UNKNOWN, RootDirectoryOrganizer.classify(dir("mm"), installed, RootTidyRules()).kind)
        assertEquals(RootEntryKind.UNKNOWN, RootDirectoryOrganizer.classify(dir("RandomStuff"), installed, RootTidyRules()).kind)
        // 隐藏的 SDK 目录按规则库识别。
        assertEquals("阿里系 SDK", RootDirectoryOrganizer.classify(dir(".UTSystemConfig"), installed, RootTidyRules()).ownerLabel)
    }

    @Test fun emptyWhitelistAndPlaceholderTakePrecedence() {
        assertEquals(RootEntryKind.EMPTY, RootDirectoryOrganizer.classify(dir("Tencent", empty = true), installed, RootTidyRules()).kind)
        assertEquals(RootEntryKind.WHITELISTED, RootDirectoryOrganizer.classify(dir("baidu"), installed, RootTidyRules(allow = setOf("BAIDU"))).kind)
        val placeholder = RootDirectoryOrganizer.classify(RootEntry("kugou", false, true), installed, RootTidyRules(block = setOf("kugou")))
        assertEquals(RootEntryKind.PLACEHOLDER, placeholder.kind); assertFalse(placeholder.removable)
        // 有内容的同名文件不是占位。
        assertEquals(RootEntryKind.ORPHAN, RootDirectoryOrganizer.classify(RootEntry("kugou", false, false, 1, 5), installed, RootTidyRules(block = setOf("kugou"))).kind)
        // 统计不完整的目录不能一键移除。
        assertFalse(RootDirectoryOrganizer.classify(dir("baidu").copy(limited = true), installed, RootTidyRules()).removable)
    }

    @Test fun sensitiveDirectoriesNeedExplicitAcknowledgementToBlock() {
        val wechat = RootDirectoryOrganizer.classify(dir("tencent", empty = true), installed, RootTidyRules())
        assertNotNull(RootDirectoryOrganizer.blockAllowed(wechat, acknowledgedSensitive = false))
        assertNull(RootDirectoryOrganizer.blockAllowed(wechat, acknowledgedSensitive = true))
        val baidu = RootDirectoryOrganizer.classify(dir("baidu", empty = true), installed, RootTidyRules())
        assertNull(RootDirectoryOrganizer.blockAllowed(baidu, false))
        val dcim = RootDirectoryOrganizer.classify(dir("DCIM", empty = true), installed, RootTidyRules())
        assertNotNull(RootDirectoryOrganizer.blockAllowed(dcim, true))
    }

    @Test fun rulesRoundTripAndRejectUnsafeNames() {
        val rules = RootTidyRules(allow = setOf("MyNotes"), block = setOf("baidu", "UCDownloads"))
        val decoded = RootDirectoryOrganizer.decodeRules(RootDirectoryOrganizer.encodeRules(rules))
        assertEquals(rules.allow, decoded.allow); assertEquals(rules.block, decoded.block); assertFalse(decoded.auto)
        val tampered = "auto=1\nblock|DCIM\nblock|../x\nblock|a/b\nallow|Android\nblock|ok|x\nblock|.baize-file-trash\nblock|good\n"
        val parsed = RootDirectoryOrganizer.decodeRules(tampered)
        assertEquals(setOf("good"), parsed.block); assertTrue(parsed.allow.isEmpty())
    }

    @Test fun placeholderCreateAndUndoNeverTouchContent() {
        val root = temp.newFolder("sdcard")
        File(root, "baidu").mkdir()
        assertEquals(RootTidyFiles.PlaceholderResult.CREATED, RootTidyFiles.createPlaceholder(root, "baidu"))
        assertTrue(File(root, "baidu").isFile); assertEquals(0L, File(root, "baidu").length())
        assertEquals(RootTidyFiles.PlaceholderResult.EXISTS, RootTidyFiles.createPlaceholder(root, "baidu"))
        // 应用无法再创建同名文件夹。
        assertFalse(File(root, "baidu").mkdir())
        assertTrue(RootTidyFiles.removePlaceholder(root, "baidu")); assertFalse(File(root, "baidu").exists())
        // 有内容的目录与文件都不会被替换或删除。
        File(root, "full").mkdir(); File(root, "full/a.txt").writeText("x")
        assertEquals(RootTidyFiles.PlaceholderResult.NOT_EMPTY, RootTidyFiles.createPlaceholder(root, "full"))
        assertTrue(File(root, "full/a.txt").isFile)
        File(root, "note").writeText("data")
        assertFalse(RootTidyFiles.removePlaceholder(root, "note")); assertEquals("data", File(root, "note").readText())
        assertEquals(RootTidyFiles.PlaceholderResult.INVALID, RootTidyFiles.createPlaceholder(root, "Download"))
        assertEquals(RootTidyFiles.PlaceholderResult.INVALID, RootTidyFiles.createPlaceholder(root, "../escape"))
    }

    @Test fun scanAndWalkStayOnTheFirstLevelAndSkipLinks() {
        val root = temp.newFolder("sd")
        File(root, "DCIM/Camera").mkdirs(); File(root, "DCIM/Camera/a.jpg").writeText("img")
        File(root, "empty").mkdir()
        File(root, "app/sub").mkdirs(); File(root, "app/sub/b.bin").writeBytes(ByteArray(7)); File(root, "app/c.bin").writeBytes(ByteArray(3))
        val outside = temp.newFolder("outside"); File(outside, "secret").writeText("s")
        Files.createSymbolicLink(File(root, "app/link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "toplink").toPath(), outside.toPath())
        val entries = RootTidyFiles.scan(root).associateBy { it.name }
        assertTrue(entries.getValue("empty").empty)
        assertTrue(entries.getValue("toplink").link)
        assertEquals(10L, entries.getValue("app").bytes); assertTrue(entries.getValue("app").link)
        assertEquals(0, entries.getValue("DCIM").files)
        val walk = RootTidyFiles.walk(File(root, "app"), 10)
        assertEquals(File(root, "app"), walk.directories.last())
        assertTrue(walk.directories.indexOf(File(root, "app/sub")) < walk.directories.indexOf(File(root, "app")))
        assertTrue(RootTidyFiles.walk(File(root, "app"), 1).limited)
        assertTrue(RootTidyFiles.removeEmptyDirectory(root, "empty"))
        assertFalse(RootTidyFiles.removeEmptyDirectory(root, "app"))
        assertTrue(File(outside, "secret").isFile)
    }

    @Test fun selectionOnlyRecommendsEmptyAndOrphanFolders() {
        val reviews = listOf(dir("baidu"), dir("empty", empty = true), dir("Random"), dir("DCIM"))
            .map { RootDirectoryOrganizer.classify(it, installed, RootTidyRules()) }
        val state = RootTidyUiState(reviews = reviews)
        assertEquals(setOf("baidu", "empty"), state.toggleAll().selected)
        assertEquals(setOf("Random"), state.toggle("Random").selected)
        assertTrue(state.toggle("DCIM").selected.isEmpty())
        assertTrue(state.copy(running = true).toggle("baidu").selected.isEmpty())
    }

    @Test fun everyOldEntryHasADestinationInsideTheUnifiedFlow() {
        val primary = LegacyEntryRedirects.PRIMARY_ENTRIES
        // 底部 4 个 Tab 不变：首页 / 清理 / 记录 / 设置。
        assertEquals(listOf("首页", "清理", "记录", "设置"), primary.keys.toList())
        // 去重：同一个入口不能出现在两个 Tab，也不能在一个 Tab 里出现两次。
        val all = primary.values.flatten()
        assertEquals(all.size, all.toSet().size)
        LegacyEntryRedirects.HOME_TOOL_DESTINATIONS.forEach { (old, destination) ->
            assertTrue("$old → $destination", primary.keys.any { destination.startsWith(it) })
        }
        for (tool in listOf("大文件", "重复文件", "截图与录屏", "旧下载", "聊天媒体", "安装包", "卸载残留", "根目录整理", "照片瘦身", "滑动整理"))
            assertTrue(tool, tool in primary.getValue("清理"))
        for (old in listOf("首页 · 微信专清 / QQ 专清", "设置 · 自动任务设置", "设置 · 保护名单", "清理 · 一键扫描", "即时缓存（InstantCacheActivity）"))
            assertTrue(old, old in LegacyEntryRedirects.HOME_TOOL_DESTINATIONS)
    }

    @Test fun reviewEstimatesUseDefaultAgesAndNeverCountDatabases() {
        val now = 1_800_000_000L
        fun record(path: String, mime: String, days: Int, bytes: Long = 100) = StorageFileRecord(1, "", path, path.substringAfterLast('/'), bytes, now - days * 86_400L, mime)
        val records = listOf(record("/storage/emulated/0/DCIM/Screenshots/Screenshot_1.png", "image/png", 40),
            record("/storage/emulated/0/DCIM/Screenshots/Screenshot_2.png", "image/png", 2),
            record("/storage/emulated/0/Download/old.zip", "application/zip", 120),
            record("/storage/emulated/0/Download/a.apk", "", 1, 500),
            record("/storage/emulated/0/Pictures/WeiXin/x.jpg", "image/jpeg", 100),
            record("/storage/emulated/0/Pictures/WeiXin/msg.db", "", 100))
        val estimates = ReviewSourceEstimates.estimate(records, now)
        assertEquals(ReviewEstimate(1, 100), estimates[ReviewSource.SCREENSHOTS])
        assertEquals(ReviewEstimate(1, 100), estimates[ReviewSource.OLD_DOWNLOADS])
        assertEquals(ReviewEstimate(1, 100), estimates[ReviewSource.CHAT_MEDIA])
        assertEquals(ReviewEstimate(1, 500), estimates[ReviewSource.APK])
    }

    @Test fun internalDirectoriesAreNeverCandidatesNorProtectedRows() {
        val reviews = listOf(dir(".baize-file-trash", bytes = 2048), dir(".baize-quarantine", empty = true), dir("baidu"))
            .map { RootDirectoryOrganizer.classify(it, installed, RootTidyRules()) }
        val state = RootTidyUiState(reviews = reviews)
        assertFalse(RootEntryKind.INTERNAL in ROOT_KIND_ORDER)
        assertEquals(listOf(".baize-file-trash", ".baize-quarantine"), state.internalDirs.map { it.entry.name })
        assertTrue(state.internalDirs.none { it.removable || it.canBlock })
        assertTrue(state.reviews.none { it.kind == RootEntryKind.PROTECTED })
        assertEquals(setOf("baidu"), state.toggleAll().selected)
        assertTrue(state.toggle(".baize-file-trash").selected.isEmpty())
        assertFalse(RootDirectoryOrganizer.ruleName(".baize-file-trash")); assertFalse(RootDirectoryOrganizer.ruleName(".Baize-Quarantine"))
        // 规则文件里出现内部目录也会被丢弃，模块不会对它放占位。
        assertTrue(RootDirectoryOrganizer.decodeRules("block|.baize-file-trash\nallow|.baize-quarantine").let { it.block.isEmpty() && it.allow.isEmpty() })
    }

    @Test fun internalTrashIsMeasuredInsteadOfShownAsZeroBytes() {
        val root = temp.newFolder("sd-internal")
        File(root, ".baize-file-trash/pkg").mkdirs(); File(root, ".baize-file-trash/pkg/.nomedia").createNewFile()
        File(root, ".baize-file-trash/pkg/payload").writeBytes(ByteArray(9))
        val entry = RootTidyFiles.scan(root).single { it.name == ".baize-file-trash" }
        assertEquals(9L, entry.bytes); assertFalse(entry.empty)
    }

    @Test fun removedFoldersRoundTripAndRecreatedGroupNeedsNoAutoAction() {
        var removed = RootDirectoryOrganizer.recordRemoved(emptyMap(), listOf("MiDrive", "baidu", "DCIM", ".baize-file-trash"), 1_000)
        assertEquals(setOf("midrive", "baidu"), removed.keys)
        removed = RootDirectoryOrganizer.recordRemoved(removed, listOf("midrive"), 2_000)
        assertEquals(2, removed.getValue("midrive").times)
        assertEquals(removed, RootDirectoryOrganizer.decodeRemoved(RootDirectoryOrganizer.encodeRemoved(removed)))
        assertTrue(RootDirectoryOrganizer.decodeRemoved("a|b|c\nDCIM|1|5\nx|0|5\n|1|1").isEmpty())

        val reviews = listOf(dir("MiDrive", empty = true).copy(modifiedSeconds = 3_000), dir("baidu").copy(modifiedSeconds = 500),
            dir("Random").copy(modifiedSeconds = 3_000), dir("DCIM"))
            .map { RootDirectoryOrganizer.classify(it, installed, RootTidyRules()) }
        val state = RootTidyUiState(reviews = reviews, removedFolders = removed)
        val recreated = state.recreated.associateBy { it.review.entry.name }
        assertEquals(setOf("MiDrive", "baidu"), recreated.keys)
        assertEquals(3, recreated.getValue("MiDrive").appearance)
        assertTrue(recreated.getValue("MiDrive").summary.startsWith("第 3 次出现 · "))
        assertEquals("百度系应用", recreated.getValue("baidu").owner)
        assertTrue(recreated.getValue("baidu").summary.contains("上次移除未生效"))
        assertTrue(RecreatedFolder(recreated.getValue("baidu").review, 2, "百度系应用", false).summary.contains("可能由 百度系应用 创建"))
        // 修改时间早于上次移除：说明移除没有真正生效，而不是被应用重建。
        assertTrue(recreated.getValue("baidu").removalIneffective)
        assertFalse(recreated.getValue("MiDrive").removalIneffective)
        // 忽略（加入不再整理）后不再出现在「被重新创建」里；只读计算不会选中或改动任何条目。
        val ignored = RootTidyUiState(reviews = reviews.map { RootDirectoryOrganizer.classify(it.entry, installed, RootTidyRules(allow = setOf("MiDrive"))) },
            removedFolders = removed)
        assertEquals(setOf("baidu"), ignored.recreatedNames)
        assertTrue(state.selected.isEmpty()); assertEquals(state.rules, RootTidyRules())

        assertEquals(listOf("baidu"), RootDirectoryOrganizer.previouslyRemoved(removed, listOf("BAIDU", "new")).map { it.lowercase() })
        assertEquals("已移除 3 项，文件在回收站保留 30 天。其中 2 个之前删过又被应用重建了，可以设为禁止重建", RootDirectoryOrganizer.removalMessage(3, 2))
        assertEquals("已移除 1 项，文件在回收站保留 30 天", RootDirectoryOrganizer.removalMessage(1, 0))
        val undone = RootDirectoryOrganizer.forgetRemoved(removed, listOf("MiDrive", "baidu"))
        assertEquals(1, undone.getValue("midrive").times); assertFalse("baidu" in undone)
    }

    @Test fun removalIsVerifiedOnDiskAndUndoCountsTopLevelEntries() {
        val root = temp.newFolder("sd-gone")
        File(root, "gone").mkdir(); File(root, "kept/sub").mkdirs()
        assertTrue(RootTidyFiles.removeEmptyDirectory(root, "gone"))
        assertTrue(RootTidyFiles.gone(root, "gone"))
        assertFalse(RootTidyFiles.gone(root, "kept"))
        assertFalse(RootTidyFiles.gone(root, "../x"))
        // 一个文件夹含子目录时，提示数字按根目录条目算，而不是把子目录也算进去。
        val undo = RootTidyUndo(directories = listOf("/a/sub", "/a", "/b"), names = listOf("a", "b"))
        assertEquals(2, undo.entries); assertEquals(3, undo.count)
    }
}
