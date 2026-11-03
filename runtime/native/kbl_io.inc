/* SPDX-License-Identifier: MIT -- explicit per-connection transactions. */
struct io_session {
    struct native*c;struct native_session*owner;uint64_t file_owner;
    struct kbl_program_api api;
};
static struct native*io_enter(void*opaque){struct io_session*s=opaque;
    if(!s||!s->c||s->c->io_owner!=s||s->c->in_job||s->c->polling||s->c->io_entered||v_el(s->c))return 0;
    s->c->io_entered=1;return s->c;
}
static int io_leave(struct native*c,int rc){c->io_entered=0;return v_el(c)?-KBL_EIO:rc;}
static int io_open(void*s,const char*p,unsigned f,kbl_file_handle*h){struct native*c=io_enter(s);if(!c)return -KBL_EBUSY;return io_leave(c,v_open(c,p,f,h));}
static int io_read(void*s,kbl_file_handle h,void*p,size_t n,size_t*d){struct native*c=io_enter(s);if(!c)return -KBL_EBUSY;return io_leave(c,v_read(c,h,p,n,d));}
static int io_write(void*s,kbl_file_handle h,const void*p,size_t n,size_t*d){struct native*c=io_enter(s);if(!c)return -KBL_EBUSY;return io_leave(c,v_write(c,h,p,n,d));}
static int io_seek(void*s,kbl_file_handle h,int64_t o,unsigned w,uint64_t*p){struct native*c=io_enter(s);if(!c)return -KBL_EBUSY;return io_leave(c,v_seek(c,h,o,w,p));}
static int io_close(void*s,kbl_file_handle h){struct native*c=io_enter(s);if(!c)return -KBL_EBUSY;return io_leave(c,v_close(c,h));}
static int io_stat(void*s,const char*p,struct kbl_file_stat*b){struct native*c=io_enter(s);if(!c)return -KBL_EBUSY;return io_leave(c,v_stat(c,p,b));}
static int io_mkdir(void*s,const char*p){struct native*c=io_enter(s);if(!c)return -KBL_EBUSY;return io_leave(c,v_mkdir(c,p));}
static int io_unlink(void*s,const char*p){struct native*c=io_enter(s);if(!c)return -KBL_EBUSY;return io_leave(c,v_unlink(c,p));}
static int io_sync(void*s,kbl_file_handle h){struct native*c=io_enter(s);if(!c)return -KBL_EBUSY;return io_leave(c,v_sync(c,h));}
static unsigned io_el(void*opaque){struct io_session*s=opaque;if(!s||!s->c||s->c->io_owner!=s)return 0;unsigned el=s->c->p.current_el(s->c->p.opaque);if(el!=s->c->el)s->c->fault=1;return el;}
static struct native_session*default_session(struct native*c){
    struct native_session*s=&c->default_session;
    if(s->cookie!=SESSION_COOKIE){s->cookie=SESSION_COOKIE;s->c=c;s->cwd=c->cwd?c->cwd:c->root;
        s->cwd_path=c->cwd_path;
        s->output=c->output;s->next=c->sessions;c->sessions=s;}
    return s;
}
int kbl_native_session_io_begin(void*opaque,void*token,const struct kbl_program_api**out){
    struct native*c=context(opaque);struct native_session*owner=session(opaque,token);
    if(out)*out=0;if(!c||!owner||!out||c->in_job||c->polling||c->fault||v_el(c))return KBL_ERROR;
    if(owner->io){if(c->io_owner!=owner->io)return KBL_ERROR;*out=&owner->io->api;return KBL_OK;}
    if(owner->state==SESSION_QUEUED||owner->state==SESSION_ACTIVE||owner->output_count)return KBL_AGAIN;
    if(!owner->io_requested){owner->ticket=next_ticket(c);if(!owner->ticket)return KBL_ERROR;owner->io_requested=1;}
    schedule(c);if(c->io_owner||c->job_active||queue_head(c)!=owner)return KBL_AGAIN;
    struct io_session*s=alloc_owned(c,sizeof(*s));if(!s){owner->ticket=0;owner->io_requested=0;schedule(c);return KBL_ERROR;}
    uint64_t id=next_job(c);if(!id){free_owned(c,s);owner->ticket=0;owner->io_requested=0;schedule(c);return KBL_ERROR;}
    s->c=c;s->owner=owner;s->file_owner=id;s->api=(struct kbl_program_api){0};s->api.abi_version=KBL_PROGRAM_ABI;s->api.struct_bytes=KBL_PROGRAM_API_FILE_BYTES;s->api.host=s;
    s->api.current_el=io_el;s->api.file_open=io_open;s->api.file_read=io_read;s->api.file_write=io_write;s->api.file_seek=io_seek;s->api.file_close=io_close;
    s->api.file_stat=io_stat;s->api.file_mkdir=io_mkdir;s->api.file_unlink=io_unlink;s->api.file_sync=io_sync;
    owner->io=s;owner->ticket=0;owner->io_requested=0;c->io_owner=s;c->file_owner=id;
    c->cwd=owner->cwd;c->cwd_path=owner->cwd_path;*out=&s->api;return KBL_OK;
}
int kbl_native_session_io_end(void*opaque,void*token,const struct kbl_program_api*api){
    struct native*c=context(opaque);struct native_session*owner=session(opaque,token);
    if(!owner||!owner->io||c->io_owner!=owner->io||&owner->io->api!=api||c->in_job||c->polling||c->io_entered)return KBL_ERROR;
    struct io_session*s=owner->io;if(close_owner_files(c,s->file_owner))return KBL_AGAIN;
    owner->cwd=c->cwd;owner->cwd_path=c->cwd_path;
    owner->io=0;c->io_owner=0;c->file_owner=0;s->c=0;free_owned(c,s);restore_default_cwd(c);schedule(c);return KBL_OK;
}
int kbl_native_io_begin(void*opaque,const struct kbl_program_api**out){
    struct native*c=context(opaque);if(!c){if(out)*out=0;return KBL_ERROR;}
    if(c->io_owner){if(out)*out=0;return KBL_AGAIN;}
    return kbl_native_session_io_begin(c,default_session(c),out);
}
int kbl_native_io_end(void*opaque,const struct kbl_program_api*api){
    struct native*c=context(opaque);return c?kbl_native_session_io_end(c,default_session(c),api):KBL_ERROR;
}
int kbl_native_io_list(void*opaque,const struct kbl_program_api*api,const char*path,uint64_t index,
                      char*name,size_t capacity,struct kbl_file_stat*st,int*end){
    struct native*c=context(opaque);if(!c||!c->io_owner||&c->io_owner->api!=api||!name||!capacity||!st||!end)return -KBL_EINVAL;
    if(!io_enter(c->io_owner))return -KBL_EBUSY;int rc=v_list(c,path,index,name,capacity,st,end);return io_leave(c,rc);
}
int kbl_native_session_close(void*opaque,void*token){
    struct native*c=context(opaque);struct native_session*s=session(opaque,token);
    if(!s||s==&c->default_session)return KBL_ERROR;
    if(c->in_job||c->polling||c->io_entered||c->fs_busy)return KBL_AGAIN;
    if(s->io&&kbl_native_session_io_end(c,s,&s->io->api)!=KBL_OK)return KBL_AGAIN;
    if(c->active_session==s){
        if(close_owner_files(c,c->file_owner))return KBL_AGAIN;
        clear_job(c);c->active_session=0;c->file_owner=0;c->output=0;c->output_count=c->output_read=0;
    }
    s->ticket=0;s->io_requested=0;free_owned(c,s->line);free_owned(c,s->stack);free_owned(c,s->output);
    struct native_session**at=&c->sessions;while(*at&&*at!=s)at=&(*at)->next;
    if(*at)*at=s->next;s->cookie=0;s->c=0;restore_default_cwd(c);free_owned(c,s->cwd_path);s->cwd_path=0;
    free_owned(c,s);--c->session_count;schedule(c);return KBL_OK;
}
int kbl_native_service_submit(void*c,const unsigned char*p,size_t n,uint64_t*j){return backend_submit(c,p,n,j);}
int kbl_native_service_poll(void*c,uint64_t j,unsigned b,unsigned char*p,size_t n,size_t*w,int*d,int*r){return backend_poll(c,j,b,p,n,w,d,r);}
