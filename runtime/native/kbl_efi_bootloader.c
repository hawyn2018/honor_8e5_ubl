/* SPDX-License-Identifier: MIT
 * Explicit, private-RAM diagnostic transformation. No mutable globals, allocations,
 * disk access, firmware calls or startup registration. Addresses come from the
 * received PE and its direct control/data flow, never a firmware RVA table.
 */
#include "kbl_efi_bootloader.h"
#define BL_SCAN_WORDS 64u
struct section {uint32_t va,raw,size,span,flags;};
struct pe {const unsigned char *b;size_t bytes,table;uint32_t headers,image,entry;unsigned count;};
static uint16_t u16(const unsigned char*p){return (uint16_t)p[0]|(uint16_t)p[1]<<8;}
static uint32_t u32(const unsigned char*p){return (uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24;}
static void put32(unsigned char*p,uint32_t w){for(unsigned i=0;i<4;++i)p[i]=(unsigned char)(w>>(i*8));}
static int range(uint64_t a,uint64_t n,uint64_t cap){return a<=cap&&n<=cap-a;}
static int overlap(uint64_t a,uint64_t n,uint64_t b,uint64_t m){return n&&m&&a<b+m&&b<a+n;}
static int power2(uint32_t n){return n&&!(n&(n-1));}
/* The validated PE table owns section metadata. Read it on demand instead of
 * imposing a stack-array section count below the format's uint16_t width. */
static struct section section_at(const struct pe*p,unsigned i){
    const unsigned char*s=p->b+p->table+(size_t)i*40;
    struct section d={u32(s+12),u32(s+20),u32(s+16),u32(s+8),u32(s+36)};
    if(d.span<d.size)d.span=d.size;return d;
}
static int parse(const void*image,size_t bytes,struct pe*p){
    const unsigned char*b=image;size_t at,op,table;uint32_t alignment,file_alignment;unsigned count,optional;
    if(!b||bytes<64||b[0]!='M'||b[1]!='Z')return 0;
    at=u32(b+60);if(at<64||!range(at,24,bytes)||u32(b+at)!=0x4550||u16(b+at+4)!=0xaa64)return 0;
    count=u16(b+at+6);optional=u16(b+at+20);op=at+24;
    if(!count||optional<112||!range(op,optional,bytes)||u16(b+op)!=0x20b||u16(b+op+68)!=10)return 0;
    p->b=b;p->bytes=bytes;p->count=count;p->headers=u32(b+op+60);p->image=u32(b+op+56);p->entry=u32(b+op+16);
    alignment=u32(b+op+32);file_alignment=u32(b+op+36);table=op+optional;
    if(!p->image||!p->headers||p->headers>p->image||p->headers>bytes||
       !power2(alignment)||!power2(file_alignment)||alignment<file_alignment||file_alignment>65536||
       p->image%alignment||p->headers%file_alignment||!range(table,(uint64_t)count*40,p->headers))return 0;
    p->table=table;int entry=0;
    for(unsigned i=0;i<count;++i){struct section current=section_at(p,i);const struct section*d=&current;
        if(!range(d->va,d->span,p->image)||(d->span&&(d->va<p->headers||d->va%alignment))||
           (d->size&&(!range(d->raw,d->size,bytes)||d->raw<p->headers||d->raw%file_alignment||d->size%file_alignment)))return 0;
        if((d->flags&0x20000000u)&&p->entry>=d->va&&(uint64_t)p->entry-d->va<d->size)entry=1;
        for(unsigned j=0;j<i;++j){struct section previous=section_at(p,j);
            if(overlap(d->va,d->span,previous.va,previous.span)||overlap(d->raw,d->size,previous.raw,previous.size))return 0;}
    }
    return entry;
}
static int mapped(const struct pe*p,uint32_t rva,size_t n,size_t*out,int executable){
    if(!executable&&rva<p->headers&&range(rva,n,p->headers)){*out=rva;return 1;}
    for(unsigned i=0;i<p->count;++i){struct section current=section_at(p,i);const struct section*s=&current;
        if((!executable||(s->flags&0x20000000u))&&rva>=s->va&&range((uint64_t)rva-s->va,n,s->size)){
            size_t delta=(size_t)((uint64_t)rva-s->va);
            if(!range(s->raw,delta,p->bytes))return 0;
            *out=(size_t)s->raw+delta;return range(*out,n,p->bytes);}}
    return 0;
}
static int code(const struct pe*p,uint32_t pc,uint32_t*w){size_t at;if(pc&3||!mapped(p,pc,4,&at,1))return 0;*w=u32(p->b+at);return 1;}
static int64_t sign_extend(uint64_t n,unsigned bits){uint64_t sign=UINT64_C(1)<<(bits-1);return (int64_t)((n^sign)-sign);}
static int relative(uint32_t pc,int64_t delta,uint32_t*out){int64_t n=(int64_t)pc+delta;if(n<0||n>UINT32_MAX)return 0;*out=(uint32_t)n;return 1;}
static int direct(uint32_t pc,uint32_t w,uint32_t*target){
    if((w&0x7c000000u)==0x14000000u)return relative(pc,sign_extend(w&0x03ffffffu,26)*4,target);
    if((w&0xff000010u)==0x54000000u||(w&0x7e000000u)==0x34000000u)return relative(pc,sign_extend((w>>5)&0x7ffffu,19)*4,target);
    if((w&0x7e000000u)==0x36000000u)return relative(pc,sign_extend((w>>5)&0x3fffu,14)*4,target);
    return 0;
}
static int call(uint32_t w){return (w&0xfc000000u)==0x94000000u||(w&0xfffffc1fu)==0xd63f0000u;}
static int branch(uint32_t w){return (w&0x7c000000u)==0x14000000u||(w&0xff000010u)==0x54000000u||
    (w&0x7e000000u)==0x34000000u||(w&0x7e000000u)==0x36000000u||
    (w&0xfffffc1fu)==0xd61f0000u||(w&0xfffffc1fu)==0xd65f0000u||w==0xd65f0bffu||w==0xd65f0fffu;}
static uint32_t bit(unsigned r){return r<31?UINT32_C(1)<<r:0;}
/* Conservative GP-write model. Unknown instructions invalidate every value;
 * recognized stores/branches/flag-only instructions never invent a copy. */
static uint32_t writes(uint32_t w){
    unsigned rd=w&31,rn=(w>>5)&31;
    if(branch(w))return call(w)?0x4007ffffu:0;
    if(call(w))return 0x4007ffffu;
    if(w==0xd503233fu||w==0xd503237fu)return bit(30);
    if((w&0xfffff01fu)==0xd503201fu){
        switch(w){case 0xd503201fu:case 0xd503203fu:case 0xd503205fu:case 0xd503207fu:
            case 0xd503209fu:case 0xd50320bfu:case 0xd50320dfu:
            case 0xd503241fu:case 0xd503245fu:case 0xd503249fu:case 0xd50324dfu:return 0;
            default:return 0x7fffffffu;}
    }
    if((w&0x1fe00000u)==0x1a400000u)return 0;
    if((w&0x1f000000u)==0x10000000u||(w&0x1f000000u)==0x11000000u||
       (w&0x1f800000u)==0x12800000u||(w&0x1f800000u)==0x12000000u||
       (w&0x0e000000u)==0x0a000000u)return bit(rd);
    if((w&0x3b000000u)==0x39000000u||(w&0x3b000000u)==0x38000000u){
        unsigned op=(w>>22)&3,vector=(w>>26)&1,mode=(w>>10)&3;
        if((w&0x3b000000u)==0x38000000u&&(w&0x00200000u)){
            unsigned option=(w>>13)&7;
            if(mode!=2||(option!=2&&option!=3&&option!=6&&option!=7))return 0x7fffffffu;
        }
        uint32_t mask=(!vector&&op&&!(w>>30==3&&op==2))?bit(rd):0;
        if((w&0x3b000000u)==0x38000000u&&!(w&0x00200000u)&&(mode==1||mode==3))mask|=bit(rn);
        return mask;
    }
    if((w&0x3a000000u)==0x28000000u){uint32_t mask=(w&0x00400000u)&&!(w&0x04000000u)?bit(rd)|bit((w>>10)&31):0;
        unsigned mode=(w>>23)&3;if(mode==1||mode==3)mask|=bit(rn);return mask;}
    if((w&0x3b000000u)==0x18000000u)return !(w&0x04000000u)&&w>>30!=3?bit(rd):0;
    return 0x7fffffffu;
}
static int no_external_entry(const struct pe*p,uint32_t start,uint32_t end){
    if(p->entry>start&&p->entry<=end)return 0;
    for(unsigned i=0;i<p->count;++i){struct section current=section_at(p,i);const struct section*s=&current;if(!(s->flags&0x20000000u))continue;
        for(size_t off=0;off<=s->size&&s->size-off>=4;off+=4){uint32_t pc=(uint32_t)((uint64_t)s->va+off),target,w=u32(p->b+(size_t)s->raw+off);
            if(direct(pc,w,&target)&&target>start&&target<=end&&(pc<start||pc>end))return 0;}}
    return 1;
}
static int launch_text(const struct pe*p,uint64_t rva){
    static const char text[]="Launching fastboot";size_t at;if(rva>UINT32_MAX||!mapped(p,(uint32_t)rva,sizeof(text),&at,0))return 0;
    for(size_t i=0;i<sizeof(text)-1;++i)if(p->b[at+i]!=(unsigned char)text[i])return 0;
    unsigned next=p->b[at+sizeof(text)-1];
    return !((next>='a'&&next<='z')||(next>='A'&&next<='Z')||(next>='0'&&next<='9')||next=='_');
}
static int find_launch(const struct pe*p,uint32_t*out){
    unsigned count=0;uint32_t match=0;
    for(unsigned i=0;i<p->count;++i){struct section current=section_at(p,i);const struct section*s=&current;if(!(s->flags&0x20000000u))continue;
        uint64_t value[32]={0};uint32_t origin[32]={0},known=0;
        for(size_t off=0;off<=s->size&&s->size-off>=4;off+=4){uint32_t pc=(uint32_t)((uint64_t)s->va+off),w=u32(p->b+(size_t)s->raw+off),rd=w&31,rn=(w>>5)&31;
            if(call(w)){
                for(unsigned reg=0;reg<=1;++reg)if((known&bit(reg))&&launch_text(p,value[reg])&&pc>=origin[reg]&&
                    (uint64_t)pc-origin[reg]<=BL_SCAN_WORDS*4&&no_external_entry(p,origin[reg],pc)){
                    if(!count||match!=pc){match=pc;++count;}break;}
            }
            uint64_t v=0;uint32_t source=pc;int set=0;
            if((w&0x1f000000u)==0x10000000u){int64_t imm=sign_extend(((uint64_t)(w>>5)&0x7ffffu)<<2|((w>>29)&3),21);
                int64_t result=(w&0x80000000u)?(int64_t)(pc&~4095u)+imm*4096:(int64_t)pc+imm;
                if(result>=0){v=(uint64_t)result;set=1;}}
            else if((w&0x9f800000u)==0x91000000u&&rn<31&&(known&bit(rn))){
                uint64_t delta=(uint64_t)((w>>10)&4095)<<((w&0x00400000u)?12:0);source=origin[rn];
                if(w&0x40000000u){set=value[rn]>=delta;if(set)v=value[rn]-delta;}
                else {v=value[rn]+delta;set=v>=value[rn];}}
            else if((w&0xffe0ffe0u)==0xaa0003e0u){unsigned rm=(w>>16)&31;if(rm<31&&(known&bit(rm))){v=value[rm];source=origin[rm];set=1;}}
            known&=~writes(w);
            if(set&&rd<31){known|=bit(rd);value[rd]=v;origin[rd]=source;}
            if(branch(w)&&!call(w))known=0;
        }
    }
    if(count>1)return KBL_EFI_BL_AMBIGUOUS;if(!count)return KBL_EFI_BL_NO_TARGET;*out=match;return 0;
}
/* Healthy EFI_STATUS keeps bit 63 clear. Preserve that existing error gate;
 * no calls, unknown conditional paths or indirect jumps are traversed. */
static int reaches_launch(const struct pe*p,uint32_t pc,uint32_t launch){
    for(unsigned n=0;n<BL_SCAN_WORDS;++n){uint32_t w,target;if(pc==launch)return 1;if(!code(p,pc,&w))return 0;
        if(call(w))return 0;
        if((w&0xfc000000u)==0x14000000u){if(!direct(pc,w,&pc))return 0;continue;}
        if((w&0x7e000000u)==0x36000000u){unsigned tested=((w>>26)&32)|((w>>19)&31);
            if(tested!=63)return 0;if(!(w&0x01000000u)){if(!direct(pc,w,&target))return 0;pc=target;continue;}}
        else if(branch(w))return 0;
        if(pc>UINT32_MAX-4)return 0;pc+=4;
    }return 0;
}
static int definition(const struct pe*p,uint32_t branch_pc,unsigned reg,uint32_t*pc_out,uint32_t*word_out,unsigned*dest,unsigned*forced){
    uint32_t pc=branch_pc;
    for(unsigned n=0;n<BL_SCAN_WORDS&&pc>=4;++n){pc-=4;uint32_t w;if(!code(p,pc,&w)||branch(w)||call(w))return 0;
        if(!(writes(w)&bit(reg)))continue;
        if(((w&0xffc00000u)==0x39400000u||(w&0xffe00c00u)==0x38400000u)&&(w&31)<31){
            *pc_out=pc;*word_out=w;*dest=w&31;*forced=2;return no_external_entry(p,pc,branch_pc);}
        if((w&0xff800000u)==0x52800000u){unsigned imm=(w>>5)&0xffff,shift=(w>>21)&3;
            if(shift<=1&&imm<=1&&(!imm||!shift)){*pc_out=pc;*word_out=w;*dest=w&31;*forced=imm;return no_external_entry(p,pc,branch_pc);}return 0;}
        if((w&0x7fe0ffe0u)==0x2a0003e0u){reg=(w>>16)&31;if(reg==31)return 0;continue;}
        if((w&0x7ffffc00u)==0x11000000u){reg=(w>>5)&31;if(reg==31)return 0;continue;}
        return 0;
    }return 0;
}
int kbl_efi_force_bootloader(void*image,size_t bytes,struct kbl_efi_bootloader_result*result){
    struct pe p;uintptr_t a=(uintptr_t)image,r=(uintptr_t)result;
    if(!result||bytes>UINTPTR_MAX-a||sizeof(*result)>UINTPTR_MAX-r||overlap(a,bytes,r,sizeof(*result))||!parse(image,bytes,&p))return KBL_EFI_BL_BAD_PE;
    uint32_t launch;int status=find_launch(&p,&launch);if(status)return status;
    struct kbl_efi_bootloader_result selected={0};unsigned matches=0;
    for(unsigned i=0;i<p.count;++i){struct section current=section_at(&p,i);const struct section*s=&current;if(!(s->flags&0x20000000u))continue;
        for(size_t off=0;off<=s->size&&s->size-off>=4;off+=4){uint32_t pc=(uint32_t)((uint64_t)s->va+off),w=u32(p.b+(size_t)s->raw+off),target;
            if((w&0x7e000000u)!=0x36000000u||(((w>>26)&32)|((w>>19)&31))!=0||!direct(pc,w,&target))continue;
            if(pc>UINT32_MAX-4)continue;
            int taken=reaches_launch(&p,target,launch),fallthrough=reaches_launch(&p,pc+4,launch);
            if(taken==fallthrough)continue;
            uint32_t load,before;unsigned reg,forced;
            if(!definition(&p,pc,w&31,&load,&before,&reg,&forced))continue;
            unsigned value=(w&0x01000000u)?(unsigned)taken:(unsigned)!taken;
            size_t file;if(!mapped(&p,load,4,&file,1))continue;
            struct kbl_efi_bootloader_result candidate={file,load,before,0x52800000u|(value<<5)|reg,forced==value};
            if(candidate.already_forced)candidate.after=candidate.before;
            if(matches&&(selected.file_offset!=candidate.file_offset||selected.after!=candidate.after))return KBL_EFI_BL_AMBIGUOUS;
            selected=candidate;matches=1;
        }
    }
    if(!matches)return KBL_EFI_BL_NO_TARGET;
    /* The sole mutation is after complete PE/reference/control-flow validation. */
    put32((unsigned char*)image+selected.file_offset,selected.after);*result=selected;return 0;
}
const char*kbl_efi_bootloader_error(int status){
    switch(status){case 0:return "ok";case KBL_EFI_BL_BAD_PE:return "invalid ARM64 EFI PE or output buffer";
    case KBL_EFI_BL_NO_TARGET:return "unique Fastboot selection flow not found";
    case KBL_EFI_BL_AMBIGUOUS:return "multiple Fastboot selection targets";default:return "unknown bootloader preparation error";}
}
