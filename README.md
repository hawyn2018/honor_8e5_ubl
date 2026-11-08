<div align="center">
  <img src="docs/assets/hnubl-logo.png" width="128" height="128" alt="hnubl app icon" />
  <h1>hnubl</h1>
  <p><sub>作者官方联系方式如下，其他来源请勿轻信！</sub></p>
  <p>
    <a href="https://qm.qq.com/q/8ax2kPeghy"><strong>QQ交流群 · 941336897</strong></a> &nbsp;·&nbsp;
    <a href="https://t.me/blroot"><strong>Telegram · blroot</strong></a> &nbsp;·&nbsp;
    <a href="https://www.coolapk.com/u/43879986"><strong>酷安 · hawyn</strong></a> &nbsp;·&nbsp;
    <a href="https://m.tb.cn/h.jgDi6un?tk=4QbA6Z6JhAO"><strong>闲鱼 · 2018hawyn</strong></a>
  </p>
  <p><strong>hnubl专为荣耀8e5设备解锁BL+假回锁适配的全功能解锁神器，目前已合入保留锁态OTA功能。</strong></p>
  <p>A full-featured bootloader unlock and simulated relock toolkit for HONOR 8e5 (Snapdragon 8 Elite Gen 5) devices,<br/>now with OTA support that preserves the configured lock state.</p>
  <p><code>3.1.4</code> &nbsp;·&nbsp; Android / Miuix &nbsp;·&nbsp; <a href="LICENSE">AGPL-3.0-only</a> &nbsp;·&nbsp; Source-only releases</p>
  <p><a href="#简体中文"><strong>中文版</strong></a> &nbsp;|&nbsp; <a href="#english"><strong>English</strong></a></p>
</div>

---

## 简体中文

**hnubl专为荣耀8e5设备解锁BL+假回锁适配的全功能解锁神器，目前已合入保留锁态OTA功能。**

