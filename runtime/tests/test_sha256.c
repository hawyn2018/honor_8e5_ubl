/* SPDX-License-Identifier: MIT */
#include "../kbl_sha256.h"
#include <stdio.h>
#include <string.h>
#define CHECK(x) do{if(!(x)){printf("SHA FAIL %u: %s\n",__LINE__,#x);return 1;}}while(0)
static int hash(const void*p,size_t n,const char*expected){
    struct kbl_sha256 s;unsigned char digest[32];char hex[65];kbl_sha256_init(&s);
    CHECK(kbl_sha256_update(&s,p,n)==KBL_OK);CHECK(kbl_sha256_final(&s,digest)==KBL_OK);
    kbl_sha256_hex(digest,hex);CHECK(strcmp(hex,expected)==0);
    CHECK(kbl_sha256_final(&s,digest)==KBL_ERROR&&kbl_sha256_update(&s,"a",1)==KBL_ERROR);return 0;
}
struct partial {const unsigned char*p;size_t size,chunk;unsigned calls,mode;};
static int read_at(void*opaque,uint64_t at,void*out,size_t n,size_t*read){
    struct partial*p=opaque;++p->calls;*read=0;
    if(p->mode==1)return KBL_ERROR;
    if(p->mode==2)return KBL_OK;
    if(p->mode==3){*read=n+1;return KBL_OK;}
    if(at>p->size||n>p->size-at)return KBL_ERROR;
    if(n>p->chunk)n=p->chunk;memcpy(out,p->p+at,n);*read=n;return KBL_OK;
}
int main(void){
    int tests=0;const char*abc="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    CHECK(!hash(0,0,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));++tests;
    CHECK(!hash("abc",3,abc));++tests;
    const char*multi="abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
    CHECK(!hash(multi,strlen(multi),"248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"));++tests;
    const char*longer="abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu";
    CHECK(!hash(longer,strlen(longer),"cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1"));++tests;
    struct kbl_sha256 s;kbl_sha256_init(&s);unsigned char digest[32];char hex[65];unsigned char thousand[1000];memset(thousand,'a',1000);
    for(unsigned i=0;i<1000;++i)CHECK(kbl_sha256_update(&s,thousand,1000)==KBL_OK);
    CHECK(kbl_sha256_final(&s,digest)==KBL_OK);kbl_sha256_hex(digest,hex);
    CHECK(strcmp(hex,"cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0")==0);++tests;
    unsigned char scratch[7];struct partial p={(const unsigned char*)multi,strlen(multi),3,0,0};
    struct kbl_reader r={&p,p.size,read_at,0};
    CHECK(kbl_sha256_reader(&r,scratch,sizeof(scratch),digest)==KBL_OK);kbl_sha256_hex(digest,hex);
    CHECK(p.calls>10&&strcmp(hex,"248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")==0);++tests;
    for(unsigned mode=1;mode<=3;++mode){p.mode=mode;
        memset(digest,0xaa,32);CHECK(kbl_sha256_reader(&r,scratch,sizeof(scratch),digest)==KBL_ERROR);
        for(unsigned i=0;i<32;++i)CHECK(!digest[i]);++tests;
    }
    p.mode=0;r.size=0;p.calls=0;CHECK(kbl_sha256_reader(&r,0,0,digest)==KBL_OK&&!p.calls);
    kbl_sha256_hex(digest,hex);CHECK(!strcmp(hex,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));++tests;
    r.size=3;CHECK(kbl_sha256_reader(&r,0,0,digest)==KBL_ERROR);++tests;
    r.size=UINT64_MAX/8+1;CHECK(kbl_sha256_reader(&r,scratch,sizeof(scratch),digest)==KBL_ERROR);++tests;
    kbl_sha256_init(&s);s.bytes=UINT64_MAX/8;CHECK(kbl_sha256_update(&s,"x",1)==KBL_ERROR);++tests;
    kbl_sha256_init(&s);CHECK(kbl_sha256_update(&s,0,1)==KBL_ERROR&&kbl_sha256_update(&s,0,0)==KBL_OK);++tests;
    printf("SHA-256 fixture: %d cases passed; published KAT and partial-reader checks.\n",tests);return 0;
}
