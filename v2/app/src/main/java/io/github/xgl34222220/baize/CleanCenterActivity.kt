package io.github.xgl34222220.baize

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.github.xgl34222220.baize.databinding.ActivityCleanCenterBinding

class CleanCenterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCleanCenterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityCleanCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        binding.backButton.setOnClickListener { finish() }
        binding.quickCleanButton.setOnClickListener {
            startActivity(
                Intent(this, DashboardActivity::class.java)
                    .putExtra(DashboardActivity.EXTRA_RUN_SMART_CLEAN, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
        binding.smartScanButton.setOnClickListener {
            startActivity(Intent(this, SmartScanActivity::class.java))
        }
        binding.cacheCard.setOnClickListener {
            startActivity(Intent(this, CacheActivity::class.java))
        }
        binding.emptyCard.setOnClickListener { openProfile("empty") }
        binding.rulesCard.setOnClickListener { openProfile("rules") }
        binding.fragmentsCard.setOnClickListener { openProfile("fragments") }
        binding.deepCard.setOnClickListener {
            confirmAdvancedAction(
                title = "开始完整深度扫描？",
                message = "将加载完整规则库并按风险分级展示候选项。扫描阶段不会删除文件，确认清理时仍会再次校验白名单、挂载点和软链接。",
                confirmText = "继续扫描"
            ) { openProfile("deep") }
        }
        binding.corpsesCard.setOnClickListener {
            confirmAdvancedAction(
                title = "扫描卸载残留？",
                message = "将核对 Android/data、obb、media 与应用私有目录中的无主数据。候选项会先展示，不会直接删除。",
                confirmText = "继续扫描"
            ) { openProfile("corpses") }
        }
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.pageContent.updatePadding(
                top = bars.top + dp(8),
                bottom = bars.bottom + dp(28)
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun openProfile(profile: String) {
        startActivity(
            Intent(this, ProfileActivity::class.java)
                .putExtra(ProfileActivity.EXTRA_PROFILE, profile)
        )
    }

    private fun confirmAdvancedAction(
        title: String,
        message: String,
        confirmText: String,
        action: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(confirmText) { _, _ -> action() }
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
