#pragma once
#include <sys/types.h>
struct stat {mode_t st_mode;off_t st_size;};
#define S_IFMT 0170000
#define S_IFREG 0100000
#define S_IFDIR 0040000
#define S_IFLNK 0120000
#define S_IFCHR 0020000
#define S_IFBLK 0060000
#define S_IFIFO 0010000
#define S_IFSOCK 0140000
#define S_ISREG(m) (((m)&S_IFMT)==S_IFREG)
#define S_ISDIR(m) (((m)&S_IFMT)==S_IFDIR)
#define S_ISLNK(m) (((m)&S_IFMT)==S_IFLNK)
#define S_ISCHR(m) (((m)&S_IFMT)==S_IFCHR)
#define makedev(ma,mi) (((uint64_t)(ma)<<32)|(mi))
