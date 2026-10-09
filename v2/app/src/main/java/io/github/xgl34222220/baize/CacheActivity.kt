package io.github.xgl34222220.baize

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * 旧版「应用缓存」独立页面的兼容入口。
 *
 * 应用缓存扫描与清理已统一到扫描工作台的 `cache` 分类（「规则与保护 → 应用缓存」同一路径），
 * 旧页面没有任何入口再打开它。保留类名，把旧 Intent 重定向到工作台，不再维护第二套缓存清理 UI。
 */
class CacheActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CleanerNavigation.scan(this, LegacyEntryRedirects.CACHE_PROFILE)
        finish()
    }
}
