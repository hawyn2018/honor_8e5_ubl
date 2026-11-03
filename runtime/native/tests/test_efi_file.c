/* Real CLI/VFS on the host with modeled allocation and EFI provider. */
#include "../kbl_native.c"
#include "efi_bootloader_fixture.h"
#include <assert.h>
#include <malloc.h>
#include <stdio.h>
#include <string.h>

static unsigned live,checks,calls;
static int provider_error;
static size_t fail_bytes;
static const size_t large_bytes=129u*1024u*1024u+17u;
static unsigned expect_forced;
#define CHECK(x) do{++checks;assert(x);}while(0)
static void*memory(void*o,size_t n,size_t a){(void)o;if(n==fail_bytes)return 0;void*p=_aligned_malloc(n,a);if(p)++live;return p;}
static void release(void*o,void*p,size_t n){(void)o;(void)n;if(p){CHECK(live);--live;_aligned_free(p);}}
static unsigned el(void*o){(void)o;return 1;}
static uint64_t now(void*o){(void)o;return 0;}
static uint32_t word(const unsigned char*b){return (uint32_t)b[0]|(uint32_t)b[1]<<8|(uint32_t)b[2]<<16|(uint32_t)b[3]<<24;}
static int provider(void*o,const void*p,size_t n,const uint16_t*options,size_t option_bytes,struct kbl_efi_run_result*r){
    (void)o;const unsigned char*b=p;++calls;
    CHECK(n==large_bytes&&b[0]=='M'&&b[1]=='Z'&&b[n-1]=='Z'&&b[n/2]==0);
    CHECK(word(b+0x440)==(expect_forced?0x52800028u:(0x39400000u|(0x33u<<10)|(26u<<5)|8u)));
    CHECK(option_bytes==8&&options[0]=='o'&&options[1]=='n'&&options[2]=='e'&&!options[3]);
    r->el_before=r->el_after=1;r->stage=KBL_EFI_RETURN;
    return provider_error;
}
static void setup(struct native*c){
    zero(c,sizeof(*c));c->cookie=NATIVE_COOKIE;c->el=1;c->job_id=1;c->in_job=c->polling=1;c->deadline=UINT64_MAX;
    c->p.allocate=memory;c->p.release=release;c->p.current_el=el;c->p.now_us=now;c->p.efi_run=provider;
    c->p.max_file_bytes=DEFAULT_FILE;c->p.max_ramfs_bytes=DEFAULT_RAMFS;c->p.max_nodes=256;
    c->root=new_node(c,0,"/",1);c->cwd=c->root;c->cwd_path=owned_string(c,"/");c->output_capacity=4096;c->output=alloc_owned(c,c->output_capacity);
    c->default_session.cookie=SESSION_COOKIE;c->default_session.c=c;c->default_session.cwd=c->root;c->default_session.cwd_path=c->cwd_path;c->default_session.output=c->output;c->sessions=&c->default_session;
    CHECK(c->root&&c->output&&DEFAULT_FILE==SIZE_MAX&&DEFAULT_RAMFS==SIZE_MAX);
}
static int run(struct native*c,const char*line){
    c->line=alloc_owned(c,length(line)+1);CHECK(c->line);copy(c->line,line,length(line)+1);CHECK(!parse(c));
    c->output_count=c->output_read=0;int rc=command(c);CHECK(!close_files(c));
    free_owned(c,c->scratch);c->scratch=0;zero(&c->scratch_node,sizeof(c->scratch_node));
    free_owned(c,c->line);c->line=0;free_owned(c,c->argv);c->argv=0;return rc;
}
static int contains(struct native*c,const char*text){size_t n=length(text);for(size_t at=0;at+n<=c->output_count;++at)if(!memcmp(c->output+at,text,n))return 1;return 0;}
int main(void){
    struct native c;setup(&c);kbl_file_handle h;size_t done;uint64_t pos;
    CHECK(!v_open(&c,"/large.img",KBL_FILE_CREATE|KBL_FILE_READ|KBL_FILE_WRITE,&h));
    unsigned char header[0x1400];CHECK(bl_fixture_pe(header,sizeof(header),0,8,0x33)==sizeof(header));
    CHECK(!v_write(&c,h,header,sizeof(header),&done)&&done==sizeof(header));
    CHECK(!v_seek(&c,h,(int64_t)large_bytes-1,KBL_SEEK_SET,&pos)&&pos==large_bytes-1);
    CHECK(!v_write(&c,h,"Z",1,&done)&&done==1);CHECK(!v_close(&c,h));
    struct node*f=resolve(&c,"/large.img",0,0);CHECK(f&&f->bytes==large_bytes&&c.ramfs_bytes==large_bytes);
    size_t owned=c.owned_bytes;unsigned allocations=c.owned_count;
    CHECK(!run(&c,"efi /large.img one")&&calls==1&&contains(&c,"status=0x0000000000000000"));
    CHECK(c.owned_bytes==owned&&c.owned_count==allocations);
    expect_forced=1;CHECK(!run(&c,"efi -bl /large.img one")&&calls==2&&contains(&c,"RAM override"));
    CHECK(!run(&c,"efi --bootloader /large.img one")&&calls==3);
    CHECK(!memcmp(f->data,header,sizeof(header))&&f->data[large_bytes-1]=='Z');
    CHECK(c.owned_bytes==owned&&c.owned_count==allocations);
    /* No allocation proceeds on size arithmetic overflow. */
    f->bytes=SIZE_MAX;CHECK(run(&c,"efi /large.img one")==1&&calls==3&&contains(&c,"allocation failed"));f->bytes=large_bytes;
    fail_bytes=large_bytes+sizeof(struct owned);
    CHECK(run(&c,"efi /large.img one")==1&&calls==3&&contains(&c,"allocation failed"));
    fail_bytes=8+sizeof(struct owned);
    CHECK(run(&c,"efi /large.img one")==1&&calls==3&&contains(&c,"allocation failed"));fail_bytes=0;
    CHECK(c.owned_bytes==owned&&c.owned_count==allocations);
    expect_forced=0;provider_error=1;CHECK(run(&c,"efi /large.img one")==1&&calls==4&&contains(&c,"provider errno"));provider_error=0;
    CHECK(c.owned_bytes==owned&&c.owned_count==allocations&&f->bytes==large_bytes);
    provider_error=INT_MIN;CHECK(run(&c,"efi /large.img one")==1&&calls==5&&contains(&c,"2147483648"));provider_error=0;
    CHECK(c.owned_bytes==owned&&c.owned_count==allocations);
    /* Explicit embedding quotas still work, with overflow-safe remaining space. */
    c.p.max_file_bytes=large_bytes;
    CHECK(!v_open(&c,"/large.img",KBL_FILE_WRITE,&h));CHECK(!v_seek(&c,h,0,KBL_SEEK_END,&pos));
    CHECK(v_write(&c,h,"X",1,&done)==-KBL_EFBIG&&!done);c.p.max_file_bytes=DEFAULT_FILE;
    c.p.max_ramfs_bytes=large_bytes;CHECK(v_write(&c,h,"X",1,&done)==-KBL_ENOSPC&&!done);
    c.p.max_ramfs_bytes=large_bytes-1;CHECK(v_write(&c,h,"X",1,&done)==-KBL_ENOSPC&&!done);
    c.p.max_ramfs_bytes=DEFAULT_RAMFS;CHECK(!v_close(&c,h));
    CHECK(!run(&c,"echo survived")&&contains(&c,"survived"));
    CHECK(!close_files(&c));while(c.owned)free_owned(&c,c.owned+1);CHECK(!live);
    printf("{\"passed\":true,\"assertions\":%u,\"largeFileBytes\":%zu,\"providerCalls\":%u,\"liveAllocations\":%u}\n",checks,large_bytes,calls,live);
    return 0;
}
