# honor_8e5_ubl

基于 Kotlin、Jetpack Compose 和 [Miuix](https://github.com/compose-miuix-ui/miuix) 的 Android 工具，整合无线调试配对、提权、分区备份及 GBL 引导写入流程。

当前源码版本为 **2.3.4（versionCode 22）**，应用包名为 `net.hawyn.honor8e5ubl`。应用冷启动默认进入“提权”页面，沿用已集成的提权实现；设备的内核、分区布局及引导兼容性仍需分别确认。

## 更新记录

**2026-10-25 · 2.3.4**

- 冷启动默认进入“提权”页面，页面恢复、左右滑动和返回键行为沿用原有实现。
- 已有 efisp 时跳过 data 缩容和 GPT 创建，改用直接读取的文件系统几何核对现有分区边界，随后备份并按当前槽位 ABL → efisp 的顺序写入。
- F2FS 历史停止计数和历史错误位图仅用于诊断；保留当前 checkpoint、CRC、容量边界及内核缩容结果核对。
- 槽位识别允许单一有效来源，要求所有可读取来源一致，并在写入前再次确认。
- 本地回归、Release 构建和 lint 通过；真机新建流程完成后的 GPT、文件系统边界及镜像直接回读核验通过。

**2026-10-25 · 2.3.3**

- 修复无线连接反复尝试失效端口的问题，改进多候选轮换、服务发现刷新和 Android 14 及以上系统的持续发现；尚未发现端口时也可点击“连接并获取 Shell”启动发现。
- 四个页面底部统一显示 [https://github.com/hawyn2018/honor_8e5_ubl](https://github.com/hawyn2018/honor_8e5_ubl)，点击访问、长按复制，隐藏额外操作提示文字。
- 提权红字补充：“提权过程中可能崩溃重启属正常现象，再次连接无线调试进行提权即可”。
- 保持版本号 2.3.3、提权实现、解锁脚本及镜像内容不变。

完整修改和验证摘要见 [CHANGELOG.md](CHANGELOG.md)。

## 功能

- **提权**：应用内无线调试配对与 Shell 连接，连接成功后运行已集成的 MagicRoot 提权流程，为本应用建立 Root 命令通道，并显示执行日志。
- **备份**：提取当前槽位的 `init_boot`、`vbmeta`、`abl`，保存 GPT 分区表备份，并额外生成 `vbmeta_patched.img`。输出目录为内部存储的 `Download/ublbackup_yyyyMMdd_HHmmss`，时间取自实际开始备份时的设备时间。
- **解锁**：提供直接解锁和自定义镜像两种入口，执行分区准备、`efisp` 创建或复用、当前槽位 ABL 与 EFI 镜像写入及校验，完成后提供进入 Recovery 的入口。
- **设置**：提供 Monet 取色、强调色、玻璃模糊、悬浮底栏等外观选项，页面支持左右滑动切换。

内置 EFI 资源仍名为 `app/src/main/assets/images/efisp.img`，内容对应 `HONOR-SM8850-freedom.pe`，SHA-256 为：

```text
c4b2a9027b0d03134a1d62ccd07c9ede5b3e40fd86d6264f95b3b2cf75b94c15
```

提权、备份和直接解锁入口均提供等待时间提示。执行分区写入前，请将备份导出设备。

## 构建

构建环境：

- JDK 21
- Android SDK Platform 37、Build Tools 37.0.0
- Android NDK r29，包含 ARM64 编译工具链
- Gradle 9.6.1，使用仓库中的 Gradle Wrapper

在项目根目录通过 PowerShell 配置 SDK 和 NDK 路径后构建：

```powershell
$env:ANDROID_HOME = Read-Host "Android SDK 目录"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_NDK_HOME = Read-Host "Android NDK r29 目录"
.\gradlew.bat :app:assembleRelease
```

Release APK 输出至 `app/build/outputs/apk/release/`。当前构建配置使用本地自动生成的 Debug 签名配置签署 Release，仓库不包含签名密钥；不同签名构建之间的覆盖安装需要保持签名一致。

如需构建调试版本，执行 `.\gradlew.bat :app:assembleDebug`。Debug 版本使用 `.debug` 包名后缀，并关闭分区写入入口；实际写入流程使用 Release 构建。

## 源码结构

| 目录 | 内容 |
| --- | --- |
| `app/` | 主应用、Compose 界面、提权对接、备份、分区处理及镜像资源 |
| `rootbridge/` | 辅助 Root 桥接应用源码 |
| `tools/` | 设备检查与 Root 桥调用辅助脚本 |
| `third_party/toolchain/` | 随附工具对应的上游源码归档、构建脚本和许可证 |
| `app/src/main/assets/licenses/` | 第三方组件许可证、来源说明与声明 |

此仓库仅发布源码和所需资源，不发布 APK 或 GitHub Releases，也不包含运行日志、个人设备日志及签名密钥。

## 许可证

本项目源码采用 **GPL-3.0-only**，详见 [LICENSE](LICENSE)。第三方组件继续适用各自的许可证，原有声明保留在 `app/src/main/assets/licenses/` 及对应第三方目录中。
