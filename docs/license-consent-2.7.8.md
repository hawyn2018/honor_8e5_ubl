# 2.7.8 AGPLv3 与首次同意

本项目的顶层许可证改为 **GNU Affero General Public License version 3（AGPL-3.0-only）**。顶层 `LICENSE` 与应用内 AGPLv3 英文资源使用同一份 GNU 官方原文，日期为 2007 年 11 月 19 日。许可证页继续提供完整的非官方中文译文；中文用于理解，许可依据为英文原文。

项目原有第三方组件的 GPL、LGPL、Apache 等许可证及通知保留，不把第三方文件中的原有许可证声明批量替换为 AGPL。原 GPLv3 文本保留在应用许可证资源中，项目自己的许可证页改为读取新的 AGPLv3 资源。

首次打开本版且没有 AGPLv3 同意记录时，显示同意弹窗。可以在同意前阅读全文或退出应用；点击外部和系统返回均不解除门禁，也不会显示可操作的业务页面。仅在用户点击“同意”并成功保存记录后进入应用。

同意记录保存在应用私有偏好中，以许可证 ID 为依据，不随每次应用版本号升级重复弹出。应用禁止自动备份；清除应用数据或重新安装后再次显示。已有旧版的用户在首次进入新 AGPL 版本时也需要同意。

未同意时暂缓页面启动的 Root 探测、无线 Shell 自动复检和显式 OTA／提权页面导航；同意后继续处理最新启动意图，恢复原有启动选择。此门禁不新增许可证正文以外的使用条款。

应用与内置 OTA 模块均为 **2.7.8 / versionCode 69**。OTA 模块此次同步版本标识与自有代码许可声明，原有模块行为保持。

GNU 官方原文：https://www.gnu.org/licenses/agpl-3.0.html

GNU 官方文本下载：https://ftp.gnu.org/gnu/Licenses/agpl-3.0.txt

非官方译文说明：https://www.gnu.org/licenses/translations.html
