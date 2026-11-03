# hnubl 2.5.3

本版基于 hnubl 2.5.2，合入 Freedom/fakebl 1.3.2 的完整核心与显式启动 KBL。

## 执行顺序

1. 通过原有 Root 通道直接读取**当前运行槽位**的完整 ABL，固定分区身份和 SHA-256。
2. 解析该镜像实际携带的防回滚元数据。已识别计数全部为 0 时继续，任何计数大于等于 1 时提示 `Oops!该设备不支持解锁`；未能确定当前槽位计数时记录具体原因并停止。
3. 使用所选来源生成 efisp。来源默认是内置 ABL；自定义仍支持上传 efisp、上传 ABL、当前槽位、对槽和内置 ABL。来源选择不会改变第 1 步读取的槽位，也不会对修补前来源增加另一道防回滚拦截。
4. 使用 1.3.2 完整核心修补并追加 KBL，完成 hnubl 原有文字定制和回读。
5. 检查实际最终 efisp（上传 efisp 亦如此）。存在防回滚计数时按第 2 步处理；结构有效且没有该元数据的 PE 可继续。解析失败与没有参数分别处理。
6. 固定最终镜像哈希、当前槽位准入身份与镜像来源。两处检查全部通过后，才进入原工具准备、GPT/ABL 备份、F2FS/GPT 调整、创建或复用 efisp。
7. 依次写入所选 ABL 目标并回读，最后写入 efisp 并回读。三页默认均为双槽 ABL；自定义仍可明确选择当槽或对槽。
8. 原完成入口保留：假回锁可选择 `reboot bootloader`；仅解锁可选择 `reboot recovery` 清理数据。不会自动重启。

该检查解析的是镜像中的防回滚要求，不读取或推断设备熔丝的实际计数，也不将结构解析等同于签名验真。

## 镜像定制

Fastboot 标签保持单行：

```text
device freedom (github hawyn2018)
```

仅解锁模式保留以下开机警告定制，假回锁模式保留原警告资源：

```text
your device has been unlocked and now freedom
unlocktool opensource address
https://github.com/hawyn2018/honor_8e5_ubl
```

文字引擎在编辑时移除并重新追加其拥有的 KBL 区域，避免将文字分配到驱动空间；全部核心补丁、文字、最终大小和哈希均回读核对。

## 操作包绑定

`payload-source.txt` 新增 `CURRENT_ARB_SLOT`、`CURRENT_ARB_VALUE`、`CURRENT_ARB_DEVICE`、`CURRENT_ARB_MM`、`CURRENT_ARB_CAPACITY`、`CURRENT_ARB_SHA256`、`FINAL_EFISP_ARB`。

两种分区后端执行/准备时要求完整准入记录。后端在写入前再次核对当前槽位分区身份和原 ABL 哈希；选择对槽作为 efisp 来源时，仍检查当前槽位准入记录。旧包只读检查和旧任务清理可用，写入必须重新由新版应用生成完整操作包。

提权实现、双槽备份、分区规划、遇只读时的 `blockdev --setrw` 重试以及 ABL 在前、efisp 在后的刷写顺序保留。
