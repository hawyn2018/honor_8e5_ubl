/* Real production namespace, RAMFS, mounted FatFs and ADB file callbacks. */
#define main legacy_vfs_main
#include "test_vfs.c"
#undef main
#include "../../fs/fat.h"
int kbl_fs_mount(const struct kbl_fs_host*h,const struct kbl_fs_device*d,const char*t,unsigned f,struct kbl_fs*out){
    struct native*c=h->opaque;CHECK(c->fs_busy);uint64_t deadline=c->deadline;c->deadline=0;CHECK(checkpoint(c)==KBL_OK);void*large=h->allocate(h->opaque,131072,16);CHECK(large);h->release(h->opaque,large,131072);
    void*aligned=h->allocate(h->opaque,17,131072);CHECK(aligned&&!((uintptr_t)aligned&131071));h->release(h->opaque,aligned,17);
    CHECK(!h->allocate(h->opaque,SIZE_MAX,16));CHECK(!h->allocate(h->opaque,16,0));CHECK(!h->allocate(h->opaque,16,3));
    c->deadline=deadline;return kbl_fat_mount(h,d,t,f,out);
}
static const struct kbl_fs_ops*real_ops;
static struct kbl_fs_ops injected;
static unsigned fail_close,fail_sync,fail_unmount,close_attempts;
static unsigned mount_fail_next_allocate;
static void*transient_memory(void*p,size_t n,size_t alignment){if(mount_fail_next_allocate){--mount_fail_next_allocate;return 0;}return memory(p,n,alignment);}
/* Inactive descriptors are reusable owned metadata, rather than fixed arrays. */
static size_t mount_payload_count(struct native*c){size_t n=c->owned_count;
    for(struct mount*m=c->mounts;m;m=m->next)--n;for(struct file_slot*f=c->files;f;f=f->next)--n;return n;}
