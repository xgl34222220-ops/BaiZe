package io.github.xgl34222220.baize

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RuleUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = RuleUpdateClient.loadSettings(applicationContext)
        if (settings.policy == "manual") return Result.success()
        val session = runCatching { RuleUpdateClient.connect(applicationContext) }.getOrElse {
            RuleUpdateClient.recordResult(applicationContext, "后台规则检查无法连接 Root：${it.message}")
            return Result.retry()
        }
        return try {
            val check = runCatching {
                RuleUpdateClient.check(applicationContext, session, settings.channel)
            }.getOrElse {
                RuleUpdateClient.recordResult(applicationContext, "后台规则检查失败：${it.message}")
                return Result.retry()
            }
            val release = check.release
            if (release == null) {
                val text = "${channelLabel(settings.channel)}规则已是最新版本 ${check.currentVersion}"
                RuleUpdateClient.recordResult(applicationContext, text)
                return Result.success()
            }

            when (settings.policy) {
                "notify" -> {
                    val text = "发现 ${release.version} · ${release.releaseNotes.ifBlank { "已通过签名索引验证" }}"
                    RuleUpdateClient.recordResult(applicationContext, text)
                    NativeNotifier.showRuleUpdate(
                        applicationContext,
                        "白泽规则有新版本",
                        "${check.currentVersion} → ${release.version}",
                        text
                    )
                    Result.success()
                }

                "download", "install" -> {
                    val ready = runCatching {
                        RuleUpdateClient.downloadRelease(applicationContext, release)
                    }.getOrElse {
                        RuleUpdateClient.recordResult(applicationContext, "规则包断点下载失败：${it.message}")
                        return Result.retry()
                    }
                    val preview = runCatching {
                        RuleUpdateClient.previewRelease(applicationContext, session, release, ready)
                    }.getOrElse {
                        RuleUpdateClient.recordResult(applicationContext, "已下载规则包签名复验失败：${it.message}")
                        return Result.failure()
                    }
                    val canAutoInstall = settings.policy == "install" && settings.channel == "stable"
                    if (!canAutoInstall) {
                        val text = "${release.version} 已下载并通过 APK 同证书复验，打开规则更新页安装"
                        RuleUpdateClient.recordResult(applicationContext, text)
                        NativeNotifier.showRuleUpdate(
                            applicationContext,
                            "白泽规则更新已准备",
                            release.version,
                            text
                        )
                        return Result.success()
                    }
                    val applied = withContext(Dispatchers.IO) {
                        JSONObject(session.pack.applyPreview(preview.optString("previewId")))
                    }
                    if (applied.has("error")) {
                        val code = applied.optString("error")
                        val message = applied.optString("message", code)
                        RuleUpdateClient.recordResult(applicationContext, "自动安装暂未完成：$message")
                        return if (code == "busy") Result.retry() else Result.failure()
                    }
                    val text = "已自动安装稳定规则 ${release.version}，上一版本可在管理中心回滚"
                    RuleUpdateClient.recordResult(applicationContext, text)
                    NativeNotifier.showRuleUpdate(
                        applicationContext,
                        "白泽规则已自动更新",
                        release.version,
                        text
                    )
                    Result.success()
                }

                else -> Result.success()
            }
        } finally {
            session.close()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "baize_signed_rule_update"
        private const val INTERVAL_HOURS = 12L
        private const val FLEX_HOURS = 2L

        internal fun ensureScheduled(context: Context) {
            configure(context, RuleUpdateClient.loadSettings(context))
        }

        internal fun configure(context: Context, settings: RuleUpdateSettings) {
            RuleUpdateClient.saveSettings(context, settings)
            val manager = WorkManager.getInstance(context)
            if (settings.policy == "manual") {
                manager.cancelUniqueWork(UNIQUE_WORK)
                return
            }
            val builder = Constraints.Builder()
                .setRequiredNetworkType(
                    if (settings.policy in setOf("download", "install")) NetworkType.UNMETERED
                    else NetworkType.CONNECTED
                )
                .setRequiresBatteryNotLow(settings.policy in setOf("download", "install"))
            if (settings.policy == "install") {
                builder.setRequiresCharging(true)
                builder.setRequiresDeviceIdle(true)
            }
            val request = PeriodicWorkRequestBuilder<RuleUpdateWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
                FLEX_HOURS, TimeUnit.HOURS
            )
                .setInitialDelay(30, TimeUnit.MINUTES)
                .setConstraints(builder.build())
                .build()
            manager.enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        internal fun policyLabel(policy: String): String = when (policy) {
            "notify" -> "发现更新时提醒"
            "download" -> "仅 Wi-Fi 自动下载并验证"
            "install" -> "充电、空闲、Wi-Fi 时自动安装稳定版"
            else -> "仅手动检查"
        }

        internal fun channelLabel(channel: String): String = if (channel == "beta") "Beta" else "稳定版"
    }
}

private fun channelLabel(channel: String): String = RuleUpdateWorker.channelLabel(channel)
