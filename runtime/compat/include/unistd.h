/* SPDX-License-Identifier: MIT */
#ifndef KBL_COMPAT_UNISTD_H
#define KBL_COMPAT_UNISTD_H
#include <sys/types.h>
#define STDIN_FILENO 0
#define STDOUT_FILENO 1
#define STDERR_FILENO 2
#define SEEK_SET 0
#define SEEK_CUR 1
#define SEEK_END 2
ssize_t read(int,void *,size_t);ssize_t write(int,const void *,size_t);
off_t lseek(int,off_t,int);int close(int);int fsync(int);int unlink(const char *);
pid_t fork(void);int execve(const char *,char *const[],char *const[]);
int usleep(unsigned);unsigned sleep(unsigned);
#endif
