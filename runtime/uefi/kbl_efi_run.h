/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_EFI_RUN_H
#define HNU_KBL_EFI_RUN_H
#include "../kbl_efi.h"
#include "kbl_efi_types.h"
struct kbl_efi_launcher {
  kefi_handle parent, child;
  struct kefi_system_table *system;
  unsigned (*current_el)(void *);
  void *opaque;
  unsigned active, expected_el, exit_borrowed;
  void *file_buffer, *options, *exit_data;
  size_t file_bytes, options_bytes, exit_bytes;
  kefi_status last_cleanup;
};
/* A zeroed launcher is initialized only at explicit KBL START. No loader,
 * protocol, driver, timer or USB side effect is performed by initialization. */
void kbl_efi_launcher_init(struct kbl_efi_launcher *, kefi_handle,
                           struct kefi_system_table *, void *,
                           unsigned (*)(void *), unsigned expected_el);
int kbl_efi_pe_validate(const void *, size_t);
int kbl_efi_run(struct kbl_efi_launcher *, const void *, size_t,
                const uint16_t *options, size_t options_bytes,
                struct kbl_efi_run_result *);
int kbl_efi_run_full(struct kbl_efi_launcher *, const void *, size_t,
                const uint16_t *options, size_t options_bytes,
                struct kbl_efi_run_full_result *);
/* Explicit stop/retry cleanup, never invoked by a boot-path hook. Failure
 * keeps image/options/file/exit-data ownership. No child is force terminated.
 */
int kbl_efi_cleanup(struct kbl_efi_launcher *);
int kbl_efi_can_unload(const struct kbl_efi_launcher *);
#endif
