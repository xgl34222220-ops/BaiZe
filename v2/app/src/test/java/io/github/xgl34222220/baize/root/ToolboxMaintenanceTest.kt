package io.github.xgl34222220.baize.root

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Exercise actual maintenance methods against temporary files and observed command responses.
 * Platform commands are simulated; these checks do not establish support on a physical ROM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ToolboxMaintenanceTest {
    @get:Rule val folder = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication<Application>()
    private val pkg = "com.baizetest.example"
    private lateinit var proc: File
    private lateinit var data: File
    private lateinit var sys: File
    private lateinit var state: File
    private lateinit var groups: File
    private val cancelled = AtomicBoolean()
    private val commands = RecordingCommand()
    private fun write(base: File, path: String, text: String): File = File(base, path).apply { parentFile!!.mkdirs(); writeText(text) }
    @Before fun setup() {
        proc = folder.newFolder("proc"); data = folder.newFolder("data"); sys = folder.newFolder("f2fs")
        state = folder.newFolder("state"); groups = folder.newFolder("groups")
        write(proc, "meminfo", "MemTotal: 100000 kB\nMemAvailable: 1000 kB\n")
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; uid = 10001; flags = 0 }
        })
    }
    private fun engine() = ToolboxMaintenance(app, cancelled, {}, proc, sys, commands, data, state, groups, "/bin/sh")
    private fun settings() = ToolboxConfig.normalize(JSONObject()).put("processPackages", pkg)
        .put("compilePackages", pkg).put("databasePackages", pkg).put("memoryThreshold", 10)
    private fun process() {
        write(proc, "123/cmdline", pkg + '\u0000')
        write(proc, "123/oom_score_adj", "900")
        write(proc, "123/status", "Uid:\t10001\t10001\t10001\t10001\n")
        write(proc, "123/stat", "123 ($pkg) " + (0..19).joinToString(" ") { if (it == 19) "42" else "0" })
        write(proc, "123/cgroup", "0::/uid_10001/pid_123\n")
        write(groups, "uid_10001/pid_123/cgroup.freeze", "0")
    }
    @Test fun pageCacheRequestWritesInterfaceAfterSyncWithoutClaimingDeletedBytes() {
        val node = write(proc, "sys/vm/drop_caches", "0")
        val result = engine().run("memory", settings())
        assertTrue(result.getBoolean("success")); assertTrue(result.getBoolean("requestedOnly"))
        assertEquals("1", node.readText().trim())
        assertEquals(listOf("/system/bin/sync"), commands.calls.single())
        assertFalse(result.has("deletedBytes"))
    }
    @Test fun oomAdjustmentAndRestoreChangeTheSameObservedProcess() {
        process()
        val engine = engine()
        val result = engine.run("process", settings().put("processMode", "oom"))
        assertTrue(result.toString(), result.getBoolean("success")); assertEquals(1, result.getInt("applied"))
        assertEquals("1000", File(proc, "123/oom_score_adj").readText().trim())
        assertTrue(engine.run("thaw", settings()).getBoolean("success"))
        assertEquals("900", File(proc, "123/oom_score_adj").readText().trim())
    }
    @Test fun freezeRequiresObservedStateAndCanBeRestored() {
        process()
        val node = File(groups, "uid_10001/pid_123/cgroup.freeze")
        commands.respond = { args ->
            when (args.getOrNull(1)) { "freeze" -> node.writeText("1"); "unfreeze" -> node.writeText("0") }
            ToolboxCommand.Result(0, if (args.last() == "help") "freeze [--sticky] PID" else "", false, false)
        }
        val engine = engine()
        assertTrue(engine.run("process", settings().put("processMode", "freeze")).getBoolean("success"))
        assertEquals("1", node.readText())
        assertTrue(engine.run("thaw", settings()).getBoolean("success"))
        assertEquals("0", node.readText())
    }
    @Test fun killIsOnlyARequestAndForegroundProcessesAreSkipped() {
        process()
        val requested = engine().run("process", settings())
        assertEquals("已提交请求", ToolboxAvailability.status(requested))
        assertTrue(File(proc, "123/cmdline").exists())
        File(proc, "123/oom_score_adj").writeText("100")
        commands.calls.clear()
        assertEquals("已跳过", ToolboxAvailability.status(engine().run("process", settings())))
        assertTrue(commands.calls.isEmpty())
    }
    @Test fun artFailureTextCannotBeReportedAsCompilationComplete() {
        commands.respond = { ToolboxCommand.Result(0, "Failure [unsupported compiler filter]", false, false) }
        val result = engine().run("dex2", settings())
        assertFalse(result.getBoolean("success")); assertEquals(1, result.getInt("failures"))
        assertTrue(result.getJSONArray("details").getJSONObject(0).getString("message").contains("Failure"))
    }
    @Test fun idleSqliteDatabaseIsOpenedAndActiveDatabaseIsSkipped() {
        val db = File(data, "user/0/$pkg/databases/main.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(db, null).use { it.execSQL("CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT)"); it.execSQL("INSERT INTO sample VALUES (1, 'keep')") }
        val result = engine().run("database", settings().put("vacuum", true))
        assertTrue(result.toString(), result.getBoolean("success")); assertEquals(1, result.getInt("optimized"))
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT value FROM sample WHERE id=1", null).use { assertTrue(it.moveToFirst()); assertEquals("keep", it.getString(0)) }
        }
        process()
        val skipped = engine().run("database", settings())
        assertEquals(0, skipped.getInt("optimized")); assertEquals("已跳过", ToolboxAvailability.status(skipped))
    }
    @Test fun gcRestoresOriginalKernelSettingAndThresholdSkipsWork() {
        val node = write(sys, "disk/gc_urgent", "0")
        write(sys, "disk/dirty_segments", "10")
        val config = settings().put("dirtyThreshold", 0).put("gcSeconds", 1)
        val result = engine().run("dirty", config)
        assertTrue(result.toString(), result.getBoolean("success"))
        assertTrue(result.getJSONArray("details").getJSONObject(0).getBoolean("restored"))
        assertEquals("0", node.readText().trim())
        assertFalse(File(state, "toolbox/gc-disk.restore").exists())
        assertEquals("已跳过", ToolboxAvailability.status(engine().run("dirty", config.put("dirtyThreshold", 10))))
    }
    @Test fun failedSystemCommandIsPreservedInTheResult() {
        commands.respond = { ToolboxCommand.Result(1, "Permission denied", false, false) }
        val result = engine().run("logcat", settings())
        assertFalse(result.getBoolean("success")); assertEquals(1, result.getInt("exitCode"))
        assertEquals("Permission denied", result.getString("message"))
    }
    private class RecordingCommand : ToolboxCommand(AtomicBoolean()) {
        val calls = mutableListOf<List<String>>()
        var respond: (List<String>) -> Result = { Result(0, "", false, false) }
        override fun run(arguments: List<String>, seconds: Long, honourCancel: Boolean, outputLimit: Int): Result {
            calls += arguments.toList(); return respond(arguments)
        }
    }
}
