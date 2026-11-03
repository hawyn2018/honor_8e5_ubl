/* Offline real-lwext4 capacity fixtures. No physical block device access. */
#define main ext4_original_fixture_main
#include "test_ext4_backend.c"
#undef main
struct owner {size_t live;};
static void *owner_allocate(void *x,size_t n,size_t a){void*p=allocate(0,n,a);if(p)++((struct owner*)x)->live;return p;}
static void owner_release(void*x,void*p,size_t n){struct owner*o=x;CHECK(o->live);--o->live;release(0,p,n);}
int main(void) {
  enum { COUNT = 6 };
  struct disk disks[COUNT] = {0}; struct kbl_fs mounts[COUNT] = {0};
  struct kbl_fs_device devices[COUNT];struct owner owners[COUNT]={0};struct kbl_fs_host hosts[COUNT]; unsigned checks = 0;
  for (unsigned i=0;i<COUNT;++i) {
    disks[i].n=16u*1024u*1024u; disks[i].p=calloc(1,disks[i].n);CHECK(disks[i].p);
    format(&disks[i],4,0);devices[i]=device(&disks[i]);
    hosts[i]=(struct kbl_fs_host){&owners[i],owner_allocate,owner_release};
    CHECK(kbl_ext4_mount(&hosts[i],&devices[i],"ext4",0,&mounts[i])==0); ++checks;
  }
  for (unsigned i=0;i<COUNT;++i) {
    void *file=0;size_t n=0;char value=(char)('a'+i),back=0;
    CHECK(mounts[i].ops->open(mounts[i].opaque,"/unique",KBL_FILE_READ|KBL_FILE_WRITE|KBL_FILE_CREATE,&file)==0);
    CHECK(mounts[i].ops->write_at(mounts[i].opaque,file,0,&value,1,&n)==0&&n==1);
    CHECK(mounts[i].ops->read_at(mounts[i].opaque,file,0,&back,1,&n)==0&&n==1&&back==value);
    CHECK(mounts[i].ops->close(mounts[i].opaque,file)==0);++checks;
  }
  char path[1024]={0},renamed[1024],child[256],back[16];size_t at=0,n=0;void *file=0;
  struct kbl_fs *v=&mounts[0];struct kbl_file_stat st;int end;
  for(unsigned i=0;i<6;++i){path[at++]='/';memset(path+at,'a'+i,100);at+=100;path[at]=0;CHECK(v->ops->mkdir(v->opaque,path)==0);++checks;}
  size_t parent_bytes=at;memcpy(path+at,"/entry",7);at+=6;
  CHECK(at>512&&v->ops->open(v->opaque,path,KBL_FILE_READ|KBL_FILE_WRITE|KBL_FILE_CREATE,&file)==0);
  CHECK(v->ops->write_at(v->opaque,file,0,"capacity",8,&n)==0&&n==8);
  CHECK(v->ops->close(v->opaque,file)==0);
  CHECK(v->ops->stat(v->opaque,path,&st)==0&&st.bytes==8);++checks;
  memcpy(renamed,path,at+1);memcpy(renamed+parent_bytes,"/renamed",9);
  CHECK(v->ops->rename(v->opaque,path,renamed)==0);
  path[parent_bytes]=0;CHECK(v->ops->list(v->opaque,path,0,child,sizeof(child),&st,&end)==0&&!end&&!strcmp(child,"renamed"));
  CHECK(v->ops->open(v->opaque,renamed,KBL_FILE_READ,&file)==0);
  CHECK(v->ops->read_at(v->opaque,file,0,back,sizeof(back),&n)==0&&n==8&&!memcmp(back,"capacity",8));
  CHECK(v->ops->close(v->opaque,file)==0);CHECK(v->ops->unlink(v->opaque,renamed)==0);++checks;
  size_t before=live;
  for(unsigned fail=0;fail<3;++fail){allocation_fail_after=fail;int r=v->ops->stat(v->opaque,path,&st);allocation_fail_after=-1;CHECK((r==0||r==-KBL_ENOMEM)&&live<=before);before=live;++checks;}
  for(unsigned i=0;i<COUNT;++i){CHECK(mounts[i].ops->unmount(mounts[i].opaque)==0);CHECK(!owners[i].live);++checks;}
  CHECK(!live);
  unsigned oom_cases=0,oom_failures=0,oom_successes=0;
  for(unsigned fail=0;fail<36;++fail){
    struct kbl_fs m={0};allocation_fail_after=fail;
    int r=kbl_ext4_mount(&host,&devices[0],"ext4",KBL_FS_READ_ONLY,&m);allocation_fail_after=-1;
    if(!r){++oom_successes;CHECK(m.ops->unmount(m.opaque)==0);}else {++oom_failures;if(r!=-KBL_ENOMEM||m.opaque)fprintf(stderr,"mount oom fail=%u r=%d live=%zu\n",fail,r,live);CHECK(r==-KBL_ENOMEM&&!m.opaque);}
    CHECK(!live);++oom_cases;
  }
  for(unsigned i=0;i<COUNT;++i)free(disks[i].p);
  printf("{\"passed\":true,\"simultaneousMounts\":6,\"pathBytes\":%zu,\"functionalCases\":%u,\"oomCases\":%u,\"oomFailures\":%u,\"oomSuccesses\":%u,\"allocatorOwners\":6,\"liveAllocations\":%zu,\"deviceOperations\":false}\n",at,checks,oom_cases,oom_failures,oom_successes,live);
  return 0;
}
