/* Unit fixture: legacy RAM/raw suites intentionally do not link FS libraries. */
#include "../../fs/kbl_fs.h"
int kbl_fs_mount(const struct kbl_fs_host*h,const struct kbl_fs_device*d,const char*t,unsigned f,struct kbl_fs*out){
    (void)h;(void)d;(void)t;(void)f;if(out)*out=(struct kbl_fs){0};return -KBL_ENOSYS;
}
