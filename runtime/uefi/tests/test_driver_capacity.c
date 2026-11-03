/* Actual driver allocator with explicit host EFI/permission models. */
#define main legacy_driver_fixture_main
#include "test_driver.c"
#undef main
int main(void){
    fresh();
    void *pool=pool_allocate(0,64,131072);CHECK(pool&&!((uintptr_t)pool&131071));
    pool_release(0,pool,64);CHECK(!g.pools);
    struct kbl_elf_allocation memory={0};size_t large=(size_t)64*1024*1024+4096;
    CHECK(!elf_allocate(0,large,4096,&memory)&&memory.bytes==large);
    CHECK(g.pages&&g.pages->count>16384);elf_release(0,&memory);CHECK(!g.pages&&!g.pools);
    size_t alignment=(size_t)128*1024*1024;
    CHECK(!elf_allocate(0,4096,alignment,&memory)&&!((uintptr_t)memory.base&(alignment-1)));
    elf_release(0,&memory);CHECK(!g.pages&&!g.pools);
    for(unsigned partial=0;partial<2;++partial){
        fail_page_alloc=1;partial_page_alloc=partial;
        CHECK(elf_allocate(0,large,4096,&memory)==KBL_ERROR&&!memory.base&&!memory.bytes);
        CHECK(!g.pages&&!g.pools);
    }
    fail_page_alloc=partial_page_alloc=0;
    CHECK(elf_allocate(0,SIZE_MAX&~(size_t)4095,4096,&memory)==KBL_ERROR&&!memory.base);
    CHECK(!g.pages&&!g.pools);off();
    CHECK(allocs==frees&&page_allocs==page_frees);
    printf("{\"passed\":true,\"cases\":6,\"assertions\":%u,\"poolAlignment\":131072,\"mappedBytes\":%zu,\"alignment\":%zu,\"poolAllocations\":%u,\"poolFrees\":%u,\"pageAllocations\":%u,\"pageFrees\":%u,\"hardware\":false}\n",
        checks,large,alignment,allocs,frees,page_allocs,page_frees);
    return 0;
}
