/* SPDX-License-Identifier: MIT */
#include "kbl_parent_shim.h"
typedef uintptr_t efi_status;
struct guid { uint32_t a; uint16_t b,c; unsigned char d[8]; };
static const struct guid service_guid=KBL_SERVICE_GUID_VALUES;
struct reply_context { const struct kbl_parent_config *cfg; uintptr_t base; unsigned sent,okay; };
static int send_final(void *v,int okay,const char *s) {
 struct reply_context *c=v; size_t n=0;
 if(c->sent || !s) return KBL_S_IO;
 /* Native formatter owns 64 bytes: four-byte prefix plus trailing NUL. */
 while(n<60 && s[n]) { if((unsigned char)s[n]<32 || (unsigned char)s[n]>126) return KBL_S_IO; ++n; }
 if(n>59) return KBL_S_IO;
 c->sent=1;c->okay=okay!=0;
 ((void(*)(const char *))(c->base+(okay?c->cfg->okay_rva:c->cfg->fail_rva)))(s);
 return 0;
}
static int no_info(void *v,const char *s) {(void)v;(void)s;return KBL_S_UNAVAILABLE;}
static int quiescent(void *v) {(void)v;return 1;}
static int argument(unsigned op,const char *s,const unsigned char **arg,size_t *bytes) {
 size_t n=0; if(!s) return 0;
 /* Native Fastboot control packets are at most 64 bytes including NUL.
  * Long shell lines are assembled by the controller from bounded chunks. */
 while(n<64 && s[n]) { if((unsigned char)s[n]<32 || (unsigned char)s[n]>126) return 0; ++n; }
 if(n>63) return 0;
 if(op==KBL_S_START || op==KBL_S_ADB || op==KBL_S_IN || op==KBL_S_OFF || op==KBL_S_STATUS ||
    op==KBL_S_REPLY_HEADER || op==KBL_S_MAX_FETCH ||
    op==KBL_S_SCREEN_OFF || op==KBL_S_SCREEN_ON || op==KBL_S_SCREEN_STATUS) {if(n) return 0;}
 else if(op==KBL_S_REPLY_PAGE || op==KBL_S_FETCH || op==KBL_S_BLOB_SIZE || op==KBL_S_BLOB_HAS_SLOT) {if(!n) return 0;}
 else { if(!n || s[0]!=' ') return 0; ++s; --n; if(!n) return 0; }
 *arg=(const unsigned char *)s;*bytes=n;return 1;
}
static int protocol_valid(struct kbl_service_protocol *p) {
 return p && p->magic==KBL_SERVICE_MAGIC && p->abi==KBL_SERVICE_ABI &&
 p->bytes>=offsetof(struct kbl_service_protocol,bind_usb) && p->request && p->can_unload;
}
static int read_envelope(const unsigned char *arg,size_t bytes) {
 if(bytes!=21 || arg[0]!='e' || arg[1]!='n' || arg[2]!='v' || arg[3]!='1' || arg[4]!=':')return 0;
 for(size_t i=5;i<21;++i)if(!((arg[i]>='0'&&arg[i]<='9')||(arg[i]>='a'&&arg[i]<='f')||(arg[i]>='A'&&arg[i]<='F')))return 0;
 return 1;
}
/* Called only after the assembly edge has matched an entire entry and NUL.
 * NativeRegister returns void, including silent allocation failure. Observe
 * the real native head/node after every call; a retry only fills missing
 * entries, and never duplicates a successfully published registration. */
