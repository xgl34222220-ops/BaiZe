#!/usr/bin/env python3
from pathlib import Path

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/BaiZeMiuixApp.kt")
text = path.read_text(encoding="utf-8")
old = '''            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.primary.copy(.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.CleaningServices, null, tint = MaterialTheme.colorScheme.primary)
            }
'''
new = '''            ApplicationIcon(
                packageName = item.packageName,
                label = item.label,
                modifier = Modifier.size(48.dp)
            )
'''
if new in text:
    print("Alpha 29 step 2 icon UI already applied")
elif old not in text:
    raise SystemExit("AppJunkCard placeholder icon block not found")
else:
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("Alpha 29 step 2 icon UI applied")
