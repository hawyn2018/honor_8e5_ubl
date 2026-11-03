# KBL 电脑端控制台（源码原型）

这是 Fastboot 的电脑端前端。设备侧包含独立的 ARM64 UEFI 驱动、RAMFS/VFS、显式文件系统挂载和 KBL SDK 程序加载器。此目录本身不是可刷固件。新挂载功能的本地 fixture、实际 ARM64 代码验证和物理设备记录分别保存，不继承其他功能的真机测试结论。

它使用 Python 3 标准库，调用现有原生 Fastboot。原生工具不会被覆盖。`fastboot.cmd` 仅包装本目录的客户端，名字相同不代表已更改系统中其他 Fastboot。

## 设备端接入完成后的使用方式

在此目录打开终端，Windows PowerShell 使用 `./fastboot.cmd`；CMD 使用 `fastboot.cmd`。客户端会向上查找工作区 `platform-tools/fastboot.exe`，也可显式指定：

```powershell
./fastboot.cmd --kbl-native D:\ai_work\honorubl\platform-tools\fastboot.exe -s SERIAL oem kblstart
./fastboot.cmd --kbl-native D:\ai_work\honorubl\platform-tools\fastboot.exe -s SERIAL oem kbladb
./fastboot.cmd --kbl-native D:\ai_work\honorubl\platform-tools\fastboot.exe -s SERIAL oem kblin
./fastboot.cmd --kbl-native D:\ai_work\honorubl\platform-tools\fastboot.exe -s SERIAL oem kbloff
./fastboot.cmd --kbl-native D:\ai_work\honorubl\platform-tools\fastboot.exe -s SERIAL oem kblstop
```

- `kblstart` 只启动 KBL，不启动 ADB；只有设备报告有效 `KBL1` 会话后才显示 `# ` 提示符。
- `kbladb` 显式启用 KBL 的 ADB 服务；KBL 尚未启动时先创建环境。客户端等待 `adb=active`，USB 重枚举期间仅查询状态，不重发启用命令。已有会话和忙任务保持原样。
- `kblin` 接管已有会话。环境未启动时按错误退出，不偷偷发送 `kblstart`。若已有任务，先接管该任务，再显示提示符。
- 输入 `exit`、`quit`、`:detach`，或按 Ctrl+D / Ctrl+C，仅断开电脑端连接；不发送 cancel、reset、reboot 或 `kbloff`。Windows 终端的 EOF 通常是 Ctrl+Z 后回车，同样只断开。
- `kbloff` 和 `kblstop` 都是明确停止请求，可在另一终端发送，或从交互台断开后发送。客户端没有自动清理环境的 `finally` / 退出钩子。
- `:status` 查询驻留状态；`:help` 显示本地帮助。
- `:push "LOCAL_FILE" /tmp/xxx` 先在 RAM 暂存带 nonce、路径、长度和 SHA-256 的完整封包，设备校验后保存，不自动运行。随后可用 `cd /tmp` 和 `./xxx ARG` 运行 KBL SDK 程序。
- `:pull /tmp/xxx "LOCAL_FILE"` 打开设备不可变快照，核对二进制回读长度及 SHA-256 后保存，并释放快照。目标已存在时明确报错。
- 其他 Fastboot 参数按原样传给原生工具，继承原生标准输入输出，不经过 KBL 解析。例如 `./fastboot.cmd -s SERIAL getvar current-slot`。这条路径不会探测、启动或轮询 KBL。

原版 `fastboot.exe oem kblstart` 只完成一个 OEM 事务；持续读取终端输入、显示 `# ` 是本包装器提供的功能。`--kbl-no-console` 可明确要求仅透传原生命令，适合自动化。

lazy 服务入口仅匹配完整 `oem kblstart`、`oem kbladb`、`oem screenoff`、`oem screenon` 或 `oem screenstatus`；普通 Fastboot 和默认系统引导维持原路径。显示命令不创建 KBL，只有前两条命令会创建运行环境。尚未启动时其他 KBL 命令仍可能收到原固件的 unknown-command 错误。只有显式 `kbladb` 会启用 ADB 并可能引发 USB 重枚举；应答或回复页中断后客户端只重复只读 `kblstatus` 查询，不重发启动或启用命令。重新接管仍要求真实非零会话、EL 及 running/busy 状态；明确的设备 remote FAIL 原样报错。