__attribute__((visibility("hidden")))
void kbl_parent_lazy_start(const struct kbl_parent_config *c,uintptr_t base,
                          const struct kbl_parent_lazy_config *l,const char *suffix,unsigned op) {
 struct reply_context x={c,base,0,0};
 if(!c||c->magic!=KBL_PARENT_MAGIC||c->abi!=KBL_PARENT_ABI||c->bytes!=sizeof(*c))return;
 if(!l||l->magic!=KBL_LAZY_MAGIC||l->bytes!=sizeof(*l)||!l->count||l->count>31||
    !l->native_register_rva||!l->head_rva||!l->entries_rva||!suffix||*suffix||
    (op!=KBL_S_START&&op!=KBL_S_ADB&&op!=KBL_S_SCREEN_OFF&&op!=KBL_S_SCREEN_ON&&op!=KBL_S_SCREEN_STATUS)){
  send_final(&x,0,"invalid KBL lazy metadata");return;
 }
 const uint32_t offsets[4]={l->next_offset,l->prefix_offset,l->length_offset,l->handler_offset};
 for(unsigned i=0;i<4;++i){
  if(offsets[i]>248 || offsets[i]%(i==2?4:8)) {send_final(&x,0,"invalid KBL node layout");return;}
  for(unsigned j=0;j<i;++j)
   if(offsets[i]<offsets[j]+(j==2?4u:8u)&&offsets[j]<offsets[i]+(i==2?4u:8u)){
    send_final(&x,0,"invalid KBL node layout");return;
   }
 }
 struct kbl_parent_state *s=(void *)(base+c->state_rva);
 if(s->registering){send_final(&x,0,"KBL registration busy");return;}
 s->registering=1;
 const uint64_t *entries=(const void *)(base+l->entries_rva);
 uintptr_t *head=(void *)(base+l->head_rva);
 for(unsigned i=0;i<l->count;++i){
  uint32_t bit=UINT32_C(1)<<i;
  if(s->registered_mask&bit)continue;
  const char *name=(const void *)(base+entries[i*2]);
  void *handler=(void *)(base+entries[i*2+1]);
  size_t n=0;while(n<64&&name[n])++n;
  if(!n||n>63){s->registering=0;send_final(&x,0,"invalid KBL registration name");return;}
  uintptr_t old=*head;
  ((void(*)(const char *,void *))(base+l->native_register_rva))(name,handler);
  uintptr_t node=*head;
  if(!node||node==old){s->registering=0;send_final(&x,0,"KBL registration allocation failed");return;}
  if(*(uintptr_t *)(node+l->next_offset)!=old ||
     *(const char **)(node+l->prefix_offset)!=name ||
     *(uint32_t *)(node+l->length_offset)!=n ||
     *(void **)(node+l->handler_offset)!=handler){
   s->registering=0;send_final(&x,0,"KBL registration verification failed");return;
  }
  s->registered_mask|=bit;
 }
 s->registering=0;
 kbl_parent_dispatch(c,base,op,suffix,0,0);
}
static int cleanup_dirty(const struct kbl_parent_config *c,uintptr_t base,struct kbl_parent_state *s) {
 void *boot=*(void **)(base+c->boot_global);
 void *system=*(void **)(base+c->system_global);
 typedef efi_status(*unload_t)(void *);
 if(!boot || !system || *(void **)((uintptr_t)system+0x60)!=boot || !s->image) return 0;
 unload_t ui=*(unload_t *)((uintptr_t)boot+0xe0);
 if(!ui || ui(s->image)) return 0;
 s->phase=0;s->image=0;s->service=0;s->usb_bound=s->started=0;return 1;
}
/* EFI 2.x ARM64 Boot Services layout: HandleProtocol=0x98,
 * LoadImage=0xc8, StartImage=0xd0, UnloadImage=0xe0. Never ExitBootServices. */
