package io.github.xgl34222220.baize

import com.topjohnwu.superuser.ipc.RootService

internal object RootConnectionMessages {
    fun bindingFailure(reason: RootService.BindingFailure): String = when (reason) {
        RootService.BindingFailure.ROOT_UNAVAILABLE ->
            "未取得 Root shell，不代表用户拒绝授权。请确认 Root 管理器及授权状态，再手动重连；若刚更改授权，请重启 App 后再试。"
        RootService.BindingFailure.STARTUP_FAILED ->
            "Root 服务启动流程失败。请查看运行诊断与 Root 管理器日志，确认 App/模块安装完整后手动重连。"
        RootService.BindingFailure.STARTUP_TIMEOUT ->
            "等待 Root 服务启动超时，原因尚未确认。请等待系统或授权交互完成后手动重连，并查看运行诊断。"
        RootService.BindingFailure.BIND_FAILED ->
            "Root 服务绑定握手失败。请查看运行诊断及版本信息，确认配套安装后手动重连。"
        RootService.BindingFailure.NULL_BINDING ->
            "Root 服务返回空 Binder。请查看运行诊断及版本信息后手动重连。"
    }

    const val DISCONNECTED = "连接已断开（不等于已确认服务崩溃）"
    const val RECOVERY_EXHAUSTED = "Root 连接自动恢复已停止，请手动重连；断连原因未确认，可查看运行诊断。"
}
