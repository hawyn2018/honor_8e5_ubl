/* Production ELF loader capacity regression; host allocator/permissions only. */
#define main legacy_elf_fixture_main
#include "test_elf.c"
#undef main

static size_t largest_mapping,largest_alignment,largest_source;
static unsigned tested_loads,tested_phdrs,tested_relocations;
static size_t tested_dynamic;
static int capacity_protect(void *opaque,struct kbl_elf_allocation *m,size_t off,size_t n,unsigned flags){
    struct fixture *f=opaque;++f->protections;
    if(!m->base||off>m->bytes||n>m->bytes-off||(off&4095)||(n&4095)||(flags&~7u)||((flags&6u)==6u))++provider_errors;
    return f->protect_fail==(int)f->protections?-1:0;
}
static void unlimited(struct fixture *f){init(f);f->host.protect=capacity_protect;}
static int loads_and_header_counts(void){
    struct fixture f;unlimited(&f);struct kbl_elf_image image={0};
    unsigned char headers[5*56],note[32];memcpy(headers,f.file+64,sizeof(headers));memcpy(note,f.file+0x400,sizeof(note));
    memset(f.file+64,0,65*56);memcpy(f.file+64,headers,sizeof(headers));memcpy(f.file+0xf00,note,sizeof(note));
    put(f.file+P2+8,8,0xf00);put(f.file+56,2,65);
    for(unsigned i=0;i<15;++i)ph(f.file+64+(5+i)*56,1,6,0,0x5000+i*4096,0,4096,4096);
    CHECK(!kbl_elf_load(f.file,sizeof(f.file),&f.host,&image));
    CHECK(f.protections>=17&&image.mapped_bytes>17*4096);
    tested_loads=17;tested_phdrs=65;kbl_elf_unload(&image);CHECK(f.allocs==f.releases);return 1;
}
static int large_mapping_and_explicit_quota(void){
    struct fixture f;unlimited(&f);struct kbl_elf_image image={0};size_t old=(size_t)64*1024*1024;
    put(f.file+P1+40,8,old+4096);
    CHECK(!kbl_elf_load(f.file,sizeof(f.file),&f.host,&image));
    largest_mapping=image.mapped_bytes;CHECK(largest_mapping>old);
    CHECK(get((unsigned char*)image.memory.base+0x2000,8)==(uintptr_t)image.memory.base+0x80);
    kbl_elf_unload(&image);CHECK(f.allocs==f.releases);
    unlimited(&f);put(f.file+P1+40,8,old+4096);f.host.max_image_bytes=old;
    CHECK(kbl_elf_load(f.file,sizeof(f.file),&f.host,&image)==KBL_ELF_BOUNDS&&!f.allocs);
    return 1;
}
static int large_alignment(void){
    struct fixture f;unlimited(&f);struct kbl_elf_image image={0};size_t alignment=(size_t)128*1024*1024;
    put(f.file+P0+48,8,alignment);
    CHECK(!kbl_elf_load(f.file,sizeof(f.file),&f.host,&image));
    CHECK(!((uintptr_t)image.memory.base&(alignment-1))&&image.virtual_min==0);
    largest_alignment=alignment;kbl_elf_unload(&image);CHECK(f.allocs==f.releases);return 1;
}
static int large_source_file(void){
    struct fixture f;unlimited(&f);struct kbl_elf_image image={0};size_t bytes=(size_t)64*1024*1024+4096;
    unsigned char *source=calloc(1,bytes);CHECK(source);memcpy(source,f.file,sizeof(f.file));source[bytes-1]=0xee;
    CHECK(!kbl_elf_load(source,bytes,&f.host,&image));CHECK(source[bytes-1]==0xee);
    largest_source=bytes;kbl_elf_unload(&image);free(source);CHECK(f.allocs==f.releases);return 1;
}
static int dynamic_table_beyond_old_page(void){
    struct fixture f;unlimited(&f);struct kbl_elf_image image={0};size_t bytes=0x5000;
    unsigned char *source=calloc(1,bytes);CHECK(source);memcpy(source,f.file,sizeof(f.file));
    put(source+P1+32,8,0x3000);put(source+P1+40,8,0x3000);
    put(source+P3+32,8,4112);put(source+P3+40,8,4112);
    CHECK(!kbl_elf_load(source,bytes,&f.host,&image));tested_dynamic=4112;
    kbl_elf_unload(&image);free(source);CHECK(f.allocs==f.releases);return 1;
}
static int relocation_count_uses_actual_table(void){
    struct fixture f;unlimited(&f);struct kbl_elf_image image={0};size_t count=65537,relbytes=count*24;
    size_t bytes=(0x2060+relbytes+4095)&~(size_t)4095;
    unsigned char *source=calloc(1,bytes);CHECK(source);memcpy(source,f.file,sizeof(f.file));
    put(source+P1+32,8,bytes-0x2000);put(source+P1+40,8,bytes-0x2000);
    put(source+0x2038,8,relbytes);
    for(size_t i=0;i<count;++i){put(source+0x2060+i*24,8,0x3000);put(source+0x2068+i*24,8,1027);put(source+0x2070+i*24,8,0x1080);}
    CHECK(!kbl_elf_load(source,bytes,&f.host,&image));tested_relocations=(unsigned)count;
    CHECK(get((unsigned char*)image.memory.base+0x2000,8)==(uintptr_t)image.memory.base+0x80);
    kbl_elf_unload(&image);free(source);CHECK(f.allocs==f.releases);return 1;
}
static int large_mapping_failures_release(void){
    for(unsigned mode=0;mode<3;++mode){struct fixture f;unlimited(&f);struct kbl_elf_image image={0};
        put(f.file+P1+40,8,(size_t)64*1024*1024+4096);
        if(mode<2){f.alloc_fail=1;f.partial_failure=(int)mode;}else f.protect_fail=2;
        CHECK(kbl_elf_load(f.file,sizeof(f.file),&f.host,&image)!=0);
        CHECK(f.allocs==1&&f.releases==(mode?1u:0u)&&!f.raw&&!image.cookie);
    }return 1;
}
int main(void){
    struct test tests[]={TEST(loads_and_header_counts),TEST(large_mapping_and_explicit_quota),TEST(large_alignment),
        TEST(large_source_file),TEST(dynamic_table_beyond_old_page),TEST(relocation_count_uses_actual_table),TEST(large_mapping_failures_release)};
    unsigned failed=0;for(size_t i=0;i<sizeof(tests)/sizeof(tests[0]);++i)if(!tests[i].run())++failed;
    printf("{\"passed\":%s,\"cases\":%zu,\"assertions\":%u,\"providerErrors\":%u,\"mappedBytes\":%zu,\"alignment\":%zu,\"sourceBytes\":%zu,\"loadSegments\":%u,\"programHeaders\":%u,\"relocations\":%u,\"dynamicBytes\":%zu,\"hardware\":false}\n",
        (!failed&&!provider_errors)?"true":"false",sizeof(tests)/sizeof(tests[0]),checks,provider_errors,
        largest_mapping,largest_alignment,largest_source,tested_loads,tested_phdrs,tested_relocations,tested_dynamic);
    return failed||provider_errors?1:0;
}
