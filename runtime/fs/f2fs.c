/* SPDX-License-Identifier: GPL-3.0-or-later
 * Explicit read-only KBL adapter to the GNU GRUB 2.14 F2FS reader.
 * No Linux ABI, crypto keys, drivers, constructors, firmware imports or writes.
 */
#include "f2fs.h"
typedef uint8_t grub_uint8_t;
typedef uint16_t grub_uint16_t;
typedef uint32_t grub_uint32_t;
typedef uint64_t grub_uint64_t;
typedef uint64_t grub_disk_addr_t;
typedef uint64_t grub_off_t;
typedef size_t grub_size_t;
typedef ptrdiff_t grub_ssize_t;
typedef int grub_err_t;
#define GRUB_PACKED __attribute__((packed))
#define GRUB_DISK_SECTOR_BITS 9
#define grub_le_to_cpu16(a) ((uint16_t)(a))
#define grub_le_to_cpu32(a) ((uint32_t)(a))
#define grub_le_to_cpu64(a) ((uint64_t)(a))
#define grub_cpu_to_le32_compile_time(a) ((uint32_t)(a))
#define grub_cpu_to_le64_compile_time(a) ((uint64_t)(a))
#define GRUB_ERR_NONE 0
#define GRUB_ERR_BAD_FS (-KBL_EINVAL)
#define GRUB_ERR_OUT_OF_RANGE (-KBL_EOVERFLOW)
#define GRUB_ERR_NOT_IMPLEMENTED_YET (-KBL_ENOSYS)
#define N_(s) s
#define grub_add(a,b,p) ((uint64_t)(a)>((uint64_t)(size_t)-1)-(uint64_t)(b)?1:(*(p)=(a)+(b),0))
struct grub_f2fs_data;
struct grub_fshelp_node;
struct f2_file;
struct f2_volume {
    struct kbl_fs_host host;
    struct kbl_fs_device device;
    struct grub_f2fs_data *data;
    struct f2_file *files;
};
typedef struct f2_volume *grub_disk_t;
typedef struct grub_fshelp_node *grub_fshelp_node_t;
enum grub_fshelp_filetype {GRUB_FSHELP_UNKNOWN,GRUB_FSHELP_REG,GRUB_FSHELP_DIR,GRUB_FSHELP_SYMLINK};
typedef int (*grub_fshelp_iterate_dir_hook_t)(const char*,enum grub_fshelp_filetype,grub_fshelp_node_t,void*);
typedef int (*grub_fs_dir_hook_t)(const char*,const void*,void*);
typedef void (*grub_disk_read_hook_t)(uint64_t,unsigned,unsigned,void*);
/* KBL serializes backend calls. The active allocator exists only during an
 * explicit operation, never as a constructor or a firmware global callback. */
