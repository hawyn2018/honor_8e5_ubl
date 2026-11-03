/* SPDX-License-Identifier: MIT
 * FatFs adapter: explicit mounts only; synchronous, no firmware imports.
 * Every successful mutation is synchronized before returning. An uncertain
 * device write/flush fault is sticky and never replayed by close/unmount.
 */
#include "fat.h"
#include <limits.h>
#include "../../third_party/fatfs/ff.h"
#include "../../third_party/fatfs/diskio.h"

#define FAT_IO_BYTES 65536u
struct fat_file {
    FIL object;
    struct fat_file *next;
    unsigned access;
};
struct fat_volume {
    struct kbl_fs_host host;
    struct kbl_fs_device device;
    FATFS object;
    struct fat_file *files;
    FATFS *registered;
    void *locks;size_t lock_stride;unsigned lock_count;
    unsigned read_only,logical_sector;
    int fault;
};
/* Registered only by kbl_fat_mount; zero data has no execution side effects.
 * Native KBL serializes calls. No callback may yield inside a FatFs operation. */
static struct fat_volume *fat_current;
static FATFS *fat_no_context;
static void fat_activate(struct fat_volume*v){fat_current=v;}
FATFS **kbl_fatfs_context_slot(void){return fat_current?&fat_current->registered:&fat_no_context;}
void *kbl_fatfs_lock_table(void){return fat_current?fat_current->locks:0;}
UINT kbl_fatfs_lock_capacity(void){return fat_current?fat_current->lock_count:0;}
int kbl_fatfs_lock_grow(size_t stride){
    struct fat_volume*v=fat_current;unsigned n;size_t bytes;void*fresh;
    if(!v||!stride||(v->lock_stride&&v->lock_stride!=stride)||v->lock_count==UINT_MAX)return 0;
    n=v->lock_count?(v->lock_count>UINT_MAX/2?UINT_MAX:v->lock_count*2):8;
    if(n>SIZE_MAX/stride)return 0;bytes=(size_t)n*stride;
    fresh=v->host.allocate(v->host.opaque,bytes,16);if(!fresh)return 0;
    kbl_fat_memset(fresh,0,bytes);if(v->locks){kbl_fat_memcpy(fresh,v->locks,(size_t)v->lock_count*stride);v->host.release(v->host.opaque,v->locks,(size_t)v->lock_count*stride);}
    v->locks=fresh;v->lock_count=n;v->lock_stride=stride;return 1;
}
static void fat_dispose(struct fat_volume*v){
    if(v->locks)v->host.release(v->host.opaque,v->locks,(size_t)v->lock_count*v->lock_stride);
    if(fat_current==v)fat_current=0;v->host.release(v->host.opaque,v,sizeof(*v));
}

