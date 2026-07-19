#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"patch target not found: {path}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/CleanCenterActivity.kt",
    '''            .background(
                if (amoled) androidx.compose.ui.graphics.Color.Black
                else Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = if (dark) .13f else .09f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background
                    )
                )
            )''',
    '''            .background(
                Brush.verticalGradient(
                    if (amoled) {
                        listOf(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Black)
                    } else {
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = if (dark) .13f else .09f),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background
                        )
                    }
                )
            )'''
)

replace_once(
    "v2/app/src/main/java/io/github/xgl34222220/baize/ProfileActivity.kt",
    '''            .background(
                if (amoled) androidx.compose.ui.graphics.Color.Black
                else Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = if (dark) .14f else .09f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background
                    )
                )
            )''',
    '''            .background(
                Brush.verticalGradient(
                    if (amoled) {
                        listOf(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Black)
                    } else {
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = if (dark) .14f else .09f),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background
                        )
                    }
                )
            )'''
)
