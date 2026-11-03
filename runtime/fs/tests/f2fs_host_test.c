/* SPDX-License-Identifier: GPL-3.0-or-later -- generated bounded files only. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../f2fs.h"
struct disk{unsigned char*data;size_t bytes;unsigned reads,writes,flushes,read_limit,read_cap;};
static unsigned live,allocation_calls;static int allocation_budget=-1;
static void*allocate(void*x,size_t n,size_t a){void*p;(void)x;++allocation_calls;if(a>16||allocation_budget==0)return 0;if(allocation_budget>0)--allocation_budget;p=calloc(1,n);if(p)live++;return p;}
static void release(void*x,void*p,size_t n){(void)x;(void)n;if(p){live--;free(p);}}
static int read_at(void*x,uint64_t off,void*b,size_t n,size_t*done){struct disk*d=x;*done=0;d->reads++;if(d->read_limit&&d->reads>d->read_limit)return-KBL_EIO;if(d->read_cap&&n>d->read_cap)n=d->read_cap;if(off>d->bytes||n>d->bytes-off)return-KBL_EIO;memcpy(b,d->data+(size_t)off,n);*done=n;return 0;}
static int write_at(void*x,uint64_t off,const void*b,size_t n,size_t*done){struct disk*d=x;(void)off;(void)b;(void)n;d->writes++;*done=0;return-KBL_EROFS;}
static int flush(void*x){((struct disk*)x)->flushes++;return-KBL_EIO;}
#define CHECK(c) do{if(!(c)){fprintf(stderr,"FAIL line%d %s\n",__LINE__,#c);exit(1);}}while(0)
static struct kbl_fs_host host={0,allocate,release};
static void read_expected(struct kbl_fs*fs,const char*p,uint64_t off,const unsigned char*expected,size_t n){void*f=0;size_t got;unsigned char b[8192];CHECK(n<=sizeof(b));CHECK(fs->ops->open(fs->opaque,p,KBL_FILE_READ,&f)==0);CHECK(fs->ops->read_at(fs->opaque,f,off,b,n,&got)==0&&got==n);CHECK(!memcmp(b,expected,n));CHECK(fs->ops->unmount(fs->opaque)==-KBL_EBUSY);CHECK(fs->ops->close(fs->opaque,f)==0);}
int main(int argc,char**argv){int arg;CHECK(argc>1);
    for(arg=1;arg<argc;arg++){
        struct disk d={0};struct kbl_fs_device dev;struct kbl_fs fs;struct kbl_file_stat st;FILE*in;long size;int rc,end;char name[768];void*f=0;unsigned before;size_t done;unsigned char expected[8192],zero[4096]={0};unsigned i;
        in=fopen(argv[arg],"rb");CHECK(in);CHECK(!fseek(in,0,SEEK_END));size=ftell(in);CHECK(size>0);CHECK(!fseek(in,0,SEEK_SET));d.bytes=(size_t)size;d.data=malloc(d.bytes);CHECK(d.data);CHECK(fread(d.data,1,d.bytes,in)==d.bytes);fclose(in);
        d.read_cap=97; /* Every metadata/data request must tolerate successful short reads. */
        dev=(struct kbl_fs_device){&d,d.bytes,4096,0,read_at,write_at,flush};
        CHECK(kbl_f2fs_mount(&host,&dev,"f2fs",0,&fs)==-KBL_EROFS&&!live);
        rc=kbl_f2fs_mount(&host,&dev,"f2fs",KBL_FS_READ_ONLY,&fs);if(rc)fprintf(stderr,"%s mount=%d\n",argv[arg],rc);CHECK(rc==0);
        CHECK(fs.ops->stat(fs.opaque,"/",&st)==0&&st.type==KBL_FILE_DIRECTORY);
        CHECK(fs.ops->open(fs.opaque,"/",KBL_FILE_READ,&f)==-KBL_EISDIR&&!f);
        CHECK(fs.ops->open(fs.opaque,"/missing",KBL_FILE_READ,&f)==-KBL_ENOENT&&!f);
        CHECK(fs.ops->open(fs.opaque,"/missing",KBL_FILE_WRITE|KBL_FILE_CREATE,&f)==-KBL_EROFS);
        CHECK(fs.ops->mkdir(fs.opaque,"/new")==-KBL_EROFS);CHECK(fs.ops->unlink(fs.opaque,"/new")==-KBL_EROFS);CHECK(fs.ops->rename(fs.opaque,"/a","/b")==-KBL_EROFS);
        CHECK(fs.ops->sync(fs.opaque,0)==0);CHECK(!d.writes&&!d.flushes);
        if(strstr(argv[arg],"reader-fixture")){
            char deep[7000];size_t path_bytes=0;
            for(unsigned part=0;part<25;++part){deep[path_bytes++]='/';memset(deep+path_bytes,'d',200);path_bytes+=200;}memcpy(deep+path_bytes,"/deepfile",10);
            CHECK(strlen(deep)>4096);read_expected(&fs,deep,0,(const unsigned char*)"inline hello",12);
            memcpy(deep+path_bytes,"/alias",7);read_expected(&fs,deep,0,(const unsigned char*)"inline hello",12);
            unsigned baseline=live,first_call=allocation_calls;CHECK(!fs.ops->stat(fs.opaque,deep,&st));unsigned needed=allocation_calls-first_call;int successful=0;
            for(unsigned budget=0;budget<=needed;++budget){allocation_budget=(int)budget;int lookup=fs.ops->stat(fs.opaque,deep,&st);allocation_budget=-1;
                CHECK(live==baseline);if(!lookup){successful=1;break;}CHECK(lookup==-KBL_ENOMEM);}
            CHECK(successful);
            rc=fs.ops->list(fs.opaque,"/",0,name,sizeof(name),&st,&end);if(rc||end||strcmp(name,"payload.bin"))fprintf(stderr,"fixture list rc=%d end=%d name=%s\n",rc,end,name);CHECK(rc==0&&!end&&!strcmp(name,"payload.bin"));
            for(i=0;i<sizeof(expected);i++)expected[i]=(unsigned char)(i*17u+3u);
            read_expected(&fs,"/payload.bin",0,expected,sizeof(expected));
            read_expected(&fs,"/inline.txt",0,(const unsigned char*)"inline hello",12);
            read_expected(&fs,"/子目录/子文件",0,(const unsigned char*)"inline hello",12);
            read_expected(&fs,"/alias",0,expected,64);
            read_expected(&fs,"/alias01",0,expected,64);
            CHECK(fs.ops->open(fs.opaque,"/loop-a",KBL_FILE_READ,&f)==-KBL_ELOOP&&!f);
            CHECK(fs.ops->open(fs.opaque,"/grow",KBL_FILE_READ,&f)==-KBL_ELOOP&&!f);
            char repeated[512]="";for(unsigned part=0;part<20;++part)strcat(repeated,"/root-link");strcat(repeated,"/inline.txt");
            read_expected(&fs,repeated,0,(const unsigned char*)"inline hello",12);
            baseline=live;first_call=allocation_calls;CHECK(!fs.ops->stat(fs.opaque,"/alias01",&st));needed=allocation_calls-first_call;
            unsigned budgets[]={0,needed/4,needed/2,needed-1,needed};
            for(unsigned k=0;k<sizeof(budgets)/sizeof(budgets[0]);++k){allocation_budget=(int)budgets[k];int lookup=fs.ops->stat(fs.opaque,"/alias01",&st);allocation_budget=-1;
                CHECK(live==baseline);CHECK(lookup==(budgets[k]<needed?-KBL_ENOMEM:0));}
            read_expected(&fs,"/large.bin",0,zero,sizeof(zero));
            read_expected(&fs,"/large.bin",(uint64_t)923*4096,expected,4096);
            read_expected(&fs,"/indirect.bin",(uint64_t)(923+2*1018)*4096,expected,4096);
            read_expected(&fs,"/double.bin",(uint64_t)(923+2*1018+2*1018*1018)*4096,expected,4096);
            CHECK(fs.ops->open(fs.opaque,"/payload.bin",KBL_FILE_READ,&f)==0);
            d.read_cap=0;d.read_limit=d.reads+1;CHECK(fs.ops->read_at(fs.opaque,f,0,expected,sizeof(expected),&done)==-KBL_EIO&&done==4096);d.read_limit=0;d.read_cap=97;CHECK(fs.ops->close(fs.opaque,f)==0);f=0;
            CHECK(fs.ops->open(fs.opaque,"/payload.bin",KBL_FILE_READ,&f)==0);
            d.read_limit=d.reads+1;CHECK(fs.ops->read_at(fs.opaque,f,0,expected,sizeof(expected),&done)==-KBL_EIO&&done==97);d.read_limit=0;CHECK(fs.ops->close(fs.opaque,f)==0);f=0;
            CHECK(fs.ops->open(fs.opaque,"/encrypted",KBL_FILE_READ,&f)==-KBL_EACCES&&!f);
            CHECK(fs.ops->open(fs.opaque,"/compressed",KBL_FILE_READ,&f)==-KBL_ENOSYS&&!f);
        }else CHECK(fs.ops->list(fs.opaque,"/",0,name,sizeof(name),&st,&end)==0&&end);
        CHECK(fs.ops->write_at(fs.opaque,0,0,zero,sizeof(zero),&done)==-KBL_EROFS&&!done);
        CHECK(fs.ops->unmount(fs.opaque)==0&&!live&&!d.writes&&!d.flushes);
        /* Corrupt primary superblock; valid backup must remain usable. */
        d.data[1024]^=0xff;CHECK(kbl_f2fs_mount(&host,&dev,"f2fs",KBL_FS_READ_ONLY,&fs)==0);CHECK(fs.ops->unmount(fs.opaque)==0&&!live);
        d.data[5120]^=0xff;before=live;CHECK(kbl_f2fs_mount(&host,&dev,"f2fs",KBL_FS_READ_ONLY,&fs)<0&&live==before&&!fs.opaque);
        printf("PASS %s: actual checkpoint/NAT mount, %s, hard RO, backup superblock, failure cleanup\n",argv[arg],strstr(argv[arg],"reader-fixture")?"data/inline/Unicode/dirs/symlink/hole/direct-node + encrypted/compressed refusal":"root directory");
        free(d.data);
    }return 0;
}
