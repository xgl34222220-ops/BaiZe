package io.github.xgl34222220.baize.ui.history

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.baize.AuditActivity
import io.github.xgl34222220.baize.DashboardActions
import io.github.xgl34222220.baize.DashboardUiState
import io.github.xgl34222220.baize.ui.appearance.UiStyle
import io.github.xgl34222220.baize.ui.history.material.HistoryScreenMaterial
import io.github.xgl34222220.baize.ui.history.miuix.HistoryScreenMiuix

@Composable
fun HistoryRoute(
    style: UiStyle,
    dashboard: DashboardUiState,
    dashboardActions: DashboardActions
) {
    val context = LocalContext.current
    val state = dashboard.toHistoryUiState()
    val actions = HistoryUiActions(
        onRefresh = dashboardActions.refresh,
        onClearHistory = dashboardActions.clearHistory,
        onReviewProtected = dashboardActions.reviewProtected
    )

    Box(Modifier.fillMaxSize()) {
        when (style) {
            UiStyle.MATERIAL -> HistoryScreenMaterial(state, actions)
            UiStyle.MIUIX -> HistoryScreenMiuix(state, actions)
        }
        ExtendedFloatingActionButton(
            onClick = { context.startActivity(Intent(context, AuditActivity::class.java)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 88.dp),
            icon = { Icon(Icons.Rounded.Description, contentDescription = null) },
            text = { Text("审计中心") }
        )
    }
}