static struct f2_volume *f2_active;
static int grub_errno;
static size_t f2_io_confirmed;
static grub_err_t grub_error(int e,const char*fmt,...){(void)fmt;grub_errno=e;return e;}
static void *grub_memcpy(void*d,const void*s,size_t n){unsigned char*a=d;const unsigned char*b=s;size_t i;for(i=0;i<n;i++)a[i]=b[i];return d;}
static void *grub_memset(void*d,int c,size_t n){unsigned char*a=d;size_t i;for(i=0;i<n;i++)a[i]=(unsigned char)c;return d;}
static size_t f2_strlen(const char*s){size_t n=0;while(s[n])n++;return n;}
static int f2_equal(const char*a,const char*b){size_t i=0;while(a[i]&&a[i]==b[i])i++;return a[i]==b[i];}
struct f2_allocation {size_t bytes;uint64_t alignment;};
static void *grub_malloc(size_t n){struct f2_allocation*h;if(!f2_active||n>(size_t)-1-sizeof(*h)){grub_errno=-KBL_ENOMEM;return 0;}h=f2_active->host.allocate(f2_active->host.opaque,sizeof(*h)+n,16);if(!h){grub_errno=-KBL_ENOMEM;return 0;}h->bytes=sizeof(*h)+n;return h+1;}
static void grub_free(void*p){struct f2_allocation*h;if(!p)return;h=(struct f2_allocation*)p-1;f2_active->host.release(f2_active->host.opaque,h,h->bytes);}
static void *grub_zalloc(size_t n){void*p=grub_malloc(n);if(p)grub_memset(p,0,n);return p;}
static grub_err_t grub_disk_read(grub_disk_t d,grub_disk_addr_t sector,size_t offset,size_t n,void*b){
    uint64_t off;size_t got,done=0;int e;
    f2_io_confirmed=0;
    if(sector>UINT64_MAX/512)return grub_error(-KBL_EOVERFLOW,"sector overflow");
    off=sector*512;if(offset>UINT64_MAX-off)return grub_error(-KBL_EOVERFLOW,"offset overflow");off+=offset;
    if(off>d->device.bytes||n>d->device.bytes-off)return grub_error(-KBL_EIO,"outside source");
    while(done<n){got=0;e=d->device.read_at(d->device.opaque,off+done,(unsigned char*)b+done,n-done,&got);
        if(got>n-done)return grub_error(-KBL_EIO,"invalid source read count");
        f2_io_confirmed=done+got;
        if(e||!got)return grub_error(e<0?e:-KBL_EIO,"source read");
        done+=got;
    }return 0;
}
static grub_ssize_t grub_fshelp_read_file(grub_disk_t disk,grub_fshelp_node_t node,grub_disk_read_hook_t hook,void*hook_data,grub_off_t pos,grub_size_t len,char*buf,grub_disk_addr_t(*map)(grub_fshelp_node_t,grub_disk_addr_t),grub_off_t size,unsigned block_log,unsigned skip){
    uint64_t block,bps=(uint64_t)512<<block_log;size_t done=0,n,in;grub_disk_addr_t physical;(void)hook;(void)hook_data;(void)skip;
    if(pos>=size)return 0;if(len>size-pos)len=(size_t)(size-pos);
    while(done<len){block=pos/bps;in=(size_t)(pos%bps);n=(size_t)bps-in;if(n>len-done)n=len-done;
        physical=map(node,block);if(grub_errno)return(grub_ssize_t)done;
        if(physical==0||physical==0xffffffffu)grub_memset(buf+done,0,n);
        else if(physical>=0xfffffffeu){grub_error(-KBL_ENOSYS,"compressed/unmapped block");return(grub_ssize_t)done;}
        else if(grub_disk_read(disk,physical<<block_log,in,n,buf+done))return(grub_ssize_t)(done+f2_io_confirmed);
        done+=n;pos+=n;
    }return(grub_ssize_t)done;
}
#include "../../third_party/grub_f2fs/f2fs_core.inc"

struct f2_file {struct grub_fshelp_node node;struct f2_file*next;};
static void activate(struct f2_volume*v){f2_active=v;grub_errno=0;}
static int node_check(struct grub_fshelp_node*n){
    struct grub_f2fs_inode*i;unsigned extra,xattr;uint32_t flags;int e;
    if(!n->inode_read){e=grub_f2fs_read_node(n->data,n->ino,&n->inode);if(e)return e;n->inode_read=1;}
    i=&n->inode.i;extra=kbl_f2fs_extra_words(i);xattr=kbl_f2fs_xattr_words(i);
    if((i->i_inline&0x20)&&(((uint16_t)i->i_addr[0]&3)||extra>64))return-KBL_EINVAL;
    if(extra+xattr+1>=DEF_ADDRS_PER_INODE)return-KBL_EINVAL;
    flags=grub_le_to_cpu32(i->i_flags);
    if((i->i_advise&0x04)||(flags&0x800u))return-KBL_EACCES; /* encrypted inode, no keys */
    if(flags&0x04u)return-KBL_ENOSYS; /* compressed file, no decompressor */
    if((i->i_inline&F2FS_INLINE_DATA)&&grub_f2fs_file_size(i)>kbl_f2fs_inline_bytes(i))return-KBL_EINVAL;
    return 0;
}
static unsigned node_type(struct grub_fshelp_node*n){unsigned mode=grub_le_to_cpu16(n->inode.i.i_mode)&0170000u;return mode==0040000?KBL_FILE_DIRECTORY:mode==0100000?KBL_FILE_REGULAR:0;}
struct find_context {const char*name;struct grub_fshelp_node*found;};
static int find_hook(const char*name,enum grub_fshelp_filetype type,grub_fshelp_node_t n,void*opaque){struct find_context*c=opaque;(void)type;if(f2_equal(name,c->name)){c->found=n;return 1;}grub_free(n);return 0;}
static int normalize_path(const char*input,char**result){
    size_t i=0,n=1,start,len,bytes;char*out;if(!result)return-KBL_EINVAL;*result=0;
    if(!input||*input!='/')return-KBL_EINVAL;bytes=f2_strlen(input);if(bytes>SIZE_MAX-2)return-KBL_EOVERFLOW;
    out=grub_malloc(bytes+2);if(!out)return grub_errno;out[0]='/';
    while(input[i]){while(input[i]=='/')i++;start=i;while(input[i]&&input[i]!='/')i++;len=i-start;if(!len)break;if(len==1&&input[start]=='.')continue;
        if(len==2&&input[start]=='.'&&input[start+1]=='.'){if(n>1){while(n>1&&out[n-1]!='/')n--;if(n>1)n--;}continue;}
        if(len>255){grub_free(out);return-KBL_ENAMETOOLONG;}if(n>1)out[n++]='/';grub_memcpy(out+n,input+start,len);n+=len;
    }out[n]=0;*result=out;return 0;
}
struct f2_link_state {struct f2_link_state*next;uint32_t inode,parent;size_t tail_bytes;char tail[];};
static void link_states_free(struct f2_link_state*s){while(s){struct f2_link_state*next=s->next;grub_free(s);s=next;}}
/* Re-entering the same link in the same parent before consuming its old tail
 * is recursive expansion, including growing loops. A shorter tail is progress:
 * /root-link/root-link/file must remain valid when root-link targets '/'. */
