/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_SHA256_H
#define HNU_KBL_SHA256_H
#include "kbl_api.h"

struct kbl_sha256 {
    uint32_t state[8];
    uint64_t bytes;
    unsigned char block[64];
    size_t used;
    unsigned finalized;
};
void kbl_sha256_init(struct kbl_sha256 *);
int kbl_sha256_update(struct kbl_sha256 *, const void *, size_t);
int kbl_sha256_final(struct kbl_sha256 *, unsigned char digest[32]);
void kbl_sha256_hex(const unsigned char digest[32], char hex[65]);
/* Never closes the reader. Scratch belongs to the caller; no image-sized or
 * 64KiB stack buffer is allocated. Supports partial progress, rejects zero or
 * excessive progress and SHA-256 bit-length overflow. On error digest is zero.
 * Empty source permits NULL scratch. The source must stay stable while read. */
int kbl_sha256_reader(struct kbl_reader *, void *scratch, size_t scratch_bytes,
                      unsigned char digest[32]);
#endif
