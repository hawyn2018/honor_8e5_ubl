# hnubl 3.0.4 / OTA module 3.0.4

[中文版](#中文版) · [English](#english)

## 中文版

应用和内置 OTA 模块同步更新为 **3.0.4 / versionCode 103**。本版基于 3.0.3，修复软件更新主页和版本详情长时间等待显示的问题。

### 原因与修复

实机复现中，旧版详情页的系统 `START → Displayed` 为 9580 ms，主线程持续占用 CPU，同时出现大量 ART 去优化警告。只停用可选按钮文字处理后，详情仍需 7124 ms，说明主要开销超出了标签扫描。

本版将全进程去优化改为按功能发现的关键和可选 OTA 方法及其精确应用调用关系进行定向处理。使用运行时 PowerManager 接口类型、真实 Binder 身份和事务语义，在原生 Binder 发送入口保留最终重启屏障；普通事务及其他 Binder 保留原行为。完成授权仍只来自 `freeotaok=1`，并保留官方延迟切槽及原重启续接。未分类调用不能借用已完成的授权。

方法计划由实际 DEX 功能和调用关系生成，没有机型、固件版本或固定地址适配表。计划采用 gzip/Base64 传输，保留完整精确应用引用闭包；该计划明确不宣称完整 CHA 内联证明，最终重启保护由 native 屏障承担。能力或语义校验不成立时，明确记录并使用既有全局保护回退。

同时修正 IInterface.asBinder 继承接口解析、opaque JNI 方法 ID 解码，以及定向去优化的 ART 锁协议。现代定向分支在 Native 状态预解析后，调用已有全线程暂停机制取得独占条件，再执行方法变更，并于 finally 恢复线程；暂停期间没有 Java 反射或诊断回调。正常流程不再将整个更新进程长期置于解释模式。

标签扫描继续分片、滚动退避和稳定状态免重复扫描；visited 复用现有弱引用，每个节点省去一份 Java WeakReference。

### 实际验证

环境：APH-AN00 10.0.0.136，软件更新 17.0.33.125。

| 情况 | 实际观察 |
|---|---|
| 3.0.3 详情页 | START → Displayed 9580 ms |
| 仅停用可选文字、保留旧全局策略的诊断对照 | 详情页 7124 ms |
| 最终候选、保护已就绪后进入详情 | 1224 ms；本次窗口无 Davey 或 Skipped 记录 |
| 最终候选三轮新进程冷启动 | 均完成定向策略、native guard 与 ready，并在 ready 后继续保持同 PID 存活；无新增 native crash 或重复去优化警告 |

三轮冷进程的选择性初始化为 5671 / 4424 / 3514 ms，页面可在完整异步初始化结束前显示。这些初始化耗时不等于每次导航的等待时间。某次冷页仍有一次短暂长帧；这里不宣称零卡顿，也不将单设备观察推导为所有设备的固定提升幅度。

安全 ART fixture 在独立新进程中执行两轮各 25000 次 descriptor 查询预热，验证拒绝、单次同步放行、重复拒绝、其他 Binder 透传、恢复及普通方法/构造器定向接口。fixture 仅允许 INTERFACE_TRANSACTION/PING，不发送真实 reboot 事务、不访问 OTA 标记或槽位。

本地回归包括四份实际更新 APK 的语义发现、调用关系、压缩与参数长度检查，重命名/DEX 重排兼容，16 组 Hook 测试、11 项原子暂停模型、15 组 JVM 的 568 项检查和应用 OTA 相关 99 项测试。

本轮真机操作只重开软件更新应用、查看页面、滚动和采集诊断；没有点击更新安装或重启按钮，没有刷写分区或重启手机。候选验证中发现的旧锁协议冷起崩溃已单独保留为失败证据，最终三轮通过仅使用修正后的原子暂停版本。

与 3.0.3 对比，提权适配、备份/解锁/分区脚本、ABL/EFISP 核心、KBL 和原生应用库保持原字节。源码包采用 AGPLv3，保留第三方许可证，排除 APK、签名私钥、本地设备日志和构建缓存。

## English

Both hnubl and its bundled OTA module are **3.0.4 / versionCode 103**. This release fixes long updater page-display stalls observed with 3.0.3.

The old detail page took 9580 ms from START to Displayed, with sustained main-thread CPU work and repeated ART deoptimization warnings. Disabling optional button labels alone still took 7124 ms. The final candidate replaces full-process deoptimization with a function-derived exact application dispatch plan and a runtime-bound native Binder reboot barrier. The completion file remains the sole OTA permission source; official deferred slot activation and original reboot continuation are retained.

Runtime interface types, Binder identity and transaction semantics identify the final boundary. Ordinary transactions and other Binder objects preserve native behavior. The DEX-derived plan has no device, firmware or fixed-address table, is compressed with gzip/Base64, and explicitly does not claim complete CHA inline coverage. Unsupported capability or semantics uses a logged legacy protection fallback.

The release also fixes inherited IInterface.asBinder resolution, opaque JNI method ID handling and ART's required exclusive suspension protocol. Modern selective operations resolve their inputs in Native state, suspend ART threads through the existing bridge helper, perform the prepared native calls and resume in finally. No Java reflection or diagnostics run inside the suspended section. UI discovery retains cancellable slices and reuses each node's existing weak reference.

On APH-AN00 10.0.0.136 with updater 17.0.33.125, the final ready-protected detail page measured **1224 ms**. Three fresh cold processes completed selective initialization and native protection, remained alive with the same PID after ready, and produced no new native crash or repeated deoptimization warning in the captured windows. Initialization measured 5671/4424/3514 ms and can finish after the first page is shown; these values are not per-navigation waits. One cold sample retained a short long frame. Results are observations on this device, not a universal latency guarantee.

An independent fresh ART fixture warmed safe descriptor queries twice at 25000 calls per run and verified denial, one synchronous allowance, repeated denial, other-Binder pass-through, restoration and ordinary/constructor method IDs. It can send only descriptor or ping transactions. It does not send real reboot transactions or access OTA marker/slot state. Host coverage includes four real APKs, semantic renaming/DEX reorder, 16 hook suites, 11 atomic suspension models, 15 JVM suites with 568 checks, and 99 app OTA tests.

Device work restarted only the updater application and inspected pages, scrolling and diagnostics. It did not click update installation or reboot buttons, flash partitions or reboot the phone. An intermediate cold crash remains a recorded failure; final cold passes refer only to the corrected atomic-suspension build.

Privilege adapters, backup/unlock/partition scripts, ABL/EFISP core, KBL and app native libraries remain byte-identical to 3.0.3. Archives retain AGPLv3 and third-party notices and exclude APKs, private signing keys, local device logs and build caches.
