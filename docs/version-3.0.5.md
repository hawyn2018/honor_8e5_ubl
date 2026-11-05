# hnubl 3.0.5 / OTA module 3.0.5

[中文版](#中文版) · [English](#english)

## 中文版

应用与内置 OTA 模块同步为 **3.0.5 / versionCode 104**。保留按功能和数据流自动定位，不使用机型、固件版本或固定绝对地址适配表。

### 缓存和驻留

3.0.4 本来已按 PID 和进程启动时间保持 Hook。此版加强验证、失效恢复和解析效率，并非把原先每次 Activity 打开都重装 Hook 的流程改为常驻。

持久缓存绑定软件更新 APK、controller 和 agent 的 SHA-256、定位策略及 SDK/ABI/固件/VM 身份，完整负载有独立摘要。缓存保存 DEX 方法描述符和语义字段位置；不把某个进程中的 JNI 方法 ID 或 ArtMethod 绝对指针用于下一进程。新进程按缓存身份绑定自身的运行时地址，原生 ART 接口仍由当前运行环境解析。

同 PID 与启动时间下，只安装一次并保持驻留，Activity 重进不重新定位或安装。类、重载、反射索引和方法句柄在固定 classloader 内复用。反射按名称和参数组建索引，只解析目标重载的返回类型，避免扩大对无关方法类型的解析要求。

缓存关键契约或绑定失配时，先结束部分安装的旧目标进程，再完整按功能定位、发布新映射、原子更新缓存并启动干净进程。每次激活对相同 APK/controller/agent 最多一次恢复，避免循环叠加 Hook。缓存满盘、只读或写入失败仅记录诊断，使用已验证的新内存映射；不把性能缓存变成新的功能前置。新发现第一会话、磁盘命中和后续新 PID 的内存复用分别记录明确来源。

### ART 性能

保留原来的完整方法覆盖计划及原生 Binder 最终重启屏障，将逐方法暂停改为默认每 32 个方法一组的短批处理。所有 opaque JNI ID、ART 能力及规格偏移在暂停前准备，每批在 finally 中恢复线程；暂停期间没有 JNI、Java 反射或诊断回调。2756 个方法的计划使用 87 次暂停，而非 2756 次；ART 仍逐目标处理，次数变化不等于所有本地工作同样减少。

单方法和批量接口共享进程内完成记录。成功的方法在线程恢复后登记，中途异常保留成功前缀；有官方 `Instrumentation::IsDeoptimized` 查询时，在同一独占范围先查询实际状态，避免其他 Hook 已处理或外部状态变化带来的重复去优化。缺少可选查询时保留本桥自己的完成记录，不猜结构偏移。现有全局兼容回退保留并明确记录，未扩大该路径。

`freeotaok=1` 的完成授权、官方延迟切槽及原重启续接保持原逻辑。提权资产、备份、解锁、分区操作、ABL/EFISP 核心和 KBL 不在本次变更范围。

### 常驻执行与界面成本

普通 Binder transaction code 在原生分发器直接调用原始 JNI；只有运行时识别出的目标重启 code 进入原 JavaScript 屏障。匹配后的 Binder 对象、单次同步原调用许可、异常传播与官方续接保持原规则。入口 code、原始 JNI 与 ArtMethod 布局仍从当前运行时识别，安装失败时恢复完整旧保护路径。只修改本模块私有 replacement clone，以 ART 独占暂停和原子指针发布安装；回调及原生代码保留至脚本结束。

修复卸载时把 JavaScript 函数与 Frida 返回的 NativeCallback 比较而漏掉清理的问题。同一窗口已有有效候选时，重复 resume 合并为已跟踪控件与 STATE 的刷新，不再次遍历全文窗口；没有候选、候选失效或窗口变化时保留原语义发现。ViewTreeObserver 合并迁移后，从仍存活的当前 observer 移除本模块原 listener，避免遗留无效回调。正文滚动、无关内容和新窗口的兼容路径保留。

最终真机 Binder 微基准中，3 万次普通事务全部走原生路径，进入 JavaScript 的次数为 0；每万次普通事务中位数约 164 ms。该结果只描述通信层，不等同于软件更新页面整体耗时。独立 ART fixture 对 2756 个新方法分 87 批处理约 27 ms；重复调用未再次修改方法，但实际状态查询仍可能需要短暂暂停。

### 共享入口发布

连续冷启动与并发反射测试捕获到共享 JNI 入口及栈遍历内联跳板尚未 flush 完整时，被应用线程执行的并发窗口。共享 JNI、解释器、解析入口及内联跳转仍由当前 ART 运行时确定，原近跳／远跳与指令迁移决策保留；新增独立补丁层先在临时缓冲区按真实目标 PC 编码、完成 literal 修正，再短暂独占暂停 ART 线程，仅复制已完成的字节并刷新指令缓存。恢复也使用同一路径，finally 恢复线程；暂停中没有 Java、JNI、反射或指令生成。

发布失败时在同一暂停范围尝试回滚原字节。可能仍被栈帧引用的跳板保留至脚本结束，不提前回收；恢复不会覆盖已被其他所有者改变的入口。部分初始化失败不会被同一 VM 当作完整成功重复使用，仍由既有干净进程恢复边界处理。这个修复不使用崩溃样本中的固定 RVA、机型或固件版本表。

### 验证边界

Host 回归覆盖缓存摘要、环境变化、关键失配后的一次干净恢复、磁盘写失败、映射代次、同进程重复初始化、反射兼容性，以及批次异常恢复。实际 ART 测试只使用独立自有进程、只读 Binder descriptor/ping 和自有 Java 方法，不发送重启事务、不操作 OTA 标记或槽位。

实际软件更新应用的缓存命中、跨页面驻留与耗时结果随交付报告提供；模型结果不替代设备结果。本轮测试不点击真实更新安装或重启按钮，不刷写分区或重启手机。

最终模块在软件更新 17.0.33.125 上通过五轮新进程冷启动、磁盘缓存命中和一次故意失效后的恢复；磁盘命中时完整发现次数为 0，恢复时完整发现次数为 1。三次详情页往返保持同一个进程和 Hook 会话，系统记录的详情显示耗时为 1355、1170、1164 ms，仍有长帧。独立反射并发进程六轮均通过。保留原定向 ART 目标、重启保护与完成文件许可。

源码采用 AGPLv3，并保留第三方来源和许可证。发布包排除签名私钥、本地设备日志、构建缓存及 APK；应用安装包另行提供。

## English

Both hnubl and its bundled OTA module are **3.0.5 / versionCode 104**. Function/dataflow-based discovery is retained, without device, firmware or fixed-address tables.

The previous release already kept one eternalized agent per PID and process start time. This release strengthens cache validation, recovery and runtime resolution. Persistent entries bind the updater APK, controller and agent digests, discovery policy and platform/VM identity. They contain relocatable DEX descriptors and semantic field identities, never cross-process JNI or ArtMethod pointers. Each new process resolves its own addresses; existing processes reuse class, overload, reflection and handle entries without reinstalling on Activity navigation.

A failed cached critical contract ends the partial target before one complete semantic rediscovery, atomic best-effort persistence and clean process launch. Recovery is bounded once per exact APK/controller/agent activation. Cache I/O failure retains the validated fresh in-memory mapping and records a warning rather than adding a new functional prerequisite. Fresh, disk-cache and subsequent memory-cache sessions have explicit generation/source diagnostics.

ART changes use short batches of 32 by default, retaining the full original coverage plan and final native Binder barrier. A 2756-method plan needs 87 suspension cycles instead of 2756; individual native method handling remains. Opaque IDs and capabilities are resolved before suspension, and every batch resumes in finally. Single and batch interfaces share successful runtime-local records. The optional official IsDeoptimized query checks actual ART state under the same exclusive lock; no structure offsets are guessed. Legacy full-process fallback remains explicit.

Completion permission still comes from `freeotaok=1`, with the original official slot/reboot continuation. Privilege assets, backup/unlock/partition logic, ABL/EFISP core and KBL are unchanged.

Ordinary Binder transaction codes now call the original JNI directly in a native dispatcher; only the runtime-resolved reboot code enters the existing JavaScript barrier. Exact Binder identity, single-use synchronous original permission, exception propagation and official continuation remain. Installation changes only the module's private replacement clone, publishing atomically under exclusive ART suspension; failure restores the complete previous guard. Code and callbacks remain alive until script termination.

Callback cleanup uses Frida's installed NativeCallback identity rather than the original JavaScript function. Repeated resume callbacks for a window with valid tracked controls refresh those controls and STATE without rescanning the full tree. Missing or invalid candidates and new windows retain original semantic discovery. Listener cleanup follows a merged ViewTreeObserver to its live successor. Unrelated updater content retains its original behavior.

In the final real-device Binder microbenchmark, all 30000 ordinary transactions took the native path with zero JavaScript callbacks. Median time per 10000 transactions was approximately 164 ms. This is an IPC microbenchmark, not updater page latency. A separate owned ART fixture changed 2756 methods in 87 batches in approximately 27 ms; repeating it made no further method changes, although authoritative state queries still require short suspensions.

Cold-process and concurrent reflection tests exposed publication races in shared JNI and inlined stack-walk trampolines while their literal fixups were incomplete. A separate bridge patch now stages the complete instruction sequence using the real target PC, then commits only finished bytes under exclusive ART suspension with instruction-cache synchronization. The original relocation and near/far branch selection are retained, and restoration follows the same path. No Java, JNI, reflection or instruction generation occurs while suspended. Failure attempts rollback before resuming; potentially reachable retired trampolines remain pinned, another owner's entry is not overwritten, and partial bootstrap state is not reused as success. Entry addresses still come from the live ART runtime, without sample-specific RVA or device/firmware tables.

Host tests cover invalidation, bounded clean recovery, cache write failure, generations, repeated initialization, reflection compatibility and batch failures. Harmless real-ART fixtures use only owned methods and Binder descriptor/ping transactions. Device cache/residency and UI timings are reported separately; host models are not hardware timing evidence. Testing does not click real install/reboot buttons, flash partitions or reboot the phone.

The final module passed five fresh-process launches, a disk-cache hit and one intentionally invalidated-cache recovery on Software Update 17.0.33.125. A disk hit used zero complete discovery scans; recovery used one. Three detail-page round trips reused one process and Hook session. System-reported detail display times were 1355, 1170 and 1164 ms; long frames remain. Six independent concurrent reflection processes passed. The original selective ART coverage plan, reboot guard and completion-file permission are preserved.

AGPLv3 and third-party notices are retained. Source archives exclude private signing keys, local device logs, build caches and APK files; the application APK is supplied separately.
