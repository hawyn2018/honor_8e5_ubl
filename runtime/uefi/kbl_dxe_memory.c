/* SPDX-License-Identifier: MIT */
#include "kbl_dxe_memory.h"
#define MASK (KEFI_RP|KEFI_RO|KEFI_XP)
static const struct kefi_guid guid={0x05ad34ba,0x6f02,0x4214,{0x95,0x2e,0x4d,0xa0,0x39,0x8e,0x2b,0xb9}};
static const struct kefi_guid cpu_guid={0x26baccb1,0x6f42,0x11d4,{0xbc,0xe7,0x00,0x80,0xc7,0x3c,0x88,0x81}};
struct configuration { struct kefi_guid guid;void *table; };
static int guid_equal(const struct kefi_guid*a,const struct kefi_guid*b){
    const unsigned char*x=(const unsigned char*)a,*y=(const unsigned char*)b;
    for(unsigned i=0;i<sizeof(*a);++i)if(x[i]!=y[i])return 0;return 1;
}
static int range(uint64_t p,uint64_t n){return p&&n&&!(p&(KEFI_PAGE-1))&&!(n&(KEFI_PAGE-1))&&
    p<=UINT64_MAX-n;}
static kefi_status descriptor(struct kbl_dxe_memory*m,uint64_t p,struct kbl_gcd_descriptor*d){
    *d=(struct kbl_gcd_descriptor){0};kefi_status s=m->services->get(p,d);if(s)return s;
    if(!d->length||d->base>p||p-d->base>d->length||KEFI_PAGE>d->length-(p-d->base)||
        (d->type!=2&&d->type!=5))return KEFI_UNSUPPORTED;
    return KEFI_SUCCESS;
}
static kefi_status gcd_get(struct kefi_memory_attributes*a,uint64_t p,uint64_t n,uint64_t*out){
    struct kbl_dxe_memory*m=(void*)a;if(!m||!m->services||!out||!range(p,n))return KEFI_INVALID;
    uint64_t first=0;for(uint64_t i=0;i<n;i+=KEFI_PAGE){struct kbl_gcd_descriptor d;
        kefi_status s=descriptor(m,p+i,&d);if(s)return s;
        uint64_t bits=d.attributes&MASK;if(i&&bits!=first)return KEFI_UNSUPPORTED;first=bits;
    }*out=first;return KEFI_SUCCESS;
}
static kefi_status change(struct kefi_memory_attributes*a,uint64_t p,uint64_t n,uint64_t bits,int clear){
    struct kbl_dxe_memory*m=(void*)a;if(!m||!m->services||!range(p,n)||(bits&~MASK))return KEFI_INVALID;
    /* Complete capability/range preflight before the first modification. */
    for(uint64_t i=0;i<n;i+=KEFI_PAGE){struct kbl_gcd_descriptor d;kefi_status s=descriptor(m,p+i,&d);if(s)return s;
        uint64_t next=clear?d.attributes&~bits:d.attributes|bits;
        m->diagnostic_address=p+i;m->diagnostic_caps=d.capabilities;m->diagnostic_caps_valid=1;m->diagnostic_before=d.attributes;m->diagnostic_requested=next;
        if(next&~d.capabilities){m->inner_phase="cap_preflight";return KEFI_UNSUPPORTED;}
    }
    for(uint64_t i=0;i<n;i+=KEFI_PAGE){struct kbl_gcd_descriptor d;kefi_status s=descriptor(m,p+i,&d);if(s)return s;
        uint64_t before=d.attributes,next=clear?before&~bits:before|bits;
        m->diagnostic_address=p+i;m->diagnostic_caps=d.capabilities;m->diagnostic_caps_valid=1;m->diagnostic_before=before;m->diagnostic_requested=next;
        if(next&~d.capabilities){m->inner_phase="cap_recheck";return KEFI_UNSUPPORTED;}
        if(next!=before){s=m->services->set(p+i,KEFI_PAGE,next);if(s){m->inner_phase="gcd_set";return s;}}
        s=descriptor(m,p+i,&d);if(s)return s;
        if(d.attributes!=next){m->inner_phase="gcd_readback";return KEFI_ACCESS_DENIED;}
    }return KEFI_SUCCESS;
}
static int cpu_ready(struct kbl_dxe_memory*m){
    if(m->cpu&&m->cpu_set)return 1;
    if(!m->bs||!m->bs->locate_protocol)return 0;
    void*p=0;kefi_status s=m->bs->locate_protocol(&cpu_guid,0,&p);
    if(s||!p||((uintptr_t)p&7))return 0;
    kefi_status (*fn)(void*,uint64_t,uint64_t,uint64_t)=*(kefi_status(**)(void*,uint64_t,uint64_t,uint64_t))((uintptr_t)p+0x38);
    if(!fn)return 0;m->cpu=p;m->cpu_set=fn;return 1;
}
static kefi_status cpu_change(struct kbl_dxe_memory*m,uint64_t p,uint64_t n,uint64_t bits,int clear){
    m->provider="cpuarch";
    if(!cpu_ready(m)){m->inner_phase="cpu_protocol";return KEFI_UNSUPPORTED;}
    if(!kbl_arch_memory_profile(&m->arch)){m->inner_phase=m->arch.failure?m->arch.failure:"arch_profile";return KEFI_UNSUPPORTED;}
    m->cpu_active=1; /* Also retain the real getter after partial failure. */
    kefi_status s=kbl_arch_memory_change(&m->arch,p,n,bits,clear,m->cpu,m->cpu_set);
    if(m->arch.last_address){m->diagnostic_address=m->arch.last_address;m->diagnostic_before=m->arch.last_before;m->diagnostic_requested=m->arch.last_requested;}
    if(s)m->inner_phase=m->arch.failure?m->arch.failure:"cpu_change";
    return s;
}
static kefi_status get(struct kefi_memory_attributes*a,uint64_t p,uint64_t n,uint64_t*out){
    struct kbl_dxe_memory*m=(void*)a;
    if(!m->strict)return gcd_get(a,p,n,out);
    if(m->native&&!m->cpu_active){m->provider="native";kefi_status s=m->native->get(m->native,p,n,out);
        if(s!=KEFI_UNSUPPORTED){m->diagnostic_status=s;if(s)m->inner_phase="native_get";return s;}
        m->fallback_reason="native_get_unsupported";
    }
    m->provider=m->cpu_active?"cpuarch":"dxe-arch";
    if(!kbl_arch_memory_get(&m->arch,p,n,out)){m->inner_phase=m->arch.failure;return m->diagnostic_status=KEFI_UNSUPPORTED;}
    return m->diagnostic_status=KEFI_SUCCESS;
}
static kefi_status dispatch_change(struct kefi_memory_attributes*a,uint64_t p,uint64_t n,uint64_t bits,int clear){
    struct kbl_dxe_memory*m=(void*)a;m->inner_phase=0;m->diagnostic_caps_valid=0;m->diagnostic_address=p;m->diagnostic_requested=bits;
    kefi_status s;
    if(!m->strict){m->provider="dxe";s=change(a,p,n,bits,clear);return m->diagnostic_status=s;}
    if(m->cpu_active){s=cpu_change(m,p,n,bits,clear);return m->diagnostic_status=s;}
    if(m->native){
        m->provider="native";s=clear?m->native->clear(m->native,p,n,bits):m->native->set(m->native,p,n,bits);
        if(s!=KEFI_UNSUPPORTED){if(s)m->inner_phase=clear?"native_clear":"native_set";return m->diagnostic_status=s;}
        m->fallback_reason=clear?"native_clear_unsupported":"native_set_unsupported";
        s=cpu_change(m,p,n,bits,clear);return m->diagnostic_status=s;
    }
    m->provider="dxe-arch";
    /* Inspect RO capability before the first RW/NX allocation transition.
     * A missing RO capability selects the actual CPU/getter pair for the
     * allocation's complete lifetime; GCD capabilities remain untouched. */
    if(m->services&&range(p,n))for(uint64_t i=0;i<n;i+=4096){struct kbl_gcd_descriptor d;
        s=descriptor(m,p+i,&d);if(s){m->inner_phase="descriptor";return m->diagnostic_status=s;}
        if(!(d.capabilities&KEFI_RO)){
            m->diagnostic_address=p+i;m->diagnostic_caps=d.capabilities;m->diagnostic_caps_valid=1;m->diagnostic_before=d.attributes;
            m->fallback_reason="gcd_ro_capability";s=cpu_change(m,p,n,bits,clear);return m->diagnostic_status=s;
        }
    }
    if(!m->services){m->fallback_reason="gcd_absent";s=cpu_change(m,p,n,bits,clear);return m->diagnostic_status=s;}
    /* Even a successful GCD setter must have a real architectural getter in
     * the production wrapper. Its software descriptor is not MMU evidence. */
    for(uint64_t i=0;i<n;i+=4096){struct kbl_arch_page before;
        if(!kbl_arch_memory_page(&m->arch,p+i,&before)){m->inner_phase=m->arch.failure;return m->diagnostic_status=KEFI_UNSUPPORTED;}
    }
    s=change(a,p,n,bits,clear);
    if(s==KEFI_UNSUPPORTED){m->fallback_reason="gcd_set_unsupported";s=cpu_change(m,p,n,bits,clear);}
    return m->diagnostic_status=s;
}
static kefi_status set(struct kefi_memory_attributes*a,uint64_t p,uint64_t n,uint64_t bits){return dispatch_change(a,p,n,bits,0);}
static kefi_status clear(struct kefi_memory_attributes*a,uint64_t p,uint64_t n,uint64_t bits){return dispatch_change(a,p,n,bits,1);}
int kbl_dxe_memory_init(struct kbl_dxe_memory*m,const struct kefi_system_table*st){
    if(!m||m->services||m->api.get||m->api.set||m->api.clear||!st||st->header.signature!=KEFI_SYSTEM_SIGNATURE||
        st->header.bytes<sizeof(*st)||!st->configuration_table||!st->configuration_count||
        st->configuration_count>SIZE_MAX/sizeof(struct configuration)||
        st->configuration_count*sizeof(struct configuration)>UINTPTR_MAX-(uintptr_t)st->configuration_table)return 0;
    const struct configuration*t=st->configuration_table;struct kbl_dxe_services*d=0;
    for(size_t i=0;i<st->configuration_count;++i)if(guid_equal(&guid,&t[i].guid)){
        if(d||!t[i].table)return 0;d=t[i].table;
    }
    if(!d||((uintptr_t)d&7)||d->header.signature!=UINT64_C(0x565245535f455844)||
        d->header.bytes<sizeof(*d)||!d->get||!d->set)return 0;
    m->services=d;m->api=(struct kefi_memory_attributes){get,set,clear};return 1;
}
int kbl_dxe_memory_compat_init(struct kbl_dxe_memory*m,const struct kefi_system_table*st,
    struct kefi_memory_attributes*native,void*opaque,int(*owns)(void*,uint64_t,uint64_t)){
    if(!m||!st||!owns||!st->boot_services||m->api.get||m->services)return 0;
    if(native&&(!native->get||!native->set||!native->clear))return 0;
    (void)kbl_dxe_memory_init(m,st);
    m->api=(struct kefi_memory_attributes){get,set,clear};m->native=native;m->bs=st->boot_services;m->strict=1;
    kbl_arch_memory_native(&m->arch,opaque,owns);
    if(!native&&!kbl_arch_memory_profile(&m->arch)){
        *m=(struct kbl_dxe_memory){0};return 0;
    }
    return native||m->services||m->bs->locate_protocol;
}
