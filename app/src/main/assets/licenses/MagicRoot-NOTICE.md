# MagicRoot 提权资产说明（assets/magic/）

BootForge 2.1.0（versionCode 13）起接入 MagicRoot 的 CVE-2026-64560 提权链。
以下资产打包于 `assets/magic/`，运行时经本应用的无线 ADB Shell（UID 2000）
部署到 `/data/local/tmp/magic`，仅服务于 Magic 9（MLY-AN00，
6.12.58-android16-6-g3a7ac1674240-abogki541502914-4k）这一台目标设备。

## 来源

- 漏洞链参考实现：CVE-2026-64560 public 仓库（GhostLock/CVE-2026-64560
  research contributors，MIT License，见文末）；HONOR 机型适配同为 MIT
  （LICENSE-HONOR-ADAPTATION-MIT.txt）。
- `ksu-template.ko`：使用者提供的 KernelSU 6.12.76 内核模块，经符号驱动的
  `track_throne` 补丁（`init_cred` 替换 `ksu_cred`）；加载时由 `ko_patch`
  按本次启动的 KASLR slide 重写未定义符号与 vermagic。
- `ksud`、`ksu_grant`、`root_stage.sh` **未打包**：本应用不安装 KernelSU
  用户态管理器，不加冕管理器。Root 通道由 `bfexec`（本项目源码
  `app/src/main/cpp/bfexec.c`，NDK r29 静态编译）承担：UID 2000 经 KernelSU
  reboot 钩子 + `KSU_IOCTL_GRANT_ROOT` 提权后 exec `sh -c`，与 MagicRoot 的
  `ksu_grant` 同一机制。

## 打包资产 SHA-256

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| addrcalc | 1,002,256 | aff3194c9923c0c1ce37a2db4b42c23631d4b566807c27793de41ce2bb46be4a |
| clean.sh | 807 | 3cc578a45efc9682bbdcd6d5fe4416cb871463b38e55bd9252485eb471473384 |
| cred_target | 1,115,104 | 88689c7529c0ba27b98f07f332eaf2b198fe3e6d43117a1ccec0ed709df499a6 |
| grab.sh | 1,502 | e77d23beb053867125c871368c19fd69e985a67223b046f7513cecf22676ccd3 |
| kaslr_slide | 1,111,992 | 4684ca538700ec8f8d8f040abd292b88c332bdcff5b52d109ed9736d797ba085 |
| ko_patch | 1,163,240 | bd5360f6cc4652e9bcda1d5476d17d44a8de34856de7acb6073a7f5624eb5a2e |
| ksu-template.ko | 485,803 | 87010f590553cef81c55378ebd02ffd7b3f02c143d0f69ef05cd394da1bd1781 |
| lib.sh | 294 | c0ced27625a665f2b238edf2e8895f54f618903f3c7857052c654eaed2625826 |
| run.sh | 3,191 | cdbbb4edf5959f6404ae24962af1c2e0a28149fbc850205edf8caeae60261c2f |
| syms.txt | 8,079 | 704497c3c239ce6ffe9d9768ef940778e61fdbb9d814c11238075e636b5e5e3c |
| t64560_cred | 1,346,856 | 667a3319e946752a23f6f6b8b12dc1562238cb5bc9a07cf74823a687fe0b0028 |
| t64560_v36 | 1,295,352 | f4cc832c57bc4bac9f8ed3f595c209a978809dc37a42c7458617b8c0f0d6cd80 |

`bfexec` 为构建期产物，哈希随构建变化；可在构建后于
`app/build/generated/magicAssets/magic/bfexec` 核对。

## MIT License

Copyright (c) 2026 GhostLock/CVE-2026-64560 research contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
