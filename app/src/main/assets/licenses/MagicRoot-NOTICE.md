# MagicRoot 提权资产说明（旧版 assets/magic/ 与 1.0.4 assets/magic104/）

honor_ubl 2.4（versionCode 30）同时保留 98260 与 64650 两条链，且每条链都包含
6.12.58 和 6.12.38 两套精确内核 profile。运行时按完整 `uname -r` 选择，不按设备型号删减。
98260 使用 rootd，64650 使用 MagicRoot 默认链与 `bfexec`；两条链均不要求另行安装
KernelSU 管理器或再次授权。失败只记录现场和 `boot_id`，不强制阻断重试或换链。

## 1.0.4 资产来源与许可

- 漏洞链参考实现：CVE-2026-64560 public 仓库（GhostLock/CVE-2026-64560
  research contributors，MIT License，见文末）；HONOR 适配同为 MIT
  （LICENSE-HONOR-ADAPTATION-MIT.txt）。98260 适配致谢见 MagicRoot 1.0.4 的
  `THIRD_PARTY_NOTICES.md`。
- `ksu-template.ko`：使用者提供的 KernelSU 模块，经符号驱动的 `track_throne`
  补丁；加载时由 `ko_patch` 按本次启动的 KASLR slide 重写符号与 vermagic。
- `ksud`、`ksu_grant`、`root_stage.sh` **未打包**：本应用不安装 KernelSU
  用户态管理器，不加冕管理器。64650 的 Root 通道由 `bfexec`（本项目源码
  `app/src/main/cpp/bfexec.c`，NDK r29 静态编译）承担；98260 由 rootd 配合
  `root-command.sh` 提供独立 stdout、stderr 和退出码。
- 1.0.4 的二进制载荷与两套内核符号表来自用户提供的
  `MagicRoot-1.0.4-source-fix.zip`；`run.sh`、`run98260.sh` 仅做本应用接入所需的调整。

## 早期 assets/magic/ 资产来源

早期 `assets/magic/` 资产及其哈希保留如下，供旧版对照；当前 1.0.4 双链资产位于
`assets/magic104/64650/` 和 `assets/magic104/98260/`。

## 早期 assets/magic/ SHA-256

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
