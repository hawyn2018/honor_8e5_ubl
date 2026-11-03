# KBL1 控制、程序上传与回读协议

本文件与 `runtime/kbl_api.h` / `runtime/kbl_control.c`、原生后端及 UEFI 驱动配合。独立模块和实际 ARM64 PE 已在显式仿真模型中验证；父 ABL/USB 正在合入，物理设备执行与正常系统引导仍需独立验证。

## 传输约束

每个 OEM 请求是独立的 Fastboot 事务，最后仅一个 OKAY 或 FAIL。控制器完整 ASCII 命令上限 63 字节；电脑端默认为 60。每条 INFO 的文本部分最多 56 ASCII 字节。普通系统引导及不匹配这些命令的 Fastboot 请求不触发任何 KBL 初始化、任务推进或清理。

父固件控制链路使用 DEFER_INFO：一次 OEM 请求按实际可用内存动态缓存 INFO，然后只发送一次原生最终应答。主机通过 `getvar kbl-reply-header` 取得 `KBLC1 g=16HEX n=N`，再用 `getvar kbl-reply:GEN:INDEX` 读取原样的 KBL1 行。查询不改变缓存世代、不推进任务；世代已过期时失败，主机不重发原 OEM 请求。此机制不承载镜像内容。

字段中的 `ID` / `JOB` / `UPLOAD` 是设备返回的十六进制标识，不是主机内存地址。上传 ID 固定 16 个十六进制字符，偏移和总字节数接受 1..16 位十六进制，单位为原始 UTF-8 字节。新驱动在开始上传及状态中声明 `KBL1 command64=1`，主机据此使用动态缓冲；原四位格式保持兼容。

## 已实现的控制器与电脑端请求

| 线上的完整命令 | 含义 |
| --- | --- |
| `oem kblstart` | 创建 KBL 环境，不启用 ADB；已运行时报告原会话 |
| `oem kbladb` | 显式启用 ADB；环境尚未运行时先启动 KBL；重复调用保留现有会话 |
| `oem screenoff` | 显式请求显示关闭，不创建 KBL、不启用 ADB |
| `oem screenon` | 显式请求显示开启，不创建 KBL、不启用 ADB |
| `oem screenstatus` | 查询实际 Display Power 接口及 Get 返回状态，不创建 KBL |
| `oem kblin` | 报告已有会话及忙任务，用于重连；不启动、不推进任务 |
| `oem kbloff` / `oem kblstop` | 请求后端停止并清理；两个明确入口等效；失败不伪报 stopped |
| `oem kblstatus` | 快照查询；不推进任务 |
| `oem kblcmd HEX` | 提交一条完整短命令，返回 job，不同步跑到结束 |
| `oem kblput B` | 开始完整命令上传，返回 upload 标识 |
| `oem kblput DUPLOAD:OOOO:HEX` | 追加一片，OOOO 必须等于已收字节数；拒绝重复、乱序或越界 |
| `oem kblput EUPLOAD:LLLL` | 提交完整上传；LLLL 必须等于已收总字节数，再返回 job |
| `oem kblpoll JOB` | 只为此任务推进有界 quantum，返回文本输出及完成状态 |
| `oem kblstore env1:NONCE` | 验证已 RAM 暂存的完整封包，再原子保存文件；不自动执行 |
| `oem kblreadopen HEXPATH` | 打开 RAMFS 不可变快照并返回别名、长度和摘要 |
| `oem kblreadopen env1:NONCE` | 用已暂存的零 payload 路径封包打开快照，支持长路径 |
| `oem kblreadclose kbl.ID` | 关闭对应快照；不销毁运行环境 |

举例：

```text
oem kblput B
INFO KBL1 put=0000000100000001
OKAY

oem kblput D0000000100000001:0000:6563686f206869
OKAY

oem kblput E0000000100000001:0007
INFO KBL1 status=busy id=0000000000000001 el=1
INFO KBL1 job=0000000000000001
OKAY
```

60 字节线长且四位偏移时 D 帧最多承载 13 原始字节；偏移增长时每片容量自动减小。完整命令按实际可用内存动态增长，各帧保持真实 Fastboot 接收契约。

