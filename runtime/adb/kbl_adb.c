/* SPDX-License-Identifier: MIT
 * Native ADB wire/service state machine. No sockets, USB ownership or Linux
 * syscall ABI. Protocol references are recorded in README.md. */
#include "kbl_adb.h"
#define ID(a,b,c,d) ((uint32_t)(a)|((uint32_t)(b)<<8)|((uint32_t)(c)<<16)|((uint32_t)(d)<<24))
#define CNXN ID('C','N','X','N')
#define OPEN ID('O','P','E','N')
#define WRTE ID('W','R','T','E')
#define OKAY ID('O','K','A','Y')
#define CLSE ID('C','L','S','E')
#define AUTH ID('A','U','T','H')
#define STAT ID('S','T','A','T')
#define STA2 ID('S','T','A','2')
#define LST2 ID('L','S','T','2')
#define LIST ID('L','I','S','T')
#define LIS2 ID('L','I','S','2')
#define DENT ID('D','E','N','T')
#define DNT2 ID('D','N','T','2')
#define SEND ID('S','E','N','D')
#define RECV ID('R','E','C','V')
#define DATA ID('D','A','T','A')
#define DONE ID('D','O','N','E')
#define FAIL ID('F','A','I','L')
#define QUIT ID('Q','U','I','T')
#define VERSION UINT32_C(0x01000001)
#define COOKIE UINT64_C(0x4b424c4144423031)
#define INITIAL_SERVICE_CHANNELS 8u
#define OFF_CHANNEL INITIAL_SERVICE_CHANNELS
#define INITIAL_CHANNELS (INITIAL_SERVICE_CHANNELS+1u)
struct control {struct control *next;uint32_t command,a,b,n;unsigned char data[];};
struct channel {
    uint32_t local,remote;unsigned used,kind,v2,interactive,echo,last_cr,waiting,closing,close_queued;
    unsigned char output_inline[KBL_ADB_MAX_PAYLOAD],*output;size_t output_bytes,output_capacity;
    unsigned char *input;size_t input_bytes,input_capacity;
    char *line;size_t line_bytes,line_capacity;unsigned eof,frame_id,exit_pending,exit_code,prompt_pending,notice_pending,stop_fail_pending;uint32_t frame_left;unsigned char shell_header[5];size_t shell_header_bytes;
    struct kbl_adb_files files;unsigned files_active,files_release; kbl_file_handle file;
    void *session;unsigned session_active,peer_closed;
    uint64_t job;unsigned job_active,pending_submit;char *pending_line;size_t pending_capacity;
    unsigned sync_state,list_v2,commit_phase;uint64_t list_index;char *path;size_t path_capacity;uint32_t send_left;
};
struct kbl_adb {
    uint64_t cookie;struct kbl_adb_config c;unsigned el,fault,connected,authorized,stop,in_call,native_stopped;
    uint64_t generation,rx_generation;
    uint32_t version,max_payload,next_id;
    unsigned char token[20],rx[24+KBL_ADB_MAX_PAYLOAD],tx[24+KBL_ADB_MAX_PAYLOAD];
    size_t rx_bytes,rx_need,tx_bytes,tx_offset;unsigned rx_complete,tx_borrowed;
    struct control *control_head,*control_tail;size_t control_count;
    struct channel inline_channels[INITIAL_CHANNELS],*channels;size_t channel_capacity,poll_cursor,output_cursor;uint64_t job;unsigned job_active;
};
static void zero(void*p,size_t n){unsigned char*d=p;while(n--)*d++=0;}
static void copy(void*d_,const void*s_,size_t n){unsigned char*d=d_;const unsigned char*s=s_;while(n--)*d++=*s++;}
static void shift(unsigned char*p,size_t n,size_t used){for(size_t i=used;i<n;++i)p[i-used]=p[i];}
static size_t length(const char*p){size_t n=0;while(p[n])++n;return n;}
static int equal(const char*a,const char*b){while(*a&&*a==*b){++a;++b;}return *a==*b;}
static uint32_t get32(const void*p_){const unsigned char*p=p_;return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24);}
static void put32(void*p_,uint32_t n){unsigned char*p=p_;for(unsigned i=0;i<4;++i){p[i]=(unsigned char)n;n>>=8;}}
static void put64(void*p_,uint64_t n){unsigned char*p=p_;for(unsigned i=0;i<8;++i){p[i]=(unsigned char)n;n>>=8;}}
static int same_el(struct kbl_adb*s){if(!s||s->cookie!=COOKIE)return 0;if(s->c.current_el(s->c.opaque)!=s->el){s->fault=1;return 0;}return 1;}
static int valid(struct kbl_adb*s){return s&&!s->fault&&same_el(s);}
static int grow(struct kbl_adb*s,void **buffer,size_t *capacity,size_t needed,size_t used,void *inline_buffer){
    if(needed<=*capacity)return 1;size_t next=*capacity?*capacity:64;
    while(next<needed){if(next>SIZE_MAX/2){next=needed;break;}next*=2;}
    void *p=s->c.allocate(s->c.opaque,next,16);if(!p)return 0;
    if(used)copy(p,*buffer,used);
    if(*buffer&&*buffer!=inline_buffer)s->c.release(s->c.opaque,*buffer,*capacity);
    *buffer=p;*capacity=next;return 1;
}
static void release_buffers(struct kbl_adb*s,struct channel*c){
    if(c->output&&c->output!=c->output_inline)s->c.release(s->c.opaque,c->output,c->output_capacity);
    if(c->input)s->c.release(s->c.opaque,c->input,c->input_capacity);
    if(c->line)s->c.release(s->c.opaque,c->line,c->line_capacity);
    if(c->pending_line)s->c.release(s->c.opaque,c->pending_line,c->pending_capacity);
    if(c->path)s->c.release(s->c.opaque,c->path,c->path_capacity);
}
static int advance_generation(struct kbl_adb*s){if(s->generation==UINT64_MAX){s->fault=1;return KBL_ERROR;}++s->generation;return KBL_OK;}
static void clear_controls(struct kbl_adb*s){
    while(s->control_head){struct control*q=s->control_head;s->control_head=q->next;s->c.release(s->c.opaque,q,sizeof(*q)+q->n);}
    s->control_tail=0;s->control_count=0;
}
static int control(struct kbl_adb*s,uint32_t cmd,uint32_t a,uint32_t b,const void*p,size_t n){
    if(n>s->max_payload||n>SIZE_MAX-sizeof(struct control)||s->control_count==SIZE_MAX||(n&&!p)){s->fault=1;return KBL_ERROR;}
    struct control*q=s->c.allocate(s->c.opaque,sizeof(*q)+n,16);if(!q){s->fault=1;return KBL_ERROR;}
    q->next=0;q->command=cmd;q->a=a;q->b=b;q->n=(uint32_t)n;if(n)copy(q->data,p,n);
    if(s->control_tail)s->control_tail->next=q;else s->control_head=q;s->control_tail=q;++s->control_count;return KBL_OK;
}
static int app(struct channel*c,unsigned id,const void*p,size_t n){
    size_t header=c->v2?5:0;if(n>KBL_ADB_MAX_PAYLOAD-header-c->output_bytes)return KBL_AGAIN;
    unsigned char*out=c->output+c->output_bytes;if(header){out[0]=(unsigned char)id;put32(out+1,(uint32_t)n);out+=5;}
    if(n)copy(out,p,n);c->output_bytes+=header+n;return KBL_OK;
}
static int app_text(struct channel*c,const char*t){return app(c,1,t,length(t));}
static void shell_finish(struct channel*c,int rc){c->exit_pending=c->v2;c->exit_code=(unsigned char)rc;c->closing=1;}
static int multi(const struct kbl_adb*s){return s->c.session_create!=0;}
static int close_file(struct kbl_adb*s,struct channel*c){
    if(!c->file)return KBL_OK;if(!same_el(s))return KBL_ERROR;kbl_file_handle h=c->file;
    int rc=c->files.api->file_close(c->files.api->host,h);if(rc!=-KBL_EBUSY)c->file=0;
    return !same_el(s)?KBL_ERROR:rc;
}
static int close_files(struct kbl_adb*s,struct channel*c){
    int rc=close_file(s,c);if(rc==-KBL_EBUSY)return KBL_AGAIN;
    if(c->files_active){if(!same_el(s))return KBL_AGAIN;
        int result=multi(s)?s->c.session_files_end(s->c.opaque,c->session,&c->files):s->c.files_end(s->c.opaque,&c->files);
        if(!same_el(s)||result!=KBL_OK)return KBL_AGAIN;c->files_active=0;c->files_release=0;zero(&c->files,sizeof(c->files));}
    return rc;
}
static int close_native(struct kbl_adb*s,struct channel*c){
    if(close_files(s,c)==KBL_AGAIN)return KBL_AGAIN;
    if(c->session_active){if(!same_el(s))return KBL_AGAIN;int r=s->c.session_close(s->c.opaque,c->session);
        if(!same_el(s)||r!=KBL_OK)return KBL_AGAIN;c->session=0;c->session_active=0;c->job_active=c->pending_submit=0;}
    return KBL_OK;
}
static int release_channel(struct kbl_adb*s,struct channel*c){if(close_native(s,c)==KBL_AGAIN)return KBL_AGAIN;release_buffers(s,c);zero(c,sizeof(*c));return KBL_OK;}
static struct channel*find(struct kbl_adb*s,uint32_t local,uint32_t remote){for(size_t i=0;i<s->channel_capacity;++i){struct channel*c=&s->channels[i];if(c->used&&c->local==local&&c->remote==remote)return c;}return 0;}
static struct channel*unused_channel(struct kbl_adb*s){
    for(size_t i=0;i<s->channel_capacity;++i)if(i!=OFF_CHANNEL&&!s->channels[i].used)return &s->channels[i];
    size_t maximum=SIZE_MAX/sizeof(*s->channels),old=s->channel_capacity;
    if(old==maximum)return 0;size_t next=old<=maximum/2?old*2:maximum;
    struct channel *channels=s->c.allocate(s->c.opaque,next*sizeof(*channels),16);if(!channels)return 0;
    zero(channels,next*sizeof(*channels));copy(channels,s->channels,old*sizeof(*channels));
    for(size_t i=0;i<old;++i)if(channels[i].output==s->channels[i].output_inline)channels[i].output=channels[i].output_inline;
    if(s->channels!=s->inline_channels)s->c.release(s->c.opaque,s->channels,old*sizeof(*channels));
    s->channels=channels;s->channel_capacity=next;return &channels[old];
}
static int make_channel(struct kbl_adb*s,struct channel*c,uint32_t remote,unsigned kind,unsigned v2){
    if(c->used)return KBL_AGAIN;zero(c,sizeof(*c));c->output=c->output_inline;c->output_capacity=sizeof(c->output_inline);c->used=1;c->remote=remote;c->kind=kind;c->v2=v2;
    ++s->next_id;if(!s->next_id)++s->next_id;c->local=s->next_id;return control(s,OKAY,c->local,c->remote,0,0);
}
static int session_ready(struct kbl_adb*s,struct channel*c){
    if(!multi(s)||c->session_active)return KBL_OK;if(s->stop||s->native_stopped)return KBL_ERROR;
    void*session=0;int rc=s->c.session_create(s->c.opaque,&session);if(session){c->session=session;c->session_active=1;}
    if(!valid(s))return KBL_ERROR;if(rc==KBL_OK&&!session)return KBL_ERROR;return rc;
}
static int files_begin(struct kbl_adb*s,struct channel*c){
    if(s->stop||s->native_stopped)return KBL_ERROR;if(c->files_active)return KBL_OK;
    int ready=session_ready(s,c);if(ready!=KBL_OK)return ready;
    if(!multi(s)&&(!s->c.files_begin||!s->c.files_end))return KBL_ERROR;
    int rc=multi(s)?s->c.session_files_begin(s->c.opaque,c->session,&c->files):s->c.files_begin(s->c.opaque,&c->files);
    if(!valid(s))return KBL_ERROR;if(rc!=KBL_OK)return rc;c->files_active=1;const struct kbl_program_api*a=c->files.api;
    if(!a||a->struct_bytes<KBL_PROGRAM_API_FILE_BYTES||!a->file_open||!a->file_read||!a->file_write||!a->file_close||!a->file_stat||!a->file_sync||!a->file_mkdir||!c->files.list){(void)close_files(s,c);return KBL_ERROR;}return KBL_OK;
}
static int finish_transaction(struct kbl_adb*s,struct channel*c){c->files_release=1;return close_files(s,c);}
static int sync_fail(struct kbl_adb*s,struct channel*c,const char*message){
    (void)close_file(s,c);size_t n=length(message);if(n>KBL_ADB_MAX_PAYLOAD-8)n=KBL_ADB_MAX_PAYLOAD-8;
    put32(c->output,FAIL);put32(c->output+4,(uint32_t)n);copy(c->output+8,message,n);c->output_bytes=n+8;c->closing=1;return KBL_OK;
}
static int is_off(const char*p){while(*p==' '||*p=='\t')++p;const char*word="kbl off";while(*word&&*p==*word){++p;++word;}if(*word)return 0;while(*p==' '||*p=='\t'||*p=='\r'||*p=='\n')++p;return !*p;}
static int request_stop(struct kbl_adb*s,struct channel*reply){
    if(s->stop){if(reply){if(app_text(reply,"KBL stopping; Fastboot retained\n"))reply->notice_pending=1;shell_finish(reply,0);}return KBL_OK;}
    if(advance_generation(s))return KBL_ERROR;
    unsigned first=!s->stop;s->stop=1;if(first&&s->c.stop_request)s->c.stop_request(s->c.opaque);if(!valid(s))return KBL_ERROR;
    for(size_t i=0;i<s->channel_capacity;++i){struct channel*c=&s->channels[i];if(c->used&&c!=reply){(void)close_native(s,c);c->input_bytes=0;c->pending_submit=0;c->closing=1;
            if(c->kind==1){c->notice_pending=1;shell_finish(c,130);}else if(c->kind==2)c->stop_fail_pending=1;}}
    if(reply){if(app_text(reply,"KBL stopping; Fastboot retained\n"))reply->notice_pending=1;shell_finish(reply,0);}return KBL_OK;
}
static int submit_line(struct kbl_adb*s,struct channel*c,const char*line){
    if(is_off(line))return request_stop(s,c);if(equal(line,"exit")){shell_finish(c,0);return KBL_OK;}
    if(!*line){if(c->interactive)(void)app_text(c,"# ");return KBL_OK;}if(s->stop||s->native_stopped)return KBL_ERROR;
    if(c->pending_submit)return KBL_AGAIN;size_t n=length(line);if(n==SIZE_MAX||!grow(s,(void**)&c->pending_line,&c->pending_capacity,n+1,0,0))return KBL_ERROR;
    copy(c->pending_line,line,n+1);c->pending_submit=1;return KBL_OK;
}
static int submit_pending(struct kbl_adb*s,struct channel*c){
    if(!c->pending_submit||c->job_active)return KBL_OK;if(!multi(s)&&s->job_active)return KBL_AGAIN;
    int ready=session_ready(s,c);if(ready!=KBL_OK)return ready;uint64_t job=0;
    int rc=multi(s)?s->c.session_submit(s->c.opaque,c->session,(const unsigned char*)c->pending_line,length(c->pending_line),&job):
        s->c.submit(s->c.opaque,(const unsigned char*)c->pending_line,length(c->pending_line),&job);
    if(!valid(s))return KBL_ERROR;if(rc==KBL_AGAIN)return rc;
    if(rc!=KBL_OK||!job){c->pending_submit=0;(void)app_text(c,"native command unavailable\n");if(!c->interactive)shell_finish(c,1);else c->prompt_pending=1;return KBL_OK;}
    c->pending_submit=0;c->job=job;c->job_active=1;if(!multi(s)){s->job=job;s->job_active=1;}return KBL_OK;
}
static int shell_service(const char*service,unsigned*v2,unsigned*pty,const char**command){
    const char*p=service,*word="shell";while(*word&&*p==*word){++p;++word;}if(*word||(*p!=':'&&*p!=','))return 0;
    *v2=0;*pty=1;while(*p&&*p!=':'){if(*p++!=',')return 0;const char*start=p;while(*p&&*p!=','&&*p!=':')++p;size_t n=(size_t)(p-start);
        if(n==2&&start[0]=='v'&&start[1]=='2')*v2=1;
        else if(n==3&&start[0]=='r'&&start[1]=='a'&&start[2]=='w')*pty=0;
        else if(n==3&&start[0]=='p'&&start[1]=='t'&&start[2]=='y')*pty=1;
        else if(!(n>=5&&start[0]=='T'&&start[1]=='E'&&start[2]=='R'&&start[3]=='M'&&start[4]=='='))return 0;
    }if(*p!=':')return 0;*command=p+1;return 1;
}
static int open_service(struct kbl_adb*s,uint32_t remote,const unsigned char*data,size_t n){
    /* A rejected OPEN owns no channel or native reference. Old partial OPENs
     * remain rejected after resume instead of being replayed on a new backend. */
    if(s->stop||s->rx_generation!=s->generation){
        return control(s,CLSE,0,remote,0,0);}
    if(!remote||!n||data[n-1])return control(s,CLSE,0,remote,0,0);
    for(size_t i=0;i+1<n;++i)if(!data[i])return control(s,CLSE,0,remote,0,0);
    const char*service=(const char*)data,*command=0;unsigned v2=0,pty=0;int shell=shell_service(service,&v2,&pty,&command);
    if(shell&&is_off(command)){
        struct channel*c=&s->channels[OFF_CHANNEL];if(c->used)return control(s,CLSE,0,remote,0,0);
        if(make_channel(s,c,remote,3,v2))return KBL_ERROR;return request_stop(s,c);
    }
    if(!shell&&!equal(service,"sync:"))return control(s,CLSE,0,remote,0,0);
    struct channel*c=unused_channel(s);if(!c)return control(s,CLSE,0,remote,0,0);
    if(make_channel(s,c,remote,shell?1:2,v2))return KBL_ERROR;
    if(shell){c->interactive=!*command;c->echo=c->interactive&&pty;
        if(c->interactive){
            if(!multi(s)&&s->job_active){unsigned owned=0;for(size_t i=0;i<s->channel_capacity;++i)if(&s->channels[i]!=c&&s->channels[i].used&&s->channels[i].job_active)owned=1;
                if(!owned){c->job=s->job;c->job_active=1;}}
            (void)app_text(c,c->job_active?"KBL native USB debug session; resuming native job\n":"KBL native USB debug session\n# ");return KBL_OK;}
        return submit_line(s,c,command);
    }
    /* Idle sync connections own no native file lease. Each operation acquires
     * exactly one transaction and releases it on completion/commit. */
    return KBL_OK;
}