void *kbl_fat_memcpy(void *d,const void *s,size_t n) {
    unsigned char *a=d;const unsigned char *b=s;size_t i;
    for(i=0;i<n;i++)a[i]=b[i];return d;
}
void *kbl_fat_memset(void *d,int c,size_t n) {
    unsigned char *a=d;size_t i;for(i=0;i<n;i++)a[i]=(unsigned char)c;return d;
}
int kbl_fat_memcmp(const void *a,const void *b,size_t n) {
    const unsigned char *p=a,*q=b;size_t i;
    for(i=0;i<n;i++)if(p[i]!=q[i])return p[i]<q[i]?-1:1;return 0;
}
size_t kbl_fat_strlen(const char *s){size_t n=0;while(s[n])n++;return n;}
char *kbl_fat_strchr(const char *s,int c){for(;;s++){if(*s==(char)c)return(char*)s;if(!*s)return 0;}}
static int same(const char*a,const char*b){return kbl_fat_strlen(a)==kbl_fat_strlen(b)&&!kbl_fat_memcmp(a,b,kbl_fat_strlen(a));}
static int result(FRESULT r) {
    switch(r){case FR_OK:return 0;case FR_NO_FILE:case FR_NO_PATH:return-KBL_ENOENT;
    case FR_INVALID_NAME:case FR_INVALID_PARAMETER:case FR_INVALID_DRIVE:return-KBL_EINVAL;
    case FR_DENIED:return-KBL_EACCES;case FR_EXIST:return-KBL_EEXIST;
    case FR_WRITE_PROTECTED:return-KBL_EROFS;case FR_LOCKED:return-KBL_EBUSY;
    case FR_NOT_ENOUGH_CORE:return-KBL_ENOMEM;case FR_TOO_MANY_OPEN_FILES:return-KBL_EMFILE;
    case FR_NOT_READY:case FR_NOT_ENABLED:return-KBL_ENODEV;
    case FR_NO_FILESYSTEM:return-KBL_EINVAL;case FR_INVALID_OBJECT:return-KBL_EBADF;
    default:return-KBL_EIO;}
}
static struct fat_volume *drive_volume(BYTE d){return d==0?fat_current:0;}
DSTATUS disk_initialize(BYTE d){return disk_status(d);}
DSTATUS disk_status(BYTE d){struct fat_volume*v=drive_volume(d);return !v?STA_NOINIT:(v->read_only?STA_PROTECT:0);}
static int io_span(struct fat_volume*v,LBA_t sector,UINT count,uint64_t*off,size_t*bytes){
    uint64_t n,o;if(!v||!count)return 0;
    if(sector>UINT64_MAX/v->logical_sector)return 0;
    o=(uint64_t)sector*v->logical_sector;n=(uint64_t)count*v->logical_sector;
    if(n>(size_t)-1||o>v->device.bytes||n>v->device.bytes-o)return 0;
    *off=o;*bytes=(size_t)n;return 1;
}
DRESULT disk_read(BYTE d,BYTE*b,LBA_t sector,UINT count){
    struct fat_volume*v=drive_volume(d);uint64_t off;size_t n,got=0;int e;
    if(!b||!io_span(v,sector,count,&off,&n))return RES_PARERR;
    if(v->fault)return RES_ERROR;
    e=v->device.read_at(v->device.opaque,off,b,n,&got);
    return e||got!=n?RES_ERROR:RES_OK;
}
DRESULT disk_write(BYTE d,const BYTE*b,LBA_t sector,UINT count){
    struct fat_volume*v=drive_volume(d);uint64_t off;size_t n,wrote=0;int e;
    if(!b||!io_span(v,sector,count,&off,&n))return RES_PARERR;
    if(v->read_only)return RES_WRPRT;
    if(v->fault)return RES_ERROR;
    e=v->device.write_at(v->device.opaque,off,b,n,&wrote);
    if(e||wrote!=n){v->fault=e<0?e:-KBL_EIO;return RES_ERROR;}return RES_OK;
}
DRESULT disk_ioctl(BYTE d,BYTE cmd,void*b){
    struct fat_volume*v=drive_volume(d);int e;if(!v)return RES_NOTRDY;
    switch(cmd){case CTRL_SYNC:
        /* RO unmount and sync are true no-ops, including no device FlushBlocks. */
        if(v->read_only)return RES_OK;if(v->fault)return RES_ERROR;
        e=v->device.flush?v->device.flush(v->device.opaque):0;
        if(e){v->fault=e<0?e:-KBL_EIO;return RES_ERROR;}return RES_OK;
    case GET_SECTOR_COUNT:if(!b)return RES_PARERR;*(LBA_t*)b=v->device.bytes/v->logical_sector;return RES_OK;
    case GET_SECTOR_SIZE:if(!b)return RES_PARERR;*(WORD*)b=(WORD)v->logical_sector;return RES_OK;
    case GET_BLOCK_SIZE:if(!b)return RES_PARERR;*(DWORD*)b=1;return RES_OK;
    default:return RES_PARERR;}
}
static void root_path(struct fat_volume*v,char path[4]){(void)v;path[0]='0';path[1]=':';path[2]='/';path[3]=0;}
struct fat_path {struct fat_volume*owner;size_t bytes;};
static void path_release(void*address){char*p=*(char**)address;if(p){struct fat_path*h=(struct fat_path*)p-1;h->owner->host.release(h->owner->host.opaque,h,h->bytes);}}
#define FAT_PATH(name) char*name __attribute__((cleanup(path_release)))=0
static int path_for(struct fat_volume*v,const char*path,char**result){
    size_t i,n;struct fat_path*h;char*out;*result=0;if(!path||path[0]!='/')return-KBL_EINVAL;
    for(n=0;path[n];n++){if(path[n]==':'||path[n]=='\\')return-KBL_EINVAL;}
    /* VFS canonicalizes dot components. Reject rather than permit an escape
     * or alias that could defeat native mount/source ownership checks. */
    for(i=0;i<n;i++)if(path[i]=='/'&&path[i+1]=='.'&&
        (path[i+2]==0||path[i+2]=='/'||(path[i+2]=='.'&&(path[i+3]==0||path[i+3]=='/'))))return-KBL_EINVAL;
    if(n>SIZE_MAX-sizeof(*h)-3)return-KBL_EOVERFLOW;
    h=v->host.allocate(v->host.opaque,sizeof(*h)+n+3,16);if(!h)return-KBL_ENOMEM;
    h->owner=v;h->bytes=sizeof(*h)+n+3;out=(char*)(h+1);out[0]='0';out[1]=':';kbl_fat_memcpy(out+2,path,n+1);*result=out;return 0;
}
static int healthy(struct fat_volume*v){fat_activate(v);return v->fault?v->fault:0;}
static int writable(struct fat_volume*v){fat_activate(v);return v->fault?v->fault:(v->read_only?-KBL_EROFS:0);}
static struct fat_file *file_check(struct fat_volume*v,void*p){fat_activate(v);struct fat_file*f;for(f=v->files;f;f=f->next)if(f==p)return f;return 0;}
static int mutation_result(struct fat_volume*v,FRESULT r){if(v->fault)return v->fault;return result(r);}
static int file_sync(struct fat_volume*v,struct fat_file*f){FRESULT r;fat_activate(v);if(v->fault)return v->fault;if(v->read_only)return 0;r=f_sync(&f->object);return mutation_result(v,r);}
static int fat_open(void*opaque,const char*path,unsigned flags,void**out){
    struct fat_volume*v=opaque;struct fat_file*f;FAT_PATH(p);BYTE mode=0;FRESULT r;int e;
    if(!out)return-KBL_EINVAL;*out=0;
    if((e=healthy(v)))return e;
    if(!flags||(flags&~(KBL_FILE_READ|KBL_FILE_WRITE|KBL_FILE_CREATE|KBL_FILE_TRUNC|KBL_FILE_APPEND|KBL_FILE_EXCL))||
       !(flags&(KBL_FILE_READ|KBL_FILE_WRITE))||((flags&(KBL_FILE_CREATE|KBL_FILE_TRUNC|KBL_FILE_APPEND|KBL_FILE_EXCL))&&!(flags&KBL_FILE_WRITE))||
       ((flags&KBL_FILE_EXCL)&&!(flags&KBL_FILE_CREATE)))return-KBL_EINVAL;
    if((flags&KBL_FILE_WRITE)&&(e=writable(v)))return e;
    if((e=path_for(v,path,&p)))return e;
    f=v->host.allocate(v->host.opaque,sizeof(*f),16);if(!f)return-KBL_ENOMEM;kbl_fat_memset(f,0,sizeof(*f));f->access=flags;
    if(flags&KBL_FILE_READ)mode|=FA_READ;if(flags&KBL_FILE_WRITE)mode|=FA_WRITE;
    if(flags&KBL_FILE_CREATE)mode|=(flags&KBL_FILE_EXCL)?FA_CREATE_NEW:(flags&KBL_FILE_TRUNC)?FA_CREATE_ALWAYS:FA_OPEN_ALWAYS;
    r=f_open(&f->object,p,mode);
    if(r!=FR_OK){v->host.release(v->host.opaque,f,sizeof(*f));return mutation_result(v,r);}
    /* Keep every opened object reachable even if later truncate/sync fails. */
    f->next=v->files;v->files=f;
    if((flags&KBL_FILE_TRUNC)&&!(flags&KBL_FILE_CREATE))r=f_truncate(&f->object);
    if(r==FR_OK&&(flags&(KBL_FILE_CREATE|KBL_FILE_TRUNC)))r=f_sync(&f->object);
    if(r!=FR_OK||v->fault){
        e=mutation_result(v,r);
        if(!v->fault){FRESULT cr=f_close(&f->object);if(cr==FR_OK){v->files=f->next;v->host.release(v->host.opaque,f,sizeof(*f));}else{v->fault=result(cr);}}
        return e;
    }
    *out=f;return 0;
}
static int fat_close(void*opaque,void*file){
    struct fat_volume*v=opaque;struct fat_file*f=file_check(v,file),**q;FRESULT r;
    if(!f)return-KBL_EBADF;if(v->fault)return v->fault;
    r=f_close(&f->object);if(r!=FR_OK)return mutation_result(v,r);
    for(q=&v->files;*q&&*q!=f;q=&(*q)->next){}*q=f->next;v->host.release(v->host.opaque,f,sizeof(*f));return 0;
}
static int fat_read(void*opaque,void*file,uint64_t off,void*buffer,size_t bytes,size_t*done){
    struct fat_volume*v=opaque;struct fat_file*f=file_check(v,file);FRESULT r;UINT n;uint64_t left;
    if(!done)return-KBL_EINVAL;*done=0;if(!f)return-KBL_EBADF;if(!(f->access&KBL_FILE_READ))return-KBL_EACCES;
    if(v->fault)return v->fault;if(bytes&&!buffer)return-KBL_EINVAL;
    if(off>=f_size(&f->object)||!bytes)return 0;left=f_size(&f->object)-off;if(bytes>left)bytes=(size_t)left;if(bytes>FAT_IO_BYTES)bytes=FAT_IO_BYTES;
    r=f_lseek(&f->object,off);if(r!=FR_OK)return mutation_result(v,r);
    r=f_read(&f->object,buffer,(UINT)bytes,&n);*done=n;return mutation_result(v,r);
}
static int fat_write(void*opaque,void*file,uint64_t off,const void*buffer,size_t bytes,size_t*done){
    struct fat_volume*v=opaque;struct fat_file*f=file_check(v,file);FRESULT r;UINT n=0;int e;
    unsigned char zero[4096];uint64_t gap;UINT chunk;
    if(!done)return-KBL_EINVAL;*done=0;if(!f)return-KBL_EBADF;if(!(f->access&KBL_FILE_WRITE))return-KBL_EACCES;
    if((e=writable(v)))return e;if(bytes&&!buffer)return-KBL_EINVAL;if(!bytes)return 0;
    if(bytes>FAT_IO_BYTES)bytes=FAT_IO_BYTES;if(f->access&KBL_FILE_APPEND)off=f_size(&f->object);
    if(off>UINT64_MAX-bytes)return-KBL_EFBIG;
    if(v->object.fs_type!=FS_EXFAT&&(off>0xffffffffu||bytes>0xffffffffu-off))return-KBL_EFBIG;
    /* Avoid relying on an extending seek's allocation/zeroing side effects.
     * Advance from EOF with ordinary writes: works for contiguous and FAT-
     * chained exFAT, and guarantees that holes never reveal old media bytes. */
    if(off>f_size(&f->object)){
        gap=off-f_size(&f->object);kbl_fat_memset(zero,0,sizeof(zero));
        r=f_lseek(&f->object,f_size(&f->object));if(r!=FR_OK)return mutation_result(v,r);
        while(gap){chunk=gap>sizeof(zero)?sizeof(zero):(UINT)gap;n=0;
            r=f_write(&f->object,zero,chunk,&n);if(r!=FR_OK)return mutation_result(v,r);
            if(n!=chunk){if((e=file_sync(v,f)))return e;return-KBL_ENOSPC;}gap-=n;
        }
    }
    r=f_lseek(&f->object,off);if(r!=FR_OK)return mutation_result(v,r);
    if(f_tell(&f->object)!=off)return-KBL_ENOSPC;
    r=f_write(&f->object,buffer,(UINT)bytes,&n);*done=n;
    if(r!=FR_OK)return mutation_result(v,r);if((e=file_sync(v,f)))return e;
    return n==bytes?0:-KBL_ENOSPC;
}
static void stat_fill(struct fat_volume*v,struct kbl_file_stat*s,uint64_t bytes,unsigned dir,unsigned attr){
    kbl_fat_memset(s,0,sizeof(*s));s->bytes=bytes;s->type=dir?KBL_FILE_DIRECTORY:KBL_FILE_REGULAR;
    s->flags=(v->read_only||(attr&AM_RDO))?KBL_FILE_READ_ONLY:0;s->block_bytes=v->logical_sector;
}
static int fat_fstat(void*opaque,void*file,struct kbl_file_stat*s){struct fat_volume*v=opaque;struct fat_file*f=file_check(v,file);if(!f)return-KBL_EBADF;if(!s)return-KBL_EINVAL;if(v->fault)return v->fault;stat_fill(v,s,f_size(&f->object),0,0);return 0;}
static int fat_stat(void*opaque,const char*path,struct kbl_file_stat*s){
    struct fat_volume*v=opaque;FAT_PATH(p);FILINFO i;int e;FRESULT r;
    if(!s)return-KBL_EINVAL;if((e=healthy(v)))return e;if((e=path_for(v,path,&p)))return e;
    if(same(path,"/")){stat_fill(v,s,0,1,0);return 0;}r=f_stat(p,&i);if(r!=FR_OK)return mutation_result(v,r);stat_fill(v,s,i.fsize,i.fattrib&AM_DIR,i.fattrib);return 0;
}
static int fat_list(void*opaque,const char*path,uint64_t index,char*name,size_t cap,struct kbl_file_stat*s,int*end){
    struct fat_volume*v=opaque;FAT_PATH(p);DIR d;FILINFO i;FRESULT r,cr;uint64_t n=0;size_t len;int e;
    if(!name||!cap||!s||!end)return-KBL_EINVAL;*end=0;name[0]=0;if((e=healthy(v)))return e;if((e=path_for(v,path,&p)))return e;
    r=f_opendir(&d,p);if(r!=FR_OK)return mutation_result(v,r);
    for(;;){r=f_readdir(&d,&i);if(r!=FR_OK||!i.fname[0])break;if(n++==index)break;}
    cr=f_closedir(&d);if(r!=FR_OK)return mutation_result(v,r);if(cr!=FR_OK)return mutation_result(v,cr);
    if(!i.fname[0]){*end=1;return 0;}len=kbl_fat_strlen(i.fname);if(len>=cap)return-KBL_ENAMETOOLONG;
    kbl_fat_memcpy(name,i.fname,len+1);stat_fill(v,s,i.fsize,i.fattrib&AM_DIR,i.fattrib);return 0;
}
static int fat_mkdir(void*opaque,const char*path){struct fat_volume*v=opaque;FAT_PATH(p);int e;if((e=writable(v)))return e;if((e=path_for(v,path,&p)))return e;return mutation_result(v,f_mkdir(p));}
static int fat_unlink(void*opaque,const char*path){struct fat_volume*v=opaque;FAT_PATH(p);int e;if((e=writable(v)))return e;if((e=path_for(v,path,&p)))return e;return mutation_result(v,f_unlink(p));}
static int fat_rename(void*opaque,const char*old,const char*newp){struct fat_volume*v=opaque;FAT_PATH(p);FAT_PATH(q);int e;if((e=writable(v)))return e;if((e=path_for(v,old,&p)))return e;if((e=path_for(v,newp,&q)))return e;return mutation_result(v,f_rename(p,q));}
static int fat_sync(void*opaque,void*file){struct fat_volume*v=opaque;struct fat_file*f;int e;fat_activate(v);if(v->fault)return v->fault;if(v->read_only)return 0;if(file){f=file_check(v,file);return f?file_sync(v,f):-KBL_EBADF;}for(f=v->files;f;f=f->next)if((e=file_sync(v,f)))return e;return disk_ioctl(0,CTRL_SYNC,0)==RES_OK?0:mutation_result(v,FR_DISK_ERR);}
static int fat_unmount(void*opaque){struct fat_volume*v=opaque;char p[4];int e;if(v->files)return-KBL_EBUSY;if((e=fat_sync(v,0)))return e;root_path(v,p);e=result(f_mount(0,p,0));if(e)return e;fat_dispose(v);return 0;}
static const struct kbl_fs_ops fat_ops={fat_unmount,fat_open,fat_close,fat_read,fat_write,fat_fstat,fat_stat,fat_list,fat_mkdir,fat_unlink,fat_rename,fat_sync};

