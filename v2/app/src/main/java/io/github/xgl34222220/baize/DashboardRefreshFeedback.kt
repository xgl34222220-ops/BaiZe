package io.github.xgl34222220.baize

/**
 * Manual refresh (History header, Home "更多操作 → 刷新状态") only updates storage numbers when
 * the Root service is not bound: every other refresh step returns early on a null service.
 * Tell the user so instead of letting the tap look ignored.
 */
internal object DashboardRefreshFeedback {
    const val CONNECTING = "正在连接 Root 服务，连接后会自动刷新"
    const val DISCONNECTED = "Root 服务未连接，仅刷新了存储空间"

    fun message(serviceConnected: Boolean, connecting: Boolean): String? = when {
        serviceConnected -> null
        connecting -> CONNECTING
        else -> DISCONNECTED
    }
}
