/* SPDX-License-Identifier: MIT */
#include "kbl_elf.h"

#define IMAGE_COOKIE UINT64_C(0x4b424c454c463031)
struct segment { uint64_t offset, va, files, memory, low, high; unsigned flags; };
struct plan {
    const unsigned char *file; uint64_t phoff; unsigned phnum,count; size_t page_bytes;
    uint64_t minimum, maximum, alignment, entry, dynamic_va, dynamic_offset, dynamic_bytes;
    uint64_t rela_va, rela_bytes, rela_entry;
    unsigned abi_notes, dynamics, api_bytes;
};
static uint16_t u16(const unsigned char *p) { return (uint16_t)(p[0] | (unsigned)p[1] << 8); }
static uint32_t u32(const unsigned char *p) { return (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24; }
static uint64_t u64(const unsigned char *p) { return (uint64_t)u32(p) | (uint64_t)u32(p+4)<<32; }
static void store64(unsigned char *p, uint64_t v) { for (unsigned i=0;i<8;++i) p[i]=(unsigned char)(v>>(i*8)); }
static void clear(void *p, size_t bytes) { unsigned char *d=p; for(size_t i=0;i<bytes;++i)d[i]=0; }
static int span(uint64_t offset, uint64_t bytes, uint64_t limit) { return offset<=limit && bytes<=limit-offset; }
static int power2(uint64_t n) { return n && !(n&(n-1)); }
static int rounded(uint64_t n, uint64_t align, uint64_t *out) {
    if(n>UINT64_MAX-(align-1))return 0; *out=(n+align-1)&~(align-1); return 1;
}
/* Iterate validated PT_LOAD records directly. The plan has constant storage;
 * input PHDR/LOAD/relocation counts are bounded by their real file ranges. */
static int next_segment(const struct plan *p,unsigned *index,struct segment *out) {
    while(*index<p->phnum) {
        unsigned i=(*index)++;
        const unsigned char *q=p->file+p->phoff+(uint64_t)i*56;
        if(u32(q)!=1 || !u64(q+40))continue;
        uint64_t va=u64(q+16),memory=u64(q+40),high=0;
        if(!rounded(va+memory,p->page_bytes,&high))return 0; /* parse proved this */
        *out=(struct segment){u64(q+8),va,u64(q+32),memory,va&~((uint64_t)p->page_bytes-1),high,u32(q+4)};
        return 1;
    }
    return 0;
}
static int containing(const struct plan *p,uint64_t va,uint64_t n,int file,unsigned flags,struct segment *out) {
    unsigned index=0;struct segment s;
    while(next_segment(p,&index,&s)) {
        uint64_t size=file?s.files:s.memory;
        if((s.flags&flags)==flags && va>=s.va && span(va-s.va,n,size)){*out=s;return 1;}
    }
    return 0;
}
static int contains(const struct plan *p,uint64_t va,uint64_t n,int file,unsigned flags) {
    struct segment s;return containing(p,va,n,file,flags,&s);
}
static const unsigned char *file_at(const unsigned char *f,const struct plan *p,uint64_t va,uint64_t n) {
    struct segment s;return containing(p,va,n,1,4,&s)?f+s.offset+(va-s.va):0;
}
static int notes(const unsigned char *file,uint64_t offset,uint64_t bytes,struct plan *p) {
    uint64_t at=0;
    while(at<bytes) {
        if(!span(at,12,bytes))return KBL_ELF_FORMAT;
        const unsigned char *n=file+offset+at; uint64_t names=u32(n),desc=u32(n+4),nround,dround;
        if(!rounded(names,4,&nround)||!rounded(desc,4,&dround)||!span(at+12,nround,bytes)||
           !span(at+12+nround,dround,bytes))return KBL_ELF_BOUNDS;
        if(names==4 && n[12]=='K' && n[13]=='B' && n[14]=='L' && n[15]==0 && u32(n+8)==KBL_PROGRAM_NOTE_TYPE) {
            const unsigned char *d=n+12+nround;
            if(desc!=16 || u32(d+4)<KBL_PROGRAM_API_BASE_BYTES || u32(d+4)>sizeof(struct kbl_program_api) ||
               u32(d+8)||u32(d+12)||++p->abi_notes!=1)return KBL_ELF_ABI;
            p->api_bytes=u32(d+4);
        }
        at+=12+nround+dround;
    }
    return KBL_ELF_OK;
}
static int parse(const unsigned char *f,size_t bytes,const struct kbl_elf_host *h,struct plan *p) {
    clear(p,sizeof(*p)); p->minimum=UINT64_MAX;p->alignment=h->page_bytes;
    if(bytes<64 || f[0]!=0x7f || f[1]!='E'||f[2]!='L'||f[3]!='F'||f[4]!=2||f[5]!=1||f[6]!=1||f[7]!=0 ||
       u16(f+16)!=3 || u16(f+18)!=183 || u32(f+20)!=1 || u16(f+52)!=64 || u16(f+54)!=56)return KBL_ELF_FORMAT;
    uint64_t phoff=u64(f+32); unsigned phnum=u16(f+56); p->entry=u64(f+24);
    if(!phnum||!span(phoff,(uint64_t)phnum*56,bytes))return KBL_ELF_BOUNDS;
    p->file=f;p->phoff=phoff;p->phnum=phnum;p->page_bytes=h->page_bytes;
    for(unsigned i=0;i<phnum;++i) {
        const unsigned char *q=f+phoff+(uint64_t)i*56; unsigned type=u32(q),flags=u32(q+4);
        uint64_t off=u64(q+8),va=u64(q+16),files=u64(q+32),mem=u64(q+40),align=u64(q+48);
        if(type==3)return KBL_ELF_UNSUPPORTED; /* PT_INTERP */
        if(type==1) {
            if(files>mem || !span(off,files,bytes)||!span(va,mem,INT64_MAX))return KBL_ELF_BOUNDS;
            if(!mem) { if(files)return KBL_ELF_BOUNDS; continue; }
            if((flags&~7u)||!(flags&4)||((flags&3)==3))return KBL_ELF_PERMISSION;
            if(align>1 && (!power2(align)||((va^off)&(align-1))))return KBL_ELF_FORMAT;
            if(align>p->alignment)p->alignment=align;
            ++p->count;uint64_t low=va&~((uint64_t)h->page_bytes-1),high=0;
            if(!rounded(va+mem,h->page_bytes,&high))return KBL_ELF_BOUNDS;
            if(low<p->minimum)p->minimum=low; if(high>p->maximum)p->maximum=high;
        } else if(type==2) {
            if(++p->dynamics!=1 || !files || files>mem || (files%16) || !span(off,files,bytes))return KBL_ELF_FORMAT;
            p->dynamic_offset=off; p->dynamic_va=va; p->dynamic_bytes=files;
        } else if(type==4) {
            if(!span(off,files,bytes))return KBL_ELF_BOUNDS;
            int rc=notes(f,off,files,p); if(rc)return rc;
        } else if(type==UINT32_C(0x6474e551)) {
            if(flags&1)return KBL_ELF_PERMISSION; /* No executable stack. */
        } else if(type!=0 && type!=6 && type!=UINT32_C(0x6474e550) && type!=UINT32_C(0x6474e552))return KBL_ELF_UNSUPPORTED;
    }
    if(!p->count || p->abi_notes!=1)return KBL_ELF_ABI;
    size_t max=h->max_image_bytes?h->max_image_bytes:KBL_ELF_DEFAULT_MAX_BYTES;
    if(p->alignment>max)return KBL_ELF_BOUNDS;
    p->minimum&=~(p->alignment-1);
    if(p->maximum<=p->minimum || p->maximum-p->minimum>max || p->maximum-p->minimum>SIZE_MAX)return KBL_ELF_BOUNDS;
    unsigned left_index=0;struct segment left;
    while(next_segment(p,&left_index,&left)) {
        unsigned right_index=0;struct segment right;
        while(next_segment(p,&right_index,&right) && right_index<left_index)
            if(left.low<right.high && right.low<left.high)return KBL_ELF_PERMISSION;
    }
    if((p->entry&3)||!contains(p,p->entry,4,1,5))return KBL_ELF_PERMISSION;
    if(p->dynamics) {
        const unsigned char *d=file_at(f,p,p->dynamic_va,p->dynamic_bytes);
        if(!d || d!=f+p->dynamic_offset)return KBL_ELF_BOUNDS;
        unsigned seen=0,terminated=0;
        for(uint64_t i=0;i<p->dynamic_bytes;i+=16) {
            uint64_t tag=u64(d+i),value=u64(d+i+8); unsigned field=0;
            if(!tag){terminated=1;break;}
            if(tag==1 || tag==22 || tag==17 || tag==18 || tag==19 ||
               ((tag==2 || tag==23 || tag==12 || tag==13 || tag==25 || tag==26 || tag==27 || tag==28 ||
                 tag==32 || tag==33 || tag==35 || tag==36 || tag==37) && value))return KBL_ELF_UNSUPPORTED;
            if(tag==7){field=1;p->rela_va=value;}
            if(tag==8){field=2;p->rela_bytes=value;}
            if(tag==9){field=4;p->rela_entry=value;}
            if(field && (seen&field))return KBL_ELF_FORMAT; seen|=field;
        }
        if(!terminated)return KBL_ELF_FORMAT;
        if(seen && (seen!=7 || p->rela_entry!=24 || p->rela_bytes%24))return KBL_ELF_RELOCATION;
        if(p->rela_bytes && !file_at(f,p,p->rela_va,p->rela_bytes))return KBL_ELF_BOUNDS;
        const unsigned char *r=p->rela_bytes?file_at(f,p,p->rela_va,p->rela_bytes):0;
        for(uint64_t i=0;i<p->rela_bytes;i+=24) {
            uint64_t target=u64(r+i),kind=u64(r+i+8),addend=u64(r+i+16);
            if(kind!=1027 || (target&7) || !contains(p,target,8,0,6) ||
               addend>INT64_MAX || !contains(p,addend,1,0,4))return KBL_ELF_RELOCATION;
        }
    }
    return KBL_ELF_OK;
}
void kbl_elf_unload(struct kbl_elf_image *image) {
    if(!image)return;
    if(image->cookie==IMAGE_COOKIE && image->memory.base && image->host.release)
        image->host.release(image->host.opaque,&image->memory);
    clear(image,sizeof(*image));
}
int kbl_elf_load(const void *file,size_t bytes,const struct kbl_elf_host *h,struct kbl_elf_image *image) {
    struct plan p; int rc;
    if(!file||!h||!image||!h->allocate||!h->release||!h->protect||!h->icache ||
       !power2(h->page_bytes)||h->page_bytes<4096||h->page_bytes>65536)return KBL_ELF_STATE;
    if(bytes>UINTPTR_MAX-(uintptr_t)file)return KBL_ELF_BOUNDS;
    if(image->memory.base||image->cookie||image->entry)return KBL_ELF_STATE;
    rc=parse(file,bytes,h,&p); if(rc)return rc;
    struct kbl_elf_allocation memory={0}; size_t size=(size_t)(p.maximum-p.minimum);
    rc=h->allocate(h->opaque,size,(size_t)p.alignment,&memory);
    if(rc || !memory.base || memory.bytes<size || ((uintptr_t)memory.base&(p.alignment-1)) ||
       memory.bytes>UINTPTR_MAX-(uintptr_t)memory.base ||
       ((uintptr_t)memory.base<(uintptr_t)file+bytes && (uintptr_t)file<(uintptr_t)memory.base+memory.bytes)) {
        if(memory.base)h->release(h->opaque,&memory); return KBL_ELF_MEMORY;
    }
    unsigned char *base=memory.base; clear(base,size);
    unsigned index=0;struct segment s;
    while(next_segment(&p,&index,&s)) {
        for(uint64_t j=0;j<s.files;++j)base[s.va-p.minimum+j]=((const unsigned char *)file)[s.offset+j];
    }
    const unsigned char *r=p.rela_bytes?file_at(file,&p,p.rela_va,p.rela_bytes):0;
    for(uint64_t i=0;i<p.rela_bytes;i+=24)
        store64(base+(u64(r+i)-p.minimum),(uint64_t)(uintptr_t)(base+(u64(r+i+16)-p.minimum)));
    /* allocate() already guarantees private RW/NX pages. Deny only pages not
     * covered by any LOAD: requesting RP for the entire image first imposes
     * an unnecessary capability on fully covered images. Walk by address,
     * independently of program-header order; overlap was rejected by parse.
     * Actual holes retain the strict no-access requirement. */
    uint64_t cursor=p.minimum;
    while(cursor<p.maximum) {
        int found=0;struct segment next;index=0;
        while(next_segment(&p,&index,&s))
            if(s.low>=cursor && (!found||s.low<next.low)){next=s;found=1;}
        uint64_t end=found?next.low:p.maximum;
        if(end>cursor && h->protect(h->opaque,&memory,(size_t)(cursor-p.minimum),(size_t)(end-cursor),0)) {
            h->release(h->opaque,&memory); return KBL_ELF_PERMISSION;
        }
        if(!found)break;
        cursor=next.high;
    }
    index=0;
    while(next_segment(&p,&index,&s)) {
        unsigned flags=KBL_ELF_R | ((s.flags&2)?KBL_ELF_W:0) | ((s.flags&1)?KBL_ELF_X:0);
        if(h->protect(h->opaque,&memory,(size_t)(s.low-p.minimum),(size_t)(s.high-s.low),flags) ||
           ((flags&KBL_ELF_X)&&h->icache(h->opaque,&memory,(size_t)(s.low-p.minimum),(size_t)(s.high-s.low)))) {
            h->release(h->opaque,&memory); return KBL_ELF_PERMISSION;
        }
    }
    image->memory=memory; image->mapped_bytes=size; image->virtual_min=p.minimum;
    image->entry=base+(p.entry-p.minimum); image->required_api_bytes=p.api_bytes; image->host=*h; image->cookie=IMAGE_COOKIE;
    return KBL_ELF_OK;
}
int kbl_elf_call(struct kbl_elf_image *image,const struct kbl_program_api *api,void *opaque,int *program_rc) {
    if(!image||image->cookie!=IMAGE_COOKIE||!image->entry||!api||!program_rc||
       api->struct_bytes<KBL_PROGRAM_API_BASE_BYTES ||
       api->struct_bytes<image->required_api_bytes||!api->write||!api->yield||!api->current_el)return KBL_ELF_ABI;
    unsigned el=api->current_el(api->host); if(el<1||el>2)return KBL_ELF_EL_CHANGED;
#if !defined(__aarch64__)
    (void)opaque;
    return KBL_ELF_UNSUPPORTED; /* Host parser fixtures must not execute ARM64 bytes. */
#else
    *program_rc=((kbl_program_entry)image->entry)(api,opaque);
    if(api->current_el(api->host)!=el)return KBL_ELF_EL_CHANGED;
    return KBL_ELF_OK;
#endif
}
