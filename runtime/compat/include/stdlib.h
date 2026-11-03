/* SPDX-License-Identifier: MIT */
#ifndef KBL_COMPAT_STDLIB_H
#define KBL_COMPAT_STDLIB_H
#include <stddef.h>
#define EXIT_SUCCESS 0
#define EXIT_FAILURE 1
void *malloc(size_t);void free(void *);void *calloc(size_t,size_t);void *realloc(void *,size_t);
int atoi(const char *);long atol(const char *);
long strtol(const char *,char **,int);unsigned long strtoul(const char *,char **,int);
long long strtoll(const char *,char **,int);unsigned long long strtoull(const char *,char **,int);
void qsort(void *,size_t,size_t,int(*)(const void *,const void *));
void *bsearch(const void *,const void *,size_t,size_t,int(*)(const void *,const void *));
int abs(int);long labs(long);long long llabs(long long);
char *getenv(const char *);int setenv(const char *,const char *,int);int unsetenv(const char *);
int system(const char *);
#endif