static int connect_reply(struct kbl_adb*s){
    static const char banner[]="device::ro.product.name=kbl;ro.product.model=KBL_USB_DEBUG;ro.product.device=kbl;features=shell_v2,stat_v2,ls_v2;";
    s->authorized=1;s->connected=1;return control(s,CNXN,s->version,s->max_payload,banner,sizeof(banner)-1);
}
static int challenge(struct kbl_adb*s){if(!s->c.auth_token||s->c.auth_token(s->c.opaque,s->token)||!valid(s))return KBL_ERROR;return control(s,AUTH,1,0,s->token,20);}
static int packet(struct kbl_adb*s){
    unsigned char*h=s->rx;uint32_t cmd=get32(h),a=get32(h+4),b=get32(h+8),n=get32(h+12);unsigned char*p=h+24;
    uint32_t checksum=0;for(uint32_t i=0;i<n;++i)checksum+=p[i];
    if(get32(h+20)!=(cmd^UINT32_MAX)||n>KBL_ADB_MAX_PAYLOAD||((((cmd==CNXN?a:s->version)<VERSION)||get32(h+16))&&get32(h+16)!=checksum))return KBL_ERROR;
    if(cmd==CNXN){if(a<UINT32_C(0x01000000)||b<4096)return KBL_ERROR;
        unsigned pending=0;for(size_t i=0;i<s->channel_capacity;++i){s->channels[i].closing=s->channels[i].peer_closed=1;if(release_channel(s,&s->channels[i])==KBL_AGAIN)pending=1;}if(pending)return KBL_AGAIN;
        s->authorized=s->connected=0;s->version=a<VERSION?a:VERSION;s->max_payload=b<KBL_ADB_MAX_PAYLOAD?b:KBL_ADB_MAX_PAYLOAD;
        return s->c.auth_verify?challenge(s):connect_reply(s);
    }
    if(cmd==AUTH&&!s->authorized&&s->c.auth_verify){if(a==2&&s->c.auth_verify(s->c.opaque,s->token,p,n)==0&&valid(s))return connect_reply(s);return challenge(s);}
    if(!s->authorized)return control(s,CLSE,0,a,0,0);
    if(cmd==OPEN)return open_service(s,a,p,n);
    struct channel*c=find(s,b,a);
    if(cmd==CLSE){if(!c)return KBL_OK;if(!c->close_queued)(void)control(s,CLSE,c->local,c->remote,0,0);
        c->peer_closed=c->closing=c->close_queued=1;c->pending_submit=0;c->output_bytes=c->input_bytes=c->waiting=0;
        (void)release_channel(s,c);return KBL_OK;}
    if(!c)return cmd==WRTE?control(s,CLSE,0,a,0,0):KBL_OK;
    if(cmd==OKAY){if(!c->waiting)return KBL_ERROR;c->waiting=0;return KBL_OK;}
    if(cmd==WRTE){if(c->closing){
            /* Late stdin/CloseStdin may race a completed job. Acknowledge and
             * discard it until queued stdout and shell EXIT have drained;
             * an early control CLSE would overtake those payload frames. */
            return control(s,c->close_queued?CLSE:OKAY,c->local,c->remote,0,0);}
        if(n>SIZE_MAX-c->input_bytes||!grow(s,(void**)&c->input,&c->input_capacity,c->input_bytes+n,c->input_bytes,0))return KBL_ERROR;
        copy(c->input+c->input_bytes,p,n);c->input_bytes+=n;return control(s,OKAY,c->local,c->remote,0,0);}
    return KBL_ERROR;
}
int kbl_adb_create(const struct kbl_adb_config*c,struct kbl_adb**out){
    if(out)*out=0;if(!out||!c||!c->allocate||!c->release||!c->current_el||!c->submit||!c->poll_job||
        (!!c->auth_token!=!!c->auth_verify)||(!c->auth_verify&&!c->explicit_usb_debug))return KBL_ERROR;
    unsigned sessions=!!c->session_create+!!c->session_close+!!c->session_submit+!!c->session_poll_job+!!c->session_files_begin+!!c->session_files_end;
    if(sessions&&sessions!=6)return KBL_ERROR;
    unsigned el=c->current_el(c->opaque);if(el!=1&&el!=2)return KBL_ERROR;
    struct kbl_adb*s=c->allocate(c->opaque,sizeof(*s),16);if(!s)return KBL_ERROR;
    if(((uintptr_t)s&15)||c->current_el(c->opaque)!=el){c->release(c->opaque,s,sizeof(*s));return KBL_ERROR;}
    zero(s,sizeof(*s));s->cookie=COOKIE;s->c=*c;s->el=el;s->version=VERSION;s->max_payload=KBL_ADB_MAX_PAYLOAD;s->rx_need=24;s->generation=1;s->channels=s->inline_channels;s->channel_capacity=INITIAL_CHANNELS;*out=s;return KBL_OK;
}
static int feed_impl(struct kbl_adb*s,const void*input,size_t n,size_t*consumed){
    if(consumed)*consumed=0;if(!valid(s)||!consumed||(n&&!input))return KBL_ERROR;const unsigned char*p=input;
    if(s->rx_complete)return KBL_AGAIN;
    while(*consumed<n){if(!s->rx_bytes)s->rx_generation=s->generation;size_t amount=s->rx_need-s->rx_bytes;if(amount>n-*consumed)amount=n-*consumed;
        copy(s->rx+s->rx_bytes,p+*consumed,amount);s->rx_bytes+=amount;*consumed+=amount;
        if(s->rx_bytes==24&&s->rx_need==24){uint32_t bytes=get32(s->rx+12);if(bytes>KBL_ADB_MAX_PAYLOAD){s->fault=1;return KBL_ERROR;}s->rx_need=24+bytes;}
        if(s->rx_bytes==s->rx_need){s->rx_complete=1;break;}
    }return KBL_OK;
}
static uint32_t mode(const struct kbl_file_stat*st){return st->type==KBL_FILE_DIRECTORY?0040755u:st->type==KBL_FILE_BLOCK?0060600u:0100644u;}
static int stat_response(struct kbl_adb*s,struct channel*c,uint32_t id,const struct kbl_file_stat*st,int rc,const char*name){
    size_t name_bytes=name?length(name):0;if(name_bytes>UINT32_MAX||name_bytes>SIZE_MAX-76||!grow(s,(void**)&c->output,&c->output_capacity,76+name_bytes,0,c->output_inline))return KBL_ERROR;
    size_t n=0;if(id==STAT){put32(c->output,STAT);put32(c->output+4,rc?0:mode(st));put32(c->output+8,rc?0:(uint32_t)st->bytes);put32(c->output+12,0);n=16;}
    else{zero(c->output,76);put32(c->output,id);put32(c->output+4,rc?(uint32_t)(-rc):0);put32(c->output+24,rc?0:mode(st));put32(c->output+28,1);put64(c->output+40,rc?0:st->bytes);n=72;}
    if(name){size_t bytes=length(name);if(id==DENT){zero(c->output,20);put32(c->output,DENT);put32(c->output+4,mode(st));put32(c->output+8,(uint32_t)st->bytes);put32(c->output+16,(uint32_t)bytes);n=20;}
        else{put32(c->output+72,(uint32_t)bytes);n=76;}copy(c->output+n,name,bytes);n+=bytes;}
    c->output_bytes=n;return KBL_OK;
}
static int parents(struct kbl_adb*s,struct channel*c,const char*path){
    size_t n=length(path);if(n==SIZE_MAX)return -KBL_ENAMETOOLONG;
    char *name=s->c.allocate(s->c.opaque,n+1,16);if(!name)return -KBL_ENOMEM;copy(name,path,n+1);
    const struct kbl_program_api*a=c->files.api;int result=0;
    for(size_t i=1;i<n;++i){
        if(name[i]!='/')continue;
        name[i]=0;struct kbl_file_stat st;int rc=a->file_stat(a->host,name,&st);if(!valid(s)){result=-KBL_EIO;break;}
        if(rc==-KBL_ENOENT){
            rc=a->file_mkdir(a->host,name);if(!valid(s)){result=-KBL_EIO;break;}
            if(rc&&rc!=-KBL_EEXIST){result=rc;break;}
            rc=a->file_stat(a->host,name,&st);if(!valid(s)){result=-KBL_EIO;break;}
        }
        name[i]='/';if(rc){result=rc;break;}if(st.type!=KBL_FILE_DIRECTORY){result=-KBL_ENOTDIR;break;}
    }
    s->c.release(s->c.opaque,name,n+1);return result;
}
static int sync_step(struct kbl_adb*s,struct channel*c){
    if(c->files_release){if(close_files(s,c)==KBL_AGAIN)return KBL_OK;}
    if(c->waiting||c->output_bytes||c->closing)return KBL_OK;
    if(!c->files_active){
        if(c->input_bytes<8)return KBL_OK;
        uint32_t operation=get32(c->input),path_bytes=get32(c->input+4);
        if(operation==QUIT){shift(c->input,c->input_bytes,8);c->input_bytes-=8;c->closing=1;return KBL_OK;}
        if(operation!=STAT&&operation!=STA2&&operation!=LST2&&operation!=LIST&&operation!=LIS2&&operation!=RECV&&operation!=SEND)return sync_fail(s,c,"unsupported sync operation");
        if(!path_bytes||(uint64_t)path_bytes+8>SIZE_MAX)return sync_fail(s,c,"invalid path length");
        if(c->input_bytes<(size_t)path_bytes+8)return KBL_OK;
        int ready=files_begin(s,c);if(ready==KBL_AGAIN||ready==-KBL_EBUSY)return KBL_OK;
        if(ready!=KBL_OK)return sync_fail(s,c,"file session unavailable");
    }
    const struct kbl_program_api*a=c->files.api;if(!a)return sync_fail(s,c,"file session unavailable");
    if(c->sync_state==1){ /* RECV: one bounded chunk per explicit poll. */
        size_t n=0;size_t capacity=s->max_payload-8;int rc=a->file_read(a->host,c->file,c->output+8,capacity,&n);if(!valid(s))return KBL_ERROR;
        if(n>capacity)return sync_fail(s,c,"invalid read count");if(rc)return sync_fail(s,c,"read failed");
        if(n){put32(c->output,DATA);put32(c->output+4,(uint32_t)n);c->output_bytes=n+8;return KBL_OK;}
        if(close_file(s,c))return sync_fail(s,c,"read close failed");(void)finish_transaction(s,c);
        put32(c->output,DONE);put32(c->output+4,0);c->output_bytes=8;c->sync_state=0;return KBL_OK;
    }
    if(c->sync_state==2){ /* LIST, one entry per poll. */
        char *name=0;size_t capacity=0;struct kbl_file_stat st;int end=0,rc;
        for(;;){size_t needed=capacity?capacity+1:256;if(needed<=capacity||!grow(s,(void**)&name,&capacity,needed,0,0)){if(name)s->c.release(s->c.opaque,name,capacity);return sync_fail(s,c,"list allocation failed");}
            rc=c->files.list(c->files.token,c->path,c->list_index,name,capacity,&st,&end);
            if(!valid(s)){s->c.release(s->c.opaque,name,capacity);return KBL_ERROR;}if(rc!=-KBL_ENAMETOOLONG)break;}
        if(rc){s->c.release(s->c.opaque,name,capacity);return sync_fail(s,c,"list failed");}
        if(end){s->c.release(s->c.opaque,name,capacity);(void)finish_transaction(s,c);zero(c->output,c->list_v2?76:20);put32(c->output,DONE);c->output_bytes=c->list_v2?76:20;c->sync_state=0;return KBL_OK;}
        if(!c->list_v2&&st.bytes>UINT32_MAX){s->c.release(s->c.opaque,name,capacity);return sync_fail(s,c,"legacy size overflow; use ls_v2");}
        ++c->list_index;rc=stat_response(s,c,c->list_v2?DNT2:DENT,&st,0,name);s->c.release(s->c.opaque,name,capacity);return rc;
    }
    if(c->sync_state==3&&c->send_left){ /* SEND DATA payload can span WRTEs. */
        if(!c->input_bytes)return KBL_OK;size_t n=c->input_bytes;if(n>c->send_left)n=c->send_left;if(n>KBL_ADB_MAX_PAYLOAD)n=KBL_ADB_MAX_PAYLOAD;
        size_t done=0;int rc=a->file_write(a->host,c->file,c->input,n,&done);if(!valid(s))return KBL_ERROR;
        if(done>n)return sync_fail(s,c,"invalid write count");shift(c->input,c->input_bytes,done);c->input_bytes-=done;c->send_left-=(uint32_t)done;
        if(rc||!done)return sync_fail(s,c,"write failed after confirmed partial bytes");return KBL_OK;
    }
    if(c->input_bytes<8)return KBL_OK;uint32_t id=get32(c->input),n=get32(c->input+4);
    if(c->sync_state==3){
        if(id==DATA){shift(c->input,c->input_bytes,8);c->input_bytes-=8;c->send_left=n;return KBL_OK;}
        if(id==DONE){
            if(!c->commit_phase){int rc=a->file_sync(a->host,c->file);if(!valid(s))return KBL_ERROR;if(rc)return sync_fail(s,c,"file commit failed");c->commit_phase=1;}
            if(c->commit_phase==1){if(close_file(s,c))return sync_fail(s,c,"file close failed");c->commit_phase=2;}
            shift(c->input,c->input_bytes,8);c->input_bytes-=8;(void)finish_transaction(s,c);
            put32(c->output,OKAY);put32(c->output+4,0);c->output_bytes=8;c->sync_state=c->commit_phase=0;return KBL_OK;}
        return sync_fail(s,c,"expected SEND DATA or DONE");
    }
    if(id==QUIT){shift(c->input,c->input_bytes,8);c->input_bytes-=8;c->closing=1;return KBL_OK;}
    if(!n||(uint64_t)n>SIZE_MAX-8)return sync_fail(s,c,"invalid path length");if(c->input_bytes<(size_t)n+8)return KBL_OK;
    for(uint32_t i=0;i<n;++i)if(!c->input[(size_t)i+8])return sync_fail(s,c,"embedded NUL in path");
    if(!grow(s,(void**)&c->path,&c->path_capacity,(size_t)n+1,0,0))return sync_fail(s,c,"path allocation failed");copy(c->path,c->input+8,n);c->path[n]=0;shift(c->input,c->input_bytes,(size_t)n+8);c->input_bytes-=(size_t)n+8;
    if(id==STAT||id==STA2||id==LST2){struct kbl_file_stat st;zero(&st,sizeof(st));int rc=a->file_stat(a->host,c->path,&st);if(!valid(s))return KBL_ERROR;
        if(id==STAT&&!rc&&st.bytes>UINT32_MAX)return sync_fail(s,c,"legacy size overflow; use stat_v2");(void)finish_transaction(s,c);return stat_response(s,c,id,&st,rc,0);}
    if(id==LIST||id==LIS2){c->sync_state=2;c->list_index=0;c->list_v2=id==LIS2;return KBL_OK;}
    if(id==RECV){int rc=a->file_open(a->host,c->path,KBL_FILE_READ,&c->file);if(!valid(s))return KBL_ERROR;if(rc)return sync_fail(s,c,"read open failed");c->sync_state=1;return KBL_OK;}
    if(id==SEND){char*comma=0;for(char*p=c->path;*p;++p)if(*p==',')comma=p;if(!comma)return sync_fail(s,c,"SEND mode missing");*comma=0;
        unsigned flags=KBL_FILE_WRITE;struct kbl_file_stat st;int rc=a->file_stat(a->host,c->path,&st);if(!valid(s))return KBL_ERROR;
        if(rc||st.type!=KBL_FILE_BLOCK){rc=parents(s,c,c->path);if(rc)return sync_fail(s,c,"parent directory unavailable");flags|=KBL_FILE_CREATE|KBL_FILE_TRUNC;}
        rc=a->file_open(a->host,c->path,flags,&c->file);if(!valid(s))return KBL_ERROR;if(rc)return sync_fail(s,c,rc==-KBL_EROFS?"read-only partition":"write open failed");c->sync_state=3;return KBL_OK;}
    return sync_fail(s,c,"unsupported sync operation");
}
static int stdin_byte(struct kbl_adb*s,struct channel*c,unsigned char ch){
    if(ch=='\n'&&c->last_cr){c->last_cr=0;return KBL_OK;}c->last_cr=ch=='\r';
    if(ch==3){(void)app_text(c,multi(s)?"\r\nchannel detached; KBL environment retained\n":"\r\nchannel detached; native job retained\n");shell_finish(c,130);c->line_bytes=0;return KBL_OK;}
    if(ch==4){c->eof=1;if(c->line_bytes){if(c->line)c->line[c->line_bytes]=0;int rc=submit_line(s,c,c->line?c->line:"");c->line_bytes=0;return rc==KBL_ERROR?rc:KBL_OK;}return KBL_OK;}
    if(ch==8||ch==127){if(c->line_bytes){--c->line_bytes;if(c->echo)(void)app_text(c,"\b \b");}return KBL_OK;}
    if(ch=='\r'||ch=='\n'){
        if(c->echo)(void)app_text(c,"\r\n");if(c->line)c->line[c->line_bytes]=0;int rc=submit_line(s,c,c->line?c->line:"");c->line_bytes=0;return rc==KBL_ERROR?rc:KBL_OK;}
    if(ch<32)return KBL_OK;if(c->line_bytes>SIZE_MAX-2||!grow(s,(void**)&c->line,&c->line_capacity,c->line_bytes+2,c->line_bytes,0))return KBL_ERROR;
    if(c->echo)(void)app(c,1,&ch,1);c->line[c->line_bytes++]=(char)ch;return KBL_OK;
}
/* A queued line backpressures ordinary stdin, but an in-band Ctrl+C must
 * still cancel this channel. Inspect a bounded copy of framing state without
 * consuming/replaying the queued bytes or treating resize records as stdin. */
