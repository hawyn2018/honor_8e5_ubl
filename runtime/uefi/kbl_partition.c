/* SPDX-License-Identifier: MIT */
#include "kbl_partition.h"

static const struct kefi_guid block_guid={0x964e5b21,0x6459,0x11d2,{0x8e,0x39,0x00,0xa0,0xc9,0x69,0x72,0x3b}};
static const struct kefi_guid info_guid={0x8cf2f62c,0xbc9b,0x4821,{0x80,0x8d,0xec,0x9e,0xc4,0x21,0xa1,0xa0}};
/* Qualcomm QcomModulePkg.dec gEfiPartitionRecordGuid. Its original
 * FastbootCmds.c PartitionGetInfo consumes the interface as EFI_PARTITION_ENTRY.
 * Consult tests/PARTITION_PROVIDER.md for exact local primary-source evidence. */
static const struct kefi_guid record_guid={0xfe2555be,0xd716,0x4686,{0xb9,0xd0,0x79,0xdb,0x59,0x21,0xb7,0x0d}};
typedef kefi_status (*locate_handles)(unsigned,const struct kefi_guid *,void *,size_t *,kefi_handle **);
struct partition_reader {
    struct kbl_partition_provider *provider;
    struct kbl_efi_block_io *io;
    struct kbl_efi_block_media *media;
    uint32_t media_id,block_size,io_align;
    uint64_t last_block,size;
    size_t allocation_bytes,bounce_bytes;
    unsigned char *bounce;
    unsigned reading,access;
};
static void copy(void*d_,const void*s_,size_t n){unsigned char*d=d_;const unsigned char*s=s_;while(n--)*d++=*s++;}
static int absent(kefi_status status){return status==KEFI_UNSUPPORTED||status==KEFI_ERROR(14);}
static int utf16_label(const char*text,uint16_t label[36],size_t*units){
    if(!text)return 0;
    size_t at=0,n=0;
    while(at<145){
        unsigned c=(unsigned char)text[at++],need=0;uint32_t cp;
        if(!c){if(!n)return 0;*units=n;return 1;}
        if(c<0x80)cp=c;
        else if(c>=0xc2&&c<=0xdf){cp=c&31;need=1;}
        else if(c>=0xe0&&c<=0xef){cp=c&15;need=2;}
        else if(c>=0xf0&&c<=0xf4){cp=c&7;need=3;}
        else return 0;
        for(unsigned i=0;i<need;++i){
            if(at>=145)return 0;
            unsigned d=(unsigned char)text[at++];if((d&0xc0)!=0x80)return 0;cp=(cp<<6)|(d&63);
        }
        if((need==1&&cp<0x80)||(need==2&&cp<0x800)||(need==3&&cp<0x10000)||cp>0x10ffff||(cp>=0xd800&&cp<=0xdfff))return 0;
        if(cp<0x10000){if(n==36)return 0;label[n++]=(uint16_t)cp;}
        else{if(n>34)return 0;cp-=0x10000;label[n++]=(uint16_t)(0xd800+(cp>>10));label[n++]=(uint16_t)(0xdc00+(cp&1023));}
    }
    return 0;
}
static int match(const struct kbl_efi_gpt_entry*entry,const uint16_t*name,size_t units){
    for(size_t i=0;i<units;++i)if(entry->name[i]!=name[i])return 0;
    if(units<36&&entry->name[units])return 0;
    return 1;
}
static int entry_for(struct kbl_partition_provider*p,kefi_handle handle,struct kbl_efi_gpt_entry*out){
    void*ptr=0;kefi_status status=p->bs->handle_protocol(handle,&info_guid,&ptr);
    if(status==KEFI_SUCCESS){
        if(!ptr)return -1;
        struct kbl_efi_partition_info*info=ptr;
        if(info->type!=2)return 0;
        copy(out,&info->gpt,sizeof(*out));return 1;
    }
    if(!absent(status))return -1;
    ptr=0;status=p->bs->handle_protocol(handle,&record_guid,&ptr);
    if(status==KEFI_SUCCESS){if(!ptr)return -1;copy(out,ptr,sizeof(*out));return 1;}
    return absent(status)?0:-1;
}
static int media_valid(struct kbl_efi_block_io*io,const struct kbl_efi_gpt_entry*entry,uint64_t*size){
    if(!io||!io->media||!io->read_blocks)return 0;
    struct kbl_efi_block_media*m=io->media;
    if(!m->present||!m->logical||!m->block_size||m->last_block==UINT64_MAX||
       (m->io_align>1&&(m->io_align&(m->io_align-1)))||
       m->last_block+1>UINT64_MAX/m->block_size)return 0;
    if(!entry->starting_lba||entry->ending_lba<entry->starting_lba||entry->ending_lba-entry->starting_lba!=m->last_block)return 0;
    const unsigned char*type=(const unsigned char*)&entry->type;unsigned nonzero=0;
    for(unsigned i=0;i<sizeof(entry->type);++i)nonzero|=type[i];
    if(!nonzero)return 0;
    *size=(m->last_block+1)*m->block_size;return 1;
}
static int unchanged(const struct partition_reader*r){
    const struct kbl_efi_block_media*m=r->io->media;
    return r->io->read_blocks&&m&&m==r->media&&m->present&&m->logical&&m->media_id==r->media_id&&
           m->block_size==r->block_size&&m->io_align==r->io_align&&m->last_block==r->last_block;
}
static int partition_read(void*opaque,uint64_t offset,void*buffer,size_t bytes,size_t*got){
    struct partition_reader*r=opaque;
    if(!got)return KBL_ERROR;*got=0;
    if(!r||r->reading||!(r->access&KBL_BLOCK_READ)||(!buffer&&bytes)||offset>r->size||bytes>r->size-offset||!unchanged(r))return KBL_ERROR;
    if(!bytes)return KBL_OK;
    r->reading=1;size_t done=0;int result=KBL_OK;
    while(done<bytes){
        uint64_t lba=offset/r->block_size;size_t skip=(size_t)(offset%r->block_size);
        size_t take=bytes-done;if(take>r->bounce_bytes-skip)take=r->bounce_bytes-skip;
        size_t cover=skip+take,blocks=cover/r->block_size+(cover%r->block_size!=0);
        if(!blocks||lba>r->last_block||blocks-1>r->last_block-lba||!unchanged(r)||
           r->io->read_blocks(r->io,r->media_id,lba,blocks*r->block_size,r->bounce)!=KEFI_SUCCESS||!unchanged(r)){
            result=KBL_ERROR;break;
        }
        copy((unsigned char*)buffer+done,r->bounce+skip,take);done+=take;offset+=take;
    }
    r->reading=0;if(result==KBL_OK)*got=done;return result;
}
static int writable(const struct partition_reader*r){return unchanged(r)&&(r->access&KBL_BLOCK_WRITE)&&!r->media->read_only&&r->io->write_blocks;}
static int partition_write(void*opaque,uint64_t offset,const void*buffer,size_t bytes,size_t*written){
    struct partition_reader*r=opaque;if(!written)return KBL_ERROR;*written=0;
    if(!r||r->reading||(!buffer&&bytes)||offset>r->size||bytes>r->size-offset||!writable(r))return KBL_ERROR;
    if(!bytes)return KBL_OK;
    const unsigned char*input=buffer;size_t done=0;int result=KBL_OK;r->reading=1;
    while(done<bytes){
        uint64_t lba=offset/r->block_size;size_t skip=(size_t)(offset%r->block_size),take=bytes-done,transfer;
        if(skip||take<r->block_size){
            if(take>r->block_size-skip)take=r->block_size-skip;
            transfer=r->block_size;
            if(!writable(r)||r->io->read_blocks(r->io,r->media_id,lba,transfer,r->bounce)!=KEFI_SUCCESS||!writable(r)){result=KBL_ERROR;break;}
            copy(r->bounce+skip,input+done,take);
        }else{
            if(take>r->bounce_bytes)take=r->bounce_bytes;take-=take%r->block_size;transfer=take;
            copy(r->bounce,input+done,take);
        }
        if(!transfer||lba>r->last_block||transfer/r->block_size-1>r->last_block-lba||!writable(r)||
           r->io->write_blocks(r->io,r->media_id,lba,transfer,r->bounce)!=KEFI_SUCCESS){result=KBL_ERROR;break;}
        /* EFI WriteBlocks has no byte-count output. Only fully successful
         * earlier calls count as confirmed progress; a failed transfer may
         * have unknown partial effects and is never retried automatically. */
        done+=take;offset+=take;
        if(!writable(r)){result=KBL_ERROR;break;}
    }
    r->reading=0;*written=done;return result;
}
static int partition_flush(void*opaque){
    struct partition_reader*r=opaque;
    if(!r||r->reading||!writable(r)||!r->io->flush_blocks)return KBL_ERROR;
    r->reading=1;kefi_status status=r->io->flush_blocks(r->io);r->reading=0;
    return status==KEFI_SUCCESS&&writable(r)?KBL_OK:KBL_ERROR;
}
static void partition_close(void*opaque){
    struct partition_reader*r=opaque;if(!r)return;
    struct kbl_partition_provider*p=r->provider;size_t bytes=r->allocation_bytes;
    /* close is once-only and serialized by the caller; a suspended read must
     * never be freed by an asynchronous unload. */
    if(r->reading)return;
    if(p->readers)--p->readers;
    p->release(p->opaque,r,bytes);
}
int kbl_partition_init(struct kbl_partition_provider*p,struct kefi_boot_services*bs,void*opaque,
                       void*(*allocate)(void*,size_t,size_t),void(*release)(void*,void*,size_t)){
    if(!p||p->initialized||!bs||!bs->handle_protocol||!bs->locate_handle_buffer||!bs->free_pool||!allocate||!release)return KBL_ERROR;
    *p=(struct kbl_partition_provider){bs,opaque,allocate,release,0,0,1};return KBL_OK;
}
int kbl_partition_can_unload(const struct kbl_partition_provider*p){return !p||(!p->readers&&!p->pending_handles);}
int kbl_partition_cleanup(struct kbl_partition_provider*p){
    if(!p||!p->initialized)return KBL_ERROR;
    if(p->pending_handles){
        if(p->bs->free_pool(p->pending_handles)!=KEFI_SUCCESS)return KBL_ERROR;
        p->pending_handles=0;
    }
    return KBL_OK;
}
static int release_handles(struct kbl_partition_provider*p,void*handles){
    if(!handles)return KBL_OK;
    p->pending_handles=handles;
    return kbl_partition_cleanup(p);
}
int kbl_partition_block_open(void*opaque,const char*label,unsigned access,struct kbl_block*out){
    struct kbl_partition_provider*p=opaque;uint16_t name[36];size_t units;
    if(!p||!p->initialized||!out||!access||(access&~(KBL_BLOCK_READ|KBL_BLOCK_WRITE))||out->reader.opaque||out->reader.read_at||out->reader.close||out->reader.size||
       out->block_bytes||out->flags||out->write_at||out->flush||!utf16_label(label,name,&units))return KBL_ERROR;
    if(units>=4&&name[0]=='k'&&name[1]=='b'&&name[2]=='l'&&name[3]=='.')return KBL_ERROR;
    if(kbl_partition_cleanup(p)!=KBL_OK)return KBL_ERROR;
    size_t count=0;kefi_handle*handles=0;
    kefi_status status=((locate_handles)p->bs->locate_handle_buffer)(2,&block_guid,0,&count,&handles);
    if(status!=KEFI_SUCCESS||!count||!handles){(void)release_handles(p,handles);return KBL_ERROR;}
    if(count>SIZE_MAX/sizeof(*handles)){(void)release_handles(p,handles);return KBL_ERROR;}
    struct kbl_efi_block_io*selected=0;struct kbl_efi_gpt_entry selected_entry={0};int failed=0;
    for(size_t i=0;i<count;++i){
        struct kbl_efi_gpt_entry entry;int kind=entry_for(p,handles[i],&entry);
        if(kind<0){failed=1;break;}if(!kind||!match(&entry,name,units))continue;
        void*io=0;
        if(selected||p->bs->handle_protocol(handles[i],&block_guid,&io)!=KEFI_SUCCESS||!io){failed=1;break;}
        selected=io;selected_entry=entry;
    }
    /* Failed firmware free retains the pointer and blocks unload/new opens
     * until cleanup succeeds; enumeration storage is never silently lost. */
    if(release_handles(p,handles)!=KBL_OK)return KBL_ERROR;
    uint64_t size=0;if(failed||!selected||!media_valid(selected,&selected_entry,&size)||p->readers==SIZE_MAX)return KBL_ERROR;
    struct kbl_efi_block_media*m=selected->media,snapshot=*m;
    if(!snapshot.block_size||snapshot.last_block==UINT64_MAX||
       snapshot.last_block+1>UINT64_MAX/snapshot.block_size||
       (snapshot.last_block+1)*snapshot.block_size!=size||
       (snapshot.io_align>1&&(snapshot.io_align&(snapshot.io_align-1))))return KBL_ERROR;
    if((access&KBL_BLOCK_WRITE)&&snapshot.read_only){out->flags=KBL_BLOCK_READ_ONLY;return KBL_ERROR;}
    if((access&KBL_BLOCK_WRITE)&&(!selected->write_blocks||!selected->flush_blocks))return KBL_ERROR;
    size_t alignment=snapshot.io_align>1?snapshot.io_align:1;
    size_t bounce=snapshot.block_size>=65536?snapshot.block_size:(65536/snapshot.block_size)*snapshot.block_size;
    if(bounce>SIZE_MAX-sizeof(struct partition_reader)-(alignment-1))return KBL_ERROR;
    size_t allocation=sizeof(struct partition_reader)+bounce+alignment-1;
    struct partition_reader*r=p->allocate(p->opaque,allocation,_Alignof(struct partition_reader));if(!r)return KBL_ERROR;
    uintptr_t start=(uintptr_t)(r+1);
    if(start>UINTPTR_MAX-(alignment-1)){p->release(p->opaque,r,allocation);return KBL_ERROR;}
    *r=(struct partition_reader){p,selected,m,snapshot.media_id,snapshot.block_size,snapshot.io_align,snapshot.last_block,size,allocation,bounce,
         (unsigned char*)((start+alignment-1)&~(uintptr_t)(alignment-1)),0,access};
    if(!unchanged(r)||r->last_block==UINT64_MAX||r->last_block+1>UINT64_MAX/r->block_size||
       (r->last_block+1)*r->block_size!=size){p->release(p->opaque,r,allocation);return KBL_ERROR;}
    if((access&KBL_BLOCK_WRITE)&&!writable(r)){p->release(p->opaque,r,allocation);return KBL_ERROR;}
    ++p->readers;*out=(struct kbl_block){{r,size,partition_read,partition_close},r->block_size,
        snapshot.read_only?KBL_BLOCK_READ_ONLY:0,(access&KBL_BLOCK_WRITE)?partition_write:0,(access&KBL_BLOCK_WRITE)?partition_flush:0};return KBL_OK;
}
int kbl_partition_open(void*opaque,const char*label,struct kbl_reader*out){
    if(!out||out->opaque||out->read_at||out->close||out->size)return KBL_ERROR;
    struct kbl_block block={0};int result=kbl_partition_block_open(opaque,label,KBL_BLOCK_READ,&block);
    if(result==KBL_OK)*out=block.reader;return result;
}
static int utf8_label(const uint16_t name[36],char output[KBL_BLOCK_LABEL_BYTES]){
    size_t at=0;for(unsigned i=0;i<36&&name[i];++i){uint32_t cp=name[i];
        if(cp>=0xd800&&cp<=0xdbff){if(i+1>=36||name[i+1]<0xdc00||name[i+1]>0xdfff)return 0;cp=0x10000+((cp-0xd800)<<10)+(name[++i]-0xdc00);}
        else if(cp>=0xdc00&&cp<=0xdfff)return 0;
        unsigned n=cp<128?1:cp<2048?2:cp<65536?3:4;if(at+n>=KBL_BLOCK_LABEL_BYTES)return 0;
        if(n==1)output[at++]=(char)cp;
        else{output[at++]=(char)((n==2?0xc0:n==3?0xe0:0xf0)|(cp>>(6*(n-1))));for(unsigned j=n-1;j;--j)output[at++]=(char)(0x80|((cp>>(6*(j-1)))&63));}
    }output[at]=0;return at!=0;
}
static int order(const char*a,const char*b){while(*a&&*a==*b){++a;++b;}return(unsigned char)*a-(unsigned char)*b;}
static void swap_info(struct kbl_block_info*a,struct kbl_block_info*b){struct kbl_block_info t=*a;*a=*b;*b=t;}
static void sift(struct kbl_block_info*a,size_t start,size_t count){
    for(size_t root=start;root<count/2;){size_t child=root*2+1;if(child+1<count&&order(a[child].label,a[child+1].label)<0)++child;
        if(order(a[root].label,a[child].label)>=0)break;swap_info(&a[root],&a[child]);root=child;}
}
int kbl_partition_list(void*opaque,struct kbl_block_info*out,size_t capacity,size_t*needed){
    struct kbl_partition_provider*p=opaque;if(!needed)return KBL_ERROR;*needed=0;
    if(!p||!p->initialized||(capacity&&!out)||kbl_partition_cleanup(p)!=KBL_OK)return KBL_ERROR;
    size_t count=0;kefi_handle*handles=0;kefi_status status=((locate_handles)p->bs->locate_handle_buffer)(2,&block_guid,0,&count,&handles);
    if(status==KEFI_ERROR(14)&&!count&&!handles)return KBL_OK;
    if(status!=KEFI_SUCCESS||(count&&!handles)||count>SIZE_MAX/sizeof(*handles)||count>SIZE_MAX/sizeof(struct kbl_block_info)){(void)release_handles(p,handles);return KBL_ERROR;}
    if(!count)return release_handles(p,handles);
    size_t bytes=count*sizeof(struct kbl_block_info),used=0;struct kbl_block_info*list=p->allocate(p->opaque,bytes,_Alignof(struct kbl_block_info));
    if(!list){(void)release_handles(p,handles);return KBL_ERROR;}
    int failed=0;for(size_t i=0;i<count;++i){struct kbl_efi_gpt_entry entry;int kind=entry_for(p,handles[i],&entry);
        if(kind<0){failed=1;break;}if(!kind)continue;void*v=0;uint64_t size;
        if(p->bs->handle_protocol(handles[i],&block_guid,&v)!=KEFI_SUCCESS||!media_valid(v,&entry,&size)||
           !utf8_label(entry.name,list[used].label)){failed=1;break;}
        if(list[used].label[0]=='k'&&list[used].label[1]=='b'&&list[used].label[2]=='l'&&list[used].label[3]=='.'){failed=1;break;}
        struct kbl_efi_block_io*io=v;list[used].bytes=size;list[used].block_bytes=io->media->block_size;
        list[used].flags=io->media->read_only?KBL_BLOCK_READ_ONLY:0;++used;
    }
    if(release_handles(p,handles)!=KBL_OK)failed=1;
    if(!failed){for(size_t i=used/2;i;--i)sift(list,i-1,used);for(size_t i=used;i>1;--i){swap_info(&list[0],&list[i-1]);sift(list,0,i-1);}
        for(size_t i=1;i<used;++i)if(!order(list[i-1].label,list[i].label)){failed=1;break;}}
    int result=KBL_ERROR;if(!failed){*needed=used;if(!capacity)result=KBL_OK;else if(capacity>=used){if(used)copy(out,list,used*sizeof(*list));result=KBL_OK;}}
    p->release(p->opaque,list,bytes);return result;
}
