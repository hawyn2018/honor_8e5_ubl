/* SPDX-License-Identifier: MIT */
#include "kbl_program.h"
KBL_DECLARE_PROGRAM_WITH_API_BYTES(KBL_PROGRAM_API_UEFI_BYTES);
int kbl_main(const struct kbl_program_api *api,void *opaque) {
    (void)opaque;
    static const char absent[]="Real UEFI environment unavailable\n";
    static const char present[]="Real UEFI environment provided\n";
    if(!api || api->struct_bytes<KBL_PROGRAM_API_UEFI_BYTES)return 10;
    void *image=0,*system_table=0;
    if(!api->uefi_environment || api->uefi_environment(api->host,&image,&system_table) || !image || !system_table) {
        api->write(api->host,absent,sizeof(absent)-1); return 1;
    }
    /* Include the real platform's UEFI headers before using these borrowed
     * handles. This example only confirms capability availability. */
    return api->write(api->host,present,sizeof(present)-1)?11:0;
}
