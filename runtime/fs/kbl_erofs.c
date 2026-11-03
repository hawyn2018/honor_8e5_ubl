/* SPDX-License-Identifier: MIT
 * Explicit read-only EROFS adapter. No disk writes, drivers, constructors, or
 * Linux syscalls. erofs-utils' upstream reader performs data/extent decoding.
 */
#include "kbl_fs.h"
#include <config.h>
#include "erofs/internal.h"
#include "erofs/dir.h"
#include "erofs/decompress.h"

struct er_volume {
    struct kbl_fs_host host;
    struct kbl_fs_device dev;
    struct erofs_sb_info sb;
    size_t allocated;
    unsigned opens;
};
struct er_file { struct erofs_inode inode; };
struct er_alloc { struct er_volume *owner;size_t size; };
/* Filesystem callbacks are serialized/non-yielding by native FS_CALL. Every
 * allocation carries its owner, including allocations freed after a new call. */
static struct er_volume *er_current;

void *kbl_erofs_memcpy(void *d,const void *s,size_t n) {
    unsigned char *a=d;const unsigned char *b=s;while(n--)*a++=*b++;return d;
}
void *kbl_erofs_memset(void *d,int c,size_t n) {
    unsigned char *a=d;while(n--)*a++=(unsigned char)c;return d;
}
void *kbl_erofs_memmove(void *d,const void *s,size_t n){
    unsigned char *a=d;const unsigned char *b=s;if(a>b&&a<b+n){while(n){--n;a[n]=b[n];}}else kbl_erofs_memcpy(d,s,n);return d;
}
void *kbl_erofs_memchr(const void *p,int c,size_t n){const unsigned char *s=p;while(n--){if(*s==(unsigned char)c)return (void*)s;++s;}return NULL;}
int kbl_erofs_memcmp(const void *a,const void *b,size_t n) {
    const unsigned char *x=a,*y=b;while(n--){if(*x!=*y)return *x-*y;++x;++y;}return 0;
}
size_t kbl_erofs_strlen(const char *s){size_t n=0;while(s[n])++n;return n;}
size_t kbl_erofs_strnlen(const char *s,size_t z){size_t n=0;while(n<z&&s[n])++n;return n;}
int kbl_erofs_strncmp(const char *a,const char *b,size_t n){while(n--){unsigned char x=*a++,y=*b++;if(x!=y)return x-y;if(!x)return 0;}return 0;}
int kbl_erofs_strcmp(const char *a,const char *b){while(*a&&*a==*b){++a;++b;}return (unsigned char)*a-(unsigned char)*b;}
char *kbl_erofs_strchr(const char *s,int c){do{if(*s==(char)c)return (char*)s;}while(*s++);return NULL;}
char *kbl_erofs_strrchr(const char *s,int c){const char *p=NULL;do{if(*s==(char)c)p=s;}while(*s++);return (char*)p;}
char *kbl_erofs_strncpy(char *d,const char *s,size_t n){char *out=d;while(n&&*s){*d++=*s++;--n;}while(n--)*d++=0;return out;}
void *kbl_erofs_malloc(size_t n){
    struct er_volume *v=er_current;struct er_alloc *a;
    if(!v||n>SIZE_MAX-sizeof(*a)||v->allocated>SIZE_MAX-n)return NULL;
    a=v->host.allocate(v->host.opaque,sizeof(*a)+n,16);
    if(!a)return NULL;a->owner=v;a->size=n;v->allocated+=n;return a+1;
}
void kbl_erofs_free(void *p){
    struct er_alloc *a;struct er_volume *v;if(!p)return;a=(struct er_alloc*)p-1;v=a->owner;
    v->allocated-=a->size;v->host.release(v->host.opaque,a,sizeof(*a)+a->size);
}
void *kbl_erofs_calloc(size_t n,size_t z){void *p;if(z&&n>SIZE_MAX/z)return NULL;p=kbl_erofs_malloc(n*z);if(p)memset(p,0,n*z);return p;}
void *kbl_erofs_realloc(void *p,size_t n){
    struct er_alloc *a;void *q;if(!p)return kbl_erofs_malloc(n);if(!n){kbl_erofs_free(p);return NULL;}
    a=(struct er_alloc*)p-1;q=kbl_erofs_malloc(n);if(q){memcpy(q,p,n<a->size?n:a->size);kbl_erofs_free(p);}return q;
}
static int er_error(int r){
    if(r>=0)return 0;
    if(r==-EOPNOTSUPP)return -KBL_ENOSYS;
    if(r==-EFSCORRUPTED)return -KBL_EIO;
    return r;
}
static int er_exact(const struct kbl_fs_device *dev,u64 off,void *buf,size_t n){
    size_t at=0;if(off>dev->bytes||n>dev->bytes-off)return -EIO;
    while(at<n){size_t got=0;int r=dev->read_at(dev->opaque,off+at,(u8*)buf+at,n-at,&got);
        if(r)return r;if(!got||got>n-at)return -EIO;at+=got;}return 0;
}
static ssize_t er_dev_pread(struct erofs_vfile *vf,void *buf,size_t n,u64 off){
    struct er_volume *v=*(struct er_volume**)vf->payload;int r=er_exact(&v->dev,off,buf,n);
    return r?r:(ssize_t)n;
}
ssize_t erofs_io_pread(struct erofs_vfile *vf,void *buf,size_t n,u64 off){
    if(!vf||!vf->ops||!vf->ops->pread)return -EIO;
    return vf->ops->pread(vf,buf,n,off);
}
ssize_t erofs_dev_read(struct erofs_sb_info *sb,int id,void *buf,u64 off,size_t n){
    ssize_t r;if(id)return -EOPNOTSUPP;r=erofs_io_pread(&sb->bdev,buf,n,off);return r<0?r:(size_t)r==n?0:-EIO;
}
int erofs_packedfile_read(struct erofs_sb_info *sb,void *buf,erofs_off_t len,erofs_off_t pos){
    struct erofs_inode vi={.sbi=sb,.nid=sb->packed_nid};struct erofs_vfile vf;int r;
    if(!vi.nid)return -EFSCORRUPTED;r=erofs_read_inode_from_disk(&vi);if(r)return r;
    if(pos>vi.i_size||len>vi.i_size-pos||len>SIZE_MAX)return -EFSCORRUPTED;
    r=erofs_iopen(&vf,&vi);return r?r:erofs_pread(&vf,buf,(size_t)len,pos);
}
/* Lookup deliberately checks each parent before scanning directory data. A
 * symlink is resolved inside this volume with owned paths and cycle states. */
