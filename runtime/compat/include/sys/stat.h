/* SPDX-License-Identifier: MIT */
#ifndef KBL_COMPAT_STAT_H
#define KBL_COMPAT_STAT_H
#include <sys/types.h>
#define S_IFMT 0170000u
#define S_IFREG 0100000u
#define S_IFDIR 0040000u
#define S_IFBLK 0060000u
#define S_ISREG(m) (((m)&S_IFMT)==S_IFREG)
#define S_ISDIR(m) (((m)&S_IFMT)==S_IFDIR)
#define S_ISBLK(m) (((m)&S_IFMT)==S_IFBLK)
struct stat {off_t st_size;mode_t st_mode;uint32_t st_blksize;};
int stat(const char *,struct stat *);int mkdir(const char *,mode_t);
#endif
