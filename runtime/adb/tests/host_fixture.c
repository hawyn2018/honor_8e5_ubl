/* Test-only host platform and shell-command models; real native external VFS. */
#define main original_vfs_main
#include "../../native/tests/test_vfs.c"
#undef main
#include "../kbl_adb.h"
#if defined(_WIN32)
#define EXPORT __declspec(dllexport)
#else
#define EXPORT __attribute__((visibility("default")))
#endif
static struct native native_context;
static struct kbl_adb*server;
static unsigned notifications,model_active,model_rc;
static unsigned backend_alive,backend_stops,backend_calls_after_stop,model_submits,model_polls,file_begins,file_ends,file_end_failures;
static int reentrant_destroy;
static uint64_t model_id;
static char model_output[2048];static size_t model_bytes,model_at;
static int model_submit(void*p,const unsigned char*line,size_t n,uint64_t*out){
    if(!backend_alive){++backend_calls_after_stop;return KBL_ERROR;}++model_submits;
    (void)p;unsigned length_probe=n>=7&&!memcmp(line,"length ",7);if(model_active||!n||(n>511&&!length_probe))return KBL_AGAIN;model_active=1;++model_id;*out=model_id;model_rc=0;model_at=model_bytes=0;
    if(length_probe)model_bytes=(size_t)sprintf(model_output,"%zu\n",n);
    else if(n>=5&&!memcmp(line,"echo ",5)){model_bytes=n-5;copy(model_output,line+5,model_bytes);model_output[model_bytes++]='\n';}
    else if(n==2&&!memcmp(line,"el",2)){copy(model_output,"EL1\n",4);model_bytes=4;}
    else if(n==5&&!memcmp(line,"false",5))model_rc=7;
    else if(n==5&&!memcmp(line,"exit7",5)){copy(model_output,"status-seven\n",13);model_bytes=13;model_rc=7;}
    else if(n==4&&!memcmp(line,"hang",4))model_rc=999;
    else if(n==3&&!memcmp(line,"big",3)){model_rc=998;model_bytes=131072;}
    else{copy(model_output,"host shell model: unsupported\n",30);model_bytes=30;model_rc=127;}
    return KBL_OK;
}
static int model_poll(void*p,uint64_t job,unsigned budget,unsigned char*out,size_t capacity,size_t*n,int*done,int*rc){
    if(!backend_alive){++backend_calls_after_stop;return KBL_ERROR;}++model_polls;
    (void)p;(void)budget;if(!model_active||job!=model_id)return KBL_ERROR;*n=0;*done=0;*rc=0;if(model_rc==999)return KBL_OK;
    size_t amount=model_bytes-model_at;if(amount>capacity)amount=capacity;
    if(model_rc==998){for(size_t i=0;i<amount;++i)out[i]='X';}else copy(out,model_output+model_at,amount);model_at+=amount;*n=amount;
    if(model_at==model_bytes){*done=1;*rc=model_rc==998?0:(int)model_rc;model_active=0;}return KBL_OK;
}
static int file_list(void*token,const char*path,uint64_t index,char*name,size_t capacity,struct kbl_file_stat*st,int*end){
    return kbl_native_io_list(&native_context,token,path,index,name,capacity,st,end);
}
static int file_begin(void*p,struct kbl_adb_files*out){(void)p;if(!backend_alive){++backend_calls_after_stop;return KBL_ERROR;}++file_begins;const struct kbl_program_api*api=0;int rc=kbl_native_io_begin(&native_context,&api);
    if(!rc)*out=(struct kbl_adb_files){(void*)api,api,file_list};return rc;}
static int file_end(void*p,struct kbl_adb_files*files){(void)p;if(!backend_alive){++backend_calls_after_stop;return KBL_ERROR;}++file_ends;
    if(file_end_failures){--file_end_failures;return KBL_AGAIN;}int rc=kbl_native_io_end(&native_context,files->api);if(!rc)zero(files,sizeof(*files));return rc;}
