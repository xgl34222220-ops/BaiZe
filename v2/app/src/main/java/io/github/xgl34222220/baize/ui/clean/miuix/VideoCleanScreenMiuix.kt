package io.github.xgl34222220.baize.ui.clean.miuix

import androidx.compose.runtime.Composable
import io.github.xgl34222220.baize.ui.clean.CleanUiActions
import io.github.xgl34222220.baize.ui.clean.CleanUiState

@Composable
fun VideoCleanScreenMiuix(state: CleanUiState, actions: CleanUiActions, expandedCategory: String,
    onExpandedCategoryChanged: (String) -> Unit) =
    CleanScreenMiuix(state, actions, expandedCategory, onExpandedCategoryChanged)
