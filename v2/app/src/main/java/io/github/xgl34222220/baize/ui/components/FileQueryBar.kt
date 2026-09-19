package io.github.xgl34222220.baize.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Search stays visible. Less frequent filter choices appear only when requested. */
@Composable
internal fun FileQueryBar(query: String, onQuery: (String) -> Unit, enabled: Boolean,
    placeholder: String, filterDescription: String, summary: String = "", onFilter: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
            placeholder = { Text(placeholder) }, shape = RoundedCornerShape(18.dp),
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { IconButton(onClick = onFilter, enabled = enabled) { Icon(Icons.Rounded.Tune, filterDescription) } })
        if (summary.isNotBlank()) Text(summary, Modifier.padding(start = 8.dp, top = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun FileFilterDialog(onDismiss: () -> Unit, onApply: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BaiZeDialog(onDismissRequest = onDismiss, title = { Text("筛选") },
        text = { Column(Modifier, verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = { BaiZeDialogButton(onClick = onApply) { Text("应用") } },
        dismissButton = { BaiZeDialogButton(onClick = onDismiss) { Text("取消") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> FileFilterChoices(title: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { (value, label) -> FilterChip(selected == value, { onSelect(value) }, label = { Text(label) }) }
    }
}
