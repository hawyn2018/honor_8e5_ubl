/* SPDX-License-Identifier: MIT
 * Real ADB internal allocation/ownership invariants; no device or transport. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../kbl_adb.c"
static unsigned allocations,fail_allocate,checks;
#define CHECK(x) do{++checks;if(!(x)){fprintf(stderr,"FAIL %u: %s\n",__LINE__,#x);exit(1);}}while(0)
static void *allocate(void *unused,size_t bytes,size_t alignment){(void)unused;CHECK(alignment==16);if(fail_allocate)return 0;void*p=malloc(bytes);if(p)++allocations;return p;}
static void release(void *unused,void *p,size_t bytes){(void)unused;(void)bytes;CHECK(p&&allocations);--allocations;free(p);}
static unsigned level(void*unused){(void)unused;return 1;}
static int submit(void*unused,const unsigned char*p,size_t n,uint64_t*j){(void)unused;(void)p;(void)n;*j=1;return 0;}
static int poll_job(void*unused,uint64_t j,unsigned budget,unsigned char*p,size_t cap,size_t*n,int*d,int*r){(void)unused;(void)j;(void)budget;(void)p;(void)cap;*n=0;*d=1;*r=0;return 0;}
int main(void){
    struct kbl_adb_config c={0};c.allocate=allocate;c.release=release;c.current_el=level;c.submit=submit;c.poll_job=poll_job;c.explicit_usb_debug=1;
    struct kbl_adb*s=0;CHECK(kbl_adb_create(&c,&s)==0);
    unsigned char payload[257];memset(payload,'q',sizeof(payload));CHECK(control(s,AUTH,2,0,payload,sizeof(payload))==0);
    const void *span=0;size_t bytes=0;CHECK(kbl_adb_output(s,&span,&bytes)==0&&bytes==24+sizeof(payload));unsigned char snapshot[281];memcpy(snapshot,span,bytes);
    for(unsigned i=0;i<33;++i)CHECK(control(s,CLSE,0,i+1,0,0)==0);
    CHECK(s->control_count==33&&!memcmp(span,snapshot,bytes));CHECK(kbl_adb_output_done(s,bytes,0)==0);
    for(unsigned i=0;i<33;++i){CHECK(kbl_adb_output(s,&span,&bytes)==0&&bytes==24);CHECK(get32((const unsigned char*)span+8)==i+1);CHECK(kbl_adb_output_done(s,bytes,0)==0);}
    char *buffer=0;size_t capacity=0;CHECK(grow(s,(void**)&buffer,&capacity,513,0,0));memset(buffer,'x',513);char*before=buffer;size_t before_capacity=capacity;
    fail_allocate=1;CHECK(!grow(s,(void**)&buffer,&capacity,capacity+1,513,0));CHECK(buffer==before&&capacity==before_capacity&&buffer[512]=='x');fail_allocate=0;release(0,buffer,capacity);
    for(size_t i=0;i<s->channel_capacity;++i)s->channels[i].used=1;
    fail_allocate=1;CHECK(!unused_channel(s)&&s->channels==s->inline_channels&&s->channel_capacity==INITIAL_CHANNELS);fail_allocate=0;
    CHECK(unused_channel(s)&&s->channels!=s->inline_channels&&s->channel_capacity>INITIAL_CHANNELS);
    CHECK(control(s,CLSE,0,1,0,0)==0);fail_allocate=1;CHECK(control(s,CLSE,0,2,0,0)==KBL_ERROR&&s->fault);fail_allocate=0;
    CHECK(kbl_adb_destroy(s)==0&&!allocations);
    printf("{\"passed\":true,\"assertions\":%u,\"cases\":4}\n",checks);return 0;
}
