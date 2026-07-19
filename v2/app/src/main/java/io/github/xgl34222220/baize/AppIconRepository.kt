package io.github.xgl34222220.baize

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads installed application icons without blocking Compose's main thread.
 *
 * The cache is deliberately bounded because OEM adaptive icons may decode to fairly large bitmaps.
 * Missing, uninstalled or hidden packages simply fall back to the BaiZe cleaning placeholder.
 */
internal object AppIconRepository {
    private const val ICON_CACHE_SIZE = 96
    private const val ICON_BITMAP_SIZE = 144
    private val memoryCache = object : LruCache<String, ImageBitmap>(ICON_CACHE_SIZE) {}

    suspend fun load(context: Context, packageName: String): ImageBitmap? = withContext(Dispatchers.IO) {
        memoryCache.get(packageName)?.let { return@withContext it }
        runCatching {
            context.packageManager
                .getApplicationIcon(packageName)
                .toBitmap(
                    width = ICON_BITMAP_SIZE,
                    height = ICON_BITMAP_SIZE,
                    config = Bitmap.Config.ARGB_8888
                )
                .asImageBitmap()
        }.getOrNull()?.also { memoryCache.put(packageName, it) }
    }
}

@Composable
internal fun ApplicationIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = AppIconRepository.load(context, packageName)
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .11f)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = requireNotNull(bitmap),
                contentDescription = label,
                modifier = Modifier.size(48.dp).clip(shape),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.CleaningServices,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(25.dp)
            )
        }
    }
}
