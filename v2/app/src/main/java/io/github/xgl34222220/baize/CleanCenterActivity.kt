package io.github.xgl34222220.baize

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.xgl34222220.baize.databinding.ActivityCleanCenterBinding

class CleanCenterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCleanCenterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCleanCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.smartScanButton.setOnClickListener {
            startActivity(Intent(this, SmartScanActivity::class.java))
        }
        binding.cacheCard.setOnClickListener {
            startActivity(Intent(this, CacheActivity::class.java))
        }
        binding.emptyCard.setOnClickListener { openProfile("empty") }
        binding.rulesCard.setOnClickListener { openProfile("rules") }
        binding.fragmentsCard.setOnClickListener { openProfile("fragments") }
        binding.deepCard.setOnClickListener { openProfile("deep") }
        binding.corpsesCard.setOnClickListener { openProfile("corpses") }
    }

    private fun openProfile(profile: String) {
        startActivity(
            Intent(this, ProfileActivity::class.java)
                .putExtra(ProfileActivity.EXTRA_PROFILE, profile)
        )
    }
}