可用 `KBL_PYTHON` 指定 Python 可执行文件，`KBL_FASTBOOT` 指定原生 Fastboot。包装器自有选项必须放在原生 Fastboot 参数前。常用 `-s/--serial`、`--slot`、`-S` 及部分全局开关支持交互入口识别；未知全局参数直接保留给原生工具，不猜测其参数位置。

## 显示控制与关机（1.3.4）

原版 Fastboot 或本目录包装器均可执行：

```text
fastboot oem screenoff
fastboot oem screenon
fastboot oem screenstatus
```

这三个完整命令可在 KBL 未启动时执行，只加载所需 EFI 服务，不创建 KBL 环境、不启动 ADB。包装器按原样透传它们，不附着交互控制台、不等待会话，不自动重试。其它原生 Fastboot 命令仍按原路径处理。

已有 KBL 的 `#` 提示符或 KBL 的 ADB Shell 中可直接输入 `screenoff`、`screenon`、`screenstatus`。显示操作使用真实 UEFI Display Power 协议；provider 缺失或 Get/Set 失败会报告错误，不以清黑图像当作已经关闭屏幕。成功时状态回复带 provider、Get/Set 的 EFI_STATUS 和固件读回状态。显示开关不等于停止 KBL，打开显示后继续使用同一会话。没有显示接口时，原有 Fastboot 与 KBL 不因而退出。

原生 OEM 命令可能只显示最终 OKAY/FAIL。详细信息仍使用同一份 DEFER_INFO 状态页：`getvar kbl-reply-header` 返回世代与页数，再以 `getvar kbl-reply:GEN:INDEX` 读取；完整字段也可在已启动的 KBL Shell 中输入 `screenstatus` 查看。电脑端 `NativeFastboot.request` 仅接收 `kbl` 前缀命令，显示命令按前述原生 Fastboot 方式透传。`screenstatus` 重新查询固件接口，而非复述上一次 Set 请求。

`poweroff` 是设备侧显式 Shell 命令，与 `kbloff` 停止环境不同。它先同步挂载来源及可写块设备缓存，成功后请求 UEFI Shutdown；同步失败会返回错误而不关机。固件关机可使 ADB/Fastboot 直接断开，主机不会因断连再发一次。exit、Ctrl+C、EOF 和 `kbloff` 都不会暗中发送 `poweroff`。本轮不在物理设备上执行 poweroff，留待使用者手工验证。

新增命令沿用当前 KBL/固件调用上下文，不自行切换异常级或启动 Linux。它们依赖固件提供的真实协议，源码和模型测试不代替特定设备的电源行为验证。

## 命令传输与任务

默认每条线上的完整命令不超过 **60 ASCII 字节**（可用 `--kbl-wire-limit 35..63` 调整），完整 Shell 命令通过协商后的分片按实际可用内存动态增长。旧固件未声明 `command64=1` 时仍按其 4096 字节缓冲处理。

短行使用 `kblcmd HEX`；长行自动使用 `kblput` 分片，每片都带完整上传 ID、至少四位、按需扩展至十六位的十六进制字节偏移，提交时带总字节数。客户端按字节分片，UTF-8 字符跨片也保持原样。NUL 或换行输入会在发送前明确报错，绝不截断后执行。协议见 [KBL_PROTOCOL.md](KBL_PROTOCOL.md)。

提交返回任务 ID；客户端通过独立 `kblpoll` 请求推进有界执行。每次请求结束后，原生 Fastboot 子进程退出，USB 接口不由整个交互提示符持续占有。间隔期间其他 Fastboot 客户端可以尝试正常请求；设备端仍需完成调度和驱动兼容验证，不能仅凭电脑端逻辑证明普通 Fastboot 在真机上始终可用。

传输失败、超时和 Ctrl+C 不触发自动重发。命令可能已到达设备，因此不会自动重新提交或销毁上下文。`kblin` 按设备报告的 busy 任务恢复。

**当前 `kblpoll` 是消费式输出。** USB 中断或 Ctrl+C 恰好发生在响应丢失时，已消费的输出可能丢失；若任务恰在此时完成，重连也不保证能重读完成页。客户端不声称 exactly-once 执行或日志无丢失。可靠输出 ACK/重放是单独的协议升级，未在此版本虚构实现。

## 回读状态

