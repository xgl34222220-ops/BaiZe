from pathlib import Path

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/FileOrganizerWorker.kt")
text = path.read_text()

if "import android.os.Handler" not in text:
    text = text.replace(
        "import android.os.IBinder\n",
        "import android.os.Handler\nimport android.os.IBinder\nimport android.os.Looper\n",
        1,
    )
if "import kotlinx.coroutines.Dispatchers" not in text:
    text = text.replace(
        "import kotlinx.coroutines.suspendCancellableCoroutine\n",
        "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.suspendCancellableCoroutine\nimport kotlinx.coroutines.withContext\n",
        1,
    )

old = '''    private suspend fun bindRootService(): RootSession = suspendCancellableCoroutine { continuation ->
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val root = IProfileRootService.Stub.asInterface(binder)
                if (root == null) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Root Binder 为空"))
                    return
                }
                if (continuation.isActive) continuation.resume(RootSession(root, connection))
                else runCatching { RootService.unbind(connection) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Root 服务已断开"))
            }
        }
        continuation.invokeOnCancellation { runCatching { RootService.unbind(connection) } }
        runCatching {
            RootService.bind(
                Intent(applicationContext, BaiZeProfileRootService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE),
                connection
            )
        }.onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
    }

    private data class RootSession(val service: IProfileRootService, val connection: ServiceConnection) {
        fun close() { runCatching { RootService.unbind(connection) } }
    }
'''
new = '''    private suspend fun bindRootService(): RootSession = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val mainHandler = Handler(Looper.getMainLooper())
            lateinit var connection: ServiceConnection

            fun unbindOnMainThread() {
                val unbind = Runnable { runCatching { RootService.unbind(connection) } }
                if (Looper.myLooper() == Looper.getMainLooper()) unbind.run() else mainHandler.post(unbind)
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val root = IProfileRootService.Stub.asInterface(binder)
                    if (root == null) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("Root Binder 为空"))
                        }
                        return
                    }
                    if (continuation.isActive) continuation.resume(RootSession(root, connection))
                    else unbindOnMainThread()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Root 服务已断开"))
                    }
                }
            }
            continuation.invokeOnCancellation { unbindOnMainThread() }
            runCatching {
                RootService.bind(
                    Intent(applicationContext, BaiZeProfileRootService::class.java)
                        .addCategory(RootService.CATEGORY_DAEMON_MODE),
                    connection
                )
            }.onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
        }
    }

    private data class RootSession(val service: IProfileRootService, val connection: ServiceConnection) {
        suspend fun close() = withContext(Dispatchers.Main.immediate) {
            runCatching { RootService.unbind(connection) }
        }
    }
'''

if old in text:
    text = text.replace(old, new, 1)
elif "withContext(Dispatchers.Main.immediate)" not in text or "unbindOnMainThread" not in text:
    raise SystemExit("FileOrganizerWorker bindRootService anchor not found")

path.write_text(text)
print("file organizer Root binding moved to the Android main thread")
