# hnubl 3.0.0：按完成文件续接 OTA

应用与内置模块同步为 **3.0.0 / versionCode 99**，旧版交付文件保留。

## 本次错误与更正

新增日志给出了三次相同的失败链路：文件值为 `1`，模块成功消费为 `0`，随后 2.9.9 的 `getStatus()==6` 前置检查拒绝继续，实际 `setSlot()` 均未调用。

这一检查采用了错误的接口语义。官方日志在同一时刻明确记录：安装已完成，更新服务重开后恢复主更新状态 `6`；厂商 `IUpdateEngine.getStatus()` 对应的却是补丁状态 `GetPatchStatus`，空闲时为 `0`。补丁状态 `0` 可以与主 OTA 等待重启状态 `6` 同时存在。这次任务没有被官方取消。2.9.9 将补丁状态误当主更新状态的判断和相关文档假设，由本版撤销。

## 唯一业务许可

是否已经完成保留锁态 OTA、能否续接官方重启流程，统一以 Root 服务读取的 `/data/adb/hnubl/freeotaok` 为准：

- 成功读取 `1`：按钮显示“立即重启”，手动续接可获得一次许可。
- 文件为 `0`、为空或不存在：按钮显示“继续流程”，点击进入 hnubl。
- 读取失败：记录明确的读取错误；读取错误不推造文件值。

不以 `getStatus()`、补丁状态、应用瞬时 completed 字段、本地 phase、旧 boot 或诊断记录有无作为新的完成条件。自动来源与手动来源的区分、私有 IPC 的身份认证和实际函数调用能力保持原合同。

许可仍按原有约定原子消费 `1→0`。消费后直接调用真实官方 `setSlot()`，由官方实现完成待执行的槽位动作，再交还原有重启调用。移除 `getStatus` 的 ABI 要求、读取、等于 6 的判断，以及错误派生的 `official_status / expected_status / official_pending` 字段。官方 setter 真正返回 false、抛异常或服务实际缺失时，如实记录执行失败，不伪造硬件切槽成功。

## 按钮状态刷新

按钮文字使用成功读取的文件值。系统更新回到前台、同一窗口重新获得焦点及原点击处理返回后，触发一次仅针对 STATE 的新读取，不依赖旧缓存，也不为此扫描整页。已有请求在途时，强制刷新会合并为后续一次新读取，避免返回前的刷新请求丢失。

稳定轮询保持在后台，文件值不变时不反复改写文字。新增固定标签 TRACE 只记录值变化与读取错误的状态变化，便于从导出的 Root 日志核对按钮是否实际读到了 `1`。

原 2.9.9 的最终重启出口保护、手动／自动理由定位、弱身份异步意图、一次消费、目标进程去优化和延迟切槽机制保持。定位继续基于实际接口、参数与调用关系，没有为本次设备、系统版本或固定地址建立适配表。

## 验证与范围

回归使用本次“主 OTA 为 6、补丁状态为 0”的真实日志场景，并验证：补丁 getter 返回任意值、缺失或抛异常时均不读取它，真实 setter 仍可执行；文件 0 不执行 setter；真实 setter 错误仍被完整记录；旧计划和诊断保存失败不形成新的业务门槛。

按钮回归保留原标签、滚动和重复刷新性能用例，另覆盖同根窗口焦点恢复、手工变更完成文件、在途请求与新强制刷新交错。产物还需通过签名、版本、模块一致性及最终源码解包复测；具体收据随交付生成。

提权资产、备份、分区调整与刷写、镜像修补核心和 KBL 保持上一版实现。诊断导出继续包含 service.log。主机验证不等同于已执行真实设备 OTA、切槽或重启；本次交付不包含这些设备操作。

## English

hnubl and its companion module are **3.0.0 / versionCode 99**. This release removes the incorrect `getStatus()==6` precondition introduced in 2.9.9. The supplied logs prove that the main OTA resumed in state 6 while the vendor getter reported idle **patch** state 0. The official setter had never been invoked on the failed attempts.

The root-owned `freeotaok` file remains the only business permission. A successful read of one means “Restart now”; zero, empty or missing means “Continue the process.” A read failure is logged rather than represented as a fabricated file value. Phase, boot, transient app state and patch status do not add completion conditions.

The original atomic one-to-zero consumption is retained. The actual official `setSlot()` is then invoked directly, followed by the original reboot call after genuine success. Missing services, false returns and native exceptions remain explicit execution failures; no hardware result is fabricated. Authentication and manual/automatic origin protection remain unchanged.

Foreground/focus recovery and return from the original click path request a fresh, state-only read. Forced refreshes coalesce behind an in-flight request, while unchanged values avoid repeated UI writes or whole-tree discovery. Value transitions and read errors have fixed private TRACE stages. Existing image patching, privilege assets, partition operations and KBL are unchanged. Final host and extracted-artifact verification is distinct from a physical-device OTA or reboot.
