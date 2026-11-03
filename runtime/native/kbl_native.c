/* SPDX-License-Identifier: MIT */
#include "kbl_native.h"
#include "kbl_native_io.h"
#include "kbl_context.h"
#include "kbl_efi_bootloader.h"
#include "../kbl_sha256.h"
#include "../fs/kbl_fs.h"
#include <limits.h>

#define NATIVE_COOKIE UINT64_C(0x4b424c4e41544931)
#define DEFAULT_STACK (64u * 1024u)
#define DEFAULT_OUTPUT 4096u
#define DEFAULT_FILE SIZE_MAX
#define DEFAULT_RAMFS SIZE_MAX
#define FILE_CHUNK 65536u
#define SESSION_COOKIE UINT64_C(0x4b424c5345535331)

struct native;
struct owned { struct owned *next, *previous; size_t bytes, total; struct native *owner; uint64_t program_job; };
_Static_assert(sizeof(struct owned) % 16 == 0, "owned header alignment");
struct node {
    struct node *parent, *children, *next;
    unsigned directory,reserved;
    char *name;
    unsigned char *data;
    size_t bytes,capacity;
};
struct mount;
struct file_slot {
    struct file_slot *next;uint64_t id;unsigned active,flags;
    uint64_t position,job;
    struct node *node;
    struct kbl_block block;
    struct mount *mount;void *file;char *path;
};
struct native;
struct mount {
    struct mount *next;struct native *owner;unsigned active,flags;
    char *path,*source;
    struct node *source_node,*point;
    struct kbl_block block;
    struct kbl_fs_device device;
    struct kbl_fs fs;
};
struct io_session;
enum session_state { SESSION_IDLE, SESSION_QUEUED, SESSION_ACTIVE, SESSION_DONE };
struct native_session {
    uint64_t cookie;struct native *c;struct native_session *next;
    struct node *cwd;char *cwd_path;
    unsigned char *output;size_t output_read,output_count;
    char *line;void *stack;int rc;
    uint64_t job,ticket;unsigned state,io_requested;
    struct io_session *io;
};
struct native {
    uint64_t cookie;
    struct kbl_native_platform p;
    struct owned *owned;
    size_t owned_count, owned_bytes, ramfs_bytes;
    size_t nodes, snapshots;unsigned el, in_job, polling;
    struct node *root, *cwd;
    char *cwd_path;
    struct mount *mounts;
    struct node scratch_node;unsigned char *scratch;
    unsigned char *output;
    size_t output_capacity, output_read, output_count;
    struct kbl_cpu_context host_cpu, job_cpu;
    void *stack;
    size_t stack_bytes;
    char *line, **argv;
    int argc, rc;
    unsigned job_active, job_done, seeded, fault;
    uint64_t job_id, deadline;
    struct kbl_elf_image image;
    struct file_slot *files;uint64_t file_seed;
    struct io_session *io_owner;
    unsigned io_entered,fs_busy;
    struct native_session default_session,*sessions,*active_session;
    size_t session_count;
    uint64_t queue_seed,job_seed,file_owner;
};

static void zero(void *p, size_t n) { unsigned char *d=p; while(n--) *d++=0; }
static void copy(void *d_, const void *s_, size_t n) { unsigned char *d=d_; const unsigned char *s=s_; while(n--) *d++=*s++; }
static size_t length(const char *s) { size_t n=0; while(s[n]) ++n; return n; }
static int equal(const char *a, const char *b) { while(*a && *a==*b) {++a;++b;} return *a==*b; }
static int slash(const char *s) { while(*s) if(*s++=='/') return 1; return 0; }
static int space(unsigned char c) { return c==' ' || c=='\t'; }
static struct native *context(void *p) { struct native *c=p; return c && c->cookie==NATIVE_COOKIE ? c : 0; }
static int checkpoint(struct native *);
static int canonical(struct native*,const char*,char**);
static int mounted_path(struct native*,const char*);
static int ram_mutation_busy(struct native*,struct node*);
static void free_owned(struct native *,void *);
static void schedule(struct native *);
static int all_cwd_under(struct native *,const char *);

static void *alloc_owned_tagged(struct native *c, size_t bytes, uint64_t job) {
    if(bytes > SIZE_MAX-sizeof(struct owned) || c->owned_bytes>SIZE_MAX-bytes) return 0;
    size_t total=bytes+sizeof(struct owned);
    struct owned *a=c->p.allocate(c->p.opaque,total,16);
    if(!a) return 0;
    if((uintptr_t)a & 15u) { c->p.release(c->p.opaque,a,total); return 0; }
    a->owner=c;a->program_job=job;a->bytes=bytes; a->total=total; a->previous=0; a->next=c->owned;
    if(c->owned) c->owned->previous=a;
    c->owned=a; ++c->owned_count; c->owned_bytes+=bytes;
    void *p=a+1;size_t at=0;while(at<bytes){size_t n=bytes-at;if(n>FILE_CHUNK)n=FILE_CHUNK;
        zero((unsigned char*)p+at,n);at+=n;if(at<bytes&&checkpoint(c)){free_owned(c,p);return 0;}}
    return p;
}
static void *alloc_owned(struct native*c,size_t bytes){return alloc_owned_tagged(c,bytes,0);}
static void free_owned(struct native *c, void *p) {
    if(!p) return;
    struct owned *a=(struct owned *)p-1;
    if(a->previous) a->previous->next=a->next; else c->owned=a->next;
    if(a->next) a->next->previous=a->previous;
    --c->owned_count; c->owned_bytes-=a->bytes;
    c->p.release(c->p.opaque,a,a->total);
}
#include "kbl_program_memory.inc"
/* Scoped temporaries survive cooperative yields and are released on every
 * return. Persistent strings explicitly transfer ownership by clearing them. */
static void release_auto(void *address){
    void*p=0;copy(&p,address,sizeof(p));if(p)free_owned(((struct owned*)p-1)->owner,p);
}
#define AUTO(type,name) type name __attribute__((cleanup(release_auto)))=0
static char*owned_string(struct native*c,const char*s){
    size_t n=length(s);if(n==SIZE_MAX)return 0;char*p=alloc_owned(c,n+1);if(p)copy(p,s,n+1);return p;
}
static const char*current_path(struct native*c){return c->cwd_path&&c->cwd_path[0]?c->cwd_path:"/";}
static int set_cwd(struct native*c,char*path){
    struct native_session*s=c->active_session?c->active_session:&c->default_session;
    char*old=s->cwd_path;s->cwd_path=path;c->cwd_path=path;free_owned(c,old);return 0;
}
static int busy_external(struct native *c) {
    if(c->io_owner||(c->job_active&&!c->in_job))return 1;
    for(struct native_session*s=c->sessions;s;s=s->next)if(s->ticket)return 1;
    return 0;
}

