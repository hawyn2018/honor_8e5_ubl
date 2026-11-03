/* SPDX-License-Identifier: MIT */
#include "kbl_efi_types.h"
#include "kbl_service.h"
#include "kbl_partition.h"
#include "kbl_usb_native.h"
#include "kbl_adb_usb.h"
#include "kbl_arch_memory.h"
#include "kbl_dxe_memory.h"
#include "kbl_efi_run.h"
#include "kbl_display.h"
#include "../kbl_api.h"
#include "../kbl_fetch.h"
#include "../kbl_sha256.h"
#include "../native/kbl_native.h"
#include "../native/kbl_native_io.h"

#define REPLY_INLINE_LINES 24u
struct pool { struct pool *next,*previous;void *user;size_t bytes;unsigned released; };
struct pages { struct pages *next;uint64_t raw;size_t count;void *base;size_t bytes;unsigned released; };
struct exported { struct exported *next;uint64_t id;struct kbl_reader reader;size_t borrowers; };
struct kernel { void *native; };
static int service_request(struct kbl_service_protocol *,unsigned,const unsigned char *,size_t,const void *,size_t,
                           const struct kbl_firmware_link *,struct kbl_service_reply *);
static int can_unload(struct kbl_service_protocol *);
static int bind_usb(struct kbl_service_protocol *,const struct kbl_usb_parent_facts *);
static uint64_t usb_now(void*);
static int native_block_open(void*,const char*,unsigned,struct kbl_block*);
static int native_block_list(void*,struct kbl_block_info*,size_t,size_t*);
static int native_efi_run(void*,const void*,size_t,const uint16_t*,size_t,struct kbl_efi_run_result*);
static int native_efi_run_full(void*,const void*,size_t,const uint16_t*,size_t,struct kbl_efi_run_full_result*);
static int native_display(void*,unsigned,char*,size_t);
static int native_poweroff(void*);
static struct kbl_efi_launcher efi_launcher;
static kefi_status unload_image(kefi_handle);
static void adb_prepare_start(void);
static int live(void);
static int adb_stop_gate(void);
static int adb_native_stopped(void);
static int adb_transport_released(void);
static void adb_diagnostics(void);
static struct kbl_service_protocol protocol={KBL_SERVICE_MAGIC,KBL_SERVICE_ABI,sizeof(protocol),service_request,can_unload,bind_usb};
static const struct kefi_guid service_guid=KBL_SERVICE_GUID_VALUES,loaded_guid=KEFI_LOADED_GUID,attributes_guid=KEFI_ATTRIBUTES_GUID;
static struct {
    kefi_handle image;struct kefi_system_table *st;struct kefi_boot_services *bs;struct kefi_loaded_image *loaded;
    kefi_status (*previous_unload)(kefi_handle);
    unsigned installed,in_request,ownership_error,transport_hold;
    struct pool *pools;struct pages *pages;struct kefi_memory_attributes *attributes;struct kbl_dxe_memory dxe_memory;struct kbl_partition_provider partitions;
    struct kernel kernel;struct kbl_session session;struct kbl_native_platform platform;struct kbl_backend native_backend;
    struct kbl_display display;
    struct exported *exports;uint64_t next_export,reply_generation;
    char reply_inline[REPLY_INLINE_LINES][57];char (*reply_lines)[57];
    size_t reply_count,reply_capacity;unsigned reply_valid,reply_overflow;
    const struct kbl_firmware_link *link;struct kbl_service_reply *reply;
    void *pending_work;size_t pending_work_bytes;
    struct kbl_usb_parent_facts usb_facts;struct kbl_native_usb_binding usb;
    struct kbl_adb *adb;struct kbl_adb_usb adb_usb;struct pool *adb_pools;
    struct kbl_usbfn_protocol *usbfn;
    kefi_status (*usbfn_event)(struct kbl_usbfn_protocol *,uint32_t *,size_t *,void *);
    unsigned adb_in_idle,adb_call,adb_stop,adb_error,adb_router,adb_armed,adb_pending_init,adb_ack_ready,adb_native_stop;
    const char *adb_phase;
    uint64_t counter_frequency;unsigned current_operation;
    const char *elf_error_phase,*elf_error_step;unsigned elf_error_flags;
    kefi_status elf_error_status;
    struct {unsigned valid,caps_valid;const char *provider,*inner,*phase,*step;
        uint64_t caps,before,requested,flags,status;} elf_last;
} g;
static void zero(void *p,size_t n){unsigned char *d=p;while(n--)*d++=0;}
static void copy(void *d_,const void *s_,size_t n){unsigned char *d=d_;const unsigned char*s=s_;while(n--)*d++=*s++;}
static size_t length(const char*s){size_t n=0;while(s[n])++n;return n;}
static char *put(char*d,const char*s){while(*s)*d++=*s++;return d;}
static char *hex64(char*d,uint64_t n){const char*h="0123456789abcdef";for(int i=15;i>=0;--i)*d++=h[(n>>(i*4))&15];return d;}
static char *decimal(char*d,uint64_t n){char b[20];size_t at=20;do{b[--at]=(char)('0'+n%10);n/=10;}while(n);while(at<20)*d++=b[at++];return d;}
static unsigned hex(unsigned c){if(c>='0'&&c<='9')return c-'0';if(c>='a'&&c<='f')return c-'a'+10;if(c>='A'&&c<='F')return c-'A'+10;return 16;}
static int parse_id(const unsigned char*s,size_t n,uint64_t*out){uint64_t v=0;if(!n||n>16)return 0;for(size_t i=0;i<n;++i){unsigned h=hex(s[i]);if(h>15)return 0;v=v*16+h;}*out=v;return 1;}
static unsigned current_el(void *p){(void)p;
#if defined(KBL_DRIVER_TEST)
    extern unsigned kbl_driver_test_current_el(void);return kbl_driver_test_current_el();
#elif defined(__aarch64__)
    uint64_t el;__asm__ volatile("mrs %0, CurrentEL":"=r"(el));return(unsigned)((el>>2)&3);
#else
    return 0;
#endif
}
static int free_pool_node(struct pool*p){
    struct pool *next=p->next,*previous=p->previous;
    if(!g.bs->free_pool||g.bs->free_pool(p)!=KEFI_SUCCESS)return 0;
    if(previous)previous->next=next;else g.pools=next;if(next)next->previous=previous;return 1;
}
static void *pool_allocate(void*unused,size_t bytes,size_t alignment){
    (void)unused;if(!g.bs||!g.bs->allocate_pool||!alignment||(alignment&(alignment-1))||
       alignment-1>SIZE_MAX-sizeof(struct pool)||bytes>SIZE_MAX-sizeof(struct pool)-(alignment-1))return 0;
    void *raw=0;size_t total=sizeof(struct pool)+bytes+alignment-1;
    if(g.bs->allocate_pool(4,total,&raw)!=KEFI_SUCCESS||!raw)return 0;
    struct pool*p=raw;zero(p,sizeof(*p));p->bytes=bytes;
    p->user=(void*)(((uintptr_t)(p+1)+alignment-1)&~(uintptr_t)(alignment-1));
    p->next=g.pools;if(g.pools)g.pools->previous=p;g.pools=p;return p->user;
}
static void pool_release(void*unused,void*memory,size_t bytes){
    (void)unused;if(!memory)return;
    for(struct pool*p=g.pools;p;p=p->next)if(p->user==memory){
        if(p->released||p->bytes!=bytes){g.ownership_error=1;return;}p->released=1;(void)free_pool_node(p);return;
    }
    g.ownership_error=1;
}
static void retry_pools(void){struct pool*p=g.pools;while(p){struct pool*next=p->next;if(p->released)(void)free_pool_node(p);p=next;}}
static int private_pages(void*unused,uint64_t base,uint64_t bytes){
    (void)unused;if(!bytes||base>UINT64_MAX-bytes)return 0;
    for(struct pages*p=g.pages;p;p=p->next)if(base>=p->raw&&base-p->raw<=p->count*KEFI_PAGE&&bytes<=p->count*KEFI_PAGE-(base-p->raw))return 1;
    return 0;
}
static int attributes_ready(void){
    if(g.attributes)return 1;
    if(!g.bs)return 0;
    struct kefi_memory_attributes *native=0;
    if(g.bs->locate_protocol){
        void *p=0;kefi_status status=g.bs->locate_protocol(&attributes_guid,0,&p);
        if(status==KEFI_SUCCESS){
            struct kefi_memory_attributes *a=p;if(!a||!a->get||!a->set||!a->clear)return 0;
            native=a;
        }
    }
    /* Explicit policy denial is retained; only UNSUPPORTED may use the
     * standard CPU provider plus an actual architectural permission getter. */
    if(!kbl_dxe_memory_compat_init(&g.dxe_memory,g.st,native,0,private_pages))return 0;
    g.attributes=&g.dxe_memory.api;return 1;
}
static void permission_error(const char*phase,const char*step,unsigned flags,kefi_status status){
    /* One private diagnostic per service request, collected only by an
     * explicitly requested ELF operation. No protocol or hardware probing. */
    if(g.elf_error_phase)return;
    g.elf_error_phase=phase;g.elf_error_step=step;g.elf_error_flags=flags;g.elf_error_status=status;
    g.elf_last.valid=1;g.elf_last.phase=phase;g.elf_last.step=step;g.elf_last.flags=flags;g.elf_last.status=status;
    g.elf_last.provider=g.dxe_memory.provider;g.elf_last.inner=g.dxe_memory.inner_phase;
    g.elf_last.caps_valid=g.dxe_memory.diagnostic_caps_valid;g.elf_last.caps=g.dxe_memory.diagnostic_caps;
    g.elf_last.before=g.dxe_memory.diagnostic_before;g.elf_last.requested=g.dxe_memory.diagnostic_requested;
}
static int set_access(uint64_t base,size_t bytes,unsigned flags,const char*phase){
    if(phase[0]=='a')g.elf_last.valid=0; /* A new allocation starts a fresh diagnostic. */
    if(!g.attributes||!bytes||(flags&~7u)||((flags&6)==6))return KBL_ERROR;
    uint64_t wanted=((flags&KBL_ELF_R)?0:KEFI_RP)|((flags&KBL_ELF_W)?0:KEFI_RO)|((flags&KBL_ELF_X)?0:KEFI_XP);
    uint64_t all=KEFI_RP|KEFI_RO|KEFI_XP,actual=0;
    /* Deny first: W is revoked before X is enabled; X before W. */
    kefi_status status;
    if(wanted&&(status=g.attributes->set(g.attributes,base,bytes,wanted))!=KEFI_SUCCESS){permission_error(phase,"set",flags,status);return KBL_ERROR;}
    if((all&~wanted)&&(status=g.attributes->clear(g.attributes,base,bytes,all&~wanted))!=KEFI_SUCCESS){permission_error(phase,"clear",flags,status);return KBL_ERROR;}
    status=g.attributes->get(g.attributes,base,bytes,&actual);
    if(status!=KEFI_SUCCESS){permission_error(phase,"get",flags,status);return KBL_ERROR;}
    if((actual&all)!=wanted){permission_error(phase,"verify",flags,status);return KBL_ERROR;}
    return KBL_OK;
}
static int free_page_node(struct pages*p){
    /* Never return RP/RO pages to the firmware allocator. A failed/partial
     * restoration stays in the released ledger until an explicit retry has
     * restored and read back private RW/NX permissions successfully. */
    if(g.dxe_memory.arch.tainted||set_access(p->raw,p->count*KEFI_PAGE,KBL_ELF_R|KBL_ELF_W,"restore")!=KBL_OK)return 0;
    if(!g.bs->free_pages||g.bs->free_pages(p->raw,p->count)!=KEFI_SUCCESS)return 0;
    struct pages **at=&g.pages;while(*at&&*at!=p)at=&(*at)->next;if(*at)*at=p->next;
    pool_release(0,p,sizeof(*p));return 1;
}
static void retry_pages(void){struct pages*p=g.pages;while(p){struct pages*next=p->next;if(p->released)(void)free_page_node(p);p=next;}}
static int elf_allocate(void*unused,size_t bytes,size_t alignment,struct kbl_elf_allocation*out){
    (void)unused;if(!out)return KBL_ERROR;out->base=0;out->bytes=0;
    if(g.dxe_memory.arch.tainted)return KBL_ERROR;
    if(!g.bs||!g.bs->allocate_pages||!g.bs->free_pages||!attributes_ready() ||
       !bytes||(bytes&(KEFI_PAGE-1))||!alignment||
       (alignment&(alignment-1))||alignment<KEFI_PAGE||
       alignment-1>SIZE_MAX-(KEFI_PAGE-1)||bytes>SIZE_MAX-(alignment-1)-(KEFI_PAGE-1))return KBL_ERROR;
    size_t count=(bytes+alignment-1+KEFI_PAGE-1)/KEFI_PAGE;
    struct pages*p=pool_allocate(0,sizeof(*p),16);if(!p)return KBL_ERROR;zero(p,sizeof(*p));
    p->count=count;
    kefi_status allocated=g.bs->allocate_pages(0,2,p->count,&p->raw);
    if(allocated||!p->raw){
        if(p->raw){p->next=g.pages;g.pages=p;p->released=1;(void)free_page_node(p);}
        else pool_release(0,p,sizeof(*p));
        return KBL_ERROR;
    }
    p->next=g.pages;g.pages=p;p->base=(void*)((p->raw+alignment-1)&~(uint64_t)(alignment-1));p->bytes=bytes;
    if((p->raw&(KEFI_PAGE-1))||p->raw>UINT64_MAX-p->count*KEFI_PAGE || (uintptr_t)p->base<p->raw ||
       (uint64_t)(uintptr_t)p->base-p->raw>p->count*KEFI_PAGE || bytes>p->count*KEFI_PAGE-((uint64_t)(uintptr_t)p->base-p->raw) ||
       set_access(p->raw,p->count*KEFI_PAGE,KBL_ELF_R|KBL_ELF_W,"allocate")!=KBL_OK){p->released=1;(void)free_page_node(p);return KBL_ERROR;}
    out->base=p->base;out->bytes=bytes;return KBL_OK;
}
static struct pages *find_pages(struct kbl_elf_allocation*m){for(struct pages*p=g.pages;p;p=p->next)if(p->base==m->base&&p->bytes==m->bytes&&!p->released)return p;return 0;}
static void elf_release(void*unused,struct kbl_elf_allocation*m){
    (void)unused;struct pages*p=find_pages(m);if(!p){g.ownership_error=1;return;}p->released=1;(void)free_page_node(p);m->base=0;m->bytes=0;
}
static int elf_protect(void*unused,struct kbl_elf_allocation*m,size_t off,size_t bytes,unsigned flags){
    (void)unused;if(!find_pages(m)||off>m->bytes||bytes>m->bytes-off||(off&(KEFI_PAGE-1))||(bytes&(KEFI_PAGE-1)))return KBL_ERROR;
    return set_access((uint64_t)(uintptr_t)m->base+off,bytes,flags,flags?"segment":"hole");
}
static int elf_icache(void*unused,struct kbl_elf_allocation*m,size_t off,size_t bytes){
    (void)unused;if(!find_pages(m)||off>m->bytes||bytes>m->bytes-off)return KBL_ERROR;
#if defined(KBL_DRIVER_TEST)
    return KBL_OK;
#elif defined(__aarch64__)
    uint64_t ctr;__asm__ volatile("mrs %0, ctr_el0":"=r"(ctr));
    uintptr_t begin=(uintptr_t)m->base+off,end=begin+bytes;size_t d=4u<<((ctr>>16)&15),i=4u<<(ctr&15);
    for(uintptr_t at=begin&~(uintptr_t)(d-1);at<end;at+=d)__asm__ volatile("dc cvau, %0"::"r"(at):"memory");
    __asm__ volatile("dsb ish":::"memory");
    for(uintptr_t at=begin&~(uintptr_t)(i-1);at<end;at+=i)__asm__ volatile("ic ivau, %0"::"r"(at):"memory");
    __asm__ volatile("dsb ish\n\tisb":::"memory");return KBL_OK;
#else
    return KBL_ERROR;
#endif
}
static int close_exports(void){
    while(g.exports){
        struct exported *e=g.exports;if(e->borrowers)return KBL_AGAIN;
        e->reader.close(e->reader.opaque);g.exports=e->next;pool_release(0,e,sizeof(*e));
    }
    return KBL_OK;
}
static int wrapper_start(void*opaque,void**context){
    (void)opaque;*context=0;
    if(!kbl_efi_can_unload(&efi_launcher))return KBL_ERROR;
    kbl_efi_launcher_init(&efi_launcher,g.image,g.st,&g,current_el,current_el(&g));
    zero(&g.platform,sizeof(g.platform));
    g.platform.opaque=&g;g.platform.allocate=pool_allocate;g.platform.release=pool_release;g.platform.current_el=current_el;
    g.platform.now_us=g.counter_frequency?usb_now:0;
    g.platform.stack_bytes=256u*1024u; /* Explicit START: firmware/returning-EFI call stack. */
    g.platform.block_open=native_block_open;g.platform.block_list=native_block_list;
    g.platform.efi_run=native_efi_run;
    g.platform.efi_run_full=native_efi_run_full;
    g.platform.display=native_display;g.platform.poweroff=native_poweroff;
    g.platform.elf=(struct kbl_elf_host){0,KEFI_PAGE,0,elf_allocate,elf_release,elf_protect,elf_icache};
    /* Raw ST capability is deliberately absent: arbitrary external callbacks
     * cannot be reclaimed by this driver's allocation/cleanup broker. */
    if(kbl_native_backend(&g.native_backend,&g.platform)!=KBL_OK)return KBL_ERROR;
    int rc=g.native_backend.start(g.native_backend.opaque,&g.kernel.native);
    if(g.kernel.native||g.pools||g.pages)*context=&g.kernel;
    return rc;
}
static int wrapper_submit(void*context,const unsigned char*line,size_t n,uint64_t*job){
    if(context!=&g.kernel||!g.kernel.native)return KBL_ERROR;return g.native_backend.submit(g.kernel.native,line,n,job);
}
static int wrapper_poll(void*context,uint64_t job,unsigned budget,unsigned char*out,size_t cap,size_t*written,int*done,int*rc){
    if(context!=&g.kernel||!g.kernel.native)return KBL_ERROR;return g.native_backend.poll(g.kernel.native,job,budget,out,cap,written,done,rc);
}
static int wrapper_stop(void*context){
    if(context!=&g.kernel)return KBL_ERROR;
    if(kbl_efi_cleanup(&efi_launcher))return KBL_AGAIN;
    if(adb_stop_gate()!=KBL_OK)return KBL_AGAIN;
    if(adb_transport_released()&&g.usb.initialized&&g.usb.attached)(void)kbl_native_usb_stop_observing(&g.usb);
    if(close_exports()!=KBL_OK)return KBL_AGAIN;
    if(g.kernel.native){int rc=g.native_backend.stop(g.kernel.native);if(rc!=KBL_OK)return rc;g.kernel.native=0;}
    if(g.partitions.initialized)(void)kbl_partition_cleanup(&g.partitions);
    retry_pages();retry_pools();
    if(g.pools||g.pages||g.pending_work||g.transport_hold||g.ownership_error||
       (g.partitions.initialized&&!kbl_partition_can_unload(&g.partitions)))return KBL_AGAIN;
    /* The retained ADB transport owns separate pools. Only attest native STOP
     * after every native file/ELF/page/pool has actually been released. */
    int rc=adb_native_stopped();if(rc==KBL_OK)g.adb_native_stop=0;return rc;
}
static void *controller_allocate(void*opaque,size_t bytes){(void)opaque;return pool_allocate(0,bytes,16);}
static void controller_release(void*opaque,void*memory,size_t bytes){(void)opaque;pool_release(0,memory,bytes);}
static const struct kbl_backend backend={0,current_el,wrapper_start,wrapper_submit,wrapper_poll,wrapper_stop,controller_allocate,controller_release};

