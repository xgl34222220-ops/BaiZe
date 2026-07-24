package io.github.xgl34222220.baize.ui.common

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.util.AtomicFile
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Composable
fun AppPackageIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 50.dp,
    corner: Dp = 15.dp
) {
    val context = LocalContext.current.applicationContext
    val stablePackage = remember(packageName) { packageName.trim() }
    val bitmap by produceState<Bitmap?>(PersistentAppIconStore.memory(stablePackage), stablePackage) {
        value = withContext(Dispatchers.IO) { PersistentAppIconStore.load(context, stablePackage) }
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        if (current != null && !current.isRecycled) {
            Image(current.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text(label.trim().firstOrNull()?.uppercase() ?: "?", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AppPackageIconPreloader(packageNames: List<String>) {
    val context = LocalContext.current.applicationContext
    val stable = remember(packageNames) {
        packageNames.map(String::trim).filter(String::isNotBlank).distinct().take(160)
    }
    LaunchedEffect(stable) {
        withContext(Dispatchers.IO) { stable.forEach { PersistentAppIconStore.load(context, it) } }
    }
}

private object PersistentAppIconStore {
    private const val PX = 128
    private val memoryCache = object : LruCache<String, Bitmap>(192) {}
    private val packageLocks = ConcurrentHashMap<String, Any>()

    @Synchronized
    fun memory(packageName: String): Bitmap? = memoryCache.get(packageName)

    fun load(context: Context, packageName: String): Bitmap? {
        if (packageName.isBlank()) return null
        synchronized(this) { memoryCache.get(packageName) }?.let { return it }
        val lock = packageLocks.getOrPut(packageName) { Any() }
        return synchronized(lock) {
            synchronized(this) { memoryCache.get(packageName) }?.let { return@synchronized it }

            val fileName = sha256(packageName) + ".png"
            val persistentDirectory = File(context.noBackupFilesDir, "baize-app-icons-v2").apply { mkdirs() }
            val persistentFile = File(persistentDirectory, fileName)
            val legacyFile = File(File(context.filesDir, "baize-app-icons"), fileName)

            decode(persistentFile)?.let { return@synchronized remember(packageName, it) }
            decode(legacyFile)?.let { legacy ->
                writeAtomic(persistentFile, legacy)
                return@synchronized remember(packageName, legacy)
            }

            val pm = context.packageManager
            val info = appInfo(pm, packageName) ?: return@synchronized null
            val drawable = runCatching { info.loadIcon(pm).mutate() }.getOrNull() ?: return@synchronized null
            val bitmap = runCatching {
                Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888).also { target ->
                    val canvas = Canvas(target)
                    drawable.setBounds(0, 0, PX, PX)
                    drawable.draw(canvas)
                }
            }.getOrNull() ?: return@synchronized null

            remember(packageName, bitmap)
            writeAtomic(persistentFile, bitmap)
            bitmap
        }.also { packageLocks.remove(packageName, lock) }
    }

    private fun decode(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        if (decoded == null) runCatching { file.delete() }
        return decoded
    }

    @Synchronized
    private fun remember(packageName: String, bitmap: Bitmap): Bitmap {
        memoryCache.put(packageName, bitmap)
        return bitmap
    }

    private fun writeAtomic(target: File, bitmap: Bitmap) {
        runCatching {
            target.parentFile?.mkdirs()
            val atomic = AtomicFile(target)
            var output: FileOutputStream? = null
            try {
                output = atomic.startWrite()
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.fd.sync()
                atomic.finishWrite(output)
                output = null
            } finally {
                output?.let(atomic::failWrite)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun appInfo(pm: PackageManager, packageName: String): ApplicationInfo? = runCatching {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS.toLong()
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags))
        } else {
            pm.getApplicationInfo(packageName, flags.toInt())
        }
    }.getOrNull()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
