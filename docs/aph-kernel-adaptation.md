# APH-AN00 内核适配方案（honor_ubl 2.5.4）

## 目标与原则

本版在两个输入固件上保留现有 61258/6138 支持，并新增 APH-AN00 10.0.0.112 的独立 profile `aph112`。10.0.0.136 经逐项复核后直接复用现有 61258，不重复创建分支。

- 98260 使用 rootd 和 `root-command.sh`，不增加 KSU 管理器或 ksud 依赖。
- 64650 沿用现有 `bfexec` 命令通道；仅生成其模块重定位必需的符号表，不新增 KSU 适配功能。
- 运行时以完整 `uname -r` 选择 profile，未知构建拒绝执行，不按机型限制。
- 保留 2.5.3 的分区、备份、刷写、重试和日志行为；新版为 `versionName=2.5.4`、`versionCode=42`。

## 输入与识别结果

| 固件 | 精确 kernel_release | 解压 Image SHA-256 | 处置 |
| --- | --- | --- | --- |
| APH-AN00 10.0.0.112(C00E110R201P4patch01) | `6.12.58-android16-6-g459a4285ccf8-abogki532718632-4k` | `411d87a6841685733804207d13000683f85b10c9bda1ec0feed0a63480e1f20a` | 新增 `aph112` |
| APH-AN00 10.0.0.136(C00E130R201P5) | `6.12.58-android16-6-g3a7ac1674240-abogki541502914-4k` | `2b158518f3436f2f7efb31c012137d14d06e301b7570ab187a9df1be856fe8ab` | 复用 `61258` |

136 虽与旧 61258 的 Image 哈希不同，但目标常量、KASLR 源码和全部 226 个 LKM 未定义符号的地址均一致，因此复用是安全的静态结论，而不是仅凭 `uname` 猜测。

## 112 的 98260 地址及相对 61258 的变化

以下均为链接期地址；运行时 slide 由 exploit 按本次 boot 动态计算，不能把 slide 写死。

| 常量 | 现有 61258 | 新增 aph112 | 差值（新－旧） |
| --- | --- | --- | --- |
| `LINK_SELINUX_STATE` | `0xffffffc0827e7a60` | `0xffffffc0827e7a20` | `-0x40` |
| `LINK_INIT_USER_NS` | `0xffffffc082572138` | `0xffffffc082572128` | `-0x10` |
| `LINK_ROOT_USER` | `0xffffffc0825723b0` | `0xffffffc0825723a0` | `-0x10` |
| `LINK_INIT_GROUPS` | `0xffffffc082573878` | `0xffffffc082573868` | `-0x10` |
| `LINK_INIT_UCOUNTS` | `0xffffffc082573d48` | `0xffffffc082573d38` | `-0x10` |
| `LINK_ALLOC_TIMER` | `0xffffffc08051a110` | `0xffffffc0805199e8` | `-0x728` |
| `LINK_ALLOC_CRED` | `0xffffffc08019c474` | `0xffffffc08019b950` | `-0xb24` |
| `LINK_ALLOC_TASK` | `0xffffffc0801f0d64` | `0xffffffc0801d11d0` | `-0x1fb94` |
| `GEOM_MEMSTART_ADDR` | `0xffffffc08193af78` | `0xffffffc08193a738` | `-0x840` |
| `BNE_LINK` | `0xffffffc0806c82bc` | `0xffffffc0806c7ac0` | `-0x7fc` |

推导来源：

- 五个数据段符号和 `memstart_addr` 直接来自该 Image 的 kallsyms。
- `LINK_ALLOC_TIMER` 来自 `do_timer_create` 内 `bl kmem_cache_alloc_noprof` 的返回地址，函数内偏移仍为 `+0xac`；过滤了 `alloc_posix_timer._alloc_tag` 记账调用。
- `LINK_ALLOC_CRED` 来自 `prepare_creds` 内同类返回地址，函数内偏移仍为 `+0x2c`；过滤了 `prepare_creds._alloc_tag`。
- `LINK_ALLOC_TASK` 使用 `copy_process+0x190`，并核对了该位置的指令语义。
- `BNE_LINK` 来自 `begin_new_exec` 符号。

## 两版一致的函数内偏移

