#include "../kbl_efi_bootloader.h"
#include "efi_bootloader_fixture.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x) do{if(!(x)){fprintf(stderr,"line %d: %s\n",__LINE__,#x);return 1;}}while(0)
static unsigned cases;
static int changed_only_word(const unsigned char*a,const unsigned char*b,size_t bytes,size_t at){
    for(size_t i=0;i<bytes;++i)if(a[i]!=b[i]&&(i<at||i>=at+4))return 0;return 1;
}
int main(int argc,char**argv){
    unsigned char b[0x1400],saved[0x1400];struct kbl_efi_bootloader_result r;
    for(unsigned reg=0;reg<31;reg+=3)for(unsigned shift=0;shift<=0x10000;shift+=0x10000){
        CHECK(bl_fixture_pe(b,sizeof b,shift,reg,17+reg));memcpy(saved,b,sizeof b);
        CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==0);CHECK(r.file_offset==0x440&&r.load_rva==0x1040+shift);
        CHECK(r.after==(0x52800020u|reg)&&!r.already_forced);CHECK(changed_only_word(saved,b,sizeof b,0x440));
        memcpy(saved,b,sizeof b);CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==0&&r.already_forced);CHECK(!memcmp(saved,b,sizeof b));++cases;
    }
    /* A copied/reordered selector register and logging format pointer. */
    bl_fixture_pe(b,sizeof b,0,8,0x222);
    bl_fixture_word(b,0x444,0x2a0803e9u);bl_fixture_word(b,0x448,0xd503201fu);
    bl_fixture_word(b,0x44c,bl_fixture_tb(0x104c,0x1080,9,0,1));bl_fixture_word(b,0x450,bl_fixture_branch(0x1050,0x1300,1));
    bl_fixture_word(b,0x484,bl_fixture_adrp(7,0x1084,0x5000));bl_fixture_word(b,0x488,0x910000e7u);
    bl_fixture_word(b,0x48c,0xaa0703e1u);bl_fixture_word(b,0x490,0x52800800u);bl_fixture_word(b,0x494,bl_fixture_branch(0x1094,0x1300,1));
    memcpy(saved,b,sizeof b);CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==0);CHECK(r.after==0x52800028u);CHECK(changed_only_word(saved,b,sizeof b,0x440));++cases;
    /* CCMP changes NZCV only; it does not redefine the copied GP selector. */
    bl_fixture_word(b,0x448,0x7a410909u);CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==0&&r.already_forced);++cases;
    /* ADR is accepted in addition to ADRP+ADD. */
    bl_fixture_pe(b,sizeof b,0,8,0x55);uint32_t delta=0x5000-0x1084;
    bl_fixture_word(b,0x484,0x10000001u|((delta&3)<<29)|((delta>>2)<<5));bl_fixture_word(b,0x488,0xd503201fu);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==0);++cases;
    /* SUB must subtract: an unrelated address must not be mistaken for a log. */
    bl_fixture_pe(b,sizeof b,0,8,0x55);delta=0x4fe0-0x1084;
    bl_fixture_word(b,0x484,0x10000001u|((delta&3)<<29)|((delta>>2)<<5));bl_fixture_word(b,0x488,0xd1008021u);
    memcpy(saved,b,sizeof b);CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_NO_TARGET);CHECK(!memcmp(saved,b,sizeof b));++cases;
    /* Both correct SUB and ADD aliases still prove the actual format address. */
    delta=0x5020-0x1084;bl_fixture_word(b,0x484,0x10000001u|((delta&3)<<29)|((delta>>2)<<5));
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==0);++cases;
    bl_fixture_pe(b,sizeof b,0,8,0x55);delta=0x4fe0-0x1084;
    bl_fixture_word(b,0x484,0x10000001u|((delta&3)<<29)|((delta>>2)<<5));bl_fixture_word(b,0x488,0x91008021u);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==0);++cases;
    /* ADDG has tagged-pointer/uimm6 semantics, not ordinary ADD imm12. */
    bl_fixture_pe(b,sizeof b,0,8,0x55);delta=0x4fbf-0x1084;
    bl_fixture_word(b,0x484,0x10000001u|((delta&3)<<29)|((delta>>2)<<5));bl_fixture_word(b,0x488,0x91810421u);
    memcpy(saved,b,sizeof b);CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_NO_TARGET);CHECK(!memcmp(saved,b,sizeof b));++cases;
    /* TBZ and fall-through layout inversion select the corresponding zero bit. */
    bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0x444,bl_fixture_tb(0x1044,0x1080,8,0,0));
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==0&&r.after==0x52800008u);++cases;
    bl_fixture_word(b,0x440,0x52a00008u);memcpy(saved,b,sizeof b);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==0&&r.already_forced);CHECK(!memcmp(saved,b,sizeof b));++cases;
    for(unsigned hw=2;hw<=3;++hw){bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0x440,0x52800008u|(hw<<21));memcpy(saved,b,sizeof b);
        CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_NO_TARGET);CHECK(!memcmp(saved,b,sizeof b));++cases;}
    bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0x444,bl_fixture_tb(0x1044,0x1300,8,0,1));
    bl_fixture_word(b,0x448,bl_fixture_branch(0x1048,0x1080,0));CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==0&&r.after==0x52800008u);++cases;
    /* Two distinct selectors are rejected before either word is changed. */
    bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0x520,0x39400000u|(23u<<10)|(26u<<5)|9);
    bl_fixture_word(b,0x524,bl_fixture_tb(0x1124,0x1080,9,0,1));bl_fixture_word(b,0x528,bl_fixture_branch(0x1128,0x1300,1));
    memcpy(saved,b,sizeof b);CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_AMBIGUOUS);CHECK(!memcmp(saved,b,sizeof b));++cases;
    bl_fixture_pe(b,sizeof b,0,8,0x33);b[0xc00]='X';memcpy(saved,b,sizeof b);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_NO_TARGET);CHECK(!memcmp(saved,b,sizeof b));++cases;
    /* LSE LDADD W0,W1,[X2] overwrites the previously known format pointer X1. */
    bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0x48c,0xb8200041u);memcpy(saved,b,sizeof b);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_NO_TARGET);CHECK(!memcmp(saved,b,sizeof b));++cases;
    /* PAuth HINT aliases can overwrite GP values; they are not generic NOPs. */
    bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0x484,bl_fixture_adrp(17,0x1084,0x5000));
    bl_fixture_word(b,0x488,0x91000231u);bl_fixture_word(b,0x48c,0xd503211fu);
    bl_fixture_word(b,0x490,0xaa1103e1u);bl_fixture_word(b,0x494,0x52800800u);bl_fixture_word(b,0x498,bl_fixture_branch(0x1098,0x1300,1));
    memcpy(saved,b,sizeof b);CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_NO_TARGET);CHECK(!memcmp(saved,b,sizeof b));++cases;
    bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0x484,bl_fixture_adrp(30,0x1084,0x5000));
    bl_fixture_word(b,0x488,0x910003deu);bl_fixture_word(b,0x48c,0xd50323bfu);
    bl_fixture_word(b,0x490,0xaa1e03e1u);bl_fixture_word(b,0x494,0x52800800u);bl_fixture_word(b,0x498,bl_fixture_branch(0x1098,0x1300,1));
    memcpy(saved,b,sizeof b);CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_NO_TARGET);CHECK(!memcmp(saved,b,sizeof b));++cases;
    bl_fixture_pe(b,sizeof b,0,8,0x33);b[0xc00+sizeof("Launching fastboot")-1]='d';memcpy(saved,b,sizeof b);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_NO_TARGET);CHECK(!memcmp(saved,b,sizeof b));++cases;
    bl_fixture_pe(b,sizeof b,0,8,0x33);b[0xc00+sizeof("Launching fastboot")-1]='\n';
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==0&&r.file_offset==0x440);++cases;
    bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0x198,0x7fffffffu);memcpy(saved,b,sizeof b);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_BAD_PE);CHECK(!memcmp(saved,b,sizeof b));++cases;
    bl_fixture_pe(b,sizeof b,0,8,0x33);memcpy(saved,b,sizeof b);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,0)==KBL_EFI_BL_BAD_PE);CHECK(!memcmp(saved,b,sizeof b));++cases;
    CHECK(kbl_efi_force_bootloader(b,sizeof b,(void*)(b+0x400))==KBL_EFI_BL_BAD_PE);CHECK(!memcmp(saved,b,sizeof b));++cases;
    /* Replacing a writeback load would drop a base-register side effect. */
    bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0x440,0x38401748u);memcpy(saved,b,sizeof b);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_NO_TARGET);CHECK(!memcmp(saved,b,sizeof b));++cases;
    /* A branch bypassing the load invalidates its definition proof. */
    bl_fixture_pe(b,sizeof b,0,8,0x33);
    bl_fixture_word(b,0x530,bl_fixture_branch(0x1130,0x1044,0));memcpy(saved,b,sizeof b);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_NO_TARGET);CHECK(!memcmp(saved,b,sizeof b));++cases;
    /* File length and SizeOfImage are not an artificial allocation policy. */
    size_t large_bytes=65u*1024u*1024u+4096u;unsigned char*large=calloc(large_bytes,1);CHECK(large);
    CHECK(bl_fixture_pe(large,large_bytes,0,8,0x33));memcpy(saved,large,sizeof saved);
    CHECK(kbl_efi_force_bootloader(large,large_bytes,&r)==0&&r.file_offset==0x440);
    CHECK(changed_only_word(saved,large,sizeof saved,0x440));
    for(size_t i=sizeof saved;i<large_bytes;++i)CHECK(large[i]==0);free(large);++cases;
    bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0xd0,0x80000000u);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==0);++cases;
    bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0xd0,0xfffff000u);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==0);++cases;
    bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0xd0,0xffffffffu);memcpy(saved,b,sizeof b);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_BAD_PE);CHECK(!memcmp(saved,b,sizeof b));++cases;
    bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0x3c,0xfffffff0u);memcpy(saved,b,sizeof b);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_BAD_PE);CHECK(!memcmp(saved,b,sizeof b));++cases;
    bl_fixture_pe(b,sizeof b,0,8,0x33);bl_fixture_word(b,0x19c,0xfffffe00u);memcpy(saved,b,sizeof b);
    CHECK(kbl_efi_force_bootloader(b,sizeof b,&r)==KBL_EFI_BL_BAD_PE);CHECK(!memcmp(saved,b,sizeof b));++cases;
    /* The PE section count uses its format width, not a 96-element array.
     * Put both meaningful sections after the former array boundary. */
    size_t many_bytes=0x4000;unsigned char*many=calloc(many_bytes,1),*many_saved=malloc(many_bytes);CHECK(many&&many_saved);
    CHECK(bl_fixture_pe(many,many_bytes,0x2000,8,0x33));
    unsigned char text_section[40],data_section[40];memcpy(text_section,many+0x188,40);memcpy(data_section,many+0x1b0,40);
    memmove(many+0x2000,many+0x400,0x1000);memset(many+0x188,0,97*40);
    memcpy(many+0x188+95*40,text_section,40);memcpy(many+0x188+96*40,data_section,40);
    bl_fixture_word(many,0x84,(97u<<16)|0xaa64u);bl_fixture_word(many,0xd4,0x2000);
    bl_fixture_word(many,0x188+95*40+20,0x2000);bl_fixture_word(many,0x188+96*40+20,0x2800);
    memcpy(many_saved,many,many_bytes);CHECK(kbl_efi_force_bootloader(many,many_bytes,&r)==0&&r.file_offset==0x2040);
    CHECK(changed_only_word(many_saved,many,many_bytes,0x2040));++cases;
    memcpy(many,many_saved,many_bytes);bl_fixture_word(many,0x188+96*40+20,0x2000);memcpy(many_saved,many,many_bytes);
    CHECK(kbl_efi_force_bootloader(many,many_bytes,&r)==KBL_EFI_BL_BAD_PE);CHECK(!memcmp(many_saved,many,many_bytes));++cases;
    bl_fixture_word(many,0x84,(65535u<<16)|0xaa64u);memcpy(many_saved,many,many_bytes);
    CHECK(kbl_efi_force_bootloader(many,many_bytes,&r)==KBL_EFI_BL_BAD_PE);CHECK(!memcmp(many_saved,many,many_bytes));++cases;
    free(many_saved);free(many);
    if(argc==3){FILE*f=fopen(argv[1],"rb");CHECK(f);CHECK(!fseek(f,0,SEEK_END));long length=ftell(f);CHECK(length>0);rewind(f);
        unsigned char*image=malloc((size_t)length),*original=malloc((size_t)length);CHECK(image&&original);
        CHECK(fread(image,1,(size_t)length,f)==(size_t)length);fclose(f);memcpy(original,image,(size_t)length);
        CHECK(kbl_efi_force_bootloader(image,(size_t)length,&r)==0);CHECK(changed_only_word(original,image,(size_t)length,r.file_offset));
        f=fopen(argv[2],"wb");CHECK(f);CHECK(fwrite(image,1,(size_t)length,f)==(size_t)length);fclose(f);
        printf("{\"cases\":%u,\"realOffset\":%zu,\"realRva\":%u,\"before\":%u,\"after\":%u}\n",cases,r.file_offset,r.load_rva,r.before,r.after);
        free(image);free(original);
    }else printf("{\"cases\":%u}\n",cases);
    return 0;
}