int kbl_fat_mount(const struct kbl_fs_host*h,const struct kbl_fs_device*d,const char*type,unsigned flags,struct kbl_fs*out){
    struct fat_volume*v;unsigned logical;char p[4];FRESULT r;unsigned char boot[512];size_t got=0;int e;
    if(!out)return-KBL_EINVAL;kbl_fat_memset(out,0,sizeof(*out));
    if(!h||!h->allocate||!h->release||!d||!d->read_at||!type||(flags&~KBL_FS_READ_ONLY)||
       (d->sector_bytes!=512&&d->sector_bytes!=1024&&d->sector_bytes!=2048&&d->sector_bytes!=4096)||
       !d->bytes||d->bytes%d->sector_bytes||(!same(type,"auto")&&!same(type,"fat")&&!same(type,"exfat")))return-KBL_EINVAL;
    if(!(flags&KBL_FS_READ_ONLY)&&((d->flags&KBL_FS_READ_ONLY)||!d->write_at))return-KBL_EROFS;
    /* The backing block IO unit can be 4096 while BPB sectors are 512. Byte
     * IO already performs backing RMW, so derive filesystem geometry from
     * the actual VBR instead of confusing those two sector sizes. */
    if(d->bytes<sizeof(boot))return-KBL_EINVAL;
    e=d->read_at(d->opaque,0,boot,sizeof(boot),&got);if(e||got!=sizeof(boot))return-KBL_EIO;
    logical=(unsigned)boot[11]|((unsigned)boot[12]<<8);
    if(!kbl_fat_memcmp(boot+3,"EXFAT   ",8))logical=boot[108]>=9&&boot[108]<=12?1u<<boot[108]:0;
    if(logical!=512&&logical!=1024&&logical!=2048&&logical!=4096)return-KBL_EINVAL;
    if(d->bytes%logical)return-KBL_EINVAL;
    v=h->allocate(h->opaque,sizeof(*v),16);if(!v)return-KBL_ENOMEM;kbl_fat_memset(v,0,sizeof(*v));v->host=*h;v->device=*d;v->read_only=!!(flags&KBL_FS_READ_ONLY);
    v->logical_sector=logical;
    fat_activate(v);root_path(v,p);r=f_mount(&v->object,p,1);
    if(r==FR_OK&&((same(type,"fat")&&v->object.fs_type==FS_EXFAT)||(same(type,"exfat")&&v->object.fs_type!=FS_EXFAT)))r=FR_NO_FILESYSTEM;
    if(r!=FR_OK){f_mount(0,p,0);fat_dispose(v);return result(r);}
    out->ops=&fat_ops;out->opaque=v;out->flags=flags;out->type=v->object.fs_type==FS_EXFAT?"exfat":"fat";return 0;
}
#if KBL_FATFS_TEST_MKFS
int kbl_fat_test_format(const struct kbl_fs_host*h,const struct kbl_fs_device*d,const char*type){
    struct fat_volume v;struct fat_volume*previous=fat_current;char path[4];unsigned char work[8192];MKFS_PARM opt;FRESULT r;
    if(!h||!d||!d->read_at||!d->write_at||!type||d->flags||!d->bytes||d->bytes%d->sector_bytes)return-KBL_EINVAL;
    kbl_fat_memset(&v,0,sizeof(v));v.host=*h;v.device=*d;v.logical_sector=d->sector_bytes;fat_activate(&v);
    kbl_fat_memset(&opt,0,sizeof(opt));opt.fmt=FM_SFD;
    if(same(type,"fat32"))opt.fmt|=FM_FAT32;
    else if(same(type,"exfat"))opt.fmt|=FM_EXFAT;
    else if(same(type,"fat"))opt.fmt|=FM_FAT;
    else{fat_current=previous;return-KBL_EINVAL;}
    opt.au_size=d->sector_bytes;opt.n_fat=1;root_path(&v,path);
    r=f_mkfs(path,&opt,work,sizeof(work));fat_current=previous;return result(r);
}
#endif
