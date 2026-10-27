# 双提权接口与 MagicRoot 1.0.4 实现

2.4（versionCode 30）保留 **98260** 和 **64650** 两个独立入口，默认选择 **98260**，并已分别注册 `Root98260Adapter` 与 `Root64650Adapter`。对接约定中的 64650 对应 MagicRoot 源码默认的 64560 链；旧 `MagicRootAdapter` 仅保留作参考。

## 双内核支持矩阵

两条链都包含以下两种 profile，不按设备型号删减：

| 提权入口 | 6.12.58 | 6.12.38 |
| --- | --- | --- |
| 64650 | KASLR、syms 与默认链载荷 | KASLR、syms 与默认链载荷 |
| 98260 | `98260/` rootd 载荷 | `98260-6138/` rootd 载荷 |

运行时按完整 `uname -r` 精确匹配，未知或不完整版本直接失败，不借用另一内核的地址或符号。

## 手动启动与切换

无线调试配对、连接、检查成功后仅建立 Shell。点击 **执行提权流程** 才部署所选分支并执行提权，任务期间固定选择且禁止重复启动。

成功时记录链路与 `boot_id`。同一开机周期已经成功使用一种链时，另一链在部署和执行前都会拒绝启动并提示先重启；新启动周期自动放行。手动清理不删除此防切换标记。已有 Root 通道仍存活时，同链可以直接复用，不重复执行竞态。

普通失败和 proof/PARKED 失败均不强制限制重新执行次数，也不要求必须重启或禁止换链；失败原因和当前 `boot_id` 只用于日志分类。应用内同一时刻仍只允许一个提权任务运行。

## 64650 后端

资产位于 `assets/magic104/64650/`，使用 MagicRoot 1.0.4 的启动稳定预检、KASLR 交叉校验、模块内容缓存、`wait_helper` 和新的竞态二进制。工作目录为 `/data/local/tmp/magic`，Root 执行器为 `/data/local/tmp/honor-64650/bfexec`。

模块出口使用官方 KernelSU 模板和当前内核的符号表，但不要求安装管理器或提取 ksud。`bfexec` 通过 KernelSU `GRANT_ROOT` 取得 UID 0 后直接执行 Base64 编码的命令，保留真实 stdout、stderr 与退出码。已有 KSU/su 不被卸载或阻止，但也不是备份、解锁的前置条件。

## 98260 后端

资产位于 `assets/magic104/98260/`，工作目录为 `/data/local/tmp/magic-98260`。正式执行前先运行 `exploit-android-key-ptr --check` 只读预检，随后建立 rootd 和 `/data/local/tmp/pc98260-root.sock`。默认不需要自动 KernelSU handoff。默认每次运行采用 32 次尝试、480 秒内部预算和 600 秒 rootd 等待上限；出现 `holding zombie` 后仅再等待 15 秒即结束本轮，失败提示追加“98260建议重启再试”，不以此限制后续手动重试。

`pc98260-su` 的传输可能包含交互提示，且不能单独提供符合框架要求的 stderr。`root-command.sh` 因而在设备端分别保存命令 stdout、stderr 和退出码，再通过无线 ADB Process 返回；不按行过滤二进制输出。rootd 在成功后保留，应用重进时可复用。

proof/PARKED 失败和等待超时只记录原因及 `boot_id`，不强制阻断重试或换链。每轮日志独立保存到 `/data/local/tmp/magic-98260/history/<boot>-<时间>/`，启动新一轮前还会保留上轮现场。清理不跨分支删除 64650 的内容，也不以清理动作代替用户自行选择重试或重启。

## 注册与进程契约

`HonorUblApplication.onCreate()` 中显式注册两个实现：

```kotlin
RootIntegration.register(RootChain.CVE_2026_98260, Root98260Adapter)
RootIntegration.register(RootChain.CVE_2026_64650, Root64650Adapter)
```

每个适配器实现 `deploy/acquireRoot/clean/setLogCallback`。`acquireRoot` 返回 `core/RootShell.kt` 的 `RootProcessProvider`，框架先以新进程执行 `id -u`，仅在退出码为 0 且 stdout 去空白后恰为 `0` 时登记 Provider。备份、ABL 提取和解锁继续使用同一个 Provider；适配器内部不递归调用 RootShell。

两个分支分别部署、分别清理，并保留独立日志。取得 Root 后，备份与解锁仍由使用者分别启动；Root 接入本身不会自动执行分区写入。
