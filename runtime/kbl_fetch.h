/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_FETCH_H
#define HNU_KBL_FETCH_H
#include "kbl_api.h"

/* Fastboot DATA carries an eight-digit hexadecimal 32-bit length. */
#define KBL_MAX_FETCH_BYTES UINT32_MAX

struct kbl_fetch_source {
    void *opaque;
    /* Partition lookup uses existing UEFI storage services, never starts KBL.
     * Reserved kbl.<id> names refer to explicitly exported read-only file
     * handles; absent/stale handles fail, never fall back to a disk partition.
     * A successful open must provide a close function and stable read source.
     */
    int (*open)(void *opaque, const char *name, struct kbl_reader *reader);
};
struct kbl_fetch_wire {
    void *opaque;
    int (*data)(void *opaque, uint32_t bytes);
    int (*write)(void *opaque, const void *data, size_t bytes, size_t *written);
    void (*final)(void *opaque, int okay, const char *reason);
    /* After DATA, errors require terminating the broken data transfer. A FAIL
     * packet in a partially sent binary body would be consumed as file bytes.
     * abort releases this transport transaction; it never destroys KBL.
     */
    void (*abort)(void *opaque);
};

/* Implements the server side of fetch:name[:0xOFFSET[:0xSIZE]].
 * Pre-reads the entire bounded chunk before DATA. Work is caller-owned private
 * storage and must be >=chunk size. Returns0 only for unrelated commands.
 * No firmware registration/USB adapter is implied by this standalone module.
 */
int kbl_fetch(const unsigned char *request, size_t bytes,
              const struct kbl_fetch_source *, const struct kbl_fetch_wire *,
              void *work, size_t work_bytes);
/* Query the exact required private buffer; opens/closes without payload I/O.
 * The subsequent fetch revalidates the source and requested range. */
int kbl_fetch_capacity(const unsigned char *request,size_t bytes,
                       const struct kbl_fetch_source *,size_t *work_bytes);
#endif
