/* Real parser/loader with a host-memory provider, not UEFI/hardware execution. */
#if defined(_WIN32) && !defined(_CRT_SECURE_NO_WARNINGS)
#define _CRT_SECURE_NO_WARNINGS
#endif
#include "kbl_elf.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>

static unsigned checks, provider_errors;
#define CHECK(x) do { ++checks; if(!(x)){fprintf(stderr,"%s:%d: %s\n",__func__,__LINE__,#x);return 0;} } while(0)
#define FILE_BYTES 0x3000u
#define P0 64u
#define P1 120u
#define P2 176u
#define P3 232u
#define P4 288u
struct fixture {
    unsigned char file[FILE_BYTES]; void *raw;
    size_t requested_bytes;
    unsigned allocs, releases, protections, flushes;
    int alloc_fail, partial_failure, bad_alignment, small_capacity, protect_fail, flush_fail, deny_no_access;
    struct {size_t offset,bytes;unsigned flags;} changes[16];
    struct kbl_elf_host host;
};
static void put(unsigned char *p,unsigned width,uint64_t value){for(unsigned i=0;i<width;++i)p[i]=(unsigned char)(value>>(i*8));}
static uint64_t get(const unsigned char *p,unsigned width){uint64_t v=0;for(unsigned i=0;i<width;++i)v|=(uint64_t)p[i]<<(i*8);return v;}
static int allocation(void *p,size_t bytes,size_t alignment,struct kbl_elf_allocation *out){
    struct fixture *f=p; ++f->allocs;f->requested_bytes=bytes;
    if(f->alloc_fail&&!f->partial_failure)return -1;
    f->raw=malloc(bytes+alignment+16); if(!f->raw)return -1;
    uintptr_t base=((uintptr_t)f->raw+alignment-1)&~(uintptr_t)(alignment-1);
    out->base=(void *)(base+(f->bad_alignment?1:0));out->bytes=bytes-(f->small_capacity?1:0);
    memset(out->base,0xcc,bytes);
    return f->alloc_fail?-1:0;
}
static void release(void *p,struct kbl_elf_allocation *memory){
    struct fixture *f=p;++f->releases;if(!f->raw||!memory->base)++provider_errors;
    free(f->raw);f->raw=0; memory->base=0;memory->bytes=0;
}
static int protect(void *p,struct kbl_elf_allocation *memory,size_t offset,size_t bytes,unsigned flags){
    struct fixture *f=p;++f->protections;
    if(!memory->base||offset>memory->bytes||bytes>memory->bytes-offset||(offset&4095)||(bytes&4095)||
       (flags&~7u)||((flags&(KBL_ELF_W|KBL_ELF_X))==(KBL_ELF_W|KBL_ELF_X)))++provider_errors;
    if(f->protections>16)++provider_errors;
    else {f->changes[f->protections-1].offset=offset;f->changes[f->protections-1].bytes=bytes;f->changes[f->protections-1].flags=flags;}
    if(f->deny_no_access&&!flags)return -1;
    return f->protect_fail==(int)f->protections?-1:0;
}
static int flush(void *p,struct kbl_elf_allocation *memory,size_t offset,size_t bytes){
    struct fixture *f=p;++f->flushes;
    if(!memory->base||offset>memory->bytes||bytes>memory->bytes-offset)++provider_errors;
    return f->flush_fail?-1:0;
}
static void ph(unsigned char *p,unsigned type,unsigned flags,uint64_t off,uint64_t va,uint64_t files,uint64_t mem,uint64_t align){
    put(p,4,type);put(p+4,4,flags);put(p+8,8,off);put(p+16,8,va);put(p+24,8,va);
    put(p+32,8,files);put(p+40,8,mem);put(p+48,8,align);
}
static void init(struct fixture *f){
    memset(f,0,sizeof(*f));unsigned char *b=f->file;
    memcpy(b,"\177ELF",4);b[4]=2;b[5]=1;b[6]=1;
    put(b+16,2,3);put(b+18,2,183);put(b+20,4,1);put(b+24,8,0x1000);put(b+32,8,64);
    put(b+52,2,64);put(b+54,2,56);put(b+56,2,5);
    ph(b+P0,1,5,0x1000,0x1000,0x1000,0x1000,4096);
    ph(b+P1,1,6,0x2000,0x3000,0x100,0x1000,4096);
    ph(b+P2,4,4,0x400,0,32,32,4);
    ph(b+P3,2,6,0x2020,0x3020,64,64,8);
    ph(b+P4,0x6474e551,6,0,0,0,0,16);
    put(b+0x400,4,4);put(b+0x404,4,16);put(b+0x408,4,KBL_PROGRAM_NOTE_TYPE);memcpy(b+0x40c,"KBL",4);
    put(b+0x410,4,1);put(b+0x414,4,sizeof(struct kbl_program_api));
    put(b+0x1000,4,0xd65f03c0);memcpy(b+0x1080,"hello",6);
    put(b+0x2000,8,UINT64_C(0xdeadbeef));
    put(b+0x2020,8,7);put(b+0x2028,8,0x3060);
    put(b+0x2030,8,8);put(b+0x2038,8,24);put(b+0x2040,8,9);put(b+0x2048,8,24);
    put(b+0x2060,8,0x3000);put(b+0x2068,8,1027);put(b+0x2070,8,0x1080);
    f->host=(struct kbl_elf_host){f,4096,0,allocation,release,protect,flush};
}
static int positive_load_bss_relocation_and_release(void){
    struct fixture f;init(&f);struct kbl_elf_image image={0};unsigned char original[FILE_BYTES];memcpy(original,f.file,FILE_BYTES);
    CHECK(kbl_elf_load(f.file,FILE_BYTES,&f.host,&image)==0);
    CHECK(f.allocs==1&&f.releases==0&&f.protections==3&&f.flushes==1);
    CHECK(image.mapped_bytes==0x3000&&image.virtual_min==0x1000&&image.entry==image.memory.base);
    CHECK(image.required_api_bytes==sizeof(struct kbl_program_api));
    unsigned char *base=image.memory.base;CHECK(get(base+0x2000,8)==(uintptr_t)(base+0x80));
    CHECK(!memcmp((void *)(uintptr_t)get(base+0x2000,8),"hello",6));
    for(size_t i=0x2100;i<0x3000;++i)CHECK(base[i]==0);
    CHECK(!memcmp(original,f.file,FILE_BYTES));kbl_elf_unload(&image);CHECK(f.releases==1&&!image.memory.base&&!image.cookie);
    kbl_elf_unload(&image);CHECK(f.releases==1);CHECK(!provider_errors);return 1;
}
static int load_bias_is_not_a_fixed_address(void){
    struct fixture a,b;init(&a);init(&b);struct kbl_elf_image x={0},y={0};
    CHECK(!kbl_elf_load(a.file,FILE_BYTES,&a.host,&x));CHECK(!kbl_elf_load(b.file,FILE_BYTES,&b.host,&y));
    CHECK(x.memory.base!=y.memory.base);
    CHECK(get((unsigned char *)x.memory.base+0x2000,8)==(uintptr_t)x.memory.base+0x80);
    CHECK(get((unsigned char *)y.memory.base+0x2000,8)==(uintptr_t)y.memory.base+0x80);
    kbl_elf_unload(&x);kbl_elf_unload(&y);return 1;
}
static int no_access_is_required_only_for_real_holes(void){
    struct fixture f;init(&f);struct kbl_elf_image image={0};
    CHECK(!kbl_elf_load(f.file,FILE_BYTES,&f.host,&image));
    CHECK(f.protections==3&&f.changes[0].offset==0x1000&&f.changes[0].bytes==0x1000&&f.changes[0].flags==0);
    CHECK(f.changes[1].offset==0&&f.changes[1].flags==(KBL_ELF_R|KBL_ELF_X));
    CHECK(f.changes[2].offset==0x2000&&f.changes[2].flags==(KBL_ELF_R|KBL_ELF_W));
    kbl_elf_unload(&image);
    init(&f);f.deny_no_access=1;CHECK(kbl_elf_load(f.file,FILE_BYTES,&f.host,&image)==KBL_ELF_PERMISSION);
    CHECK(f.protections==1&&f.releases==1&&!f.raw&&!image.cookie);
    /* Expanding the first LOAD's BSS fills the gap without changing files,
     * relocations or entry. A provider lacking RP still supplies final RX/RW. */
    init(&f);put(f.file+P0+40,8,0x2000);f.deny_no_access=1;
    CHECK(!kbl_elf_load(f.file,FILE_BYTES,&f.host,&image));
    CHECK(f.protections==2&&f.changes[0].offset==0&&f.changes[0].bytes==0x2000&&f.changes[0].flags==5);
    CHECK(f.changes[1].offset==0x2000&&f.changes[1].bytes==0x1000&&f.changes[1].flags==3);
    kbl_elf_unload(&image);CHECK(f.releases==1);return 1;
}
static int hole_detection_is_independent_of_header_order(void){
    struct fixture f;init(&f);struct kbl_elf_image image={0};unsigned char saved[56];
    memcpy(saved,f.file+P0,56);memcpy(f.file+P0,f.file+P1,56);memcpy(f.file+P1,saved,56);
    put(f.file+P1+48,8,65536);
    CHECK(!kbl_elf_load(f.file,FILE_BYTES,&f.host,&image));
    CHECK(f.protections==4);
    CHECK(f.changes[0].offset==0&&f.changes[0].bytes==0x1000&&f.changes[0].flags==0);
    CHECK(f.changes[1].offset==0x2000&&f.changes[1].bytes==0x1000&&f.changes[1].flags==0);
    CHECK(f.changes[2].offset==0x3000&&f.changes[2].flags==3);
    CHECK(f.changes[3].offset==0x1000&&f.changes[3].flags==5);
    kbl_elf_unload(&image);CHECK(f.releases==1);return 1;
}
static int segment_alignment_controls_private_allocation(void){
    struct fixture f;init(&f);put(f.file+P0+48,8,65536);struct kbl_elf_image image={0};
    CHECK(!kbl_elf_load(f.file,FILE_BYTES,&f.host,&image));
    CHECK(!((uintptr_t)image.memory.base&65535)&&image.virtual_min==0&&image.mapped_bytes==0x4000);
    CHECK(image.entry==(unsigned char *)image.memory.base+0x1000);
    CHECK(get((unsigned char *)image.memory.base+0x3000,8)==(uintptr_t)image.memory.base+0x1080);
    kbl_elf_unload(&image);CHECK(f.releases==1);return 1;
}
static int invalid_files_never_allocate(void){
    const struct mutation{const char *name;unsigned offset,width;uint64_t value;} cases[]={
        {"class",4,1,1},{"endian",5,1,2},{"osabi",7,1,3},{"exec_not_dyn",16,2,2},{"machine",18,2,62},
        {"ehsize",52,2,63},{"phentsize",54,2,0},{"phnum_zero",56,2,0},{"phoff_overflow",32,8,UINT64_MAX},
        {"files_outside",P0+8,8,0x2fff},{"files_exceed_memory",P1+32,8,0x1001},
        {"va_overflow",P1+16,8,UINT64_MAX-4095},{"bad_alignment",P1+48,8,3},
        {"offset_va_mismatch",P1+8,8,0x2001},{"writable_executable",P0+4,4,7},{"nonreadable",P0+4,4,1},
        {"unknown_flags",P0+4,4,0x1005},{"entry_rw",24,8,0x3000},{"entry_hole",24,8,0x2000},
        {"entry_bss",24,8,0x3100},{"entry_alignment",24,8,0x1001},{"interpreter",P2,4,3},
        {"missing_note_owner",0x40c,1,'X'},{"note_api_bytes",0x414,4,8},
        {"note_desc_bytes",0x404,4,15},{"note_range",0x400,4,UINT32_MAX},{"note_flags",0x418,4,1},
        {"exec_stack",P4+4,4,7},{"dynamic_unmapped",P3+16,8,0x8000},
        {"dynamic_offset_mismatch",P3+8,8,0x2000},{"dynamic_unaligned_size",P3+32,8,63},
        {"needed",0x2020,8,1},{"textrel",0x2020,8,22},{"rel_not_rela",0x2020,8,17},
        {"rela_symbol",0x2068,8,UINT64_C(0x100000403)},{"rela_type",0x2068,8,257},
        {"rela_target_rx",0x2060,8,0x1000},{"rela_target_hole",0x2060,8,0x2000},
        {"rela_target_unaligned",0x2060,8,0x3001},{"rela_target_oob",0x2060,8,0x3ffc},
        {"rela_addend_outside",0x2070,8,0x5000},{"rela_addend_negative",0x2070,8,UINT64_MAX},
        {"rela_addend_hole",0x2070,8,0x2000},{"rela_entry_size",0x2048,8,16},
        {"rela_oversize",0x2038,8,24*65537u},{"rela_table_outside",0x2028,8,0x8000},
        {"non_power_of_two_large_alignment",P0+48,8,(UINT64_C(1)<<40)+1}
    };
    for(size_t i=0;i<sizeof(cases)/sizeof(cases[0]);++i){
        struct fixture f;init(&f);struct kbl_elf_image image={0};put(f.file+cases[i].offset,cases[i].width,cases[i].value);
        int rc=kbl_elf_load(f.file,FILE_BYTES,&f.host,&image);
        if(!rc)fprintf(stderr,"accepted mutation %s\n",cases[i].name);
        CHECK(rc!=0);CHECK(!f.allocs&&!f.releases&&!image.memory.base&&!image.cookie);
    }
    return 1;
}
static int page_overlap_and_duplicate_notes_are_rejected(void){
    struct fixture f;init(&f);struct kbl_elf_image image={0};
    put(f.file+P1+16,8,0x1800);put(f.file+P1+48,8,1);
    CHECK(kbl_elf_load(f.file,FILE_BYTES,&f.host,&image)!=0&&!f.allocs);
    init(&f);put(f.file+56,2,6);memcpy(f.file+344,f.file+P2,56);
    CHECK(kbl_elf_load(f.file,FILE_BYTES,&f.host,&image)==KBL_ELF_ABI&&!f.allocs);
    init(&f);put(f.file+0x2050,8,10);put(f.file+0x2058,8,1);
    CHECK(kbl_elf_load(f.file,FILE_BYTES,&f.host,&image)!=0&&!f.allocs);return 1;
}
static int resource_cap_and_invalid_provider_are_rejected(void){
    struct fixture f;init(&f);struct kbl_elf_image image={0};f.host.max_image_bytes=0x2000;
    CHECK(kbl_elf_load(f.file,FILE_BYTES,&f.host,&image)==KBL_ELF_BOUNDS&&!f.allocs);
    init(&f);f.host.page_bytes=3000;CHECK(kbl_elf_load(f.file,FILE_BYTES,&f.host,&image)==KBL_ELF_STATE);
    init(&f);f.host.protect=0;CHECK(kbl_elf_load(f.file,FILE_BYTES,&f.host,&image)==KBL_ELF_STATE&&!f.allocs);
    init(&f);CHECK(kbl_elf_load(f.file,63,&f.host,&image)!=0&&!f.allocs);return 1;
}
static int failed_allocations_and_permissions_release_once(void){
    for(unsigned mode=0;mode<8;++mode){
        struct fixture f;init(&f);struct kbl_elf_image image={0};
        if(mode==0)f.alloc_fail=1;
        if(mode==1){f.alloc_fail=1;f.partial_failure=1;}
        if(mode==2)f.bad_alignment=1;
        if(mode==3)f.small_capacity=1;
        if(mode==4)f.protect_fail=1;
        if(mode==5)f.protect_fail=2;
        if(mode==6)f.protect_fail=3;
        if(mode==7)f.flush_fail=1;
        CHECK(kbl_elf_load(f.file,FILE_BYTES,&f.host,&image)!=0);CHECK(f.allocs==1);
        CHECK(f.releases==(mode?1u:0u));CHECK(!f.raw&&!image.memory.base&&!image.cookie);
        kbl_elf_unload(&image);CHECK(f.releases==(mode?1u:0u));
    }
    CHECK(!provider_errors);return 1;
}
static unsigned zero_el(void *p){(void)p;return 0;}
static int fake_write(void *p,const void *data,size_t bytes){(void)p;(void)data;(void)bytes;return 0;}
static int fake_yield(void *p,unsigned budget){(void)p;(void)budget;return 0;}
static int invocation_checks_abi_before_executing(void){
    struct fixture f;init(&f);struct kbl_elf_image image={0};CHECK(!kbl_elf_load(f.file,FILE_BYTES,&f.host,&image));
    int rc=0;struct kbl_program_api api={0};
    CHECK(kbl_elf_call(&image,&api,0,&rc)==KBL_ELF_ABI);
    api=(struct kbl_program_api){.abi_version=1,.struct_bytes=sizeof(api),.write=fake_write,.yield=fake_yield,.current_el=zero_el};
    CHECK(kbl_elf_call(&image,&api,0,&rc)==KBL_ELF_EL_CHANGED);
    api.struct_bytes=KBL_PROGRAM_API_BASE_BYTES;CHECK(kbl_elf_call(&image,&api,0,&rc)==KBL_ELF_ABI);
    kbl_elf_unload(&image);CHECK(kbl_elf_call(&image,&api,0,&rc)==KBL_ELF_ABI);return 1;
}
static int base_api_note_remains_compatible(void){
    struct fixture f;init(&f);put(f.file+0x414,4,KBL_PROGRAM_API_BASE_BYTES);struct kbl_elf_image image={0};
    CHECK(!kbl_elf_load(f.file,FILE_BYTES,&f.host,&image));CHECK(image.required_api_bytes==KBL_PROGRAM_API_BASE_BYTES);
    struct kbl_program_api api={.abi_version=1,.struct_bytes=KBL_PROGRAM_API_BASE_BYTES,.write=fake_write,.yield=fake_yield,.current_el=zero_el};int rc=0;
    CHECK(kbl_elf_call(&image,&api,0,&rc)==KBL_ELF_EL_CHANGED);kbl_elf_unload(&image);return 1;
}
static int numeric_abi_revision_uses_available_prefix(void){
    const unsigned revisions[]={0,2,UINT32_MAX};
    for(unsigned i=0;i<sizeof(revisions)/sizeof(revisions[0]);++i){
        struct fixture f;init(&f);put(f.file+0x410,4,revisions[i]);struct kbl_elf_image image={0};
        CHECK(!kbl_elf_load(f.file,FILE_BYTES,&f.host,&image));
        struct kbl_program_api api={.abi_version=revisions[i],.struct_bytes=sizeof(api),.write=fake_write,.yield=fake_yield,.current_el=zero_el};int rc=0;
        CHECK(kbl_elf_call(&image,&api,0,&rc)==KBL_ELF_EL_CHANGED);
        api.write=0;CHECK(kbl_elf_call(&image,&api,0,&rc)==KBL_ELF_ABI);
        kbl_elf_unload(&image);CHECK(f.allocs==f.releases);
    }return 1;
}
static int live_image_cannot_be_overwritten(void){
    struct fixture f;init(&f);struct kbl_elf_image image={0};CHECK(!kbl_elf_load(f.file,FILE_BYTES,&f.host,&image));
    void *base=image.memory.base;CHECK(kbl_elf_load(f.file,FILE_BYTES,&f.host,&image)==KBL_ELF_STATE);
    CHECK(image.memory.base==base&&f.allocs==1&&!f.releases);kbl_elf_unload(&image);return 1;
}
static int actual_sdk_elf_loads(const char *path){
    FILE *file=fopen(path,"rb");CHECK(file);CHECK(!fseek(file,0,SEEK_END));long length=ftell(file);CHECK(length>0);rewind(file);
    unsigned char *bytes=malloc((size_t)length);CHECK(bytes);CHECK(fread(bytes,1,(size_t)length,file)==(size_t)length);fclose(file);
    struct fixture f;init(&f); /* This SDK profile has four LOADs and a different span. */
    struct kbl_elf_image image={0};f.protect_fail=0;f.deny_no_access=1;
    CHECK(!kbl_elf_load(bytes,(size_t)length,&f.host,&image));
    CHECK(f.allocs==1&&f.flushes==1&&image.required_api_bytes==KBL_PROGRAM_API_BASE_BYTES);
    CHECK((uintptr_t)image.entry>=(uintptr_t)image.memory.base);
    CHECK((uintptr_t)image.entry<(uintptr_t)image.memory.base+image.mapped_bytes);
    kbl_elf_unload(&image);free(bytes);CHECK(f.releases==1);CHECK(!provider_errors);return 1;
}
struct test{const char *name;int(*run)(void);};
#define TEST(n) {#n,n}
int main(int argc,char **argv){
    const struct test tests[]={TEST(positive_load_bss_relocation_and_release),TEST(load_bias_is_not_a_fixed_address),
        TEST(no_access_is_required_only_for_real_holes),TEST(hole_detection_is_independent_of_header_order),
        TEST(segment_alignment_controls_private_allocation),
        TEST(invalid_files_never_allocate),TEST(page_overlap_and_duplicate_notes_are_rejected),
        TEST(resource_cap_and_invalid_provider_are_rejected),TEST(failed_allocations_and_permissions_release_once),
        TEST(invocation_checks_abi_before_executing),TEST(base_api_note_remains_compatible),TEST(numeric_abi_revision_uses_available_prefix),TEST(live_image_cannot_be_overwritten)};
    unsigned failed=0;
    puts("FIXTURE: host memory-provider callbacks; no UEFI or ARM64 execution claim");
    for(size_t i=0;i<sizeof(tests)/sizeof(tests[0]);++i){int ok=tests[i].run();if(!ok)++failed;printf("%s %s\n",ok?"PASS":"FAIL",tests[i].name);}
    if(argc>1){int ok=actual_sdk_elf_loads(argv[1]);if(!ok)++failed;printf("%s actual_sdk_elf_loads\n",ok?"PASS":"FAIL");}
    printf("RESULT tests=%u failed=%u assertions=%u provider_errors=%u\n",(unsigned)(sizeof(tests)/sizeof(tests[0]))+(argc>1),failed,checks,provider_errors);
    return failed||provider_errors?1:0;
}
