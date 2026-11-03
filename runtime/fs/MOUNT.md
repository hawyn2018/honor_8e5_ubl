# KBL 文件系统挂载

完整 `fastboot oem kblstart` 启动环境后才能使用 `mount`。系统引导、原生
Fastboot 初始化及普通命令不探测或挂载文件系统；挂载操作沿用当前 EL。

## 格式支持

| 格式 | 读取 | 写入 | 限制 |
| --- | --- | --- | --- |
| FAT12 / FAT16 / FAT32 | 支持 | 支持，默认 RW | UTF-8 长文件名；512/1024/2048/4096 字节文件系统扇区；有界 VBR/分区镜像。 |
| exFAT | 支持 | 支持，默认 RW | UTF-8 长文件名、64 位文件大小；文件系统扇区与 backing IO 单元分开处理。 |
| ext2 / ext3 / ext4 | 支持后端识别的特性 | clean 文件系统可 RW | 未 clean、有错误状态或需要 journal recovery 时拒绝 RW；不做离线修复或隐式 journal replay。 |
| F2FS | 支持 | 已有文件有效范围覆盖 | clean checkpoint、双 superblock 一致；仅已分配普通数据块或已有 inline 数据，不分配块、不扩大文件。 |
| EROFS | 只读 | 返回 EROFS | 普通数据及 LZ4 压缩读取；其他压缩算法返回 unsupported。 |

F2FS 读取支持 4 KiB 块、checkpoint/NAT/journal、inline/extra attrs、目录、
符号链接、空洞和三级节点。RW 为已存在普通文件的真实原位覆盖，不是完整
文件系统管理器：新建、扩大、截断、填补未分配空洞、mkdir、删除和 rename
返回 ENOSYS。写前核对 clean checkpoint、双 superblock、NAT/node footer、
目标范围及 inode checksum；inline 写后更新 checksum。verity、加密、压缩、
immutable 和 append-only inode 拒绝覆盖。修改时间当前保持原值。

F2FS 读取最新有效 checkpoint，不进行 roll-forward recovery；异常关机后
尚未进入 checkpoint 的内容可能不在视图中。加密 inode 返回 EACCES，压缩
文件、多设备、大型 NAT checkpoint bitmap 及 16 KiB 块返回 unsupported。
Casefold 目录当前按存储的准确 UTF-8 名称查找。EROFS 多设备、metabox 和
后端未实现的特性返回明确错误。格式支持不代表支持全部可选特性组合。

## 语法、来源与卸载

```text
mount
mount [-t TYPE] [-o ro|rw] SOURCE TARGET
umount TARGET
```

无参数列出挂载。默认 `-t auto -o rw`；需要只读时手动指定 `-o ro`。类型还
接受 `fat`、`vfat`、`fat12`、`fat16`、`fat32`、`exfat`、`ext2`、`ext3`、
`ext4`、`f2fs`、`erofs`。EROFS 应明确指定 `-o ro`；其 RW 请求返回 EROFS。
F2FS 的 RW 受上述覆盖范围约束，未实现操作明确报错，不自动降级或伪造成功。
类型选择不转换、格式化或修复来源。

先用 `mkdir` 建好现有、空的 RAMFS 目录作为目标。根目录、保留命名空间、
重叠/嵌套挂载、重复来源和已打开的来源被拒绝。卸载前关闭文件，并 `cd /`
离开挂载点。失败卸载保留资源，不报告成功。

来源可以是 `/dev/block/by-name/LABEL` 对应的实际 UEFI 分区，也可以是
RAMFS 已有的固定容量镜像文件。镜像从偏移 0 开始包含完整 raw 文件系统，
不自动增大；空间耗尽返回错误。挂载期间来源文件受保护，拒绝原始覆盖、
移动、截断或删除。硬件只读属性保持不变。

完整磁盘镜像的分区表不自动展开。Android `super`/LP metadata 不自动解析
或建立逻辑分区映射，应使用已提取的文件系统镜像或平台实际暴露的独立
分区。Android sparse 传输格式应先展开为 raw 镜像。

Android userdata 常有 dm-default-key 元数据加密及 fscrypt/FBE 文件加密，
依赖硬件 wrapped keys；原始 userdata 并非已解密的 F2FS 设备。本实现不是
解密工具，不创建 Android dm、TEE 或用户密钥服务。相同 EL 不会自动带来
Linux 的解密映射。

## RAM 镜像控制台例子

在新启动的 KBL 交互控制台准备目录，上传已格式化镜像与测试文件：

