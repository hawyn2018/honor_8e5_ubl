/* SPDX-License-Identifier: MIT
 * Explicit fake EFI / fake native backend model. Real controller, fetch,
 * partition and SHA modules are linked. This is not hardware validation. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define KBL_DRIVER_TEST 1
#include "../kbl_driver.c"

static unsigned checks,cases,el=1,allocs,frees,page_allocs,page_frees,handles,installs,uninstalls,locates;
static unsigned fail_free,fail_uninstall,missing_attr,deny_attr,deny_clear,deny_get,fail_start,start_calls,stop_calls;
static unsigned fail_page_alloc,partial_page_alloc;
static unsigned info_calls,final_calls,data_calls,abort_calls,quiescent=1,fail_write,fail_final;
static int last_ok;static char last_final[80],infos[16][57];static size_t fetched;
static const struct kbl_native_platform *np;
static size_t large_store_expected,large_store_accepted;
static char stored_path[1024];
struct fake_context { size_t bytes;unsigned char data[4096]; };
struct fake_snapshot { size_t bytes;unsigned char data[4096]; };
struct fake_page { void *original;uint64_t raw,attr;size_t count; };
static struct fake_page fp[16];
static struct kefi_system_table st;static struct kefi_boot_services bs;static struct kefi_loaded_image li;
#define CHECK(x) do {++checks;if(!(x)){fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x);exit(1);}}while(0)
unsigned kbl_driver_test_current_el(void){return el;}
static kefi_status alloc_pool(unsigned type,size_t n,void **out){CHECK(type==4);*out=calloc(1,n);if(*out)++allocs;return *out?0:KEFI_OUT_OF_RESOURCES;}
static kefi_status free_pool(void*p){if(fail_free)return KEFI_OUT_OF_RESOURCES;CHECK(p);++frees;free(p);return 0;}
static kefi_status alloc_page(unsigned type,unsigned mem,size_t count,uint64_t*out){CHECK(type==0&&mem==2);*out=0;if(fail_page_alloc&&!partial_page_alloc)return KEFI_OUT_OF_RESOURCES;for(unsigned i=0;i<16;++i)if(!fp[i].original){void*p=calloc(1,count*4096+4095);if(!p)return KEFI_OUT_OF_RESOURCES;fp[i]=(struct fake_page){p,((uintptr_t)p+4095)&~(uintptr_t)4095,0,count};*out=fp[i].raw;++page_allocs;return fail_page_alloc?KEFI_OUT_OF_RESOURCES:0;}return KEFI_OUT_OF_RESOURCES;}
static kefi_status free_page(uint64_t p,size_t count){if(fail_free)return KEFI_OUT_OF_RESOURCES;for(unsigned i=0;i<16;++i)if(fp[i].original&&fp[i].raw==p){CHECK(fp[i].count==count);free(fp[i].original);memset(&fp[i],0,sizeof(fp[i]));++page_frees;return 0;}CHECK(0);return KEFI_INVALID;}
static struct fake_page *page_at(uint64_t p,uint64_t n){for(unsigned i=0;i<16;++i)if(fp[i].original&&p>=fp[i].raw&&p-fp[i].raw<=fp[i].count*4096&&n<=fp[i].count*4096-(p-fp[i].raw))return &fp[i];return 0;}
static kefi_status attr_get(struct kefi_memory_attributes*a,uint64_t p,uint64_t n,uint64_t*out){(void)a;struct fake_page*f=page_at(p,n);if(!f||deny_get)return KEFI_INVALID;*out=f->attr;return 0;}
static kefi_status attr_set(struct kefi_memory_attributes*a,uint64_t p,uint64_t n,uint64_t flags){(void)a;struct fake_page*f=page_at(p,n);if(!f||deny_attr)return KEFI_ACCESS_DENIED;f->attr|=flags;return 0;}
static kefi_status attr_clear(struct kefi_memory_attributes*a,uint64_t p,uint64_t n,uint64_t flags){(void)a;struct fake_page*f=page_at(p,n);if(!f||deny_attr||deny_clear)return KEFI_ACCESS_DENIED;f->attr&=~flags;return 0;}
static struct kefi_memory_attributes attrs={attr_get,attr_set,attr_clear};
static kefi_status handle(kefi_handle image,const struct kefi_guid*guid,void**out){++handles;CHECK(image==(void*)0x1234);CHECK(!memcmp(guid,&loaded_guid,16));*out=&li;return 0;}
static kefi_status install(kefi_handle*image,const struct kefi_guid*guid,unsigned kind,void*value){++installs;CHECK(*image==(void*)0x1234&&kind==0);CHECK(!memcmp(guid,&service_guid,16)&&value==&protocol);return 0;}
static kefi_status uninstall(kefi_handle image,const struct kefi_guid*guid,void*value){++uninstalls;CHECK(image==(void*)0x1234&&!memcmp(guid,&service_guid,16)&&value==&protocol);return fail_uninstall?KEFI_ACCESS_DENIED:0;}
static kefi_status locate(const struct kefi_guid*guid,void*reg,void**out){++locates;CHECK(!reg&&!memcmp(guid,&attributes_guid,16));*out=missing_attr?0:&attrs;return missing_attr?KEFI_UNSUPPORTED:0;}
static int native_start(void*o,void**out){(void)o;CHECK(np->stack_bytes==256u*1024u&&np->efi_run);++start_calls;*out=np->allocate(np->opaque,sizeof(struct fake_context),16);return *out&&!fail_start?0:-1;}
static int native_submit(void*c,const unsigned char*p,size_t n,uint64_t*j){(void)c;(void)p;(void)n;*j=1;return 0;}
static int native_poll(void*c,uint64_t j,unsigned b,unsigned char*p,size_t cap,size_t*w,int*d,int*r){(void)c;(void)j;(void)b;(void)p;(void)cap;*w=0;*d=1;*r=0;return 0;}
static int native_stop(void*c){++stop_calls;np->release(np->opaque,c,sizeof(struct fake_context));return 0;}
int kbl_native_backend(struct kbl_backend*b,const struct kbl_native_platform*p){np=p;*b=(struct kbl_backend){0,current_el,native_start,native_submit,native_poll,native_stop,0,0};return 0;}
/* This legacy failure model intentionally has no external native file API.
 * Real native ADB/broker integration is covered by its separate fixture. */
