package io.github.xgl34222220.baize.ui.common

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
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
import java.security.MessageDigest

@Composable
fun AppPackageIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 50.dp,
    corner: Dp = 15.dp
) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(PersistentAppIconStore.get(packageName), packageName) {
        if (value == null) value = withContext(Dispatchers.IO) { PersistentAppIconStore.load(context, packageName) }
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        if (current != null) {
            Image(current.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text(label.trim().firstOrNull()?.uppercase() ?: "?", fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun AppPackageIconPreloader(packageNames: List<String>) {
    val context = LocalContext.current.applicationContext
    val stable = packageNames.filter { it.isNotBlank() }.distinct().take(80)
    LaunchedEffect(stable) {
        withContext(Dispatchers.IO) { stable.forEach { PersistentAppIconStore.load(context, it) } }
    }
}

private object PersistentAppIconStore {
    private const val PX = 112
    private val memory = object : LruCache<String, Bitmap>(160) {}

    @Synchronized
    fun get(packageName: String): Bitmap? = memory.get(packageName)

    fun load(context: Context, packageName: String): Bitmap? {
        synchronized(this) { memory.get(packageName) }?.let { return it }
        val directory = File(context.filesDir, "baize-app-icons").apply { mkdirs() }
        val stableFile = File(directory, sha256(packageName) + ".png")
        val disk = runCatching { if (stableFile.isFile) BitmapFactory.decodeFile(stableFile.path) else null }.getOrNull()
        if (disk != null) {
            synchronized(this) { memory.put(packageName, disk) }
            return disk
        }
        val pm = context.packageManager
        val info = appInfo(pm, packageName) ?: return null
        val drawable = runCatching { info.loadIcon(pm).mutate() }.getOrNull() ?: return null
        val bitmap = runCatching {
            Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888).also { target ->
                drawable.setBounds(0, 0, PX, PX)
                drawable.draw(Canvas(target))
            }
        }.getOrNull() ?: return null
        synchronized(this) { memory.put(packageName, bitmap) }
        runCatching {
            val temporary = File(directory, stableFile.name + ".tmp")
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temporary.renameTo(stableFile)) {
                temporary.copyTo(stableFile, overwrite = true)
                temporary.delete()
            }
        }
        return bitmap
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
