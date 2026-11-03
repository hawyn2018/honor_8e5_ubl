/* SPDX-License-Identifier: MIT -- generated RAM images only; no device access. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "../fat.h"

struct memory_disk{unsigned char*data;size_t size;unsigned sector,writes,reads,flushes;int fail_write,fail_flush;};
static unsigned live_allocs;static int allocation_budget=-1;
static void*alloc(void*o,size_t n,size_t a){void*p;(void)o;if(a>16||allocation_budget==0)return 0;if(allocation_budget>0)--allocation_budget;p=calloc(1,n);if(p)live_allocs++;return p;}
static void release(void*o,void*p,size_t n){(void)o;(void)n;if(p){live_allocs--;free(p);}}
static int read_disk(void*o,uint64_t off,void*b,size_t n,size_t*done){struct memory_disk*d=o;*done=0;d->reads++;if(off>d->size||n>d->size-off)return-KBL_EIO;memcpy(b,d->data+(size_t)off,n);*done=n;return 0;}
static int write_disk(void*o,uint64_t off,const void*b,size_t n,size_t*done){struct memory_disk*d=o;*done=0;d->writes++;if(off>d->size||n>d->size-off)return-KBL_EIO;if(d->fail_write){size_t partial=n>32?32:n;memcpy(d->data+(size_t)off,b,partial);*done=partial;return-KBL_EIO;}memcpy(d->data+(size_t)off,b,n);*done=n;return 0;}
static int flush_disk(void*o){struct memory_disk*d=o;d->flushes++;return d->fail_flush?-KBL_EIO:0;}
static struct kbl_fs_host host={0,alloc,release};
static struct kbl_fs_device device(struct memory_disk*d){struct kbl_fs_device b={d,d->size,d->sector,0,read_disk,write_disk,flush_disk};return b;}
#define CHECK(c) do{if(!(c)){fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#c);exit(1);}}while(0)
static void expect_file(struct kbl_fs*f,const char*p,const unsigned char*b,size_t n){void*h=0;unsigned char temp[9000];size_t got;CHECK(n<=sizeof(temp));CHECK(f->ops->open(f->opaque,p,KBL_FILE_READ,&h)==0);CHECK(f->ops->read_at(f->opaque,h,0,temp,n+1,&got)==0&&got==n);CHECK(!memcmp(temp,b,n));CHECK(f->ops->close(f->opaque,h)==0);}
static void basic_image(const char*dir,const char*name,const char*format,size_t size,unsigned sector){
    struct memory_disk d={0};struct kbl_fs_device dev;struct kbl_fs fs;void*f=0;size_t done;struct kbl_file_stat st;unsigned char expected[5000],data[5000];char path[1024],entry[1000];FILE*out;int end;unsigned i,writes,flushes;unsigned initial=live_allocs;
    d.size=size;d.sector=sector;d.data=calloc(1,size);CHECK(d.data);dev=device(&d);
    CHECK(kbl_fat_test_format(&host,&dev,format)==0);
    CHECK(kbl_fat_mount(&host,&dev,"auto",0,&fs)==0);
    {void*held[8];char locked[32];for(unsigned j=0;j<8;++j){snprintf(locked,sizeof(locked),"/lock%u",j);CHECK(!fs.ops->open(fs.opaque,locked,KBL_FILE_READ|KBL_FILE_WRITE|KBL_FILE_CREATE,&held[j]));}
     unsigned owned=live_allocs,prior_writes=d.writes;allocation_budget=2;void*failed=0;
     CHECK(fs.ops->open(fs.opaque,"/lock-new",KBL_FILE_WRITE|KBL_FILE_CREATE,&failed)==-KBL_ENOMEM&&!failed);
     allocation_budget=-1;CHECK(live_allocs==owned&&d.writes==prior_writes);
     for(unsigned j=0;j<8;++j){CHECK(!fs.ops->close(fs.opaque,held[j]));snprintf(locked,sizeof(locked),"/lock%u",j);CHECK(!fs.ops->unlink(fs.opaque,locked));}}
    CHECK(fs.ops->mkdir(fs.opaque,"/测试目录")==0);
    CHECK(fs.ops->open(fs.opaque,"/测试目录/文件测试.bin",KBL_FILE_READ|KBL_FILE_WRITE|KBL_FILE_CREATE|KBL_FILE_EXCL,&f)==0);
    for(i=0;i<sizeof(data);i++)data[i]=(unsigned char)(i*29u+7u);memset(expected,0,sizeof(expected));
    CHECK(fs.ops->write_at(fs.opaque,f,0,data,2000,&done)==0&&done==2000);memcpy(expected,data,2000);
    CHECK(fs.ops->write_at(fs.opaque,f,100,data+200,13,&done)==0&&done==13);memcpy(expected+100,data+200,13);
    {int rc=fs.ops->write_at(fs.opaque,f,4000,data,11,&done);if(rc||done!=11)fprintf(stderr,"%s gap write rc=%d done=%zu\n",name,rc,done);CHECK(rc==0&&done==11);}memcpy(expected+4000,data,11);
    CHECK(fs.ops->fstat(fs.opaque,f,&st)==0&&st.bytes==4011);
    CHECK(fs.ops->rename(fs.opaque,"/测试目录/文件测试.bin","/测试目录/new.bin")==-KBL_EBUSY);
    CHECK(fs.ops->unmount(fs.opaque)==-KBL_EBUSY);
    CHECK(fs.ops->close(fs.opaque,f)==0);f=0;
    expect_file(&fs,"/测试目录/文件测试.bin",expected,4011);
    CHECK(fs.ops->open(fs.opaque,"/测试目录/文件测试.bin",KBL_FILE_WRITE|KBL_FILE_CREATE|KBL_FILE_EXCL,&f)==-KBL_EEXIST&&!f);
    CHECK(fs.ops->list(fs.opaque,"/测试目录",0,entry,sizeof(entry),&st,&end)==0&&!end&&!strcmp(entry,"文件测试.bin")&&st.bytes==4011);
    CHECK(fs.ops->list(fs.opaque,"/测试目录",1,entry,sizeof(entry),&st,&end)==0&&end);
    CHECK(fs.ops->rename(fs.opaque,"/测试目录/文件测试.bin","/测试目录/persist.bin")==0);
    CHECK(fs.ops->open(fs.opaque,"/append",KBL_FILE_WRITE|KBL_FILE_CREATE|KBL_FILE_APPEND,&f)==0);
    CHECK(fs.ops->write_at(fs.opaque,f,999,data,5,&done)==0&&done==5);
    CHECK(fs.ops->write_at(fs.opaque,f,0,data+5,7,&done)==0&&done==7);
    CHECK(fs.ops->close(fs.opaque,f)==0);expect_file(&fs,"/append",data,12);
    CHECK(fs.ops->open(fs.opaque,"/append",KBL_FILE_WRITE|KBL_FILE_TRUNC,&f)==0);
    CHECK(fs.ops->fstat(fs.opaque,f,&st)==0&&!st.bytes);CHECK(fs.ops->close(fs.opaque,f)==0);
    CHECK(fs.ops->unlink(fs.opaque,"/append")==0);
    CHECK(fs.ops->stat(fs.opaque,"/append",&st)==-KBL_ENOENT);
    CHECK(fs.ops->sync(fs.opaque,0)==0);CHECK(fs.ops->unmount(fs.opaque)==0);
    writes=d.writes;flushes=d.flushes;
    /* Real UFS byte IO may have a 4096-byte backing unit for a 512-byte
     * filesystem, and RAM images may have 512-byte backing for 4096 BPBs. */
    dev.sector_bytes=sector==512?4096:512;
    CHECK(kbl_fat_mount(&host,&dev,"auto",KBL_FS_READ_ONLY,&fs)==0);
    CHECK(fs.ops->stat(fs.opaque,"/测试目录/persist.bin",&st)==0&&st.block_bytes==sector);
    expect_file(&fs,"/测试目录/persist.bin",expected,4011);
    CHECK(fs.ops->open(fs.opaque,"/denied",KBL_FILE_WRITE|KBL_FILE_CREATE,&f)==-KBL_EROFS&&!f);
    CHECK(fs.ops->mkdir(fs.opaque,"/denied")==-KBL_EROFS);
    CHECK(fs.ops->unlink(fs.opaque,"/测试目录/persist.bin")==-KBL_EROFS);
    CHECK(fs.ops->rename(fs.opaque,"/测试目录/persist.bin","/denied")==-KBL_EROFS);
    CHECK(fs.ops->sync(fs.opaque,0)==0);CHECK(fs.ops->unmount(fs.opaque)==0);
    CHECK(d.writes==writes&&d.flushes==flushes);
    for(i=0;i<40;i++)CHECK(kbl_fat_mount(&host,&dev,!strcmp(format,"exfat")?"fat":"exfat",KBL_FS_READ_ONLY,&fs)==-KBL_EINVAL);
    CHECK(live_allocs==initial);
    snprintf(path,sizeof(path),"%s/%s.img",dir,name);out=fopen(path,"wb");CHECK(out);CHECK(fwrite(d.data,1,d.size,out)==d.size);CHECK(fclose(out)==0);
    printf("PASS %s: RW Unicode, overwrite, zero-filled seek, append, truncate, locks, list, rename, sync, remount, RO-zero-write, failed-mount cleanup; image=%zu sector=%u\n",name,size,sector);
    free(d.data);
}
static void fault_test(int flush_fault){
    struct memory_disk*d=calloc(1,sizeof(*d));struct kbl_fs_device dev;struct kbl_fs fs;void*f=0;unsigned char data[8192];size_t done;unsigned writes,flushes;
    CHECK(d);d->size=8u*1024*1024;d->sector=512;d->data=calloc(1,d->size);CHECK(d->data);dev=device(d);
    CHECK(kbl_fat_test_format(&host,&dev,"fat")==0);CHECK(kbl_fat_mount(&host,&dev,"fat",0,&fs)==0);
    CHECK(fs.ops->open(fs.opaque,"/fault",KBL_FILE_WRITE|KBL_FILE_CREATE,&f)==0);memset(data,0x5a,sizeof(data));
    if(flush_fault)d->fail_flush=1;else d->fail_write=1;
    CHECK(fs.ops->write_at(fs.opaque,f,0,data,sizeof(data),&done)==-KBL_EIO);
    writes=d->writes;flushes=d->flushes;
    CHECK(fs.ops->write_at(fs.opaque,f,0,data,sizeof(data),&done)==-KBL_EIO&&!done);
    CHECK(fs.ops->sync(fs.opaque,f)==-KBL_EIO);CHECK(fs.ops->sync(fs.opaque,0)==-KBL_EIO);
    CHECK(fs.ops->close(fs.opaque,f)==-KBL_EIO);CHECK(fs.ops->mkdir(fs.opaque,"/no")==-KBL_EIO);
    CHECK(fs.ops->unmount(fs.opaque)==-KBL_EBUSY);CHECK(d->writes==writes&&d->flushes==flushes);
    printf("PASS %s failure is sticky; close/sync/unmount retain objects with zero replay\n",flush_fault?"flush":"partial-device-write");
    /* Objects and backing image intentionally retained until process exit,
     * matching runtime ownership after an uncertain storage failure. */
}
int main(int argc,char**argv){const char*dir=argc>1?argv[1]:".";
    basic_image(dir,"fat12-1m","fat",1u*1024*1024,512);
    basic_image(dir,"fat16-8m","fat",8u*1024*1024,512);
    basic_image(dir,"fat32-64m","fat32",64u*1024*1024,512);
    basic_image(dir,"exfat-8m","exfat",8u*1024*1024,512);
    basic_image(dir,"fat12-4k-8m","fat",8u*1024*1024,4096);
    basic_image(dir,"fat16-4k-32m","fat",32u*1024*1024,4096);
    basic_image(dir,"exfat-4k-32m","exfat",32u*1024*1024,4096);
    CHECK(live_allocs==0);fault_test(0);fault_test(1);return 0;
}
