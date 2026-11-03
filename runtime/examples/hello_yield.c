/* SPDX-License-Identifier: MIT */
#include "kbl_program.h"
KBL_DECLARE_PROGRAM();
/* Volatile table forces real self-image R_AARCH64_RELATIVE relocations. */
static const char *volatile messages[] = {"KBL demo before yield\n", "KBL demo after yield\n"};
static int text(const struct kbl_program_api *api,const char *s) {
    size_t n=0; while(s[n])++n; return api->write(api->host,s,n);
}
int kbl_main(const struct kbl_program_api *api,void *opaque) {
    (void)opaque;
    if(!api || api->abi_version!=KBL_PROGRAM_ABI || api->struct_bytes<KBL_PROGRAM_API_BASE_BYTES)return 10;
    if(text(api,messages[0]))return 11;
    uint64_t raw; __asm__ volatile("mrs %0, CurrentEL":"=r"(raw));
    unsigned el=(unsigned)((raw>>2)&3);
    char line[]="CurrentEL=0\n"; line[10]=(char)('0'+el);
    if(el!=api->current_el(api->host) || api->write(api->host,line,sizeof(line)-1))return 12;
    if(api->struct_bytes>=KBL_PROGRAM_API_ARGV_BYTES && api->argc>0 && api->argv && api->argv[0]) {
        if(text(api,"argv[0]=") || text(api,api->argv[0]) || text(api,"\n"))return 15;
    }
    if(api->yield(api->host,1000))return 13;
    return text(api,messages[1])?14:0;
}
