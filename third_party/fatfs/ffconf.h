/* SPDX-License-Identifier: MIT -- KBL configuration; upstream files retain ChaN license. */
#ifndef HNU_KBL_FATFS_CONFIG_H
#define HNU_KBL_FATFS_CONFIG_H
#include <stddef.h>
#define KBL_FATFS_PORTABLE_TYPES 1
#define FS_FATFS_WINDOW_ALIGNMENT 16
#define FFCONF_DEF 80386
#define FF_FS_READONLY 0
#define FF_FS_MINIMIZE 0
#define FF_USE_FIND 0
#ifndef KBL_FATFS_TEST_MKFS
#define KBL_FATFS_TEST_MKFS 0
#endif
#define FF_USE_MKFS KBL_FATFS_TEST_MKFS
#define FF_USE_FASTSEEK 0
#define FF_USE_EXPAND 0
#define FF_USE_CHMOD 0
#define FF_USE_LABEL 0
#define FF_USE_FORWARD 0
#define FF_USE_STRFUNC 0
#define FF_PRINT_LLI 0
#define FF_PRINT_FLOAT 0
#define FF_STRF_ENCODE 0
#define FF_CODE_PAGE 437
#define FF_USE_LFN 2
#define FF_MAX_LFN 255
#define FF_LFN_UNICODE 2
#define FF_LFN_BUF 765
#define FF_SFN_BUF 36
#define FF_FS_RPATH 0
#define FF_PATH_DEPTH 32
/* One local numeric drive per explicit opaque adapter context. This is not
 * the number of mounts: each volume owns its own registration slot. */
#define FF_KBL_CONTEXT 1
#define FF_VOLUMES 1
#define FF_STR_VOLUME_ID 0
#define FF_MULTI_PARTITION 0
#define FF_MIN_SS 512
#define FF_MAX_SS 4096
#define FF_LBA64 1
#define FF_MIN_GPT 0x10000000
#define FF_USE_TRIM 0
#define FF_FS_TINY 0
#define FF_FS_EXFAT 1
#define FF_FS_NORTC 1
#define FF_NORTC_MON 1
#define FF_NORTC_MDAY 1
#define FF_NORTC_YEAR 2026
#define FF_FS_CRTIME 0
#define FF_FS_NOFSINFO 3
/* Sharing is enabled; its KBL record table grows through the volume allocator. */
#define FF_FS_LOCK 1
#define FF_FS_REENTRANT 0
#define FF_FS_TIMEOUT 1000
/* Namespace every emitted library symbol, including private disk callbacks. */
#define f_open kbl_fatfs_open
#define f_close kbl_fatfs_close
#define f_read kbl_fatfs_read
#define f_write kbl_fatfs_write
#define f_lseek kbl_fatfs_lseek
#define f_truncate kbl_fatfs_truncate
#define f_sync kbl_fatfs_sync
#define f_opendir kbl_fatfs_opendir
#define f_closedir kbl_fatfs_closedir
#define f_readdir kbl_fatfs_readdir
#define f_findfirst kbl_fatfs_findfirst
#define f_findnext kbl_fatfs_findnext
#define f_mkdir kbl_fatfs_mkdir
#define f_unlink kbl_fatfs_unlink
#define f_rename kbl_fatfs_rename
#define f_stat kbl_fatfs_stat
#define f_chmod kbl_fatfs_chmod
#define f_utime kbl_fatfs_utime
#define f_chdir kbl_fatfs_chdir
#define f_chdrive kbl_fatfs_chdrive
#define f_getcwd kbl_fatfs_getcwd
#define f_getfree kbl_fatfs_getfree
#define f_getlabel kbl_fatfs_getlabel
#define f_setlabel kbl_fatfs_setlabel
#define f_forward kbl_fatfs_forward
#define f_expand kbl_fatfs_expand
#define f_mount kbl_fatfs_mount
#define f_mkfs kbl_fatfs_mkfs
#define f_fdisk kbl_fatfs_fdisk
#define f_setcp kbl_fatfs_setcp
#define f_putc kbl_fatfs_putc
#define f_puts kbl_fatfs_puts
#define f_printf kbl_fatfs_printf
#define get_fattime kbl_fatfs_get_fattime
#define ff_oem2uni kbl_fatfs_oem2uni
#define ff_uni2oem kbl_fatfs_uni2oem
#define ff_wtoupper kbl_fatfs_wtoupper
#define disk_initialize kbl_fatfs_disk_initialize
#define disk_status kbl_fatfs_disk_status
#define disk_read kbl_fatfs_disk_read
#define disk_write kbl_fatfs_disk_write
#define disk_ioctl kbl_fatfs_disk_ioctl
void *kbl_fat_memcpy(void *,const void *,size_t);
void *kbl_fat_memset(void *,int,size_t);
int kbl_fat_memcmp(const void *,const void *,size_t);
size_t kbl_fat_strlen(const char *);
char *kbl_fat_strchr(const char *,int);
#define memcpy kbl_fat_memcpy
#define memset kbl_fat_memset
#define memcmp kbl_fat_memcmp
#define strlen kbl_fat_strlen
#define strchr kbl_fat_strchr
#endif
