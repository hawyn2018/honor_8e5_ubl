/* Host-only exact memory ownership and wrong-EL provider guard fixtures. */
#define main original_vfs_fixture_main
#include "test_vfs.c"
#undef main
int main(void){
    struct native c;setup(&c);c.job_active=1;c.job_id=99;
    size_t base=c.owned_count;void*internal=alloc_owned(&c,32);CHECK(internal);
    void*p=sdk_memory_allocate(&c,256);CHECK(p&&c.owned_count==base+2);
    CHECK(sdk_memory_release(&c,internal,32)==-KBL_EINVAL);
    CHECK(sdk_memory_release(&c,(unsigned char*)p+1,255)==-KBL_EINVAL);
    CHECK(sdk_memory_release(&c,p,255)==-KBL_EINVAL);
    level=2;unsigned held=allocations;
    CHECK(!sdk_memory_allocate(&c,16)&&c.fault&&allocations==held);
    CHECK(sdk_memory_release(&c,p,256)==-KBL_EIO&&allocations==held);
    release_job_memory(&c,99);CHECK(c.fault&&allocations==held&&c.owned_count==base+2);
    level=1;c.fault=0;c.job_id=100;CHECK(sdk_memory_release(&c,p,256)==-KBL_EINVAL);
    release_job_memory(&c,100);CHECK(allocations==held);
    c.job_id=99;CHECK(!sdk_memory_release(&c,p,256));CHECK(sdk_memory_release(&c,p,256)==-KBL_EINVAL);
    p=sdk_memory_allocate(&c,17);CHECK(p);release_job_memory(&c,99);CHECK(c.owned_count==base+1);
    release_job_memory(&c,99);CHECK(c.owned_count==base+1);free_owned(&c,internal);
    CHECK(c.owned_count==base);finish(&c);
    printf("{\"passed\":true,\"assertions\":%u,\"wrongElPreservesOwnership\":true,\"foreignInternalAndWrongJobRejected\":true,\"deviceOperations\":false}\n",checks);return 0;
}
