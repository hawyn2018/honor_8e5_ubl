#ifndef KBL_EXT4_STRING_H
#define KBL_EXT4_STRING_H
#include <stddef.h>
void *kbl_ext4_memcpy(void *, const void *, size_t);
void *kbl_ext4_memmove(void *, const void *, size_t);
void *kbl_ext4_memset(void *, int, size_t);
int kbl_ext4_memcmp(const void *, const void *, size_t);
size_t kbl_ext4_strlen(const char *);
int kbl_ext4_strcmp(const char *, const char *);
int kbl_ext4_strncmp(const char *, const char *, size_t);
char *kbl_ext4_strcpy(char *, const char *);
char *kbl_ext4_strncpy(char *, const char *, size_t);
char *kbl_ext4_strcat(char *, const char *);
char *kbl_ext4_strchr(const char *, int);
char *kbl_ext4_strrchr(const char *, int);
char *kbl_ext4_strstr(const char *, const char *);
#define strstr kbl_ext4_strstr
#define memcpy kbl_ext4_memcpy
#define memmove kbl_ext4_memmove
#define memset kbl_ext4_memset
#define memcmp kbl_ext4_memcmp
#define strlen kbl_ext4_strlen
#define strcmp kbl_ext4_strcmp
#define strncmp kbl_ext4_strncmp
#define strcpy kbl_ext4_strcpy
#define strncpy kbl_ext4_strncpy
#define strcat kbl_ext4_strcat
#define strchr kbl_ext4_strchr
#define strrchr kbl_ext4_strrchr
#endif
