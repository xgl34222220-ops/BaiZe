package io.github.xgl34222220.baize.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp

/** White-label, 24-unit line icons with the same two-unit rounded stroke. */
internal object BaiZeIcons {
    private fun line(name: String, block: PathBuilder.() -> Unit) = ImageVector.Builder(
        name = "BaiZe.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply { path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block) }.build()
    val Folder = line("Folder") { moveTo(3f,7f); lineTo(3f,19f); lineTo(21f,19f); lineTo(21f,7f); lineTo(12f,7f); lineTo(9f,4f); lineTo(3f,4f); close() }
    val Copy = line("Copy") { moveTo(8f,8f); lineTo(21f,8f); lineTo(21f,21f); lineTo(8f,21f); close(); moveTo(16f,4f); lineTo(4f,4f); lineTo(4f,16f) }
    val Chart = line("Chart") { moveTo(12f,3f); lineTo(12f,12f); lineTo(21f,12f); curveTo(21f,17f,17f,21f,12f,21f); curveTo(7f,21f,3f,17f,3f,12f); curveTo(3f,7f,7f,3f,12f,3f); close(); moveTo(16f,3f); curveTo(19f,4f,20f,6f,21f,8f); lineTo(16f,8f); close() }
    val Package = line("Package") { moveTo(4f,7f); lineTo(12f,3f); lineTo(20f,7f); lineTo(20f,17f); lineTo(12f,21f); lineTo(4f,17f); close(); moveTo(4f,7f); lineTo(12f,11f); lineTo(20f,7f); moveTo(12f,11f); lineTo(12f,21f); moveTo(8f,5f); lineTo(16f,9f) }
    val Photo = line("Photo") { moveTo(3f,4f); lineTo(21f,4f); lineTo(21f,20f); lineTo(3f,20f); close(); moveTo(3f,16f); lineTo(8f,11f); lineTo(13f,16f); lineTo(16f,13f); lineTo(21f,18f); moveTo(16f,8f); lineTo(16.1f,8f) }
    val Video = line("Video") { moveTo(3f,5f); lineTo(21f,5f); lineTo(21f,19f); lineTo(3f,19f); close(); moveTo(10f,9f); lineTo(15f,12f); lineTo(10f,15f); close() }
    val Music = line("Music") { moveTo(9f,17f); lineTo(9f,5f); lineTo(20f,3f); lineTo(20f,15f); moveTo(9f,8f); lineTo(20f,6f); moveTo(9f,17f); curveTo(5f,15f,2f,19f,5f,21f); curveTo(7f,22f,9f,20f,9f,17f); moveTo(20f,15f); curveTo(16f,13f,13f,17f,16f,19f); curveTo(18f,20f,20f,18f,20f,15f) }
    val Document = line("Document") { moveTo(5f,3f); lineTo(14f,3f); lineTo(19f,8f); lineTo(19f,21f); lineTo(5f,21f); close(); moveTo(14f,3f); lineTo(14f,8f); lineTo(19f,8f); moveTo(9f,12f); lineTo(15f,12f); moveTo(9f,16f); lineTo(15f,16f) }
    val Book = line("Book") { moveTo(12f,5f); curveTo(9f,3f,5f,3f,3f,4f); lineTo(3f,19f); curveTo(6f,18f,9f,18f,12f,20f); curveTo(15f,18f,18f,18f,21f,19f); lineTo(21f,4f); curveTo(18f,3f,15f,3f,12f,5f); lineTo(12f,20f) }
    val Clock = line("Clock") { moveTo(21f,12f); curveTo(21f,17f,17f,21f,12f,21f); curveTo(7f,21f,3f,17f,3f,12f); curveTo(3f,7f,7f,3f,12f,3f); curveTo(17f,3f,21f,7f,21f,12f); moveTo(12f,7f); lineTo(12f,12f); lineTo(16f,14f) }
    val Shield = line("Shield") { moveTo(12f,3f); lineTo(20f,6f); lineTo(20f,12f); curveTo(19f,17f,15f,20f,12f,21f); curveTo(9f,20f,5f,17f,4f,12f); lineTo(4f,6f); close(); moveTo(8f,12f); lineTo(11f,15f); lineTo(16f,9f) }
    val Clean = line("Clean") { moveTo(16f,3f); lineTo(10f,13f); moveTo(7f,12f); lineTo(14f,16f); lineTo(12f,21f); lineTo(3f,21f); lineTo(7f,12f); moveTo(7f,17f); lineTo(6f,21f); moveTo(18f,13f); lineTo(22f,13f); moveTo(20f,11f); lineTo(20f,15f) }
    val Home = line("Home") { moveTo(3f,10f); lineTo(12f,3f); lineTo(21f,10f); moveTo(5f,9f); lineTo(5f,21f); lineTo(10f,21f); lineTo(10f,14f); lineTo(14f,14f); lineTo(14f,21f); lineTo(19f,21f); lineTo(19f,9f) }
}

internal fun baiZeLineIcon(icon: ImageVector): ImageVector = when (icon.name.substringAfterLast('.')) {
    "InstallMobile", "Android", "FolderZip", "Inventory2" -> BaiZeIcons.Package
    "Folder", "FolderOpen", "FolderCopy", "FolderDelete" -> BaiZeIcons.Folder
    "ContentCopy" -> BaiZeIcons.Copy
    "DataUsage", "PieChart", "Storage" -> BaiZeIcons.Chart
    "Image", "Photo", "PhotoLibrary" -> BaiZeIcons.Photo
    "Movie", "VideoLibrary", "Videocam" -> BaiZeIcons.Video
    "MusicNote", "AudioFile" -> BaiZeIcons.Music
    "Description", "Article", "Rule" -> BaiZeIcons.Document
    "MenuBook", "Book" -> BaiZeIcons.Book
    "CalendarMonth", "Schedule", "History", "Timer" -> BaiZeIcons.Clock
    "Security", "Shield", "VerifiedUser" -> BaiZeIcons.Shield
    "CleaningServices", "AutoAwesome", "DeleteSweep" -> BaiZeIcons.Clean
    "Home" -> BaiZeIcons.Home
    else -> icon
}

@Composable
internal fun BaiZeIconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(modifier.size(40.dp), shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = .07f)) {
        Box(contentAlignment = Alignment.Center) { Icon(baiZeLineIcon(icon), null, Modifier.size(24.dp), tint = accent) }
    }
}
