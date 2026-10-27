#!/system/bin/sh
# Kernel / root-backend profile registry for MagicRoot.
#
# Two independent selections happen here:
#   1. the kernel profile (6.12.58 / 6.12.38 ...), which picks KASLR anchors,
#      syms and the CVE-2026-64560 payload binaries;
#   2. the root backend flavor, which picks the manager package, the userspace
#      ksud and the KernelSU-family LKM template:
#        resukisu = BakaSU 4.2.0-rc3       kernel 35252 / uapi 6
#        fina     = FinaSU 1.2.1             kernel 33074 / uapi 6
#        kernelsu = official KernelSU 3.3.1 kernel 32680 / uapi 5
#        sukisu   = SukiSU 4.2.1-r2         kernel 41068 / uapi 6
#        next     = KernelSU-Next 3.4.2     kernel 33355 / uapi 5
#
# The userspace ksud (lib/arm64-v8a/libksud.so or the renamed native library
# used by newer managers) and the manager signing tuple are extracted from the
# installed manager APK; there is no bundled manager/userspace fallback.
#
# A per-flavor KMI-matched LKM can be supplied at runtime as
#   $D/ko-<flavor>.ko
# which overrides the bundled template.

# Allowed backend flavors (keep in sync with UiState.kt / MainActivity.java).
KSU_FLAVORS="kernelsu resukisu next sukisu fina"

ksu_flavor_normalize() {
    case "$1" in
        resukisu|fina|kernelsu|sukisu|next) echo "$1" ;;
        official|bakasu) echo "resukisu" ;;
        *) echo "kernelsu" ;;
    esac
}

ksu_flavor_pkg() {
    case "$1" in
        resukisu) echo "com.resukisu.resukisu" ;;
        fina)     echo "me.yuki.fina" ;;
        kernelsu) echo "me.weishu.kernelsu" ;;
        sukisu)   echo "com.sukisu.ultra" ;;
        next)     echo "com.rifsxd.ksunext" ;;
        *)        echo "me.weishu.kernelsu" ;;
    esac
}

ksu_flavor_label() {
    case "$1" in
        resukisu) echo "BakaSU" ;;
        fina)     echo "FinaSU" ;;
        kernelsu) echo "KernelSU" ;;
        sukisu)   echo "SukiSU" ;;
        next)     echo "KernelSU-Next" ;;
        *)        echo "KernelSU" ;;
    esac
}

ksu_flavor_kver() {
    case "$1" in
        resukisu) echo "35252" ;;
        fina)     echo "33074" ;;
        kernelsu) echo "32680" ;;
        sukisu)   echo "41068" ;;
        next)     echo "33355" ;;
        *)        echo "32680" ;;
    esac
}

ksu_flavor_ksud() {
    # All five backends expose the userspace daemon as ksud; the app
    # extracts the real binary from the installed manager APK.
    case "" in
        resukisu|fina|kernelsu|sukisu|next) echo "ksud" ;;
        *) echo "ksud" ;;
    esac
}

ksu_flavor_uapi() {
    case "$1" in
        resukisu) echo "6" ;;
        fina)     echo "6" ;;
        kernelsu) echo "5" ;;
        sukisu)   echo "6" ;;
        next)     echo "5" ;;
        *)        echo "5" ;;
    esac
}

