/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef HNUBL_BOOT_RECOVERY_H
#define HNUBL_BOOT_RECOVERY_H

#define BR_CFG_SELF_RVA 0
#define BR_CFG_STATE_RVA 8
#define BR_CFG_ENTRY_RVA 16
#define BR_CFG_REQUEST_FLAG_RVA 24
#define BR_CFG_CONTINUE_FLAG_RVA 32
#define BR_CFG_BOOTLINUX_RVA 40
#define BR_CFG_SET_ACTIVE_RVA 48
#define BR_CFG_CANARY_RVA 56
#define BR_CFG_THRESHOLD 64
#define BR_CFG_RETRY_US 72
#define BR_CFG_STATE_BYTES 80
#define BR_CFG_VERSION 88
#define BR_CFG_CANARY_BYTES 96
#define BR_CFG_AUTH_RVA 104
#define BR_CFG_EVENT_SLOT_RVA 112
#define BR_CFG_EVENT_CLEANUP_RVA 120
#define BR_CFG_BYTES 256

#define BR_STATE_BASE 8
#define BR_STATE_HANDLE 16
#define BR_STATE_TABLE 24
#define BR_STATE_SP 32
#define BR_STATE_NZCV 40
#define BR_STATE_FORCE 48
#define BR_STATE_FAILURES 56
#define BR_STATE_STATUS 64
#define BR_STATE_REASON 72
#define BR_STATE_SITE 80
#define BR_STATE_MANUAL_DEPTH 88
#define BR_STATE_GPR 96
#define BR_STATE_FP 208
#define BR_STATE_PENDING 304
#define BR_STATE_BOOT_SCOPE 320
#define BR_STATE_MANUAL_BOOTLINUX_STATUS 336
#define BR_STATE_MANUAL_BOOTLINUX_COUNT 344
#define BR_STATE_MANUAL_BOOTLINUX_SITE 352
#define BR_STATE_LAYOUT_BYTES 360

#define BR_REASON_ENTRY_RETURN 1
#define BR_REASON_AUTO_SLOT 2
#define BR_REASON_BOOT_FATAL 3
#define BR_REASON_CARDINFO 4
#define BR_REASON_BOOTLINUX_RETURN 5
#define BR_REASON_DEFAULT_SLOT 6
#define BR_REASON_AUTH_RETURN 7

#ifndef __ASSEMBLER__
#include <stddef.h>
#include <stdint.h>
typedef uint64_t BRU64;
#define BR_STATE_MAGIC UINT64_C(0x3742524642484e48)
#define BR_RECORD_MAGIC UINT32_C(0x48424642)
#define BR_RECORD_VERSION UINT32_C(1)
#define BR_RECORD_ATTRIBUTES UINT32_C(7)
#define BR_RECORD_CHECKSUM_SEED UINT32_C(0x917ab36d)
typedef struct {
    BRU64 self_rva, state_rva, entry_rva, request_flag_rva, continue_flag_rva;
    BRU64 bootlinux_rva, set_active_rva, canary_rva, threshold, retry_us;
    BRU64 state_bytes, version, canary_bytes, auth_rva, event_slot_rva, event_cleanup_rva, reserved[16];
} BRConfig;
typedef struct {
    BRU64 magic, base, handle, table, saved_sp, saved_nzcv;
    BRU64 force, failures, last_status, last_reason, last_site, manual_depth;
    BRU64 saved_gpr[13], alignment_padding, saved_fp[8];
    BRU64 nv_status, initialized, watchdog_status, retry_count, pending_fatal, initial_canary, boot_scope, nv_requested_count;
    /* Diagnostic tail only. No permission, retry, or boot decision reads it. */
    BRU64 manual_bootlinux_status, manual_bootlinux_count, manual_bootlinux_site;
} BRState;
_Static_assert(offsetof(BRState, saved_gpr) == BR_STATE_GPR, "GPR ABI");
_Static_assert(offsetof(BRState, saved_fp) == BR_STATE_FP, "FP ABI");
_Static_assert(offsetof(BRState, force) == BR_STATE_FORCE, "force ABI");
_Static_assert(offsetof(BRState, pending_fatal) == BR_STATE_PENDING, "pending ABI");
_Static_assert(offsetof(BRState, boot_scope) == BR_STATE_BOOT_SCOPE, "boot scope ABI");
_Static_assert(offsetof(BRState, nv_requested_count) == 328, "NV deduplication ABI");
_Static_assert(offsetof(BRState, manual_bootlinux_status) == BR_STATE_MANUAL_BOOTLINUX_STATUS, "manual result tail ABI");
_Static_assert(offsetof(BRState, manual_bootlinux_count) == BR_STATE_MANUAL_BOOTLINUX_COUNT, "manual count tail ABI");
_Static_assert(offsetof(BRState, manual_bootlinux_site) == BR_STATE_MANUAL_BOOTLINUX_SITE, "manual site tail ABI");
_Static_assert(sizeof(BRState) == BR_STATE_LAYOUT_BYTES, "state layout ABI");
_Static_assert(sizeof(BRConfig) == BR_CFG_BYTES, "config ABI");
void br_initialize(BRState *state, const BRConfig *config);
void br_prepare_retry(BRState *state, const BRConfig *config);
void br_note_failure(BRState *state, const BRConfig *config);
void br_before_bootlinux(BRState *state, const BRConfig *config);
void br_cleanup_event(BRState *state, const BRConfig *config);
#endif
#endif
