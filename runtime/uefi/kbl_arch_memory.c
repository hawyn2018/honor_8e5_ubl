/* SPDX-License-Identifier: MIT */
#include "kbl_arch_memory.h"
#define ADDRESS UINT64_C(0x0000fffffffff000)
#define MASK (KEFI_RP|KEFI_RO|KEFI_XP)
static int bad(struct kbl_arch_memory*m,const char*s){m->failure=s;return 0;}
static int range(uint64_t p,uint64_t n){return p&&n&&!(p&4095)&&!(n&4095)&&p<=UINT64_MAX-n;}
static int same(const struct kbl_arch_config*a,const struct kbl_arch_config*b){
    return a->sctlr==b->sctlr&&a->tcr==b->tcr&&a->ttbr0==b->ttbr0&&a->mair==b->mair&&
        a->mmfr3==b->mmfr3&&a->tcr2==b->tcr2&&a->mmfr1==b->mmfr1&&a->pan==b->pan&&a->el==b->el;
}
static int supported(struct kbl_arch_memory*m,const struct kbl_arch_config*c,uint64_t p){
    unsigned t0=(unsigned)(c->tcr&63),ips=(unsigned)((c->tcr>>32)&7),tcrx=(unsigned)(c->mmfr3&15),pan=(unsigned)((c->mmfr1>>20)&15);
    if(c->el!=1||!(c->sctlr&1)||(c->sctlr&(UINT64_C(1)<<25)))return bad(m,"el-or-mmu");
    if(t0<16||t0>39||ips>5||(c->tcr&(UINT64_C(3)<<14))||(c->tcr&(UINT64_C(1)<<7))||
       (c->tcr&(UINT64_C(1)<<37))||(c->tcr&(UINT64_C(3)<<39))||(c->tcr&(UINT64_C(1)<<59)))return bad(m,"tcr-profile");
    if(tcrx>1||(tcrx==1&&c->tcr2))return bad(m,"tcr2-profile");
    if(pan>3||(pan&&(c->pan&(UINT64_C(1)<<22))))return bad(m,"pan-profile");
    /* A short top-level table may have weaker alignment in the architecture;
     * this profile deliberately requires an actually aligned 4KiB root. */
    if((c->ttbr0&4094)||!(c->ttbr0&ADDRESS))return bad(m,"ttbr-profile");
    if(p>=(UINT64_C(1)<<(64-t0)))return bad(m,"va-profile");
    return 1;
}
static int walk(struct kbl_arch_memory*m,const struct kbl_arch_config*c,uint64_t p,struct kbl_arch_page*out){
    if(!supported(m,c,p))return 0;
    unsigned bits=64-(unsigned)(c->tcr&63),levels=(bits-12+8)/9,start=4-levels;
    uint64_t table=c->ttbr0&ADDRESS,forced_ro=0,forced_xp=0,ns=0;
    unsigned pa_bits=(unsigned[]){32,36,40,42,44,48}[(c->tcr>>32)&7];
    uint64_t pa_mask=(UINT64_C(1)<<pa_bits)-1;
    if(table&~pa_mask)return bad(m,"table-pa");
    for(unsigned level=start;level<4;++level){
        unsigned shift=12+(3-level)*9;
        uint64_t at=table+(((p>>shift)&511)*8),entry=0,again=0;
        if(at<table||!m->read_table(m->opaque,at,&entry)||!m->read_table(m->opaque,at,&again)||entry!=again)return bad(m,"table-read");
        unsigned kind=(unsigned)(entry&3);
        if(level<3&&kind==3){
            if(entry&UINT64_C(0x000f000000000000))return bad(m,"table-pa");
            table=entry&ADDRESS;if(!table||(table&~pa_mask))return bad(m,"table-pa");
            if(!(c->tcr&(UINT64_C(1)<<41))){forced_ro|=(entry>>62)&1;forced_xp|=(entry>>59)&1;}
            ns|=(entry>>63)&1;continue;
        }
        if((level==0)||(level==3?kind!=3:kind!=1))return bad(m,"descriptor-kind");
        if(entry&((UINT64_C(1)<<51)|(UINT64_C(1)<<52)|UINT64_C(0x0003000000000000)))return bad(m,"leaf-profile");
        uint64_t size=UINT64_C(1)<<shift,base=entry&ADDRESS;
        if((base&(size-1))||(base&~pa_mask))return bad(m,"leaf-pa");
        uint64_t physical=base+(p&(size-1));
        if(physical!=(p&~UINT64_C(4095)))return bad(m,"nonidentity-page");
        unsigned index=(unsigned)((entry>>2)&7),attr=(unsigned)((c->mair>>(index*8))&255);
        uint64_t cache=attr==0xff?8:attr==0xbb?4:attr==0x44?2:0;
        if(!cache)return bad(m,"non-normal-page");
        unsigned ro=(unsigned)((entry>>7)&1)|(unsigned)forced_ro;
        unsigned xp=(unsigned)((entry>>53)&1)|(unsigned)forced_xp;
        if((c->sctlr&(UINT64_C(1)<<19))&&!ro)xp=1;
        uint64_t attributes=(!(entry&(UINT64_C(1)<<10))?KEFI_RP:0)|(ro?KEFI_RO:0)|(xp?KEFI_XP:0);
        /* Include both effective NS and unchanged cache/shareability/index,
         * global and guarded/contiguous metadata. AP/AF/XN may change. */
        uint64_t signature=(entry&(UINT64_C(0x1c)|UINT64_C(0x300)|UINT64_C(0x800)|
            (UINT64_C(1)<<50)|(UINT64_C(1)<<52)))|((uint64_t)attr<<56)|((((entry>>5)&1)|ns)<<55);
        *out=(struct kbl_arch_page){physical,attributes,cache,signature,(unsigned)forced_ro,(unsigned)forced_xp,
            !!((c->sctlr&(UINT64_C(1)<<36))&&(entry&(UINT64_C(1)<<50)))};return 1;
    }
    return bad(m,"walk-level");
}
int kbl_arch_memory_profile(struct kbl_arch_memory*m){
    struct kbl_arch_config c={0};return m&&m->configuration&&m->configuration(m->opaque,&c)&&supported(m,&c,4096);
}
int kbl_arch_memory_page(struct kbl_arch_memory*m,uint64_t p,struct kbl_arch_page*out){
    if(!m||!out||!m->configuration||!m->read_table||!m->owns||!range(p,4096)||!m->owns(m->opaque,p,4096))return m?bad(m,"ownership"):0;
    struct kbl_arch_config before={0},after={0};
    if(!m->configuration(m->opaque,&before))return bad(m,"register-read");
    if(!walk(m,&before,p,out))return 0;
    if(!m->configuration(m->opaque,&after)||!same(&before,&after))return bad(m,"register-change");
    return 1;
}
int kbl_arch_memory_get(struct kbl_arch_memory*m,uint64_t p,uint64_t n,uint64_t*out){
    if(!m||!out||!range(p,n)||!m->owns||!m->owns(m->opaque,p,n))return m?bad(m,"ownership"):0;
    uint64_t first=0;for(uint64_t i=0;i<n;i+=4096){struct kbl_arch_page page;
        if(!kbl_arch_memory_page(m,p+i,&page))return 0;
        if(i&&page.attributes!=first)return bad(m,"mixed-permissions");first=page.attributes;
    }*out=first;return 1;
}
kefi_status kbl_arch_memory_change(struct kbl_arch_memory*m,uint64_t p,uint64_t n,uint64_t bits,int clear,
    void*cpu,kefi_status(*set)(void*,uint64_t,uint64_t,uint64_t)){
    if(!m||!set||!range(p,n)||(bits&~MASK)||!m->owns||!m->owns(m->opaque,p,n))return KEFI_INVALID;
    m->failure=0;
    if(m->tainted){bad(m,"tainted-mapping");return KEFI_ACCESS_DENIED;}
    if(!clear&&(bits&KEFI_RP)){bad(m,"rp-unsupported");return KEFI_UNSUPPORTED;}
    /* Full read-only preflight precedes the first CPU mutation. */
    for(uint64_t i=0;i<n;i+=4096){struct kbl_arch_page page;if(!kbl_arch_memory_page(m,p+i,&page))return KEFI_UNSUPPORTED;
        uint64_t next=clear?page.attributes&~bits:page.attributes|bits;
        if(!(next&(KEFI_RO|KEFI_XP|KEFI_RP))){bad(m,"rwx-request");return KEFI_ACCESS_DENIED;}
        if(page.bti&&!(next&KEFI_XP)){bad(m,"bti-page");return KEFI_UNSUPPORTED;}
        if((page.forced_ro&&!(next&KEFI_RO))||(page.forced_xp&&!(next&KEFI_XP))){bad(m,"hierarchy-restriction");return KEFI_UNSUPPORTED;}
    }
    unsigned attempted=0;
    for(uint64_t i=0;i<n;i+=4096){struct kbl_arch_page before,after;
        struct kbl_arch_config config_before={0},config_after={0},config_ready={0};
        if(!m->configuration(m->opaque,&config_before)){if(attempted)m->tainted=1;return KEFI_UNSUPPORTED;}
        if(!kbl_arch_memory_page(m,p+i,&before)){if(attempted)m->tainted=1;return KEFI_UNSUPPORTED;}
        uint64_t next=clear?before.attributes&~bits:before.attributes|bits;
        if(!(next&(KEFI_RO|KEFI_XP|KEFI_RP))){bad(m,"rwx-request");return KEFI_ACCESS_DENIED;}
        if(before.bti&&!(next&KEFI_XP)){bad(m,"bti-page");return KEFI_UNSUPPORTED;}
        if((before.forced_ro&&!(next&KEFI_RO))||(before.forced_xp&&!(next&KEFI_XP))){bad(m,"hierarchy-restriction");return KEFI_UNSUPPORTED;}
        m->last_address=p+i;m->last_before=before.cache|before.attributes;m->last_requested=before.cache|next;
        if(!m->configuration(m->opaque,&config_ready)||!same(&config_before,&config_ready)){if(attempted)m->tainted=1;bad(m,"cpu-register-change");return KEFI_UNSUPPORTED;}
        kefi_status status=KEFI_SUCCESS;
        if(next!=before.attributes){attempted=1;status=set(cpu,p+i,4096,before.cache|next);}
        if(!m->configuration(m->opaque,&config_after)||!same(&config_before,&config_after)){m->tainted=1;bad(m,"cpu-register-change");return KEFI_UNSUPPORTED;}
        if(!kbl_arch_memory_page(m,p+i,&after)){m->tainted=1;return KEFI_UNSUPPORTED;}
        if(after.physical!=before.physical||after.cache!=before.cache||after.signature!=before.signature){m->tainted=1;bad(m,"cpu-layout-change");return KEFI_ACCESS_DENIED;}
        if(status){bad(m,"cpu-set");return status;}
        if(after.attributes!=next){bad(m,"cpu-readback");return KEFI_ACCESS_DENIED;}
    }return KEFI_SUCCESS;
}
#if defined(__aarch64__) && !defined(KBL_DRIVER_TEST)
static int native_config(void*unused,struct kbl_arch_config*c){
    (void)unused;uint64_t el;
    __asm__ volatile("mrs %0, CurrentEL":"=r"(el));c->el=(unsigned)((el>>2)&3);if(c->el!=1)return 0;
    __asm__ volatile("mrs %0, sctlr_el1":"=r"(c->sctlr));
    __asm__ volatile("mrs %0, tcr_el1":"=r"(c->tcr));
    __asm__ volatile("mrs %0, ttbr0_el1":"=r"(c->ttbr0));
    __asm__ volatile("mrs %0, mair_el1":"=r"(c->mair));
    __asm__ volatile("mrs %0, S3_0_C0_C7_3":"=r"(c->mmfr3));
    __asm__ volatile("mrs %0, id_aa64mmfr1_el1":"=r"(c->mmfr1));
    c->tcr2=c->pan=0;
    if((c->mmfr3&15)==1)__asm__ volatile("mrs %0, S3_0_C2_C0_3":"=r"(c->tcr2));
    unsigned pan=(unsigned)((c->mmfr1>>20)&15);if(pan>3)return 0;
    if(pan)__asm__ volatile("mrs %0, S3_0_C4_C2_3":"=r"(c->pan));
    return 1;
}
static int native_read(void*unused,uint64_t p,uint64_t*out){
    (void)unused;if(!p||(p&7)||p>UINT64_C(0x0000fffffffffff8))return 0;
    uint64_t daif,oldpar,par,a=0,b=0;
    __asm__ volatile("mrs %0, daif\n\tmsr daifset, #15\n\tmrs %1, par_el1\n\tat s1e1r, %3\n\tisb\n\tmrs %2, par_el1"
        :"=&r"(daif),"=&r"(oldpar),"=&r"(par):"r"(p):"memory");
    unsigned attr=(unsigned)(par>>56);
    int ok=!(par&1)&&!(par&UINT64_C(0x000f000000000000))&&(par&ADDRESS)==(p&ADDRESS)&&(attr==0xff||attr==0xbb||attr==0x44);
    if(ok){a=*(volatile const uint64_t*)(uintptr_t)p;__asm__ volatile("dmb ishld":::"memory");b=*(volatile const uint64_t*)(uintptr_t)p;ok=a==b;}
    __asm__ volatile("msr par_el1, %0\n\tisb\n\tmsr daif, %1"::"r"(oldpar),"r"(daif):"memory");
    if(ok)*out=a;return ok;
}
#else
static int native_config(void*p,struct kbl_arch_config*c){(void)p;(void)c;return 0;}
static int native_read(void*p,uint64_t a,uint64_t*v){(void)p;(void)a;(void)v;return 0;}
#endif
void kbl_arch_memory_native(struct kbl_arch_memory*m,void*opaque,int(*owns)(void*,uint64_t,uint64_t)){
    *m=(struct kbl_arch_memory){opaque,native_config,native_read,owns,0,0,0,0,0};
}
