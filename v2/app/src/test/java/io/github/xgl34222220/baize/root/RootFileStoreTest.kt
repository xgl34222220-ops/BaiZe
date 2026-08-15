package io.github.xgl34222220.baize.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * env 文件解析与原子写入。
 *
 * 这条链路是 App 与 Root 侧脚本之间的全部数据通道：脚本写 key=value，
 * App 读回来渲染界面。解析出错不会崩溃，只会静默显示错误数字，
 * 因此值得有明确的边界测试。
 */
class RootFileStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun envFile(content: String): File =
        folder.newFile().apply { writeText(content) }

    @Test
    fun `解析基本键值对`() {
        val json = RootFileStore.readEnv(envFile("files=12\nbytes=3456\nmode=deep-scan\n"))
        assertEquals(12L, json.getLong("files"))
        assertEquals(3456L, json.getLong("bytes"))
        assertEquals("deep-scan", json.getString("mode"))
    }

    @Test
    fun `数字被解析为 Long，非数字保持字符串`() {
        val json = RootFileStore.readEnv(envFile("count=42\nname=cache\nfloaty=1.5\n"))
        assertTrue(json.get("count") is Long)
        assertTrue(json.get("name") is String)
        // 1.5 不是合法 Long，应保持字符串而不是被截断成 1
        assertEquals("1.5", json.getString("floaty"))
    }

    @Test
    fun `忽略空行与注释`() {
        val json = RootFileStore.readEnv(
            envFile("# 这是注释\n\nfiles=1\n   \n# files=999\nbytes=2\n")
        )
        assertEquals(1L, json.getLong("files"))
        assertEquals(2L, json.getLong("bytes"))
        assertEquals(2, json.length())
    }

    @Test
    fun `忽略没有等号的行`() {
        val json = RootFileStore.readEnv(envFile("garbage\nfiles=1\n另一行乱码\n"))
        assertEquals(1, json.length())
        assertEquals(1L, json.getLong("files"))
    }

    @Test
    fun `键与值两侧空白被裁剪`() {
        val json = RootFileStore.readEnv(envFile("  files  =  7  \n"))
        assertEquals(7L, json.getLong("files"))
    }

    @Test
    fun `值中包含等号时只按第一个等号切分`() {
        val json = RootFileStore.readEnv(envFile("path=/data/a=b/c\n"))
        assertEquals("/data/a=b/c", json.getString("path"))
    }

    @Test
    fun `重复键取最后一次出现`() {
        // 脚本用追加方式更新状态，读取方必须看到最新值。
        val json = RootFileStore.readEnv(envFile("state=running\nstate=done\n"))
        assertEquals("done", json.getString("state"))
    }

    @Test
    fun `文件不存在时返回空对象而不是抛异常`() {
        val missing = File(folder.root, "does-not-exist.env")
        assertEquals(0, RootFileStore.readEnv(missing).length())
    }

    @Test
    fun `目录被当作文件传入时返回空对象`() {
        assertEquals(0, RootFileStore.readEnv(folder.root).length())
    }

    @Test
    fun `空文件返回空对象`() {
        assertEquals(0, RootFileStore.readEnv(envFile("")).length())
    }

    @Test
    fun `原子写入产生目标文件且不残留临时文件`() {
        val target = File(folder.root, "nested/dir/out.env")
        RootFileStore.writeAtomic(target, "files=3\n")
        assertTrue(target.isFile)
        assertEquals("files=3\n", target.readText())
        val leftovers = target.parentFile!!.listFiles()!!.filter { it.name.contains(".tmp.") }
        assertTrue("不应残留临时文件：$leftovers", leftovers.isEmpty())
    }

    @Test
    fun `原子写入覆盖已有内容`() {
        val target = File(folder.root, "out.env")
        RootFileStore.writeAtomic(target, "a=1\n")
        RootFileStore.writeAtomic(target, "b=2\n")
        assertEquals("b=2\n", target.readText())
    }

    @Test
    fun `写入后可被 readEnv 原样读回`() {
        val target = File(folder.root, "roundtrip.env")
        RootFileStore.writeAtomic(target, "files=9\nmode=cache-scan\n")
        val json = RootFileStore.readEnv(target)
        assertEquals(9L, json.getLong("files"))
        assertEquals("cache-scan", json.getString("mode"))
    }

    @Test
    fun `tailText 返回文件尾部且不超过上限`() {
        val file = envFile((1..500).joinToString("\n") { "line$it" })
        val tail = RootFileStore.tailText(file, 100)
        assertTrue(tail.length <= 100)
        assertTrue("应包含最后一行", tail.endsWith("line500"))
    }

    @Test
    fun `tailText 对短文件返回全部内容`() {
        val file = envFile("short")
        assertEquals("short", RootFileStore.tailText(file, 1000))
    }

    @Test
    fun `tailText 对非法输入返回空串而不抛异常`() {
        assertEquals("", RootFileStore.tailText(File(folder.root, "nope"), 100))
        assertEquals("", RootFileStore.tailText(envFile("abc"), 0))
        assertEquals("", RootFileStore.tailText(envFile("abc"), -5))
    }

    @Test
    fun `tailText 能处理多字节中文而不返回乱码前缀`() {
        // 日志里大量中文，按字节截断可能切碎一个字符。
        val file = envFile("垃圾清理完成，共释放 123 MB 空间")
        val tail = RootFileStore.tailText(file, 8)
        assertTrue(tail.length <= 8)
        assertFalse("不应出现替换字符", tail.startsWith("�"))
    }
}