static int has_tail(const struct kbl_firmware_link*l,size_t end){return l&&l->bytes>=end;}
static int quiet(const struct kbl_firmware_link*l){return (!g.usb.initialized||g.usb.link.quiescent(&g.usb)==1)&&
    has_tail(l,offsetof(struct kbl_firmware_link,quiescent)+sizeof(l->quiescent))&&l->quiescent&&l->quiescent(l->opaque)==1;}
static void io_failure(void){g.transport_hold=1;if(g.reply)g.reply->transport_error=1;}
static void clear_reply(void){
    if(g.reply_lines)pool_release(0,g.reply_lines,g.reply_capacity*57);
    g.reply_lines=0;g.reply_capacity=g.reply_count=0;g.reply_valid=g.reply_overflow=0;
}
static int reserve_reply(void){
    size_t capacity=g.reply_lines?g.reply_capacity:REPLY_INLINE_LINES;
    if(g.reply_count<capacity)return 1;
    size_t maximum=SIZE_MAX/57;if(capacity==maximum)return 0;
    capacity=capacity<=maximum/2?capacity*2:maximum;
    char (*lines)[57]=pool_allocate(0,capacity*57,16);if(!lines)return 0;
    copy(lines,g.reply_lines?(void*)g.reply_lines:(void*)g.reply_inline,g.reply_count*57);
    if(g.reply_lines)pool_release(0,g.reply_lines,g.reply_capacity*57);
    g.reply_lines=lines;g.reply_capacity=capacity;return 1;
}
static void output_info(void*unused,const char*s){
    (void)unused;size_t n=length(s);
    if(n>56||!reserve_reply()){g.reply_overflow=1;g.reply_valid=0;return;}
    copy(g.reply_lines?g.reply_lines[g.reply_count++]:g.reply_inline[g.reply_count++],s,n+1);
    uint64_t flags=has_tail(g.link,offsetof(struct kbl_firmware_link,flags)+sizeof(g.link->flags))?g.link->flags:0;
    if(!(flags&KBL_LINK_DEFER_INFO)&&(!g.link->info||g.link->info(g.link->opaque,s)))io_failure();
}
static void output_final(void*unused,int okay,const char*s){
    (void)unused;
    if(g.reply->response_sent){io_failure();return;}
    /* Only the explicit ADB operation arms the exact final-ACK fence.
     * START, attach, status and ordinary Fastboot never opt into ADB. */
    if(g.current_operation==KBL_S_ADB&&okay&&live())adb_prepare_start();
    if(g.current_operation==KBL_S_START||g.current_operation==KBL_S_ADB||g.current_operation==KBL_S_STATUS||g.current_operation==KBL_S_OFF){
        char line[57],*p=put(line,"KBL1 adb=");p=put(p,g.adb_phase?g.adb_phase:"disabled");*p=0;output_info(0,line);
        if(g.adb_usb.composite_started)output_info(0,"KBL1 usb_transport=resident");
    }
    if(g.current_operation==KBL_S_STATUS)adb_diagnostics();
    if(g.elf_error_phase){
        char line[57],*p=put(line,"KBL1 elf_phase=");p=put(p,g.elf_error_phase);*p++='.';p=put(p,g.elf_error_step);*p=0;output_info(0,line);
        p=put(line,"KBL1 elf_flags=");p=hex64(p,g.elf_error_flags);*p=0;output_info(0,line);
        p=put(line,"KBL1 elf_fw=");p=hex64(p,g.elf_error_status);*p=0;output_info(0,line);
        g.elf_error_phase=g.elf_error_step=0;
    }
    /* Extended diagnostics are queried through status, rather than appended
     * to a potentially full poll (12 output lines + done + 3 failure lines).
     * Success records only the selected provider, without a stale error. */
    if(g.current_operation==KBL_S_STATUS&&g.dxe_memory.provider){
        char line[57],*p=put(line,"KBL1 elf_provider=");p=put(p,g.elf_last.valid&&g.elf_last.provider?g.elf_last.provider:g.dxe_memory.provider);*p=0;output_info(0,line);
        if(g.elf_last.valid){
            p=put(line,"KBL1 elf_inner=");p=put(p,g.elf_last.inner?g.elf_last.inner:"unspecified");*p=0;output_info(0,line);
            p=put(line,"KBL1 elf_caps=");if(g.elf_last.caps_valid)p=hex64(p,g.elf_last.caps);else p=put(p,"unavailable");*p=0;output_info(0,line);
            p=put(line,"KBL1 elf_before=");p=hex64(p,g.elf_last.before);*p=0;output_info(0,line);
            p=put(line,"KBL1 elf_req=");p=hex64(p,g.elf_last.requested);*p=0;output_info(0,line);
            p=put(line,"KBL1 elf_fw=");p=hex64(p,g.elf_last.status);*p=0;output_info(0,line);
        }
    }
    if(g.reply_overflow){okay=0;s="KBL reply cache overflow";}
    if(g.reply->transport_error){okay=0;s="KBL transport failed";}
    g.reply->response_sent=1; /* Attempted: never ask the parent to queue a second final after uncertain I/O. */
    if(g.link->final(g.link->opaque,okay,s))io_failure();
}
static const struct kbl_output output={0,output_info,output_final};
static int live(void){return(g.session.phase==KBL_RUNNING||g.session.phase==KBL_BUSY)&&g.kernel.native;}
static int native_display(void*opaque,unsigned op,char*reply,size_t capacity){
    if(opaque!=&g||!live())return KBL_ERROR;
    kefi_status rc=kbl_display_request(&g.display,g.st,op);
    if(!kbl_display_describe(&g.display,reply,capacity))return KBL_ERROR;
    return rc==KEFI_SUCCESS?KBL_OK:KBL_ERROR;
}
static int native_poweroff(void*opaque){
    if(opaque!=&g||!live())return KBL_ERROR;
    (void)kbl_power_shutdown(g.st);return KBL_ERROR;
}
static int native_storage_request(void){return live()&&((g.in_request&&(g.current_operation==KBL_S_POLL||
    g.current_operation==KBL_S_READ_OPEN||g.current_operation==KBL_S_FETCH))||g.adb_call);}