static int suspend_job(struct native *c) {
#if defined(__aarch64__)
    if(!c->in_job || !c->polling || c->fs_busy) return KBL_ERROR;
    c->in_job=0;
    kbl_context_switch(&c->job_cpu,&c->host_cpu);
    c->in_job=1;
    if(c->p.current_el(c->p.opaque)!=c->el) { c->fault=1; return KBL_ERROR; }
    return KBL_OK;
#else
    (void)c; return KBL_ERROR;
#endif
}
static int checkpoint(struct native *c) {
    if(c->fs_busy){if(c->p.current_el(c->p.opaque)!=c->el){c->fault=1;return KBL_ERROR;}return KBL_OK;}
    if(!c->in_job) return KBL_OK;
    if(!c->p.now_us || c->p.now_us(c->p.opaque)>=c->deadline)
        return suspend_job(c);
    return KBL_OK;
}
static int emit(struct native *c, const void *data, size_t bytes) {
    const unsigned char *p=data;
    if((bytes && !p) || !c->in_job) return KBL_ERROR;
    while(bytes) {
        if(c->output_count==c->output_capacity && suspend_job(c)!=KBL_OK) return KBL_ERROR;
        size_t at=(c->output_read+c->output_count)%c->output_capacity;
        size_t n=c->output_capacity-at;
        if(n>c->output_capacity-c->output_count) n=c->output_capacity-c->output_count;
        if(n>bytes) n=bytes;
        if(n>256) n=256;
        copy(c->output+at,p,n); c->output_count+=n; p+=n; bytes-=n;
        if(checkpoint(c)!=KBL_OK) return KBL_ERROR;
    }
    return KBL_OK;
}
static int text(struct native *c, const char *s) { return emit(c,s,length(s)); }
static void number(struct native *c, uint64_t v) {
    char b[21]; size_t n=sizeof(b); do { b[--n]=(char)('0'+v%10); v/=10; }while(v);
    (void)emit(c,b+n,sizeof(b)-n);
}
static int error(struct native *c, const char *s) { (void)text(c,s); (void)text(c,"\n"); return 1; }

static struct node *child(struct node *p, const char *name) {
    if(!p || !p->directory) return 0;
    for(struct node *n=p->children;n;n=n->next) if(equal(n->name,name)) return n;
    return 0;
}
static struct node *new_node(struct native*c,struct node*p,const char*name,unsigned dir){
    if(!*name||c->nodes==SIZE_MAX||(c->p.max_nodes&&c->nodes>=c->p.max_nodes)||child(p,name)||(p&&p->reserved))return 0;
    AUTO(struct node*,f);f=alloc_owned(c,sizeof(*f));if(!f)return 0;
    f->name=owned_string(c,name);if(!f->name)return 0;f->directory=dir;f->parent=p;
    if(p){f->next=p->children;p->children=f;}++c->nodes;struct node*out=f;f=0;return out;
}
static void unlink_node(struct node *f) {
    struct node **at=&f->parent->children;
    while(*at && *at!=f) at=&(*at)->next;
    if(*at) *at=f->next;
    f->next=0;
}
static int ancestor(struct node *a, struct node *n) {
    while(n) { if(a==n) return 1; n=n->parent; } return 0;
}
/* Resolve all components. parent_mode returns the existing parent plus leaf. */
static struct node *resolve(struct native*c,const char*path,int parent_mode,char**leaf){
    AUTO(char*,full);if(canonical(c,path,&full))return 0;struct node*n=c->root;char*at=full;
    while(*at){while(*at=='/')++at;if(!*at)break;char*end=at;while(*end&&*end!='/')++end;
        char saved=*end;*end=0;const char*tail=end+(saved!=0);while(*tail=='/')++tail;
        if(parent_mode&&!*tail){if(!n->directory||!leaf)return 0;*leaf=owned_string(c,at);return *leaf?n:0;}
        if(!n->directory)return 0;n=child(n,at);if(!n)return 0;*end=saved;at=end;
    }return parent_mode?0:n;
}
static int replace(struct native *c, struct node *f, const void *data, size_t bytes) {
    if(!f || f->directory || bytes>c->p.max_file_bytes || (bytes&&!data) || f->bytes>c->ramfs_bytes) return KBL_ERROR;
    size_t retained=c->ramfs_bytes-f->bytes;
    if(bytes>c->p.max_ramfs_bytes || retained>c->p.max_ramfs_bytes-bytes) return KBL_ERROR;
    unsigned char *fresh=bytes?alloc_owned(c,bytes):0;
    if(bytes&&!fresh) return KBL_ERROR;
    for(size_t at=0;at<bytes;) {
        size_t n=bytes-at; if(n>256) n=256;
        copy(fresh+at,(const unsigned char *)data+at,n); at+=n;
        if(checkpoint(c)!=KBL_OK) { free_owned(c,fresh); return KBL_ERROR; }
    }
    unsigned char *old=f->data; f->data=fresh; f->bytes=bytes;f->capacity=bytes; c->ramfs_bytes=retained+bytes;
    free_owned(c,old); return KBL_OK;
}
int kbl_native_put(void *opaque,const char *path,const void *data,size_t bytes) {
    struct native *c=context(opaque); if(!c || (bytes&&!data)) return KBL_ERROR;
    if(busy_external(c)) return KBL_AGAIN;
    if(mounted_path(c,path))return KBL_ERROR;
    AUTO(char*,name); struct node *p=resolve(c,path,1,&name); if(!p||p->reserved) return KBL_ERROR;
    struct node *f=child(p,name); unsigned created=0;
    if(!f) { f=new_node(c,p,name,0); created=1; }
    if(!f||ram_mutation_busy(c,f)) return KBL_ERROR;
    int rc=replace(c,f,data,bytes);
    if(rc!=KBL_OK && created) { unlink_node(f); --c->nodes; free_owned(c,f->name); free_owned(c,f); }
    return rc;
}
int kbl_native_get(void *opaque,const char *path,uint64_t offset,void *buffer,size_t cap,
                   size_t *read_bytes,uint64_t *file_bytes) {
    struct native *c=context(opaque);
    if(read_bytes) *read_bytes=0;
    if(!c||!read_bytes||!file_bytes||(cap&&!buffer)) return KBL_ERROR;
    if(c->io_owner)return KBL_AGAIN;
    if(mounted_path(c,path))return KBL_ERROR;
    struct node *f=resolve(c,path,0,0); if(!f||f->directory||offset>f->bytes) return KBL_ERROR;
    size_t n=f->bytes-(size_t)offset; if(n>cap) n=cap;
    if(n) copy(buffer,f->data+(size_t)offset,n);
    *read_bytes=n; *file_bytes=f->bytes; return KBL_OK;
}
struct snapshot { struct native *c; size_t bytes; unsigned char *data; };
static int snapshot_read(void *opaque,uint64_t off,void *out,size_t bytes,size_t *read) {
    struct snapshot *s=opaque; if(read) *read=0;
    if(!s||!read||(bytes&&!out)||off>s->bytes) return KBL_ERROR;
    size_t n=s->bytes-(size_t)off; if(n>bytes) n=bytes;
    if(n) copy(out,s->data+(size_t)off,n);
    *read=n; return KBL_OK;
}
static void snapshot_close(void *opaque) {
    struct snapshot *s=opaque; if(!s) return;
    struct native *c=s->c; free_owned(c,s->data); --c->snapshots; free_owned(c,s);
}
int kbl_native_open_snapshot(void *opaque,const char *path,struct kbl_reader *reader) {
    struct native *c=context(opaque); if(!c||!reader||c->in_job||c->polling||c->snapshots==SIZE_MAX) return KBL_ERROR;
    if(c->io_owner)return KBL_AGAIN;
    if(reader->opaque||reader->read_at||reader->close||reader->size) return KBL_ERROR;
    if(mounted_path(c,path))return KBL_ERROR;
    struct node *f=resolve(c,path,0,0); if(!f||f->directory) return KBL_ERROR;
    struct snapshot *s=alloc_owned(c,sizeof(*s)); if(!s) return KBL_ERROR;
    s->c=c; s->bytes=f->bytes; s->data=f->bytes?alloc_owned(c,f->bytes):0;
    if(f->bytes&&!s->data) { free_owned(c,s); return KBL_ERROR; }
    if(f->bytes) copy(s->data,f->data,f->bytes);
    ++c->snapshots;
    *reader=(struct kbl_reader){s,f->bytes,snapshot_read,snapshot_close}; return KBL_OK;
}

