/* SPDX-License-Identifier: MIT -- allocation/output are explicit Unicorn
 * callbacks. Memory operations execute as real ARM64 fixture instructions. */
#include "native_usb_fixture_libc.h"
void *stderr;
void *memcpy(void *p,const void *q,size_t n){unsigned char*d=p;const unsigned char*s=q;while(n--)*d++=*s++;return p;}
void *memset(void *p,int c,size_t n){unsigned char*d=p;while(n--)*d++=(unsigned char)c;return p;}
int memcmp(const void *p,const void *q,size_t n){const unsigned char*a=p,*b=q;while(n--){if(*a!=*b)return *a-*b;++a;++b;}return 0;}
size_t strlen(const char *p){size_t n=0;while(p[n])++n;return n;}
__attribute__((noinline)) void *malloc(size_t n){(void)n;return 0;}
__attribute__((noinline)) void free(void *p){(void)p;}
__attribute__((noinline)) int printf(const char *p,...){(void)p;return 0;}
__attribute__((noinline)) int fprintf(void *f,const char *p,...){(void)f;(void)p;return 0;}
_Noreturn void exit(int code){(void)code;for(;;)__asm__ volatile("nop");}
unsigned native_usb_fixture_current_el(void){uint64_t value;__asm__ volatile("mrs %0, CurrentEL":"=r"(value));return (unsigned)(value>>2);}
