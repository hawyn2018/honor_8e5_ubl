# hnubl 3.0.2 / ABL-EFISP fakebl 1.4.0

[中文版](#中文版) · [English](#english)

## 中文版

本版交付 hnubl **3.0.2 / versionCode 101** 与独立 fakebl 修补器 **1.4.0 / versionCode 34**。OTA 模块继续使用 3.0.1；既有提权适配、分区操作和备份流程沿用上一版。

### 修复与功能

- 改进正常系统引导的 VM DTBO 选择。按 VM 角色、安全域、host VMID 和板型关系识别条目，精确硬件匹配优先；旧表没有当前硬件 tuple 时，使用已经验证的原生条目作为锚点，唯一补齐互补 VM。歧义、冲突或不支持的格式保留原生选择。
- 该接口运行于原生 DTBO 选择与回退完成之后、交给内核之前。按键进入 Fastboot 的路径不运行此扫描。
- 保留普通 `efi`，新增 `efi -cs` / `efi --clearstart`。交接前清理本轮 KBL 会话、挂载、文件、传输、ELF 与跟踪的分配，并停止旧 USB 接收及观察资源；外层固件仍引用的父镜像和驱动继续驻留。
- 手动 `fastboot continue` 调用的 `BootLinux` 若返回，保留原生返回状态、栈与清理流程。
- 新增 `clear errorcount`，仅清除并回读核对本工具的私有 `HnublEfispBootFailures` 记录。

### KBL 用法

```text
efi /efisp.img
efi -bl /efisp.img
efi -cs /efisp.img
efi --clearstart --bootloader /efisp.img
clear errorcount
```

普通 `efi` 延续原加载方式；`-bl` / `--bootloader` 为目标镜像选择 Fastboot。`-cs` / `--clearstart` 显式执行上述交接清理，可与 bootloader 选项组合。交接前须排空当前 KBL 任务的输出；电脑端包装器会继续轮询原任务，不重复派发同一次加载。

### 已完成的验证

本轮设备环境为 BKQ-AN90 10.0.0.108。

| ABL 来源 | 本版生成镜像的观察结果 |
|---|---|
| 应用内置 ABL | 普通 RAM 加载后正常、稳定进入系统；另通过 clearstart RAM 引导 |
| BKQ-AN90 108 原厂 ABL | 普通 RAM 加载后正常、稳定进入系统 |
| `ublbackup_20261024_233110/abl.img` 较新备份来源 | 与当前低版固件组合仍出现早期 `BOOTLOADER_CRASH / PSHOLD`，尚在排查 |

这份备份来源的 SHA-256 为 `504bafcef96d4416bbcdac466a751e462610ce47e5c8ce81a23c2eb2be16ad2e`。本次发布将已验证的内置和 108 来源修复交付，保留该组合的已知限制。

离线检查包括实际应用适配器的 11 项来源及重新导入用例、R4 受影响的 32 项 Gradle 检查，以及 VM 选择、跳转现场和 KBL 资源交接的原生/ARM64 模型。3.0.2 使用冻结的 R4 生产核心；单独的诊断探针继续保存在本地排查目录。

源码包使用 AGPLv3，保留第三方许可证和构建所需资产，排除 APK、签名私钥、构建缓存与本地运行日志。

## English

This release provides hnubl **3.0.2 / versionCode 101** and standalone fakebl **1.4.0 / versionCode 34**. The OTA module stays at 3.0.1. Existing privilege adapters, partition operations and backup workflows are retained.

### Changes

- Improve VM DTBO selection on the normal system boot path. Entries are identified by VM role, security domain, host VMID and board relationships. Exact hardware matches take priority. Older tables may use a validated native entry as an anchor to select one unique complementary VM. Ambiguous, conflicting or unsupported data retains the native selection.
- Run this interface after the original selector and fallback, before the kernel handoff. The volume-key Fastboot path does not run this scan.
- Preserve ordinary `efi` and add explicit `efi -cs` / `efi --clearstart`. Handoff cleans the current KBL sessions, mounts, files, transfers, ELF state and tracked allocations, and stops the old USB receive/observer resources. The parent image and driver remain mapped while outer firmware frames reference them.
- Preserve the native status, stack and cleanup when `BootLinux` returns from a manual `fastboot continue`.
- Add `clear errorcount` to clear and verify only this tool's private `HnublEfispBootFailures` record.

Use the commands shown above. `-bl` / `--bootloader` selects Fastboot for the target image and can be combined with clearstart. Drain the original KBL job output before handoff; the host wrapper polls that same job without dispatching the load again.

### Validation and known limitation

On the tested BKQ-AN90 10.0.0.108 environment, images generated from the bundled ABL and the original 108 ABL booted stably through ordinary RAM loading. The bundled source also passed a clearstart RAM boot.

The newer `ublbackup_20261024_233110/abl.img` source still produces an early `BOOTLOADER_CRASH / PSHOLD` with this older firmware environment. Its SHA-256 is listed in the Chinese section. This combination remains under investigation; it is not marked as fixed by this release.

Offline validation includes 11 actual app-adapter source/reimport cases, 32 affected R4 Gradle checks, and native/ARM64 models for VM selection, injected call state and KBL handoff. This release uses the frozen R4 production core. Diagnostic probes remain separate local investigation artifacts.

Source archives use AGPLv3, retain third-party notices and required build assets, and exclude APKs, signing private keys, build caches and local execution logs.