int kbl_native_io_begin(void*c,const struct kbl_program_api**a){(void)c;*a=0;return -KBL_ENOSYS;}
int kbl_native_io_end(void*c,const struct kbl_program_api*a){(void)c;(void)a;return -KBL_ENOSYS;}
int kbl_native_io_list(void*c,const struct kbl_program_api*a,const char*p,uint64_t i,char*n,size_t z,struct kbl_file_stat*s,int*e){(void)c;(void)a;(void)p;(void)i;(void)n;(void)z;(void)s;(void)e;return -KBL_ENOSYS;}
/* Session APIs are deliberately unavailable in this fake-native lifecycle
 * model. Real concurrent shell/sync semantics use the actual-native fixture. */
int kbl_native_session_create(void*c,void**out){(void)c;if(out)*out=0;return KBL_ERROR;}
int kbl_native_session_close(void*c,void*s){(void)c;(void)s;return KBL_ERROR;}
int kbl_native_session_submit(void*c,void*s,const unsigned char*p,size_t n,uint64_t*j){(void)c;(void)s;(void)p;(void)n;if(j)*j=0;return KBL_ERROR;}
int kbl_native_session_poll(void*c,void*s,uint64_t j,unsigned budget,unsigned char*p,size_t z,size_t*n,int*d,int*r){(void)c;(void)s;(void)j;(void)budget;(void)p;(void)z;if(n)*n=0;if(d)*d=1;if(r)*r=KBL_ERROR;return KBL_ERROR;}
int kbl_native_session_io_begin(void*c,void*s,const struct kbl_program_api**a){(void)c;(void)s;if(a)*a=0;return KBL_ERROR;}
int kbl_native_session_io_end(void*c,void*s,const struct kbl_program_api*a){(void)c;(void)s;(void)a;return KBL_ERROR;}
int kbl_native_service_submit(void*c,const unsigned char*p,size_t n,uint64_t*j){return native_submit(c,p,n,j);}
int kbl_native_service_poll(void*c,uint64_t j,unsigned b,unsigned char*p,size_t z,size_t*n,int*d,int*r){return native_poll(c,j,b,p,z,n,d,r);}
int kbl_native_put(void*c,const char*path,const void*p,size_t n){struct fake_context*f=c;if(!path[0]||strlen(path)>=sizeof(stored_path))return -1;strcpy(stored_path,path);if(n>sizeof(f->data)){if(n!=large_store_expected||!p||((const unsigned char*)p)[0]!='z'||((const unsigned char*)p)[n-1]!='z')return -1;large_store_accepted=n;return 0;}f->bytes=n;if(n)memcpy(f->data,p,n);return 0;}
static int snap_read(void*c,uint64_t off,void*p,size_t n,size_t*out){struct fake_snapshot*s=c;*out=0;if(off>s->bytes||n>s->bytes-off)return -1;if(n)memcpy(p,s->data+(size_t)off,n);*out=n;return 0;}
static void snap_close(void*c){np->release(np->opaque,c,sizeof(struct fake_snapshot));}
int kbl_native_open_snapshot(void*c,const char*path,struct kbl_reader*r){struct fake_context*f=c;(void)path;struct fake_snapshot*s=np->allocate(np->opaque,sizeof(*s),16);if(!s)return -1;s->bytes=f->bytes;memcpy(s->data,f->data,f->bytes);*r=(struct kbl_reader){s,s->bytes,snap_read,snap_close};return 0;}
static int info(void*o,const char*s){(void)o;CHECK(strlen(s)<=56);if(info_calls<16)strcpy(infos[info_calls],s);++info_calls;return 0;}
static int final(void*o,int okay,const char*s){(void)o;++final_calls;last_ok=okay;CHECK(strlen(s)<sizeof(last_final));strcpy(last_final,s);return fail_final?-1:0;}
static int data(void*o,uint32_t n){(void)o;++data_calls;CHECK(n<=KBL_MAX_FETCH_BYTES);return 0;}
static int write_data(void*o,const void*p,size_t n,size_t*w){(void)o;CHECK(p&&n);if(fail_write){*w=0;return -1;}*w=n;fetched+=n;return 0;}
static void abort_data(void*o){(void)o;++abort_calls;}
static int is_quiet(void*o){(void)o;return quiescent;}
static struct kbl_firmware_link link={1,sizeof(link),0,info,final,data,write_data,abort_data,is_quiet,0};
static struct kbl_service_reply request(unsigned op,const char*a,const void*d,size_t n){struct kbl_service_reply r={sizeof(r),0,0,0};unsigned before=final_calls;int rc=protocol.request(&protocol,op,(const unsigned char*)a,a?strlen(a):0,d,n,&link,&r);CHECK(rc==0||rc==KBL_S_IO);CHECK(r.response_sent||op==KBL_S_FETCH);CHECK(final_calls==before+1||(op==KBL_S_FETCH&&abort_calls));return r;}
static void fresh(void){CHECK(!g.pools&&!g.pages);memset(&g,0,sizeof(g));memset(&st,0,sizeof(st));memset(&bs,0,sizeof(bs));memset(&li,0,sizeof(li));el=1;quiescent=1;fail_free=fail_uninstall=missing_attr=deny_attr=deny_clear=deny_get=fail_start=fail_write=fail_final=0;info_calls=final_calls=data_calls=abort_calls=0;link.flags=0;link.bytes=sizeof(link);
    large_store_expected=large_store_accepted=0;stored_path[0]=0;
    st.header=(struct kefi_header){KEFI_SYSTEM_SIGNATURE,0,sizeof(st),0,0};st.boot_services=&bs;bs.header=(struct kefi_header){KEFI_BOOT_SIGNATURE,0,sizeof(bs),0,0};
    bs.allocate_pool=alloc_pool;bs.free_pool=free_pool;bs.allocate_pages=alloc_page;bs.free_pages=free_page;bs.handle_protocol=handle;bs.install_protocol=install;bs.uninstall_protocol=uninstall;bs.locate_protocol=locate;
    unsigned a=allocs,h=handles,i=installs,l=locates,s=start_calls;CHECK(EfiMain((void*)0x1234,&st)==0);CHECK(allocs==a&&handles==h+1&&installs==i+1&&locates==l&&start_calls==s);CHECK(protocol.can_unload(&protocol));CHECK(!g.platform.uefi_environment);++cases;
}
static void off(void){request(KBL_S_OFF,0,0,0);CHECK(last_ok&&protocol.can_unload(&protocol));}
static void hexpath(char*out,const char*s){const char*h="0123456789abcdef";while(*s){unsigned c=(unsigned char)*s++;*out++=h[c>>4];*out++=h[c&15];}*out=0;}
static void store_legacy(void){char path[64];hexpath(path,"/sample");request(KBL_S_STORE,path,"example",7);CHECK(last_ok);}
static void case_cache(void){fresh();link.flags=KBL_LINK_DEFER_INFO;request(KBL_S_START,0,0,0);CHECK(last_ok&&!info_calls&&!protocol.can_unload(&protocol));uint64_t gen=g.reply_generation;unsigned count=g.reply_count;CHECK(count);request(KBL_S_REPLY_HEADER,0,0,0);CHECK(last_ok&&strstr(last_final,"KBLC1 g=")&&g.reply_generation==gen);char query[40];sprintf(query,"%016llx:0",(unsigned long long)gen);request(KBL_S_REPLY_PAGE,query,0,0);CHECK(last_ok&&!strncmp(last_final,"KBL1 ",5)&&g.reply_count==count&&g.reply_generation==gen);sprintf(query,"%016llx:0",(unsigned long long)(gen+1));request(KBL_S_REPLY_PAGE,query,0,0);CHECK(!last_ok);off();gen=g.reply_generation;request(KBL_S_REPLY_HEADER,0,0,0);CHECK(last_ok&&g.reply_generation==gen&&!info_calls);}
static void case_snapshot_fetch(void){fresh();request(KBL_S_START,0,0,0);store_legacy();char path[64];hexpath(path,"/sample");request(KBL_S_READ_OPEN,path,0,0);CHECK(last_ok&&g.exports&&g.exports->id);char alias[32];sprintf(alias,"kbl.%016llx",(unsigned long long)g.exports->id);size_t before=fetched;request(KBL_S_FETCH,alias,0,0);CHECK(last_ok&&fetched-before==7&&g.exports->id&&!g.exports->borrowers);request(KBL_S_READ_CLOSE,alias,0,0);CHECK(last_ok&&!g.exports);request(KBL_S_READ_CLOSE,alias,0,0);CHECK(!last_ok);off();}
static void le(unsigned char*p,uint64_t n,unsigned k){while(k--){*p++=(unsigned char)n;n>>=8;}}
static void case_envelope(void){fresh();request(KBL_S_START,0,0,0);unsigned char b[80]={0};memcpy(b,"KBLPUT01",8);le(b+8,0x1234,8);le(b+16,4,4);le(b+24,3,8);memcpy(b+64,"/fooabc",7);CHECK(byte_hash(b+68,3,b+32)==0);
    request(KBL_S_STORE,"env1:0000000000001234",b,71);CHECK(last_ok&&g.reply_count==4&&strstr(g.reply_inline[0],"stored=1 size=3"));CHECK(!memcmp(((struct fake_context*)g.kernel.native)->data,"abc",3));
    b[68]='x';request(KBL_S_STORE,"env1:0000000000001234",b,71);CHECK(!last_ok&&!memcmp(((struct fake_context*)g.kernel.native)->data,"abc",3));b[68]='a';request(KBL_S_STORE,"env1:0000000000001235",b,71);CHECK(!last_ok);request(KBL_S_STORE,"env1:0000000000001234",b,70);CHECK(!last_ok);b[20]=1;request(KBL_S_STORE,"env1:0000000000001234",b,71);CHECK(!last_ok);b[20]=0;b[64]=0;request(KBL_S_STORE,"env1:0000000000001234",b,71);CHECK(!last_ok);off();}
