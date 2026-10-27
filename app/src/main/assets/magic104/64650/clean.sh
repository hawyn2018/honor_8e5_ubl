#!/system/bin/sh
D=/data/local/tmp/magic
R=/data/local/tmp/honor-64650

pkill -9 -f '[c]red_target' 2>/dev/null
pkill -9 -f '[t]64560_v36' 2>/dev/null
pkill -9 -f '[t]64560_cred' 2>/dev/null
pkill -9 -f '[v]ictim-child' 2>/dev/null
pkill -9 -f '[f]orker' 2>/dev/null
pkill -9 -f '[k]su_grant' 2>/dev/null
pkill -9 -f '[k]o_patch' 2>/dev/null
pkill -9 -f '[k]aslr_slide' 2>/dev/null
sleep 1
rm -rf "$D" "$R"
sync
echo CLEAN_OK