/* Only the native executor's explicit efi command reaches this provider.
 * It is a synchronous exclusive transaction: keep USB configuration intact,
 * do not yield/reenter its poller, and resume the original pump on return. */
static int native_efi_run(void*opaque,const void*image,size_t bytes,
                          const uint16_t*options,size_t option_bytes,
                          struct kbl_efi_run_result*result){
    if(opaque!=&g||!live()||!((g.in_request&&g.current_operation==KBL_S_POLL)||g.adb_call))return-KBL_EACCES;
    if(g.transport_hold||g.pending_work||efi_launcher.active)return-KBL_EBUSY;
    return kbl_efi_run(&efi_launcher,image,bytes,options,option_bytes,result);
}
static int native_efi_run_full(void*opaque,const void*image,size_t bytes,
                          const uint16_t*options,size_t option_bytes,
                          struct kbl_efi_run_full_result*result){
    if(opaque!=&g||!live()||!((g.in_request&&g.current_operation==KBL_S_POLL)||g.adb_call))return-KBL_EACCES;
    if(g.transport_hold||g.pending_work||efi_launcher.active)return-KBL_EBUSY;
    return kbl_efi_run_full(&efi_launcher,image,bytes,options,option_bytes,result);
}
static int storage_ready(void){return g.partitions.initialized||kbl_partition_init(&g.partitions,g.bs,0,pool_allocate,pool_release)==KBL_OK;}
static int native_block_open(void*opaque,const char*label,unsigned access,struct kbl_block*out){
    if(opaque!=&g||!native_storage_request()||!storage_ready())return KBL_ERROR;
    return kbl_partition_block_open(&g.partitions,label,access,out);
}
static int native_block_list(void*opaque,struct kbl_block_info*out,size_t cap,size_t*count){
    if(count)*count=0;
    if(opaque!=&g||!native_storage_request()||!storage_ready())return KBL_ERROR;
    return kbl_partition_list(&g.partitions,out,cap,count);
}
static int utf8(const unsigned char*p,size_t n){
    size_t i=0;while(i<n){unsigned c=p[i++];if(!c)return 0;if(c<128)continue;unsigned remain;uint32_t code;
        if(c>=0xc2&&c<=0xdf){remain=1;code=c&31;}else if(c>=0xe0&&c<=0xef){remain=2;code=c&15;}else if(c>=0xf0&&c<=0xf4){remain=3;code=c&7;}else return 0;
        unsigned count=remain;if(n-i<remain)return 0;while(remain--){unsigned x=p[i++];if((x&0xc0)!=0x80)return 0;code=(code<<6)|(x&63);}
        if((count==2&&code<0x800)||(count==3&&code<0x10000)||code>0x10ffff||(code>=0xd800&&code<=0xdfff))return 0;
    }return 1;
}
static int path_decode(const unsigned char*a,size_t n,char **out){
    if(!out)return 0;*out=0;if(!a||!n||(n&1)||n/2==SIZE_MAX)return 0;
    for(size_t i=0;i<n;i+=2)if(hex(a[i])>15||hex(a[i+1])>15)return 0;
    char *path=pool_allocate(0,n/2+1,16);if(!path)return 0;
    for(size_t i=0;i<n;i+=2)path[i/2]=(char)(hex(a[i])*16+hex(a[i+1]));
    if(!utf8((const unsigned char*)path,n/2)){pool_release(0,path,n/2+1);return 0;}
    path[n/2]=0;*out=path;return 1;
}
static void path_release(char *path){if(path)pool_release(0,path,length(path)+1);}
static struct exported *exported_id(const unsigned char*a,size_t n){
    uint64_t id;if(n<5||a[0]!='k'||a[1]!='b'||a[2]!='l'||a[3]!='.'||!parse_id(a+4,n-4,&id)||!id)return 0;
    for(struct exported *e=g.exports;e;e=e->next)if(e->id==id)return e;return 0;
}
static void export_release(struct exported *e){
    struct exported **at=&g.exports;while(*at&&*at!=e)at=&(*at)->next;
    if(*at){*at=e->next;e->reader.close(e->reader.opaque);pool_release(0,e,sizeof(*e));}
}

