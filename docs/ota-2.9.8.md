# hnubl 2.9.8：完成许可与官方 OTA 续接

本版针对实际诊断日志中的两种情况修复：首次点击时标记仍为 `1`，但原生更新服务已经停止；再次点击时，旧 `COMMITTING` 阶段又阻止续接。应用和内置模块同步为 **2.9.8 / versionCode 97**。历史版本保留。

## 唯一业务许可

是否允许继续官方软件更新暂停的 OTA 流程，只由 Root 服务读取的 `/data/adb/hnubl/freeotaok` 决定：

- 文件为 `0`、为空或不存在：手动重启入口跳转 hnubl，不执行官方续接和重启。
- 文件为 `1`：通过原有常驻服务原子消费为 `0`，获得一次续接许可。
- 新一轮官方 OTA 开始时重置旧许可；hnubl 本次保留锁态 OTA 成功后写入 `1`。

原生计划阶段、完成凭据、日志证明与可选服务准备状态均不作为额外的业务放行条件。Root 通道的应用身份认证、私有文件保护和原生通知身份验证仍然保留。

## 保留延迟切槽，续接原有流程

安装阶段继续延期真实的槽位激活动作。标记为 `1` 并消费许可后，若登记了当前启动及当前源槽对应的待完成动作，Root 服务调用官方 `IUpdateEngine.setSlot()`。完成后，回到点击入口原来的线程与 Looper，调用先前保存的厂商原入口；重启及其余官方流程由软件更新应用继续执行。

按当前 APK 的功能和调用关系发现官方客户端的工厂、启动、绑定与成对释放路径。在续接前尽力启动官方客户端，解决更新完成后服务停止的情况；可选客户端识别缺失、绑定失败或启动返回失败只记录结果，不覆盖文件许可判断。实际的待完成槽位动作若确实失败，则明确记录执行失败并停止该次重启，避免把失败当作成功。该次许可已消费；重新完成保锁态操作写入新的 `1` 后可再次尝试。

旧计划中的 `DEFERRED`、`DONE`、`COMMITTING` 或其他阶段不再造成永久拒绝。阶段用于诊断；待完成动作及确认成功记录用于避免重复执行真实切槽。没有登记当前待完成动作时保留厂商原流程，不凭旧计划推造新的切槽操作。

## 防止未保锁时误点重启

手动重启、自动重启及倒计时入口的关键接管状态分别验证。缺少可选版本提示、全量包或生命周期增强时，只记录诊断，不因此退出更新应用。明确发现关键重启保护目标缺失、歧义或绑定失败时，模块如实报告保护未就绪，并停止该目标更新应用，避免未接管的原按钮仍然可用；普通传输异常不等同于这种定位失败。

关键映射仍通过当前 DEX 的协议参数、消息发送与接收、调用关系和原流程汇合点识别。倒计时兼容路径增加实际 Handler 绑定及自动重启分支的结构证明，减少对诊断日志用词的依赖；更新开始的 Binder 协议入口还需存在真实的非 Proxy/Stub 调用者，避免仅因保留了闲置接口就认作保护有效。这是具体调用关系证明，并不声称已覆盖任意未来应用的完整异步界面流程。没有添加机型、版本、固定地址或厂商混淆名称的适配表。未知的新结构仍可能需要后续适配，界面不会将关键保护失配报告为已生效。

## 详细日志

保留 **导出 OTA 诊断日志**。根服务 journal 记录请求关联 ID、原始标记采样、许可消费、官方客户端启动与释放、待完成切槽动作、原入口续接，以及实际失败原因。日志位置为 `/data/adb/hnubl/logs/ota-events.jsonl`，包含轮转文件；不在成功轮询或滚动帧中反复写入。

移除 2.9.7 新增的完成通知重试、完成凭据 outbox 及其恢复判定；旧凭据可作为历史诊断读取，绝不参与放行。日志导出本身不消费许可、不切槽、不刷写、不重启。

## 验证范围

本版使用多份实际软件更新 APK、名称及布局变化样本、生产 Java 状态机、生产 JS 和编译后的原生策略做主机回归。重点覆盖标记 `0` 时误点、旧标记重置、重复回调、可选增强失配、旧 `COMMITTING` 记录、官方服务停止、实际调用失败以及新许可重试。主机测试使用受控 Binder、线程、文件与 HAL 端口，不等同于目标设备已经完成真实 OTA、切槽和重启验证。具体测试数量与源码、产物哈希随本轮验证记录提供。

## English

hnubl and its bundled companion module are both version **2.9.8 / versionCode 97**. The root-owned `freeotaok` marker is the only business permission for resuming the paused official OTA flow. Missing, empty or zero redirects the manual reboot entry to hnubl. A value of one is consumed atomically to zero and grants one attempt. A new official update resets stale permission, and a successful hnubl preserve-lock OTA writes one.

Native slot activation remains deferred. After permission is consumed, a recorded pending activation for the current boot and source slot is executed through the official `IUpdateEngine.setSlot()`. The captured vendor continuation then runs on its original Looper. Reboot and the remaining vendor work remain the updater's own implementation.

The optional vendor client lifecycle is discovered from factory, service binding, shared ownership and normal release relationships. Its absence or startup failure does not add a permission gate. An actual pending activation failure is reported distinctly as an authorized execution failure, with the grant already spent; the updater's reboot is not falsely treated as completed. A new explicit marker grant permits another attempt. Legacy diagnostic phases do not permanently block it.

Critical manual, automatic and countdown protection must be proven before readiness is reported. Explicit critical discovery or binding failure stops only the target updater; missing optional enhancements merely produce diagnostics. Countdown fallback follows the actual Handler sender/receiver and proved automatic reboot branch rather than depending solely on log wording. Payload-start Binder entries also require an actual non-Proxy/Stub caller, so a retained unused protocol implementation alone is insufficient. This is concrete call evidence, not a proof of every future asynchronous UI path. Discovery uses current DEX behavior and data flow, with no model/version/address or obfuscated-name table. Future unknown structures may still require adaptation.

Detailed correlated diagnostics and export remain available. The 2.9.7 completion retry/outbox recovery is removed from the control flow. Host tests cover actual updater APKs, structural mutations, production permission and continuation code, and the compiled native policy. Controlled host ports do not establish a successful physical-device OTA or reboot.