static int load(const struct kbl_parent_config *c,uintptr_t base,struct kbl_parent_state *s) {
 void *boot=*(void **)(base+c->boot_global);
 void *parent=*(void **)(base+c->image_global);
 void *system=*(void **)(base+c->system_global);
 void *handle=0;struct kbl_service_protocol *p=0;efi_status rc;
 if(s->phase==1) return protocol_valid(s->service);
 if(s->phase || !boot || !parent || !system || *(void **)((uintptr_t)system+0x60)!=boot) return 0;
 typedef efi_status(*load_t)(unsigned char,void *,void *,void *,size_t,void **);
 typedef efi_status(*start_t)(void *,size_t *,uint16_t **);
 typedef efi_status(*protocol_t)(void *,const struct guid *,void **);
 typedef efi_status(*unload_t)(void *);
 load_t li=*(load_t *)((uintptr_t)boot+0xc8);
 start_t si=*(start_t *)((uintptr_t)boot+0xd0);
 protocol_t hp=*(protocol_t *)((uintptr_t)boot+0x98);
 unload_t ui=*(unload_t *)((uintptr_t)boot+0xe0);
 if(!li||!si||!hp||!ui) return 0;
 rc=li(0,parent,0,(void *)(base+c->driver_rva),(size_t)c->driver_bytes,&handle);
 if(!handle) return 0;
 s->phase=2;s->image=handle;s->service=0;
 /* EFI_SECURITY_VIOLATION may still return an owned loaded-image handle.
  * Account for it before interpreting status; never silently lose it. */
 if(rc) {if(!ui(handle)){s->phase=0;s->image=0;s->usb_bound=0;}return 0;}
 rc=si(handle,0,0);
 if(!rc) rc=hp(handle,&service_guid,(void **)&p);
 if(!rc && protocol_valid(p)) {s->service=p;s->phase=1;return 1;}
 /* Retain dirty handle on failed cleanup; do not load a duplicate image. */
 if(!ui(handle)) {s->phase=0;s->image=0;s->service=0;s->usb_bound=0;}
 return 0;
}
__attribute__((section(".text.kbl_entry"),visibility("hidden")))
void kbl_parent_dispatch(const struct kbl_parent_config *c,uintptr_t base,unsigned op,
                         const char *suffix,const void *download,size_t download_bytes) {
 struct reply_context x={c,base,0,0};const unsigned char *arg=0;size_t arg_bytes=0;
 if(!c || c->magic!=KBL_PARENT_MAGIC || c->abi!=KBL_PARENT_ABI || c->bytes!=sizeof(*c)) return;
 if(!suffix) {send_final(&x,0,"invalid KBL command");return;}
 struct kbl_parent_state *s=(void *)(base+c->state_rva);
 /* Preserve existing generic getvar/fetch commands. Native dispatcher suffix
  * points inside its original complete NUL-terminated command buffer. */
 int alias=suffix && suffix[0]=='k' && suffix[1]=='b' && suffix[2]=='l' && suffix[3]=='.';
 if((op==KBL_S_BLOB_SIZE || op==KBL_S_BLOB_HAS_SLOT || (op==KBL_S_MAX_FETCH && suffix && *suffix)) &&
    (!alias || op==KBL_S_MAX_FETCH) && c->generic_getvar_rva) {
  size_t prefix=op==KBL_S_BLOB_SIZE?15:op==KBL_S_BLOB_HAS_SLOT?9:14;
  ((void(*)(const char *,const void *,size_t))(base+c->generic_getvar_rva))(suffix-prefix,download,download_bytes);return;
 }
 if(op==KBL_S_FETCH && !alias) {
  if(c->generic_fetch_rva){((void(*)(const char *,const void *,size_t))(base+c->generic_fetch_rva))(suffix,download,download_bytes);return;}
  /* Only an explicitly successful START may expose the read-only partition
   * source when this firmware had no original native FETCH command. */
  if(s->phase!=1||!s->started||!s->usb_bound){send_final(&x,0,"unknown fetch target");return;}
 }
 if(op<KBL_S_START || op>KBL_S_SCREEN_STATUS || !argument(op,suffix,&arg,&arg_bytes)) {
  send_final(&x,0,"invalid KBL command");return;
 }
 int uses_download=op==KBL_S_STORE || (op==KBL_S_READ_OPEN && read_envelope(arg,arg_bytes));
 if(uses_download && !(c->flags&KBL_PARENT_STORE_ABI)) {
  send_final(&x,0,"KBL store ABI unavailable");return;
 }
 if(op==KBL_S_MAX_FETCH && (s->phase!=1 || !s->usb_bound || !s->started) && c->generic_getvar_rva) {
  ((void(*)(const char *,const void *,size_t))(base+c->generic_getvar_rva))(suffix-14,download,download_bytes);return;
 }
 if(op==KBL_S_OFF && s->phase==2) {
  int cleaned=cleanup_dirty(c,base,s);
  send_final(&x,cleaned,cleaned?"KBL driver unloaded":"KBL cleanup pending");return;
 }
 /* Explicit display controls load only the service image. No bind_usb,
  * observer/native kernel/ADB start occurs on this path. */
 if((op==KBL_S_START||op==KBL_S_ADB||op==KBL_S_SCREEN_OFF||op==KBL_S_SCREEN_ON||op==KBL_S_SCREEN_STATUS) && !load(c,base,s)) {send_final(&x,0,"KBL driver load failed");return;}
 if(s->phase!=1 || !protocol_valid(s->service)) {send_final(&x,0,"KBL not started");return;}
 if(op==KBL_S_START||op==KBL_S_ADB) {
  s->usb_bound=0;
  if(c->usb_facts_rva && s->service->bytes>=sizeof(struct kbl_service_protocol) && s->service->bind_usb) {
   const struct kbl_usb_parent_facts *f=(const void *)(base+c->usb_facts_rva);
   struct kbl_usb_parent_facts actual=*f;actual.parent_base=base;
   if(actual.abi==KBL_SERVICE_ABI && actual.bytes==sizeof(actual) && !s->service->bind_usb(s->service,&actual))s->usb_bound=1;
  }
 }
 if((op==KBL_S_FETCH || op==KBL_S_MAX_FETCH) && (!s->usb_bound||!s->started)) {send_final(&x,0,"KBL binary transport unavailable");return;}
 struct kbl_firmware_link link={KBL_SERVICE_ABI,sizeof(link),&x,no_info,send_final,0,0,0,quiescent,KBL_LINK_DEFER_INFO};
 struct kbl_service_reply r={sizeof(r),0,0,0};
 int rc=s->service->request(s->service,op,arg,arg_bytes,
                           uses_download?download:0,uses_download?download_bytes:0,&link,&r);
 /* Native final is invoked at most once. Driver-cached INFO is fetched by the
  * exact dedicated getvar requests, never blind USB intermediate sends. */
 if(op==KBL_S_START||op==KBL_S_ADB)s->started=x.sent&&x.okay;
 if(op==KBL_S_OFF)s->usb_bound=s->started=0;
 if(!x.sent && !r.response_sent && !(op==KBL_S_FETCH && r.transport_error))
  send_final(&x,0,rc?"KBL request failed":"KBL response missing");
}
