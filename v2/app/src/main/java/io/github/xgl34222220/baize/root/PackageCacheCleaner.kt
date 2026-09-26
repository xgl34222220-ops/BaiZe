package io.github.xgl34222220.baize.root

import android.content.pm.IPackageDataObserver
import android.os.Binder
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Root-only cache API, following AOSP's observer contract and SD Maid's per-user approach.
 * An accepted request is never replayed on timeout, cancellation, or a negative callback. */
internal class PackageCacheCleaner(
    private val packageManager: Any,
    private val cancelled: AtomicBoolean,
    private val command: ToolboxCommand = ToolboxCommand(cancelled),
    private val timeoutMillis: Long = 20_000
) {
    private var shellSupported: Boolean? = null

    fun clear(packageName: String, userId: Int): JSONObject {
        require(RootValidation.packageName.matches(packageName) && userId in 0..999)
        if (cancelled.get()) return outcome(false, "已停止").put("cancelled", true)
        val method = try {
            packageManager.javaClass.getMethod("deleteApplicationCacheFilesAsUser",
                String::class.java, Int::class.javaPrimitiveType, IPackageDataObserver::class.java)
        } catch (_: ReflectiveOperationException) {
            return shellClear(packageName, userId)
        }
        val completed = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        val observer = object : IPackageDataObserver.Stub() {
            override fun onRemoveCompleted(name: String?, success: Boolean) {
                if (name != packageName) return
                succeeded.set(success)
                completed.countDown()
            }
        }
        try {
            // This method can run on a Binder thread: the system must see this root service,
            // not the unprivileged UI caller whose transaction entered it.
            val identity = Binder.clearCallingIdentity()
            try { method.invoke(packageManager, packageName, userId, observer) }
            finally { Binder.restoreCallingIdentity(identity) }
        } catch (error: Exception) {
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            return outcome(false, cause.message ?: cause.javaClass.simpleName)
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!completed.await(50, TimeUnit.MILLISECONDS)) {
            if (cancelled.get()) return outcome(false, "已停止等待；系统已接收的清理请求可能继续执行")
                .put("cancelled", true).put("submitted", true)
            if (System.nanoTime() >= deadline) return outcome(false, "系统未在期限内返回清理结果；请重新查看缓存")
                .put("timeout", true).put("submitted", true)
        }
        return outcome(succeeded.get(), if (succeeded.get()) "系统已确认缓存清理完成" else "系统报告缓存清理失败")
            .put("submitted", true).put("callbackConfirmed", true)
    }

    private fun shellClear(packageName: String, userId: Int): JSONObject {
        if (shellSupported == null) {
            val help = command.run(listOf("/system/bin/cmd", "package", "help"), 5, outputLimit = 256000)
            shellSupported = help.success && help.output.contains("--cache-only")
        }
        if (shellSupported != true) return outcome(false, "系统缓存接口和 cache-only 命令均不可用")
            .put("unsupported", true)
        val result = command.run(listOf("/system/bin/cmd", "package", "clear", "--cache-only",
            "--user", userId.toString(), packageName), 20)
        val success = result.success && result.output.lineSequence().any { it.trim() == "Success" } &&
            !result.output.contains("Failed", true) && !result.output.contains("Error:", true)
        return outcome(success, if (success) "系统已确认缓存清理完成" else result.output.ifBlank { "系统未确认缓存清理完成" })
            .put("backend", "package-manager-shell").put("exitCode", result.exit)
            .put("output", result.output).put("cancelled", result.cancelled).put("timeout", result.timedOut)
    }

    private fun outcome(success: Boolean, message: String) = JSONObject().put("success", success)
        .put("backend", "package-manager-observer").put("message", message)
}
