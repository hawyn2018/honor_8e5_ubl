# hnubl 2.9.9：重启出口与官方切槽续接

应用和内置模块同步为 **2.9.9 / versionCode 98**，保留全部旧版交付文件。

## 本轮日志依据

用户补充的 `service.log` 显示，重启前的新进程已报告关键 Hook 和服务启动接口就绪；这排除了仅凭“模块没有启动”解释现场。结合前一批诊断，完成标记仍为原来的 `1`，本地计划保持旧 `COMMITTING`，却没有留下新版授权消费与切槽提交的记录。官方日志则显示重启后仍在 B 槽，随后取消 A 槽快照并执行回退覆盖。

静态分析确认，既有带理由参数的 VAB 重启入口之外，还有直接调用系统重启助手的路径。某一具体未接管路径或运行时优化是否导致本次现场绕过，现有日志尚未完全证明。本版修复覆盖这些实际缺口，并补足后续诊断，不把其中一种推断当作已观测事实。

## 放行和执行

`/data/adb/hnubl/freeotaok` 继续是唯一业务许可。缺失、空或 `0` 时跳转 hnubl；`1` 经常驻 Root 服务原子消费为 `0` 后，才获得一次实际续接尝试。

重启控制同时覆盖上游的手动／自动分类和实际系统重启出口。按当前 DEX 的协议参数、方法签名、真实调用与回调关系定位厂商路径；许可在最终出口统一消费，避免上游准备、异步回调和末端重复消费。自动及未证明的来源不会花掉手动许可。原厂更新准备、关联安装、官方切槽接口及最终原始重启调用保持由原实现执行。

目标更新进程安装 Hook 时执行一次运行时去优化，减少已编译调用绕过替换方法的情况；不使用逐帧扫描或持续去优化。该机制仅作用于已限定的更新进程。Frida 官方说明指出，部分优化会影响方法 Hook，提供了对应的[去优化 API](https://frida.re/docs/javascript-api/)。这项机制是兼容防护，不代表已证明现场具体发生了内联绕过。

获得许可后，执行器通过当前平台的 typed `IUpdateEngine` 接口读取官方状态。官方处于 `UPDATED_NEED_REBOOT` 时调用原生 `setSlot()`，由官方实现选择、验证和提交待完成更新；不依据旧本地 target 手动切槽。本地计划不存在、属于旧启动或旧阶段，都不再被静默解释为“没有动作，直接重启”。官方已取消、已清理、无待重启任务、服务异常或 setter 失败时，明确返回已授权的执行失败，并保留当前系统。

本地计划用于诊断和关联。其写入失败记录为警告，不能把已确认成功的官方动作伪报为未执行，也不会自动重放动作。文件许可仍按一次尝试消费；新的显式许可才允许再次尝试。安装器、Root 传输身份认证及私有文件保护保持原合同。

## 日志与已有功能

诊断导出新增 `/data/adb/hnubl/service.log`，与完成标记、原生计划、定位结果、模块状态及 journal 一起保存。根日志增加实际保护就绪、入口和最终出口的调用关联，便于区分“已赋 Hook”“实际进入 Hook”“官方切槽执行结果”和“原始重启调用”。诊断导出仅读取状态，不消费许可、不刷写、不切槽、不重启。

既有全量包、版本过滤、WebUI、倒计时控制及按钮文字性能优化保留。提权资产、镜像修补核心、KBL、分区备份与刷写实现保持上一版内容。

## 验证边界

验证使用四份实际更新 APK、名称和 DEX 布局变化、功能目标缺失负例、生产 JS／Java 及最终编译产物。重点覆盖：文件未授权误点、独立手动路径、自动路径、异步准备、最终出口一次消费、模块重装、服务重开、旧启动计划、无本地计划及官方回退取消。

主机的受控线程、Binder、HAL 和文件端口可验证这些执行路径，不等同于已经完成目标设备真实 OTA 和重启。本次现场的官方日志已确认快照被取消并覆盖部分 A 槽静态分区；后续真机测试应重新准备官方 OTA 任务，而不是沿用已被清理的旧任务或手动猜测目标槽。

## English

hnubl and the companion module are both **2.9.9 / versionCode 98**. The root-owned `freeotaok` file remains the only business permission. A value of one is consumed atomically to zero for one continuation attempt; missing, empty or zero redirects to hnubl.

Reboot protection covers both the upstream manual/automatic context and the actual platform reboot outlet. Vendor metadata follows current DEX signatures, protocol arguments and concrete call/callback relationships, rather than model, version, address or obfuscated-name tables. Permission is consumed at the final outlet, avoiding duplicate consumption across preparation and asynchronous callbacks. Automatic and unproved sources do not spend a manual grant. One-time target-process deoptimization reduces optimization-related method-hook bypass; it is not evidence that inlining caused the reported incident.

After authorization, the typed official `IUpdateEngine` status must describe an update pending reboot before its own `setSlot()` is invoked. The vendor implementation selects and commits the actual pending update. Missing or old local diagnostic plans no longer silently turn into a successful reboot with activation skipped. Cancelled/cleaned updates, unavailable services and failed official calls are explicit authorized execution failures. Local diagnostic persistence warnings do not undo confirmed official success or replay a mutation.

Diagnostic export now includes the root service log, and runtime lifecycle/outlet events are correlated. Existing privilege assets, partition operations, image patching core and KBL remain unchanged. Host verification includes real updater APKs, structural changes, negative cases and final extracted artifacts; it does not establish a successful physical-device OTA or reboot.
