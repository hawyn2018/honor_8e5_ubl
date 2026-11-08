# APH-AN00 g142 内核适配（honor_ubl 3.1.1）

## 输入和结论

输入为 `boot_b (1).img`，来自用户指定的 APH-AN00 10.0.0.136 固件资料。它携带的是一个新的内核构建，不能与旧文档中 g3a7a 的 136 或 g459a 的 112 混用。

```text
kernel release  6.12.58-android16-6-g142ddad31e60-abogki576095858-4k
boot header     v4，kernel_size=41,441,792
boot SHA-256    697a6356f2157665fff4926f5f4efbac08c3efcd0d20a8b08c8609ac084c5051
Image SHA-256   f445f7756756ad5c8a58b63c48b12efd69f2e829903e3f009ad2ad6bdfb17c56
BTF             6,926,113 bytes
IKCONFIG        221,656 bytes
kallsyms        129,139 recovered symbols
```

新增 profile 为 `aph136g142`。保留原有 61258、6138 和 aph112，不按机型放行；只有完整 `uname -r` 相等才选择该 profile。

本次沿用 10-30 20:34 方案的双链接口：98260 通过 rootd 和 `root-command.sh` 返回真实 UID 0 进程，64650 通过 `bfexec` 返回 root。没有增加 KSU 管理器、ksud 或新的 KSU 适配功能。已有文件中的模板仅用于原流程兼容和静态符号核对。

## 98260 链接地址

以下均为链接地址，运行时 slide 由 exploit 在当前 boot 上重新测量。

| 常量 | g142 |
| --- | --- |
| `LINK_SELINUX_STATE` | `0xffffffc0827e7a60` |
| `LINK_INIT_USER_NS` | `0xffffffc082572138` |
| `LINK_ROOT_USER` | `0xffffffc0825723b0` |
| `LINK_INIT_GROUPS` | `0xffffffc082573878` |
| `LINK_INIT_UCOUNTS` | `0xffffffc082573d48` |
| `LINK_ALLOC_TIMER` | `0xffffffc08051a918` |
| `LINK_ALLOC_CRED` | `0xffffffc0801935e4` |
| `LINK_ALLOC_TASK` | `0xffffffc0801f06e0` |
| `GEOM_MEMSTART_ADDR` | `0xffffffc08193b1b8` |
| `BNE_LINK` | `0xffffffc0806c8c58` |

调用点由 ARM64 反汇编逐项核对：

| 位置 | 函数内偏移 | 证据 |
| --- | ---: | --- |
| `do_timer_create` 分配返回点 | `+0xac` | `bl kmem_cache_alloc_noprof`，cache 为 `posix_timers_cache` |
| `prepare_creds` 分配返回点 | `+0x2c` | `bl kmem_cache_alloc_noprof`，cache 为 `cred_jar` |
| `copy_process` task 分配返回点 | `+0x190` | 前一条为 `bl kmem_cache_alloc_node_noprof` |
| `begin_new_exec -> release_task` | `+0x410` | 调用目标匹配 |
| `begin_new_exec -> down_read_killable` | `+0x50c` | 调用目标匹配 |
| `begin_new_exec -> posix_cpu_timers_exit` | `+0x87c` | 调用目标匹配 |
| `begin_new_exec -> exit_itimers` | `+0x88c` | 调用目标匹配 |

工具过滤了 `alloc_posix_timer._alloc_tag` 和 `prepare_creds._alloc_tag`，没有把 profiling 记账调用误当成对象分配点。

## BTF 结构布局

| 字段 | 值 |
| --- | ---: |
| `sizeof(task_struct)` | `0x1440` |
| `task_struct.real_cred` / `cred` / `comm` | `0x8f8` / `0x900` / `0x910` |
| `task_struct.pid` | `0x708` |
| `sizeof(struct cred)` | `0xb8` |
| `cred.uid` | `0x08` |
| `cred.cap_effective` / `cap_bset` / `cap_ambient` | `0x40` / `0x48` / `0x50` |
| `cred.security` / `user_ns` | `0x80` / `0x90` |
| `sizeof(k_itimer)` | `0x100` |
| `k_itimer.it.cpu.node` | `0x78` |
| `k_itimer.rcu` | `0xf0` |
| `sizeof(selinux_state)` | `0x80` |
| `selinux_state.enforcing` / `initialized` | `0x0` / `0x1` |
| SELinux 写目标 | `selinux_state - 7` |

