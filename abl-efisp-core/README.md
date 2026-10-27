# ABL-EFISP core 来源

本模块来自使用者提供的 ABL-EFISP fakebl 1.2.5 与 freedom 1.2.3 源码包。两份包的核心源码相同，通过 `PatchMode.FREEDOM` 和 `PatchMode.FREEDOM_FAKE_LOCK` 选择对应模式。26 个生产 Kotlin 文件保持原样，来源及 SHA-256 见 `SOURCE-PROVENANCE.json`。

此处仅合入纯 Kotlin/JVM 核心，不含独立应用或 Compose 页面。源码随宿主的 GPL-3.0-only 源码包分发，保留来源记录；第三方 XZ for Java 1.12 使用 0BSD，许可证见 `XZ-COPYING.txt`。`THIRD-PARTY-NOTICES.txt` 保留原来源说明，其中部分界面依赖属于上游可选 feature，本核心实际运行依赖仅为 XZ 与 Kotlin 标准库。

`src/test` 复用工作区现有测试；生产源码与两个用户提供包逐字节相同。合成夹具测试可独立运行，部分固件集成测试会按其原有条件寻找工作区本地夹具，不随源码包分发固件。

宿主通过 `AblEfisp.patch` 和 `AblEfisp.editText` 调用；本模块不自动访问 Root、分区或外部应用。已有 Root 提取 API 仅在显式调用时读取。
