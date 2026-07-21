from pathlib import Path

main = Path("v2/app/src/main")

notifier = main / "java/io/github/xgl34222220/baize/NativeNotifier.kt"
text = notifier.read_text()
if "import android.annotation.SuppressLint" not in text:
    text = text.replace(
        "package io.github.xgl34222220.baize\n\n",
        "package io.github.xgl34222220.baize\n\nimport android.annotation.SuppressLint\n",
        1,
    )
text = text.replace(
    "    fun showTaskResult(context: Context, title: String, summary: String, details: String) {",
    "    @SuppressLint(\"MissingPermission\")\n    fun showTaskResult(context: Context, title: String, summary: String, details: String) {",
    1,
)
text = text.replace(
    '        NotificationManagerCompat.from(context).notify("baize-app-task", NOTIFICATION_ID, notification)',
    '        try {\n            NotificationManagerCompat.from(context).notify("baize-app-task", NOTIFICATION_ID, notification)\n        } catch (_: SecurityException) {\n            // Permission or OEM policy changed after the explicit permission check.\n        }',
    1,
)
notifier.write_text(text)

manifest = main / "AndroidManifest.xml"
text = manifest.read_text().replace(
    '<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />',
    '<uses-permission\n        android:name="android.permission.QUERY_ALL_PACKAGES"\n        tools:ignore="QueryAllPackagesPermission" />',
    1,
)
manifest.write_text(text)

for relative in ("res/values/themes.xml", "res/values-night/themes.xml"):
    path = main / relative
    text = path.read_text()
    text = text.replace(
        "<resources>",
        '<resources xmlns:tools="http://schemas.android.com/tools">',
        1,
    )
    text = text.replace(
        '<item name="android:windowLightNavigationBar">',
        '<item name="android:windowLightNavigationBar" tools:targetApi="27">',
    )
    for attribute in (
        "android:forceDarkAllowed",
        "android:enforceStatusBarContrast",
        "android:enforceNavigationBarContrast",
    ):
        text = text.replace(
            f'<item name="{attribute}">',
            f'<item name="{attribute}" tools:targetApi="29">',
        )
    path.write_text(text)

checks = {
    notifier: '@SuppressLint("MissingPermission")',
    manifest: 'tools:ignore="QueryAllPackagesPermission"',
    main / "res/values/themes.xml": 'tools:targetApi="29"',
    main / "res/values-night/themes.xml": 'tools:targetApi="27"',
}
for path, marker in checks.items():
    if marker not in path.read_text():
        raise SystemExit(f"lint fix missing in {path}")

print("Android lint fixes applied")
