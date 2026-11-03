#pragma once
#include <stddef.h>
void *kbl_erofs_malloc(size_t);void *kbl_erofs_calloc(size_t,size_t);
void *kbl_erofs_realloc(void*,size_t);void kbl_erofs_free(void*);
#define malloc kbl_erofs_malloc
#define calloc kbl_erofs_calloc
#define realloc kbl_erofs_realloc
#define free kbl_erofs_free
