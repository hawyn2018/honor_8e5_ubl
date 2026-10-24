#!/system/bin/sh
# 公共变量与函数（被其它脚本 source）
TR=/sys/kernel/tracing
[ -d "$TR/events" ] || TR=/sys/kernel/debug/tracing
DIR=/data/local/tmp/magic
WORK="$DIR"

say() { echo "[m9] $*"; }
kv() { printf 'M9_%s=%s\n' "$1" "$2"; }

have() { command -v "$1" >/dev/null 2>&1; }
