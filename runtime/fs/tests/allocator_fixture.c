/* Host-only access to each production allocator; never opens a filesystem. */
#if defined(TEST_F2FS_ALLOCATOR)
#include "../f2fs.c"
static struct f2_volume test_volume;
void test_allocator_setup(const struct kbl_fs_host *host){test_volume=(struct f2_volume){0};test_volume.host=*host;activate(&test_volume);}
void *test_allocator_malloc(size_t n){return grub_malloc(n);}
void *test_allocator_calloc(size_t n,size_t z){if(z&&n>SIZE_MAX/z)return 0;return grub_zalloc(n*z);}
void test_allocator_free(void *p){grub_free(p);}
void test_allocator_disable(void){activate(0);}
#else
#include "../kbl_erofs.c"
static struct er_volume test_volume;
void test_allocator_setup(const struct kbl_fs_host *host){test_volume=(struct er_volume){0};test_volume.host=*host;er_current=&test_volume;}
void *test_allocator_malloc(size_t n){return kbl_erofs_malloc(n);}
void *test_allocator_calloc(size_t n,size_t z){return kbl_erofs_calloc(n,z);}
void test_allocator_free(void *p){kbl_erofs_free(p);}
void test_allocator_disable(void){er_current=0;}
size_t test_allocator_total(void){return test_volume.allocated;}
void test_allocator_set_total(size_t n){test_volume.allocated=n;}
#endif