# Per-flavor native 6.12 KMI LKM template + matching syms table.
# The reference MultiSU bundle provides native android16-6.12 LKMs for all five
# backends, so each flavor now uses its own kernel module (no cross-flavor
# compatibility fallback).
ksu_select_ko() {
    profile="$1"   # 61258 | 6138
    flavor="$2"
    FLAVOR_SYMS_MISSING=0
    case "$flavor" in
        resukisu)
            KO_TEMPLATE="$D/ksu-template-bakasu.ko"
            SYMS="$D/syms_${profile}_bakasu.txt"
            KO_COMPAT="native-6.12-bakasu"
            ;;
        kernelsu)
            KO_TEMPLATE="$D/ksu-template.ko"
            SYMS="$D/syms_${profile}.txt"
            KO_COMPAT="native-6.12-kernelsu"
            ;;
        next)
            KO_TEMPLATE="$D/ksu-template-next.ko"
            SYMS="$D/syms_${profile}_next.txt"
            KO_COMPAT="native-6.12-kernelsu-next"
            ;;
        sukisu)
            KO_TEMPLATE="$D/ksu-template-sukisu.ko"
            SYMS="$D/syms_${profile}_sukisu.txt"
            KO_COMPAT="native-6.12-sukisu"
            ;;
        fina)
            KO_TEMPLATE="$D/ksu-template-fina.ko"
            SYMS="$D/syms_${profile}_fina.txt"
            KO_COMPAT="native-6.12-finasu"
            ;;
        *)
            KO_TEMPLATE="$D/ksu-template.ko"
            SYMS="$D/syms_${profile}.txt"
            KO_COMPAT="native-6.12-kernelsu"
            ;;
    esac
    # Explicit per-flavor override always wins.
    if [ -f "$D/ko-$flavor.ko" ]; then
        KO_TEMPLATE="$D/ko-$flavor.ko"
        KO_COMPAT="override"
    fi
    # Fail closed: a template without a matching syms table for this KMI would
    # resolve against another kernel's addresses and crash the module.
    if [ ! -f "$KO_TEMPLATE" ] || [ ! -f "$SYMS" ]; then
        FLAVOR_SYMS_MISSING=1
        echo "KSU_FLAVOR_UNSUPPORTED flavor=$flavor profile=$profile template=$KO_TEMPLATE syms=$SYMS"
    fi
}

select_kernel_profile() {
    RELEASE="$1"
    PROFILE_ID=""
    PROFILE_READY=0
    KASLR_BIN=""
    SYMS=""
    KO_TEMPLATE=""
    KO_COMPAT=""
    PROFILE_KASLR_MIN_VOTES=""
    PROFILE_KASLR_MIN_ANCHORS=""
    PROFILE_KASLR_MIN_BYTES=""
    PROFILE_KASLR_PREPARE_LINK=""
    PROFILE_KASLR_SELINUX_LINK=""
    PROFILE_CRED_LAYOUT=""
    KSU_FLAVOR=$(ksu_flavor_normalize "${MAGICROOT_KSU_FLAVOR:-$(cat "$D/ksu_flavor" 2>/dev/null | tr -d '\r\n')}")
    export MAGICROOT_KSU_FLAVOR="$KSU_FLAVOR"
    KSU_MANAGER_PKG=$(ksu_flavor_pkg "$KSU_FLAVOR")
    KSU_MANAGER_LABEL=$(ksu_flavor_label "$KSU_FLAVOR")
    EXPECT_KVER=$(ksu_flavor_kver "$KSU_FLAVOR")
    EXPECT_UAPI=$(ksu_flavor_uapi "$KSU_FLAVOR")
    export MAGICROOT_KSU_MANAGER="$KSU_MANAGER_PKG"
    export MAGICROOT_KSU_LABEL="$KSU_MANAGER_LABEL"
    export PROFILE_KASLR_MIN_VOTES PROFILE_KASLR_MIN_ANCHORS PROFILE_KASLR_MIN_BYTES
    export PROFILE_KASLR_PREPARE_LINK PROFILE_KASLR_SELINUX_LINK
    export PROFILE_CRED_LAYOUT
    export MAGICROOT_EXPECT_KVER="$EXPECT_KVER"
    export MAGICROOT_EXPECT_UAPI="$EXPECT_UAPI"
    V36_BIN="$D/t64560_v36"
    CRED_BIN="$D/t64560_cred"

    case "$RELEASE" in
        6.12.58-android16-6-g3a7ac1674240-abogki541502914-4k)
            PROFILE_ID="61258"
            PROFILE_READY=1
            KASLR_BIN="$D/kaslr_slide_61258"
            V36_BIN="$D/t64560_v36"
            CRED_BIN="$D/t64560_cred"
            PROFILE_KASLR_MIN_VOTES=3
            PROFILE_KASLR_MIN_ANCHORS=5
            PROFILE_KASLR_MIN_BYTES=1048576
            PROFILE_KASLR_PREPARE_LINK=0xffffffc08019c448
            PROFILE_KASLR_SELINUX_LINK=0xffffffc0827e7a60
            PROFILE_CRED_LAYOUT="6.12-BTF task_cred=0x900 cred_cap_eff=0x40 cred_cap_bset=0x48 itimer_node=0x78 slot_stride=0x100"
            ksu_select_ko "61258" "$KSU_FLAVOR"
            export MAGICROOT_KSU_TEMPLATE="$KO_TEMPLATE"
            export MAGICROOT_KSU_COMPAT="$KO_COMPAT"
            export MAGICROOT_FLAVOR_SYMS_MISSING="$FLAVOR_SYMS_MISSING"
            return 0
            ;;
        6.12.38-android16-5-gfde7767f6ef6-abogki481467632-4k)
            PROFILE_ID="6138"
            PROFILE_READY=1
            KASLR_BIN="$D/kaslr_slide_6138"
            V36_BIN="$D/t64560_v36"
            CRED_BIN="$D/t64560_cred"
            PROFILE_KASLR_MIN_VOTES=3
            PROFILE_KASLR_MIN_ANCHORS=5
            PROFILE_KASLR_MIN_BYTES=1048576
            PROFILE_KASLR_PREPARE_LINK=0xffffffc080b8a21c
            PROFILE_KASLR_SELINUX_LINK=0xffffffc0826663c8
            PROFILE_CRED_LAYOUT="6.12-BTF task_cred=0x900 cred_cap_eff=0x40 cred_cap_bset=0x48 itimer_node=0x78 slot_stride=0x100"
            ksu_select_ko "6138" "$KSU_FLAVOR"
            export MAGICROOT_KSU_TEMPLATE="$KO_TEMPLATE"
            export MAGICROOT_KSU_COMPAT="$KO_COMPAT"
            export MAGICROOT_FLAVOR_SYMS_MISSING="$FLAVOR_SYMS_MISSING"
            return 0
            ;;
        6.18.*)
            PROFILE_ID="reserved-6.18"
            KO_TEMPLATE=""
            return 0
            ;;
        *)
            PROFILE_ID="unsupported"
            return 1
            ;;
    esac
}

