# 2.9.7：OTA 续接恢复与诊断

## 修复内容

在真实原生更新完成后，完成通知若在根服务持久化之前遇到通信故障，旧代码会丢弃本地完成凭据。根服务因此可能一直保留 `DEFERRED`，即使 `freeotaok` 为 `1`，点击重启仍会跳转 hnubl。

新代码先将真实 `Terminate` 返回所对应的完成凭据原子保存，再确认同一启动、源槽、引擎与更新周期，重发幂等的 `DONE` / `FAIL` 通知；收到匹配且持久化确认的回复后释放内存凭据。重试采用退避定时器，仅在存在待确认通知时运行；不会重放 HAL、系统更新、刷写或重启操作。明确识别出新周期或失效身份后，旧通知停止重试。

原子保存的凭据位于 `/data/misc/update_engine/hnubl-module/completion-更新周期UUID.json`，仅保存真实完成的事件、结果和身份字段。文件采用 root 0600、私有目录 0700、固定周期文件名、完整写入及文件／目录 fsync。更新进程退出后，根服务在消费重启许可时，可从这份凭据恢复同一周期的完成状态，再继续原有切槽提交与系统更新流程。恢复只针对 `DEFERRED`，不会覆盖 `FAILED`、`COMMITTING` 等状态。

这些凭据从新版模块启用后开始保存。旧版未留下凭据、文件保存失败或身份不一致的计划，仍保留明确的未完成状态与日志；不会仅凭完成标记 `1` 推定原生更新成功。

完成标记与原生切槽计划是两层独立状态。新版本保留原有计划核对与系统原流程续接；不会只凭标记 `1` 把尚未完成的原生流程判为成功。拒绝原因、状态与请求关联信息写入诊断日志。

## 导出日志

在 hnubl 的 OTA 页找到“运行日志”，点击 **导出 OTA 诊断日志**，通过系统文件选择器保存 `hnubl_ota_logs_yyyyMMdd_HHmmss.zip`。建议故障出现后立即导出，保留现场；日志收集本身只读取状态，不消费完成标记、不切槽、不重启、不刷写。

ZIP 包含应用版本、更新应用版本、OTA 页面日志，以及 Root 可读的完成标记、原始标记字节、原生计划、当前周期完成凭据、发现结果、模块状态与版本、选项、事件日志及限定标签的 Android 日志。部分证据读取失败时仍导出其余内容，具体原因见 `collection.properties` 和错误输出。

根服务详细日志保存在 `/data/adb/hnubl/logs/ota-events.jsonl`，另保留 `.1` 轮转文件，合计约 2 MiB。日志按事件记录，不在成功的 STATE 轮询、界面逐帧或滑动过程中反复写入。记录包含时间、请求关联、真实标记采样、计划阶段、原生通知与拒绝原因；排除认证秘密和分区镜像。Android 标签日志是否完整还取决于系统日志缓冲区。

## 兼容方式与验证边界

软件更新的钩子继续通过 DEX 功能语义、协议参数、调用关系与原流程汇合点定位；没有添加针对 17.0.33.135 的类名、方法名、固定地址或版本适配表。既有完成页文字识别和滑动性能优化保留。

本版以实际 APK 的发现结果、生产 JS 与 Java 状态机进行离线模拟；覆盖通知丢失、确认丢失、幂等重试、计划拒绝、旧新更新应用及标识变动。离线通过证明这些受控路径符合约定，不代表远端设备已经完成真实 OTA／切槽／重启验证。现场问题仍需同次点击的日志确认具体分支。

## English

Version 2.9.7 atomically saves genuine native completion evidence before notifying the root service, and retains its in-memory copy until an acknowledged, durable `DONE` or `FAIL` response arrives. Transient transport failures are retried with backoff after validating the same boot, source slot, engine and update cycle. The retry only resends the idempotent notification; it never replays HAL calls, updates, partition writes or reboot operations.

If `update_engine` exits, the root service can recover a matching `DEFERRED` plan from its private, UUID-named completion file before consuming the reboot permission. File ownership/mode, boot, cycle, source/target slots, original engine identity and the actual result must match. Recovery does not override failed or ambiguous commit states. Evidence absent from an older module, or rejected/missing evidence, is not reconstructed from the marker alone.

The completion marker and native deferred-slot plan remain separate checks. A marker value of `1` does not manufacture native completion. Denials now include correlatable diagnostic evidence.

Use **Export OTA diagnostics** in the OTA page's log section and save the ZIP through the system document picker. It includes application/updater metadata, the OTA console, controlled module state files, the private event journal and tagged Android logs. Partial evidence is retained with explicit collection errors. The journal uses event-driven logging with two approximately 1 MiB files; successful STATE polling and scrolling do not generate repeated journal writes. Authentication secrets and partition images are excluded.

Updater discovery remains semantic and data-flow based, without a version-specific address/class table. Offline simulations execute production policies against controlled persistence, transport and HAL ports; they do not establish a successful OTA or reboot on the unavailable remote device.
