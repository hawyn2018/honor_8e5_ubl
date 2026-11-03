/* SPDX-License-Identifier: MIT -- real AArch64 UEFI application, not a KBL ELF.
 * It returns immediately, never exits Boot Services, touches USB, registers a
 * callback, changes EL, changes x18 or starts a kernel. */
#include "../../uefi/kbl_efi_types.h"
struct text_out {
  void *reset;
  kefi_status (*output)(struct text_out *, const uint16_t *);
};
kefi_status EfiMain(kefi_handle, struct kefi_system_table *);
static kefi_status (*volatile relocation_anchor)(
    kefi_handle, struct kefi_system_table *) = EfiMain;
static unsigned read_el(void) {
  uint64_t value;
  __asm__ volatile("mrs %0, CurrentEL" : "=r"(value));
  return (unsigned)((value >> 2) & 3);
}
kefi_status EfiMain(kefi_handle image, struct kefi_system_table *st) {
  unsigned before = read_el();
  uint16_t message[] = {75,  66,  76,  32,  69, 70,  73,  32,  82,  69,  84,
                        85,  82,  78,  58,  32, 67,  117, 114, 114, 101, 110,
                        116, 69,  76,  61,  95, 44,  32,  114, 101, 116, 117,
                        114, 110, 101, 100, 32, 116, 111, 32,  99,  97,  108,
                        108, 101, 114, 46,  13, 10,  0};
  if (!image || !st || st->header.signature != KEFI_SYSTEM_SIGNATURE ||
      before < 1 || before > 3 || relocation_anchor != EfiMain)
    return KEFI_INVALID;
  message[26] = (uint16_t)('0' + before);
  if (st->console_out) {
    struct text_out *out = st->console_out;
    if (out->output)
      (void)out->output(out, message);
  }
  return read_el() == before ? KEFI_SUCCESS : KEFI_ACCESS_DENIED;
}
