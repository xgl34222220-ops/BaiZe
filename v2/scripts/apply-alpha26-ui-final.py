#!/usr/bin/env python3
from pathlib import Path

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"UI final patch target not found:\n{old[:400]}")
    text = text.replace(old, new, 1)


replace_once("import androidx.compose.foundation.lazy.rememberLazyListState\n", "")
replace_once("import androidx.compose.runtime.LaunchedEffect\n", "")
replace_once(
    '''    val accentGradient = rememberAccentGradient()
    val listState = rememberLazyListState()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LaunchedEffect(state.scanCompleted) {
        if (state.scanCompleted) {
            listState.animateScrollToItem(3)
        } else if (listState.firstVisibleItemIndex > 0) {
            listState.animateScrollToItem(0)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),''',
    '''    val accentGradient = rememberAccentGradient()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),''',
)
replace_once(
    '''                        when {
                            state.running -> Icons.Rounded.Stop
                            state.ready -> Icons.Rounded.AutoAwesome
                            else -> Icons.Rounded.Refresh
                        },''',
    '''                        when {
                            state.running -> Icons.Rounded.Stop
                            state.scanCompleted && state.scanFiles > 0 -> Icons.Rounded.AutoAwesome
                            state.ready -> Icons.Rounded.AutoAwesome
                            else -> Icons.Rounded.Refresh
                        },''',
)
replace_once(
    '''                        when {
                            state.running -> "安全停止任务"
                            state.ready -> "立即智能清理"
                            state.connected -> "恢复连接并清理"
                            else -> "连接 Root 并清理"
                        },''',
    '''                        when {
                            state.running -> "安全停止任务"
                            state.scanCompleted && state.scanFiles > 0 -> "按扫描结果清理"
                            state.ready -> "立即智能清理"
                            state.connected -> "恢复连接并清理"
                            else -> "连接 Root 并清理"
                        },''',
)
text = text.replace("contentPadding = PaddingValues(bottom = 132.dp)", "contentPadding = PaddingValues(bottom = 24.dp)")
text = text.replace("contentPadding = PaddingValues(bottom = 130.dp)", "contentPadding = PaddingValues(bottom = 24.dp)")

path.write_text(text, encoding="utf-8")
