/* Host-only synthetic ARM64 PE. No device addresses or firmware identities. */
#ifndef KBL_TEST_EFI_BL_FIXTURE_H
#define KBL_TEST_EFI_BL_FIXTURE_H
#include <stddef.h>
#include <stdint.h>
static void bl_fixture_word(unsigned char *b,size_t at,uint32_t w){for(unsigned i=0;i<4;++i)b[at+i]=(unsigned char)(w>>(8*i));}
static uint32_t bl_fixture_branch(uint32_t pc,uint32_t target,int link){return (link?0x94000000u:0x14000000u)|(((target-pc)>>2)&0x03ffffffu);}
static uint32_t bl_fixture_adrp(unsigned reg,uint32_t pc,uint32_t target){uint32_t n=((target>>12)-(pc>>12))&0x1fffffu;return 0x90000000u|((n&3)<<29)|((n>>2)<<5)|reg;}
static uint32_t bl_fixture_tb(uint32_t pc,uint32_t target,unsigned reg,unsigned bit,int nonzero){return (nonzero?0x37000000u:0x36000000u)|((bit&32)<<26)|((bit&31)<<19)|((((target-pc)>>2)&0x3fffu)<<5)|reg;}
static size_t bl_fixture_pe(unsigned char *b,size_t cap,uint32_t shift,unsigned reg,unsigned field){
    if(cap<0x1400||reg>30||field>4095)return 0;
    for(size_t i=0;i<0x1400;++i)b[i]=0;
    uint32_t c=0x1000+shift,d=0x5000+shift;
    b[0]='M';b[1]='Z';bl_fixture_word(b,0x3c,0x80);bl_fixture_word(b,0x80,0x4550);
    bl_fixture_word(b,0x84,0x0002aa64);bl_fixture_word(b,0x94,0x202200f0);bl_fixture_word(b,0x98,0x20b);
    bl_fixture_word(b,0xa8,c);bl_fixture_word(b,0xb8,0x1000);bl_fixture_word(b,0xbc,0x200);
    bl_fixture_word(b,0xd0,d+0x1000);bl_fixture_word(b,0xd4,0x400);bl_fixture_word(b,0xdc,10);bl_fixture_word(b,0x104,16);
    const char *names[2]={".text",".data"};
    for(unsigned j=0;j<2;++j){size_t at=0x188+j*40;for(unsigned i=0;names[j][i];++i)b[at+i]=(unsigned char)names[j][i];
        bl_fixture_word(b,at+8,0x800);bl_fixture_word(b,at+12,j?d:c);bl_fixture_word(b,at+16,0x800);
        bl_fixture_word(b,at+20,0x400+j*0x800);bl_fixture_word(b,at+36,j?0xc0000040u:0x60000020u);}
    bl_fixture_word(b,0x400,bl_fixture_branch(c,c+0x40,0));
    bl_fixture_word(b,0x440,0x39400000u|(field<<10)|(26u<<5)|reg);
    bl_fixture_word(b,0x444,bl_fixture_tb(c+0x44,c+0x80,reg,0,1));
    bl_fixture_word(b,0x448,bl_fixture_branch(c+0x48,c+0x300,1));
    bl_fixture_word(b,0x480,bl_fixture_tb(c+0x80,c+0xc0,19,63,1));
    bl_fixture_word(b,0x484,bl_fixture_adrp(1,c+0x84,d));bl_fixture_word(b,0x488,0x91000021u);
    bl_fixture_word(b,0x48c,0x52800800u);bl_fixture_word(b,0x490,bl_fixture_branch(c+0x90,c+0x300,1));
    bl_fixture_word(b,0x4c0,0xd65f03c0u);bl_fixture_word(b,0x700,0xd65f03c0u);
    const char *text="Launching fastboot";for(unsigned i=0;text[i];++i)b[0xc00+i]=(unsigned char)text[i];
    return 0x1400;
}
#endif
