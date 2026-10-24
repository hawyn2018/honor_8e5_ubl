# Wireless ADB pairing and transport

The app-owned wireless debugging implementation is adapted from the official
RikkaApps/Shizuku v13.6.0 ADB client, ADB RSA identity encoding and SPAKE2 pairing
implementation. Shizuku is Copyright RikkaApps and contributors, Apache-2.0.

Official source: https://github.com/RikkaApps/Shizuku/tree/v13.6.0/manager/src/main

The unmodified ARM64 `libadb.so` is extracted from the official signed release:
https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0

Release asset: `shizuku-v13.6.0.r1086.2650830c-release.apk`.
The library SHA-256 is
`a4257bc05ace812afa60a81e656724a07691edd08868dc90caab3bc6d0a49207`.
Its JNI registration requires the original
`moe.shizuku.manager.adb.PairingContext` class and native signatures.
It has 16 KiB aligned load segments.

The release library statically links BoringSSL (`io.github.vvb2060.ndk:boringssl:20250114`)
and libc++ (`org.lsposed.libcxx:libcxx:27.0.12077973`) according to the official
manager/build.gradle and manager/src/main/jni/CMakeLists.txt. Their notices are
included beside this notice. A source/build reference is also retained in
`analysis/shizuku-official` in the development workspace.

TLS is supplied through the public API of Google's Conscrypt Android 2.8.0
(Apache-2.0), including `Conscrypt.exportKeyingMaterial`. It does not depend on
Android's hidden Conscrypt APIs. Conscrypt includes BoringSSL; consult its full
upstream license and bundled BoringSSL license.

Official Conscrypt source: https://github.com/google/conscrypt/tree/2.8.0

Changes from Shizuku: app-specific encrypted key storage, standard DER certificate
generation without Bouncy Castle, public Conscrypt provider, explicit network
timeouts, bounded packet allocation/output, strict stream validation and closing
every TLS/socket/SPAKE2 resource on failure. Commands use this app's own paired
ADB connection; the Shizuku manager/server is not embedded.

The asynchronous shell process and stream decoder follow AOSP's shell-v2 protocol
(`shell_protocol.h`, `shell_service_protocol.cpp`, `daemon/shell_service.cpp`),
Copyright The Android Open Source Project, Apache-2.0:
https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/
They retain separate binary stdout/stderr and the protocol's actual exit status.