static int link_state_add(struct f2_link_state**states,uint32_t inode,uint32_t parent,const char*tail){
    size_t n=f2_strlen(tail);for(struct f2_link_state*s=*states;s;s=s->next)
        if(s->inode==inode&&s->parent==parent&&n>=s->tail_bytes&&f2_equal(tail+n-s->tail_bytes,s->tail))return-KBL_ELOOP;
    if(n>SIZE_MAX-sizeof(struct f2_link_state)-1)return-KBL_EOVERFLOW;
    struct f2_link_state*s=grub_malloc(sizeof(*s)+n+1);if(!s)return grub_errno;
    s->inode=inode;s->parent=parent;s->tail_bytes=n;grub_memcpy(s->tail,tail,n+1);s->next=*states;*states=s;return 0;
}
static int resolve_path(struct f2_volume*v,const char*path,struct grub_fshelp_node**out){
    struct grub_fshelp_node*cur=0,*next;struct find_context c;char*normalized=0;char component[256];size_t pos=1,start,len,parent_len,link_len,tail_len;char*link;unsigned mode;int e;
    struct f2_link_state*states=0;*out=0;if((e=normalize_path(path,&normalized)))return e;
restart:
    cur=grub_malloc(sizeof(*cur));if(!cur){e=grub_errno;goto fail;}grub_memcpy(cur,&v->data->diropen,sizeof(*cur));
    while(normalized[pos]){
        if((e=node_check(cur)))goto fail;if(node_type(cur)!=KBL_FILE_DIRECTORY){e=-KBL_ENOTDIR;goto fail;}
        start=pos;while(normalized[pos]&&normalized[pos]!='/')pos++;len=pos-start;grub_memcpy(component,normalized+start,len);component[len]=0;
        c.name=component;c.found=0;grub_f2fs_iterate_dir(cur,find_hook,&c);if(grub_errno){e=grub_errno;if(c.found)grub_free(c.found);goto fail;}
        if(!c.found){e=-KBL_ENOENT;goto fail;}next=c.found;
        if((e=node_check(next))){grub_free(next);goto fail;}mode=grub_le_to_cpu16(next->inode.i.i_mode)&0170000u;
        if(mode==0120000u){if((e=link_state_add(&states,next->ino,cur->ino,normalized+pos))){grub_free(next);goto fail;}
            link=grub_f2fs_read_symlink(next);grub_free(next);if(!link){e=grub_errno?grub_errno:-KBL_EIO;goto fail;}
            link_len=f2_strlen(link);tail_len=f2_strlen(normalized+pos);parent_len=start;
            if(link[0]=='/')parent_len=0;
            if(tail_len==SIZE_MAX||link_len>SIZE_MAX-tail_len-1||parent_len>SIZE_MAX-link_len-tail_len-1){grub_free(link);e=-KBL_EOVERFLOW;goto fail;}
            char*combined=grub_malloc(parent_len+link_len+tail_len+1),*fresh=0;
            if(!combined){grub_free(link);e=grub_errno;goto fail;}
            if(parent_len)grub_memcpy(combined,normalized,parent_len);grub_memcpy(combined+parent_len,link,link_len);grub_memcpy(combined+parent_len+link_len,normalized+pos,tail_len+1);grub_free(link);
            e=normalize_path(combined,&fresh);grub_free(combined);if(e)goto fail;
            grub_free(cur);cur=0;grub_free(normalized);normalized=fresh;
            pos=1;goto restart;
        }
        grub_free(cur);cur=next;if(normalized[pos]=='/')pos++;
    }
    if((e=node_check(cur)))goto fail;*out=cur;grub_free(normalized);link_states_free(states);return 0;
fail:grub_free(cur);grub_free(normalized);link_states_free(states);return e;
}
static int resolve(struct f2_volume*v,const char*p,struct grub_fshelp_node**out){return resolve_path(v,p,out);}
static struct f2_file*file_check(struct f2_volume*v,void*p){struct f2_file*f;for(f=v->files;f;f=f->next)if(f==p)return f;return 0;}
static void fill_stat(struct grub_fshelp_node*n,struct kbl_file_stat*s){grub_memset(s,0,sizeof(*s));s->bytes=grub_f2fs_file_size(&n->inode.i);s->type=node_type(n);s->flags=KBL_FILE_READ_ONLY;s->block_bytes=4096;}
static int f2_open(void*opaque,const char*p,unsigned flags,void**out){struct f2_volume*v=opaque;struct grub_fshelp_node*n;struct f2_file*f;int e;if(!out)return-KBL_EINVAL;*out=0;if(flags!=KBL_FILE_READ)return(flags&~KBL_FILE_READ)?-KBL_EROFS:-KBL_EINVAL;activate(v);if((e=resolve(v,p,&n)))return e;if(node_type(n)!=KBL_FILE_REGULAR){grub_free(n);return-KBL_EISDIR;}f=grub_malloc(sizeof(*f));if(!f){grub_free(n);return grub_errno;}grub_memcpy(&f->node,n,sizeof(f->node));grub_free(n);f->next=v->files;v->files=f;*out=f;return 0;}
static int f2_close(void*opaque,void*file){struct f2_volume*v=opaque;struct f2_file*f,**q;activate(v);f=file_check(v,file);if(!f)return-KBL_EBADF;for(q=&v->files;*q&&*q!=f;q=&(*q)->next){}*q=f->next;grub_free(f);return 0;}
static int f2_read(void*opaque,void*file,uint64_t off,void*b,size_t n,size_t*done){struct f2_volume*v=opaque;struct f2_file*f;grub_ssize_t got;if(!done)return-KBL_EINVAL;*done=0;activate(v);f=file_check(v,file);if(!f)return-KBL_EBADF;if(n&&!b)return-KBL_EINVAL;if(!n||off>=grub_f2fs_file_size(&f->node.inode.i))return 0;if(n>65536)n=65536;got=grub_f2fs_read_file(&f->node,0,0,off,n,b);if(got>=0)*done=(size_t)got;if(got<0||grub_errno)return grub_errno?grub_errno:-KBL_EIO;return 0;}
static int f2_write(void*v,void*f,uint64_t off,const void*b,size_t n,size_t*done){(void)v;(void)f;(void)off;(void)b;(void)n;if(done)*done=0;return-KBL_EROFS;}
static int f2_fstat(void*opaque,void*file,struct kbl_file_stat*s){struct f2_volume*v=opaque;struct f2_file*f;activate(v);f=file_check(v,file);if(!f)return-KBL_EBADF;if(!s)return-KBL_EINVAL;fill_stat(&f->node,s);return 0;}
static int f2_stat(void*opaque,const char*p,struct kbl_file_stat*s){struct f2_volume*v=opaque;struct grub_fshelp_node*n;int e;if(!s)return-KBL_EINVAL;activate(v);if((e=resolve(v,p,&n)))return e;fill_stat(n,s);grub_free(n);return 0;}
struct list_context{uint64_t wanted,index;char*name;size_t cap;struct kbl_file_stat*stat;int found,error;};
static int list_hook(const char*name,enum grub_fshelp_filetype type,grub_fshelp_node_t n,void*opaque){struct list_context*c=opaque;size_t len;int e;(void)type;
    if(f2_equal(name,".")||f2_equal(name,"..")){grub_free(n);return 0;}
    if(c->index++!=c->wanted){grub_free(n);return 0;}len=f2_strlen(name);
    if(len>=c->cap)c->error=-KBL_ENAMETOOLONG;
    else if(!n->inode_read&&(e=grub_f2fs_read_node(n->data,n->ino,&n->inode)))c->error=e;
    else{grub_memcpy(c->name,name,len+1);fill_stat(n,c->stat);c->found=1;}
    grub_free(n);return 1;
}
static int f2_list(void*opaque,const char*p,uint64_t index,char*name,size_t cap,struct kbl_file_stat*s,int*end){struct f2_volume*v=opaque;struct grub_fshelp_node*n;struct list_context c;int e;if(!name||!cap||!s||!end)return-KBL_EINVAL;*end=0;name[0]=0;activate(v);if((e=resolve(v,p,&n)))return e;if(node_type(n)!=KBL_FILE_DIRECTORY){grub_free(n);return-KBL_ENOTDIR;}grub_memset(&c,0,sizeof(c));c.wanted=index;c.name=name;c.cap=cap;c.stat=s;grub_f2fs_iterate_dir(n,list_hook,&c);grub_free(n);if(c.error)return c.error;if(grub_errno)return grub_errno;*end=!c.found;return 0;}
static int f2_mutate(void*v,const char*p){(void)v;(void)p;return-KBL_EROFS;}
static int f2_rename(void*v,const char*a,const char*b){(void)v;(void)a;(void)b;return-KBL_EROFS;}
static int f2_sync(void*opaque,void*f){struct f2_volume*v=opaque;if(f&&!file_check(v,f))return-KBL_EBADF;return 0;}
static int f2_unmount(void*opaque){struct f2_volume*v=opaque;struct kbl_fs_host h;if(v->files)return-KBL_EBUSY;activate(v);grub_free(v->data);h=v->host;h.release(h.opaque,v,sizeof(*v));f2_active=0;return 0;}
static const struct kbl_fs_ops f2_ops={f2_unmount,f2_open,f2_close,f2_read,f2_write,f2_fstat,f2_stat,f2_list,f2_mutate,f2_mutate,f2_rename,f2_sync};
int kbl_f2fs_mount(const struct kbl_fs_host*h,const struct kbl_fs_device*d,const char*type,unsigned flags,struct kbl_fs*out){
    struct f2_volume*v;int e;
    if(!out)return-KBL_EINVAL;grub_memset(out,0,sizeof(*out));if(!h||!h->allocate||!h->release||!d||!d->read_at||!type||(!f2_equal(type,"f2fs")&&!f2_equal(type,"auto")))return-KBL_EINVAL;
    if(!(flags&KBL_FS_READ_ONLY))return-KBL_EROFS;if(flags&~KBL_FS_READ_ONLY)return-KBL_EINVAL;
    v=h->allocate(h->opaque,sizeof(*v),16);if(!v)return-KBL_ENOMEM;grub_memset(v,0,sizeof(*v));v->host=*h;v->device=*d;activate(v);
    v->data=grub_f2fs_mount(v);if(!v->data){e=grub_errno?grub_errno:-KBL_EINVAL;goto fail;}
    /* Multi-device layouts need a device-map ABI; do not read a different
     * physical device's block numbers from this single bounded source. */
    if(grub_le_to_cpu32(v->data->sblock.devices[1].segments)){e=-KBL_ENOSYS;goto fail_data;}
    if((e=node_check(&v->data->diropen)))goto fail_data;
    if(node_type(&v->data->diropen)!=KBL_FILE_DIRECTORY){e=-KBL_EINVAL;goto fail_data;}
    out->ops=&f2_ops;out->opaque=v;out->type="f2fs";out->flags=KBL_FS_READ_ONLY;return 0;
fail_data:grub_free(v->data);
fail:h->release(h->opaque,v,sizeof(*v));f2_active=0;return e;
}