static int work(struct native *c,unsigned *count) {
    if(++*count<256)return KBL_OK;*count=0;return checkpoint(c);
}
static int unsigned_value(const char *s,uint64_t *out) {
    unsigned base=10;uint64_t v=0;if(!*s)return 0;
    if(s[0]=='0'&&(s[1]=='x'||s[1]=='X')){base=16;s+=2;if(!*s)return 0;}
    while(*s){unsigned char ch=(unsigned char)*s++;unsigned d=ch>='0'&&ch<='9'?ch-'0':
        ch>='a'&&ch<='f'?ch-'a'+10:ch>='A'&&ch<='F'?ch-'A'+10:99;
        if(d>=base||v>(UINT64_MAX-d)/base)return 0;v=v*base+d;}
    *out=v;return 1;
}
#include "kbl_vfs.inc"
static int all_cwd_under(struct native*c,const char*path){
    if(path_under(path,current_path(c)))return 1;
    for(struct native_session*s=c->sessions;s;s=s->next)
        if(path_under(path,s->cwd_path?s->cwd_path:"/"))return 1;
    return 0;
}
static void restore_default_cwd(struct native*c){
    if(c->job_active||c->io_owner)return;
    struct native_session*s=&c->default_session;
    if(s->cookie==SESSION_COOKIE){c->cwd=s->cwd;c->cwd_path=s->cwd_path;}
}
static int whitespace(unsigned char ch){return ch==' '||(ch>='\t'&&ch<='\r');}
static int ranged_output(struct native *c,struct node *f,size_t begin,size_t end){
    while(begin<end){size_t n=end-begin;if(n>256)n=256;if(emit(c,f->data+begin,n))return 1;begin+=n;}return 0;
}
static int hash_files(struct native *c){
    int first=1;if(c->argc>1&&equal(c->argv[1],"--"))++first;
    if(first==c->argc)return error(c,"usage: sha256sum [--] FILE...");
    for(int i=first;i<c->argc;++i){kbl_file_handle h=0;if(v_open(c,c->argv[i],KBL_FILE_READ,&h))return error(c,"file open failed");
        struct kbl_sha256 hash;unsigned char digest[32];char encoded[65];kbl_sha256_init(&hash);
        unsigned char *buffer=alloc_owned(c,FILE_CHUNK);if(!buffer){v_close(c,h);return error(c,"allocation failed");}
        int failure=0;for(;;){size_t n=0;int status=v_read(c,h,buffer,FILE_CHUNK,&n);
            if(n&&kbl_sha256_update(&hash,buffer,n)!=KBL_OK){failure=1;break;}
            if(status){failure=1;break;}if(!n)break;if(checkpoint(c)){failure=1;break;}}
        free_owned(c,buffer);if(v_close(c,h))failure=1;if(failure)return error(c,"SHA-256 read failed");
        if(kbl_sha256_final(&hash,digest)!=KBL_OK)return error(c,"SHA-256 failed");
        kbl_sha256_hex(digest,encoded);
        if(text(c,encoded)||text(c,"  ")||text(c,c->argv[i])||text(c,"\n"))return 1;
    }return 0;
}
static int word_count(struct native *c){
    unsigned flags=0;int first=1;
    while(first<c->argc&&c->argv[first][0]=='-'){
        const char *s=c->argv[first++];if(equal(s,"--"))break;
        if(!s[1])return error(c,"usage: wc [-lwc] [--] FILE...");
        for(++s;*s;++s){if(*s=='l')flags|=1;else if(*s=='w')flags|=2;else if(*s=='c')flags|=4;
            else return error(c,"usage: wc [-lwc] [--] FILE...");}
    }
    if(first==c->argc)return error(c,"usage: wc [-lwc] [--] FILE...");if(!flags)flags=7;
    uint64_t totals[3]={0,0,0};
    for(int i=first;i<c->argc;++i){struct node*f=v_load_node(c,c->argv[i]);
        if(!f||f->directory)return error(c,"file not found");uint64_t values[3]={0,0,f->bytes};unsigned in_word=0,steps=0;
        for(size_t at=0;at<f->bytes;++at){unsigned char ch=f->data[at];if(ch=='\n')++values[0];
            if(whitespace(ch))in_word=0;else if(!in_word){++values[1];in_word=1;}
            if(work(c,&steps))return 1;}
        unsigned emitted=0;for(unsigned j=0;j<3;++j){totals[j]+=values[j];if(flags&(1u<<j)){
            if(emitted++&&text(c," "))return 1;number(c,values[j]);}}
        if(text(c," ")||text(c,c->argv[i])||text(c,"\n"))return 1;
    }
    if(c->argc-first>1){unsigned emitted=0;
        for(unsigned j=0;j<3;++j){if(flags&(1u<<j)){
            if(emitted++&&text(c," "))return 1;number(c,totals[j]);}}
        if(text(c," total\n"))return 1;
    }
    return 0;
}
static int edge_file(struct native *c,int tail){
    int i=1,bytes=0;uint64_t count=10;
    if(i<c->argc&&(equal(c->argv[i],"-n")||equal(c->argv[i],"-c"))){bytes=equal(c->argv[i++],"-c");
        if(i==c->argc||!unsigned_value(c->argv[i++],&count))return error(c,"usage: head|tail [-n N|-c N] [--] FILE");}
    if(i<c->argc&&equal(c->argv[i],"--"))++i;
    if(i+1!=c->argc)return error(c,"usage: head|tail [-n N|-c N] [--] FILE");
    struct node*f=v_load_node(c,c->argv[i]);if(!f||f->directory)return error(c,"file not found");
    size_t begin=0,end=f->bytes;unsigned steps=0;
    if(!count)return 0;
    if(bytes){if(count<f->bytes){if(tail)begin=f->bytes-(size_t)count;else end=(size_t)count;}}
    else if(tail){begin=f->bytes;if(begin&&f->data[begin-1]=='\n')--begin;
        while(begin){--begin;if(f->data[begin]=='\n'&&!--count){++begin;break;}if(work(c,&steps))return 1;}}
    else{end=0;while(end<f->bytes){unsigned char ch=f->data[end++];if(ch=='\n'&&!--count)break;if(work(c,&steps))return 1;}}
    return ranged_output(c,f,begin,end);
}
static unsigned char folded(unsigned char c){return c>='A'&&c<='Z'?(unsigned char)(c+32):c;}
static int grep_file(struct native *c){
    unsigned numbered=0,invert=0,ignore=0,count_only=0;int first=1;
    while(first<c->argc&&c->argv[first][0]=='-'){
        const char*s=c->argv[first++];if(equal(s,"--"))break;if(!s[1])return error(c,"usage: grep [-Fnicv] [--] LITERAL FILE...")+1;
        for(++s;*s;++s){if(*s=='F')continue;if(*s=='n')numbered=1;else if(*s=='v')invert=1;else if(*s=='i')ignore=1;else if(*s=='c')count_only=1;
            else return error(c,"grep uses literal byte matching; options: -F -n -i -c -v")+1;}
    }
    if(first+1>=c->argc)return error(c,"usage: grep [-Fnicv] [--] LITERAL FILE...")+1;
    const unsigned char*pattern=(const unsigned char*)c->argv[first++];size_t pn=length((const char*)pattern);unsigned any=0;
    for(int file=first;file<c->argc;++file){struct node*f=v_load_node(c,c->argv[file]);
        if(!f||f->directory)return error(c,"file not found")+1;
        size_t begin=0;uint64_t line=0,matches=0;unsigned steps=0;
        while(begin<f->bytes){size_t end=begin;while(end<f->bytes&&f->data[end]!='\n'){++end;if(work(c,&steps))return 2;}
            unsigned matched=!pn;
            if(pn<=end-begin)for(size_t at=begin;!matched&&at<=end-pn;++at){size_t j=0;
                while(j<pn){unsigned char x=f->data[at+j],y=pattern[j];if(ignore){x=folded(x);y=folded(y);}
                    if(work(c,&steps))return 2;if(x!=y)break;++j;}
                if(j==pn)matched=1;}
            ++line;if((matched!=0)!=invert){any=1;++matches;
                if(!count_only){if(c->argc-first>1&&(text(c,c->argv[file])||text(c,":")))return 2;
                    if(numbered){number(c,line);if(text(c,":"))return 2;}
                    if(ranged_output(c,f,begin,end)||text(c,"\n"))return 2;}}
            begin=end+(end<f->bytes);if(checkpoint(c))return 2;
        }
        if(count_only){if(c->argc-first>1&&(text(c,c->argv[file])||text(c,":")))return 2;
            number(c,matches);if(text(c,"\n"))return 2;}
    }return any?0:1;
}
static int dump_file(struct native*c){
    int i=1;uint64_t limit=UINT64_MAX;
    if(i<c->argc&&(equal(c->argv[i],"-n")||equal(c->argv[i],"-l"))){++i;if(i==c->argc||!unsigned_value(c->argv[i++],&limit))return error(c,"usage: hexdump|xxd [-n N|-l N] [-C] [--] FILE");}
    if(i<c->argc&&equal(c->argv[i],"-C"))++i;if(i<c->argc&&equal(c->argv[i],"--"))++i;
    if(i+1!=c->argc)return error(c,"usage: hexdump|xxd [-n N|-l N] [-C] [--] FILE");
    struct node*f=v_load_node(c,c->argv[i]);if(!f||f->directory)return error(c,"file not found");
    size_t end=f->bytes;if(limit<end)end=(size_t)limit;const char*digits="0123456789abcdef";
    for(size_t at=0;at<end;at+=16){char line[96];size_t n=0,amount=end-at;if(amount>16)amount=16;
        for(int shift=at>UINT32_MAX?60:28;shift>=0;shift-=4)line[n++]=digits[((uint64_t)at>>shift)&15];line[n++]=' ';line[n++]=' ';
        for(unsigned j=0;j<16;++j){if(j==8)line[n++]=' ';if(j<amount){line[n++]=digits[f->data[at+j]>>4];line[n++]=digits[f->data[at+j]&15];}else{line[n++]=' ';line[n++]=' ';}line[n++]=' ';}
        line[n++]=' ';line[n++]='|';for(size_t j=0;j<amount;++j){unsigned char ch=f->data[at+j];line[n++]=ch>=32&&ch<127?(char)ch:'.';}line[n++]='|';line[n++]='\n';
        if(emit(c,line,n))return 1;
    }return 0;
}
static int path_part(struct native*c,int directory){
    if(c->argc<2||c->argc>(directory?2:3))return error(c,directory?"usage: dirname NAME":"usage: basename NAME [SUFFIX]");
    const char*s=c->argv[1];size_t end=length(s);while(end>1&&s[end-1]=='/')--end;
    size_t start=end;while(start&&s[start-1]!='/')--start;
    if(directory){if(!start)return text(c,".\n")?1:0;while(start>1&&s[start-1]=='/')--start;
        return emit(c,s,start)||text(c,"\n")?1:0;}
    if(!end)return text(c,"\n")?1:0;if(end==1&&s[0]=='/')return text(c,"/\n")?1:0;
    if(c->argc==3){size_t suffix=length(c->argv[2]);if(suffix&&suffix<end-start){size_t j=0;while(j<suffix&&s[end-suffix+j]==c->argv[2][j])++j;if(j==suffix)end-=suffix;}}
    return emit(c,s+start,end-start)||text(c,"\n")?1:0;
}
static int format_output(struct native*c){
    if(c->argc<2)return error(c,"usage: printf FORMAT [ARGS...] (%% %s %c %d %u %x %X)");
    const char*fmt=c->argv[1];int argument=2;
    /* Validate before emitting, so malformed formats do not print a prefix. */
    for(size_t i=0;fmt[i];++i){if(fmt[i]=='\\'){char ch=fmt[++i];if(!ch||!(ch=='n'||ch=='r'||ch=='t'||ch=='\\'))return error(c,"printf escape is unsupported");}
        else if(fmt[i]=='%'){char ch=fmt[++i];if(ch=='%')continue;if(!ch||!(ch=='s'||ch=='c'||ch=='d'||ch=='u'||ch=='x'||ch=='X')||argument==c->argc)return error(c,"printf invalid format or argument count");
            if(ch!='s'&&ch!='c'){const char*s=c->argv[argument];uint64_t value;if(ch=='d'&&s[0]=='-')++s;
                if(!unsigned_value(s,&value)||(ch=='d'&&value>(c->argv[argument][0]=='-'?UINT64_C(0x8000000000000000):INT64_MAX)))return error(c,"printf integer out of range");}++argument;}}
    if(argument!=c->argc)return error(c,"printf argument count mismatch");argument=2;
    for(size_t i=0;fmt[i];++i){char ch=fmt[i];if(ch=='\\'){ch=fmt[++i];ch=ch=='n'?'\n':ch=='r'?'\r':ch=='t'?'\t':'\\';if(emit(c,&ch,1))return 1;}
        else if(ch=='%'){ch=fmt[++i];if(ch=='%'){if(text(c,"%"))return 1;continue;}const char*s=c->argv[argument++];
            if(ch=='s'){if(text(c,s))return 1;}else if(ch=='c'){if(emit(c,s,1))return 1;}
            else{uint64_t value;unsigned negative=ch=='d'&&*s=='-';if(negative)++s;(void)unsigned_value(s,&value);
                if(negative&&value&&text(c,"-"))return 1;
                if(ch=='x'||ch=='X'){char buf[16];size_t n=16;const char*digits=ch=='x'?"0123456789abcdef":"0123456789ABCDEF";
                    do{buf[--n]=digits[value&15];value>>=4;}while(value);if(emit(c,buf+n,16-n))return 1;}
                else number(c,value);}}
        else if(emit(c,&ch,1))return 1;
    }return 0;
}
#include "kbl_efi_command.inc"
static int command(struct native *c) {
    if(!c->argc) return 0;
    char **a=c->argv; int n=c->argc;
    if(equal(a[0],"help")) {
        text(c,"KBL native VFS commands:\nhelp el pwd ls cd mkdir touch cat cp mv rm echo hex stat true false\nsha256sum wc head tail grep hexdump xxd basename dirname printf dd sync mount umount\nefi PATH [ARGS...] (UEFI PE/COFF application, synchronous)\nefi -bl PATH [ARGS...] / efi --bootloader PATH [ARGS...] (explicit RAM-only bootloader mode)\n");
        text(c,"grep matches literal bytes (-F -n -i -c -v); no regex or pipes.\n");
        text(c,"screenoff screenon screenstatus; poweroff (sync then EFI shutdown).\n");
        text(c,"./FILE [ARGS] runs a KBL ABI AArch64 ELF; exit keeps this runtime.\n"); return 0;
    }
    if(equal(a[0],"screenoff")||equal(a[0],"screenon")||equal(a[0],"screenstatus")){
        if(n!=1)return error(c,"usage: screenoff | screenon | screenstatus");
        if(!c->p.display)return error(c,"display provider unavailable");
        char reply[1024];zero(reply,sizeof(reply));
        unsigned op=equal(a[0],"screenoff")?1u:equal(a[0],"screenon")?2u:0u;
        int rc=c->p.display(c->p.opaque,op,reply,sizeof(reply));
        if(reply[sizeof(reply)-1])return error(c,"display reply overflow");
        if(text(c,reply))return 1;
        return rc?error(c,"display operation failed; see protocol status"):0;
    }
    if(equal(a[0],"poweroff")){
        if(n!=1)return error(c,"usage: poweroff");
        if(!c->p.poweroff)return error(c,"shutdown provider unavailable");
        if(sync_command(c))return error(c,"shutdown cancelled: filesystem/block sync failed");
        (void)c->p.poweroff(c->p.opaque);
        return error(c,"shutdown returned; device remains running");
    }
    if(equal(a[0],"mount"))return mount_command(c);
    if(equal(a[0],"umount")){if(n!=2)return error(c,"usage: umount TARGET");int rc=mount_remove(c,a[1]);if(rc){text(c,"umount failed errno=");number(c,(uint64_t)-rc);text(c,"\n");return 1;}return 0;}
    if(equal(a[0],"efi"))return efi_command(c);
    if(equal(a[0],"true")) return 0;
    if(equal(a[0],"dd"))return dd_command(c);
    if(equal(a[0],"sync"))return sync_command(c);
    if(equal(a[0],"sha256sum"))return hash_files(c);
    if(equal(a[0],"wc"))return word_count(c);
    if(equal(a[0],"head"))return edge_file(c,0);
    if(equal(a[0],"tail"))return edge_file(c,1);
    if(equal(a[0],"grep"))return grep_file(c);
    if(equal(a[0],"hexdump")||equal(a[0],"xxd"))return dump_file(c);
    if(equal(a[0],"basename"))return path_part(c,0);
    if(equal(a[0],"dirname"))return path_part(c,1);
    if(equal(a[0],"printf"))return format_output(c);
    if(equal(a[0],"false")) return 1;
    if(equal(a[0],"exit")) { text(c,"exit ignored; runtime retained\n"); return 0; }
    if(equal(a[0],"el")) { text(c,"EL"); number(c,c->p.current_el(c->p.opaque)); text(c,"\n"); return 0; }
    if(equal(a[0],"pwd")) { text(c,current_path(c)); text(c,"\n"); return 0; }
    if(equal(a[0],"echo")) {
        int first=1, newline=1; if(n>1&&equal(a[1],"-n")) { first=2; newline=0; }
        for(int i=first;i<n;++i) { if(i>first) text(c," "); text(c,a[i]); }
        if(newline) text(c,"\n"); return 0;
    }
    if(equal(a[0],"cd")) {
        if(n>2)return error(c,"usage: cd [PATH]");AUTO(char*,full);struct kbl_file_stat st;
        if(canonical(c,n==2?a[1]:"/",&full)||v_stat(c,full,&st)||st.type!=KBL_FILE_DIRECTORY)return error(c,"directory not found");
        c->cwd=resolve(c,full,0,0);if(!c->cwd)c->cwd=c->root;set_cwd(c,full);full=0;return 0;
    }
    if(equal(a[0],"ls")) {
        if(n>2)return error(c,"usage: ls [PATH]");const char*path=n==2?a[1]:".";struct kbl_file_stat st;
        if(v_stat(c,path,&st))return error(c,"path not found");if(st.type!=KBL_FILE_DIRECTORY){text(c,path);text(c,"\n");return 0;}
        for(uint64_t index=0;;++index){size_t cap=256;AUTO(char*,name);name=alloc_owned(c,cap);if(!name)return error(c,"directory list allocation failed");
            int end=0,rc;while((rc=v_list(c,path,index,name,cap,&st,&end))==-KBL_ENAMETOOLONG){
                if(cap>SIZE_MAX/2)return error(c,"directory name not addressable");cap*=2;free_owned(c,name);name=alloc_owned(c,cap);if(!name)return error(c,"directory list allocation failed");}
            if(rc)return error(c,"directory list failed");if(end)break;if(text(c,name)||(st.type==KBL_FILE_DIRECTORY&&text(c,"/"))||text(c,"\n")||checkpoint(c))return 1;
            if(index==UINT64_MAX)return error(c,"directory index overflow");}return 0;
    }
    if(equal(a[0],"mkdir")) {if(n!=2)return error(c,"usage: mkdir PATH");return v_mkdir(c,a[1])?error(c,"mkdir failed"):0;}
    if(equal(a[0],"touch")) {
        if(n!=2)return error(c,"usage: touch FILE");kbl_file_handle h=0;int rc=v_open(c,a[1],KBL_FILE_WRITE|KBL_FILE_CREATE,&h);if(rc)return error(c,"touch failed");
        rc=v_sync(c,h);if(v_close(c,h))rc=-KBL_EIO;return rc?error(c,"touch failed"):0;
    }
    if(equal(a[0],"cat")) {
        if(n<2) return error(c,"usage: cat FILE...");
        for(int i=1;i<n;++i) {
            kbl_file_handle h=0;if(v_open(c,a[i],KBL_FILE_READ,&h))return error(c,"file open failed");
            unsigned char buffer[1024];int failed=0;for(;;){size_t count=0;int status=v_read(c,h,buffer,sizeof(buffer),&count);
                if(count&&emit(c,buffer,count)){failed=1;break;}if(status){failed=1;break;}if(!count)break;}
            if(v_close(c,h))failed=1;if(failed)return error(c,"read failed");
        } return 0;
    }
    if(equal(a[0],"stat")) {
        if(n!=2) return error(c,"usage: stat PATH");
        struct kbl_file_stat s;if(v_stat(c,a[1],&s))return error(c,"path not found");
        text(c,s.type==KBL_FILE_DIRECTORY?"type=directory size=":s.type==KBL_FILE_BLOCK?"type=block size=":"type=file size="); number(c,s.bytes); text(c,"\n"); return 0;
    }
    if(equal(a[0],"cp")) {if(n!=3)return error(c,"usage: cp FILE DEST");return copy_command(c,a[1],a[2]);}
    if(equal(a[0],"mv")) {if(n!=3)return error(c,"usage: mv SOURCE DEST");AUTO(char*,dest);
        if(v_destination(c,a[1],a[2],&dest))return error(c,"invalid destination");int rc=v_rename(c,a[1],dest);
        if(rc==-KBL_EXDEV)return error(c,"cross-filesystem mv requires cp followed by rm");return rc?error(c,"move failed"):0;}
    if(equal(a[0],"rm")) {if(n!=2)return error(c,"usage: rm FILE_OR_EMPTY_DIRECTORY");return v_unlink(c,a[1])?error(c,"remove failed"):0;}
    if(equal(a[0],"hex")) {
        if(n!=2) return error(c,"usage: hex FILE");
        struct node *f=v_load_node(c,a[1]); if(!f||f->directory) return error(c,"file not found");
        const char digits[]="0123456789abcdef"; size_t limit=f->bytes;
        for(size_t i=0;i<limit;++i) { char b[3]={digits[f->data[i]>>4],digits[f->data[i]&15],(i%16==15||i+1==limit)?'\n':' '}; if(emit(c,b,3)) return 1; }
        return 0;
    }
    if(slash(a[0])) {
        struct node *f=v_load_node(c,a[0]); if(!f||f->directory) return error(c,"program file not found");
        int load=kbl_elf_load(f->data,f->bytes,&c->p.elf,&c->image);
        if(load!=KBL_ELF_OK) { text(c,"ELF load failed; code="); if(load<0) text(c,"-"); number(c,(uint64_t)(load<0?-load:load)); text(c,"\n"); return 126; }
        /* SDK callbacks are defined below; the image stays owned across yield. */
        extern int kbl_native_program_run(struct native *);
        return kbl_native_program_run(c);
    }
    return error(c,"unknown command; use help");
}

