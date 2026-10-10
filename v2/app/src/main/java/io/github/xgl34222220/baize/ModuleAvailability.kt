package io.github.xgl34222220.baize

import org.json.JSONObject

/**
 * 自动清理模块的真实可用状态。旧逻辑只看 module.prop 是否存在：在 Root 管理器里「停用」或
 * 「待移除」的模块、以及刚安装尚未重启的模块，会被误报为「已启用」或笼统的「未安装」。
 */
internal data class ModuleAvailability(val installed: Boolean, val disabled: Boolean, val pendingReboot: Boolean) {
    val active: Boolean get() = installed && !disabled
    val inactiveText: String get() = when {
        installed && disabled -> "自动清理模块已在 Root 管理器中停用或待移除，启用后重启生效"
        pendingReboot -> "自动清理模块已安装，重启手机后生效"
        else -> "未安装自动清理模块"
    }
    companion object {
        fun of(ping: JSONObject) = ModuleAvailability(ping.optBoolean("module"),
            ping.optBoolean("moduleDisabled"), ping.optBoolean("modulePendingReboot"))
    }
}