```text
mkdir /tmp
mkdir /mnt
:push "D:/fixtures/fat16-8m.img" /tmp/fat.img
:push "D:/fixtures/payload.bin" /tmp/payload.bin
mount -t fat -o rw /tmp/fat.img /mnt
ls /mnt
cp /tmp/payload.bin /mnt/payload.bin
sha256sum /tmp/payload.bin /mnt/payload.bin
sync /mnt/payload.bin
cd /
umount /mnt
:pull /tmp/fat.img "D:/fixtures/fat-after.img"
```

`:push`/`:pull` 是电脑控制台本地命令。回读容器前先卸载，得到已同步的
独立 RAMFS 镜像。本版默认 RAMFS 单文件 128 MiB、内容总额 256 MiB；8 MiB
FAT16/exFAT fixture 可用于小镜像测试，128 MiB F2FS fixture 在单文件上限内。
上传、回读快照与后端缓存还会临时使用额外内存；不截短镜像来迁就上限。
原 Fastboot `:push`/STORE 协议仍限制单次 16 MiB；更大的镜像使用 `adb push`
流式写入 RAMFS，再执行同一条 `mount` 命令。该限制不等于挂载容量上限。

只读例子为 `mount -t auto -o ro /tmp/filesystem.img /mnt`，随后使用现有 `ls`、
`cat`、`cp`、`dd`、`sha256sum`；结束时 `cd /`、`umount /mnt`。

## ADB 访问同一挂载

完整 START 成功、KBL ADB 就绪后，Shell 与 sync 文件服务使用相同 VFS。
以下只操作 RAM 镜像：

```powershell
adb -s SERIAL shell "mkdir /tmp"
adb -s SERIAL shell "mkdir /mnt"
adb -s SERIAL push "D:\fixtures\fat16-8m.img" /tmp/fat.img
adb -s SERIAL shell "mount -t fat -o rw /tmp/fat.img /mnt"
adb -s SERIAL push "D:\fixtures\payload.bin" /mnt/payload.bin
adb -s SERIAL shell "sha256sum /mnt/payload.bin"
adb -s SERIAL pull /mnt/payload.bin "D:\fixtures\payload-readback.bin"
adb -s SERIAL shell "umount /mnt"
adb -s SERIAL pull /tmp/fat.img "D:\fixtures\fat-after.img"
```

Shell job 和 ADB sync 会话按单 owner 串行执行，并发可能返回 busy。退出
Shell 或 Ctrl+C 保留 KBL；成功 `kbl off`、`kbloff` 或 `kblstop` 销毁 RAMFS，
包括镜像中的 RAM 改动，需要保留时先回读到电脑。

## 同步、ABI 与原生 Fastboot

原有命令和 **ABI1 / 136 字节文件 SDK API** 保留，挂载路径使用原 file
callbacks。`./program` 仍要求 GPR-only AArch64 KBL SDK 程序，普通
Linux/Android ELF、动态链接和 Linux 脚本不因此获得执行环境。

原始 block/RAMFS close 不自动 flush。挂载文件系统 close 可能提交元数据，
显式 `file_sync`/`sync PATH` 用于确认同步；FAT 成功修改在返回前同步。
短写、失败写或失败 flush 保留错误及资源，不经 close、sync、umount 或 OFF
盲重放不确定写，也不释放仍被失败挂载持有的来源。

F2FS 覆盖保持 size、块地址、NAT/SIT 计数和 checkpoint 引用不变，适用于
健康文件映射，不用作损坏文件系统修复。它不提供断电事务原子性，尤其
inline 涉及整个 inode 页；部分写失败后保留 sticky 错误和来源。
当前覆盖 writer 与完整 allocator 的边界见 [F2FS 写入说明](F2FS-WRITE.md)。

已有文件覆盖可使用 `dd if=/tmp/patch.bin of=/mnt/existing.bin bs=1 seek=OFFSET
conv=notrunc,fsync`。普通 `dd`/`cp` 和 ADB push 带截断或新建语义，F2FS 当前
会明确拒绝这些操作。SDK 示例 `runtime/examples/port_overwrite.c` 则使用现有
文件的 READ/WRITE、seek、sync 接口，先核对整个范围并回读验证，供移植参考。

KBL 内部原始写入保护已挂载来源；原生 Fastboot flash/erase 没有加入
文件系统调度。对分区使用原生 Fastboot 刷写前，先关闭文件、退出目录并
成功 `umount`，避免两条路径同时修改同一存储。

Host fixture、实际 ARM64 编译/仿真与物理设备验证分别记录。前两者通过
不替代某镜像在手机上的挂载、回读或引导测试。