static int sdk_write(void *opaque,const void *p,size_t n) { struct native *c=context(opaque);if(v_gate(c))return KBL_ERROR;int rc=emit(c,p,n);return v_el(c)?KBL_ERROR:rc; }
static int sdk_yield(void *opaque,unsigned budget_us) { struct native *c=context(opaque);if(v_gate(c))return KBL_ERROR;
    int rc=budget_us?checkpoint(c):suspend_job(c);return v_el(c)?KBL_ERROR:rc; }
static unsigned sdk_el(void *opaque) { struct native *c=context(opaque);if(!c)return 0;unsigned el=c->p.current_el(c->p.opaque);if(el!=c->el)c->fault=1;return el; }
static int sdk_uefi(void *opaque,void **image,void **table) {
    struct native *c=context(opaque);
    if(image) *image=0; if(table) *table=0;
    if(v_gate(c)||!image||!table||image==table||!c->p.uefi_environment) return KBL_ERROR;
    int rc=c->p.uefi_environment(c->p.opaque,image,table);return v_el(c)?KBL_ERROR:rc;
}
int kbl_native_program_run(struct native *c) {
    struct kbl_program_api api; zero(&api,sizeof(api));
    api.abi_version=KBL_PROGRAM_ABI; api.struct_bytes=sizeof(api); api.host=c;
    api.write=sdk_write; api.yield=sdk_yield; api.current_el=sdk_el;
    api.argc=c->argc; api.argv=(const char *const *)c->argv;
    api.uefi_environment=c->p.uefi_environment?sdk_uefi:0;
    api.file_open=v_open;api.file_read=v_read;api.file_write=v_write;api.file_seek=v_seek;api.file_close=v_close;
    api.file_stat=v_stat;api.file_mkdir=v_mkdir;api.file_unlink=v_unlink;api.file_sync=v_sync;
    api.memory_allocate=sdk_memory_allocate;api.memory_release=sdk_memory_release;
    if(v_el(c))return 126;
    int rc=126, status=kbl_elf_call(&c->image,&api,c,&rc);
    if(v_el(c))return 126;
    release_job_memory(c,c->job_id);
    kbl_elf_unload(&c->image);
    if(status!=KBL_ELF_OK) return error(c,"ELF execution failed");
    return rc;
}

