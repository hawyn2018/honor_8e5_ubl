/* SPDX-License-Identifier: GPL-3.0-or-later -- production VFS/RW on host media. */
#define main legacy_vfs_main
#include "test_vfs.c"
#undef main
#include "../../fs/f2fs.h"
#include "../../fs/f2fs_rw.h"
int kbl_fs_mount(const struct kbl_fs_host*h,const struct kbl_fs_device*d,const char*t,unsigned f,struct kbl_fs*out){
    CHECK(((struct native*)h->opaque)->fs_busy);return f&KBL_FS_READ_ONLY?kbl_f2fs_mount(h,d,t,f,out):kbl_f2fs_rw_mount(h,d,t,f,out);
}
struct fixture_disk {unsigned char*data;size_t size;unsigned reads,writes,flushes,open_count,close_count;size_t fail_write;unsigned fail_flush;int fail_errno;size_t read_cap;unsigned zero_read,error_read,over_read;};
static int fixture_read(void*p,uint64_t at,void*out,size_t n,size_t*done){struct fixture_disk*d=p;*done=0;d->reads++;if(at>d->size||n>d->size-at)return KBL_ERROR;if(d->zero_read)return 0;if(d->read_cap&&n>d->read_cap)n=d->read_cap;memcpy(out,d->data+(size_t)at,n);*done=n;if(d->over_read)*done=n+1;return d->error_read?KBL_ERROR:0;}
static int fixture_write(void*p,uint64_t at,const void*in,size_t n,size_t*done){struct fixture_disk*d=p;*done=0;d->writes++;if(at>d->size||n>d->size-at)return KBL_ERROR;
    if(d->fail_write&&n>d->fail_write)n=d->fail_write;memcpy(d->data+(size_t)at,in,n);*done=n;return d->fail_write?(d->fail_errno?d->fail_errno:KBL_ERROR):0;}
static int fixture_flush(void*p){struct fixture_disk*d=p;d->flushes++;return d->fail_flush?KBL_ERROR:0;}
static void fixture_close(void*p){((struct fixture_disk*)p)->close_count++;}
static int fixture_open(void*p,const char*label,unsigned access,struct kbl_block*out){struct fixture_disk*d=p;zero(out,sizeof(*out));if(strcmp(label,"f2fixture"))return KBL_ERROR;d->open_count++;
    *out=(struct kbl_block){{d,d->size,fixture_read,fixture_close},4096,0,access&KBL_BLOCK_WRITE?fixture_write:0,fixture_flush};return 0;}
