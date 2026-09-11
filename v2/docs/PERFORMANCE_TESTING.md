# 扫描与清理性能回归

## 覆盖的真实入口

| 入口 | 本次优化 | 保留的边界 |
| --- | --- | --- |
| 首页一键清理 | 后台 Worker 仍执行 `clean → cleaner.sh.compat`；C 辅助程序规范化、去重已过滤候选清单 | 全部分类型流程、配置开关、路径/包白名单、删除与统计仍由兼容引擎控制；辅助程序不能枚举或删除文件 |
| 安全/深度扫描工作台 | Kotlin 扫描复用单次访问的元数据和目录列表；规则展开复用目录列表与 glob 模式 | 受保护目录空壳仍扫描；发现阶段的缓存不进入删除授权；风险和规则 SHA 校验不变 |
| 工作台结果展示 | 先展示首批，几何递增发布不可变预览，后台统一排序与分组 | 读取完成前禁止选择、删除、隔离和白名单修改；停止、断连、分页异常使快照不可执行 |
| 模块深度任务 | 目标发现衔接不可变清单生成；批量持久断点 | 挂载点、符号链接、大文件、白名单、风险和文件身份校验不可省略 |

工作台仍等待两个阻塞扫描接口完成后才请求结果页。本次渐进展示不是文件发现事件流。旋转或进程重启可恢复已经展示的预览，但未读取完的结果必须重新扫描，不能直接清理。快照有效期不会因分页等待而重新计时。

## 自动验证

```sh
bash v2/tests/run-all.sh
sh scripts/check.sh
python3 v2/scripts/validate-rules.py --check
sh v2/scripts/sync-version.sh --source-only --check
cd v2
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :macrobenchmark:assemble
ANDROID_SDK_ROOT=/path/to/sdk sh scripts/build-native.sh
```

发布前暂不推进 OTA，所以源码阶段使用 `--source-only --check`；正式产物与镜像验证完成后使用完整 `--check`。正式 Release 另外运行 Release 单元测试、lint、全量模块打包检查和签名验证。

### 首页清理

`test-compat-home-clean.py` 执行真实模块总入口，只在测试副本中将 Android 绝对数据路径映射到临时目录。分别启用/禁用 C 辅助程序，比较剩余文件、分类覆盖、统计、历史和锁释放；包括配置开关、包/路径白名单、符号链接、用户文件、停止、坏清单、特殊字符和失败回退。

可选阶段基准：

```sh
BAIZE_COMPAT_BENCH=1 python3 v2/tests/test-compat-home-clean.py
```

这比较同一个 1,000 文件清单的规范化去重阶段，不包含扫描、删除或 Android 存储 I/O。不能把该结果写成整机或整个清理过程的提速倍数。

### Kotlin 遍历和结果展示

- `NativeProfileTraversalTest`：普通/受保护目录覆盖、深度边界、失败列表、符号链接、停止/期限、规则缓存、快照失效和删除前重新校验。
- 合成树包含 49 个目录、288 个文件；断言每目录只列举一次，同一访问中的多个消费者复用元数据和规范路径，不依赖不稳定的计时阈值。
- `ProgressiveScanResultsTest`：首批发布、默认选择、不可变预览、60,000 项复制量上界、分页身份/偏移/总数、停止和旧结果拒绝。
- `ScanReviewStoreTest`：完整结果、未完成预览、重新扫描前失效的持久化恢复。

### 模块深度任务

详见 [深扫与断点协议](../tests/deep-performance.md)。`test-deep-performance.py` 覆盖进程终止、删除和结果记录之间的中断窗口、重复恢复、被替换文件、损坏账本、同步失败，以及真实 Shell 包装器的历史修复。`test-deep-recovery-bounds.py` 补充模拟重启后的旧账本拒绝和受保护父目录下四种风险子项的覆盖一致性。

已开始清理的账本绑定内核启动 UUID。Root 进程在同次启动内中断可继续恢复；设备重启后需要重新扫描，即使之前是正常停止。这样不会把可能在断电后失效的删除记录当作新一轮已确认结果。不能读取启动 UUID 时，在删除前停止。

## 真机验收

主机测试不能证明 Android 实际提速或 Root 生命周期正确。发布后应在实际慢的设备上记录同一批目录的前后对照：

1. 标明入口：首页一键清理、工作台安全/深度扫描，或模块深度任务。
2. 记录扫描耗时、首批展示耗时、完整结果可操作耗时、清理耗时，以及文件/目录/候选数量。
3. 固定配置、白名单和风险策略；禁止靠缩小扫描范围得到更快结果。
4. 分别验证停止、关闭 App 后后台任务、断连、恢复、白名单和扫描后新增/替换文件。
5. 在 Android 16 与实际使用的 Magisk/KernelSU/APatch 上复测 Root 连接；连接验收见 [ROOT_CONNECTION_TESTING.md](ROOT_CONNECTION_TESTING.md)。

文件系统时限为协作式检查，不能强行中断已经阻塞的系统调用。首页兼容路径仍保留顺序遍历，本次不是全引擎重写。
