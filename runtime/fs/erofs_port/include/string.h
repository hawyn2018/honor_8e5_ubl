#pragma once
#include <stddef.h>
void *kbl_erofs_memcpy(void*,const void*,size_t);
void *kbl_erofs_memset(void*,int,size_t);
void *kbl_erofs_memmove(void*,const void*,size_t);
void *kbl_erofs_memchr(const void*,int,size_t);
int kbl_erofs_memcmp(const void*,const void*,size_t);
size_t kbl_erofs_strlen(const char*);size_t kbl_erofs_strnlen(const char*,size_t);
int kbl_erofs_strcmp(const char*,const char*);int kbl_erofs_strncmp(const char*,const char*,size_t);
char *kbl_erofs_strchr(const char*,int);char *kbl_erofs_strrchr(const char*,int);
char *kbl_erofs_strncpy(char*,const char*,size_t);
char *strerror(int);
#define memcpy kbl_erofs_memcpy
#define memset kbl_erofs_memset
#define memmove kbl_erofs_memmove
#define memchr kbl_erofs_memchr
#define memcmp kbl_erofs_memcmp
#define strlen kbl_erofs_strlen
#define strnlen kbl_erofs_strnlen
#define strcmp kbl_erofs_strcmp
#define strncmp kbl_erofs_strncmp
#define strchr kbl_erofs_strchr
#define strrchr kbl_erofs_strrchr
#define strncpy kbl_erofs_strncpy
