from pathlib import Path


def required(path: str, old: str, new: str, count: int = -1) -> None:
    file = Path(path)
    text = file.read_text()
    if new in text and old not in text:
        print(f"[already] {path}: {new[:80]}")
        return
    if old not in text:
        raise SystemExit(f"[missing] {path}: {old[:140]!r}")
    file.write_text(text.replace(old, new, count))
    print(f"[patched] {path}: {old[:80]}")


def optional(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if old in text:
        file.write_text(text.replace(old, new))
        print(f"[patched optional] {path}: {old[:80]}")
    else:
        print(f"[skip optional] {path}: {old[:80]}")


def patch_snapshot_cleaning() -> None:
    cache = "v2/app/src/main/java/io/github/xgl34222220/baize/root/BaiZeRootService.kt"
    required(
        cache,
        '''                    if (path.startsWith("/") && path.length <= 4096) {
                        put(path, json.optBoolean(path, false))
                    }''',
        '''                    if ((path == "__all_safe__" || path.startsWith("/")) && path.length <= 4096) {
                        put(path, json.optBoolean(path, false))
                    }''',
    )
    required(
        cache,
        '''                (selection[candidate.path] == true)''',
        '''                (selection["__all_safe__"] == true || selection[candidate.path] == true)''',
    )

    profile = "v2/app/src/main/java/io/github/xgl34222220/baize/root/NativeProfileEngine.kt"
    required(
        profile,
        '''        val selection = parseSelection(selectionJson)
        val selected = snapshot.candidates.filter { selection[it.id] == true || selection[it.path] == true }
        if (selected.isEmpty()) {''',
        '''        val selection = parseSelection(selectionJson)
        val selectAllSafe = selection["__all_safe__"] == true
        val selected = snapshot.candidates.filter { candidate ->
            val explicit = selection[candidate.id] == true || selection[candidate.path] == true
            explicit || (selectAllSafe && (candidate.risk == "low" || candidate.risk == "medium"))
        }
        if (selected.isEmpty()) {''',
    )
    required(
        profile,
        'json.optInt("fragmentDays", 7).coerceIn(1, 365)',
        'json.optInt("fragmentDays", 7).coerceIn(0, 365)',
    )


def patch_dashboard() -> None:
    layout = "v2/app/src/main/res/layout/activity_dashboard.xml"
    required(
        layout,
        '''                <com.google.android.material.card.MaterialCardView
                    style="@style/Widget.BaiZe.GlassCard"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="18dp"
                    app:cardBackgroundColor="?attr/colorPrimaryContainer">''',
        '''                <com.google.android.material.card.MaterialCardView
                    android:tag="glass:hero"
                    style="@style/Widget.BaiZe.GlassCard"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="18dp">''',
        1,
    )
    required(
        layout,
        '''    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottomNavigation"
        android:layout_width="match_parent"
        android:layout_height="72dp"
        android:layout_gravity="bottom"
        android:layout_marginStart="20dp"
        android:layout_marginEnd="20dp"
        android:layout_marginBottom="18dp"
        android:background="@drawable/bg_bottom_nav"
        android:elevation="24dp"
        app:itemIconSize="24dp"
        app:itemIconTint="@color/nav_item_color"
        app:itemTextColor="@color/nav_item_color"
        app:labelVisibilityMode="labeled"
        app:menu="@menu/menu_bottom_nav" />''',
        '''    <io.github.xgl34222220.baize.ui.FloatingGlassDock
        android:id="@+id/bottomNavigation"
        android:layout_width="match_parent"
        android:layout_height="84dp"
        android:layout_gravity="bottom"
        android:layout_marginStart="20dp"
        android:layout_marginEnd="20dp"
        android:layout_marginBottom="22dp" />''',
    )
    dashboard = "v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt"
    required(dashboard, 'binding.versionText.text = "Alpha 8"', 'binding.versionText.text = "Alpha 9"')


def patch_versions() -> None:
    optional("v2/app/build.gradle.kts", "versionCode = 20008", "versionCode = 20009")
    optional("v2/app/build.gradle.kts", 'versionName = "2.0.0-alpha08"', 'versionName = "2.0.0-alpha09"')
    optional("v2/module/module.prop", "version=v2.0.0-alpha08", "version=v2.0.0-alpha09")
    optional("v2/module/module.prop", "versionCode=20008", "versionCode=20009")
    optional("v2/module/module.prop", "白泽 v2 Alpha 8", "白泽 v2 Alpha 9")
    optional("v2/module/customize.sh", "白泽 v2 Alpha 8", "白泽 v2 Alpha 9")
    optional("v2/module/service.sh", "module_version=2.0.0-alpha08", "module_version=2.0.0-alpha09")
    optional("v2/scripts/package-module.sh", "BaiZe-v2-Alpha8-Module.zip", "BaiZe-v2-Alpha9-Module.zip")


if __name__ == "__main__":
    patch_snapshot_cleaning()
    patch_dashboard()
    patch_versions()
    print("Alpha 9 source migration complete")