客户端不自动重试有歧义的帧。偏移和总长度用于拒绝不完整或乱序提交；它们不提供网络级重放保障，也不等于 cryptographic authentication。

## 响应字段

以下均为 INFO 文本，不含前面的 `INFO` 四字节及原生 CLI 的 `(bootloader)` 格式：

```text
KBL1 status=running id=0000000000000001 el=1
KBL1 job=0000000000000001
KBL1 put=0000000100000001
KBL1 out=e4b8ade696870a
KBL1 done=0 rc=0
KBL1 done=1 rc=-1
```

- `status`：`running` / `busy` / `stopped`，错误清理状态还可能是 `starting` / `stopping` / `error`。
- `id`：环境本次创建的世代标识；`kblin` 必须指向原世代。销毁后再启动为新世代。
- `el`：后端实际读取的 CurrentEL 解码值；当前控制器启动只接受 EL1/EL2，并在执行后核对未变化。主机只验证设备报告前后一致，不能据此证明真实硬件级别。
- `job`：当前异步任务。`kblin` 报 busy 时需同时报告任务。
- `out`：UTF-8 文本字节的十六进制片段。控制器目前每 poll 最多取 24 字节，并按至多 23 字节拆包，所以每条 out INFO 最多 55 ASCII 字节。主机跨片解码。
- `done` 为 0 或 1，`rc` 为十进制有符号返回码。电脑端数值解析保留 signed64 的负值和 unsigned64 的大小／计数范围；超出两者真实字段宽度的数值会拒绝。

`kblput D` 目前可以只有最终 OKAY 而无 KBL1 INFO；主机仅在这一步允许无标签 ACK。启动、重接、开始上传、提交及轮询必须出现有效 KBL1 信息，普通 OKAY 不会被当作环境已启动。

## 会话生命周期与其他 Fastboot 命令

电脑端提示符为 `# `。exit、quit、Ctrl+D、Ctrl+C 和终端关闭只会停止主机输入/轮询；主机没有自动发送 kbloff 的清理逻辑。设备控制器也保护单独的 exit/quit，不调用 stop。Shell 内部脚本的 exit、故障或返回还需真实后端保证只结束任务而保留运行环境。

普通 Fastboot 请求不隐式 pump 任务。有界任务推进只发生在 kblpoll 请求中；每个请求结束后原 Fastboot 事务结束。ADB 仅由 `kbladb` 显式启用；`kblstart` 的终端通过 Fastboot 控制协议工作。设备侧的 USB、Fastboot 主循环以及后端 cooperative yield 都需要实际验证。该约定不声称在任意无界原生命令、失控指针或同 EL 故障后仍有硬件隔离。

当前输出为消费式，没有 ACK。断连时可能丢输出页；主机不会自动重新提交任务来补输出。未来可另加 `poll(JOB,SEQ)` 保留输出至主机落盘后 `ack(JOB,SEQ)`，重连重放未 ACK 页；这项尚未实现，不能与当前 KBL1 语义混用。

## 文件上传封包

原生 `stage FILE` 仅向 Fastboot RAM 下载缓冲暂存封包；随后发送对应 `kblstore` 或 `kblreadopen`。二者之间不插入另一次 stage。并发客户端更换缓冲会导致 nonce/摘要失败，不应自动重试。

固定64字节小端头为 `<8sQIIQ32s>`：`KBLPUT01`、64位 nonce、32位 UTF-8 路径字节数、32位零保留字段、64位 payload 字节数、32字节 payload SHA-256。后接不含 NUL 的绝对路径（长度由封包的 uint32 字段表示），再接文件 payload。总长度必须精确匹配。READ_OPEN 的 payload 必须为空且摘要为真实 SHA-256(empty)。STORE 在校验完成前不发布文件。

STORE 成功 INFO 为 `KBL1 stored=1 size=N`、`KBL1 id=16HEX`、两条32hex的 `hash0/hash1`；命令不自动执行保存的程序。

## 二进制回读

