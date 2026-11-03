# 2.8.5 清理与模拟

解锁执行代码清除旧计数字段兼容、实际来源/回退阶段元数据、旧不支持设备提示及弹窗。来源直接由 EFI_SOURCE 表示，SOURCE_SLOT / SOURCE_ABL_* 的身份与哈希核对保持。

OTA 实际使用的 AblRollbackInspector、EfispArbFallback、OtaPolicy、OtaEngine 与 ota-device.sh 保持。共用 AblRollbackGuardPolicy 仅删除无生产调用的 currentAbl、UNSUPPORTED_MESSAGE、Decision.Unverified，OTA 使用的实际解析与决策函数不变。

回归包括真实容器元数据解析及 OTA 策略/成品回退集成模拟，以及主机普通文件上的解锁/OTA 执行契约测试。模拟不代表整机升级或物理启动验证；不在手机上执行缩容或分区刷写。

旧解锁操作包沿用其配套旧脚本；本版生成的新包使用精简后的来源记录和配套脚本。准备任务仍按来源/镜像/引擎摘要核对，旧准备结果不会混用。

应用和内置模块同步为 2.8.5 / versionCode 78，模块只更新版本元数据。
