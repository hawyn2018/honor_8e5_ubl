/* Real native VFS + FatFs; host-only backing image, no USB/device IO. */
#include "host_fixture.c"
#include "../../fs/fat.h"
static unsigned mkdir_calls,stat_calls;
static const struct kbl_fs_ops*fat_original;
static struct kbl_fs_ops traced;
static unsigned parent_fault;
static int fat_stat(void*v,const char*p,struct kbl_file_stat*s){++stat_calls;if(parent_fault==1&&!strcmp(p,"/probe"))return-KBL_EIO;return fat_original->stat(v,p,s);}
static int fat_mkdir(void*v,const char*p){
    ++mkdir_calls;
    if(parent_fault==2&&!strcmp(p,"/race")){int r=fat_original->mkdir(v,p);return r?r:-KBL_EEXIST;}
    if(parent_fault==3&&!strcmp(p,"/racerfile")){void*f=0;int r=fat_original->open(v,p,KBL_FILE_WRITE|KBL_FILE_CREATE,&f);if(r)return r;r=fat_original->close(v,f);return r?r:-KBL_EEXIST;}
    if(parent_fault==4&&!strcmp(p,"/denied"))return-KBL_EROFS;
    return fat_original->mkdir(v,p);
}
int kbl_fs_mount(const struct kbl_fs_host*h,const struct kbl_fs_device*d,const char*t,unsigned f,struct kbl_fs*out){return kbl_fat_mount(h,d,t,f,out);}
EXPORT int fixture_mount_fat(unsigned read_only){
    struct native*c=&native_context;c->in_job=c->polling=1;c->p.max_file_bytes=16u*1024*1024;c->p.max_ramfs_bytes=32u*1024*1024;int r=0;
    unsigned char*blank=calloc(1,8u*1024*1024);if(!blank)return-KBL_ENOMEM;r=kbl_native_put(c,"/fat.img",blank,8u*1024*1024);free(blank);if(r)return r;
    struct mount temp={0};temp.owner=c;temp.active=2;temp.source_node=resolve(c,"/fat.img",0,0);temp.device=(struct kbl_fs_device){&temp,8u*1024*1024,512,0,mount_device_read,mount_device_write,mount_device_flush};
    struct kbl_fs_host host={c,mount_allocate,mount_release};FS_CALL(c,r,kbl_fat_test_format(&host,&temp.device,"fat"));if(r)return r;
    if((r=v_mkdir(c,"/mnt"))||(r=mount_add(c,"/fat.img","/mnt","fat",0))||(r=v_mkdir(c,"/mnt/roundtrip")))return r;
    kbl_file_handle h=0;if((r=v_open(c,"/mnt/roundtrip/blocker",KBL_FILE_WRITE|KBL_FILE_CREATE,&h))||(r=v_close(c,h)))return r;
    if(read_only){if((r=mount_remove(c,"/mnt"))||(r=mount_add(c,"/fat.img","/mnt","fat",KBL_FS_READ_ONLY)))return r;}
    fat_original=c->mounts[0].fs.ops;traced=*fat_original;traced.stat=fat_stat;traced.mkdir=fat_mkdir;c->mounts[0].fs.ops=&traced;
    mkdir_calls=stat_calls=parent_fault=0;c->in_job=c->polling=0;return 0;
}
EXPORT void fixture_parent_fault(unsigned n){parent_fault=n;mkdir_calls=stat_calls=0;}
EXPORT unsigned fixture_parent_counts(unsigned which){return which?stat_calls:mkdir_calls;}
EXPORT int fixture_unmount_fat(void){struct native*c=&native_context;if(c->io_owner)return-KBL_EBUSY;c->in_job=c->polling=1;int r=mount_remove(c,"/mnt");c->in_job=c->polling=0;return r;}