# ---------------------------------------------------------------------------
# Second chain: CVE-2026-98260 (Honor 6.12.58 / 6.12.38 adapted builds).
#
# This profile is deliberately NOT reachable from select_kernel_profile():
# the 98260 binaries derive their own KASLR slide (tracefs kprobe + perf), so
# the 64560 KASLR anchors/syms are never used for it.  run.sh only calls this
# when MAGICROOT_CHAIN=98260; the 64560 pipeline keeps its own behavior.
# The shared stage machine (assets/98260/run98260.sh) selects the per-kernel
# binary directory itself from the exact uname -r build.
# ---------------------------------------------------------------------------
CHAIN_98260_PROFILE_ID="honor-61258-98260"
CHAIN_98260_KERNEL="6.12.58-android16-6-g3a7ac1674240-abogki541502914-4k"
CHAIN_98260_PROFILE_ID_6138="honor-6138-98260"
CHAIN_98260_KERNEL_6138="6.12.38-android16-5-gfde7767f6ef6-abogki481467632-4k"
CHAIN_98260_KERNELS="$CHAIN_98260_KERNEL|$CHAIN_98260_KERNEL_6138"

select_chain_98260_profile() {
    _chain_release="$1"
    PROFILE_CHAIN=98260
    PROFILE_READY=1
    case "$_chain_release" in
        "$CHAIN_98260_KERNEL")
            PROFILE_ID="$CHAIN_98260_PROFILE_ID" ;;
        "$CHAIN_98260_KERNEL_6138")
            PROFILE_ID="$CHAIN_98260_PROFILE_ID_6138" ;;
        *)
            PROFILE_READY=0
            return 1 ;;
    esac
    return 0
}
