/* SPDX-License-Identifier: GPL-3.0-or-later
 * Synchronous F2FS existing-range write adapter. No constructors or firmware
 * imports. The independent core owns mapping/byte writes/checksums; this layer
 * owns access flags, aliases, failure state and device flush/close ordering. */
#include "f2fs_rw.h"
#include "f2fs_rw_core.h"

struct rw_file {struct rw_file *next;void *reader_file;unsigned access;};
struct rw_volume {
    struct kbl_fs_host host;struct kbl_fs_device device;struct kbl_fs reader;
    struct rw_file *files;int fault;unsigned dirty;
};
static void clear(void*p,size_t n){unsigned char*b=p;while(n--)*b++=0;}
static struct rw_file*find_file(struct rw_volume*v,void*p){struct rw_file*f;for(f=v->files;f;f=f->next)if(f==p)return f;return 0;}
static int latch(struct rw_volume*v,int r){if(!v->fault)v->fault=r<0?r:-KBL_EIO;return v->fault;}
static int refresh(struct rw_volume*v,struct rw_file*f){return kbl_f2fs_refresh_existing(v->reader.opaque,f->reader_file);}
static int rw_sync(void*opaque,void*file){
    struct rw_volume*v=opaque;if(file&&!find_file(v,file))return-KBL_EBADF;if(v->fault)return v->fault;
    if(v->dirty){int r=v->device.flush(v->device.opaque);if(r)return latch(v,r);v->dirty=0;}return 0;
}
static int rw_open(void*opaque,const char*path,unsigned flags,void**out){
    struct rw_volume*v=opaque;struct rw_file*f;void*reader=0;int r;
    if(!out)return-KBL_EINVAL;*out=0;if(v->fault)return v->fault;
    if(!(flags&(KBL_FILE_READ|KBL_FILE_WRITE))||(flags&~63u)||
       ((flags&(KBL_FILE_CREATE|KBL_FILE_TRUNC|KBL_FILE_APPEND))&&!(flags&KBL_FILE_WRITE))||
       ((flags&KBL_FILE_EXCL)&&!(flags&KBL_FILE_CREATE)))return-KBL_EINVAL;
    /* Reject before opening/changing anything; never silently weaken truncate. */
    if(flags&KBL_FILE_TRUNC)return-KBL_ENOSYS;
    r=v->reader.ops->open(v->reader.opaque,path,KBL_FILE_READ,&reader);
    if(r)return r==-KBL_ENOENT&&(flags&KBL_FILE_CREATE)?-KBL_ENOSYS:r;
    if((flags&KBL_FILE_CREATE)&&(flags&KBL_FILE_EXCL)){
        r=v->reader.ops->close(v->reader.opaque,reader);return r?latch(v,r):-KBL_EEXIST;
    }
    f=v->host.allocate(v->host.opaque,sizeof(*f),16);
    if(!f){r=v->reader.ops->close(v->reader.opaque,reader);return r?latch(v,r):-KBL_ENOMEM;}
    clear(f,sizeof(*f));f->reader_file=reader;f->access=flags;f->next=v->files;v->files=f;*out=f;return 0;
}
static int rw_close(void*opaque,void*file){
    struct rw_volume*v=opaque;struct rw_file*f=find_file(v,file),**p;int r;
    if(!f)return-KBL_EBADF;if(v->fault)return v->fault;
    if((f->access&KBL_FILE_WRITE)&&(r=rw_sync(v,f)))return r;
    r=v->reader.ops->close(v->reader.opaque,f->reader_file);if(r)return latch(v,r);
    for(p=&v->files;*p&&*p!=f;p=&(*p)->next){}*p=f->next;
    v->host.release(v->host.opaque,f,sizeof(*f));return 0;
}
static int rw_read(void*opaque,void*file,uint64_t off,void*buffer,size_t n,size_t*done){
    struct rw_volume*v=opaque;struct rw_file*f=find_file(v,file);int r;
    if(!done)return-KBL_EINVAL;*done=0;if(!f||!(f->access&KBL_FILE_READ))return-KBL_EBADF;
    if(n&&!buffer)return-KBL_EINVAL;if(v->fault)return v->fault;if((r=refresh(v,f)))return r;
    return v->reader.ops->read_at(v->reader.opaque,f->reader_file,off,buffer,n,done);
}
static int rw_write(void*opaque,void*file,uint64_t off,const void*buffer,size_t n,size_t*done){
    struct rw_volume*v=opaque;struct rw_file*f=find_file(v,file);int r;
    if(!done)return-KBL_EINVAL;*done=0;if(!f||!(f->access&KBL_FILE_WRITE))return-KBL_EBADF;
    if(n&&!buffer)return-KBL_EINVAL;if(v->fault)return v->fault;if(!n)return 0;if(n>65536)n=65536;
    if((r=refresh(v,f)))return r;
    if(f->access&KBL_FILE_APPEND){struct kbl_file_stat s;r=v->reader.ops->fstat(v->reader.opaque,f->reader_file,&s);if(r)return r;off=s.bytes;}
    r=kbl_f2fs_write_existing(v->reader.opaque,f->reader_file,off,buffer,n,done);
    if(*done>n){*done=0;return latch(v,-KBL_EIO);}if(*done)v->dirty=1;
    /* All unsupported/preflight failures return zero confirmed bytes. Partial
     * writes or I/O failures poison the volume; close/sync do not replay them. */
    if(r>0||r==-KBL_EIO||(*done&&r)||(!r&&*done!=n))return latch(v,r?r:-KBL_EIO);
    return r;
}
static int rw_fstat(void*opaque,void*file,struct kbl_file_stat*s){
    struct rw_volume*v=opaque;struct rw_file*f=find_file(v,file);int r;if(!f)return-KBL_EBADF;if(!s)return-KBL_EINVAL;
    if(v->fault)return v->fault;if((r=refresh(v,f)))return r;r=v->reader.ops->fstat(v->reader.opaque,f->reader_file,s);
    if(!r&&s->type==KBL_FILE_REGULAR)s->flags&=~KBL_FILE_READ_ONLY;return r;
}
static int rw_stat(void*opaque,const char*path,struct kbl_file_stat*s){
    struct rw_volume*v=opaque;if(!s)return-KBL_EINVAL;if(v->fault)return v->fault;
    int r=v->reader.ops->stat(v->reader.opaque,path,s);if(!r&&s->type==KBL_FILE_REGULAR)s->flags&=~KBL_FILE_READ_ONLY;return r;
}
static int rw_list(void*opaque,const char*path,uint64_t index,char*name,size_t cap,struct kbl_file_stat*s,int*end){
    struct rw_volume*v=opaque;if(v->fault)return v->fault;int r=v->reader.ops->list(v->reader.opaque,path,index,name,cap,s,end);
    if(!r&&end&&!*end&&s&&s->type==KBL_FILE_REGULAR)s->flags&=~KBL_FILE_READ_ONLY;return r;
}
static int rw_mutate(void*opaque,const char*path){struct rw_volume*v=opaque;(void)path;return v->fault?v->fault:-KBL_ENOSYS;}
static int rw_rename(void*opaque,const char*a,const char*b){struct rw_volume*v=opaque;(void)a;(void)b;return v->fault?v->fault:-KBL_ENOSYS;}
static int rw_unmount(void*opaque){
    struct rw_volume*v=opaque;struct kbl_fs_host host;int r;if(v->files)return-KBL_EBUSY;if(v->fault)return v->fault;
    if((r=rw_sync(v,0)))return r;r=v->reader.ops->unmount(v->reader.opaque);if(r)return r;
    host=v->host;host.release(host.opaque,v,sizeof(*v));return 0;
}
static const struct kbl_fs_ops rw_ops={rw_unmount,rw_open,rw_close,rw_read,rw_write,rw_fstat,rw_stat,rw_list,rw_mutate,rw_mutate,rw_rename,rw_sync};
int kbl_f2fs_rw_mount(const struct kbl_fs_host*host,const struct kbl_fs_device*device,const char*type,unsigned flags,struct kbl_fs*out){
    struct rw_volume*v;int r;if(out)clear(out,sizeof(*out));
    if(!out||!host||!host->allocate||!host->release||!device||!type||flags)return-KBL_EINVAL;
    if((device->flags&KBL_FS_READ_ONLY)||!device->read_at||!device->write_at||!device->flush)return-KBL_EROFS;
    v=host->allocate(host->opaque,sizeof(*v),16);if(!v)return-KBL_ENOMEM;clear(v,sizeof(*v));v->host=*host;v->device=*device;
    r=kbl_f2fs_rw_reader_mount(host,device,type,KBL_FS_READ_ONLY,&v->reader);
    if(r){host->release(host->opaque,v,sizeof(*v));return r;}
    r=kbl_f2fs_existing_writable(v->reader.opaque);
    if(r){int cleanup=v->reader.ops->unmount(v->reader.opaque);if(cleanup)return cleanup;host->release(host->opaque,v,sizeof(*v));return r;}
    out->ops=&rw_ops;out->opaque=v;out->type="f2fs";return 0;
}