static const char*payload_path,*inline_path;
static void start_fixture(struct native*c,struct fixture_disk*d,const char*path){
    payload_path=strstr(path,"reader-fixture")?"/mnt/payload.bin":"/mnt/normal8k.bin";inline_path=strstr(path,"reader-fixture")?"/mnt/inline.txt":"/mnt/small.txt";
    setup(c);c->p.opaque=d;c->p.block_open=fixture_open;c->cwd_path[0]='/';c->cwd_path[1]=0;
    FILE*f=fopen(path,"rb");CHECK(f);CHECK(!fseek(f,0,SEEK_END));long n=ftell(f);CHECK(n>0);CHECK(!fseek(f,0,SEEK_SET));d->size=(size_t)n;d->data=malloc(d->size);CHECK(d->data);CHECK(fread(d->data,1,d->size,f)==d->size);fclose(f);
    CHECK(!v_mkdir(c,"/mnt"));CHECK(!mount_add(c,"/dev/block/by-name/f2fixture","/mnt","f2fs",0));
}
static void expect_span(struct native*c,const char*path,uint64_t offset,const void*expected,size_t n){
    kbl_file_handle h;char data[128];uint64_t at;size_t got;CHECK(n<=sizeof(data));CHECK(!v_open(c,path,KBL_FILE_READ,&h));CHECK(!v_seek(c,h,(int64_t)offset,KBL_SEEK_SET,&at));CHECK(!v_read(c,h,data,n,&got)&&got==n&&!memcmp(data,expected,n));CHECK(!v_close(c,h));
}
static void normal_fixture(const char*path){
    struct native c;struct fixture_disk d={0};d.read_cap=97;start_fixture(&c,&d,path);kbl_file_handle h,alias;size_t done;uint64_t at;struct kbl_file_stat st;
    CHECK(!v_stat(&c,payload_path,&st)&&st.bytes==8192&&!(st.flags&KBL_FILE_READ_ONLY));
    CHECK(!v_open(&c,payload_path,KBL_FILE_READ|KBL_FILE_WRITE,&h));CHECK(!v_seek(&c,h,4090,KBL_SEEK_SET,&at));
    const char crossing[]="0123456789-ALLOCATION-UNCHANGED!";CHECK(!v_write(&c,h,crossing,sizeof(crossing)-1,&done)&&done==sizeof(crossing)-1);CHECK(!v_sync(&c,h));CHECK(!v_close(&c,h));
    expect_span(&c,payload_path,4090,crossing,sizeof(crossing)-1);
    CHECK(!v_stat(&c,inline_path,&st));size_t inline_bytes=(size_t)st.bytes;char inline_expected[128];CHECK(inline_bytes>=8&&inline_bytes<=sizeof(inline_expected));
    CHECK(!v_open(&c,inline_path,KBL_FILE_READ,&alias));CHECK(!v_read(&c,alias,inline_expected,inline_bytes,&done)&&done==inline_bytes);CHECK(!v_seek(&c,alias,0,KBL_SEEK_SET,&at));memcpy(inline_expected,"PATCHED!",8);CHECK(!v_open(&c,inline_path,KBL_FILE_WRITE,&h));CHECK(!v_write(&c,h,"PATCHED!",8,&done)&&done==8);
    char b[32];CHECK(!v_read(&c,alias,b,sizeof(b),&done)&&done==inline_bytes&&!memcmp(b,inline_expected,inline_bytes));CHECK(!v_close(&c,h));CHECK(!v_close(&c,alias));
    unsigned writes_before=d.writes;CHECK(v_open(&c,payload_path,KBL_FILE_WRITE|KBL_FILE_TRUNC,&h)==-KBL_ENOSYS&&d.writes==writes_before);
    CHECK(v_open(&c,"/mnt/missing",KBL_FILE_WRITE|KBL_FILE_CREATE,&h)==-KBL_ENOSYS&&d.writes==writes_before);
    CHECK(v_open(&c,payload_path,KBL_FILE_WRITE|KBL_FILE_CREATE|KBL_FILE_EXCL,&h)==-KBL_EEXIST&&d.writes==writes_before);
    CHECK(!v_open(&c,payload_path,KBL_FILE_WRITE|KBL_FILE_CREATE,&h));CHECK(!v_seek(&c,h,8191,KBL_SEEK_SET,&at));CHECK(v_write(&c,h,"XX",2,&done)==-KBL_ENOSYS&&!done&&d.writes==writes_before);CHECK(!v_close(&c,h));
    CHECK(v_mkdir(&c,"/mnt/new")==-KBL_ENOSYS&&v_unlink(&c,payload_path)==-KBL_ENOSYS&&d.writes==writes_before);
    CHECK(!kbl_native_put(&c,"/patch","ABC",3));char command_line[256];snprintf(command_line,sizeof(command_line),"dd if=/patch of=%s",payload_path);CHECK(run(&c,command_line)==1&&d.writes==writes_before);
    snprintf(command_line,sizeof(command_line),"dd if=/patch of=%s conv=notrunc,fsync",payload_path);CHECK(!run(&c,command_line));expect_span(&c,payload_path,0,"ABC",3);
    if(!v_stat(&c,"/mnt/large6m.bin",&st)){
        static const char marker[]="KBL-F2FS-INDIRECT-NODE-CROSSING";CHECK(st.bytes==6u*1024*1024);
        CHECK(!v_open(&c,"/mnt/large6m.bin",KBL_FILE_READ|KBL_FILE_WRITE,&h));CHECK(!v_seek(&c,h,5u*1024*1024+4090,KBL_SEEK_SET,&at));
        CHECK(!v_write(&c,h,marker,sizeof(marker)-1,&done)&&done==sizeof(marker)-1);CHECK(!v_sync(&c,h));CHECK(!v_close(&c,h));
        expect_span(&c,"/mnt/large6m.bin",5u*1024*1024+4090,marker,sizeof(marker)-1);
    }
    CHECK(!v_stat(&c,payload_path,&st)&&st.bytes==8192);snprintf(command_line,sizeof(command_line),"cp %s /readback",payload_path);CHECK(!run(&c,command_line));CHECK(resolve(&c,"/readback",0,0)->bytes==8192);
    c.in_job=c.polling=0;const struct kbl_program_api*api=0;CHECK(!kbl_native_io_begin(&c,&api));
    CHECK(!api->file_open(api->host,payload_path,KBL_FILE_WRITE,&h));CHECK(!api->file_seek(api->host,h,100,KBL_SEEK_SET,&at));CHECK(!api->file_write(api->host,h,"ADB",3,&done)&&done==3);
    CHECK(!api->file_sync(api->host,h));CHECK(!api->file_close(api->host,h));CHECK(!kbl_native_io_end(&c,api));c.in_job=c.polling=1;
    expect_span(&c,payload_path,100,"ADB",3);CHECK(!mount_remove(&c,"/mnt"));CHECK(d.open_count==d.close_count);
    CHECK(!mount_add(&c,"/dev/block/by-name/f2fixture","/mnt","f2fs",KBL_FS_READ_ONLY));CHECK(v_open(&c,payload_path,KBL_FILE_WRITE,&h)==-KBL_EROFS);
    expect_span(&c,payload_path,0,"ABC",3);expect_span(&c,inline_path,0,inline_expected,inline_bytes);CHECK(!mount_remove(&c,"/mnt"));
    CHECK(d.writes>0&&d.flushes>0&&d.open_count==d.close_count);if(!strstr(path,"reader-fixture")){char saved[2048];snprintf(saved,sizeof(saved),"%s.native-overwrite.img",path);FILE*out=fopen(saved,"wb");CHECK(out);CHECK(fwrite(d.data,1,d.size,out)==d.size);CHECK(!fclose(out));}free(d.data);finish(&c);++groups;
}
static void poisoned_fixture(const char*path,unsigned failure_kind){
    struct native c;struct fixture_disk d={0};start_fixture(&c,&d,path);kbl_file_handle h;size_t done;struct kbl_file_stat failed_stat;
    CHECK(!v_open(&c,failure_kind>=2?inline_path:payload_path,KBL_FILE_WRITE,&h));if(failure_kind!=1)d.fail_write=3;if(failure_kind==3){d.fail_write=372;d.fail_errno=-KBL_ENOSPC;}
    int r=v_write(&c,h,"PARTIAL-WRITE",13,&done);
    if(failure_kind==1){CHECK(!r&&done==13);d.fail_flush=1;CHECK(v_sync(&c,h)==-KBL_EIO);}else if(failure_kind==3){CHECK(r==-KBL_EIO&&done<=13);if(strstr(path,"extra.img"))CHECK(done==0);}else CHECK(r==-KBL_EIO&&done==(failure_kind==2?0:3));
    unsigned w=d.writes,f=d.flushes;CHECK(v_write(&c,h,"AGAIN",5,&done)==-KBL_EIO&&!done&&d.writes==w&&d.flushes==f);
    CHECK(v_close(&c,h)==-KBL_EIO&&!slot(&c,h));CHECK(close_files(&c)==-KBL_EIO&&d.writes==w&&d.flushes==f);
    CHECK(mount_remove(&c,"/mnt")==-KBL_EBUSY);CHECK(mount_stop_all(&c)==-KBL_EIO&&c.mounts->active==1);
    CHECK(v_stat(&c,payload_path,&failed_stat)==-KBL_EIO);CHECK(d.writes==w&&d.flushes==f);
    /* Model a platform reset for fixture teardown only. Production has no
     * force-close/reset escape hatch that would replay an uncertain write. */
    c.mounts->block.reader.close(c.mounts->block.reader.opaque);while(c.owned)free_owned(&c,c.owned+1);CHECK(!allocations);CHECK(d.open_count==d.close_count);free(d.data);++groups;
}
static uint32_t fixture_u32(const unsigned char*p){return(uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24;}
static void fixture_put32(unsigned char*p,uint32_t v){for(unsigned i=0;i<4;++i)p[i]=(unsigned char)(v>>(i*8));}
static uint32_t fixture_crc(uint32_t crc,const unsigned char*p,size_t n){for(size_t i=0;i<n;++i){crc^=p[i];for(unsigned b=0;b<8;++b)crc=crc>>1^((0u-(crc&1u))&0xedb88320u);}return crc;}
static void checkpoint_policy(struct fixture_disk*d,unsigned bit,unsigned enable){
    uint32_t cp=fixture_u32(d->data+1024+76),blocks=1u<<fixture_u32(d->data+1024+20);
    for(unsigned i=0;i<2;++i){uint64_t base=(uint64_t)(cp+i*blocks)*4096;uint32_t pack=fixture_u32(d->data+base+136);CHECK(pack>1&&base+(uint64_t)pack*4096<=d->size);
        for(unsigned tail=0;tail<2;++tail){unsigned char*page=d->data+base+(tail?(uint64_t)(pack-1)*4096:0);uint32_t offset=fixture_u32(page+164);CHECK(offset>=192&&offset<=4092);
            uint32_t flags=fixture_u32(page+132);flags=enable?flags|bit:flags&~bit;fixture_put32(page+132,flags);uint32_t crc=fixture_crc(0xf2f52010u,page,offset);
            if(offset<4092)crc=fixture_crc(crc,page+offset+4,4096-offset-4);fixture_put32(page+offset,crc);
        }
    }
}
static void source_boundary_fixture(const char*path){
    struct native c;struct fixture_disk d={0};start_fixture(&c,&d,path);CHECK(!mount_remove(&c,"/mnt"));
    for(unsigned mode=0;mode<3;++mode){unsigned reads_before=d.reads,writes_before=d.writes;d.zero_read=mode==0;d.error_read=mode==1;d.over_read=mode==2;
        CHECK(mount_add(&c,"/dev/block/by-name/f2fixture","/mnt","f2fs",KBL_FS_READ_ONLY)<0);CHECK(d.reads-reads_before<=2&&d.writes==writes_before);
        d.zero_read=d.error_read=d.over_read=0;CHECK(d.open_count==d.close_count);
    }
    for(unsigned bit=2;bit<=16;bit*=8){unsigned writes_before=d.writes;checkpoint_policy(&d,bit,1);
        CHECK(!mount_add(&c,"/dev/block/by-name/f2fixture","/mnt","f2fs",KBL_FS_READ_ONLY));CHECK(!mount_remove(&c,"/mnt"));
        CHECK(mount_add(&c,"/dev/block/by-name/f2fixture","/mnt","f2fs",0)==-KBL_EIO&&d.writes==writes_before);CHECK(d.open_count==d.close_count);checkpoint_policy(&d,bit,0);
    }
    free(d.data);finish(&c);++groups;
}
int main(int argc,char**argv){CHECK(argc>1);for(int i=1;i<argc;++i)normal_fixture(argv[i]);poisoned_fixture(argv[1],0);poisoned_fixture(argv[1],1);poisoned_fixture(argv[1],2);poisoned_fixture(argv[argc-1],3);source_boundary_fixture(argv[1]);
    printf("{\"passed\":true,\"groups\":%u,\"assertions\":%u,\"physicalDeviceIO\":false}\n",groups,checks);return 0;}