主机 `NativeFastboot.fetch` 调用原生 `fetch PARTITION LOCAL_TEMP`，而不是经 INFO 搬运大镜像。函数要求预期 size 与 SHA-256，并拒绝覆盖已有文件；两项均验证通过才发布目标。

驱动已实现真实 RAMFS 快照、GPT PartitionInfo/BlockIO 只读 provider、SHA-256 和有界二进制 FETCH 服务。READ_OPEN 成功回复四行：`KBL1 file=kbl.16HEX`、`KBL1 size=N`、`KBL1 hash0=32HEX`、`KBL1 hash1=32HEX`。同一别名始终读取原快照，失效别名不得回退成同名 GPT 分区。

`fetch:NAME[:hexOffset[:hexLength]]` 按请求的实际长度申请缓冲，不再受 8 MiB 软件阈值限制；单个 DATA 长度受 Fastboot 八位十六进制字段的 UINT32_MAX 自然边界约束，整文件可分多次回读。预读失败发生于 DATA 之前。发送 DATA 后出现传输错误则中止二进制事务，不向镜像字节混入 FAIL 文本。父 USB 绑定必须处理 DMA 所有权、超时、断连、最终原生 RX rearm；单独有 FETCH 服务并不代表固件 USB 回读已经可用。

标准 host 查询需要 `getvar:max-fetch-size`、`getvar:partition-size:kbl.ID` 和 `getvar:has-slot:kbl.ID` 对应驱动接口。普通分区的原生 getvar 保持原流程。分区直接读取不会创建 KBL native context；当前拟议父 USB observer 必须先由显式 kblstart 建立活跃传输观察，未观察到完整 ACK/RX 边界时 FETCH 明确失败。

`kblreadclose kbl.ID` 只释放快照；kbloff 会先处理快照再停止 native context。尚有借用读者或未完成 DMA 时保留资源并报告待清理，不把内存交给仍在执行的传输。普通 Fastboot 透传 fetch 不自动附带主机 API 的额外 SHA-256 校验。

## 显示服务与独立关机命令（1.3.4）

父模块/driver 新增私有操作号 18/19/20，分别对应完整 `screenoff` / `screenon` / `screenstatus`；原 1..17 编号不变。它们不是 KBL 环境 START，不执行 native 初始化、ADB 启用或 USB observer 绑定。请求在原未知命令边完成完整字符串与终止 NUL 检查，注册后的前缀 handler 仍拒绝多余参数。命令帧和 INFO 的真实单包大小不变，控制页缓存按需增长。

显示元数据使用 `display_provider`、`display_revision`、`display_get_status`、`display_set_status`、`display_state`、`display_state_value`、`display_saved`、`display_observed`；按存在的实际步骤输出，缺失协议明确报告 unsupported。`display_observed=1` 表示本次 Get 返回了有效状态，不是 host 根据 OKAY 推测成功。Set 成功也应结合后续 Get 和硬件观测判定。缓存状态页沿用世代约束，普通 getvar 不推进显示或关机操作。

KBL Shell 的 screenoff/screenon/screenstatus 进入同一显示后端。Shell `poweroff` 是单独显式任务，先同步挂载与块设备缓存、再调用 RuntimeServices ResetSystem(Shutdown)。它不由 OFF、程序退出、主机断连或控制台清理触发。Shutdown 成功可能在任务输出及最终 ACK 前切断 transport；这不是主机重新提交的依据。物理关机测试按本轮约束留给使用者，离线 ResetSystem fixture 只证明调用边界。

协议依据：[Microsoft Display Power](https://learn.microsoft.com/en-us/windows-hardware/drivers/bringup/efi-display-power-protocol) 定义 UINT32 Revision、Set/Get 的顺序及兼容规则；[DisplayPowerState](https://learn.microsoft.com/en-us/windows-hardware/drivers/bringup/efi-display-power-state) 定义 Off=1、Maximum=2。关机接口依据 [UEFI ResetSystem](https://uefi.org/specs/UEFI/2.10/08_Services_Runtime_Services.html#resetsystem)。协议 GUID 与结构是公开接口标识，不是机型地址适配表；协议存在仍需特定设备的固件读回及硬件行为验证。
