/* SPDX-License-Identifier: MIT */
#ifndef KBL_COMPAT_FCNTL_H
#define KBL_COMPAT_FCNTL_H
#define O_RDONLY 0
#define O_WRONLY 1
#define O_RDWR 2
#define O_CREAT 64
#define O_EXCL 128
#define O_TRUNC 512
#define O_APPEND 1024
int open(const char *,int,...);
#endif
