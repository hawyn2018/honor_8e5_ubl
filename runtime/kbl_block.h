/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_BLOCK_H
#define HNU_KBL_BLOCK_H
#include "kbl_api.h"
#define KBL_BLOCK_READ 1u
#define KBL_BLOCK_WRITE 2u
#define KBL_BLOCK_READ_ONLY 1u
#define KBL_BLOCK_LABEL_BYTES 145u
struct kbl_block_info {
    char label[KBL_BLOCK_LABEL_BYTES];
    uint64_t bytes;
    uint32_t block_bytes,flags;
};
/* Opened by exact GPT label. Byte positions are partition-relative. The
 * reader supplies immutable media identity/size metadata, not immutable data.
 * close transfers cleanup ownership exactly once, like kbl_reader.close.
 * write_at reports completed bytes on partial failure; flush is explicit.
 */
struct kbl_block {
    struct kbl_reader reader;
    uint32_t block_bytes,flags;
    int (*write_at)(void *,uint64_t,const void *,size_t,size_t *);
    int (*flush)(void *);
};
#endif