| 位置 | 偏移 |
| --- | ---: |
| `do_timer_create` 的目标分配调用 | `+0xac` |
| `prepare_creds` 的目标分配调用 | `+0x2c` |
| `copy_process` 的 task 位置 | `+0x190` |
| `begin_new_exec -> release_task` | `+0x410` |
| `begin_new_exec -> down_read_killable` | `+0x50c` |
| `begin_new_exec -> posix_cpu_timers_exit` | `+0x87c` |
| `begin_new_exec -> exit_itimers` | `+0x88c` |

这些偏移在两份固件中通过 ARM64 反汇编分别核对，不是跨版本照抄绝对地址。

## 两版一致的 BTF 几何

| 字段 | 值 |
| --- | ---: |
| `task_struct.cred` | `0x900` |
| `task_struct.real_cred` | `0x8f8` |
| `task_struct.comm` | `0x910` |
| `sizeof(struct cred)` | `0xb8`（184） |
| `cred.cap_effective` | `0x40` |
| `cred.cap_bset` | `0x48` |
| `cred.cap_ambient` | `0x50` |
| `sizeof(k_itimer)` | `0x100` |
| `k_itimer.it.cpu.node` | `0x78` |
| `sizeof(selinux_state)` | `0x80` |
| `selinux_state.enforcing` | `0x0` |
| `selinux_state.initialized` | `0x1` |
| SELinux 写目标 | `selinux_state - 7` |

`CAP_FULL` 继续为 `0x1ffffffffff`。

## 112 的 64560 KASLR 数据

`kaslr_slide_aph112.c` 使用以下函数入口作为锚点：

- `worker_thread = 0xffffffc080209330`
- `rcu_gp_fqs_loop = 0xffffffc08018c334`
- `rcu_gp_kthread = 0xffffffc08018be2c`
- `folio_wait_bit_common = 0xffffffc0802e9b30`
- `ufshcd_issue_dev_cmd = 0xffffffc080e54448`
- `icc_set_bw = 0xffffffc080378af0`

`prepare_creds = 0xffffffc08019b924`，`selinux_state = 0xffffffc0827e7a20`。`syms_aph112.txt` 的 226 个官方 LKM 未定义符号全部解析，`MISSING=0`。136 的同名 226 个地址与现有 `syms_61258.txt` 全部一致。

## Slab、内存与运行期边界

- 头文件回退值沿用 `GEO_TASK_OBJSIZE=5696`、5 objects/slab、8 pages；运行时会读取 `/proc/slabinfo` 并用真实 task cache objsize 覆盖该回退值。
- `GEO_POSIX_TIMER_OBJSIZE=256`、32 objects/slab、order-1、fragment page 8192，与 BTF 和现有 6.12 结构一致。
- `GEOM_MEMSTART_PFN=0x80000` 为现有映射的静态推断值，首次上机需用 `/proc/iomem` 或内核日志复核。
- KASLR slide 必须每次启动重新测量，不能由本文件或旧设备日志代入。
- 当前完成的是固件静态推导、编译和打包验证，**没有把静态推导宣称为这两种固件上的真实 UAF 已成功**；首次上机应先做 `--check` 和 KASLR 交叉校验，再验证 rootd。

## 接入位置

- 112 的 98260：`app/src/main/assets/root_extra/g459a/98260/`
- 112 的 64560：`app/src/main/assets/root_extra/g459a/64650/`
- 112 目标头：`app/src/main/cpp/pc98260_target_aph112.h`
- 两版机器可读 profile：`adaptation/aph112/adaptation-profile.json`、`adaptation/aph136/adaptation-profile.json`
- 运行选择：`NewKernel98260Adapter`、`NewKernel64650Adapter`、`run98260.sh`、`root-command.sh`、`kernel_profiles.sh`

`NewKernel98260Adapter` 使用 `/data/local/tmp/hnubl-g459a-98260`，`NewKernel64650Adapter` 使用 `/data/local/tmp/hnubl-g459a-64650`。`run98260.sh` 将 112 映射到 `98260-aph112`，`root-command.sh` 将同内核的 root 命令映射到该目录的 `pc98260-su`。`kernel_profiles.sh` 将 112 映射到 `kaslr_slide_aph112` 和 `syms_aph112.txt`；136 继续走原 61258 路径。
