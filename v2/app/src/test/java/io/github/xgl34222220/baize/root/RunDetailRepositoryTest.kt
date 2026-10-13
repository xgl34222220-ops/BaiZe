package io.github.xgl34222220.baize.root

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** 记录详情页 Root 端：路径只从明细文件读取；受保护项需临时允许；删除进入隔离区并写回状态。 */
class RunDetailRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var state: File
    private lateinit var data: File

    private fun setUp(protectedRule: String? = null): RunDetailRepository {
        state = folder.newFolder("state").canonicalFile
        data = folder.newFolder("data").canonicalFile
        val whitelist = File(state, "whitelist.conf")
        whitelist.writeText(if (protectedRule != null) "$protectedRule\n" else "")
        return RunDetailRepository(state, QuarantineRepository(state, File(state, "config.conf")),
            WhitelistRepository(whitelist, File(state, "whitelist.packages"), emptyList()))
    }

    private fun writeDetail(id: String, vararg rows: String) {
        val dir = File(state, "run-details").apply { mkdirs() }
        File(dir, "$id.tsv").writeText((listOf("#baize-run-details\tv1\t$id\t0", "#counts\tfailed=1\tskipped=${rows.size - 1}\tlisted=${rows.size}") + rows)
            .joinToString("\n", postfix = "\n"))
    }

    @Test fun listsItemsWithPlainChineseReasonAndExistence() {
        val repository = setUp()
        val denied = File(data, "denied.log").apply { writeText("x") }
        writeDetail("r1", "failed\tdelete_failed\t规则垃圾\t1\t1\t${denied.path}\tPermission denied\t",
            "skipped\tslow\t规则垃圾\t1\t0\t${data.path}/gone\t\t", "skipped\tchanged\t规则垃圾\t3\t0\t候选状态已变化\t\t")
        val json = JSONObject(repository.readJson("r1"))
        assertTrue(json.toString(), json.getBoolean("success"))
        val items = json.getJSONArray("items")
        assertEquals(3, items.length())
        val first = items.getJSONObject(0)
        assertEquals("删除失败：没有权限（Permission denied）", first.getString("reasonText"))
        assertTrue(first.getBoolean("exists"))
        assertTrue(first.getBoolean("actionable"))
        assertEquals("目录太大或太慢，本次跳过", items.getJSONObject(1).getString("reasonText"))
        assertFalse(items.getJSONObject(1).getBoolean("exists"))
        // An aggregate row without a path is informational only.
        assertFalse(items.getJSONObject(2).getBoolean("actionable"))
    }

    @Test fun deleteMovesItemToQuarantineAndPersistsState() {
        val repository = setUp()
        val target = File(data, "cache.bin").apply { writeText("payload") }
        writeDetail("r2", "failed\tdelete_failed\t应用缓存\t1\t7\t${target.path}\tDevice or resource busy\t")
        val result = JSONObject(repository.deleteJson("r2", JSONArray().put(0).toString(), allowProtected = false, permanent = false))
        assertTrue(result.toString(), result.getBoolean("success"))
        assertEquals(1, result.getInt("removed"))
        assertFalse(target.exists())
        val item = JSONObject(repository.readJson("r2")).getJSONArray("items").getJSONObject(0)
        assertEquals("quarantined", item.getString("state"))
        assertFalse(item.getBoolean("actionable"))
        // A second request for the same row does nothing.
        assertEquals(0, JSONObject(repository.deleteJson("r2", "[0]", false, false)).getInt("removed"))
    }

    @Test fun protectedItemNeedsExplicitTemporaryAllowance() {
        val keep = folder.newFolder("keep").canonicalFile
        val repository = setUp(protectedRule = keep.path)
        val target = File(keep, "photo.tmp").apply { writeText("x") }
        writeDetail("r3", "skipped\tprotected\t路径保护\t1\t0\t${target.path}\t\t")
        val listed = JSONObject(repository.readJson("r3")).getJSONArray("items").getJSONObject(0)
        assertEquals(keep.path, listed.getString("protectionRule"))
        val refused = JSONObject(repository.deleteJson("r3", "[0]", allowProtected = false, permanent = false))
        assertEquals(1, refused.getInt("protected"))
        assertTrue(target.exists())
        val allowed = JSONObject(repository.deleteJson("r3", "[0]", allowProtected = true, permanent = false))
        assertEquals(1, allowed.getInt("removed"))
        assertFalse(target.exists())
        // The protection rule itself is unchanged.
        assertTrue(File(state, "whitelist.conf").readText().contains(keep.path))
    }

    @Test fun symlinksAndModuleDataAreNeverTouched() {
        val repository = setUp()
        val real = File(data, "real.bin").apply { writeText("x") }
        val link = File(data, "link.bin")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        writeDetail("r4", "failed\tdelete_failed\tx\t1\t0\t${link.path}\tPermission denied\t",
            "failed\tdelete_failed\tx\t1\t0\t/data/adb/baize-v2/config.conf\tPermission denied\t")
        val result = JSONObject(repository.deleteJson("r4", "[0,1]", allowProtected = true, permanent = true))
        assertEquals(0, result.getInt("removed"))
        assertTrue(real.exists()); assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun storageRootsAndWholeAppDirectoriesAreNeverOffered() {
        // A skipped "unreadable /data/media" or "slow Download" row is information only.
        listOf("/", "/data", "/data/media", "/data/media/0", "/data/media/0/", "/storage/emulated/0",
            "/sdcard/Download", "/storage/emulated/0/DCIM", "/data/media/0/Android/data", "/data/data",
            "/data/data/com.tencent.mm", "/data/user/0/com.tencent.mm", "/storage/1A2B-3C4D", "/data/user/10")
            .forEach { assertTrue(it, RunDetailRepository.isCriticalRoot(it)) }
        listOf("/data/media/0/Download/a.apk", "/storage/emulated/0/Android/data/com.example/cache",
            "/data/data/com.tencent.mm/cache", "/sdcard/Download/old", "/data/media/0/Tencent")
            .forEach { assertFalse(it, RunDetailRepository.isCriticalRoot(it)) }
    }

    @Test fun invalidOrExpiredDetailIdIsRejected() {
        val repository = setUp()
        assertTrue(JSONObject(repository.readJson("../history")).getBoolean("expired"))
        assertTrue(JSONObject(repository.readJson("does-not-exist")).getBoolean("expired"))
        assertFalse(JSONObject(repository.deleteJson("../x", "[0]", false, false)).getBoolean("success"))
    }

    @Test fun reasonTextsArePlainChinese() {
        assertEquals("在你的保护名单中", RunDetailReasons.skipText("protected"))
        assertEquals("目录无法完整读取，本次未处理", RunDetailReasons.skipText("unreadable"))
        assertEquals("文件正被占用（Device or resource busy）", RunDetailReasons.errnoText("Device or resource busy"))
    }
}
