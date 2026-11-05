/* Fake EFI ownership/failure model around the production driver + handoff. */
#define main legacy_driver_main
#include "test_driver.c"
#undef main
extern kefi_status KblHandoffMain(kefi_handle,struct kefi_system_table*);
unsigned kbl_handoff_test_el(void){return el;}
static struct kefi_loaded_image child_li[2];
static unsigned child_live[2],loads,helper_starts,target_starts;
static unsigned fail_load_number,fail_unload_child,fail_helper_options;
static unsigned context_alloc_failure;
static kefi_status target_status;
static kefi_status cs_allocate(unsigned type,size_t n,void**out){
    if(context_alloc_failure&&n==sizeof(struct kbl_handoff)){if(context_alloc_failure==1){kefi_status s=alloc_pool(type,n,out);CHECK(!s);}else *out=0;return KEFI_OUT_OF_RESOURCES;}
    return alloc_pool(type,n,out);
}
static unsigned char *target;static size_t target_bytes;
static const uint16_t opts[]={'x',0x4e2d,0};
static kefi_handle child_handle(unsigned i){return(void*)(uintptr_t)(0x2000+i);}
static int child_index(kefi_handle h){return h==child_handle(0)?0:h==child_handle(1)?1:-1;}
static kefi_status cs_handle(kefi_handle h,const struct kefi_guid*guid,void**out){
    int i=child_index(h);if(i<0)return handle(h,guid,out);*out=child_live[i]?&child_li[i]:0;
    return child_live[i]?0:KEFI_INVALID;
}
static kefi_status cs_load(unsigned char policy,kefi_handle parent,void*path,void*memory,size_t n,kefi_handle*out){
    CHECK(!policy&&parent==g.image&&!path);unsigned i=loads++;CHECK(i<2);
    if(i==0)CHECK(n==target_bytes&&!memcmp(memory,target,n));
    else CHECK(n==kbl_handoff_image_bytes);
    *out=0;if(loads==fail_load_number)return KEFI_ERROR(26);
    child_live[i]=1;*out=child_handle(i);child_li[i].system_table=&st;
    child_li[i].code_type=i==1&&fail_helper_options?2:1;return 0;
}
static kefi_status cs_unload(kefi_handle h){
    int i=child_index(h);CHECK(i>=0);if(fail_unload_child)return KEFI_ACCESS_DENIED;
    if(!child_live[i])return KEFI_INVALID;child_live[i]=0;return 0;
}
static kefi_status cs_start(kefi_handle h,size_t*n,uint16_t**exit_data){
    int i=child_index(h);CHECK(i>=0&&child_live[i]);*n=0;*exit_data=0;
    CHECK(!g.kernel.native&&!g.pools&&!g.pages&&!g.adb&&!g.adb_pools&&!g.usb.attached);
    if(i==0){++target_starts;CHECK(child_li[0].options_bytes==sizeof(opts)&&!memcmp(child_li[0].options,opts,sizeof(opts)));
        child_live[i]=0;return target_status;}
    ++helper_starts;kefi_status s=KblHandoffMain(h,&st);child_live[i]=0;return s;
}
static void setup_cs(void){
    fresh();request(KBL_S_START,0,0,0);CHECK(last_ok&&!g.cs.active);zero(child_li,sizeof(child_li));zero(child_live,sizeof(child_live));
    loads=helper_starts=target_starts=fail_load_number=fail_unload_child=fail_helper_options=0;
    fake_native_in_job=fake_native_job_active=context_alloc_failure=0;target_status=0;bs.allocate_pool=cs_allocate;
    bs.handle_protocol=cs_handle;bs.load_image=(void*)cs_load;bs.start_image=(void*)cs_start;bs.unload_image=(void*)cs_unload;
}
static int queue_cs(void){
    struct kbl_efi_run_result r={0};g.in_request=1;g.current_operation=KBL_S_POLL;
    int rc=np->efi_clearstart(np->opaque,target,target_bytes,opts,sizeof(opts),&r);
    g.in_request=0;g.current_operation=0;return rc;
}
static void allow_output(void){g.cs.reply_drained=1;}
static void ended_cs(void){CHECK(!g.cs.active&&!g.cs.context&&!g.cs.helper&&!g.cs.exit_data&&!child_live[0]&&!child_live[1]);off();CHECK(allocs==frees);}
int main(int argc,char**argv){
    CHECK(argc==2);FILE*f=fopen(argv[1],"rb");CHECK(f);fseek(f,0,SEEK_END);target_bytes=(size_t)ftell(f);rewind(f);
    target=malloc(target_bytes);CHECK(target&&fread(target,1,target_bytes,f)==target_bytes);fclose(f);
    setup_cs();CHECK(!queue_cs()&&loads==2&&!helper_starts&&!target_starts&&g.kernel.native);
    clearstart_step(1);CHECK(g.kernel.native&&!helper_starts); /* deferred cached output fence */
    allow_output();fake_native_in_job=1;clearstart_step(1);CHECK(g.kernel.native&&!helper_starts);
    fake_native_in_job=0;fake_native_job_active=1;clearstart_step(1);CHECK(g.kernel.native&&!helper_starts);
    fake_native_job_active=0;clearstart_step(0);CHECK(g.kernel.native&&!helper_starts); /* ACK/DMA fence */
    clearstart_step(1);CHECK(!g.kernel.native&&helper_starts==1&&target_starts==1&&g.cs.phase==CS_RETURN);ended_cs();++cases;
    for(unsigned i=1;i<=2;++i){setup_cs();fail_load_number=i;CHECK(queue_cs()<0&&g.kernel.native&&!target_starts&&g.cs.phase==CS_FAILED);
        CHECK(!g.cs.context&&!g.cs.helper);ended_cs();++cases;}
    setup_cs();fail_helper_options=1;CHECK(queue_cs()<0&&g.kernel.native&&!target_starts);ended_cs();++cases;
    setup_cs();CHECK(!queue_cs());allow_output();target_status=KEFI_ERROR(7);clearstart_step(1);
    CHECK(g.cs.phase==CS_FAILED&&target_starts==1&&!g.kernel.native);ended_cs();++cases;
    setup_cs();fail_load_number=2;fail_unload_child=1;CHECK(queue_cs()<0&&g.cs.context&&child_live[0]&&g.kernel.native&&!target_starts);
    fail_unload_child=0;CHECK(!clearstart_dispose());ended_cs();++cases;
    setup_cs();CHECK(!queue_cs());allow_output();fail_free=1;clearstart_step(1);CHECK(!target_starts&&g.cs.active);
    g.cs.steps=65535;clearstart_step(1);CHECK(g.cs.phase==CS_FAILED&&!target_starts&&g.cs.context);
    fail_free=0;CHECK(!clearstart_dispose());ended_cs();++cases;
    setup_cs();CHECK(!queue_cs());allow_output();fail_unload_child=1;clearstart_step(1);
    CHECK(g.cs.phase==CS_FAILED&&g.cs.context);fail_unload_child=0;
    /* Explicit OFF retries the ownership ledger; the next START works. */
    off();CHECK(!g.cs.context&&!g.cs.helper);request(KBL_S_START,0,0,0);CHECK(last_ok);ended_cs();++cases;
    for(unsigned i=1;i<=2;++i){setup_cs();context_alloc_failure=i;CHECK(queue_cs()==-KBL_ENOMEM&&g.kernel.native&&!g.cs.context&&!target_starts);ended_cs();}
    free(target);printf("{\"passed\":true,\"cases\":10,\"assertions\":%u,\"targetStarts\":%u,\"hardware\":false}\n",checks,target_starts);return 0;
}
