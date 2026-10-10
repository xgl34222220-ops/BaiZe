package io.github.xgl34222220.baize

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * 旧版「规则垃圾 / 空文件 / 残留碎片 / 深度 / 卸载残留」独立页面的兼容入口。
 *
 * 这些分类早已统一到 [ScanWorkbenchActivity]（同一套扫描、复核与清理流程）。旧页面的完整
 * 实现在跳转前就会 finish，属于不可达代码，已删除；这里只保留类名与 [EXTRA_PROFILE]，
 * 让旧的 Intent / 通知 / 固定快捷方式继续落到扫描工作台的对应分类。
 */
class ProfileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LegacyEntryRedirects.profileTarget(intent.getStringExtra(EXTRA_PROFILE))
            ?.let { CleanerNavigation.scan(this, it) }
        finish()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
