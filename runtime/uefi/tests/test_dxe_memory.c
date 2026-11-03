/* SPDX-License-Identifier: MIT */
#include "../kbl_dxe_memory.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
static struct kbl_dxe_services ds;
static struct {struct kefi_guid guid;void*table;} tables[2];
static struct kefi_system_table st;
static uint64_t attrs[4],caps[4];static unsigned sets,descriptor_reads;static int fail_index,no_apply,short_descriptor,io_type;
static uint64_t *large_attrs;static unsigned large_sets;
static kefi_status large_descriptor(uint64_t p,struct kbl_gcd_descriptor*d){
    uint64_t n=UINT64_C(64)*1024*1024+4096;
    if(p<0x10000||p-0x10000>=n)return KEFI_INVALID;
    *d=(struct kbl_gcd_descriptor){p&~UINT64_C(4095),4096,8|KEFI_RP|KEFI_RO|KEFI_XP,large_attrs[(p-0x10000)/4096],2,0,0,0};return 0;
}
static kefi_status large_set(uint64_t p,uint64_t n,uint64_t a){
    assert(p>=0x10000&&n==4096);++large_sets;large_attrs[(p-0x10000)/4096]=a;return 0;
}
static kefi_status read_descriptor(uint64_t p,struct kbl_gcd_descriptor*d){
    ++descriptor_reads;if(p<0x10000||p>=0x14000)return KEFI_INVALID;
    unsigned i=(unsigned)((p-0x10000)/4096);*d=(struct kbl_gcd_descriptor){0x10000+i*4096,short_descriptor?2048:4096,caps[i],attrs[i],io_type?3:2,0,0,0};return 0;
}
static kefi_status write_attributes(uint64_t p,uint64_t n,uint64_t a){
    assert(n==4096);unsigned i=(unsigned)((p-0x10000)/4096);++sets;
    if((int)i==fail_index)return KEFI_ACCESS_DENIED;if(!no_apply)attrs[i]=a;return 0;
}
static void reset(void){
    memset(&ds,0,sizeof(ds));memset(&st,0,sizeof(st));memset(tables,0,sizeof(tables));
    ds.header.signature=UINT64_C(0x565245535f455844);ds.header.bytes=sizeof(ds);ds.get=read_descriptor;ds.set=write_attributes;
    tables[0].guid=(struct kefi_guid){0x05ad34ba,0x6f02,0x4214,{0x95,0x2e,0x4d,0xa0,0x39,0x8e,0x2b,0xb9}};tables[0].table=&ds;
    st.header.signature=KEFI_SYSTEM_SIGNATURE;st.header.bytes=sizeof(st);st.configuration_count=1;st.configuration_table=tables;
    for(unsigned i=0;i<4;++i){attrs[i]=8|KEFI_XP;caps[i]=8|KEFI_RP|KEFI_RO|KEFI_XP;}
    sets=descriptor_reads=0;fail_index=-1;no_apply=short_descriptor=io_type=0;
}
int main(void){unsigned cases=0;struct kbl_dxe_memory m;uint64_t result;
    reset();memset(&m,0,sizeof(m));assert(kbl_dxe_memory_init(&m,&st));assert(sets==0&&descriptor_reads==0);++cases;
    assert(!m.api.get(&m.api,0x10000,8192,&result)&&result==KEFI_XP);assert(!m.api.set(&m.api,0x10000,8192,KEFI_RO));
    assert(!m.api.clear(&m.api,0x10000,8192,KEFI_XP));assert(attrs[0]==(8|KEFI_RO)&&attrs[1]==(8|KEFI_RO));++cases;
    reset();memset(&m,0,sizeof(m));attrs[1]=1|KEFI_XP;caps[1]=1|KEFI_RP|KEFI_RO|KEFI_XP;assert(kbl_dxe_memory_init(&m,&st));
    assert(!m.api.set(&m.api,0x10000,8192,KEFI_RO));assert(attrs[0]==(8|KEFI_XP|KEFI_RO)&&attrs[1]==(1|KEFI_XP|KEFI_RO));++cases;
    reset();memset(&m,0,sizeof(m));caps[1]&=~KEFI_RO;assert(kbl_dxe_memory_init(&m,&st));
    assert(m.api.set(&m.api,0x10000,8192,KEFI_RO));assert(!sets&&attrs[0]==(8|KEFI_XP));++cases;
    reset();memset(&m,0,sizeof(m));assert(kbl_dxe_memory_init(&m,&st));
    assert(m.api.set(&m.api,0x10001,4096,KEFI_RO));assert(m.api.set(&m.api,0x10000,4095,KEFI_RO));
    assert(m.api.set(&m.api,UINT64_MAX-4095,8192,KEFI_RO));assert(m.api.clear(&m.api,0x10000,4096,8));assert(!sets);++cases;
    reset();memset(&m,0,sizeof(m));short_descriptor=1;assert(kbl_dxe_memory_init(&m,&st));assert(m.api.set(&m.api,0x10000,4096,KEFI_RO));assert(!sets);++cases;
    reset();memset(&m,0,sizeof(m));io_type=1;assert(kbl_dxe_memory_init(&m,&st));assert(m.api.set(&m.api,0x10000,4096,KEFI_RO));assert(!sets);++cases;
    reset();memset(&m,0,sizeof(m));fail_index=1;assert(kbl_dxe_memory_init(&m,&st));assert(m.api.set(&m.api,0x10000,8192,KEFI_RO));
    assert(attrs[0]==(8|KEFI_XP|KEFI_RO)&&attrs[1]==(8|KEFI_XP));++cases;
    reset();memset(&m,0,sizeof(m));no_apply=1;assert(kbl_dxe_memory_init(&m,&st));assert(m.api.set(&m.api,0x10000,4096,KEFI_RO));++cases;
    reset();memset(&m,0,sizeof(m));attrs[1]|=KEFI_RO;assert(kbl_dxe_memory_init(&m,&st));assert(m.api.get(&m.api,0x10000,8192,&result));++cases;
    reset();memset(&m,0,sizeof(m));tables[1]=tables[0];st.configuration_count=2;assert(!kbl_dxe_memory_init(&m,&st));assert(!m.services);++cases;
    reset();memset(&m,0,sizeof(m));ds.header.bytes=32;assert(!kbl_dxe_memory_init(&m,&st));ds.header.bytes=sizeof(ds);
    st.configuration_count=SIZE_MAX;assert(!kbl_dxe_memory_init(&m,&st));++cases;
    reset();memset(&m,0,sizeof(m));
    size_t count=4097;void *extended=calloc(count,sizeof(tables[0]));assert(extended);
    memcpy((unsigned char*)extended+(count-1)*sizeof(tables[0]),&tables[0],sizeof(tables[0]));
    st.configuration_table=extended;st.configuration_count=count;assert(kbl_dxe_memory_init(&m,&st));free(extended);++cases;
    reset();memset(&m,0,sizeof(m));ds.get=large_descriptor;ds.set=large_set;assert(kbl_dxe_memory_init(&m,&st));
    uint64_t large=UINT64_C(64)*1024*1024+4096;large_attrs=calloc(16385,sizeof(*large_attrs));assert(large_attrs);
    for(size_t i=0;i<16385;++i)large_attrs[i]=8|KEFI_XP;large_sets=0;
    assert(!m.api.get(&m.api,0x10000,large,&result)&&result==KEFI_XP);
    assert(!m.api.set(&m.api,0x10000,large,KEFI_RO)&&large_sets==16385);
    assert(!m.api.get(&m.api,0x10000,large,&result)&&result==(KEFI_RO|KEFI_XP));
    assert(m.api.set(&m.api,UINT64_MAX-4095,8192,KEFI_RO)==KEFI_INVALID);free(large_attrs);large_attrs=0;++cases;
    printf("{\"passed\":true,\"cases\":%u,\"scope\":\"Explicit PI DXE descriptor/provider model; no phone memory permissions proof\"}\n",cases);return 0;
}
