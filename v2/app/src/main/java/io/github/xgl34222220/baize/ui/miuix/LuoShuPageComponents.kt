package io.github.xgl34222220.baize.ui.miuix

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.baize.ui.theme.BaiZeTokens

@Composable
fun LuoShuPageHeader(
    eyebrow: String,
    title: String,
    subtitle: String,
    actionIcon: ImageVector? = null,
    actionDescription: String = "",
    actionBusy: Boolean = false,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = eyebrow.uppercase(),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.4.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 39.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        if (actionIcon != null && onAction != null) {
            VideoCard(contentPadding = 0) {
                IconButton(onClick = onAction, modifier = Modifier.size(50.dp)) {
                    if (actionBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(23.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(actionIcon, contentDescription = actionDescription, modifier = Modifier.size(23.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun LuoShuSectionTitle(
    eyebrow: String,
    title: String,
    subtitle: String
) {
    Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 4.dp)) {
        Text(
            text = eyebrow.uppercase(),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.2.sp
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 19.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}
