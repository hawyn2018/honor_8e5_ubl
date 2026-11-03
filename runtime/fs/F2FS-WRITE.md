# F2FS writer 当前边界与下一阶段

`f2fs_rw_core.c` 是独立实现的原位覆盖 writer，包含 GPLv3+ 的现有 GRUB
读取器以共享精确的 inode/NAT/块映射布局。它没有复制 GPLv2-only
f2fs-tools 的 fsck/sload 写入代码；官方工具用于格式理解、制作独立 fixture
及写后 fsck 验证。`f2fs_rw.c` 持有用户 access、dirty、sticky error 和文件
资源，既有只读 `f2fs.c` 继续独立提供 RO 模式。

## 已实现的真实写入

- 已有普通文件、现有 size 范围、已分配块中的覆盖；已有 inline 字节覆盖。
- 每次最多 64 KiB，写前预查整个范围，不边写边发现空洞或未实现功能。
- 核对 clean checkpoint、双 superblock、NAT 定位、inode/间接 node footer
  身份与 offset、checksum 字段布局和 inode CRC；拒绝数据与本次 metadata
  路径页重叠，以及同一请求映射重复数据块。
- inline 写入保留 footer，重算包含全 4096 字节 node 的 inode checksum。
- 原全零 inline 的 DATA_EXIST 可为空；覆盖后有非零数据时先置 DATA_EXIST，
  再更新 CRC。干净挂载也拒绝 CP_FSCK 和 orphan 标志，不隐式恢复。
- 保持 size、地址、块所有权、NAT/SIT 计数与 checkpoint 引用不变。
- 写入/flush 失败保持 sticky，保留来源与资源，不盲重试；部分结果仅统计
  已确认的用户数据字节，不把 inline 部分写等同于完整 inode 成功提交。

这条路径信任健康文件映射，不是损坏文件系统修复工具。当前没有全面核对
所有目标的 SIT valid bit 和 SSA owner/version，也不提供断电原子性。
创建、增长、截断、填充未分配空洞、mkdir、unlink 和 rename 返回 ENOSYS。
verity、加密、压缩、immutable 与 append-only 文件拒绝覆盖，时间戳保持原值。

## 完整分配器所需边界（尚未实现）

完整写入仍可使用 `kbl_fs_device` byte IO 与 `kbl_fs_host` allocator，保持
同步、无 Linux syscall 和同 EL；其内部需要独立事务对象，不能把新建文件
简单变成一次 data write。

| 阶段 | 内部职责 | 对外成功条件 |
| --- | --- | --- |
| 只读准备 | 选择一致 superblock/checkpoint，处理所需恢复语义，检查特性、quota 与空间预算。 | 未写介质即可拒绝不支持的状态。 |
| 分配规划 | 分配 NID、空闲 segment/block，读取 NAT/SIT 双副本与 journal、SSA owner；构造目录与 inode 修改计划。 | 预留足够空间且不覆盖旧 checkpoint 仍引用的有效块。 |
| 写新 data/node | out-of-place 写入，更新 inode checksum/footer、summary、NAT/SIT 差异；保留旧映射。 | 每个实际 I/O 返回准确的已确认字节；失败不重放。 |
| metadata 与 checkpoint 提交 | 写相应 metadata 副本/summary，按设备 flush 屏障完成新 checkpoint pack 的两端及 CRC/version。 | 新 pack 完整有效后才切换对外引用；断写仍有旧有效 checkpoint。 |
| 闭卷/回读 | 独立读取新 checkpoint、文件内容和元数据；官方 fsck 检查 NAT/SIT/计数。 | close/sync/unmount 不丢弃失败事务，读回与空间账本一致。 |

新建、unlink、rename、truncate 还涉及目录散列/inline 转换、link count、
orphan、quota、回收与失效块处理。真实 userdata 容量远大于测试镜像，
NAT/SIT 应分页或使用有界缓存，避免按整块数分配导致内存耗尽。事务的
失败状态、资源所有权和可回收时点必须与 native OFF 协调。

这些是后续 backend 内部扩展，不需要更改现有 SDK ABI1/136 字节数字接口，
也不以 RAM overlay、整镜像重打包或伪造写入成功替代真实分区写入。
