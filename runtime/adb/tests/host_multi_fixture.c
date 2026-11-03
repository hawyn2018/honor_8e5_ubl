/* Channel-routing/native-lease scheduler models. Real ADB wire and native VFS. */
#define KBL_ADB_FIXTURE_MULTI 1
#include "host_fixture.c"
struct model_session {struct model_session*next;uint64_t id,job,ticket;unsigned request,ran,big,rc;size_t count,at;char command[512],output[2048],cwd[64];const struct kbl_program_api*api;};
static struct model_session *model_sessions,*model_owner;
static uint64_t multi_sequence,multi_jobs,multi_tickets;
static unsigned multi_live,multi_created,multi_closed,multi_begins,multi_ends,multi_executed;
static void fixture_multi_reset(void){CHECK(!model_sessions);model_owner=0;multi_sequence=multi_jobs=multi_tickets=0;multi_live=multi_created=multi_closed=multi_begins=multi_ends=multi_executed=0;}
static void model_schedule(void){if(model_owner)return;for(struct model_session*x=model_sessions;x;x=x->next)if(x->request&&(!model_owner||x->ticket<model_owner->ticket))model_owner=x;}
static int fixture_session_create(void*p,void**out){(void)p;if(!backend_alive){++backend_calls_after_stop;return KBL_ERROR;}*out=0;
    struct model_session*x=memory(0,sizeof(*x),16);if(!x)return KBL_ERROR;zero(x,sizeof(*x));x->id=++multi_sequence;copy(x->cwd,"/",2);x->next=model_sessions;model_sessions=x;*out=x;++multi_live;++multi_created;return 0;}
static int fixture_session_files_end(void*p,void*session,struct kbl_adb_files*f){(void)p;struct model_session*x=session;if(!backend_alive){++backend_calls_after_stop;return KBL_ERROR;}
    int r=file_end(0,f);if(r)return r;x->api=0;x->request=0;x->ticket=0;if(model_owner==x)model_owner=0;++multi_ends;model_schedule();return 0;}
static int fixture_session_close(void*p,void*session){struct model_session*x=session,**at;if(!backend_alive){++backend_calls_after_stop;return KBL_ERROR;}
    if(x->api){struct kbl_adb_files files={(void*)x->api,x->api,file_list};int r=fixture_session_files_end(p,x,&files);if(r)return KBL_AGAIN;}
    if(model_owner==x)model_owner=0;for(at=&model_sessions;*at&&*at!=x;at=&(*at)->next){}CHECK(*at==x);*at=x->next;--multi_live;++multi_closed;release(0,x,sizeof(*x));model_schedule();return 0;}
static int fixture_session_submit(void*p,void*session,const unsigned char*line,size_t n,uint64_t*out){(void)p;struct model_session*x=session;if(!backend_alive){++backend_calls_after_stop;return KBL_ERROR;}
    if(x->request||!n||n>=sizeof(x->command))return KBL_AGAIN;copy(x->command,line,n);x->command[n]=0;x->job=++multi_jobs;x->request=1;x->ticket=++multi_tickets;x->ran=x->at=x->count=x->rc=x->big=0;*out=x->job;++model_submits;model_schedule();return 0;}
static void model_execute(struct model_session*x){if(x->ran||equal(x->command,"hang"))return;x->ran=1;++multi_executed;
    if(!memcmp(x->command,"echo ",5)){x->count=length(x->command+5);copy(x->output,x->command+5,x->count);x->output[x->count++]='\n';}
    else if(!memcmp(x->command,"cd ",3)){size_t n=length(x->command+3);CHECK(n<sizeof(x->cwd));copy(x->cwd,x->command+3,n+1);}
    else if(equal(x->command,"pwd")){x->count=length(x->cwd);copy(x->output,x->cwd,x->count);x->output[x->count++]='\n';}
    else if(equal(x->command,"el")){copy(x->output,"EL1\n",4);x->count=4;}
    else if(equal(x->command,"false"))x->rc=7;
    else if(equal(x->command,"exit7")){copy(x->output,"status-seven\n",13);x->count=13;x->rc=7;}
    else if(equal(x->command,"big")){x->big=1;x->count=131072;}
    else {copy(x->output,"unsupported\n",12);x->count=12;x->rc=127;}
}
static int fixture_session_poll(void*p,void*session,uint64_t job,unsigned budget,unsigned char*out,size_t cap,size_t*n,int*done,int*rc){(void)p;(void)budget;struct model_session*x=session;*n=0;*done=0;*rc=0;
    if(!backend_alive){++backend_calls_after_stop;return KBL_ERROR;}++model_polls;if(x->request!=1||x->job!=job)return KBL_ERROR;model_schedule();
    if(model_owner&&model_owner->request==1)model_execute(model_owner);if(model_owner!=x||!x->ran)return 0;
    size_t amount=x->count-x->at;if(amount>cap)amount=cap;if(x->big){for(size_t i=0;i<amount;++i)out[i]='X';}else copy(out,x->output+x->at,amount);x->at+=amount;*n=amount;
    if(x->at==x->count){*done=1;*rc=(int)x->rc;x->request=0;x->ticket=0;model_owner=0;model_schedule();}return 0;
}
static int fixture_session_files_begin(void*p,void*session,struct kbl_adb_files*out){(void)p;struct model_session*x=session;if(!backend_alive){++backend_calls_after_stop;return KBL_ERROR;}
    if(x->api){*out=(struct kbl_adb_files){(void*)x->api,x->api,file_list};return 0;}if(!x->request){x->request=2;x->ticket=++multi_tickets;}if(x->request!=2)return KBL_AGAIN;model_schedule();if(model_owner!=x)return KBL_AGAIN;
    int r=file_begin(0,out);if(r)return r;x->api=out->api;++multi_begins;return 0;
}
EXPORT unsigned fixture_multi_state(unsigned which){switch(which){case 0:return multi_live;case 1:return multi_created;case 2:return multi_closed;case 3:return multi_begins;case 4:return multi_ends;case 5:return multi_executed;default:return 0;}}