static int pending_interrupt(struct kbl_adb*s,struct channel*c){
    if(!c->pending_submit)return 0;
    if(!c->v2){for(size_t i=0;i<c->input_bytes;++i)if(c->input[i]==3)return stdin_byte(s,c,3);return 0;}
    unsigned frame_id=c->frame_id;uint32_t left=c->frame_left;unsigned char header[5];size_t used=c->shell_header_bytes;copy(header,c->shell_header,used);
    for(size_t i=0;i<c->input_bytes;++i){unsigned char ch=c->input[i];
        if(!left){header[used++]=ch;if(used<5)continue;frame_id=header[0];left=get32(header+1);used=0;
            if((frame_id!=0&&frame_id!=4&&frame_id!=5)||(frame_id==4&&left))return KBL_ERROR;continue;}
        if(frame_id==0&&ch==3)return stdin_byte(s,c,3);--left;
    }return 0;
}
static int shell_input(struct kbl_adb*s,struct channel*c){
    if(!c->interactive){c->input_bytes=0;return KBL_OK;}
    if(pending_interrupt(s,c))return KBL_ERROR;if(c->closing)return KBL_OK;
    size_t at=0,limit=c->input_bytes;if(limit>4096)limit=4096;
    while(at<limit&&!c->closing&&!s->stop&&!c->pending_submit){unsigned char ch=c->input[at++];
        if(!c->v2){if(stdin_byte(s,c,ch))return KBL_ERROR;continue;}
        if(!c->frame_left){c->shell_header[c->shell_header_bytes++]=ch;if(c->shell_header_bytes<5)continue;
            c->frame_id=c->shell_header[0];c->frame_left=get32(c->shell_header+1);c->shell_header_bytes=0;
            if((c->frame_id!=0&&c->frame_id!=4&&c->frame_id!=5)||(c->frame_id==4&&c->frame_left))return KBL_ERROR;
            if(c->frame_id==4&&!c->frame_left&&stdin_byte(s,c,4))return KBL_ERROR;continue;}
        if(c->frame_id==0&&stdin_byte(s,c,ch))return KBL_ERROR;--c->frame_left;
    }
    shift(c->input,c->input_bytes,at);c->input_bytes-=at;return KBL_OK;
}
static int poll_impl(struct kbl_adb*s,unsigned budget){
    if(!valid(s))return KBL_ERROR;
    if(s->rx_complete){int rc=packet(s);if(rc==KBL_ERROR){s->fault=1;return rc;}if(rc!=KBL_AGAIN){s->rx_complete=0;s->rx_bytes=0;s->rx_need=24;}}
    /* Native references are reclaimed independently of peer ACK/EXIT/CLSE.
     * Closing one channel only cancels its own session; OFF broadcasts. */
    for(size_t i=0;i<s->channel_capacity;++i){struct channel*c=&s->channels[i];
        if(c->used&&(s->stop||c->closing))(void)close_native(s,c);}
    if(!valid(s))return KBL_ERROR;
    size_t start=s->poll_cursor;s->poll_cursor=(s->poll_cursor+1)%s->channel_capacity;
    size_t owners=s->channel_capacity-1;unsigned slice=owners>budget?1:budget/(unsigned)owners;if(!slice)slice=1;
    for(size_t order=0;order<s->channel_capacity;++order){size_t index=start>=s->channel_capacity-order?start-(s->channel_capacity-order):start+order;struct channel*c=&s->channels[index];if(!c->used)continue;
        if(c->peer_closed){(void)release_channel(s,c);continue;}
        if(c->closing){
            if(!c->waiting&&!c->output_bytes&&c->stop_fail_pending){const char*t="KBL stopped during sync";put32(c->output,FAIL);put32(c->output+4,(uint32_t)length(t));copy(c->output+8,t,length(t));c->output_bytes=8+length(t);c->stop_fail_pending=0;continue;}
            if(!c->waiting&&!c->output_bytes&&c->notice_pending){(void)app_text(c,"KBL stopping; Fastboot retained\n");c->notice_pending=0;continue;}
            if(!c->waiting&&!c->output_bytes&&c->exit_pending){unsigned char code=(unsigned char)c->exit_code;(void)app(c,3,&code,1);c->exit_pending=0;continue;}
            if(!c->waiting&&!c->output_bytes&&!c->close_queued){if(close_native(s,c)==KBL_AGAIN)continue;
                if(control(s,CLSE,c->local,c->remote,0,0))return KBL_ERROR;c->close_queued=1;}
            continue;
        }
        if(c->kind==2){if(sync_step(s,c))return KBL_ERROR;continue;}
        if(c->kind!=1)continue;
        int ready=session_ready(s,c);if(ready==KBL_AGAIN)continue;
        if(ready!=KBL_OK){(void)app_text(c,"native session unavailable\n");shell_finish(c,1);continue;}
        if(shell_input(s,c))return KBL_ERROR;if(s->stop||c->closing)continue;
        int submitted=submit_pending(s,c);if(submitted==KBL_ERROR)return KBL_ERROR;
        if(c->prompt_pending&&!c->pending_submit&&!c->job_active&&!c->waiting&&!c->output_bytes){(void)app_text(c,"# ");c->prompt_pending=0;}
        if(!c->waiting&&!c->output_bytes&&c->job_active){size_t n=0;int done=0,rc=0;
            int result=multi(s)?s->c.session_poll_job(s->c.opaque,c->session,c->job,slice,c->output+(c->v2?5:0),s->max_payload-(c->v2?5:0),&n,&done,&rc):
                s->c.poll_job(s->c.opaque,c->job,slice,c->output+(c->v2?5:0),s->max_payload-(c->v2?5:0),&n,&done,&rc);
            if(!valid(s))return KBL_ERROR;if(result==KBL_AGAIN)continue;
            if(result!=KBL_OK){(void)app_text(c,"native job poll failed\n");shell_finish(c,1);continue;}
            if(n>s->max_payload-(c->v2?5:0))return KBL_ERROR;
            if(n){if(c->v2){c->output[0]=1;put32(c->output+1,(uint32_t)n);}c->output_bytes=n+(c->v2?5:0);}
            if(done){c->job_active=0;if(!multi(s))s->job_active=0;
                if(c->interactive&&!c->eof)c->prompt_pending=1;else shell_finish(c,rc);}
        }else if(!c->job_active&&!c->pending_submit&&c->eof&&!c->waiting&&!c->output_bytes)shell_finish(c,0);
    }return valid(s)?KBL_OK:KBL_ERROR;
}
static void frame(struct kbl_adb*s,uint32_t cmd,uint32_t a,uint32_t b,const void*p,size_t n){
    put32(s->tx,cmd);put32(s->tx+4,a);put32(s->tx+8,b);put32(s->tx+12,(uint32_t)n);uint32_t checksum=0;
    for(size_t i=0;i<n;++i)checksum+=((const unsigned char*)p)[i];put32(s->tx+16,s->version<VERSION?checksum:0);put32(s->tx+20,cmd^UINT32_MAX);
    if(n)copy(s->tx+24,p,n);s->tx_bytes=n+24;s->tx_offset=0;
}
static int output_impl(struct kbl_adb*s,const void**data,size_t*bytes){
    if(data)*data=0;if(bytes)*bytes=0;if(!valid(s)||!data||!bytes)return KBL_ERROR;
    if(!s->tx_bytes){if(s->control_head){struct control*q=s->control_head;frame(s,q->command,q->a,q->b,q->data,q->n);s->control_head=q->next;if(!s->control_head)s->control_tail=0;--s->control_count;s->c.release(s->c.opaque,q,sizeof(*q)+q->n);}
        else for(size_t order=0;order<s->channel_capacity;++order){size_t index=s->output_cursor>=s->channel_capacity-order?s->output_cursor-(s->channel_capacity-order):s->output_cursor+order;struct channel*c=&s->channels[index];if(c->used&&!c->peer_closed&&!c->waiting&&c->output_bytes){
            size_t n=c->output_bytes;if(n>s->max_payload)n=s->max_payload;frame(s,WRTE,c->local,c->remote,c->output,n);
            shift(c->output,c->output_bytes,n);c->output_bytes-=n;c->waiting=1;s->output_cursor=(index+1)%s->channel_capacity;break;}}}
    if(s->tx_bytes){*data=s->tx+s->tx_offset;*bytes=s->tx_bytes-s->tx_offset;s->tx_borrowed=1;}return KBL_OK;
}
static int done_impl(struct kbl_adb*s,size_t confirmed,int status){
    if(!s||s->cookie!=COOKIE||!s->tx_borrowed||confirmed>s->tx_bytes-s->tx_offset)return KBL_ERROR;
    s->tx_borrowed=0;s->tx_offset+=confirmed;if(status!=KBL_OK){s->fault=1;return KBL_ERROR;}
    if(!confirmed){s->fault=1;return KBL_ERROR;}if(s->tx_offset==s->tx_bytes)s->tx_offset=s->tx_bytes=0;return valid(s)?KBL_OK:KBL_ERROR;
}
int kbl_adb_stop_requested(const struct kbl_adb*s){return s&&s->cookie==COOKIE&&s->stop;}
int kbl_adb_stop_ready(const struct kbl_adb*s){if(!s||s->cookie!=COOKIE||!s->stop||s->in_call||s->tx_bytes||s->tx_borrowed||s->control_count||s->rx_complete)return 0;
    for(size_t i=0;i<s->channel_capacity;++i)if(s->channels[i].used||s->channels[i].files_active)return 0;return 1;}
