/* SPDX-License-Identifier: MIT */
#include "include/kbl_port.h"
#ifndef KBL_PORT_REQUIRED_BYTES
#define KBL_PORT_REQUIRED_BYTES KBL_PROGRAM_API_ARGV_BYTES
#endif
KBL_DECLARE_PROGRAM_WITH_API_BYTES(KBL_PORT_REQUIRED_BYTES);
extern int main(int,char **);
int kbl_main(const struct kbl_program_api*api,void*opaque){
    (void)opaque;if(kbl_compat_enter(api))return 120;
    int argc=0;char**argv=0;
    if(api->struct_bytes>=KBL_PROGRAM_API_ARGV_BYTES){argc=api->argc;argv=(char**)api->argv;}
    int rc=main(argc,argv);if(kbl_compat_leave()&&!rc)rc=121;return rc;
}
