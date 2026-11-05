#ifndef VM_DTBO_H
#define VM_DTBO_H
#include <stdint.h>
#include <stddef.h>
/* All inputs are borrowed from the native, already completed partition read.
 * No allocator, firmware service, disk access or guest launch is used here. */
enum { VM_DTBO_NORMAL_AFTER_KEYS=1, VM_DTBO_FASTBOOT=2 };
enum { VM_DTBO_KEPT=0, VM_DTBO_FIXED=1, VM_DTBO_MODE=-1,
       VM_DTBO_MALFORMED=-2, VM_DTBO_NO_MATCH=-3, VM_DTBO_AMBIGUOUS=-4, VM_DTBO_UNSUPPORTED=-5 };
int vm_dtbo_select(const void *table, size_t available, uint32_t board,
                   uint32_t revision, int32_t indices[2],
                   const uint32_t domains[2], uint32_t mode);
#endif
