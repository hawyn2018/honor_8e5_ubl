/* Synthetic UEFI BlockIO memory disks only; no physical target. */
#define main readonly_regression_main
#include "test_partition.c"
#undef main
static unsigned flushes,fail_write_number,fail_flush,change_after_write;
static kefi_status commit(struct kbl_efi_block_io*io,uint32_t id,uint64_t lba,size_t bytes,void*data){
    ++f.writes;struct disk*d=0;for(unsigned i=0;i<PARTS;++i)if(io==&f.disks[i].io)d=&f.disks[i];
    if(!d||d->media.read_only||id!=d->media.media_id||!bytes||bytes%d->media.block_size||lba>d->media.last_block||
       bytes/d->media.block_size-1>d->media.last_block-lba||(d->media.io_align>1&&(uintptr_t)data%d->media.io_align))return KEFI_INVALID;
    if(fail_write_number==f.writes)return KEFI_ERROR(7);
    memcpy(d->bytes+lba*d->media.block_size,data,bytes);
    if(change_after_write)++d->media.media_id;
    return KEFI_SUCCESS;
}
static kefi_status flush(struct kbl_efi_block_io*io){(void)io;++flushes;return fail_flush?KEFI_ERROR(7):KEFI_SUCCESS;}
static int rw_setup(unsigned block,unsigned align){
    flushes=fail_write_number=fail_flush=change_after_write=0;if(setup(block,align))return 1;
    for(unsigned i=0;i<PARTS;++i){f.disks[i].media.read_only=0;f.disks[i].io.write_blocks=commit;f.disks[i].io.flush_blocks=flush;}return 0;
}
static int rw_balanced(void){return f.enum_alloc==f.enum_free&&f.alloc==f.free&&!f.live&&kbl_partition_can_unload(&f.provider);}
static int byte_roundtrip(unsigned block,unsigned alignment){
    CHECK(!rw_setup(block,alignment));struct kbl_block b={0};CHECK(kbl_partition_block_open(&f.provider,"abl_a",3,&b)==0);
    CHECK(!f.reads&&!f.writes&&!flushes&&b.block_bytes==block&&b.reader.size==CAPACITY);
    static unsigned char before[CAPACITY],input[90003],readback[90003];memcpy(before,f.disks[0].bytes,CAPACITY);
    for(unsigned i=0;i<sizeof(input);++i)input[i]=(unsigned char)(i*17+3);
    size_t done=0;CHECK(b.write_at(b.reader.opaque,17,input+1,90000,&done)==0&&done==90000);
    CHECK(!memcmp(f.disks[0].bytes,before,17)&&!memcmp(f.disks[0].bytes+17,input+1,90000));
    CHECK(!memcmp(f.disks[0].bytes+90017,before+90017,CAPACITY-90017));
    CHECK(b.reader.read_at(b.reader.opaque,17,readback+1,90000,&done)==0&&done==90000&&!memcmp(readback+1,input+1,90000));
    CHECK(!flushes);CHECK(b.flush(b.reader.opaque)==0&&flushes==1);
    unsigned writes=f.writes,reads=f.reads;CHECK(b.write_at(b.reader.opaque,CAPACITY,0,0,&done)==0&&!done&&f.writes==writes&&f.reads==reads);
    CHECK(b.write_at(b.reader.opaque,CAPACITY,input,1,&done)==KBL_ERROR&&!done);
    CHECK(b.write_at(b.reader.opaque,UINT64_MAX,input,2,&done)==KBL_ERROR&&!done);
    b.reader.close(b.reader.opaque);CHECK(flushes==1&&rw_balanced());return 0;
}
int main(void){unsigned cases=0;
    CHECK(!byte_roundtrip(512,1));++cases;CHECK(!byte_roundtrip(4096,8192));++cases;CHECK(!byte_roundtrip(512,131072));++cases;
    struct kbl_block b={0};size_t done;unsigned char data[70000];memset(data,0xa5,sizeof(data));
    CHECK(!rw_setup(512,4096));CHECK(!kbl_partition_block_open(&f.provider,"abl_a",KBL_BLOCK_WRITE,&b));
    CHECK(b.reader.read_at(b.reader.opaque,0,data,1,&done)==KBL_ERROR&&!done);
    CHECK(!b.write_at(b.reader.opaque,3,data,2,&done)&&done==2&&f.reads==1);
    b.reader.close(b.reader.opaque);CHECK(rw_balanced());++cases;
    CHECK(!rw_setup(512,1));b=(struct kbl_block){0};CHECK(!kbl_partition_block_open(&f.provider,"abl_a",3,&b));
    CHECK(!b.write_at(b.reader.opaque,0,data,1536,&done)&&done==1536&&!f.reads&&f.writes==1);
    b.reader.close(b.reader.opaque);CHECK(rw_balanced());++cases;
    for(unsigned mode=0;mode<5;++mode){
        CHECK(!rw_setup(512,1));b=(struct kbl_block){0};CHECK(!kbl_partition_block_open(&f.provider,"abl_a",3,&b));
        if(mode==0)f.disks[0].media.read_only=1;
        if(mode==1)++f.disks[0].media.media_id;
        if(mode==2)f.change_media=1;
        if(mode==3)f.fail_read=1;
        if(mode==4)f.disks[0].io.write_blocks=0;
        CHECK(b.write_at(b.reader.opaque,1,data,3,&done)==KBL_ERROR&&!done&&!f.writes);
        b.reader.close(b.reader.opaque);CHECK(rw_balanced());++cases;
    }
    CHECK(!rw_setup(512,1));b=(struct kbl_block){0};CHECK(!kbl_partition_block_open(&f.provider,"abl_a",3,&b));
    fail_write_number=2;CHECK(b.write_at(b.reader.opaque,0,data,sizeof(data),&done)==KBL_ERROR&&done==65536&&f.writes==2);
    CHECK(!memcmp(f.disks[0].bytes,data,65536)&&!flushes);fail_write_number=0;fail_flush=1;CHECK(b.flush(b.reader.opaque)==KBL_ERROR&&flushes==1);
    b.reader.close(b.reader.opaque);CHECK(flushes==1&&rw_balanced());++cases;
    CHECK(!rw_setup(512,1));b=(struct kbl_block){0};CHECK(!kbl_partition_block_open(&f.provider,"abl_a",3,&b));change_after_write=1;
    CHECK(b.write_at(b.reader.opaque,0,data,512,&done)==KBL_ERROR&&done==512&&f.writes==1);
    b.reader.close(b.reader.opaque);CHECK(rw_balanced());++cases;
    for(unsigned mode=0;mode<4;++mode){CHECK(!rw_setup(512,1));b=(struct kbl_block){0};
        if(mode==0)f.disks[0].media.read_only=1;if(mode==1)f.disks[0].io.flush_blocks=0;if(mode==2)f.disks[0].io.write_blocks=0;
        CHECK(kbl_partition_block_open(&f.provider,"abl_a",mode==3?0:3,&b)==KBL_ERROR&&!b.reader.opaque&&!f.writes&&rw_balanced());++cases;}
    CHECK(!rw_setup(512,1));f.disks[0].media.read_only=1;b=(struct kbl_block){0};CHECK(!kbl_partition_block_open(&f.provider,"abl_a",1,&b));
    CHECK(b.flags==KBL_BLOCK_READ_ONLY&&!b.write_at&&!b.flush);b.reader.close(b.reader.opaque);CHECK(rw_balanced());++cases;
    struct kbl_block_info list[4],original[4];size_t count=99;
    CHECK(!rw_setup(4096,4096));f.count=3;
    memset(f.disks[1].info.gpt.name,0,72);f.disks[1].info.gpt.name[0]='z';
    memset(f.disks[2].info.gpt.name,0,72);f.disks[2].info.gpt.name[0]='b';
    CHECK(!kbl_partition_list(&f.provider,0,0,&count)&&count==3&&!f.reads&&!f.writes&&!flushes&&rw_balanced());
    memset(list,0xa5,sizeof(list));memcpy(original,list,sizeof(list));
    CHECK(kbl_partition_list(&f.provider,list,2,&count)==KBL_ERROR&&count==3&&!memcmp(list,original,sizeof(list))&&rw_balanced());
    CHECK(!kbl_partition_list(&f.provider,list,4,&count)&&count==3&&!strcmp(list[0].label,"abl_a")&&!strcmp(list[1].label,"b")&&!strcmp(list[2].label,"z"));
    CHECK(list[0].bytes==CAPACITY&&list[0].block_bytes==4096&&!list[0].flags&&rw_balanced());++cases;
    CHECK(!rw_setup(512,1));f.count=2;memset(list,0xa5,sizeof(list));memcpy(original,list,sizeof(list));
    CHECK(kbl_partition_list(&f.provider,list,4,&count)==KBL_ERROR&&!count&&!memcmp(list,original,sizeof(list))&&rw_balanced());++cases;
    CHECK(!rw_setup(512,1));f.disks[0].info.gpt.name[0]=0xd800;f.disks[0].info.gpt.name[1]=0;
    CHECK(kbl_partition_list(&f.provider,list,4,&count)==KBL_ERROR&&!count&&rw_balanced());++cases;
    CHECK(!rw_setup(512,1));f.count=0;CHECK(!kbl_partition_list(&f.provider,0,0,&count)&&!count&&rw_balanced());++cases;
    CHECK(!rw_setup(512,1));f.fail_enum_free=1;CHECK(kbl_partition_list(&f.provider,0,0,&count)==KBL_ERROR&&f.provider.pending_handles&&!f.live);
    f.fail_enum_free=0;CHECK(!kbl_partition_cleanup(&f.provider)&&rw_balanced());++cases;
    CHECK(!rw_setup(512,1));f.disks[0].standard=0;f.disks[0].fallback=1;
    memset(f.disks[0].info.gpt.name,0,72);f.disks[0].info.gpt.name[0]=0x4e2d;f.disks[0].info.gpt.name[1]=0xd83d;f.disks[0].info.gpt.name[2]=0xde00;
    CHECK(!kbl_partition_list(&f.provider,list,4,&count)&&count==1&&!strcmp(list[0].label,"\xe4\xb8\xad\xf0\x9f\x98\x80")&&rw_balanced());++cases;
    printf("{\"passed\":true,\"cases\":%u,\"scope\":\"Synthetic BlockIO byte RMW/write/flush/list model; no physical writes\"}\n",cases);return 0;
}
