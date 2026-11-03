# 电脑端验证记录

2026-10-30：services 分支的完整 PC 回归为 **66 项全部通过，0 失败、0 跳过**。固定源码摘要与原始 unittest 输出保存在 `analysis/kbl-services-20261030/lazy-start/host/validation.json` 和 `unittest.log`。测试只使用本地脚本/子进程结果 fixture，未打开 USB、ADB 或真实 Fastboot。

新增证据包括完整 `kblstop` 别名、首次 START 丢 ACK / reply-page 期间断连后的只读状态恢复、明确 remote FAIL 不重试、超时有界、身份/EL/busy-job 校验、重连等待中的 Ctrl+C 不触发停止，以及其他提交/上传/停止命令丢 ACK 时没有自动重放。START 只发送一次；后续只读 status 和回复页可重复。原参数透传、4096 字节命令、任务恢复、文件和分区回读校验等既有测试同时通过。

以下为早期基线记录，保留其当时的实现范围：

2026-10-29，Python 3.12，Windows。源码原型验证，不是设备测试。

执行：

```text
python -X utf8 -m unittest discover -s repairs/abl-efisp-blsh-20261029/source/tools/kbl_console/tests -v
```

结果：**28 个测试全部通过，38.545 秒**。同时完成 `py_compile` 语法检查。

主要证据：

- 本地 fake-native 子进程跨多次调用保留会话，exit、Ctrl+D、Ctrl+C 和中断轮询后都没有收到 kbloff；仅明确 kbloff 改成 stopped。
- busy 任务断开后由 kblin 接管同一会话，未重新初始化。ACK 丢失不自动重新提交任务。
- 4096 UTF-8 字节命令完整重建，上传带逐片偏移和最终总长度，每条线长符合配置；无截断。
- INFO 十六进制片段跨 UTF-8 字符边界后仍保持输出完整；缺 KBL1、坏十六进制、冲突身份和 EL 变化会报错。
- 原生 Fastboot 参数中的空格和 Unicode 原样到达 fixture。没有实际执行分区刷写；测试参数中的 flash 只记录在本地 JSON fixture。
- 二进制 fetch fixture 的 4098 字节逐字节保持，必须通过尺寸和 SHA-256 后才发布；失败、短读、摘要不一致及覆盖已有文件均被拒绝。

边界：没有 USB/ADB/真 Fastboot 连接；未检验设备微内核、同 EL 硬件读值、驱动并发、完整 POSIX Shell、固件启动或实际分区/文件回读。当前文件回读后端明确未实现。消费式轮询遇到响应丢失仍可能丢日志页，测试不能消除这个协议限制。
