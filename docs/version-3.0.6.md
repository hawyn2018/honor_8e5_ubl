# hnubl 3.0.6

[中文版](#中文版) · [English](#english)

## 中文版

应用和内置 OTA 模块均为 **3.0.6（versionCode 105）**。

软件更新 18.0.3.230 的定向处理计划包含一个静态初始化方法，运行时绑定失败后触发全进程回退。新规则根据 DEX 调用关系判断。只有可到达的目标全部是只读版本查询，并且初始化方法本身不是绑定入口时，才将其从运行时定向处理计划移除。原始调用路径仍保留在诊断信息中，关键入口、普通调用者、重启保护和官方续接继续执行原有保护。

发现的描述符映射在当前进程成功完成定向绑定、启用原生重启屏障并通过注入器就绪验证后才持久化。同一 APK 的新进程可复用验证过的映射。全进程回退仍可保护当前会话，但该会话不会写入持久缓存。

18.0.3.230 真机验证通过 2784 个定向方法的绑定。第二个进程命中磁盘缓存，扫描次数为 0。17.0.33.135 的离线处理计划和 Hook 逐项一致，18.0.3.206 只排除了一处符合规则的静态初始化方法。60 项 JVM 回归断言通过。本轮验证覆盖绑定、缓存和保护就绪状态，未执行完整 OTA。

## English

The app and bundled OTA module both use **3.0.6 (versionCode 105)**.

Software Update 18.0.3.230 had a static initializer in its selective handling plan that could not bind at runtime, triggering whole-process fallback. The fix uses DEX call reachability. It excludes a non-root initializer from selective runtime handling only if it reaches solely read-only version queries. Its original call path remains in diagnostics. Critical entries, ordinary callers, reboot protection and official continuation retain their prior handling.

A discovered descriptor mapping is persisted only after the current process completes selective binding and verifies the native reboot barrier and injector readiness. A subsequent process for the same updater APK can reuse the validated mapping. Whole-process fallback remains available for the current session, with no persistent write from that session.

On a real device, 18.0.3.230 bound 2,784 selective methods. A second process hit the disk cache with zero discovery scans. Offline checks found an identical handling plan and hook set for 17.0.33.135, and a single qualifying initializer exclusion for 18.0.3.206. All 60 JVM regression assertions passed. The device tests covered binding, caching and guard readiness. A full OTA was outside this test.
