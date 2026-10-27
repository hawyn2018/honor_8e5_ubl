# 2.3.7 解锁模式与镜像来源

版本为 **2.3.7（versionCode 25）**，包名 `net.hawyn.honor8e5ubl`。三个子页依次为假回锁解锁、直接解锁、自定义解锁；默认选择假回锁解锁。

## 两种修补模式

应用复用用户提供的 ABL-EFISP-fakebl 1.2.5 和 ABL-EFISP-freedom 1.2.3 共享核心。`EfispImagePatcher` 直接调用 `AblEfisp.patch`，假回锁使用 `PatchMode.FREEDOM_FAKE_LOCK`，自由模式使用 `PatchMode.FREEDOM`；随后调用文字扫描、定制与回读接口。应用内不启动这两个独立 APK。

- 假回锁解锁：提取当前槽位 ABL，按假回锁方式生成 efisp；ABL 分区写入内置 ABL。
- 直接解锁：提取当前槽位 ABL，按 freedom 方式生成 efisp；ABL 分区写入内置 ABL。
- 自定义解锁：独立选择 ABL 写入文件、efisp 来源及生成时的修补方式。

普通两种模式的实际来源由 `UnlockRequest.resolved()` 固定为内置 ABL 写入、当前槽位 ABL 生成 efisp。保留的上传文件或自定义来源设置不会传入这两个模式。自定义请求保留自己选择的所有来源，默认修补方式为假回锁。

## 自定义选择

ABL 刷写来源有两项：

1. 使用上传版本。
2. 使用内置版本。

efisp 刷写来源有四项：

1. 刷入上传版本的 efisp：按所选文件内容写入，不执行 ABL 修补或文字定制。
2. 将上传版本的 ABL 修补为 efisp。
3. 将当前槽位的 ABL 修补为 efisp。
4. 将应用内置的 ABL 修补为 efisp。

后三种生成来源可选择假回锁或自由模式，默认假回锁。上传 ABL 可同时作为 ABL 刷写输入与 efisp 生成输入；即使同用一份上传文件，两项操作仍分别按本次选择执行。选择上传来源后需先提供相应文件，未选择文件时保留待选择状态。

## 定制文字

所有生成方式将 fastboot 中的 `PHONE unlocked` 定制为：

```text
device freedom (github hawyn2018)
```

自由模式的开机警告为：

```text
your device has been unlocked and now freedom
unlocktool opensource address
https://github.com/hawyn2018/honor_8e5_ubl
```

假回锁模式保留开机警告资源文字。文字修改遵循共享引擎扫描所得的可编辑范围与最大字节数。fastboot 自动排版产生的换行沿用其已证明的缓冲和布局规则，回读时核对完整文字；开机三行资源按上述文本核对。定制结束后继续核对核心修补内容，避免文字编辑覆盖核心改动。

## 准备与执行

1. 在页面选择模式和来源。点击执行时冻结本次请求；任务中禁用来源切换与重复启动。
2. `Payloads.stage(context, request)` 根据已解析请求选择文件。需要当前槽位 ABL 时，`CurrentAblSnapshot` 识别槽位及分区身份，通过现有 Root 命令通道执行直接读取的 `dd`，在读取前后核对身份。
3. `EfispImagePatcher` 在普通文件上完成修补、文字定制、输出与 SHA-256 回读。应用不再从 assets 读取预制 efisp 镜像。
4. 固定本次 `abl.img`、`efisp.img`、`payloads.sha256`、`payload-source.txt` 与生成报告。当前槽位来源记录绑定槽位、设备身份和原始 ABL 哈希。
5. 保存原有 GPT、ABL 等恢复备份，再按原有在线流程处理 F2FS、userdata GPT 末端以及 efisp 创建或复用。尾部已释放超过 60 MiB 时跳过再次缩容，按实际尾部空间确定 efisp 容量。
6. 实际写入继续保持当前槽位 ABL → efisp 顺序，并执行现有回读核验。源绑定核验贯穿准备与写入，避免换槽或 ABL 内容变化后使用旧生成镜像。
7. 完成后仍由使用者选择是否进入 Recovery。应用请求 `reboot recovery`，数据清空与重建由 Recovery 中的恢复出厂设置完成。

镜像生成在分区变更前完成，避免 GPT 已变更后再因修补或文字资源问题中止。`Payloads.bundle` 直接打包已经固定的输入及备份，保持同一操作包里的镜像与执行来源一致，不在打包阶段重新读取当前 ABL。

`UnlockRequest.encode/decode` 以枚举名称保存完整请求，用于服务间传递及任务记录。非法或不完整记录在解析时被拒绝。模式、来源或上传文件发生变化时，需要重新准备对应任务及镜像。

## 提权接口边界

2.3.6 的 64650 / 98260 两条 RootAdapter 接口和手动启动方式继续保留，默认选中 98260。两条适配器注册槽初始为空，由使用者分别接入实现。Shell 配对、连接与检查成功仅更新 Shell 状态，点击执行提权流程才会调用已注册后端。

后端通过 `RootProcessProvider` 提供每条命令对应的真实进程，并通过现有 UID 0 校验。镜像提取、备份和刷写继续使用这个命令接口；本次合入 ABL-EFISP 修补核心没有补入新的提权链实现。对接说明见 [2.3.6 双接口文档](root-adapter-2.3.6.md)。
