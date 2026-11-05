/* SPDX-License-Identifier: GPL-3.0-only
 * Manual reset of HnublEfispBootFailures only. No OEM/ARB/system records,
 * partition access, boot dispatch, event cleanup, or USB lifecycle operations.
 */
#include "kbl_errorcount.h"
#include "boot_recovery.h"

#define EC_NOT_FOUND KEFI_ERROR(14)
#define EC_COMPROMISED KEFI_ERROR(33)
typedef kefi_status (*ECGet)(const uint16_t *, const struct kefi_guid *, uint32_t *, uint64_t *, void *);
typedef kefi_status (*ECSet)(const uint16_t *, const struct kefi_guid *, uint32_t, uint64_t, const void *);
struct record { uint32_t magic, version, count, checksum; };
struct section { uint32_t rva, bytes, flags; };
static const struct kefi_guid own_guid = {0x53c7ef8f, 0x21a9, 0x47dc,
    {0x99,0xdc,0x43,0xfe,0x6a,0x38,0xd1,0xe7}};
static const uint16_t own_name[] = {
    'H','n','u','b','l','E','f','i','s','p','B','o','o','t','F','a','i','l','u','r','e','s',0};
static void copy(void *to_, const void *from_, size_t bytes) {
    unsigned char *to = to_; const unsigned char *from = from_;
    while (bytes--) *to++ = *from++;
}
static uint16_t u16(const unsigned char *p) { return (uint16_t)(p[0] | (uint16_t)p[1] << 8); }
static uint32_t u32(const unsigned char *p) { return (uint32_t)u16(p) | (uint32_t)u16(p+2) << 16; }
static uint32_t sum(const struct record *r) {
    return r->magic ^ r->version ^ r->count ^ BR_RECORD_CHECKSUM_SEED;
}
static int valid(const struct record *r, uint64_t bytes, uint32_t attrs) {
    return bytes == sizeof(*r) && attrs == BR_RECORD_ATTRIBUTES &&
        r->magic == BR_RECORD_MAGIC && r->version == BR_RECORD_VERSION && r->checksum == sum(r);
}
static int name_is(const unsigned char *p, const char *name) {
    for (unsigned i=0; i<8; ++i) if (p[i] != (unsigned char)name[i]) return 0;
    return 1;
}
static int in_range(uint64_t at, uint64_t bytes, uint64_t limit) {
    return at <= limit && bytes <= limit-at;
}
static int mapped(uint64_t rva, const struct section *s) {
    return rva >= s->rva && rva-s->rva < s->bytes;
}
static kefi_status find_cache(void *base_, size_t bytes, struct kefi_system_table *st, BRState **out) {
    const unsigned char *base = base_;
    *out = 0;
    if (!base || (uintptr_t)base > UINTPTR_MAX-bytes || bytes < 64 || u16(base) != 0x5a4d)
        return EC_COMPROMISED;
    uint32_t pe = u32(base+0x3c);
    if (!in_range(pe,24,bytes) || u32(base+pe) != 0x00004550 || u16(base+pe+4) != 0xaa64)
        return EC_COMPROMISED;
    uint32_t count = u16(base+pe+6), optional_bytes = u16(base+pe+20);
    uint64_t optional = (uint64_t)pe+24, table = optional+optional_bytes;
    if (!count || optional_bytes < 112 || !in_range(optional,optional_bytes,bytes) ||
        u16(base+optional) != 0x20b || !in_range(table,(uint64_t)count*40,bytes)) return EC_COMPROMISED;
    uint32_t image_bytes = u32(base+optional+56), headers = u32(base+optional+60);
    if (!image_bytes || image_bytes > bytes || headers > image_bytes || table+(uint64_t)count*40 > headers)
        return EC_COMPROMISED;
    struct section rx = {0}, rw = {0}; unsigned have_rx=0, have_rw=0;
    for (unsigned i=0; i<count; ++i) {
        const unsigned char *s = base+table+(uint64_t)i*40;
        uint32_t span = u32(s+8), raw_bytes = u32(s+16);
        if (span < raw_bytes) span = raw_bytes;
        uint32_t rva = u32(s+12);
        if (span && (rva < headers || !in_range(rva,span,image_bytes))) return EC_COMPROMISED;
        if (name_is(s,".textfr") || name_is(s,".datafr")) {
            struct section found = {rva,span,u32(s+36)};
            if (name_is(s,".textfr")) { rx=found; ++have_rx; } else { rw=found; ++have_rw; }
        }
    }
    if (!have_rx && !have_rw) return EC_NOT_FOUND;
    if (have_rx != 1 || have_rw != 1 || rx.bytes < sizeof(BRConfig) || rw.bytes < sizeof(BRState) ||
        (rx.flags&UINT32_C(0xe0000000)) != UINT32_C(0x60000000) ||
        (rw.flags&UINT32_C(0xe0000000)) != UINT32_C(0xc0000000) ||
        (uint64_t)rx.rva+rx.bytes > rw.rva || ((uintptr_t)base+rw.rva)%8) return EC_COMPROMISED;
    /* Both section names and the embedded self-relative configuration must
     * agree. The config's location may move as the independent module grows.
     * These are own-module ABI fields, never model/version ABL addresses.
     */
    unsigned candidates = 0;
    for (uint64_t offset=0; offset <= rx.bytes-sizeof(BRConfig); offset+=8) {
        BRConfig c; copy(&c,base+rx.rva+offset,sizeof(c));
        if (c.self_rva != rx.rva || c.state_rva != rw.rva) continue;
        if (c.version != 1 || c.state_bytes != 4096 || c.state_bytes > rw.bytes ||
            (c.canary_bytes != 4 && c.canary_bytes != 8) ||
            !in_range(c.entry_rva,4,image_bytes) || !in_range(c.bootlinux_rva,4,image_bytes) ||
            !in_range(c.auth_rva,4,image_bytes) || !in_range(c.continue_flag_rva,1,image_bytes) ||
            !c.entry_rva || !c.bootlinux_rva || !c.auth_rva || !c.continue_flag_rva ||
            (c.request_flag_rva && !in_range(c.request_flag_rva,1,image_bytes)) ||
            (c.canary_rva && !in_range(c.canary_rva,c.canary_bytes,image_bytes)) ||
            (c.set_active_rva && !in_range(c.set_active_rva,4,image_bytes)) ||
            (c.event_slot_rva && !in_range(c.event_slot_rva,8,image_bytes)) ||
            (c.event_cleanup_rva && !in_range(c.event_cleanup_rva,4,image_bytes))) return EC_COMPROMISED;
        for (unsigned i=0; i<16; ++i) if (c.reserved[i]) return EC_COMPROMISED;
        ++candidates;
    }
    if (candidates != 1) return EC_COMPROMISED;
    /* No other section may alias either owned mapping. */
    for (unsigned i=0; i<count; ++i) {
        const unsigned char *s = base+table+(uint64_t)i*40;
        if (name_is(s,".textfr") || name_is(s,".datafr")) continue;
        uint32_t size=u32(s+8), raw=u32(s+16), rva=u32(s+12); if (size < raw) size=raw;
        if (size && (mapped(rva,&rx) || mapped(rva,&rw) ||
            (rva <= rx.rva && (uint64_t)rva+size > rx.rva) ||
            (rva <= rw.rva && (uint64_t)rva+size > rw.rva))) return EC_COMPROMISED;
    }
    BRState *state = (BRState *)(void *)(base+rw.rva);
    if (state->magic != BR_STATE_MAGIC || state->base != (uintptr_t)base ||
        state->table != (uintptr_t)st || state->initialized != 1) return EC_COMPROMISED;
    *out = state;
    return KEFI_SUCCESS;
}
static int sync_cache(volatile BRState *s) {
    /* Keep the native caller's scope, saved CPU context, canary, manual depth,
     * native request/continue flags, live event and diagnostic tail intact.
     * A subsequent real failure starts at one and can be persisted again.
     */
    s->failures=0; s->force=0; s->pending_fatal=0;
    s->last_status=0; s->last_reason=0; s->last_site=0;
    s->retry_count=0; s->nv_status=0; s->nv_requested_count=0;
    return !s->failures && !s->force && !s->pending_fatal && !s->last_status &&
        !s->last_reason && !s->last_site && !s->retry_count && !s->nv_status && !s->nv_requested_count;
}
#if defined(KBL_ERRORCOUNT_TEST)
__declspec(dllexport)
#endif
kefi_status kbl_errorcount_clear(struct kefi_system_table *st, void *parent_base,
                                size_t parent_bytes, struct kbl_errorcount_result *r) {
    if (!r) return KEFI_INVALID;
    *r=(struct kbl_errorcount_result){0};
    r->get_status=r->set_status=r->verify_status=r->cache_status=KEFI_UNSUPPORTED;
    if (!st || st->header.signature != KEFI_SYSTEM_SIGNATURE || !st->runtime_services) return KEFI_UNSUPPORTED;
    ECGet get; ECSet set;
    copy(&get,(const unsigned char *)st->runtime_services+0x48,sizeof(get));
    copy(&set,(const unsigned char *)st->runtime_services+0x58,sizeof(set));
    if (!get) return KEFI_UNSUPPORTED;
    BRState *cache=0;
    r->phase=KBL_ERRORCOUNT_CACHE;
    r->cache_status=find_cache(parent_base,parent_bytes,st,&cache);
    if (r->cache_status && r->cache_status != EC_NOT_FOUND) return r->cache_status;
    r->cache_found=cache != 0;
    struct record v={0}; uint64_t size=sizeof(v); uint32_t attrs=0;
    r->phase=KBL_ERRORCOUNT_READ;
    r->get_status=get(own_name,&own_guid,&attrs,&size,&v);
    if (r->get_status != EC_NOT_FOUND) {
        if (r->get_status) return r->get_status;
        r->phase=KBL_ERRORCOUNT_FORMAT;
        if (!valid(&v,size,attrs)) return EC_COMPROMISED;
        r->record_present=1; r->before_known=1; r->before=r->after=v.count;
        if (v.count) {
            r->phase=KBL_ERRORCOUNT_WRITE;
            if (!set) return KEFI_UNSUPPORTED;
            v.count=0; v.checksum=sum(&v); r->write_attempted=1;
            r->set_status=set(own_name,&own_guid,attrs,sizeof(v),&v);
            if (r->set_status) return r->set_status;
        }
    } else r->before_known=1;
    /* Also reconfirm already-zero and absent records. Do not update a live
     * cache from an unverified service return or a rejected/partial write.
     */
    r->phase=KBL_ERRORCOUNT_VERIFY;
    v=(struct record){0}; size=sizeof(v); attrs=0;
    r->verify_status=get(own_name,&own_guid,&attrs,&size,&v);
    if (r->verify_status != EC_NOT_FOUND) {
        if (r->verify_status) return r->verify_status;
        if (!valid(&v,size,attrs)) return EC_COMPROMISED;
        r->after=v.count; r->after_known=1;
        if (v.count) return EC_COMPROMISED;
    } else { r->after=0; r->after_known=1; }
    r->verified=1;
    if (cache) {
        if (!sync_cache(cache)) { r->cache_status=KEFI_ERROR(7); return r->cache_status; }
        r->cache_synced=1;
    }
    r->phase=KBL_ERRORCOUNT_DONE;
    return KEFI_SUCCESS;
}
const char *kbl_errorcount_phase_name(unsigned phase) {
    switch (phase) {
    case KBL_ERRORCOUNT_CONTEXT: return "context";
    case KBL_ERRORCOUNT_CACHE: return "cache";
    case KBL_ERRORCOUNT_READ: return "read";
    case KBL_ERRORCOUNT_FORMAT: return "format";
    case KBL_ERRORCOUNT_WRITE: return "write";
    case KBL_ERRORCOUNT_VERIFY: return "verify";
    case KBL_ERRORCOUNT_DONE: return "done";
    default: return "unknown";
    }
}
