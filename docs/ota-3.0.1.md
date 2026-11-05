# hnubl 3.0.1：OTA 运行时保持设备策略

应用和内置 OTA 模块同步为 **3.0.1 / versionCode 100**。原 3.0.0 文件及所有较旧交付保留。

## 修复内容

旧内置运行时在 injector 初始化期间会读取当前 SELinux 策略，加入它的类型和规则，再整份加载回内核。Android 的两个 Netlink 专有配置位与标准 policy capabilities 位图独立；同代内核的策略导出和运行时所用 libsepol 转换没有完整保留这两个位。因此，普通 allow 规则没有相应变化时，整份重载仍可能改变 GETLINK／GETNEIGH 的权限映射。这是全局策略副作用，并非软件更新功能需要放宽其他应用的权限。

3.0.1 的下游运行时保留 Frida 正常初始化，但跳过上述自动全局策略修改。其 agent memfd 的完整 SELinux context 改用设备已有的 `u:object_r:ksu_file:s0`，保留原 fd 标签设置函数和处理流程，不新增类型或全局 allow。该路径要求设备的 Root 提供方确实已有有效的 KSU 文件类型，以及目标进程所需的访问权限；其他 Root 环境仍需按实际错误诊断。

原生更新进程的执行内存权限与文件标签是两项独立条件。模块沿用原有原生目标身份核对，读取实际进程域；既有增量规则生成器仅多产生一条该域到自身的 `process execmem` 许可，通过原有 ksud 路径应用。既有规则字符串缓存继续避免重复应用。没有全域、应用域通配、permissive 或新建 Frida 类型的规则，也不按机型或固定的更新进程域名建立许可表。

这是对 OTA 模块自身运行时的兼容性修复，不修改检测应用，也不根据某个检测结果伪造返回值。不同 Root 提供方、系统策略或应用可以存在其他可观察特征；本次修复不承诺所有应用均检测不到设备状态。

官方上游压缩资产、许可证和来源记录保留。下游差异及可复现派生记录与运行时一并核对，明确区分上游文件和模块实际使用的变体。第三方组件继续适用原有许可证，项目许可证保持 AGPL-3.0-only。

## 沿用的流程

- `freeotaok` 仍是保留锁态 OTA 是否完成的唯一业务许可，未增加其他状态门槛。
- 官方流程的暂停、跳转、原子消费和续接、延迟切槽、按钮同步与旧新软件更新应用兼容方式沿用 3.0.0。
- 详细诊断导出、WebUI 配置和滚动性能处理保持原实现。
- 提权资产及对接、备份、分区表与文件系统处理、分区刷写、ABL/EFISP 修补核心、KBL 和签名身份均保持原版本内容。

## 从旧版迁移

停止旧进程或热替换运行时，不等于撤销旧版已经加载进内核的全局策略副作用。该影响需下一次正常系统重启清除。如果系统 OTA 已安装并正在等待重启，请先完成保留锁态 OTA，再按官方升级流程正常重启，不建议直接强制重启。

为比较本次修复，测试需区分同一开机周期的热停用和重新启动后形成的冷态；本说明不要求应用在普通运行中整份回载原厂策略。

## 验证范围

交付核对包含运行时唯一两处声明差异、其余字节保留、XZ 解包一致、模块与应用版本／校验值同步、受保护资源和签名身份，以及本次增量生成器的 15 套 Java 主机回归。新增断言覆盖同域最小权限、真实厂商域名变化、稳定规则缓存键、重复规则和非法类型拒绝；其余原生策略应用流程保持原实现。真机证据按对应测试记录说明其确切范围：运行时附加成功、fd 标签和策略变化范围，不等同于实际执行完整官方 OTA、切槽、刷写或重启。

## English

The application and bundled OTA module are **3.0.1 / versionCode 100**. Earlier releases are retained.

The former injector automatically read, modified and reloaded the whole SELinux policy during startup. Android-specific Netlink configuration bits are separate from the standard capability bitmap. Export and conversion paths can omit these bits, so reloading can change Netlink message-permission mapping even when the relevant ordinary allow rules are unchanged.

The downstream runtime retains normal initialization while omitting this automatic global policy change. Its agent memfd uses the valid, existing `ksu_file` context through the unchanged label-setting code. It creates neither new types nor global allow rules. A provider with that type and the required target permissions is necessary; other Root environments need their own error-based diagnosis.

The module separately adds one same-domain `process execmem` permission for the verified native updater's actual SELinux type. The existing incremental application path and rule-string cache remain. It does not add a broad domain rule, permissive mode or model-specific permission table.

The module does not modify a detector or fabricate detector results. This correction addresses its own runtime side effect and makes no claim that every application can no longer observe other device or Root characteristics. The official upstream archive, third-party licensing and downstream derivation provenance remain explicit.

Version 3.0.0's completion-file permission, official OTA pause and continuation, deferred activation, labels, compatibility discovery, scrolling performance and detailed diagnostics are preserved. Root integration assets, backups, partition logic, ABL/EFISP patching, KBL and signing identity remain unchanged.

Verification distinguishes static preservation, real runtime attachment and narrowly scoped policy changes from a real OTA, slot activation or reboot. The updated native-policy generator is covered by the fresh 15-suite Java regression and its new assertions.

Replacing or stopping the old runtime does not undo a whole-policy reload already performed during the current boot. The next normal system restart clears that old state. If an OTA is installed and awaiting restart, complete the preserve-lock-state OTA procedure before using the official restart flow; do not use a forced reboot as the migration step. A cold-start comparison is a separate observation.