`CAP_FULL=0x1ffffffffff`。task cache 回退值仍为 5696 bytes、5 objects/slab、8 pages；运行时按 `/proc/slabinfo` 的真实 cache 信息覆盖。POSIX timer 使用 256 bytes、32 objects/slab、order-1、8192-byte fragment page。

`GEOM_MEMSTART_PFN=0x80000` 是沿用相同布局的静态推断，必须在目标设备上复核。没有把其他设备的 slide 写入 profile。

## 64560 锚点

`src/kaslr_slide_aph136g142.c` 使用与既有 APH 适配相同的六锚点算法，数值由该 Image 的 kallsyms 重新生成：

```text
worker_thread           0xffffffc080207bc8
rcu_gp_fqs_loop         0xffffffc08018dbfc
rcu_gp_kthread          0xffffffc08018d6f4
folio_wait_bit_common   0xffffffc0802e798c
ufshcd_issue_dev_cmd    0xffffffc080e55658
icc_set_bw              0xffffffc08037a2b8
prepare_creds           0xffffffc0801935b8
selinux_state           0xffffffc0827e7a60
```

`syms_aph136g142.txt` 的 226 个未定义符号全部命中，静态 `ko_patch` 输出 `PATCHED=226 MISSING=0`，vermagic 与 g142 完全一致。零 slide 仅用于静态检查，不能在设备上代替 KASLR 推导。

## 内置执行文件

使用与旧 APH112 相同的 `uaf_plant_61258.c`、`rootd.c`、`stubs.c`、`su98260.c` 和 NDK r29 静态编译。旧 APH112 在两个项目中的二进制已逐字节比对一致，没有换用其他漏洞链。

| 文件 | SHA-256 | 嵌入位置 |
| --- | --- | --- |
| `exploit-android-key-ptr` | `13df9faca97007456db4568aeab69ea108b6efb03caf4d32a418f77340dd3b8a` | `root_extra/g142/98260/98260-aph136g142/` |
| `pc98260-su` | `2ad971c4eb50a7002e53671b8f7f107f5c7ad81bbdb26b4da2d250c0ec947321` | 同上 |
| `kaslr_slide_aph136g142` | `925ecd35fda6ba70a6dafa137ae2bffcfe009f164376e2cc98888a0edf1bb5d6` | `root_extra/g142/64650/` |

`pc98260-su` 的源码不变，编译器运行库版本会使它与旧版的二进制哈希不同；命令行协议不变。新目标选择宏为 `PC98260_TARGET_APH136G142`。

复现命令：

```powershell
powershell -File adaptation/aph136g142/build-aph136g142.ps1 `
  -NdkHome D:\AndroidSDK\ndk\android-ndk-r29 -Install
```

该脚本编译三个执行文件、复制到 UBL assets，并生成 profile 的 `SHA256SUMS.txt`。

## 应用接入

- `G14298260Adapter` 部署 aph136g142；`run98260.sh` 和 `root-command.sh` 仅接收完整的 g142 内核版本。
- `G14264650Adapter` 部署 g142 的 KASLR 和符号文件；`kernel_profiles.sh` 按完整内核版本选择。
- `KernelRootDispatcher` 使用新版已有的 G142 路由，未知构建仍拒绝执行。
- `NewKernelRootAdapterBase` 通过 `NEW_ROOT_KERNEL_G142` 校验目标，返回进程仍须通过 `id -u == 0` 验证。
- 保留 3.1.0 的 OTA、备份、分区和解锁实现，版本为 `versionName=3.1.1`、`versionCode=111`。

## 验证边界

已完成 boot/kernel 提取、BTF 核对、反汇编、源码编译、静态符号和 UBL 接入检查。本次构建时没有连接 APH/g142 设备，因此未执行新载荷，也不宣称 g142 的真实 UAF/rootd 已成功。首次在 APH/g142 上应先运行只读 `--check` 与 slide 校验，再分别验证两种 root 通道；切换提权链前按原流程重启。