`NativeFastboot.fetch(partition, destination, expected_size=..., expected_sha256=...)` 已有标准二进制 `fetch` 接收逻辑。它要求真实来源的预期字节数和 SHA-256，先接收到同目录临时文件，检查精确长度及摘要，再以独占方式创建目标文件。读取失败、短读或摘要不一致会删除本次临时文件，不覆盖已有文件。

这只是主机 API；设备必须真正实现标准 `fetch`。当前没有为缺失的设备能力编造数据，也没有把海量 INFO 十六进制文本当作分区二进制回读。普通 `fastboot fetch ...` 的直接透传仍由原生工具处理，不会自动获得本 API 的额外校验。

`fetch_kbl_file` 已实现上述快照流程。短路径使用十六进制；较长 UTF-8 路径使用 RAM 暂存封包，避免超过命令缓冲区。空文件需设备报告真实空摘要，不发送不支持的零长度 fetch。关闭快照失败会明确报告，不会偷偷销毁运行环境。设备缺少实际 FETCH/USB 后端时会原样失败，主机不会制造输出文件。

控制回复可由驱动缓存，再通过 `getvar:kbl-reply-header` 和带世代标识的回复页读取。这样每次 OEM 控制事务只调用原生最终应答一次，避免循环复用原 USB 响应缓冲。回复页仅传递有界控制信息；文件和分区内容仍走二进制 DATA。

程序应使用 `runtime/sdk` 的 **general-register-only ARM64 KBL ABI** 编译；文件后缀不限。普通 Linux/Android ELF、动态链接程序、依赖系统调用或 SIMD/FPU 上下文的程序不属于这个执行接口。原生程序需主动 yield；同 EL 特权代码没有硬件故障隔离或强制抢占。

## 文件系统挂载与 ADB 文件读写

只有完整 `oem kblstart` 或 `oem kbladb` 显式启动 KBL 后才执行挂载。ADB 需要单独使用 `oem kbladb` 启用。默认 `mount` 为 **RW**，
需要只读时加 `-o ro`。FAT12/16/32、exFAT 和支持特性内的 clean ext2/3/4
可读写。F2FS RW 仅覆盖已有文件的已分配范围/inline 字节，要求健康 clean
checkpoint 与一致双 superblock；新建、扩大、截断、删除、rename 等返回
ENOSYS。F2FS 仍有显式 `-o ro` 读取模式。EROFS 只读，应明确 `-o ro`，RW
请求返回 EROFS。EROFS
压缩读取当前支持 LZ4，其他算法返回 unsupported。详细格式和加密边界见
[挂载说明](../../runtime/fs/MOUNT.md)。

在新环境的 `#` 提示符下先建空目录、上传固定容量的文件系统 RAM 镜像：

```text
mkdir /tmp
mkdir /mnt
:push "D:/fixtures/fat16-8m.img" /tmp/fat.img
:push "D:/fixtures/payload.bin" /tmp/payload.bin
mount -t fat /tmp/fat.img /mnt
cp /tmp/payload.bin /mnt/payload.bin
sha256sum /tmp/payload.bin /mnt/payload.bin
sync /mnt/payload.bin
cd /
umount /mnt
:pull /tmp/fat.img "D:/fixtures/fat-after.img"
```

容器大小不自动扩展。默认 RAMFS 不设置单文件及文件内容总量的容量阈值，由实际可用内存和分配结果决定（Fastboot 单次 DATA 的真实 32 位长度字段仍保留，更大文件使用 ADB 或已挂载的文件系统），
8 MiB FAT16/exFAT 镜像适合作为首轮 RAM 测试。`TARGET` 必须为预先存在的
空 RAMFS 目录。`:pull` 的不可变快照合同仍只用于独立 RAMFS 文件，因此
应先卸载，再导出镜像；挂载内文件可用 ADB sync 直接回读。

电脑 ADB 示例同样只修改 RAM 镜像。启动时用 `--kbl-no-console` 完成一个
START 请求，再等待设备的 KBL ADB 接口就绪：

