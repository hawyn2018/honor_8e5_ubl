/* SPDX-License-Identifier: MIT -- test-only freestanding libc surface. */
#include <stddef.h>
#include <stdint.h>
extern void *stderr;
void *memcpy(void *,const void *,size_t);
void *memset(void *,int,size_t);
int memcmp(const void *,const void *,size_t);
size_t strlen(const char *);
void *malloc(size_t);
void free(void *);
int printf(const char *,...);
int fprintf(void *,const char *,...);
_Noreturn void exit(int);
