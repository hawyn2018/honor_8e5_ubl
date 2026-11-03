/* SPDX-License-Identifier: GPL-3.0-only
 * Optional private EFI-variable counter; no OEM variables, GPT, or partitions.
 * Missing/failed counter services never block an otherwise healthy boot.
 */
#include "boot_recovery.h"

#define BR_ERROR(n) (UINT64_C(0x8000000000000000) | (n))
#define BR_NOT_FOUND BR_ERROR(14)
#define BR_UNSUPPORTED BR_ERROR(3)
#define BR_MAGIC UINT64_C(0x3742524642484e48)

typedef struct { uint32_t a; uint16_t b, c; uint8_t d[8]; } BRGuid;
typedef struct { uint32_t magic, version, count, checksum; } BRRecord;
typedef BRU64 (*GetVariable)(const uint16_t *, const BRGuid *, uint32_t *, BRU64 *, void *);
typedef BRU64 (*SetVariable)(const uint16_t *, const BRGuid *, uint32_t, BRU64, const void *);
typedef BRU64 (*Stall)(BRU64);
typedef BRU64 (*Watchdog)(BRU64, BRU64, BRU64, const void *);
static const BRGuid br_guid = {0x53c7ef8f, 0x21a9, 0x47dc,
    {0x99,0xdc,0x43,0xfe,0x6a,0x38,0xd1,0xe7}};
static const uint16_t br_name[] = {
    'H','n','u','b','l','E','f','i','s','p','B','o','o','t','F','a','i','l','u','r','e','s',0};

static BRU64 pointer_at(BRU64 address, BRU64 offset) {
    return address ? *(const BRU64 *)(uintptr_t)(address + offset) : 0;
}
static uint32_t checksum(const BRRecord *r) {
    return r->magic ^ r->version ^ r->count ^ UINT32_C(0x917ab36d);
}
static BRU64 runtime_services(const BRState *s) { return pointer_at(s->table, 0x58); }
static void write_counter(BRState *s, uint32_t count) {
    /* A persistent hardware/protocol failure can retry many times. Never hammer
     * flash-backed variables with an unchanged threshold value, even when the
     * service rejects writes. A cleared pre-handoff value can be persisted again.
     */
    if (s->nv_requested_count == count) return;
    s->nv_requested_count = count;
    BRU64 entry = pointer_at(runtime_services(s), 0x58);
    if (!entry) { s->nv_status = BR_UNSUPPORTED; return; }
    BRRecord record = {UINT32_C(0x48424642), 1, count, 0};
    record.checksum = checksum(&record);
    s->nv_status = ((SetVariable)(uintptr_t)entry)(br_name, &br_guid, 7, sizeof(record), &record);
}
void br_initialize(BRState *s, const BRConfig *c) {
    s->magic = BR_MAGIC;
    s->force = 0; s->failures = 0; s->last_status = 0; s->last_reason = 0;
    s->last_site = 0; s->manual_depth = 0; s->retry_count = 0;
    s->watchdog_status = 0; s->nv_status = BR_UNSUPPORTED; s->pending_fatal = 0;
    s->boot_scope = 1;
    s->nv_requested_count = UINT64_MAX;
    s->initial_canary = !c->canary_rva ? 0 : c->canary_bytes == 4 ?
        *(const uint32_t *)(uintptr_t)(s->base + c->canary_rva) : pointer_at(s->base, c->canary_rva);
    BRU64 entry = pointer_at(runtime_services(s), 0x48);
    if (entry) {
        BRRecord record = {0,0,0,0};
        BRU64 size = sizeof(record);
        s->nv_status = ((GetVariable)(uintptr_t)entry)(br_name, &br_guid, 0, &size, &record);
        if (!s->nv_status && size == sizeof(record) && record.magic == UINT32_C(0x48424642) &&
            record.version == 1 && record.checksum == checksum(&record))
            s->failures = record.count;
        else if (s->nv_status == BR_NOT_FOUND) s->nv_status = 0;
    }
    if (c->threshold && s->failures >= c->threshold) s->force = 1;
    s->initialized = 1;
}
void br_note_failure(BRState *s, const BRConfig *c) {
    s->force = 1;
    s->pending_fatal = 0;
    s->manual_depth = 0;
    /* A successful EFI return is still caught, but is not a boot-error count. */
    if (s->last_status || s->last_reason != BR_REASON_ENTRY_RETURN) {
        if (s->failures < UINT32_MAX) ++s->failures;
        BRU64 persist = c->threshold && s->failures > c->threshold ? c->threshold : s->failures;
        write_counter(s, (uint32_t)persist);
    }
}
void br_cleanup_event(BRState *s, const BRConfig *c) {
    /* Request sites are synchronous application-TPL paths. Notifications only
     * DEFER and return to firmware first. Close a still-active native timer
     * while the original caller stack is intact, before any nonlocal unwind.
     */
    if (c->event_slot_rva && c->event_cleanup_rva && pointer_at(s->base, c->event_slot_rva))
        ((void (*)(void))(uintptr_t)(s->base + c->event_cleanup_rva))();
}
void br_before_bootlinux(BRState *s, const BRConfig *c) {
    (void)c;
    s->boot_scope = 1;
    /* Keep the RAM count: a returned BootLinux restores it and adds a failure.
     * A successful nonreturning kernel handoff leaves the persistent count zero.
     * This runs before BootLinux obtains its memory-map key, not after it.
     */
    if (s->failures) write_counter(s, 0);
}
void br_prepare_retry(BRState *s, const BRConfig *c) {
    s->manual_depth = 0;
    s->boot_scope = 1;
    if (!s->force) return;
    if (c->canary_rva && c->canary_bytes == 4)
        *(volatile uint32_t *)(uintptr_t)(s->base + c->canary_rva) = (uint32_t)s->initial_canary;
    else if (c->canary_rva && c->canary_bytes == 8)
        *(volatile BRU64 *)(uintptr_t)(s->base + c->canary_rva) = s->initial_canary;
    if (c->continue_flag_rva) *(volatile uint8_t *)(uintptr_t)(s->base + c->continue_flag_rva) = 0;
    if (c->request_flag_rva) *(volatile uint8_t *)(uintptr_t)(s->base + c->request_flag_rva) = 1;
    BRU64 bs = pointer_at(s->table, 0x60);
    BRU64 wd = pointer_at(bs, 0x100);
    if (wd) s->watchdog_status = ((Watchdog)(uintptr_t)wd)(0, 0x10000, 0, 0);
    BRU64 delay = pointer_at(bs, 0xf8);
    if (s->retry_count && delay && c->retry_us) ((Stall)(uintptr_t)delay)(c->retry_us);
    ++s->retry_count;
}