/* Shared, independently vector-tested SHA-256; no fixture digest. */
static int byte_hash(const void *data,size_t bytes,unsigned char digest[32]){
    struct kbl_sha256 s;kbl_sha256_init(&s);
    return kbl_sha256_update(&s,data,bytes)==KBL_OK?kbl_sha256_final(&s,digest):KBL_ERROR;
}
static int snapshot_hash(struct kbl_reader*r,char text[65]){
    unsigned char scratch[1024],digest[32];
    int rc=kbl_sha256_reader(r,scratch,sizeof(scratch),digest);
    if(rc==KBL_OK)kbl_sha256_hex(digest,text);return rc;
}
static uint64_t little(const unsigned char*p,unsigned n){uint64_t v=0;for(unsigned i=0;i<n;++i)v|=(uint64_t)p[i]<<(i*8);return v;}
static int store_payload(const unsigned char*a,size_t n,const void*download,size_t download_n,char **path,
                         const void**payload,size_t*payload_n,char digest[65]){
    *path=0;
    if(download_n&&!download)return 0;
    unsigned char hash[32];
    if(n==21&&a&&a[0]=='e'&&a[1]=='n'&&a[2]=='v'&&a[3]=='1'&&a[4]==':'){
        uint64_t nonce;const unsigned char*b=download;
        if(!parse_id(a+5,16,&nonce)||download_n<64||!b)return 0;
        static const unsigned char magic[8]={'K','B','L','P','U','T','0','1'};
        for(unsigned i=0;i<8;++i)if(b[i]!=magic[i])return 0;
        uint64_t pn=little(b+16,4),dn=little(b+24,8);
        if(little(b+8,8)!=nonce||little(b+20,4)||!pn||pn>download_n-64||pn>=SIZE_MAX||dn!=download_n-64-pn)return 0;
        if(!utf8(b+64,(size_t)pn))return 0;
        *payload=b+64+pn;*payload_n=(size_t)dn;
        if(byte_hash(*payload,*payload_n,hash)!=KBL_OK)return 0;
        unsigned different=0;for(unsigned i=0;i<32;++i)different|=hash[i]^b[32+i];if(different)return 0;
        *path=pool_allocate(0,(size_t)pn+1,16);if(!*path)return 0;
        copy(*path,b+64,(size_t)pn);(*path)[pn]=0;
    }else{
        if(byte_hash(download,download_n,hash)!=KBL_OK||!path_decode(a,n,path))return 0;
        *payload=download;*payload_n=download_n;
    }
    kbl_sha256_hex(hash,digest);return 1;
}
static int read_path(const unsigned char*a,size_t n,const void*download,size_t download_n,char **path){
    if(n==21&&a&&a[0]=='e'&&a[1]=='n'&&a[2]=='v'&&a[3]=='1'&&a[4]==':'){
        const void*payload=0;size_t payload_n=0;char digest[65];
        if(!store_payload(a,n,download,download_n,path,&payload,&payload_n,digest))return 0;
        if(payload_n){path_release(*path);*path=0;return 0;}return 1;
    }
    return path_decode(a,n,path);
}
static int borrowed_read(void*p,uint64_t off,void*buf,size_t n,size_t*read){struct exported*e=p;return e->id&&e->borrowers?e->reader.read_at(e->reader.opaque,off,buf,n,read):KBL_ERROR;}
static void borrowed_close(void*p){struct exported*e=p;if(e->borrowers)--e->borrowers;else g.ownership_error=1;}
static int fetch_open(void*unused,const char*name,struct kbl_reader*out){
    (void)unused;struct exported*e=exported_id((const unsigned char*)name,length(name));
    if(!e){
        if(name[0]=='k'&&name[1]=='b'&&name[2]=='l'&&name[3]=='.')return KBL_ERROR;
        if(!live())return KBL_ERROR;
        if(!g.partitions.initialized&&kbl_partition_init(&g.partitions,g.bs,0,pool_allocate,pool_release)!=KBL_OK)return KBL_ERROR;
        return kbl_partition_open(&g.partitions,name,out);
    }
    if(e->borrowers==SIZE_MAX)return KBL_ERROR;
    ++e->borrowers;*out=(struct kbl_reader){e,e->reader.size,borrowed_read,borrowed_close};return KBL_OK;
}
static int fetch_data(void*unused,uint32_t n){(void)unused;if(!g.link->data||g.link->data(g.link->opaque,n)){io_failure();return KBL_ERROR;}return KBL_OK;}
static int fetch_write(void*unused,const void*p,size_t n,size_t*written){(void)unused;if(!g.link->write||g.link->write(g.link->opaque,p,n,written)){io_failure();return KBL_ERROR;}return KBL_OK;}
static void fetch_abort(void*unused){(void)unused;io_failure();if(g.link->abort)g.link->abort(g.link->opaque);}
static int stopped(void){return kbl_efi_can_unload(&efi_launcher)&&g.session.phase==KBL_STOPPED&&!g.kernel.native&&!g.pools&&!g.pages&&!g.pending_work&&!g.transport_hold&&!g.ownership_error&&
    !g.adb&&!g.adb_pools&&adb_transport_released()&&
    (!g.usb.initialized||kbl_native_usb_can_release(&g.usb))&&
    (!g.partitions.initialized||kbl_partition_can_unload(&g.partitions));}