static void case_read_envelope(void){fresh();request(KBL_S_START,0,0,0);store_legacy();unsigned char b[80]={0};memcpy(b,"KBLPUT01",8);le(b+8,0x1234,8);le(b+16,7,4);memcpy(b+64,"/sample",7);CHECK(byte_hash(0,0,b+32)==0);request(KBL_S_READ_OPEN,"env1:0000000000001234",b,71);CHECK(last_ok&&g.exports&&g.exports->id);request(KBL_S_READ_OPEN,"env1:0000000000001235",b,71);CHECK(!last_ok);b[71]='x';le(b+24,1,8);CHECK(byte_hash(b+71,1,b+32)==0);request(KBL_S_READ_OPEN,"env1:0000000000001234",b,72);CHECK(!last_ok);off();}
static void case_failed_free(void){fresh();request(KBL_S_START,0,0,0);fail_free=1;unsigned stops=stop_calls;struct kbl_service_reply r=request(KBL_S_OFF,0,0,0);CHECK(!last_ok&&!r.stopped&&stop_calls==stops+1&&g.session.context&&!g.kernel.native&&g.pools);CHECK(!protocol.can_unload(&protocol));fail_free=0;off();CHECK(stop_calls==stops+1);}
static void case_transport(void){fresh();request(KBL_S_START,0,0,0);store_legacy();char path[64];hexpath(path,"/sample");request(KBL_S_READ_OPEN,path,0,0);char alias[32];sprintf(alias,"kbl.%016llx",(unsigned long long)g.exports->id);quiescent=0;fail_write=1;unsigned finals=final_calls;struct kbl_service_reply r={sizeof(r),0,0,0};CHECK(protocol.request(&protocol,KBL_S_FETCH,(unsigned char*)alias,strlen(alias),0,0,&link,&r)==KBL_S_IO);CHECK(abort_calls==1&&final_calls==finals&&!r.response_sent&&g.pending_work&&g.transport_hold);CHECK(!protocol.can_unload(&protocol));quiescent=1;fail_write=0;off();CHECK(!g.pending_work&&!g.transport_hold);}
static void case_attributes(void){fresh();struct kbl_elf_allocation m={0};missing_attr=1;CHECK(elf_allocate(0,4096,4096,&m)!=0&&!m.base&&!g.pools);missing_attr=0;CHECK(elf_allocate(0,4096,4096,&m)==0);CHECK(find_pages(&m));CHECK((page_at((uintptr_t)m.base,m.bytes)->attr&(KEFI_XP|KEFI_RO))==KEFI_XP);CHECK(elf_protect(0,&m,0,4096,KBL_ELF_R|KBL_ELF_X)==0);CHECK((page_at((uintptr_t)m.base,m.bytes)->attr&(KEFI_XP|KEFI_RO))==KEFI_RO);CHECK(elf_protect(0,&m,0,4096,7)!=0);elf_release(0,&m);CHECK(!g.pages&&!g.pools);deny_attr=1;unsigned freed=page_frees;CHECK(elf_allocate(0,4096,4096,&m)!=0);CHECK(g.pages&&g.pools&&page_frees==freed);deny_attr=0;off();}
static void case_permission_restore_failure(void){fresh();struct kbl_elf_allocation m={0};CHECK(elf_allocate(0,4096,4096,&m)==0);CHECK(elf_protect(0,&m,0,4096,0)==0);unsigned freed=page_frees;deny_clear=1;elf_release(0,&m);CHECK(!m.base&&g.pages&&g.pages->released&&page_frees==freed);CHECK((page_at(g.pages->raw,4096)->attr&(KEFI_RP|KEFI_RO))==(KEFI_RP|KEFI_RO));request(KBL_S_OFF,0,0,0);CHECK(!last_ok&&page_frees==freed&&!protocol.can_unload(&protocol));deny_clear=0;deny_get=1;request(KBL_S_OFF,0,0,0);CHECK(!last_ok&&page_frees==freed&&g.pages);deny_get=0;off();CHECK(page_frees==freed+1);}
static void case_unload(void){fresh();request(KBL_S_START,0,0,0);CHECK(li.unload((void*)0x1234)==KEFI_ACCESS_DENIED);off();fail_uninstall=1;CHECK(li.unload((void*)0x1234)==KEFI_ACCESS_DENIED&&g.installed);fail_uninstall=0;CHECK(li.unload((void*)0x1234)==0&&!g.installed&&!li.unload);}
static void case_final_error(void){fresh();quiescent=0;fail_final=1;struct kbl_service_reply r=request(KBL_S_STATUS,0,0,0);CHECK(r.response_sent&&r.transport_error&&!r.stopped&&!protocol.can_unload(&protocol));fail_final=0;quiescent=1;off();}
static void case_start_failure(void){fresh();fail_start=1;request(KBL_S_START,0,0,0);CHECK(!last_ok&&g.session.phase==KBL_FAULTED&&g.session.context&&g.kernel.native);fail_start=0;off();}
static void case_idle_page_cleanup(void){fresh();struct kbl_elf_allocation m={0};CHECK(elf_allocate(0,4096,4096,&m)==0);fail_free=1;elf_release(0,&m);CHECK(!m.base&&g.pages&&g.pools&&!protocol.can_unload(&protocol));request(KBL_S_OFF,0,0,0);CHECK(!last_ok);fail_free=0;off();CHECK(!g.pages&&!g.pools);}
static void case_legacy_link_error(void){fresh();link.bytes=offsetof(struct kbl_firmware_link,quiescent);fail_final=1;request(KBL_S_STATUS,0,0,0);CHECK(g.transport_hold);fail_final=0;request(KBL_S_OFF,0,0,0);CHECK(!last_ok&&g.transport_hold);link.bytes=sizeof(link);off();}
static void case_invalid_provider_arguments(void){fresh();struct kbl_elf_allocation m={0};CHECK(elf_allocate(0,0,4096,&m)!=0);CHECK(elf_allocate(0,1,4096,&m)!=0);CHECK(elf_allocate(0,4096,(SIZE_MAX>>1)+1,&m)!=0);CHECK(!g.pools&&!g.pages);char *path=0;CHECK(!path_decode((unsigned char*)"c080",4,&path));CHECK(!path_decode((unsigned char*)"eda080",6,&path));CHECK(!path_decode((unsigned char*)"f4908080",8,&path));CHECK(!path_decode((unsigned char*)"00",2,&path));CHECK(path_decode((unsigned char*)"2fe4b8ad",8,&path));path_release(path);off();}
static void case_dynamic_exports(void){
    fresh();request(KBL_S_START,0,0,0);store_legacy();char path[64];hexpath(path,"/sample");
    for(unsigned i=0;i<9;++i){request(KBL_S_READ_OPEN,path,0,0);CHECK(last_ok&&g.exports);}
    size_t count=0;for(struct exported*e=g.exports;e;e=e->next)++count;CHECK(count==9);
    struct exported*e=g.exports;char alias[32];sprintf(alias,"kbl.%016llx",(unsigned long long)e->id);
    struct kbl_reader reader={0};CHECK(fetch_open(0,alias,&reader)==KBL_OK&&e->borrowers==1);
    request(KBL_S_READ_CLOSE,alias,0,0);CHECK(!last_ok&&g.exports==e&&e->borrowers==1);
    reader.close(reader.opaque);CHECK(!e->borrowers);request(KBL_S_READ_CLOSE,alias,0,0);CHECK(last_ok);
    count=0;for(e=g.exports;e;e=e->next)++count;CHECK(count==8);off();CHECK(!g.exports);
}
static void case_dynamic_store_size_and_path(void){
    fresh();request(KBL_S_START,0,0,0);const size_t pn=513,dn=16u*1024u*1024u+1,total=64+pn+dn;
    unsigned char *b=calloc(1,total);CHECK(b);memcpy(b,"KBLPUT01",8);le(b+8,0x1234,8);le(b+16,pn,4);le(b+24,dn,8);
    b[64]='/';memset(b+65,'a',pn-1);memset(b+64+pn,'z',dn);CHECK(byte_hash(b+64+pn,dn,b+32)==KBL_OK);
    large_store_expected=dn;request(KBL_S_STORE,"env1:0000000000001234",b,total);
    CHECK(last_ok&&large_store_accepted==dn&&strlen(stored_path)==pn&&!memcmp(stored_path,b+64,pn));
    free(b);off();
}
static void case_dynamic_reply(void){
    fresh();link.flags=KBL_LINK_DEFER_INFO;request(KBL_S_START,0,0,0);
    g.link=&link;for(unsigned i=0;i<25;++i)output_info(0,"KBL1 dynamic=ok");g.link=0;
    CHECK(g.reply_valid&&!g.reply_overflow&&g.reply_lines&&g.reply_count>24);
    uint64_t gen=g.reply_generation;request(KBL_S_REPLY_HEADER,0,0,0);CHECK(last_ok&&g.reply_generation==gen);
    char query[40];sprintf(query,"%016llx:24",(unsigned long long)gen);request(KBL_S_REPLY_PAGE,query,0,0);CHECK(last_ok&&!strcmp(last_final,"KBL1 dynamic=ok"));
    off();CHECK(!g.reply_lines&&!g.reply_capacity);request(KBL_S_REPLY_PAGE,query,0,0);CHECK(!last_ok);
}
static void case_adb_allocator_alignment(void){
    fresh();void *p=adb_allocate(0,129,128u*1024u);CHECK(p&&!((uintptr_t)p% (128u*1024u))&&g.adb_pools);
    memset(p,0xa5,129);CHECK(!adb_allocate(0,1,3)&&!adb_allocate(0,SIZE_MAX,128u*1024u));
    adb_release(0,p,129);CHECK(!g.adb_pools&&!g.adb_error);off();
}
int main(void){case_cache();case_snapshot_fetch();case_envelope();case_read_envelope();case_failed_free();case_transport();case_attributes();case_permission_restore_failure();case_unload();case_final_error();case_start_failure();case_idle_page_cleanup();case_legacy_link_error();case_invalid_provider_arguments();case_dynamic_exports();case_dynamic_store_size_and_path();case_dynamic_reply();case_adb_allocator_alignment();CHECK(allocs==frees&&page_allocs==page_frees);printf("{\"scope\":\"fake EFI and fake native backend; real controller/fetch/SHA/partition modules\",\"cases\":%u,\"assertions\":%u,\"pool_allocations\":%u,\"page_allocations\":%u}\n",cases,checks,allocs,page_allocs);return 0;}
