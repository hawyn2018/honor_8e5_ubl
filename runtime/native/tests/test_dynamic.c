/* Cross the previous namespace, argument and transfer policy boundaries. */
#define main legacy_vfs_main
#include "test_vfs.c"
#undef main
static void namespace_limits(void){
    struct native c;setup(&c);c.p.max_nodes=0;
    char path[602];path[0]='/';for(size_t i=1;i<601;++i)path[i]='L';path[601]=0;
    kbl_file_handle h;size_t n;CHECK(!v_open(&c,path,KBL_FILE_CREATE|KBL_FILE_READ|KBL_FILE_WRITE,&h));
    CHECK(!v_write(&c,h,"ok",2,&n)&&n==2);CHECK(!v_close(&c,h));
    struct kbl_file_stat st;CHECK(!v_stat(&c,path,&st)&&st.bytes==2);CHECK(!v_unlink(&c,path));
    char nested[700];nested[0]=0;
    for(unsigned i=0;i<300;++i){nested[i*2]='/';nested[i*2+1]='d';nested[i*2+2]=0;CHECK(!v_mkdir(&c,nested));}
    CHECK(!v_stat(&c,nested,&st)&&st.type==KBL_FILE_DIRECTORY&&c.nodes>256);
    char command[720];snprintf(command,sizeof(command),"cd %s",nested);CHECK(!run(&c,command));
    CHECK(length(current_path(&c))==600);CHECK(!run(&c,"cd ..")&&length(current_path(&c))==598);
    CHECK(!run(&c,"cd /"));finish(&c);++groups;
}
static void dd_and_full_hex(void){
    struct native c;setup(&c);CHECK(!kbl_native_put(&c,"/small","123456789",9));
    CHECK(!run(&c,"dd if=/small of=/copy bs=67108865 count=1"));struct node*f=resolve(&c,"/copy",0,0);CHECK(f&&f->bytes==9&&!memcmp(f->data,"123456789",9));
    CHECK(!run(&c,"dd if=/small of=/copy bs=0000000000000000000000000000000000000000000000000000000000000000001 count=1"));
    f=resolve(&c,"/copy",0,0);CHECK(f&&f->bytes==1&&f->data[0]=='1');
    unsigned char bytes[300];memset(bytes,0,sizeof(bytes));CHECK(!kbl_native_put(&c,"/hex",bytes,sizeof(bytes)));
    CHECK(!run(&c,"hex /hex")&&c.output_count==900);finish(&c);++groups;
}
static size_t countdown=SIZE_MAX;
static void*failing_memory(void*o,size_t n,size_t a){if(countdown!=SIZE_MAX){if(!countdown)return 0;--countdown;}return memory(o,n,a);}
static void temporary_failure_cleanup(void){
    struct native c;setup(&c);c.p.allocate=failing_memory;size_t before=c.owned_bytes;unsigned count=c.owned_count,nodes=c.nodes;
    for(size_t fail=0;fail<9;++fail){countdown=fail;int rc=v_mkdir(&c,"/oom");countdown=SIZE_MAX;
        if(!rc)CHECK(!v_unlink(&c,"/oom"));else CHECK(!resolve(&c,"/oom",0,0));
        CHECK(c.owned_bytes==before&&c.owned_count==count&&c.nodes==nodes);
    }finish(&c);++groups;
}
static void snapshot_counter_boundary(void){
    struct native c;setup(&c);CHECK(!kbl_native_put(&c,"/snapshot","ok",2));c.in_job=c.polling=0;
    struct kbl_reader r={0};size_t bytes=c.owned_bytes;c.snapshots=SIZE_MAX;
    CHECK(kbl_native_open_snapshot(&c,"/snapshot",&r)==KBL_ERROR&&!r.opaque&&!r.close&&c.owned_bytes==bytes);
    c.snapshots=(size_t)UINT32_MAX;CHECK(!kbl_native_open_snapshot(&c,"/snapshot",&r)&&c.snapshots==(size_t)UINT32_MAX+1);r.close(r.opaque);CHECK(c.snapshots==UINT32_MAX);c.snapshots=0;
    c.in_job=c.polling=1;finish(&c);++groups;
}
static void resource_width_boundaries(void){
    struct native c;setup(&c);c.p.max_nodes=0;size_t nodes=c.nodes;c.nodes=UINT32_MAX;
    CHECK(!v_mkdir(&c,"/wide")&&c.nodes==(size_t)UINT32_MAX+1);struct kbl_native_stats st;CHECK(!kbl_native_get_stats(&c,&st)&&st.nodes==UINT_MAX);
    CHECK(!v_unlink(&c,"/wide"));c.nodes=nodes;
    c.file_seed=(uint64_t)UINT32_MAX-1;kbl_file_handle a,b;kbl_file_handle empty=0;
    CHECK(!v_open(&c,"/ids",KBL_FILE_CREATE|KBL_FILE_READ|KBL_FILE_WRITE,&a)&&a==UINT32_MAX);CHECK(!v_close(&c,a));
    CHECK(!v_open(&c,"/ids",KBL_FILE_READ,&b)&&b==(uint64_t)UINT32_MAX+1);size_t n;char out;
    CHECK(v_read(&c,a,&out,1,&n)==-KBL_EBADF);CHECK(!v_close(&c,b));
    c.file_seed=UINT64_MAX-1;CHECK(!v_open(&c,"/ids",KBL_FILE_READ,&b)&&b==UINT64_MAX);CHECK(!v_close(&c,b));
    CHECK(v_open(&c,"/ids",KBL_FILE_READ,&empty)==-KBL_EOVERFLOW&&!empty);
    finish(&c);++groups;
}
int main(void){
    CHECK(!legacy_vfs_main());namespace_limits();dd_and_full_hex();temporary_failure_cleanup();snapshot_counter_boundary();resource_width_boundaries();
    printf("{\"passed\":true,\"groups\":%u,\"assertions\":%u,\"longPathBytes\":601,\"pathDepth\":300,\"nodeCountAbove\":256,\"ddBlockBytes\":67108865,\"hexFileBytes\":300}\n",groups,checks);
    return 0;
}
