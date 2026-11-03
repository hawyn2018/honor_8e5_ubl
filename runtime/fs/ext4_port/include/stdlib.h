#ifndef KBL_EXT4_STDLIB_H
#define KBL_EXT4_STDLIB_H
#include "kbl_ext4_config.h"
void kbl_ext4_qsort(void *, size_t, size_t,
                    int (*)(const void *, const void *));
#define qsort kbl_ext4_qsort
#endif