static int close_inject(void*v,void*f){++close_attempts;if(fail_close)return -KBL_EIO;return real_ops->close(v,f);}
static int sync_inject(void*v,void*f){if(fail_sync)return -KBL_EIO;return real_ops->sync(v,f);}
static int unmount_inject(void*v){if(fail_unmount)return -KBL_EIO;return real_ops->unmount(v);}
static void image(struct native*c,const char*path,const char*type,size_t bytes){
    unsigned char*zeroes=calloc(1,bytes);CHECK(zeroes);CHECK(!kbl_native_put(c,path,zeroes,bytes));free(zeroes);
    struct mount temp={0};temp.owner=c;temp.active=2;temp.source_node=resolve(c,path,0,0);temp.device=(struct kbl_fs_device){&temp,bytes,512,0,mount_device_read,mount_device_write,mount_device_flush};
    struct kbl_fs_host host={c,mount_allocate,mount_release};int rc;FS_CALL(c,rc,kbl_fat_test_format(&host,&temp.device,type));if(rc)fprintf(stderr,"format fixture %s bytes=%zu errno=%d\n",path,bytes,rc);CHECK(!rc);
}
static void mounted_real(void){
    struct native c;setup(&c);c.p.max_file_bytes=128u*1024*1024;c.p.max_ramfs_bytes=256u*1024*1024;c.cwd_path[0]='/';c.cwd_path[1]=0;
    CHECK(!v_mkdir(&c,"/mnt"));CHECK(!v_mkdir(&c,"/other"));image(&c,"/fat.img","fat",8u*1024*1024);
    image(&c,"/exfat.img","exfat",8u*1024*1024);size_t baseline=mount_payload_count(&c);
    /* Explicit read-only and fixed source pin; malformed mount does not leak. */
    CHECK(mount_add(&c,"/fat.img","/mnt","bogus",KBL_FS_READ_ONLY)==-KBL_EINVAL);CHECK(mount_payload_count(&c)==baseline);
    CHECK(!run(&c,"mount -o ro /fat.img /mnt"));CHECK(c.mounts->flags&KBL_FS_READ_ONLY);kbl_file_handle h=0;size_t done=0;uint64_t pos=0;
    CHECK(v_open(&c,"/mnt/file",KBL_FILE_CREATE|KBL_FILE_WRITE,&h)==-KBL_EROFS);CHECK(v_unlink(&c,"/fat.img")==-KBL_EBUSY);
    CHECK(kbl_native_put(&c,"/fat.img","x",1)==KBL_ERROR);CHECK(v_rename(&c,"/fat.img","/changed")==-KBL_EBUSY);
    CHECK(v_open(&c,"/fat.img",KBL_FILE_WRITE,&h)==-KBL_EBUSY);CHECK(!mount_remove(&c,"/mnt"));CHECK(mount_payload_count(&c)==baseline);
    CHECK(!run(&c,"mount -t fat /fat.img /mnt"));CHECK(!(c.mounts->flags&KBL_FS_READ_ONLY));CHECK(!run(&c,"mount -t exfat -o rw /exfat.img /other"));
    CHECK(!v_mkdir(&c,"/mnt/dir"));CHECK(!v_open(&c,"/mnt/dir/file",KBL_FILE_READ|KBL_FILE_WRITE|KBL_FILE_CREATE,&h));
    CHECK(!v_write(&c,h,"abc",3,&done)&&done==3);CHECK(!v_seek(&c,h,4096,KBL_SEEK_SET,&pos));CHECK(!v_write(&c,h,"Z",1,&done)&&done==1);
    CHECK(!v_seek(&c,h,4092,KBL_SEEK_SET,&pos));char buf[32];CHECK(!v_read(&c,h,buf,sizeof(buf),&done)&&done==5&&!buf[0]&&buf[4]=='Z');
    CHECK(mount_remove(&c,"/mnt")==-KBL_EBUSY);CHECK(v_unlink(&c,"/mnt/dir/file")==-KBL_EBUSY);CHECK(!v_sync(&c,h));CHECK(!v_close(&c,h));
    CHECK(!run(&c,"cd /mnt/dir"));CHECK(!run(&c,"pwd"));CHECK(output_has(&c,"/mnt/dir\n"));CHECK(mount_remove(&c,"/mnt")==-KBL_EBUSY);
    CHECK(!run(&c,"cp file /local"));CHECK(resolve(&c,"/local",0,0)->bytes==4097);CHECK(!run(&c,"cp /local /other/cross"));
    CHECK(v_rename(&c,"/other/cross","/mnt/cross")==-KBL_EXDEV);CHECK(!run(&c,"sha256sum /other/cross"));CHECK(!run(&c,"hexdump -n 4 file"));
    CHECK(!run(&c,"wc -c file"));CHECK(output_has(&c,"4097"));CHECK(!run(&c,"cd /"));CHECK(!run(&c,"mv /mnt/dir/file /mnt/dir/renamed"));
    CHECK(!run(&c,"ls /mnt/dir"));CHECK(output_has(&c,"renamed"));CHECK(!run(&c,"head -c 3 /mnt/dir/renamed"));CHECK(!memcmp(c.output,"abc",3));
    size_t n=0;uint64_t filebytes=0;CHECK(kbl_native_get(&c,"/mnt/dir/renamed",0,buf,sizeof(buf),&n,&filebytes)==KBL_ERROR);
    struct kbl_reader snapshot={0};c.in_job=c.polling=0;CHECK(kbl_native_open_snapshot(&c,"/mnt/dir/renamed",&snapshot)==KBL_ERROR);
    const struct kbl_program_api*a=0;CHECK(!kbl_native_io_begin(&c,&a));char name[256];struct kbl_file_stat st;int end=0;
    CHECK(!kbl_native_io_list(&c,a,"/mnt/dir",0,name,sizeof(name),&st,&end)&&!end&&!strcmp(name,"renamed")&&st.bytes==4097);
    CHECK(!a->file_open(a->host,"/other/adb",KBL_FILE_WRITE|KBL_FILE_CREATE,&h));CHECK(!a->file_write(a->host,h,"ADB",3,&done)&&done==3);
    CHECK(!a->file_sync(a->host,h));CHECK(!a->file_close(a->host,h));CHECK(!kbl_native_io_end(&c,a));c.in_job=c.polling=1;
    CHECK(!mount_remove(&c,"/other"));CHECK(!mount_remove(&c,"/mnt"));CHECK(mount_payload_count(&c)==baseline+3); /* /local node + owned name + bytes */
    CHECK(!mount_add(&c,"/fat.img","/mnt","fat",KBL_FS_READ_ONLY));CHECK(!v_stat(&c,"/mnt/dir/renamed",&st)&&st.bytes==4097);CHECK(!mount_remove(&c,"/mnt"));
    /* Close consumes the public token on failure but retains the file/mount. */
    CHECK(!mount_add(&c,"/fat.img","/mnt","fat",0));struct mount*m=route_mount(&c,"/mnt",0);CHECK(m);real_ops=m->fs.ops;injected=*real_ops;injected.close=close_inject;injected.sync=sync_inject;injected.unmount=unmount_inject;m->fs.ops=&injected;
    CHECK(!v_open(&c,"/mnt/dir/renamed",KBL_FILE_READ,&h));fail_close=1;close_attempts=0;size_t live=c.owned_count;
    CHECK(v_close(&c,h)==-KBL_EIO&&!slot(&c,h)&&c.owned_count==live&&close_attempts==1);CHECK(close_files(&c)==-KBL_EIO&&c.owned_count==live);
    CHECK(mount_remove(&c,"/mnt")==-KBL_EBUSY);fail_close=0;CHECK(!close_files(&c));fail_sync=1;live=c.owned_count;
    CHECK(mount_stop_all(&c)==-KBL_EIO&&m->active==1&&c.owned_count==live);fail_sync=0;fail_unmount=1;
    CHECK(mount_stop_all(&c)==-KBL_EIO&&m->active==1&&c.owned_count==live);fail_unmount=0;CHECK(!mount_stop_all(&c));
    finish(&c);++groups;
}
static void mounted_raw(void){struct native c;setup(&c);c.cwd_path[0]='/';c.cwd_path[1]=0;CHECK(!v_mkdir(&c,"/mnt"));
    /* A tiny block image is invalid as a filesystem: raw provider closes once. */
    zero(media,sizeof(media));unsigned before=opens;CHECK(mount_add(&c,"/dev/block/by-name/test","/mnt","fat",KBL_FS_READ_ONLY)<0);CHECK(opens==before+1&&opens==closes);
    /* Mount rejection with an existing raw handle does not reopen the device. */
    kbl_file_handle h;CHECK(!v_open(&c,"/dev/block/by-name/test",KBL_FILE_READ,&h));before=opens;
    CHECK(mount_add(&c,"/dev/block/by-name/test","/mnt","fat",KBL_FS_READ_ONLY)==-KBL_EBUSY&&opens==before);CHECK(!v_close(&c,h));
    struct opened backing={KBL_BLOCK_READ|KBL_BLOCK_WRITE,0,0};
    struct kbl_fs_host host={&c,mount_allocate,mount_release};struct kbl_fs_device dev={&backing,sizeof(media),512,0,bread,bwrite,bflush};int formatted;
    FS_CALL(&c,formatted,kbl_fat_test_format(&host,&dev,"fat"));CHECK(!formatted);
    CHECK(!mount_add(&c,"/dev/block/by-name/test","/mnt","fat",0));
    CHECK(v_open(&c,"/dev/block/by-name/test",KBL_FILE_WRITE,&h)==-KBL_EBUSY);
    CHECK(!v_open(&c,"/mnt/raw.txt",KBL_FILE_WRITE|KBL_FILE_CREATE,&h));size_t n;CHECK(!v_write(&c,h,"raw",3,&n)&&n==3);CHECK(!v_close(&c,h));
    CHECK(!mount_remove(&c,"/mnt"));CHECK(opens==closes);CHECK(!mount_add(&c,"/dev/block/by-name/test","/mnt","fat",KBL_FS_READ_ONLY));
    struct kbl_file_stat st;CHECK(!v_stat(&c,"/mnt/raw.txt",&st)&&st.bytes==3);CHECK(!mount_remove(&c,"/mnt"));finish(&c);++groups;
}
static void session_close_retry(void){struct native c;setup(&c);c.cwd_path[0]='/';c.cwd_path[1]=0;
    c.p.output_bytes=4096;c.p.stack_bytes=32768;
    image(&c,"/fat.img","fat",1024*1024);CHECK(!v_mkdir(&c,"/mnt"));CHECK(!mount_add(&c,"/fat.img","/mnt","fat",0));
    struct mount*m=route_mount(&c,"/mnt",0);CHECK(m);real_ops=m->fs.ops;injected=*real_ops;injected.close=close_inject;m->fs.ops=&injected;
    c.in_job=c.polling=0;void*a=0,*b=0;CHECK(!kbl_native_session_create(&c,&a));CHECK(!kbl_native_session_create(&c,&b));
    const struct kbl_program_api*api=0,*waiter=0;CHECK(!kbl_native_session_io_begin(&c,a,&api));kbl_file_handle h=0;
    CHECK(!api->file_open(api->host,"/mnt/owned",KBL_FILE_READ|KBL_FILE_WRITE|KBL_FILE_CREATE,&h));size_t n=0;
    CHECK(!api->file_write(api->host,h,"owned",5,&n)&&n==5);CHECK(kbl_native_session_io_begin(&c,b,&waiter)==KBL_AGAIN);
    fail_close=1;size_t live=c.owned_count;CHECK(kbl_native_session_close(&c,a)==KBL_AGAIN&&c.owned_count==live&&c.io_owner);
    CHECK(kbl_native_session_io_begin(&c,b,&waiter)==KBL_AGAIN);fail_close=0;CHECK(!kbl_native_session_close(&c,a));
    CHECK(!kbl_native_session_io_begin(&c,b,&waiter));struct kbl_file_stat st;
    CHECK(!waiter->file_stat(waiter->host,"/mnt/owned",&st)&&st.bytes==5);char out;
    CHECK(waiter->file_read(waiter->host,h,&out,1,&n)==-KBL_EBADF);
    CHECK(!kbl_native_session_io_end(&c,b,waiter));CHECK(!kbl_native_session_close(&c,b));c.in_job=c.polling=1;
    CHECK(!mount_remove(&c,"/mnt"));finish(&c);++groups;
}
static void mounted_idle_cwds(void){struct native c;setup(&c);c.cwd_path[0]='/';c.cwd_path[1]=0;
    c.p.output_bytes=4096;c.p.stack_bytes=32768;image(&c,"/fat.img","fat",1024*1024);
    CHECK(!v_mkdir(&c,"/mnt"));CHECK(!mount_add(&c,"/fat.img","/mnt","fat",0));CHECK(!v_mkdir(&c,"/mnt/held"));
    c.in_job=c.polling=0;void*a=0,*b=0;CHECK(!kbl_native_session_create(&c,&a));CHECK(!kbl_native_session_create(&c,&b));
    c.in_job=c.polling=1;CHECK(!run(&c,"cd /mnt/held"));
    /* Host harness records a completed production cd just as save_active does. */
    struct native_session*idle=a;idle->cwd=c.cwd;free_owned(&c,idle->cwd_path);idle->cwd_path=owned_string(&c,c.cwd_path);CHECK(idle->cwd_path);CHECK(!run(&c,"cd /"));
    CHECK(mount_remove(&c,"/mnt")==-KBL_EBUSY);
    CHECK(v_unlink(&c,"/mnt/held")==-KBL_EBUSY);CHECK(v_rename(&c,"/mnt/held","/mnt/moved")==-KBL_EBUSY);
    CHECK(!v_mkdir(&c,"/mnt/source"));CHECK(v_rename(&c,"/mnt/source","/mnt/held")==-KBL_EBUSY);
    c.in_job=c.polling=0;CHECK(!kbl_native_session_close(&c,a));CHECK(!kbl_native_session_close(&c,b));c.in_job=c.polling=1;
    CHECK(!v_unlink(&c,"/mnt/held"));CHECK(!v_unlink(&c,"/mnt/source"));CHECK(!mount_remove(&c,"/mnt"));finish(&c);++groups;
}
static void many_mounts(void){
    struct native c;setup(&c);c.p.max_file_bytes=2u*1024*1024;c.p.max_ramfs_bytes=32u*1024*1024;
    struct mount*held[12];char path[32],source[32];
    for(unsigned i=0;i<12;++i){snprintf(path,sizeof(path),"/mount%u",i);snprintf(source,sizeof(source),"/image%u",i);
        CHECK(!v_mkdir(&c,path));image(&c,source,"fat",1024*1024);CHECK(!mount_add(&c,source,path,"fat",0));held[i]=route_mount(&c,path,0);CHECK(held[i]);
        for(unsigned j=0;j<=i;++j){snprintf(path,sizeof(path),"/mount%u",j);CHECK(route_mount(&c,path,0)==held[j]);}}
    unsigned count=0;for(struct mount*m=c.mounts;m;m=m->next)if(m->active)++count;CHECK(count==12);
    /* A one-shot canonical allocation failure is an error, never evidence
     * that a mounted path belongs to the underlying RAM namespace. */
    CHECK(!kbl_native_put(&c,"/existing","old",3));unsigned nodes_before=c.nodes;
    c.p.allocate=transient_memory;mount_fail_next_allocate=1;
    CHECK(kbl_native_put(&c,"/mount5/hidden","x",1)==KBL_ERROR);CHECK(!mount_fail_next_allocate);
    c.p.allocate=memory;CHECK(c.nodes==nodes_before&&!held[5]->point->children);
    c.p.allocate=transient_memory;mount_fail_next_allocate=1;
    CHECK(kbl_native_put(&c,"/existing","x",1)==KBL_ERROR);c.p.allocate=memory;
    struct node*existing=resolve(&c,"/existing",0,0);CHECK(existing&&existing->bytes==3&&!memcmp(existing->data,"old",3));
    /* Real per-volume sharing metadata grows beyond the old 32 slots. */
    kbl_file_handle held_files[65];char file[64];
    for(unsigned i=0;i<65;++i){snprintf(file,sizeof(file),"/mount11/file%u",i);CHECK(!v_open(&c,file,KBL_FILE_READ|KBL_FILE_WRITE|KBL_FILE_CREATE,&held_files[i]));}
    size_t cross_done;uint64_t cross_position;char cross_bytes[8];kbl_file_handle cross_file;
    CHECK(!v_write(&c,held_files[64],"volume11",8,&cross_done)&&cross_done==8);
    CHECK(!v_open(&c,"/mount0/context",KBL_FILE_WRITE|KBL_FILE_CREATE,&cross_file));CHECK(!v_write(&c,cross_file,"zero",4,&cross_done)&&cross_done==4);CHECK(!v_close(&c,cross_file));
    CHECK(!v_seek(&c,held_files[64],0,KBL_SEEK_SET,&cross_position));CHECK(!v_read(&c,held_files[64],cross_bytes,8,&cross_done)&&cross_done==8&&!memcmp(cross_bytes,"volume11",8));
    int locked;FS_CALL(&c,locked,held[11]->fs.ops->unlink(held[11]->fs.opaque,"/file64"));CHECK(locked==-KBL_EBUSY);
    FS_CALL(&c,locked,held[11]->fs.ops->rename(held[11]->fs.opaque,"/file64","/renamed"));CHECK(locked==-KBL_EBUSY);
    for(unsigned i=0;i<65;++i)CHECK(!v_close(&c,held_files[i]));
    /* The old 0x100 writer sentinel must not turn the 256th reader into a
     * writer. Read-open count and write exclusivity now have separate fields. */
    kbl_file_handle readers[270],writer=0;
    for(unsigned i=0;i<270;++i)CHECK(!v_open(&c,"/mount11/file64",KBL_FILE_READ,&readers[i]));
    CHECK(v_open(&c,"/mount11/file64",KBL_FILE_WRITE,&writer)==-KBL_EBUSY&&!writer);
    CHECK(mount_remove(&c,"/mount11")==-KBL_EBUSY);
    for(unsigned i=0;i<270;++i)CHECK(!v_close(&c,readers[i]));
    CHECK(!v_open(&c,"/mount11/file64",KBL_FILE_WRITE,&writer));CHECK(!v_close(&c,writer));
    CHECK(!v_open(&c,"/mount0/file64",KBL_FILE_WRITE|KBL_FILE_CREATE,&writer));CHECK(!v_close(&c,writer));
    /* Full paths exceed 4 KiB while each on-disk component stays <=255. */
    char deep[7000]="/mount0";size_t deep_bytes=strlen(deep);
    for(unsigned i=0;i<25;++i){deep[deep_bytes++]='/';memset(deep+deep_bytes,'d',200);deep_bytes+=200;deep[deep_bytes]=0;CHECK(!v_mkdir(&c,deep));}
    memcpy(deep+deep_bytes,"/leaf",6);CHECK(strlen(deep)>4096);CHECK(!v_open(&c,deep,KBL_FILE_WRITE|KBL_FILE_CREATE,&writer));
    size_t written=0;CHECK(!v_write(&c,writer,"deep",4,&written)&&written==4);CHECK(!v_close(&c,writer));
    struct kbl_file_stat deep_stat;CHECK(!v_stat(&c,deep,&deep_stat)&&deep_stat.bytes==4);
    CHECK(!run(&c,"cd /mount3"));CHECK(mount_remove(&c,"/mount3")==-KBL_EBUSY);CHECK(!run(&c,"cd /"));
    kbl_file_handle h;CHECK(!v_open(&c,"/mount4/held",KBL_FILE_WRITE|KBL_FILE_CREATE,&h));CHECK(mount_remove(&c,"/mount4")==-KBL_EBUSY);CHECK(!v_close(&c,h));
    CHECK(!mount_remove(&c,"/mount4"));for(unsigned i=0;i<12;++i)if(i!=4){snprintf(path,sizeof(path),"/mount%u",i);CHECK(route_mount(&c,path,0)==held[i]);}
    CHECK(!mount_add(&c,"/image4","/mount4","fat",0));CHECK(route_mount(&c,"/mount4",0)==held[4]);CHECK(!mount_stop_all(&c));
    count=0;for(struct mount*m=c.mounts;m;m=m->next){CHECK(!m->active&&!m->path&&!m->source);++count;}CHECK(count==12);
    finish(&c);++groups;
}
int main(void){mounted_real();mounted_raw();session_close_retry();mounted_idle_cwds();many_mounts();printf("{\"passed\":true,\"groups\":%u,\"assertions\":%u,\"realFilesystems\":[\"fat12\",\"fat16\",\"exfat\"],\"simultaneousMounts\":12,\"distinctOpenFatFiles\":65,\"sharedFatReaders\":270,\"deviceIO\":false}\n",groups,checks);return 0;}