static void stop_request(void*p){(void)p;++notifications;reentrant_destroy=kbl_adb_destroy(server);}
static int token(void*p,unsigned char*out){(void)p;for(unsigned i=0;i<20;++i)out[i]=(unsigned char)(i+1);return 0;}
static int verify(void*p,const unsigned char*t,const void*signature,size_t n){(void)p;(void)t;return n==3&&!memcmp(signature,"yes",3)?0:-1;}
#ifdef KBL_ADB_FIXTURE_MULTI
static void fixture_multi_reset(void);
static int fixture_session_create(void*,void**);
static int fixture_session_close(void*,void*);
static int fixture_session_submit(void*,void*,const unsigned char*,size_t,uint64_t*);
static int fixture_session_poll(void*,void*,uint64_t,unsigned,unsigned char*,size_t,size_t*,int*,int*);
static int fixture_session_files_begin(void*,void*,struct kbl_adb_files*);
static int fixture_session_files_end(void*,void*,struct kbl_adb_files*);
#endif
EXPORT int fixture_start(unsigned auth){
    if(server)return -1;setup(&native_context);native_context.in_job=native_context.polling=0;notifications=model_active=0;
    backend_alive=1;backend_stops=backend_calls_after_stop=model_submits=model_polls=file_begins=file_ends=file_end_failures=0;
    struct kbl_adb_config c={0};c.allocate=memory;c.release=release;c.current_el=current;c.submit=model_submit;c.poll_job=model_poll;
    c.files_begin=file_begin;c.files_end=file_end;c.stop_request=stop_request;c.explicit_usb_debug=1;
#ifdef KBL_ADB_FIXTURE_MULTI
    fixture_multi_reset();c.session_create=fixture_session_create;c.session_close=fixture_session_close;
    c.session_submit=fixture_session_submit;c.session_poll_job=fixture_session_poll;
    c.session_files_begin=fixture_session_files_begin;c.session_files_end=fixture_session_files_end;
#endif
    if(auth){c.auth_token=token;c.auth_verify=verify;}return kbl_adb_create(&c,&server);
}
EXPORT int fixture_feed(const void*p,size_t n,size_t*consumed){return kbl_adb_feed(server,p,n,consumed);}
EXPORT int fixture_poll(void){return kbl_adb_poll(server,2000);}
EXPORT int fixture_output(const void**out,size_t*n){return kbl_adb_output(server,out,n);}
EXPORT int fixture_done(size_t n,int status){return kbl_adb_output_done(server,n,status);}
EXPORT int fixture_ready(void){return kbl_adb_stop_ready(server);}
EXPORT int fixture_request_stop(void){return kbl_adb_request_stop(server);}
#ifndef KBL_ADB_FIXTURE_LEGACY_API
EXPORT int fixture_core_status(unsigned q){switch(q){
    case 0:return kbl_adb_healthy(server);case 1:return kbl_adb_native_quiescent(server);case 2:return kbl_adb_parked(server);
    case 3:return kbl_adb_stop_ready(server);case 4:return kbl_adb_stop_requested(server);case 5:return (int)model_submits;
    case 6:return (int)model_polls;case 7:return (int)file_begins;case 8:return (int)file_ends;
    case 9:return (int)backend_calls_after_stop;case 10:return (int)backend_stops;default:return -1;}}
EXPORT int fixture_note_native_stopped(void){return kbl_adb_native_stopped(server);}
EXPORT int fixture_stop_native_model(void){if(!kbl_adb_native_quiescent(server))return KBL_AGAIN;
    backend_alive=0;model_active=0;++backend_stops;return kbl_adb_native_stopped(server);}
EXPORT int fixture_start_native_model(void){backend_alive=1;return 0;}
EXPORT int fixture_resume(void){return kbl_adb_resume(server);}
EXPORT void fixture_fail_file_ends(unsigned count){file_end_failures=count;}
#endif
EXPORT int fixture_reentrant_result(void){return reentrant_destroy;}
EXPORT unsigned fixture_notifications(void){return notifications;}
EXPORT unsigned fixture_io_active(void){return native_context.io_owner!=0;}
EXPORT void fixture_level(unsigned value){level=value;}
EXPORT int fixture_disconnect(void){return kbl_adb_disconnect(server);}
EXPORT int fixture_finish(void){int rc=kbl_adb_destroy(server);if(rc)return rc;server=0;native_context.in_job=native_context.polling=1;finish(&native_context);return allocations?1:0;}
EXPORT size_t fixture_size(void){return sizeof(struct kbl_program_api);}