/* Internal fiber entry. It returns only to the saved caller context. */
void kbl_native_job_entry(struct native *c) {
    c->rc=command(c);
    if(close_owner_files(c,c->file_owner?c->file_owner:c->job_id)&&!c->rc)c->rc=1;
    free_owned(c,c->scratch);c->scratch=0;zero(&c->scratch_node,sizeof(c->scratch_node));
    if(c->p.current_el(c->p.opaque)!=c->el) c->fault=1;
    c->job_done=1;
    for(;;) (void)suspend_job(c);
}
/* Counting and writing share the same parser. Long single arguments no longer
 * reserve a pointer table sized for one argument per two input characters. */
static int parse_words(char*line,char**argv,int*out_argc){
    char*read=line,*write=line;int argc=0;
    while(*read){while(space((unsigned char)*read))++read;if(!*read)break;
        if(argc==INT_MAX)return KBL_ERROR;if(argv)argv[argc]=write;++argc;unsigned char quote=0;
        while(*read){unsigned char ch=(unsigned char)*read++;
            if(quote){if(ch==quote){quote=0;continue;}if(ch=='\\'&&quote=='"'){if(!*read)return KBL_ERROR;ch=(unsigned char)*read++;}if(argv)*write++=(char)ch;continue;}
            if(ch=='\''||ch=='"'){quote=ch;continue;}if(ch=='\\'){if(!*read)return KBL_ERROR;if(argv)*write++=*read;read++;continue;}
            if(space(ch))break;if(ch=='|'||ch=='&'||ch==';'||ch=='<'||ch=='>')return KBL_ERROR;if(argv)*write++=(char)ch;
        }if(quote)return KBL_ERROR;if(argv)*write++=0;
    }if(argv)argv[argc]=0;*out_argc=argc;return KBL_OK;
}
static int parse_arguments(struct native*c,char*line,char***out,int*out_argc){
    *out=0;*out_argc=0;int count=0;if(parse_words(line,0,&count))return KBL_ERROR;
    size_t capacity=(size_t)count+1;if(capacity>SIZE_MAX/sizeof(char*))return KBL_ERROR;
    AUTO(char**,argv);argv=alloc_owned(c,capacity*sizeof(*argv));if(!argv)return KBL_ERROR;
    if(parse_words(line,argv,out_argc)||*out_argc!=count)return KBL_ERROR;*out=argv;argv=0;return KBL_OK;
}
static int parse(struct native*c){free_owned(c,c->argv);c->argv=0;return parse_arguments(c,c->line,&c->argv,&c->argc);}
static void*backend_allocate(void*o,size_t n){const struct kbl_native_platform*p=o;return p->allocate(p->opaque,n,16);}
static void backend_release(void*o,void*m,size_t n){const struct kbl_native_platform*p=o;p->release(p->opaque,m,n);}
static unsigned backend_el(void *opaque) {
    const struct kbl_native_platform *p=opaque;
    return p&&p->current_el?p->current_el(p->opaque):0;
}
static int backend_start(void *opaque,void **out) {
    const struct kbl_native_platform *p=opaque;
    if(out) *out=0;
    if(!out||!p||!p->allocate||!p->release||!p->current_el) return KBL_ERROR;
#if !defined(__aarch64__)
    return KBL_ERROR;
#else
    unsigned el=p->current_el(p->opaque); if(el!=1&&el!=2) return KBL_ERROR;
    struct native *c=p->allocate(p->opaque,sizeof(*c),16); if(!c) return KBL_ERROR;
    if((uintptr_t)c&15u) { p->release(p->opaque,c,sizeof(*c)); return KBL_ERROR; }
    zero(c,sizeof(*c)); copy(&c->p,p,sizeof(*p)); c->cookie=NATIVE_COOKIE; c->el=el;
    c->p.stack_bytes=p->stack_bytes?p->stack_bytes:DEFAULT_STACK;
    c->p.output_bytes=p->output_bytes?p->output_bytes:DEFAULT_OUTPUT;
    c->p.max_ramfs_bytes=p->max_ramfs_bytes?p->max_ramfs_bytes:DEFAULT_RAMFS;
    c->p.max_file_bytes=p->max_file_bytes?p->max_file_bytes:c->p.max_ramfs_bytes;
    c->p.max_nodes=p->max_nodes;
    *out=c;
    if(c->p.stack_bytes<16384 || c->p.stack_bytes%16 || c->p.output_bytes<256 ||(c->p.max_nodes&&c->p.max_nodes<4)|| c->p.max_ramfs_bytes<c->p.max_file_bytes) return KBL_ERROR;
    c->output=alloc_owned(c,c->p.output_bytes); c->output_capacity=c->p.output_bytes;
    c->root=new_node(c,0,"/",1);c->cwd=c->root;c->cwd_path=owned_string(c,"/");
    if(!c->output||!c->root||!c->cwd_path||p->current_el(p->opaque)!=el) return KBL_ERROR;
    struct node *dev=new_node(c,c->root,"dev",1),*block=dev?new_node(c,dev,"block",1):0,*names=block?new_node(c,block,"by-name",1):0;
    if(!names)return KBL_ERROR;dev->reserved=1;block->reserved=2;names->reserved=3;
    struct native_session*s=&c->default_session;s->cookie=SESSION_COOKIE;s->c=c;
    s->cwd=c->root;s->cwd_path=c->cwd_path;s->output=c->output;c->sessions=s;
    return KBL_OK;
#endif
}
static void clear_job(struct native *c) {
    release_job_memory(c,c->job_id);
    (void)close_owner_files(c,c->file_owner?c->file_owner:c->job_id);
    free_owned(c,c->scratch);c->scratch=0;zero(&c->scratch_node,sizeof(c->scratch_node));
    kbl_elf_unload(&c->image);
    free_owned(c,c->stack); free_owned(c,c->line);free_owned(c,c->argv);
    c->stack=0; c->line=0; c->job_active=0; c->job_done=0; c->seeded=0; c->argc=0;
    c->argv=0; zero(&c->job_cpu,sizeof(c->job_cpu));
}
static struct native_session*session(void*opaque,void*token){
    struct native*c=context(opaque);if(!c||!token)return 0;
    for(struct native_session*s=c->sessions;s;s=s->next){if(s==token&&s->cookie==SESSION_COOKIE&&s->c==c)return s;}return 0;
}
static uint64_t next_ticket(struct native*c){if(c->queue_seed==UINT64_MAX)return 0;return ++c->queue_seed;}
static uint64_t next_job(struct native*c){if(c->job_seed==UINT64_MAX)return 0;return ++c->job_seed;}
static struct native_session*queue_head(struct native*c){
    struct native_session*head=0;for(struct native_session*s=c->sessions;s;s=s->next){
        if(s->ticket&&(!head||s->ticket<head->ticket))head=s;}return head;
}
static void save_active(struct native*c){struct native_session*s=c->active_session;if(!s)return;
    s->output_read=c->output_read;s->output_count=c->output_count;s->cwd=c->cwd;
    s->cwd_path=c->cwd_path;
}
static void finish_active(struct native*c){
    struct native_session*s=c->active_session;if(!s||!c->job_done)return;
    save_active(c);s->rc=c->rc;
    /* A failed close remains owned and is retried before freeing the stack. */
    if(close_owner_files(c,c->file_owner))return;
    clear_job(c);s->state=SESSION_DONE;c->active_session=0;c->file_owner=0;
    c->output=0;c->output_count=c->output_read=0;restore_default_cwd(c);
}
static void schedule(struct native*c){
    if(c->in_job||c->polling||c->fault||c->io_owner||c->job_active)return;
    struct native_session*s=queue_head(c);if(!s||s->io_requested||s->state!=SESSION_QUEUED)return;
    s->ticket=0;s->state=SESSION_ACTIVE;c->active_session=s;
    c->cwd=s->cwd;c->cwd_path=s->cwd_path;
    c->output=s->output;c->output_capacity=c->p.output_bytes;c->output_read=s->output_read;c->output_count=s->output_count;
    c->line=s->line;s->line=0;c->stack=s->stack;s->stack=0;c->stack_bytes=c->p.stack_bytes;
    c->job_id=s->job;c->file_owner=s->job;c->rc=0;c->job_done=0;c->job_active=1;c->seeded=0;
    if(parse(c)){c->rc=2;c->job_done=1;finish_active(c);}
}
int kbl_native_session_create(void*opaque,void**out){
    struct native*c=context(opaque);if(out)*out=0;if(!c||!out||c->in_job||c->polling||c->fault)return KBL_ERROR;
    if(c->session_count==SIZE_MAX)return KBL_ERROR;
    struct native_session*s=alloc_owned(c,sizeof(*s));if(!s)return KBL_ERROR;
    s->output=alloc_owned(c,c->p.output_bytes);if(!s->output){free_owned(c,s);return KBL_ERROR;}
    s->cwd_path=owned_string(c,"/");if(!s->cwd_path){free_owned(c,s->output);free_owned(c,s);return KBL_ERROR;}
    s->cookie=SESSION_COOKIE;s->c=c;s->cwd=c->root;s->next=c->sessions;c->sessions=s;
    ++c->session_count;*out=s;return KBL_OK;
}
int kbl_native_session_submit(void*opaque,void*token,const unsigned char*line,size_t bytes,uint64_t*job){
    struct native*c=context(opaque);struct native_session*s=session(opaque,token);
    if(!s||!job||!line||!bytes||bytes==SIZE_MAX||c->in_job||c->polling||c->fault||v_el(c))return KBL_ERROR;
    if(s->io||s->io_requested||s->state==SESSION_QUEUED||s->state==SESSION_ACTIVE||s->output_count)return KBL_AGAIN;
    for(size_t i=0;i<bytes;++i)if(!line[i]||(line[i]<32&&!space(line[i]))||line[i]==127)return KBL_ERROR;
    AUTO(char*,checked);checked=alloc_owned(c,bytes+1);if(!checked)return KBL_ERROR;copy(checked,line,bytes);checked[bytes]=0;
    AUTO(char**,args);int argc=0;if(parse_arguments(c,checked,&args,&argc))return KBL_ERROR;
    AUTO(char*,saved);saved=alloc_owned(c,bytes+1);if(!saved)return KBL_ERROR;copy(saved,line,bytes);saved[bytes]=0;
    AUTO(void*,stack);stack=alloc_owned(c,c->p.stack_bytes);if(!stack)return KBL_ERROR;
    uint64_t id=next_job(c),ticket=next_ticket(c);if(!id||!ticket)return KBL_ERROR;
    s->line=saved;saved=0;s->stack=stack;stack=0;s->job=id;s->ticket=ticket;s->state=SESSION_QUEUED;
    s->output_read=s->output_count=0;s->rc=0;*job=id;schedule(c);return KBL_OK;
}
static int backend_submit(void*opaque,const unsigned char*line,size_t bytes,uint64_t*job){
    struct native*c=context(opaque);if(!c)return KBL_ERROR;
    /* Original API retains its immediate busy contract for legacy clients. */
    if(c->io_owner)return KBL_AGAIN;
    return kbl_native_session_submit(c,&c->default_session,line,bytes,job);
}
static size_t drain(struct native *c,unsigned char *out,size_t capacity) {
    size_t n=c->output_count; if(n>capacity) n=capacity;
    for(size_t i=0;i<n;++i) out[i]=c->output[(c->output_read+i)%c->output_capacity];
    c->output_read=(c->output_read+n)%c->output_capacity; c->output_count-=n; return n;
}
static int poll_executor(struct native *c,unsigned budget_us) {
#if !defined(__aarch64__)
    (void)budget_us;
#endif
    if(!c->job_active)return KBL_OK;
    if(c->p.current_el(c->p.opaque)!=c->el) { c->fault=1; return KBL_ERROR; }
    if(!c->job_done&&c->output_count<c->output_capacity) {
#if defined(__aarch64__)
        if(!c->seeded) {
            zero(&c->job_cpu,sizeof(c->job_cpu)); kbl_context_seed(&c->job_cpu);
            c->job_cpu.x19_x30[0]=(uintptr_t)c;
            c->job_cpu.x19_x30[11]=(uintptr_t)kbl_fiber_trampoline;
            c->job_cpu.sp=(uintptr_t)c->stack+c->stack_bytes;
            c->seeded=1;
        }
        uint64_t now=c->p.now_us?c->p.now_us(c->p.opaque):0;
        c->deadline=UINT64_MAX-now<budget_us?UINT64_MAX:now+budget_us;
        c->polling=1; c->in_job=1;
        kbl_context_switch(&c->host_cpu,&c->job_cpu);
        c->in_job=0; c->polling=0;
#else
        return KBL_ERROR;
#endif
        if(c->fault||c->p.current_el(c->p.opaque)!=c->el) { c->fault=1; return KBL_ERROR; }
    }
    save_active(c);finish_active(c);return KBL_OK;
}
int kbl_native_session_poll(void*opaque,void*token,uint64_t job,unsigned budget_us,unsigned char*out,
                          size_t capacity,size_t*written,int*done,int*rc){
    struct native*c=context(opaque);struct native_session*s=session(opaque,token);
    if(written)*written=0;if(done)*done=0;
    if(!s||!written||!done||!rc||!capacity||!out||!job||job!=s->job||s->state==SESSION_IDLE||c->in_job||c->polling||c->fault)return KBL_ERROR;
    schedule(c);
    if(s==c->active_session&&c->output_count){*written=drain(c,out,capacity);save_active(c);}
    if(!*written&&poll_executor(c,budget_us))return KBL_ERROR;
    if(!*written){
        if(s==c->active_session){*written=drain(c,out,capacity);save_active(c);}
        else{size_t n=s->output_count;if(n>capacity)n=capacity;
            for(size_t i=0;i<n;++i)out[i]=s->output[(s->output_read+i)%c->p.output_bytes];
            s->output_read=(s->output_read+n)%c->p.output_bytes;s->output_count-=n;*written=n;}
    }
    *done=s->state==SESSION_DONE&&!s->output_count;*rc=s->rc;schedule(c);return KBL_OK;
}
static int backend_poll(void*opaque,uint64_t job,unsigned b,unsigned char*out,size_t n,size_t*w,int*d,int*r){
    struct native*c=context(opaque);return c?kbl_native_session_poll(c,&c->default_session,job,b,out,n,w,d,r):KBL_ERROR;
}
static int backend_stop(void *opaque) {
    struct native *c=context(opaque); if(!c) return KBL_ERROR;
    if(c->in_job||c->polling||c->snapshots) return KBL_AGAIN;
    if(c->io_owner||c->session_count)return KBL_AGAIN;
    if(close_files(c))return KBL_ERROR;
    if(mount_stop_all(c))return KBL_AGAIN;
    kbl_elf_unload(&c->image);
    struct kbl_native_platform p=c->p;
    while(c->owned) free_owned(c,c->owned+1);
    c->cookie=0; p.release(p.opaque,c,sizeof(*c)); return KBL_OK;
}
int kbl_native_backend(struct kbl_backend *out,const struct kbl_native_platform *p) {
    if(!out||!p) return KBL_ERROR;
    *out=(struct kbl_backend){(void *)p,backend_el,backend_start,backend_submit,backend_poll,backend_stop,backend_allocate,backend_release};
    return p->allocate&&p->release&&p->current_el?KBL_OK:KBL_ERROR;
}
int kbl_native_get_stats(void *opaque,struct kbl_native_stats *out) {
    struct native *c=context(opaque); if(!c||!out) return KBL_ERROR;
    *out=(struct kbl_native_stats){c->owned_count,c->owned_bytes,c->ramfs_bytes,c->nodes>UINT_MAX?UINT_MAX:(unsigned)c->nodes,
        c->snapshots>UINT_MAX?UINT_MAX:(unsigned)c->snapshots,c->el,c->job_active,c->in_job,c->job_seed?c->job_seed:c->job_id,0};
    for(struct file_slot*f=c->files;f;f=f->next)if(f->active&&out->open_files<UINT_MAX)++out->open_files;return KBL_OK;
}
#include "kbl_io.inc"
