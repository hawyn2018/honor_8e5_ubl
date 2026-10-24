#!/system/bin/sh
# 抓取 sched_blocked_reason 的 raw trace（只读内核状态，仅写 /data/local/tmp）。
#
# 产物：
#   /data/local/tmp/bl_<cpu>.bin   每 CPU 的 trace_pipe_raw
#   /data/local/tmp/bl_text.txt    trace 文本（含 caller=<func>+0x..）
#
# 说明：该 tracepoint 在这台设备上默认已启用（enable=1），因此不使用 perfetto
# （perfetto 会重置 buffer，导致文本锚点丢失）。只用 tracefs 自身的 enable。
. "$(dirname "$0")/lib.sh"

EV=sched/sched_blocked_reason
DUR=${1:-8}

say "tracefs=$TR"
[ -e "$TR/events/$EV/id" ] || { echo "M9_ERROR=no_sched_blocked_reason"; exit 2; }

rm -f "$DIR"/bl_*.bin "$DIR/bl_text.txt"

echo 1 > "$TR/events/$EV/enable" 2>/dev/null
echo 1 > "$TR/tracing_on" 2>/dev/null
sleep 1

# 文本锚点（非消费式读取）
grep "sched_blocked_reason" "$TR/trace" > "$DIR/bl_text.txt" 2>/dev/null

i=0
while [ $i -lt 8 ]; do
  cat "$TR/per_cpu/cpu$i/trace_pipe_raw" > "$DIR/bl_$i.bin" 2>/dev/null </dev/null &
  i=$((i+1))
done

sleep "$DUR"
pkill -f trace_pipe_raw 2>/dev/null
sleep 0.5
wait 2>/dev/null

grep "sched_blocked_reason" "$TR/trace" > "$DIR/bl_text.txt" 2>/dev/null

TOTAL=0
i=0
while [ $i -lt 8 ]; do
  f="$DIR/bl_$i.bin"
  if [ -f "$f" ]; then
    SZ=$(stat -c %s "$f" 2>/dev/null || echo 0)
    TOTAL=$((TOTAL + SZ))
  fi
  i=$((i+1))
done

echo "M9_RAW_BYTES=$TOTAL"
echo "M9_TEXT_LINES=$(wc -l < "$DIR/bl_text.txt" 2>/dev/null || echo 0)"
[ "$TOTAL" -gt 0 ] || echo "M9_ERROR=no_raw_data"
exit 0
