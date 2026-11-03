/* SPDX-License-Identifier: MIT */
#include "kbl_sha256.h"

static uint32_t ror(uint32_t x,unsigned n){return(x>>n)|(x<<(32-n));}
static void transform(struct kbl_sha256*s,const unsigned char*p){
    static const uint32_t k[64]={
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
    uint32_t w[64];
    for(unsigned i=0;i<16;++i)w[i]=(uint32_t)p[4*i]<<24|(uint32_t)p[4*i+1]<<16|(uint32_t)p[4*i+2]<<8|p[4*i+3];
    for(unsigned i=16;i<64;++i)w[i]=w[i-16]+(ror(w[i-15],7)^ror(w[i-15],18)^(w[i-15]>>3))+w[i-7]+(ror(w[i-2],17)^ror(w[i-2],19)^(w[i-2]>>10));
    uint32_t a=s->state[0],b=s->state[1],c=s->state[2],d=s->state[3],e=s->state[4],f=s->state[5],g=s->state[6],h=s->state[7];
    for(unsigned i=0;i<64;++i){
        uint32_t t=h+(ror(e,6)^ror(e,11)^ror(e,25))+((e&f)^(~e&g))+k[i]+w[i];
        uint32_t u=(ror(a,2)^ror(a,13)^ror(a,22))+((a&b)^(a&c)^(b&c));
        h=g;g=f;f=e;e=d+t;d=c;c=b;b=a;a=t+u;
    }
    s->state[0]+=a;s->state[1]+=b;s->state[2]+=c;s->state[3]+=d;
    s->state[4]+=e;s->state[5]+=f;s->state[6]+=g;s->state[7]+=h;
}
void kbl_sha256_init(struct kbl_sha256*s){
    if(!s)return;
    *s=(struct kbl_sha256){{0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19},0,{0},0,0};
}
int kbl_sha256_update(struct kbl_sha256*s,const void*data,size_t n){
    if(!s||s->finalized||s->used>=64||(!data&&n)||s->bytes>UINT64_MAX/8||n>UINT64_MAX/8-s->bytes)return KBL_ERROR;
    const unsigned char*p=data;s->bytes+=n;
    while(n--){s->block[s->used++]=*p++;if(s->used==64){transform(s,s->block);s->used=0;}}
    return KBL_OK;
}
int kbl_sha256_final(struct kbl_sha256*s,unsigned char digest[32]){
    if(!s||!digest||s->finalized||s->used>=64||s->bytes>UINT64_MAX/8)return KBL_ERROR;
    uint64_t bits=s->bytes*8;
    s->block[s->used++]=0x80;
    if(s->used>56){while(s->used<64)s->block[s->used++]=0;transform(s,s->block);s->used=0;}
    while(s->used<56)s->block[s->used++]=0;
    for(unsigned i=0;i<8;++i)s->block[56+i]=(unsigned char)(bits>>(56-8*i));
    transform(s,s->block);s->finalized=1;s->used=0;
    for(unsigned i=0;i<32;++i)digest[i]=(unsigned char)(s->state[i/4]>>(24-8*(i%4)));
    return KBL_OK;
}
void kbl_sha256_hex(const unsigned char digest[32],char out[65]){
    static const char h[]="0123456789abcdef";
    for(unsigned i=0;i<32;++i){out[2*i]=h[digest[i]>>4];out[2*i+1]=h[digest[i]&15];}out[64]=0;
}
int kbl_sha256_reader(struct kbl_reader*r,void*scratch,size_t capacity,unsigned char digest[32]){
    if(!digest)return KBL_ERROR;
    for(unsigned i=0;i<32;++i)digest[i]=0;
    if(!r||!r->read_at||r->size>UINT64_MAX/8||(r->size&&(!scratch||!capacity)))return KBL_ERROR;
    struct kbl_sha256 s;kbl_sha256_init(&s);uint64_t at=0,size=r->size;
    while(at<size){
        size_t n=size-at>capacity?capacity:(size_t)(size-at),got=0;
        if(r->size!=size||r->read_at(r->opaque,at,scratch,n,&got)!=KBL_OK||!got||got>n||r->size!=size||kbl_sha256_update(&s,scratch,got)!=KBL_OK)return KBL_ERROR;
        at+=got;
    }
    return kbl_sha256_final(&s,digest);
}