struct er_link_state {struct er_link_state*next;erofs_nid_t inode,parent;size_t tail_bytes;char tail[];};
static void er_link_states_free(struct er_link_state*s){while(s){struct er_link_state*next=s->next;free(s);s=next;}}
/* Match both directory context and unconsumed suffix. Repeated traversal with
 * a shrinking tail is finite progress; equal/growing tail means recursion. */
static int er_link_state_add(struct er_link_state**states,erofs_nid_t inode,erofs_nid_t parent,const char*tail){
    size_t n=strlen(tail);for(struct er_link_state*s=*states;s;s=s->next)
        if(s->inode==inode&&s->parent==parent&&n>=s->tail_bytes&&!strcmp(tail+n-s->tail_bytes,s->tail))return-ELOOP;
    if(n>SIZE_MAX-sizeof(struct er_link_state)-1)return-EOVERFLOW;
    struct er_link_state*s=malloc(sizeof(*s)+n+1);if(!s)return-ENOMEM;
    s->inode=inode;s->parent=parent;s->tail_bytes=n;memcpy(s->tail,tail,n+1);s->next=*states;*states=s;return 0;
}
struct er_find {struct erofs_dir_context ctx;const char *name;size_t len;erofs_nid_t nid;int found;};
static int er_find_cb(struct erofs_dir_context *ctx){
    struct er_find *f=(struct er_find*)ctx;
    if(ctx->de_namelen==f->len&&!memcmp(ctx->dname,f->name,f->len)){f->nid=ctx->de_nid;f->found=1;return 1;}return 0;
}
static int er_lookup(struct er_volume *v,const char *path,struct erofs_inode *out){
    char *work,*next=NULL;size_t pos=0,base=0,len;int r=0;struct er_link_state*states=NULL;
    struct erofs_inode vi={.sbi=&v->sb,.nid=v->sb.root_nid};
    er_current=v;if(!path||path[0]!='/')return -KBL_EINVAL;len=strlen(path);if(len==SIZE_MAX)return -KBL_EOVERFLOW;
    work=malloc(len+1);if(!work)return -KBL_ENOMEM;memcpy(work,path,len+1);
restart:
    vi=(struct erofs_inode){.sbi=&v->sb,.nid=v->sb.root_nid};pos=0;base=0;
    r=erofs_read_inode_from_disk(&vi);if(r)goto done;
    while(work[pos]){
        size_t start,n;struct er_find f;erofs_nid_t parent=vi.nid;
        while(work[pos]=='/')++pos;if(!work[pos])break;start=pos;while(work[pos]&&work[pos]!='/')++pos;n=pos-start;
        if(n==1&&work[start]=='.')continue;
        if(!S_ISDIR(vi.i_mode)){r=-ENOTDIR;goto done;}
        memset(&f,0,sizeof(f));f.ctx.dir=&vi;f.ctx.cb=er_find_cb;f.name=work+start;f.len=n;
        r=erofs_iterate_dir(&f.ctx,true);if(r<0)goto done;if(!f.found){r=-ENOENT;goto done;}
        vi=(struct erofs_inode){.sbi=&v->sb,.nid=f.nid};r=erofs_read_inode_from_disk(&vi);if(r)goto done;
        if(S_ISLNK(vi.i_mode)){
            struct erofs_vfile vf;size_t remain=strlen(work+pos),prefix=start,link;
            r=er_link_state_add(&states,vi.nid,parent,work+pos);if(r)goto done;
            if(!vi.i_size||vi.i_size>SIZE_MAX){r=-ENAMETOOLONG;goto done;}link=(size_t)vi.i_size;
            if(remain==SIZE_MAX||link>SIZE_MAX-remain-1||prefix>SIZE_MAX-link-remain-1){r=-EOVERFLOW;goto done;}
            next=malloc(prefix+link+remain+1);if(!next){r=-ENOMEM;goto done;}
            r=erofs_iopen(&vf,&vi);if(r)goto done;r=erofs_pread(&vf,next+prefix,link,0);if(r)goto done;
            if(memchr(next+prefix,0,link)){r=-EFSCORRUPTED;goto done;}
            if(next[prefix]=='/'){memmove(next,next+prefix,link);prefix=0;}else memcpy(next,work,prefix);
            memcpy(next+prefix+link,work+pos,remain+1);free(work);work=next;next=NULL;goto restart;
        }
        base=pos;
    }
    (void)base;*out=vi;r=0;
done:free(next);free(work);er_link_states_free(states);return er_error(r);
}
static void er_stat_fill(struct er_volume *v,const struct erofs_inode *vi,struct kbl_file_stat *st){
    memset(st,0,sizeof(*st));st->bytes=vi->i_size;st->type=S_ISDIR(vi->i_mode)?KBL_FILE_DIRECTORY:KBL_FILE_REGULAR;
    st->flags=KBL_FILE_READ_ONLY;st->block_bytes=erofs_blksiz(&v->sb);
}
static int er_unmount(void *p){struct er_volume *v=p;struct kbl_fs_host h=v->host;if(v->opens||v->allocated)return -KBL_EBUSY;if(er_current==v)er_current=NULL;h.release(h.opaque,v,sizeof(*v));return 0;}
static int er_open(void *p,const char *path,unsigned flags,void **out){
    struct er_volume *v=p;struct er_file *f;struct erofs_inode vi;int r;*out=NULL;
    if(flags&~KBL_FILE_READ)return -KBL_EROFS;if(!(flags&KBL_FILE_READ))return -KBL_EINVAL;
    r=er_lookup(v,path,&vi);if(r)return r;if(S_ISDIR(vi.i_mode))return -KBL_EISDIR;if(!S_ISREG(vi.i_mode))return -KBL_ENOSYS;
    f=v->host.allocate(v->host.opaque,sizeof(*f),16);if(!f)return -KBL_ENOMEM;f->inode=vi;++v->opens;*out=f;return 0;
}
static int er_close(void *p,void *f){struct er_volume *v=p;v->host.release(v->host.opaque,f,sizeof(struct er_file));--v->opens;return 0;}
static int er_read(void *p,void *f,uint64_t off,void *buf,size_t n,size_t *done){
    struct er_volume *v=p;struct er_file *file=f;struct erofs_vfile vf;int r;*done=0;er_current=v;
    if(off>=file->inode.i_size)return 0;if(n>file->inode.i_size-off)n=(size_t)(file->inode.i_size-off);
    r=erofs_iopen(&vf,&file->inode);if(!r)r=erofs_pread(&vf,buf,n,off);if(!r)*done=n;return er_error(r);
}
static int er_write(void *p,void *f,uint64_t o,const void *b,size_t n,size_t *d){(void)p;(void)f;(void)o;(void)b;(void)n;*d=0;return -KBL_EROFS;}
static int er_fstat(void *p,void *f,struct kbl_file_stat *st){er_stat_fill(p,&((struct er_file*)f)->inode,st);return 0;}
static int er_stat(void *p,const char *path,struct kbl_file_stat *st){struct erofs_inode vi;int r=er_lookup(p,path,&vi);if(!r)er_stat_fill(p,&vi,st);return r;}
struct er_listing {struct erofs_dir_context ctx;struct er_volume *v;uint64_t index;char *name;size_t cap;struct kbl_file_stat *st;int found;};
static int er_list_cb(struct erofs_dir_context *ctx){
    struct er_listing *l=(struct er_listing*)ctx;struct erofs_inode vi={.sbi=&l->v->sb,.nid=ctx->de_nid};int r;
    if(ctx->dot_dotdot)return 0;if(l->index){--l->index;return 0;}
    if(!ctx->de_namelen||ctx->de_namelen>=l->cap)return -ENAMETOOLONG;
    r=erofs_read_inode_from_disk(&vi);if(r)return r;
    memcpy(l->name,ctx->dname,ctx->de_namelen);l->name[ctx->de_namelen]=0;er_stat_fill(l->v,&vi,l->st);l->found=1;return 1;
}
static int er_list(void *p,const char *path,uint64_t index,char *name,size_t cap,struct kbl_file_stat *st,int *end){
    struct er_volume *v=p;struct erofs_inode vi;struct er_listing l;int r;*end=0;
    r=er_lookup(v,path,&vi);if(r)return r;if(!S_ISDIR(vi.i_mode))return -KBL_ENOTDIR;
    memset(&l,0,sizeof(l));l.ctx.dir=&vi;l.ctx.cb=er_list_cb;l.v=v;l.index=index;l.name=name;l.cap=cap;l.st=st;
    r=erofs_iterate_dir(&l.ctx,true);if(r<0)return er_error(r);*end=!l.found;return 0;
}
static int er_mut(void *v,const char *p){(void)v;(void)p;return -KBL_EROFS;}
static int er_rename(void *v,const char *a,const char *b){(void)v;(void)a;(void)b;return -KBL_EROFS;}
static int er_sync(void *v,void *f){(void)v;(void)f;return 0;}
static const struct kbl_fs_ops er_ops={er_unmount,er_open,er_close,er_read,er_write,er_fstat,er_stat,er_list,er_mut,er_mut,er_rename,er_sync};
int kbl_erofs_mount(const struct kbl_fs_host *host,const struct kbl_fs_device *dev,const char *type,unsigned flags,struct kbl_fs *out){
    struct er_volume *v;struct erofs_super_block *sb;struct erofs_inode root;unsigned char buf[4096];uint64_t blocks;int r;
    static struct erofs_vfops io={.pread=er_dev_pread};(void)type;memset(out,0,sizeof(*out));
    if(!(flags&KBL_FS_READ_ONLY))return -KBL_EROFS;
    r=er_exact(dev,0,buf,sizeof(buf));if(r)return r;
    sb=(struct erofs_super_block*)(buf+1024);
    if(le32_to_cpu(sb->magic)!=EROFS_SUPER_MAGIC_V1)return -KBL_EINVAL;
    if(sb->blkszbits<9||sb->blkszbits>12||sb->dirblkbits||sb->sb_extslots>1)return -KBL_ENOSYS;
    if(le32_to_cpu(sb->feature_incompat)&~EROFS_ALL_FEATURE_INCOMPAT)return -KBL_ENOSYS;
    /* External blob devices require a separately supplied mapping; this API is
     * deliberately one source. Compressed metadata is not enabled recursively. */
    if(le16_to_cpu(sb->extra_devices)||(le32_to_cpu(sb->feature_incompat)&EROFS_FEATURE_INCOMPAT_METABOX))return -KBL_ENOSYS;
    blocks=le32_to_cpu(sb->blocks_lo);if(le32_to_cpu(sb->feature_incompat)&EROFS_FEATURE_INCOMPAT_48BIT)blocks|=(uint64_t)le16_to_cpu(sb->rb.blocks_hi)<<32;
    if(!blocks||blocks>dev->bytes>>sb->blkszbits)return -KBL_EIO;
    if(le32_to_cpu(sb->feature_compat)&EROFS_FEATURE_COMPAT_SB_CHKSUM){
        uint32_t expected=le32_to_cpu(sb->checksum),len=1u<<sb->blkszbits;if(len>1024)len-=1024;
        if(len>3072){r=er_exact(dev,1024,buf,len);if(r)return r;sb=(void*)buf;}
        sb->checksum=0;if(erofs_crc32c(~0u,(const u8*)sb,len)!=expected)return -KBL_EIO;
        /* Re-read because smaller block sizes checksum only a prefix. */
        r=er_exact(dev,0,buf,sizeof(buf));if(r)return r;sb=(void*)(buf+1024);
    }
    v=host->allocate(host->opaque,sizeof(*v),16);if(!v)return -KBL_ENOMEM;memset(v,0,sizeof(*v));v->host=*host;v->dev=*dev;er_current=v;
    v->sb.blkszbits=sb->blkszbits;v->sb.sb_size=128+sb->sb_extslots*16;v->sb.feature_compat=le32_to_cpu(sb->feature_compat);v->sb.feature_incompat=le32_to_cpu(sb->feature_incompat);
    v->sb.meta_blkaddr=(int32_t)le32_to_cpu(sb->meta_blkaddr);v->sb.xattr_blkaddr=le32_to_cpu(sb->xattr_blkaddr);
    v->sb.root_nid=erofs_sb_has_48bit(&v->sb)&&le64_to_cpu(sb->rootnid_8b)?le64_to_cpu(sb->rootnid_8b):le16_to_cpu(sb->rb.rootnid_2b);
    v->sb.primarydevice_blocks=blocks;v->sb.total_blocks=blocks;v->sb.packed_nid=le64_to_cpu(sb->packed_nid);v->sb.epoch=le64_to_cpu(sb->epoch);v->sb.fixed_nsec=le32_to_cpu(sb->fixed_nsec);
    v->sb.bdev.ops=&io;*(struct er_volume**)v->sb.bdev.payload=v;
    r=z_erofs_parse_cfgs(&v->sb,sb);
    if(!r&&(v->sb.available_compr_algs&~(1u<<Z_EROFS_COMPRESSION_LZ4)))r=-EOPNOTSUPP;
    if(!r){root=(struct erofs_inode){.sbi=&v->sb,.nid=v->sb.root_nid};r=erofs_read_inode_from_disk(&root);if(!r&&!S_ISDIR(root.i_mode))r=-EFSCORRUPTED;}
    if(r){er_current=NULL;host->release(host->opaque,v,sizeof(*v));return er_error(r);}
    out->ops=&er_ops;out->opaque=v;out->flags=KBL_FS_READ_ONLY;out->type="erofs";return 0;
}