```powershell
./fastboot.cmd --kbl-no-console -s SERIAL oem kblstart
adb -s SERIAL shell "mkdir /tmp"
adb -s SERIAL shell "mkdir /mnt"
adb -s SERIAL push "D:\fixtures\fat16-8m.img" /tmp/fat.img
adb -s SERIAL shell "mount -t fat /tmp/fat.img /mnt"
adb -s SERIAL push "D:\fixtures\payload.bin" /mnt/payload.bin
adb -s SERIAL shell "sha256sum /mnt/payload.bin"
adb -s SERIAL pull /mnt/payload.bin "D:\fixtures\payload-readback.bin"
adb -s SERIAL shell "umount /mnt"
adb -s SERIAL pull /tmp/fat.img "D:\fixtures\fat-after.img"
```

只读 F2FS/EROFS 例子分别为 `mount -t f2fs -o ro SOURCE /mnt` 和
`mount -t erofs -o ro SOURCE /mnt`。来源可以是 raw 文件系统 RAM 镜像，或
平台暴露的 `/dev/block/by-name/LABEL`。Android sparse、super/LP 逻辑分区
映射和 dm-default-key/FBE 解密不由 mount 自动完成。

F2FS 已有范围覆盖可用 `dd if=/tmp/patch.bin of=/mnt/existing.bin bs=1
count=N seek=OFFSET conv=notrunc,fsync`。先确认范围位于现有文件大小内且已
分配；普通 `cp` 或默认带 truncate 的 `dd` 不会被偷偷降级为覆盖。原位写
不具备断电原子性，失败写/flush 保留 sticky 错误和资源，不自动重放。

关闭文件、`cd /` 并成功 `umount` 后，才对该来源使用原生 Fastboot
flash/erase。KBL 内的来源保护不等于原生 Fastboot 与文件系统之间有并发
调度。关闭 KBL 会丢失全部 RAMFS 与 RAM 镜像改动，先导出需要保留的文件。
原有命令及 SDK 数字 ABI1/136 字节文件接口保留；挂载不会把普通 Android
或 Linux 可执行文件变成可运行的 KBL 程序。

## 本地测试

```powershell
python -X utf8 -m unittest discover -s tests -v
```

测试运行本地 Python fake-native 子进程，验证精确参数传递、60 字节分片、4097/65537 字节完整命令、动态分页与长路径、Unicode、任务恢复、Ctrl+C / exit 不销毁、会话身份及 EL 变化、二进制文件字节完整性、短读及摘要错误拒绝等。没有打开 USB、ADB 或真实 Fastboot 设备，测试通过不是硬件引导验证。


本版增加 ADB 多独立会话与显式 UEFI 应用启动。Fastboot 交互前端仍使用自己的默认控制会话；独立终端请使用多个 `adb shell`，并可同时 `adb push` / `adb pull`。事务按 FIFO 串行执行。详见源码根目录的 `runtime/uefi/KBL-SESSIONS-APPS.md`。

EFI 临时引导可显式使用 `efi -bl /candidate.img [参数...]`，长选项为 `efi --bootloader /candidate.img [参数...]`。仅对本次读取的 RAM 副本按功能关系定位并设置 bootloader 路径，不修改源文件或刷写分区。输入结构无效、目标缺失或目标歧义时停止，不进入 EFI 执行。普通 `efi /candidate.img` 保留原行为。

该选项只在文件路径之前解析；路径前重复指定会报错，路径后所有参数（包括 `-bl`、`--bootloader`）仍原样参与现有 UTF-16 LoadOptions 编码。执行前后的 EL 核对、错误返回和资源清理保持原有流程。

EFI 文件长度与 PE SizeOfImage 不再受 64 MiB 阈值限制；普通 `efi`、`-bl` 和 `--bootloader` 一致。实际加载仍需完整内存副本及固件 LoadImage 的内存；分配不足会报错并清理，不执行子镜像。PE 字段自身的位宽、结构范围、整数溢出与参数长度检查保留。


STORE 会在设备完成封包摘要和 RAM 文件复制后才返回确认。电脑端对这次暂存和确认的单次等待，使用配置的 `--kbl-timeout` 加上实际封包字节数按 1 MiB/s 换算的时间；等待随文件大小增长，没有额外固定上限。显式的外层重连截止时间仍然生效，成功、异常或中断后恢复原配置，超时后仍须先确认现场状态。

`kblreadopen` 创建快照前尚无可靠的文件长度；该路径保留用户配置的 `--kbl-timeout`。回读大文件时可提前调大配置，客户端不会额外执行探测命令，也不会重放结果不明的请求。
