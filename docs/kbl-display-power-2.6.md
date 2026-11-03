# hnubl 2.6：显示电源与关机

原生 Fastboot 命令：

```text
fastboot oem screenoff
fastboot oem screenon
fastboot oem screenstatus
```

完整命令才加载独立服务并调用已定位的固件显示电源协议；这些入口不启动 KBL 或 ADB。KBL 启动后可输入同名 screenoff/screenon/screenstatus，显示开关保留运行环境与调试连接。screenstatus 回读固件 GetDisplayPowerState，提供协议、状态和 EFI 返回值；协议缺失或调用失败明确报错。没有以单纯黑色画面冒充屏幕断电。

KBL Shell 中输入 `poweroff` 会先同步挂载文件系统，再调用 UEFI ResetSystem(EfiResetShutdown)；其他命令不触发关机。此功能按使用者要求仅做离线模拟验证，不在真机执行。

新增命令沿用语义与数据流定位的未知命令入口、缓冲区关系、原始 FAIL/返回路径，保留实际 EL；不使用型号/版本/固定地址表。旧修补成品先核验和剥离旧适配层，再重建新入口。hnubl 的文字定制继续保留。硬件验证使用独立 RAM Fastboot 诊断副本，不刷入分区；生产镜像保持原始启动选择。
