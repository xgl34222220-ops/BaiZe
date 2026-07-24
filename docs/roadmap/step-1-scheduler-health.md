# 第一步：自动任务体检与诊断

本阶段只增强自动任务的可解释性、诊断和自愈能力，不改变任何任务周期范围、默认周期或自动任务开关。

## 已实现

- Scheduler 与 Supervisor 进程、心跳和队列健康状态。
- 结构化阻塞原因码和真实 `blocked_groups`。
- 无破坏体检，不创建清理任务、不扫描用户文件、不修改配置。
- 一键修复陈旧锁、停止标记和临时状态，并重新唤醒 Supervisor。
- 脱敏诊断包，优先导出到 `Download/BaiZe`。
- Material 与 MIUIx 设置页共用的自动任务体检面板。

## 原因码

- `SCHEDULER_DISABLED`
- `SERVICE_UNHEALTHY`
- `RUNNING`
- `WAIT_SCREEN_OFF`
- `WAIT_CHARGING`
- `WAIT_BATTERY`
- `WAIT_IDLE`
- `USER_STOPPED`
- `TASK_CONFLICT`
- `RECOVERING`
- `RETRY_BACKOFF`
- `SKIPPED`
- `QUEUED`
- `WAIT_NEXT_RUN`
- `WAITING`

## 安全边界

体检与修复不会修改：

- 定时周期；
- 每日固定时间；
- 任务启用状态；
- 清理规则；
- 白名单；
- 扫描快照；
- 用户文件。
