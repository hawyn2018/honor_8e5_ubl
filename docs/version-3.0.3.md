# hnubl 3.0.3 / OTA module 3.0.3

[中文版](#中文版) · [English](#english)

## 中文版

应用与内置 OTA 模块同步为 **3.0.3 / versionCode 102**。本次基于 3.0.2，只修复系统更新应用的官方重启续接和界面扫描调度。

### 重启续接

当前设备的三次诊断记录都显示：`freeotaok=1` 已放行，官方 `UpdateEngine.setSlot()` 返回 `true`，随后记录原 Java 方法返回，但系统没有执行重启。现有日志未发现二次消费、额外状态拒绝或官方切槽接口报错。

离线回归进一步复现了一个对应缺口：Frida 保存的 Java overload 从异步任务调用时，可能再次进入自身 replacement，随后被“准备中”分支直接返回。旧代码因此记录了调用成功，却没有走到真正的原方法。

本版给该次原方法续接增加同步调用范围，按已绑定方法、线程、接收对象和参数精确匹配，仅允许一次自身重入；静态方法按已发现的静态属性处理。退出和异常都清理该范围。它不缓存完成授权，也不放行后续独立点击。

完成状态仍只读取 `/data/adb/hnubl/freeotaok`：值为 `1` 才续接；值为 `0`、空或缺失时转入 hnubl。官方延迟切槽调用及原软件更新重启流程保留，不模拟重启，不新增版本、机型、ABI 或本地状态授权条件。

诊断新增精确的原方法重入与返回事件。`original_returned` 只表示 Java 调用返回，明确不表示设备已经完成重启；旧日志阶段仍可解析。

### 界面性能

按钮语义发现继续使用文字、资源和控件关系，不绑定机型、OUC 版本或固定地址。界面树扫描分片运行，并在页面切换、暂停和卸载时取消；已知候选增量更新，稳定状态不重复进行整树扫描。关键重启拦截覆盖仍保留。

### 验证范围

- 使用真实 agent 代码的本地 Java/ART 行为模型复现：旧版授权后原方法调用次数为 0，新版为 1，完成标记仅消费一次。
- 覆盖静态门、不同 Java 包装对象、UI/工作线程、不同参数/方法/线程拒绝、异常清理及后续标记为 0 的独立点击。
- 回归旧版与新版更新应用的语义文字、控件发现、版本策略、官方延迟切槽及文件标记协议。
- 真机仅进行了 Root 只读日志、状态与帧统计采集；本次没有点击实际重启、切槽、刷写分区或执行系统更新。实际重启完成需由后续 OTA 场景确认。

与 3.0.2 对比，提权适配、备份、解锁、分区脚本、ABL/EFISP 修补核心和 KBL 保持原字节；独立 fakebl 修补器继续为 1.4.0。源码包采用 AGPLv3，排除 APK、签名私钥、本地运行日志与构建缓存。

## English

Both hnubl and its bundled OTA module are **3.0.3 / versionCode 102**. This release is based on 3.0.2 and changes only official updater reboot continuation and UI scan scheduling.

Three captured device attempts granted the file permission and confirmed the official `UpdateEngine.setSlot()` call. The old Java-entry log then returned without a platform reboot. The captured records do not show a duplicate consume, an additional authorization denial or a failed official setter.

An actual-agent host regression reproduced asynchronous Java overload replay re-entering its own Frida replacement and returning from the preparation-pending branch. A synchronous, single-use replay scope now matches the bound method, thread, receiver and arguments. Static methods use their discovered static property. Return and exception paths clear the scope; subsequent independent clicks cannot reuse it.

`/data/adb/hnubl/freeotaok` remains the sole completion permission: `1` resumes the official deferred slot operation and reboot path; `0`, empty or missing redirects to hnubl. No extra model, version, ABI or local-state permission check was introduced. Diagnostics distinguish entering the original replay from a Java method merely returning; neither asserts that a physical reboot completed.

Semantic button discovery retains text, resource and view relationships. Tree traversal is divided into cancellable slices, discovered candidates are updated incrementally, and stable state refreshes avoid another full scan. Critical reboot interception remains enabled.

Host tests cover actual agent callbacks, Java/ART replay behavior, static and instance methods, UI and worker threads, object aliases, one-time consumption, rejection boundaries, cleanup, old/new updater UI, marker protocols and deferred official slot activation. Device work in this release was read-only; no real reboot, slot mutation, partition write or system update was performed. Physical reboot completion still requires a later OTA scenario.

Privilege adapters, backup/unlock flows, partition scripts, ABL/EFISP core and KBL remain byte-identical to 3.0.2. Standalone fakebl remains 1.4.0. Source archives retain AGPLv3 and third-party notices and exclude APKs, private signing keys, local logs and build caches.
