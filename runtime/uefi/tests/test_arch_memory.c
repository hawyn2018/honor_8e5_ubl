/* Explicit table/config/CPU provider models. No hardware-register claim. */
#include "../kbl_arch_memory.h"
#include "../kbl_dxe_memory.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#define TABLE UINT64_C(0x10000)
#define TARGET UINT64_C(0x50000000)
#define NX UINT64_C(0x60000000000000)
struct fixture {
    struct kbl_arch_config config;uint64_t tables[2048];struct kbl_arch_memory arch;
    unsigned calls,reads,mode,flip_read,read_fail,config_flip;
};
static unsigned checks,cases;
#define CHECK(x) do{++checks;assert(x);}while(0)
static int owns(void*p,uint64_t at,uint64_t n){(void)p;return at>=TARGET&&at-TARGET<16384&&n<=16384-(at-TARGET);}
static int configuration(void*p,struct kbl_arch_config*c){struct fixture*f=p;*c=f->config;if(f->config_flip)++f->config.tcr;return 1;}
static int read_table(void*p,uint64_t at,uint64_t*out){struct fixture*f=p;++f->reads;
    if(f->read_fail||at<TABLE||at-TABLE>=sizeof(f->tables)||(at&7))return 0;
    *out=f->tables[(at-TABLE)/8];if(f->flip_read&&f->reads%2==0)*out^=4;return 1;
}
static void init(struct fixture*f){memset(f,0,sizeof(*f));
    f->config=(struct kbl_arch_config){1,16|(UINT64_C(5)<<32),TABLE,UINT64_C(0xffbb4400),0,0,0,0,1};
    f->tables[0]=TABLE+4096+3;f->tables[512+1]=TABLE+8192+3;f->tables[1024+128]=TABLE+12288+3;
    for(unsigned i=0;i<4;++i)f->tables[1536+i]=TARGET+i*4096+0x70f+NX;
    f->arch=(struct kbl_arch_memory){f,configuration,read_table,owns,0,0,0,0,0};
}
static kefi_status set_cpu(void*p,uint64_t at,uint64_t n,uint64_t attrs){struct fixture*f=p;CHECK(owns(f,at,n)&&n==4096);++f->calls;
    unsigned i=1536+(unsigned)((at-TARGET)/4096);uint64_t v=f->tables[i];
    if(f->mode==1)return 0;
    v&=~(UINT64_C(0xc0)|NX);v|=attrs&KEFI_RO?0xc0:0;v|=attrs&KEFI_XP?NX:0;v|=0x400;
    if(f->mode==2)v+=4096;
    if(f->mode==3)v=(v&~UINT64_C(0x1c))|8;
    if(f->mode==4)v^=0x20;
    f->tables[i]=v;
    if(f->mode==5)++f->config.tcr;
    if(f->mode==6)f->read_fail=1;
    return f->mode>=2&&f->mode<=6?KEFI_ACCESS_DENIED:f->mode==7?KEFI_ACCESS_DENIED:KEFI_SUCCESS;
}
static void positive(void){struct fixture f;init(&f);uint64_t out;struct kbl_arch_page page;
    CHECK(kbl_arch_memory_get(&f.arch,TARGET,16384,&out)&&out==KEFI_XP);
    CHECK(!kbl_arch_memory_change(&f.arch,TARGET,4096,KEFI_RO,0,&f,set_cpu));
    CHECK(kbl_arch_memory_page(&f.arch,TARGET,&page)&&page.attributes==(KEFI_RO|KEFI_XP));
    CHECK(!kbl_arch_memory_change(&f.arch,TARGET,4096,KEFI_XP,1,&f,set_cpu));
    CHECK(kbl_arch_memory_page(&f.arch,TARGET,&page)&&page.attributes==KEFI_RO);
    unsigned calls=f.calls;CHECK(kbl_arch_memory_change(&f.arch,TARGET,4096,KEFI_RO,1,&f,set_cpu)==KEFI_ACCESS_DENIED&&calls==f.calls);
    CHECK(!kbl_arch_memory_change(&f.arch,TARGET,16384,KEFI_XP,0,&f,set_cpu));
    CHECK(!kbl_arch_memory_change(&f.arch,TARGET,16384,KEFI_RO|KEFI_RP,1,&f,set_cpu));
    CHECK(kbl_arch_memory_get(&f.arch,TARGET,16384,&out)&&out==KEFI_XP&&!f.arch.tainted);++cases;
}
static void profiles(void){
    for(unsigned mode=0;mode<19;++mode){struct fixture f;init(&f);struct kbl_arch_page page;
        switch(mode){case 0:f.config.el=2;break;case 1:f.config.sctlr=0;break;
        case 2:f.config.tcr|=UINT64_C(1)<<59;break;case 3:f.config.tcr|=UINT64_C(1)<<39;break;case 4:f.config.tcr|=UINT64_C(1)<<40;break;
        case 5:f.config.tcr|=UINT64_C(1)<<14;break;case 6:f.config.tcr|=UINT64_C(1)<<7;break;
        case 7:f.config.tcr|=UINT64_C(1)<<37;break;case 8:f.config.tcr=15|(UINT64_C(5)<<32);break;
        case 9:f.config.tcr=40|(UINT64_C(5)<<32);break;case 10:f.config.tcr=16|(UINT64_C(6)<<32);break;
        case 11:f.config.mmfr3=2;break;case 12:f.config.mmfr3=1;f.config.tcr2=2;break;
        case 13:f.config.sctlr|=UINT64_C(1)<<25;break;case 14:f.config.ttbr0|=8;break;
        case 15:f.config.mmfr1=UINT64_C(4)<<20;break;case 16:f.config.mmfr1=UINT64_C(1)<<20;f.config.pan=UINT64_C(1)<<22;break;
        case 17:f.flip_read=1;break;case 18:f.config_flip=1;break;}
        CHECK(!kbl_arch_memory_page(&f.arch,TARGET,&page)&&!f.calls);++cases;
    }
    struct fixture f;init(&f);struct kbl_arch_page page;f.config.ttbr0|=UINT64_C(0x1234)<<48;f.config.mmfr3=1;
    CHECK(kbl_arch_memory_page(&f.arch,TARGET,&page));++cases;
}
static void descriptors(void){
    for(unsigned mode=0;mode<8;++mode){struct fixture f;init(&f);struct kbl_arch_page page;
        switch(mode){case 0:f.tables[1536]=0;break;case 1:f.tables[0]=TARGET|1;break;
        case 2:f.tables[1536]=(f.tables[1536]&~UINT64_C(3))|1;break;
        case 3:f.tables[1536]+=4096;break;case 4:f.tables[1536]&=~UINT64_C(0x1c);break;
        case 5:f.tables[1536]|=UINT64_C(1)<<51;break;case 6:f.tables[1536]|=UINT64_C(1)<<52;break;
        case 7:f.tables[0]|=UINT64_C(1)<<48;break;}
        CHECK(!kbl_arch_memory_page(&f.arch,TARGET,&page));++cases;
    }
    struct fixture f;init(&f);struct kbl_arch_page page;f.tables[1536]&=~UINT64_C(0x400);
    CHECK(kbl_arch_memory_page(&f.arch,TARGET,&page)&&(page.attributes&KEFI_RP));
    CHECK(kbl_arch_memory_change(&f.arch,TARGET,4096,KEFI_RP,0,&f,set_cpu)==KEFI_UNSUPPORTED&&!f.calls);++cases;
    init(&f);f.tables[1024+128]=TARGET|0x70d|NX;
    CHECK(kbl_arch_memory_page(&f.arch,TARGET+4096,&page)&&page.physical==TARGET+4096);++cases;
    init(&f);f.tables[512+1]=UINT64_C(0x40000000)|0x70d|NX;
    CHECK(kbl_arch_memory_page(&f.arch,TARGET,&page)&&page.physical==TARGET);++cases;
}
static void restrictions(void){struct fixture f;init(&f);struct kbl_arch_page page;
    f.tables[0]|=(UINT64_C(1)<<62)|(UINT64_C(1)<<59);
    CHECK(kbl_arch_memory_page(&f.arch,TARGET,&page)&&page.forced_ro&&page.forced_xp);
    CHECK(kbl_arch_memory_change(&f.arch,TARGET,4096,KEFI_RO,1,&f,set_cpu)==KEFI_UNSUPPORTED&&!f.calls);
    f.config.tcr|=UINT64_C(1)<<41;CHECK(kbl_arch_memory_page(&f.arch,TARGET,&page)&&!page.forced_ro&&!page.forced_xp);++cases;
    init(&f);f.tables[1536]&=~NX;f.config.sctlr|=UINT64_C(1)<<19;
    CHECK(kbl_arch_memory_page(&f.arch,TARGET,&page)&&(page.attributes&KEFI_XP));++cases;
    init(&f);f.tables[1536]|=(UINT64_C(1)<<50)|0xc0;f.config.sctlr|=UINT64_C(1)<<36;
    CHECK(kbl_arch_memory_change(&f.arch,TARGET,4096,KEFI_XP,1,&f,set_cpu)==KEFI_UNSUPPORTED&&!f.calls);++cases;
}
static void cleanup_faults(void){
    for(unsigned mode=1;mode<=7;++mode){struct fixture f;init(&f);f.mode=mode;
        CHECK(kbl_arch_memory_change(&f.arch,TARGET,4096,KEFI_RO,0,&f,set_cpu)!=0);
        CHECK(f.arch.tainted==(mode>=2&&mode<=6));
        if(!f.arch.tainted){f.mode=0;CHECK(!kbl_arch_memory_change(&f.arch,TARGET,4096,KEFI_XP,0,&f,set_cpu));
            CHECK(!kbl_arch_memory_change(&f.arch,TARGET,4096,KEFI_RO|KEFI_RP,1,&f,set_cpu));}
        else {unsigned n=f.calls;CHECK(kbl_arch_memory_change(&f.arch,TARGET,4096,KEFI_XP,0,&f,set_cpu)==KEFI_ACCESS_DENIED&&f.calls==n);}
        ++cases;
    }
}
struct large_fixture {struct kbl_arch_config config;struct kbl_arch_memory arch;uint64_t *attrs;size_t pages;unsigned calls;};
static int large_owns(void*p,uint64_t at,uint64_t n){struct large_fixture*f=p;return at>=TARGET&&at-TARGET<=f->pages*4096&&n<=f->pages*4096-(at-TARGET);}
static int large_config(void*p,struct kbl_arch_config*c){*c=((struct large_fixture*)p)->config;return 1;}
static int large_read(void*p,uint64_t at,uint64_t*out){struct large_fixture*f=p;
    if(at==TABLE){*out=TABLE+4096+3;return 1;}
    if(at==TABLE+4096+8){*out=TABLE+8192+3;return 1;}
    if(at>=TABLE+8192+128*8&&at<TABLE+8192+161*8){size_t region=(size_t)(at-(TABLE+8192+128*8))/8;*out=TABLE+0x10000+region*4096+3;return 1;}
    if(at>=TABLE+0x10000&&at<TABLE+0x10000+33*4096){size_t off=(size_t)(at-(TABLE+0x10000));size_t page=(off/4096)*512+(off%4096)/8;
        if(page>=f->pages)return 0;*out=TARGET+page*4096+0x70f+(f->attrs[page]&KEFI_RO?0x80:0)+(f->attrs[page]&KEFI_XP?NX:0);return 1;}
    return 0;
}
static kefi_status large_cpu(void*p,uint64_t at,uint64_t n,uint64_t attrs){struct large_fixture*f=p;CHECK(large_owns(f,at,n)&&n==4096);++f->calls;f->attrs[(at-TARGET)/4096]=attrs&(KEFI_RO|KEFI_XP|KEFI_RP);return 0;}
static void large_range(void){struct large_fixture f={0};f.pages=16385;f.attrs=calloc(f.pages,sizeof(*f.attrs));CHECK(f.attrs);
    for(size_t i=0;i<f.pages;++i)f.attrs[i]=KEFI_XP;
    f.config=(struct kbl_arch_config){1,16|(UINT64_C(5)<<32),TABLE,UINT64_C(0xffbb4400),0,0,0,0,1};
    f.arch=(struct kbl_arch_memory){&f,large_config,large_read,large_owns,0,0,0,0,0};uint64_t bits=0,n=f.pages*4096;
    CHECK(kbl_arch_memory_get(&f.arch,TARGET,n,&bits)&&bits==KEFI_XP);
    CHECK(!kbl_arch_memory_change(&f.arch,TARGET,n,KEFI_RO,0,&f,large_cpu)&&f.calls==f.pages);
    CHECK(kbl_arch_memory_get(&f.arch,TARGET,n,&bits)&&bits==(KEFI_RO|KEFI_XP));
    unsigned calls=f.calls;CHECK(kbl_arch_memory_change(&f.arch,UINT64_MAX-4095,8192,KEFI_RO,0,&f,large_cpu)==KEFI_INVALID&&calls==f.calls);
    free(f.attrs);++cases;
}
int main(void){positive();profiles();descriptors();restrictions();cleanup_faults();large_range();
    printf("{\"passed\":true,\"cases\":%u,\"assertions\":%u,\"scope\":\"Explicit architecture register/page-table/CPU models; not physical firmware\"}\n",cases,checks);return 0;}
