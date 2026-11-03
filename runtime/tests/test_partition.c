/* SPDX-License-Identifier: MIT */
#include "../uefi/kbl_partition.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(x) do{if(!(x)){printf("partition FAIL %u: %s\n",__LINE__,#x);return 1;}}while(0)
enum { CAPACITY=262144, PARTS=4 };
struct disk {struct kbl_efi_block_media media;struct kbl_efi_block_io io;struct kbl_efi_partition_info info;unsigned char bytes[CAPACITY];unsigned standard,fallback;};
struct fixture {
    struct kefi_boot_services bs;struct kbl_partition_provider provider;struct disk disks[PARTS];
    size_t count;unsigned enum_alloc,enum_free,alloc,free,live,reads,writes;
    unsigned fail_read,change_media,fail_alloc,fail_enum,fail_enum_free,fail_info,change_align;
};
static struct fixture f;
static kefi_status read_blocks(struct kbl_efi_block_io*io,uint32_t id,uint64_t lba,size_t n,void*out){
    ++f.reads;struct disk*d=0;
    for(unsigned i=0;i<PARTS;++i)if(io==&f.disks[i].io)d=&f.disks[i];
    if(!d||!out||!n||id!=d->media.media_id||n%d->media.block_size||lba>d->media.last_block||
       n/d->media.block_size-1>d->media.last_block-lba||
       (d->media.io_align>1&&(uintptr_t)out%d->media.io_align))return KEFI_INVALID;
    if(f.fail_read)return KEFI_ERROR(7);
    memcpy(out,d->bytes+lba*d->media.block_size,n);
    if(f.change_media)++d->media.media_id;
    return KEFI_SUCCESS;
}
static kefi_status write_blocks(void){++f.writes;return KEFI_ERROR(15);}
static kefi_status handles(unsigned search,const struct kefi_guid*guid,void*key,size_t*count,kefi_handle**out){
    if(search!=2||guid->a!=0x964e5b21||key)return KEFI_INVALID;
    if(f.fail_enum)return KEFI_ERROR(7);
    *count=f.count;*out=malloc((f.count?f.count:1)*sizeof(**out));
    if(!*out)return KEFI_OUT_OF_RESOURCES;
    ++f.enum_alloc;for(size_t i=0;i<f.count;++i)(*out)[i]=f.count>PARTS&&i?0:&f.disks[i];return KEFI_SUCCESS;
}
static kefi_status free_handles(void*p){if(f.fail_enum_free)return KEFI_ERROR(7);++f.enum_free;free(p);return KEFI_SUCCESS;}
static kefi_status protocol(kefi_handle h,const struct kefi_guid*guid,void**out){
    struct disk*d=h;*out=0;if(!d)return KEFI_ERROR(14);
    if(guid->a==0x964e5b21){*out=&d->io;return KEFI_SUCCESS;}
    if(guid->a==0x8cf2f62c){if(f.fail_info)return KEFI_ERROR(7);if(d->standard){*out=&d->info;return KEFI_SUCCESS;}}
    if(guid->a==0xfe2555be&&d->fallback){*out=&d->info.gpt;return KEFI_SUCCESS;}
    return KEFI_ERROR(14);
}
static void*allocate(void*unused,size_t bytes,size_t align){
    (void)unused;++f.alloc;if(f.fail_alloc||align>16)return 0;
    void*p=malloc(bytes);if(p)++f.live;
    if(f.change_align)f.disks[0].media.io_align=131072;
    return p;
}
static void release(void*unused,void*p,size_t bytes){(void)unused;(void)bytes;++f.free;--f.live;free(p);}
static int setup(unsigned block,unsigned alignment){
    memset(&f,0,sizeof(f));f.count=1;
    f.bs.handle_protocol=protocol;f.bs.locate_handle_buffer=(void*)handles;f.bs.free_pool=free_handles;
    for(unsigned i=0;i<PARTS;++i){
        struct disk*d=&f.disks[i];d->media=(struct kbl_efi_block_media){7,0,1,1,1,0,block,alignment,CAPACITY/block-1};
        d->io=(struct kbl_efi_block_io){0x20031,&d->media,0,read_blocks,(void*)write_blocks,0};
        d->info.revision=0x1000;d->info.type=2;d->info.gpt.type.a=1;
        d->info.gpt.starting_lba=100;d->info.gpt.ending_lba=100+d->media.last_block;
        d->standard=1;const char*name="abl_a";
        for(unsigned j=0;name[j];++j)d->info.gpt.name[j]=(uint16_t)name[j];
        for(unsigned j=0;j<CAPACITY;++j)d->bytes[j]=(unsigned char)(j*31+i);
    }
    return kbl_partition_init(&f.provider,&f.bs,0,allocate,release);
}
static int balanced(void){return f.enum_alloc==f.enum_free&&f.alloc==f.free&&!f.live&&!f.writes&&kbl_partition_can_unload(&f.provider);}
static int roundtrip(unsigned block,unsigned align){
    CHECK(setup(block,align)==KBL_OK);CHECK(!f.reads&&!f.alloc&&!f.enum_alloc);
    struct kbl_reader r={0};CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_OK);
    CHECK(r.size==CAPACITY&&!f.reads&&f.provider.readers==1&&!kbl_partition_can_unload(&f.provider));
    unsigned char out[90002];size_t got=99;
    CHECK(r.read_at(r.opaque,17,out+1,90000,&got)==KBL_OK&&got==90000);
    CHECK(memcmp(out+1,f.disks[0].bytes+17,90000)==0&&f.reads>=2);
    CHECK(r.read_at(r.opaque,CAPACITY-3,out,3,&got)==KBL_OK&&got==3);
    CHECK(memcmp(out,f.disks[0].bytes+CAPACITY-3,3)==0);
    unsigned calls=f.reads;CHECK(r.read_at(r.opaque,CAPACITY,0,0,&got)==KBL_OK&&!got&&f.reads==calls);
    CHECK(r.read_at(r.opaque,CAPACITY, out,1,&got)==KBL_ERROR&&!got);
    CHECK(r.read_at(r.opaque,UINT64_MAX,out,2,&got)==KBL_ERROR&&!got);
    r.close(r.opaque);CHECK(balanced());return 0;
}
int main(void){
    int tests=0;CHECK(!roundtrip(512,0));++tests;CHECK(!roundtrip(4096,4096));++tests;CHECK(!roundtrip(512,131072));++tests;
    struct kbl_reader r={0};size_t got;unsigned char out[8];
    CHECK(setup(512,1)==KBL_OK);f.count=65537;
    CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_OK);r.close(r.opaque);CHECK(balanced());++tests;
    CHECK(setup(512,1)==KBL_OK);f.count=65537;size_t needed=0;struct kbl_block_info item={0};
    CHECK(kbl_partition_list(&f.provider,&item,1,&needed)==KBL_OK&&needed==1&&!strcmp(item.label,"abl_a")&&balanced());++tests;
    CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};f.disks[0].standard=0;f.disks[0].fallback=1;
    CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_OK);r.close(r.opaque);CHECK(balanced());++tests;
    CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};f.disks[0].fallback=1;
    f.disks[0].info.type=1;CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_ERROR&&balanced());++tests;
    CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};f.count=2;
    CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_ERROR&&balanced());++tests;
    const char*missing[]={"missing","abl","abl_b","ABL_A","kbl.0123","","1234567890123456789012345678901234567","\xc0\xaf","\xed\xa0\x80","\xf4\x90\x80\x80","\xe4"};
    for(unsigned i=0;i<sizeof(missing)/sizeof(*missing);++i){
        CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};
        CHECK(kbl_partition_open(&f.provider,missing[i],&r)==KBL_ERROR&&balanced());++tests;
    }
    CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};
    for(unsigned i=0;i<36;++i)f.disks[0].info.gpt.name[i]='x';
    CHECK(kbl_partition_open(&f.provider,"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",&r)==KBL_OK);
    r.close(r.opaque);CHECK(balanced());++tests;
    CHECK(setup(4096,8192)==KBL_OK);r=(struct kbl_reader){0};
    memset(f.disks[0].info.gpt.name,0,72);f.disks[0].info.gpt.name[0]=0x4e2d;f.disks[0].info.gpt.name[1]=0xd83d;f.disks[0].info.gpt.name[2]=0xde00;
    CHECK(kbl_partition_open(&f.provider,"\xe4\xb8\xad\xf0\x9f\x98\x80",&r)==KBL_OK);r.close(r.opaque);CHECK(balanced());++tests;
    for(unsigned mode=0;mode<10;++mode){
        CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};
        if(mode==0)f.disks[0].media.present=0;
        if(mode==1)f.disks[0].media.logical=0;
        if(mode==2)f.disks[0].media.block_size=0;
        if(mode==3)f.disks[0].media.io_align=3;
        if(mode==4)f.disks[0].media.last_block=UINT64_MAX;
        if(mode==5)f.disks[0].media.last_block=UINT64_MAX/512;
        if(mode==6)--f.disks[0].info.gpt.ending_lba;
        if(mode==7)f.disks[0].info.gpt.type.a=0;
        if(mode==8)f.disks[0].io.read_blocks=0;
        if(mode==9)f.disks[0].info.revision=0;
        CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_ERROR&&balanced());++tests;
    }
    for(unsigned mode=0;mode<8;++mode){
        CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};
        CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_OK);
        if(mode==0)f.fail_read=1;
        if(mode==1)++f.disks[0].media.media_id;
        if(mode==2)f.change_media=1;
        if(mode==3)f.disks[0].media.block_size=4096;
        if(mode==4)f.disks[0].media.io_align=4096;
        if(mode==5)--f.disks[0].media.last_block;
        if(mode==6)f.disks[0].media.present=0;
        if(mode==7)f.disks[0].io.read_blocks=0;
        CHECK(r.read_at(r.opaque,0,out,8,&got)==KBL_ERROR&&!got);
        r.close(r.opaque);CHECK(balanced());++tests;
    }
    CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};f.fail_alloc=1;
    CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_ERROR);CHECK(f.enum_free==1&&!f.live&&!f.reads);++tests;
    CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};f.fail_enum_free=1;
    CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_ERROR&&f.provider.pending_handles&&!kbl_partition_can_unload(&f.provider));
    CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_ERROR&&f.enum_alloc==1);
    f.fail_enum_free=0;CHECK(kbl_partition_cleanup(&f.provider)==KBL_OK&&balanced());++tests;
    CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};f.change_align=1;
    CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_ERROR&&balanced());++tests;
    CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};f.fail_enum=1;
    CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_ERROR&&balanced());++tests;
    CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};f.fail_info=1;
    CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_ERROR&&balanced());++tests;
    CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};f.count=0;
    CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_ERROR&&balanced());++tests;
    CHECK(setup(512,1)==KBL_OK);r=(struct kbl_reader){0};CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_OK);
    CHECK(kbl_partition_open(&f.provider,"abl_a",&r)==KBL_ERROR&&f.alloc==1);r.close(r.opaque);CHECK(balanced());++tests;
    printf("Partition provider fixture: %d cases passed; mock UEFI only, no device.\n",tests);return 0;
}
