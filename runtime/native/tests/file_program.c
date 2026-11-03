/* SDK VFS integration fixture; deliberately leaves one invocation-owned file. */
#include "kbl_program.h"
KBL_DECLARE_PROGRAM_WITH_API_BYTES(KBL_PROGRAM_API_FILE_BYTES);
_Static_assert(KBL_PROGRAM_API_UEFI_BYTES==64,"old prefix");
_Static_assert(KBL_PROGRAM_API_FILE_BYTES==136,"VFS ABI");
int kbl_main(const struct kbl_program_api*a,void*opaque){
    (void)opaque;if(a->struct_bytes<KBL_PROGRAM_API_FILE_BYTES||a->current_el(a->host)!=1)return 80;
    kbl_file_handle h=0;size_t n=0;uint64_t position=0;char buffer[4];struct kbl_file_stat stat;
    if(a->file_open(a->host,"/sdk-file",KBL_FILE_READ|KBL_FILE_WRITE|KBL_FILE_CREATE|KBL_FILE_TRUNC,&h))return 81;
    if(a->file_write(a->host,h,"a\0b",3,&n)||n!=3)return 82;
    unsigned force=a->argc>1&&a->argv[1][0]=='f';
    if(a->yield(a->host,force?0:100))return 83;
    if(a->file_seek(a->host,h,0,KBL_SEEK_SET,&position)||position)return 84;
    if(a->file_read(a->host,h,buffer,4,&n)||n!=3||buffer[0]!='a'||buffer[1]!=0||buffer[2]!='b')return 85;
    if(a->file_stat(a->host,"/sdk-file",&stat)||stat.bytes!=3||stat.type!=KBL_FILE_REGULAR)return 86;
    if(a->file_sync(a->host,h))return 87;
    if(a->write(a->host,"SDK VFS passed\n",15))return 88;
    /* Runtime, not the program, must close h before reporting job complete. */
    return 0;
}
