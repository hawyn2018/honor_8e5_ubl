/* SPDX-License-Identifier: MIT -- optional synchronous firmware application
 * API. */
#ifndef HNU_KBL_EFI_H
#define HNU_KBL_EFI_H
#include <stddef.h>
#include <stdint.h>
#define KBL_EFI_EXIT_UNITS 256u
/* Source length uses size_t without a fixed byte cap. PE image extents still
 * obey their encoded field widths and the firmware's available resources. */
enum kbl_efi_stage {
  KBL_EFI_VALIDATE = 1,
  KBL_EFI_LOAD,
  KBL_EFI_OPTIONS,
  KBL_EFI_START,
  KBL_EFI_RETURN,
  KBL_EFI_CLEANUP
};
struct kbl_efi_run_result {
  uint64_t status, cleanup_status;
  uint32_t stage, el_before, el_after, exit_units, exit_truncated;
  uint16_t exit_text[KBL_EFI_EXIT_UNITS];
};
/* Optional full-output provider extension. The old result/callback ABI stays
 * unchanged; its fixed exit_text is only a compatibility preview. The full
 * view borrows firmware-owned exit data until release_exit is called once.
 * Release errors leave the provider's allocation pending explicit cleanup. */
struct kbl_efi_run_full_result {
  struct kbl_efi_run_result result;
  const uint16_t *exit_text;
  size_t exit_units;
  void *exit_owner;
  int (*release_exit)(void *);
};
/* The optional provider is synchronous and exclusive. No yield, session IO,
 * transport reentry or asynchronous execution is allowed across the call.
 * It uses the actual UEFI environment at the current EL; it is not isolation.
 * Negative KBL_E denotes validation/loading/EL/cleanup failure. On zero, status
 * still contains the application's EFI_STATUS (possibly an EFI error value).
 * An application may take over boot and never return; USB configuration is
 * retained but no command response is promised while child code is running. */
#endif
