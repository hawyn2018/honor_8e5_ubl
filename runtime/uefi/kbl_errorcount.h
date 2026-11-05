/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef HNUBL_KBL_ERRORCOUNT_H
#define HNUBL_KBL_ERRORCOUNT_H
#include "kbl_efi_types.h"

enum kbl_errorcount_phase {
    KBL_ERRORCOUNT_CONTEXT = 0, KBL_ERRORCOUNT_CACHE,
    KBL_ERRORCOUNT_READ, KBL_ERRORCOUNT_FORMAT,
    KBL_ERRORCOUNT_WRITE, KBL_ERRORCOUNT_VERIFY, KBL_ERRORCOUNT_DONE
};
struct kbl_errorcount_result {
    kefi_status get_status, set_status, verify_status, cache_status;
    uint64_t before, after;
    unsigned phase, record_present, write_attempted, verified;
    unsigned cache_found, cache_synced;
    unsigned before_known, after_known;
};

/* Explicit command only. The parent is an actual LoadedImage mapping, bounded
 * by its ImageSize, never a file buffer or a source-firmware address table.
 * Only the application's exact private variable GUID/name and validated record
 * are written. A verified zero/absent record commits the matching live BRState
 * cache change; every earlier failure leaves that cache unchanged. A malformed
 * owned recovery mapping fails before any variable access. No owned mappings
 * is a supported cache-absent result. All EFI errors are retained in result.
 */
#if defined(KBL_ERRORCOUNT_TEST)
__declspec(dllexport)
#endif
kefi_status kbl_errorcount_clear(struct kefi_system_table *, void *parent_base,
                                size_t parent_bytes, struct kbl_errorcount_result *);
const char *kbl_errorcount_phase_name(unsigned phase);
#endif