int kbl_adb_healthy(const struct kbl_adb*s){return s&&s->cookie==COOKIE&&!s->fault;}
static int native_references(const struct kbl_adb*s){
    for(size_t i=0;i<s->channel_capacity;++i)if(s->channels[i].file||s->channels[i].files_active||s->channels[i].session_active)return 1;return 0;}
int kbl_adb_native_quiescent(const struct kbl_adb*s){
    return kbl_adb_healthy(s)&&s->stop&&!s->in_call&&!native_references(s);}
int kbl_adb_parked(const struct kbl_adb*s){return kbl_adb_healthy(s)&&s->stop&&s->native_stopped;}
static int disconnect_impl(struct kbl_adb*s){
    if(!s||s->cookie!=COOKIE||s->tx_borrowed)return KBL_AGAIN;
    unsigned pending=0;for(size_t i=0;i<s->channel_capacity;++i){struct channel*c=&s->channels[i];c->closing=c->peer_closed=1;if(release_channel(s,c)==KBL_AGAIN)pending=1;}if(pending)return KBL_AGAIN;
    s->connected=s->authorized=0;s->rx_bytes=0;s->rx_need=24;s->rx_complete=0;s->tx_bytes=s->tx_offset=0;clear_controls(s);
    return s->c.current_el(s->c.opaque)==s->el?KBL_OK:KBL_ERROR;
}
static int enter(struct kbl_adb*s){if(!s||s->cookie!=COOKIE)return KBL_ERROR;if(s->in_call)return KBL_AGAIN;s->in_call=1;return KBL_OK;}
int kbl_adb_feed(struct kbl_adb*s,const void*p,size_t n,size_t*done){int rc=enter(s);if(rc){if(done)*done=0;return rc;}rc=feed_impl(s,p,n,done);s->in_call=0;return rc;}
int kbl_adb_poll(struct kbl_adb*s,unsigned budget){int rc=enter(s);if(rc)return rc;rc=poll_impl(s,budget);s->in_call=0;return rc;}
int kbl_adb_output(struct kbl_adb*s,const void**p,size_t*n){int rc=enter(s);if(rc){if(p)*p=0;if(n)*n=0;return rc;}rc=output_impl(s,p,n);s->in_call=0;return rc;}
int kbl_adb_output_done(struct kbl_adb*s,size_t n,int status){int rc=enter(s);if(rc)return rc;rc=done_impl(s,n,status);s->in_call=0;return rc;}
int kbl_adb_disconnect(struct kbl_adb*s){int rc=enter(s);if(rc)return rc;rc=disconnect_impl(s);s->in_call=0;return rc;}
int kbl_adb_request_stop(struct kbl_adb*s){int rc=enter(s);if(rc)return rc;
    if(!same_el(s))rc=KBL_ERROR;
    else if(s->fault){unsigned first=!s->stop;s->stop=1;if(first&&s->c.stop_request)s->c.stop_request(s->c.opaque);rc=disconnect_impl(s);}
    else rc=request_stop(s,0);s->in_call=0;return rc;}
int kbl_adb_native_stopped(struct kbl_adb*s){int rc=enter(s);if(rc)return rc;
    if(!valid(s)||!s->stop)rc=KBL_ERROR;
    else if(native_references(s))rc=KBL_AGAIN;
    else{s->job=0;s->job_active=0;s->native_stopped=1;rc=KBL_OK;}
    s->in_call=0;return rc;}
int kbl_adb_resume(struct kbl_adb*s){int rc=enter(s);if(rc)return rc;
    if(!valid(s)||!s->stop||!s->native_stopped||s->job_active||native_references(s))rc=KBL_ERROR;
    else if((rc=advance_generation(s))==KBL_OK){s->stop=0;s->native_stopped=0;}
    s->in_call=0;return rc;}
int kbl_adb_destroy(struct kbl_adb*s){int rc=enter(s);if(rc)return rc;rc=disconnect_impl(s);if(rc!=KBL_OK){s->in_call=0;return rc;}
    struct kbl_adb_config c=s->c;if(s->channels!=s->inline_channels)c.release(c.opaque,s->channels,s->channel_capacity*sizeof(*s->channels));s->cookie=0;c.release(c.opaque,s,sizeof(*s));return KBL_OK;}
