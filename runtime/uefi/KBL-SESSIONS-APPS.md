# KBL 多会话和 UEFI 应用

本版：Freedom 1.3.4 / fakebl 1.3.4。旧版本文件保留。

## 启动边界

完整输入 `fastboot oem kblstart` 创建 KBL，只有显式 `fastboot oem kbladb` 才启用 ADB；未创建环境时 kbladb 会先创建 KBL。显示控制服务可由完整 screenoff/screenon/screenstatus 显式加载，但不创建 KBL 或启用 ADB。新增会话、文件系统和
UEFI 应用启动器不参与普通系统与 Fastboot 的启动路径。原 Fastboot、父模块入口和默认系统
引导补丁保持原有实现。设备上实际测得的 KBL 执行级别为 EL1。

```text
fastboot oem kbladb
adb shell
```

## 一个核心，多个会话

ADB 服务（Shell 或文件传输）按实际内存扩展，同时保留独立停止控制通道。
每个 Shell 有自己的当前目录、命令、输出和文件句柄归属；RAMFS 和 mount 表共享。
一个会话的 `cd`、关闭或 Ctrl+C 不改变其他会话的目录或销毁 KBL。

例如，保持两个 `adb shell` 窗口打开，第三个窗口仍可使用 `adb push` / `adb pull`。
空闲 Shell 不再占住传输事务。命令和文件事务由同一个执行器按 FIFO 排队；
写操作成功接受后不会自动重放。长事务会延迟后续事务，这是串行执行而非多核并发。
一个传输完成或取消后，后续事务继续执行。

所有会话的工作目录和文件归属均参与卸载、删除及重命名保护。
`exit` / Ctrl+C 只断开当前终端；`kbl off`、`fastboot oem kbloff` 或
`fastboot oem kblstop` 才停止整个 KBL 环境。停止时关闭全部会话，原 Fastboot 保留。

## 显式执行 UEFI 应用

```text
adb push return.efi /return.efi
adb shell efi /return.efi hello
```

KBL Shell 内也可输入 `efi /return.efi hello`。这是标准 ARM64 PE/COFF
UEFI_APPLICATION 的 LoadImage → LoadOptions → StartImage 路径。
只有执行 `efi` 时才加载应用。镜像文件及 PE 的 `SizeOfImage` 不设置固定字节上限，参数按实际长度转换成 UTF-16，并检查 LoadedImage 字段宽度；
返回日志包含 EFI_STATUS、清理状态、调用前后 EL 和完整退出信息；旧提供者兼容原前缀。

普通 SDK 程序仍通过 `./program.kbl` 或绝对路径执行；SDK ABI 1 / FILE136 前缀保持，新版尾接口 MEMORY152 支持按需主机内存。
Linux/Android ELF 和 UEFI PE/COFF 是不同的程序接口。

UEFI 启动是同步、独占的显式事务。应用返回后恢复处理队列和传输；应用运行期间
保留 USB 配置，但 KBL 不额外安装定时器或递归处理 USB，其他命令可能等待或超时。
应用使用同一权限与地址环境，需自行遵守固件 ABI 并返回；这不提供故障隔离。
主动接管 USB、重启或 ExitBootServices 的应用会改变当前固件环境。

示例源码见 `runtime/examples/efi_return/`、`runtime/examples/efi_protocols/`，
重建使用 `python runtime/uefi/build_apps.py`。
只读协议清单示例仅枚举标准 UEFI 协议，不调用未知厂商方法。

## userdata 解密调查

本机 fstab 为 `metadata_encryption=aes-256-xts:wrappedkey_v0`，并启用 FBE。
只读测试确认 metadata 的 ext4 可挂载；实际存在版本 1 的密钥目录、
230 字节的 keymaster_key_blob 和 291 字节的 encrypted_key。
测试仅读取目录、长度和版本，未读取密钥内容或写入物理 data。

挂载 metadata 与解密 userdata 是两个步骤。后者需要经过验证的 KeyMint/Keymaster
解封装、硬件密钥派生及 UFS ICE 加密 IO 接口，FBE 目录还需要对应的用户密钥。
UEFI 真机诊断确认 Qseecom 存在，并成功返回协议清单；这只确认通讯入口，
尚未验证具体 Keymaster 命令和 ICE 数据通路。本版保留显式协议调查入口，
尚未实现本机 userdata 的解密读写。
该限制不阻止多会话、UEFI 应用和已有未加密文件系统功能使用。

依据：

- https://source.android.com/docs/security/features/encryption/metadata
- https://source.android.com/docs/security/features/encryption/hw-wrapped-keys

F2FS 写入范围仍以 `runtime/fs/F2FS-WRITE.md` 为准；本版没有扩大其分配或加密能力。
