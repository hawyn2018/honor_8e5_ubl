#!/system/bin/sh
# 清理工作目录与运行残留。默认会删除 /data/local/tmp/magic 下的已部署资产，
# 并删除 BootForge 的 Root 执行通道 /data/local/tmp/bootforge；
# 下次运行前需要重新「部署提权资产」。
D=/data/local/tmp/magic

echo "== 清理残留进程 =="
pkill -9 -f '[t]64560' 2>/dev/null
pkill -9 -f '[c]red_target' 2>/dev/null
pkill -9 -f '[v]ictim-child' 2>/dev/null
pkill -9 exe 2>/dev/null
pkill -9 -f '[r]un.sh' 2>/dev/null
sleep 1

echo "== 删除工作目录与旧版残留 =="
rm -rf "$D"
rm -rf /data/local/tmp/.magicroot-exec
rm -rf /data/local/tmp/honor /data/local/tmp/root_stage.sh
rm -f /data/local/tmp/bl_*.bin /data/local/tmp/bl_text.txt
rm -rf /storage/emulated/0/Download/MagicRoot
rm -rf /data/local/tmp/bootforge

sync
echo "CLEAN_OK"