static int can_unload(struct kbl_service_protocol*p){return p==&protocol&&g.installed&&!g.in_request&&stopped()&&!g.exports;}
static void cache_info(const char*key,const char*value){char line[57],*p=put(line,"KBL1 ");p=put(p,key);*p++='=';p=put(p,value);*p=0;output_info(0,line);}
static int service_request(struct kbl_service_protocol*p,unsigned op,const unsigned char*a,size_t n,const void*download,size_t download_n,
                           const struct kbl_firmware_link*l,struct kbl_service_reply*r){
    if(p!=&protocol||!g.installed||g.in_request||!l||l->abi!=KBL_SERVICE_ABI||l->bytes<offsetof(struct kbl_firmware_link,final)+sizeof(l->final)||!l->final||!r||r->bytes<sizeof(*r))return KBL_S_ERROR;
    r->stopped=r->response_sent=r->transport_error=0;g.in_request=1;g.current_operation=op;g.link=l;g.reply=r;
    g.elf_error_phase=g.elf_error_step=0;g.elf_error_flags=0;g.elf_error_status=0;
    if(op==KBL_S_REPLY_HEADER||op==KBL_S_REPLY_PAGE){
        char out[57],*t=out;int ok=0;
        if(op==KBL_S_REPLY_HEADER&&!n&&g.reply_valid){t=put(t,"KBLC1 g=");t=hex64(t,g.reply_generation);t=put(t," n=");t=decimal(t,g.reply_count);*t=0;ok=1;}
        else if(op==KBL_S_REPLY_PAGE&&a&&n>=18&&a[16]==':'&&g.reply_valid){uint64_t gen;size_t index=0;ok=parse_id(a,16,&gen)&&gen==g.reply_generation;
            for(size_t i=17;i<n&&ok;++i){unsigned digit=a[i]-'0';if(digit>9||index>(SIZE_MAX-digit)/10)ok=0;else index=index*10+digit;}
            if(index>=g.reply_count)ok=0;if(ok)copy(out,g.reply_lines?g.reply_lines[index]:g.reply_inline[index],57);
        }
        if(!ok)copy(out,"KBL reply unavailable",22);
        r->response_sent=1;if(l->final(l->opaque,ok,out)){io_failure();}
    }else{
        if(g.transport_hold&&quiet(l)){g.transport_hold=0;if(g.pending_work){pool_release(0,g.pending_work,g.pending_work_bytes);g.pending_work=0;g.pending_work_bytes=0;}}
        clear_reply();++g.reply_generation;if(!g.reply_generation)++g.reply_generation;g.reply_valid=1;
        if(op==KBL_S_SCREEN_OFF||op==KBL_S_SCREEN_ON||op==KBL_S_SCREEN_STATUS){
            if(n)output_final(0,0,"Unexpected display arguments");
            else{
                unsigned action=op==KBL_S_SCREEN_OFF?KBL_DISPLAY_OFF:op==KBL_S_SCREEN_ON?KBL_DISPLAY_ON:KBL_DISPLAY_STATUS;
                kefi_status rc=kbl_display_request(&g.display,g.st,action);char report[1024];
                if(!kbl_display_describe(&g.display,report,sizeof(report)))output_final(0,0,"Display reply unavailable");
                else{
                    char line[57];size_t at=0;unsigned failed=0;
                    for(const char*s=report;*s;++s){
                        if(*s=='\n'){line[at]=0;output_info(0,line);at=0;}
                        else if(at<56)line[at++]=*s;
                        else{failed=1;break;}
                    }
                    if(at){line[at]=0;output_info(0,line);}
                    output_final(0,!failed&&rc==KEFI_SUCCESS,failed?"Display reply overflow":rc!=KEFI_SUCCESS?"Display protocol operation failed":
                        g.display.state==1?"display=off":"display=on");
                }
            }
        }else if((op>=KBL_S_START&&op<=KBL_S_POLL)||op==KBL_S_ADB){
            static const char*names[]={"oem kblstart","oem kblin","oem kbloff","oem kblstatus","oem kblcmd","oem kblput","oem kblpoll"};
            const char *name=op==KBL_S_ADB?"oem kbladb":names[op-1];
            char request[KBL_WIRE_BYTES+1];size_t z=length(name);
            if(op==KBL_S_OFF&&g.session.phase==KBL_STOPPED){
                (void)close_exports();if(g.partitions.initialized)(void)kbl_partition_cleanup(&g.partitions);retry_pages();retry_pools();
            }
            if(op==KBL_S_OFF&&g.session.phase==KBL_STOPPED&&(g.kernel.native||g.pools||g.pages||g.pending_work||g.transport_hold||g.ownership_error))output_final(0,0,"KBL driver cleanup pending");
            else if((n&&!a)||n>KBL_WIRE_BYTES-z-(n?1:0))output_final(0,0,"KBL argument too long");
            else{copy(request,name,z);if(n){request[z++]=' ';copy(request+z,a,n);z+=n;}request[z]=0;if(!kbl_handle(&g.session,&backend,(unsigned char*)request,z,&output))output_final(0,0,"KBL request rejected");}
        }else if(op==KBL_S_STORE||op==KBL_S_READ_OPEN){
            char *path=0;if(!live())output_final(0,0,"KBL not running");
            else if(op==KBL_S_STORE){const void*payload=0;size_t payload_n=0;char digest[65];
                if(!store_payload(a,n,download,download_n,&path,&payload,&payload_n,digest))output_final(0,0,"KBL store envelope/path/hash invalid");
                else if(kbl_native_put(g.kernel.native,path,payload,payload_n)!=KBL_OK)output_final(0,0,"KBL store failed or busy");
                else{char line[57],*t=put(line,"KBL1 stored=1 size=");t=decimal(t,payload_n);*t=0;output_info(0,line);
                    char half[33];copy(half,digest,32);half[32]=0;cache_info("hash0",half);cache_info("hash1",digest+32);
                    char id[17];t=hex64(id,g.session.generation);*t=0;cache_info("id",id);output_final(0,1,"");}
            }
            else if(!read_path(a,n,download,download_n,&path))output_final(0,0,"Invalid UTF-8 path envelope");
            else{struct exported*e=pool_allocate(0,sizeof(*e),16);if(e)zero(e,sizeof(*e));
                char digest[65];if(!e)output_final(0,0,"KBL snapshot allocation failed");
                else if(kbl_native_open_snapshot(g.kernel.native,path,&e->reader)!=KBL_OK){pool_release(0,e,sizeof(*e));output_final(0,0,"KBL snapshot unavailable or busy");}
                else if(!e->reader.close||!e->reader.read_at||e->reader.size>UINT64_MAX/8||snapshot_hash(&e->reader,digest)!=KBL_OK){if(e->reader.close)e->reader.close(e->reader.opaque);pool_release(0,e,sizeof(*e));output_final(0,0,"KBL snapshot hash failed");}
                else{++g.next_export;if(!g.next_export)++g.next_export;e->id=g.next_export;e->next=g.exports;g.exports=e;char value[40],*t=put(value,"kbl.");t=hex64(t,e->id);*t=0;cache_info("file",value);
                    t=decimal(value,e->reader.size);*t=0;cache_info("size",value);char half[33];copy(half,digest,32);half[32]=0;cache_info("hash0",half);cache_info("hash1",digest+32);output_final(0,1,"");}
            }
            path_release(path);
        }else if(op==KBL_S_READ_CLOSE){struct exported*e=a?exported_id(a,n):0;if(!e||e->borrowers)output_final(0,0,"KBL read handle unavailable");else{export_release(e);output_final(0,1,"");}}
        else if(op==KBL_S_MAX_FETCH){output_final(0,1,"0xffffffff");}
        else if(op==KBL_S_BLOB_SIZE||op==KBL_S_BLOB_HAS_SLOT){struct exported*e=a?exported_id(a,n):0;char value[17];if(!e)output_final(0,0,"KBL read handle unavailable");else if(op==KBL_S_BLOB_HAS_SLOT)output_final(0,1,"no");else{char*t=hex64(value,e->reader.size);*t=0;output_final(0,1,value);}}
        else if(op==KBL_S_FETCH){
            int bound=1;if(g.usb.initialized){
                bound=kbl_native_usb_begin(&g.usb)==KBL_USB_OK;
                if(bound){l=kbl_native_usb_link(&g.usb);g.link=l;}
            }
            if(!bound)output_final(0,0,"Native USB idle witness unavailable; start KBL first");
            else if(!has_tail(l,offsetof(struct kbl_firmware_link,abort)+sizeof(l->abort))||!l->data||!l->write||!l->abort||g.transport_hold||g.pending_work)output_final(0,0,"Binary fetch transport unavailable");
            else if(!a||!n||n>KBL_WIRE_BYTES-6)output_final(0,0,"Invalid fetch argument");
            else{unsigned char request[KBL_WIRE_BYTES];copy(request,"fetch:",6);copy(request+6,a,n);
                const struct kbl_fetch_source source={0,fetch_open};const struct kbl_fetch_wire wire={0,fetch_data,fetch_write,output_final,fetch_abort};size_t work_bytes=0;
                if(kbl_fetch_capacity(request,n+6,&source,&work_bytes)!=KBL_OK)output_final(0,0,"Invalid fetch source or size");
                else{void*work=pool_allocate(0,work_bytes,16);if(!work)output_final(0,0,"Fetch memory unavailable");else{
                    (void)kbl_fetch(request,n+6,&source,&wire,work,work_bytes);
                    if(g.transport_hold&&!quiet(l)){g.pending_work=work;g.pending_work_bytes=work_bytes;}
                    else{g.transport_hold=0;pool_release(0,work,work_bytes);}
                }}
            }
        }else output_final(0,0,"Unsupported KBL service operation");
    }
    r->stopped=stopped();int result=r->transport_error?KBL_S_IO:KBL_S_OK;g.link=0;g.reply=0;g.current_operation=0;g.in_request=0;return result;
}
static int usb_enter(void*p){return p==&g&&g.in_request&&g.current_operation==KBL_S_FETCH;}
static void usb_leave(void*p){(void)p;}
static int usb_command_rx(void*p,const struct kbl_usb_outcome*t){
    if(p!=&g||!t||t->endpoint!=1||t->status!=KBL_USB_COMPLETE||!t->completed||t->completed>KBL_WIRE_BYTES)return 0;
    uintptr_t base=g.usb_facts.parent_base;
    return t->buffer==*(void**)(base+g.usb_facts.command_rx_slot_rva)&&
           *(const unsigned char*)(base+g.usb_facts.download_state_byte_rva)==g.usb_facts.command_state_value;
}
static void usb_final(void*p,int okay,const char*s){
    if(p!=&g)return;uintptr_t target=g.usb_facts.parent_base+(okay?g.usb_facts.native_okay_rva:g.usb_facts.native_fail_rva);
    ((void(*)(const char*))target)(s);
}
static uint64_t usb_now(void*p){
    if(p!=&g||!g.counter_frequency)return 0;
    uint64_t tick=((uint64_t(*)(void))(g.usb_facts.parent_base+g.usb_facts.counter_ticks_rva))();
    return(tick/g.counter_frequency)*UINT64_C(1000000)+(tick%g.counter_frequency)*UINT64_C(1000000)/g.counter_frequency;
}
#include "kbl_adb_driver.inc"
static int same_bytes(const void*a,const void*b,size_t n){const unsigned char*x=a,*y=b;while(n--)if(*x++!=*y++)return 0;return 1;}
static int bind_usb(struct kbl_service_protocol*p,const struct kbl_usb_parent_facts*f){
    if(p!=&protocol||!g.installed||g.in_request||!f||f->abi!=1||f->bytes!=sizeof(*f)||!f->parent_base||f->command_state_value>255||
       (f->descriptor_set_rva&&f->descriptor_set_rva>UINTPTR_MAX-f->parent_base))return KBL_S_ERROR;
    const uint64_t offsets[]={f->protocol_slot_rva,f->ack_buffer_slot_rva,f->command_rx_slot_rva,f->download_state_byte_rva,
                             f->native_okay_rva,f->native_fail_rva,f->counter_frequency_rva,f->counter_ticks_rva};
    for(unsigned i=0;i<sizeof(offsets)/sizeof(offsets[0]);++i)if(!offsets[i]||offsets[i]>UINTPTR_MAX-f->parent_base)return KBL_S_ERROR;
    if(g.usb.initialized&&g.usb.attached)return same_bytes(&g.usb_facts,f,sizeof(*f))?KBL_S_OK:KBL_S_ERROR;
    if(g.usb.initialized&&!kbl_native_usb_can_release(&g.usb))return KBL_S_ERROR;
    uint64_t frequency=((uint64_t(*)(void))(f->parent_base+f->counter_frequency_rva))();
    if(!frequency||frequency>UINT32_MAX)return KBL_S_UNAVAILABLE;
    zero(&g.usb,sizeof(g.usb));g.usb_facts=*f;g.counter_frequency=frequency;
    const struct kbl_native_usb_environment e={&g,(void*)(f->parent_base+f->protocol_slot_rva),
        *(void**)(f->parent_base+f->ack_buffer_slot_rva),usb_enter,usb_leave,usb_command_rx,usb_final,usb_now,0};
    if(kbl_native_usb_init(&g.usb,&e,65536,UINT64_C(2000000),UINT64_C(250000),1000000)!=KBL_USB_OK)return KBL_S_UNAVAILABLE;
    const struct kbl_native_usb_extensions x={&g,adb_usb_event,adb_idle};
    if(kbl_native_usb_extensions(&g.usb,&x)!=KBL_USB_OK)return KBL_S_UNAVAILABLE;
    return kbl_native_usb_observe(&g.usb)==KBL_USB_OK?KBL_S_OK:KBL_S_UNAVAILABLE;
}
_Static_assert(sizeof(struct kbl_usb_parent_facts)==88,"parent USB facts ABI");
static kefi_status unload_image(kefi_handle image){
    if(image!=g.image||!can_unload(&protocol))return KEFI_ACCESS_DENIED;
    kefi_status rc=g.bs->uninstall_protocol(g.image,&service_guid,&protocol);if(rc!=KEFI_SUCCESS)return rc;
    if(g.loaded)g.loaded->unload=g.previous_unload;g.installed=0;return KEFI_SUCCESS;
}
kefi_status EfiMain(kefi_handle image,struct kefi_system_table*st){
    if(g.installed)return KEFI_ALREADY_STARTED;
    if(!image||!st||st->header.signature!=KEFI_SYSTEM_SIGNATURE||st->header.bytes<sizeof(*st)||!st->boot_services)return KEFI_INVALID;
    struct kefi_boot_services*bs=st->boot_services;
    if(bs->header.signature!=KEFI_BOOT_SIGNATURE||bs->header.bytes<offsetof(struct kefi_boot_services,locate_protocol)+sizeof(bs->locate_protocol)||
       !bs->install_protocol||!bs->uninstall_protocol||!bs->handle_protocol)return KEFI_UNSUPPORTED;
    void *loaded=0;kefi_status rc=bs->handle_protocol(image,&loaded_guid,&loaded);if(rc!=KEFI_SUCCESS||!loaded)return KEFI_UNSUPPORTED;
    g.image=image;g.st=st;g.bs=bs;g.loaded=loaded;g.previous_unload=g.loaded->unload;g.loaded->unload=unload_image;
    kefi_handle own=image;rc=bs->install_protocol(&own,&service_guid,0,&protocol);
    if(rc!=KEFI_SUCCESS){g.loaded->unload=g.previous_unload;zero(&g,sizeof(g));return rc;}
    if(own!=image){/* Broken provider: quarantine rather than unload callback code still installed elsewhere. */
        if(bs->uninstall_protocol(own,&service_guid,&protocol)==KEFI_SUCCESS){g.loaded->unload=g.previous_unload;zero(&g,sizeof(g));return KEFI_ACCESS_DENIED;}
        g.installed=1;g.ownership_error=1;return KEFI_SUCCESS;
    }
    g.installed=1;return KEFI_SUCCESS;
}

#if defined(KBL_DRIVER_FREESTANDING)
void *memcpy(void*d,const void*s,size_t n){copy(d,s,n);return d;}
void *memset(void*d,int c,size_t n){unsigned char*p=d;while(n--)*p++=(unsigned char)c;return d;}
void *memmove(void*d,const void*s,size_t n){unsigned char*p=d;const unsigned char*q=s;if(p<q)copy(d,s,n);else while(n){--n;p[n]=q[n];}return d;}
#endif