从无线调试连接、提权、原始分区备份，到 ABL → EFISP 修补、分区准备、解锁刷写和 OTA 后的保留锁态处理，在一个应用中完成。界面基于 [Miuix](https://github.com/compose-miuix-ui/miuix)，提供动态配色、玻璃模糊和可滑动的五页导航。

**3.1.1 内核适配补充：** APH-AN00 `6.12.58-android16-6-g142ddad31e60-abogki576095858-4k` 已接入实际 98260 与 64650 提权实现和内置执行文件，按完整 `uname -r` 选择 G142 路由，不需要新增 KSU。保留 3.1.0 的 OTA、备份、分区与解锁流程。详细偏移和验证边界见 [APH g142 内核适配](docs/aph136g142-kernel-adaptation.md)。

**导航：** [功能](#功能一览) · [支持设备](#支持范围) · [使用流程](#使用流程) · [OTA](#保留锁态-ota) · [构建](#从源码构建) · [许可证](#许可证与第三方组件)

### 功能一览

| 页面 | 提供的功能 |
| --- | --- |
| **提权** | 应用内无线调试配对和 Shell 连接；已有 Root 检查；98260 / 64650 两条手动提权入口；自定义 `su` 路径对接。 |
| **备份** | A/B 双槽 `init_boot`、ABL 原始镜像；当前槽位标记；主备 GPT、分区清单与 SHA-256 校验记录。 |
| **OTA** | 标准 OTA、尝鲜 OTA、自定义 OTA；安装或更新配套 KSU 模块；仅更新 EFISP、导出 EFISP，以及旧 EFISP 增加功能。 |
| **解锁** | 假回锁解锁、仅解锁、自定义刷写；按来源生成 EFISP；创建或复用 `efisp` 分区；ABL 槽位选择和写后回读。 |
| **设置** | Monet 取色、强调色、玻璃模糊、悬浮底栏、随应用配色的桌面入口、自定义 `su`、支持与中英双语许可证。 |

**按功能和数据流适配。** 修补器依据指令、协议参数、缓冲区及调用关系定位功能，动态安排补丁空间，兼容可识别的旧成品重新导入。没有型号、固件版本或固定地址适配表；核心目标缺失时会报告原因，可选增强未匹配时按对应策略跳过。此设计提高了适配范围，并不代表任意 ABL、固件组合都经过验证。

**随附 KBL 调试环境。** 生成的 EFISP 包含显式启动的 KBL、Fastboot 调试入口和 EFI 应用加载能力。`kblstart` 与 `kbladb` 分开启用，支持文件及分区操作、屏幕开关等功能。KBL 运行于原生 UEFI 环境，具体能力取决于已经识别的固件接口；它与 Android/Linux Shell 的程序接口有区别。使用方法见 [KBL 电脑端工具](tools/kbl_console/README.md)。

### 支持范围

**在搭载高通第五代骁龙 8 至尊版的荣耀设备上提供有限支持。**

| 应用内列出的支持设备 | 目前确定不支持的设备 |
| --- | --- |
| honor magic 8 | honor win rt2 |
| honor magic 8 pro | honor magic 9 |
| honor magic 8 rsr | |
| honor win | |
| honor robot phone | |
| honor magic v6 | |
| honor magicpad3 pro 13.3 | |

这份列表仅用于展示，应用没有按型号设置硬性准入限制。相同处理器也可能具有不同的签名启动链、设备计数、固件接口和系统兼容条件；列表中的名称不构成对所有系统版本的兼容保证。使用前请确认当前设备、镜像和系统组合的适用性。

假回锁是基于 EFISP 的引导策略。Fastboot 显示、镜像接受行为与厂商官方解锁凭据是不同层面的状态，请勿仅凭某一处 `locked` / `unlocked` 显示判断全部启动链状态。

### 使用流程

#### 1. 建立提权通道

1. 打开开发者选项中的**无线调试**，在应用的**提权**页面配对并连接 Shell。配对端口与连接端口是两个不同端口，应用支持自动发现，也可手动填写。
2. 连接后先检查现有权限：`su` 授权成功，或 Shell 本身已经是 UID 0 时，可直接进行备份、解锁等操作。已有 KernelSU / Magisk 授权可使用相应 Root 通道。
3. 尚未获得 Root 时，选择 **98260** 或 **64650**，再手动点击**执行提权流程**。配对或连接成功本身不会自动执行提权链；最终以应用的 UID 0 检查结果为准。
4. 如使用特殊 `su` 可执行文件，在**设置 → 自定义 su 路径**保存绝对路径并开启开关，再回到提权页重新检查。该开关默认关闭。

提权可能需要较长时间。按页面提示清理提权临时文件，并等待日志和权限状态完成更新；若设备重启，需要重新建立无线调试连接后再操作。

#### 2. 备份，并将备份导出设备

在**备份**页面点击**备份分区与分区表**。备份保存到：

```text
内部存储/Download/hnubl/ublbackup_yyyyMMdd_HHmmss/
```

日期时间来自点击开始备份时的设备时间，例如 `ublbackup_20261103_142705`。主要内容包括：

```text
init_boot_a.img
init_boot_b.img
abl_a.img
abl_b.img
slot_active_a  或  slot_active_b
GPT 主备表、分区信息、清单及校验记录
```

`slot_active_a` / `slot_active_b` 的内容只有对应字母 `a` / `b`。当前备份功能不包含 vbmeta 修补。请将备份另存到电脑等设备外位置，再进行分区调整、镜像刷写或清除 data。

#### 3. 选择解锁方式（建议假回锁，深度优化防检测+免清数据解锁）

| 模式 | 默认 EFISP 来源 | 默认 ABL 写入 | 完成后的入口 |
| --- | --- | --- | --- |
| **假回锁解锁** | 当前槽位 ABL → 假回锁 EFISP | 内置 ABL，A/B 双槽 | 显示刷写完成，可选择进入 Fastboot。 |
| **仅解锁** | 当前槽位 ABL → 仅解锁 EFISP | 内置 ABL，A/B 双槽 | 提示进入 Recovery 清除 data。 |
| **自定义刷写** | 可选来源和修补模式 | 可选上传 / 内置 ABL，以及当槽 / 对槽 / 双槽 | 随所选假回锁或仅解锁模式变化。 |

自定义刷写中的 **ABL 写入来源**与 **EFISP 修补来源**分别选择。上传用于写入的 ABL，不会自动替代另一项修补来源。EFISP 可选上传成品原样刷入，或由上传 ABL、当前槽位 ABL、对槽 ABL、内置 ABL 生成。

应用不内置预制 EFISP。生成流程先固定所选来源、完成修补与文字定制，然后使用固定的镜像执行后续步骤。默认场景下，当前槽位 ABL 的提取发生在 ABL 刷写之前。

点击开始后，工具会备份本次任务需要的 GPT 和原始分区，检查 F2FS 与连续尾部空间，按需要调整 userdata 并创建 `efisp`，再写入 ABL 与 EFISP、进行回读校验。**已有 `efisp` 时复用该分区，跳过 userdata 缩容和新建分区。** 分区调整和引导镜像刷写会影响设备启动；请按实际日志确认完成后再重启。

由外部来源修补失败时，完整解锁流程可询问是否使用内置 ABL 重新修补后继续；选择停止会保留失败日志。已有文件的来源选择和实际生成结果可在日志中核对。

### 保留锁态 OTA

此功能用于**系统 OTA 安装完成、尚未重启**的阶段。配套模块负责与系统软件更新协作，hnubl 负责备份及保留锁态处理；最终仍由系统软件更新执行原生收尾与重启。

模块 WebUI 的**获取补丁包**默认开启，升级时保留已保存的开关状态。开启后，系统确认可用的纯热补丁优先由系统软件更新推送和安装，无需执行保留锁态 OTA。没有可用纯热补丁时，仍按**始终获取全量包**开关的原规则选择和比较版本。涉及对槽写入的固件更新继续使用下列流程。全量包、涉及对槽写入的冷补丁及类型不明确的更新都受 `freeotaok` 控制。

#### 推荐顺序

1. **开始系统升级前**，进入 hnubl 的 OTA 页面，点击**升级前请点此处安装或更新模块**，确认日志中的模块状态。相同或更高版本的已安装模块不会被降级重装。
2. 打开系统**软件更新**，按系统流程下载并安装更新。配套模块提供自动更新控制、全量包入口，以及完成后的倒计时 / 重启保护。其 WebUI 可选择**始终获取全量包**：首次安装默认关闭，后续升级保留已有设置；“立即应用”只对软件更新应用应用配置，不重启设备。
3. 安装完成后，系统更新的重启入口在 hnubl 完整流程成功前显示为**继续流程**，点击进入 hnubl 的 OTA 页面。若显示增强在某个版本未匹配，请以实际模块状态和流程日志为准。
4. 选择标准、尝鲜或自定义 OTA，执行**保留锁态 OTA**。工具要求设备已有 `efisp`，并在相关分区写入之前完成相应备份。
5. 完整流程成功后，hnubl 写入完成许可并打开 KernelSU 主页。若未自动跳转，可点击 hnubl 的**KernelSU 对槽安装**。在 KernelSU 中选择**安装到未使用的槽位**并确认目标分区为 `init_boot`。
6. 安装完成后，返回系统**软件更新**点击**立即重启**，由系统原流程完成切槽和重启。

#### 三种 OTA 方案

| 方案 | ABL 刷写来源 | EFISP 处理来源 |
| --- | --- | --- |
| **标准 OTA** | 应用内置 ABL | 当前槽位 ABL 修补。 |
| **尝鲜 OTA（默认）** | 应用内置 ABL | OTA 写入后的对槽 ABL 修补。 |
| **自定义 OTA** | 当前槽位 / 内置 / 上传 ABL | 按所选 EFISP 来源和修补模式处理。 |

手动更改 ABL 或 EFISP 来源会切到自定义 OTA。高级来源还包括不操作 EFISP、直接刷入上传的 EFISP、上传 ABL 修补、设备现有 EFISP 增加功能、上传 EFISP 增加功能等。

OTA 备份目录为：

```text
内部存储/Download/hnubl/hnubl_ota_backup_yyyyMMdd_HHmmss/
```

**仅刷写 EFISP**用于单独更新 EFISP，**导出 EFISP**用于生成并保存镜像。仅刷写完成后留在 hnubl，点击**重启体验新版efisp**才会请求正常重启。仅刷写和导出均不写入 OTA 完成标记，导出流程也不写入分区。

完整 OTA 修补外部来源失败时可确认使用内置 ABL 重试；仅刷写 / 导出失败会显示重试建议。请根据实际所选来源、备份和输出日志判断结果。

### 3.1.4 更新重点

- **OTA 补丁包优先。** 模块新增「获取补丁包」设置。3.1.4 在没有已保存选择时默认开启。只有已明确识别的纯热补丁才使用独立的官方流程；对槽写入和类型不明确的更新继续受 `freeotaok` 控制。旧版已生效的设置在重新应用或重启后采用新默认值，已保存的明确选择会保留。此版 WebUI 的静态说明可能仍写着「默认关闭」，请以开关状态为准。
- **完成 OTA 后打开 KernelSU。** 完整保留锁态 OTA 成功后进入 KernelSU 主页，手动安装到未使用的槽位，再回系统更新重启。仅刷写 EFISP 有单独的手动重启入口。
- **Fastboot `continue` 兼容性。** 提前进入 Fastboot 后，手动 `continue` 会按功能定位并补齐原厂板型和平台初始化，然后继续原厂引导。普通引导、自动切槽拦截和故障转 Fastboot 的策略保持不变。
- **KBL 与内核适配。** KBL ADB Shell 欢迎信息加入项目链接，提权页新增 g142 内核的两条适配路由。

完整累计变更见 [3.1.4 Release](https://github.com/hawyn2018/honor_8e5_ubl/releases/tag/v3.1.4)。

### 3.0.6 更新重点

新版软件更新恢复定向处理。持久缓存仅保存通过真机绑定和重启屏障检查的映射，同一软件更新 APK 的新进程可复用。详见 [3.0.6 兼容修复与验证范围](docs/version-3.0.6.md)。

### 3.0.0 更新重点

**修复保留锁态 OTA 已成功后，返回软件更新仍显示“继续流程”，或点击重启再次回到 hnubl 的问题。应用与配套模块同步为 3.0.0。**

- **完成文件决定业务许可。** `/data/adb/hnubl/freeotaok=1` 表示可获得一次手动续接许可；为 `0`、空或不存在时，入口显示“继续流程”并引导进入 hnubl。移除 2.9.9 错将补丁 `getStatus()` 与主 OTA 等待重启状态比较的 `==6` 门槛，本地 phase、旧启动记录和瞬时界面状态不增加完成条件。
- **官方切槽与原流程续接。** 文件许可原子消费 `1→0` 后，直接调用真实官方 `setSlot()`；成功后交还原生重启流程。实际调用失败仍保留明确错误，许可已消费不等于切槽成功。
- **只刷新必要的按钮状态。** 同一窗口恢复焦点、原点击处理返回后强制读取 STATE，在途强制刷新合并，旧回包和旧 UI 队列不覆盖新结果。稳定同值时不反复扫描整页或改写文字，既有滚动保护与固定尺寸文字监听保留。
- **保留已有保护与适配。** 最终重启出口、手动／自动来源区分、弱身份异步意图、一次消费、目标进程反优化及延迟切槽沿用 2.9.9；提权、备份、分区操作、镜像修补核心与 KBL 保持上一版实现。

**使用顺序：** 升级前在 OTA 页安装或更新模块 → 系统 OTA 安装完成且尚未重启 → 执行完整保留锁态 OTA → 按需要在 KernelSU 安装到未使用的槽位 → 返回软件更新，点击“立即重启”。仅刷写或导出 EFISP 不等同于完整 OTA 流程。

用户已反馈本次问题设备修复成功；离线回归覆盖四份系统更新 APK、受控协议模拟及 12 套 JavaScript 测试，保留原标签 79 例并新增状态刷新 11 例。验证范围不代表所有机型、固件组合均已完成真机 OTA 测试。

GitHub Release 仅提供源码，不附 APK、签名私钥或本地日志；项目许可继续为 **AGPL-3.0-only**，第三方许可与声明保留。详见 [3.0.0 完成文件与按钮刷新](docs/ota-3.0.0.md)、[完成页性能说明](docs/ota-2.9.6.md) 和 [OTA 返回与续接](docs/ota-2.9.2.md)。

---

## English

**hnubl is an all-in-one bootloader unlocking toolkit built specifically for HONOR 8e5 devices, with fake-lock support and integrated OTA handling that preserves the intended lock state.**

It brings wireless-debugging pairing, privilege access, partition backups, ABL-to-EFISP patching, partition preparation, flashing, and post-OTA handling into one application. The interface uses [Miuix](https://github.com/compose-miuix-ui/miuix), with dynamic colors, glass blur, and five swipeable navigation pages.

**Navigation:** [Features](#features) · [Device support](#device-support) · [Getting started](#getting-started) · [OTA workflow](#ota-workflow) · [Build](#building-from-source) · [License](#license-and-third-party-components) · [简体中文](#简体中文)

### Features

| Page | Capabilities |
| --- | --- |
| **Privilege access** | In-app wireless-debugging pairing and Shell connection, existing Root detection, manual 98260 / 64650 entry points, and an optional custom `su` path. |
| **Backup** | Original `init_boot` and ABL images from both A/B slots, an active-slot marker, primary / backup GPT data, partition information, and SHA-256 records. |
| **OTA** | Standard, Experimental, and Custom presets; installation / updating of the companion KSU module; EFISP-only flashing, image export, and feature upgrades for existing EFISP images. |
| **Unlock** | Fake-lock unlock, Unlock only, and Custom flashing; EFISP generation; creation or reuse of the `efisp` partition; selectable ABL targets and read-back verification. |
| **Settings** | Monet colors, accents, glass blur, a floating navigation bar, a launcher shortcut that follows app colors, custom `su`, support, and a bilingual license reader. |

**Function- and data-flow-based patching.** The patcher identifies instructions, protocol arguments, buffers, and call relationships, allocates patch space dynamically, and supports recognized previously patched images. It does not use model, firmware-version, or fixed-address adaptation tables. Missing core targets are reported; optional enhancements follow their own skip policy. This broadens compatibility without guaranteeing every ABL or firmware combination.

**Included KBL environment.** Generated EFISP images include explicitly activated KBL, Fastboot debugging commands, and EFI application loading. `kblstart` and `kbladb` are separate activation steps. File and partition operations, display controls, and other capabilities depend on the firmware interfaces actually identified. KBL runs in the native UEFI environment and has a different program interface from an Android / Linux Shell. See the [PC console documentation](tools/kbl_console/README.md).

### Device support

**Limited support is offered for HONOR devices using the fifth-generation Snapdragon 8 Elite platform.**

| Devices listed as supported in the app | Devices currently confirmed as unsupported |
| --- | --- |
| honor magic 8 | honor win rt2 |
| honor magic 8 pro | honor magic 9 |
| honor magic 8 rsr | |
| honor win | |
| honor robot phone | |
| honor magic v6 | |
| honor magicpad3 pro 13.3 | |

The list is informational: the application does not enforce a model allowlist. Devices sharing a processor can still differ in their signed boot chain, device counters, firmware interfaces, and system compatibility. A listed model is not a compatibility guarantee for every system version; check the suitability of your device and images before use.

Fake-lock is an EFISP-based boot policy. Fastboot labels, image-acceptance behavior, and an OEM-issued unlock credential represent different layers of state. A single `locked` / `unlocked` display does not describe the entire boot chain.

### Getting started

#### 1. Establish privilege access

1. Enable **Wireless debugging** in Developer options, then pair and connect from the app's privilege page. The pairing port and connection port are separate; automatic discovery and manual entry are both available.
2. The app first checks existing access. An authorized `su` session or a Shell already running as UID 0 can be used directly for backup and unlock operations. Existing KernelSU / Magisk authorization can also provide a Root channel.
3. If Root is still needed, select **98260** or **64650** and manually start the privilege workflow. Successful pairing or connection does **not** automatically execute a privilege chain. Readiness is based on the app's actual UID 0 check.
4. For a custom `su` executable, save its absolute path under **Settings → Custom su path**, enable the option, then check access again. The option is disabled by default.

Privilege setup may take time. Follow the on-screen temporary-file guidance and wait for the logs and access status to finish updating. If the device restarts, reconnect wireless debugging before proceeding.

#### 2. Back up and copy the backup off the device

Use **Backup partitions and partition tables** on the Backup page. Files are saved under:

```text
Internal storage/Download/hnubl/ublbackup_yyyyMMdd_HHmmss/
```

The timestamp uses the device's time when backup starts. The backup includes `init_boot_a.img`, `init_boot_b.img`, `abl_a.img`, `abl_b.img`, GPT data, partition information, manifests, and verification records. `slot_active_a` or `slot_active_b` contains the single corresponding letter, `a` or `b`.

The current backup workflow does not patch vbmeta. Copy the backup to a computer or another off-device location before changing partitions, flashing boot images, or clearing data.

#### 3. Choose an unlock mode (fake relock recommended: extensive anti-detection optimization + no-wipe unlocking)

| Mode | Default EFISP source | Default ABL write | Completion action |
| --- | --- | --- | --- |
| **Fake-lock unlock** | Current-slot ABL, patched in fake-lock mode | Bundled ABL to both slots | Reports completion and offers Fastboot. |
| **Unlock only** | Current-slot ABL, patched in unlock-only mode | Bundled ABL to both slots | Offers Recovery with a data-wipe prompt. |
| **Custom flashing** | Selectable source and patch mode | Uploaded / bundled ABL; current / other / both slots | Follows the selected patch mode. |

The ABL image to flash and the ABL used to generate EFISP are independent selections. Custom EFISP inputs include an uploaded ready-made image, an uploaded ABL, current-slot ABL, other-slot ABL, and bundled ABL.

The app does not bundle a pre-generated EFISP image. It snapshots the selected input, completes patching and text customization, and uses that fixed output for subsequent operations. In the default workflow, extraction of the current-slot ABL happens **before** ABL flashing.

The operation backs up the required GPT and original partitions, checks F2FS and contiguous tail space, adjusts userdata and creates `efisp` when needed, then writes ABL and EFISP with read-back verification. **An existing `efisp` partition is reused; userdata shrinking and partition creation are skipped.** Partition changes and boot-image flashing affect startup, so confirm completion in the logs before rebooting.

If a selected external source fails to patch during a full unlock workflow, the app can ask whether to retry with the bundled ABL and continue. Choosing to stop preserves the failure logs.

### OTA workflow

Run this workflow **after the system OTA has finished installing and before rebooting**. The companion module coordinates with the system updater; hnubl performs the backup and lock-state preservation work. Finalization and reboot remain part of the system updater's native workflow.

The module's **Get patch packages** switch is on by default. Upgrades preserve the saved setting. When enabled, the updater prioritizes a confirmed hot-patch-only offer and handles its notification and installation through the native flow. If no such patch is available, the existing **Always obtain a full package** setting still controls firmware selection and version comparison. Full firmware updates, cold patches that write the inactive slot, and ambiguous updates continue through the `freeotaok` workflow below.

1. **Before starting the system upgrade**, open the OTA page and use **Install or update the module before upgrading**. Check the installation / readiness logs. An installed module of the same or a newer version is not downgraded or reinstalled.
2. Download and install the update in the system updater. The module provides automatic-update control, a full-package entry, and post-install countdown / reboot protection. Its WebUI offers **Always obtain a full package**, disabled on first installation and preserved across subsequent upgrades. **Apply now** applies the setting to the updater application without rebooting the device.
3. Until the full hnubl OTA workflow succeeds, the updater's restart action is presented as **Continue workflow** and opens hnubl. Presentation is an optional enhancement; check actual module readiness and logs if a particular updater version does not match it.
4. Select a preset and execute the full **Preserve lock-state OTA** operation. An existing `efisp` partition is required. Relevant backups are completed before the corresponding ABL / EFISP writes.
5. On full success, hnubl records completion permission and opens the KernelSU home screen. If it does not open automatically, tap **KernelSU 对槽安装** in hnubl. In KernelSU, select **Install to inactive slot** and verify that the target is `init_boot`.
6. After installing, return to the system updater and choose **Restart now** to let the updater activate the slot and reboot through its original flow.

| Preset | ABL image to flash | EFISP input |
| --- | --- | --- |
| **Standard OTA** | Bundled ABL | Current-slot ABL. |
| **Experimental OTA — default** | Bundled ABL | Other-slot ABL written by the system OTA. |
| **Custom OTA** | Current-slot / bundled / uploaded ABL | The selected EFISP source and patch mode. |

Changing either source manually selects Custom OTA. Advanced options include leaving EFISP untouched, flashing an uploaded EFISP directly, patching uploaded ABL, or adding features to an existing / uploaded EFISP image.

OTA backups are saved in `Download/hnubl/hnubl_ota_backup_yyyyMMdd_HHmmss/`.

**Flash EFISP only** updates EFISP separately and keeps the completion screen in hnubl. Tap **Reboot to try the new EFISP** to request a normal reboot. **Export EFISP** generates and saves an image without writing a partition. Both operations leave the OTA completion marker untouched.

### What's new in 3.1.4

- **Patch packages take priority.** The module adds a Get patch packages setting, enabled by default in 3.1.4 when there is no saved choice. Confirmed hot-patch-only updates use the native updater independently. Updates that write the inactive slot, or whose type is uncertain, still use `freeotaok`. Legacy active settings pick up the new default on the next apply or reboot, and saved choices are preserved. The static WebUI help in this version may still say “off by default”; use the actual switch state.
- **Full OTA opens KernelSU.** After hnubl finishes lock-state-preserving OTA, install manually to the inactive slot from KernelSU home, then return to the system updater to restart. EFISP-only flashing has a separate manual reboot action.
- **Fastboot `continue` compatibility.** After early Fastboot entry, manual `continue` locates and completes OEM board and platform initialization before resuming the OEM boot flow. Normal boot, automatic slot-switch interception, and fault routing to Fastboot retain their prior behavior.
- **KBL and kernel support.** The KBL ADB shell welcome includes the project link. Both privilege routes support exact matching of `6.12.58-android16-6-g142ddad31e60-abogki576095858-4k`.

See the [3.1.4 release](https://github.com/hawyn2018/honor_8e5_ubl/releases/tag/v3.1.4) for the complete change list.

### What's new in 3.0.6

Restores selective handling for newer Software Update versions. The persistent cache stores mappings only after verified runtime binding and reboot protection. A new process for the same updater APK can reuse the mapping. See [3.0.6 compatibility and validation](docs/version-3.0.6.md#english).

### What's new in 3.0.0

**Fixes the system updater still showing “Continue the process,” or returning to hnubl after a reboot request, even though hnubl’s lock-state-preserving OTA workflow had succeeded. The app and companion module are both 3.0.0.**

- **The completion file decides business permission.** `/data/adb/hnubl/freeotaok=1` grants one manual continuation. Zero, an empty file or a missing file keeps the “Continue the process” entry and routes the user to hnubl. The incorrect `getStatus()==6` patch-status precondition from 2.9.9 is removed; local phases, old boot records and transient UI state add no completion conditions.
- **Official activation and original continuation.** Permission is atomically consumed from `1` to `0`, followed directly by the real official `setSlot()`. Genuine success returns control to the original reboot flow. Actual failures remain explicit; permission consumption is not a successful slot action.
- **Refreshes only the necessary button state.** Regaining focus in the same window or returning from the original click handler forces a STATE read. In-flight forced refreshes coalesce, and stale replies or queued UI work cannot overwrite a newer result. Unchanged values avoid repeated whole-page discovery and text writes; scrolling protection and fixed-size text observation remain.
- **Retains established protection and adaptation.** The final reboot outlet, manual/automatic origin handling, weak-identity asynchronous intent, single consumption, target-process deoptimization and deferred activation retain the 2.9.9 implementation. Privilege handling, backups, partition operations, the image-patching core and KBL retain the previous implementation.

**Workflow:** install or update the module before upgrading → let the system OTA finish installing without rebooting → complete hnubl’s full lock-state-preserving OTA workflow → use KernelSU’s Install to inactive slot when needed → return to the system updater and choose “Restart now.” Flashing or exporting EFISP alone does not complete the OTA workflow.

The user has confirmed the fix on the affected device. Offline regression covers four updater APK versions, controlled protocol simulations and 12 JavaScript suites, retaining the 79 existing label cases and adding 11 state-refresh cases. This does not imply physical-device OTA testing across every model or firmware combination.

GitHub Releases are source-only, without APKs, signing private keys or local logs. Project-owned code remains **AGPL-3.0-only**, with third-party licenses and notices retained. See [3.0.0 marker and button updates](docs/ota-3.0.0.md#english), [completion-page performance](docs/ota-2.9.6.md), and [OTA handoff and continuation](docs/ota-2.9.2.md).

---

## 项目资料 / Project information

### 从源码构建

| 组件 | 当前配置 |
| --- | --- |
| JDK | 21 |
| Android SDK | Platform 37、Build Tools 37.0.0 |
| Android NDK | r29，含 ARM64 LLVM 工具链 |
| Gradle | 9.6.1，使用仓库 Wrapper |

在项目根目录通过 PowerShell 配置本机路径：

```powershell
$env:ANDROID_HOME = Read-Host "Android SDK 目录"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_NDK_HOME = Read-Host "Android NDK r29 目录"
.\gradlew.bat :app:assembleRelease
```

APK 输出到 `app/build/outputs/apk/release/`。当前构建脚本以本机 Debug 签名配置签署 Release；本仓库不包含签名私钥，覆盖安装需要保持签名一致。

开发调试可运行 `:app:assembleDebug`。Debug 构建使用 `.debug` 包名后缀，**关闭分区写入入口**；实际写入流程使用 Release 构建。应用最低 API 配置为 28，设备功能是否可用仍取决于系统、权限及固件接口。

### 源码导航

| 路径 | 内容 |
| --- | --- |
| [`app/`](app/) | Android 应用、Miuix / Compose 界面、权限通道、备份、分区操作与 OTA 事务。 |
| [`abl-efisp-core/`](abl-efisp-core/) | ABL / EFISP 分析、修补与成品处理核心。 |
| [`ota-module/`](ota-module/) | 配套模块、原生与 Java 钩子、协议服务、WebUI。 |
| [`runtime/`](runtime/) | KBL 原生运行环境、UEFI / 文件系统 / ADB 接口。 |
| [`tools/kbl_console/`](tools/kbl_console/) | KBL 电脑端控制台与传输工具。 |
| [`rootbridge/`](rootbridge/) | 独立 Root 桥接辅助应用。 |
| [`docs/`](docs/) | 功能、版本及接口说明。 |
| [`third_party/`](third_party/) | 第三方组件、工具来源、构建材料与许可证。 |

本仓库侧重源码与所需资源；签名私钥、APK 构建产物、个人设备日志和本地运行日志不纳入源码发布。

### 许可证与第三方组件

hnubl 及随附修补器、模块、KBL 中属于本项目的代码采用 **AGPL-3.0-only**，详见 [LICENSE](LICENSE)。应用内提供中英双语阅读入口，首次打开需要同意后继续使用；中文文本用于阅读参考，许可条款以英文原文为准。

第三方组件沿用各自许可证，项目整体许可不替代其原有声明。请保留 [`app/src/main/assets/licenses/`](app/src/main/assets/licenses/) 及 [`third_party/`](third_party/) 中的许可证、来源和 NOTICE。项目按现状提供，设备和固件适用性需要由使用者确认。

---

### Building from source

The current configuration uses **JDK 21**, **Android SDK Platform 37 / Build Tools 37.0.0**, **NDK r29 with the ARM64 LLVM toolchain**, and the included **Gradle 9.6.1 Wrapper**.

From the project root in PowerShell:

```powershell
$env:ANDROID_HOME = Read-Host "Android SDK directory"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_NDK_HOME = Read-Host "Android NDK r29 directory"
.\gradlew.bat :app:assembleRelease
```

The APK is generated under `app/build/outputs/apk/release/`. The current script signs Release with the local Debug signing configuration. Signing private keys are excluded from the repository; updates installed over an existing build must use a matching signing key.

Use `:app:assembleDebug` for development. Debug builds have a `.debug` application-ID suffix and **disable partition-writing entry points**. Use a Release build for the actual writing workflow. The configured minimum API is 28; individual capabilities still depend on the system, permissions, and firmware interfaces.

### Source map

| Path | Contents |
| --- | --- |
| [`app/`](app/) | Android app, Miuix / Compose UI, privilege channels, backups, partition operations, and OTA transactions. |
| [`abl-efisp-core/`](abl-efisp-core/) | ABL / EFISP analysis, patching, and output handling. |
| [`ota-module/`](ota-module/) | Companion module, native / Java hooks, protocol service, and WebUI. |
| [`runtime/`](runtime/) | KBL runtime and UEFI, filesystem, and ADB interfaces. |
| [`tools/kbl_console/`](tools/kbl_console/) | PC console and transfer tools for KBL. |
| [`rootbridge/`](rootbridge/) | Standalone Root bridge helper. |
| [`docs/`](docs/) | Feature, version, and interface documentation. |
| [`third_party/`](third_party/) | Third-party components, source materials, build resources, and licenses. |

The repository focuses on source code and required resources. Signing private keys, APK build outputs, personal device logs, and local runtime logs are excluded from source publication.

### License and third-party components

Project-owned code in hnubl and its included patcher, module, and KBL is licensed under **AGPL-3.0-only**; see [LICENSE](LICENSE). The app includes Chinese and English license readers and requires agreement on first launch. The Chinese text is for reading assistance; the English license text governs.

Third-party components retain their own licenses. Preserve the license, source, and NOTICE files in [`app/src/main/assets/licenses/`](app/src/main/assets/licenses/) and [`third_party/`](third_party/). The project is provided as is; users must establish suitability for their particular device and firmware.

<div align="center">
  <p><a href="#简体中文">中文版</a> · <a href="#english">English</a> · <a href="https://github.com/hawyn2018/honor_8e5_ubl">GitHub</a></p>
</div>
