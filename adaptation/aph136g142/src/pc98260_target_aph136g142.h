#ifndef PC98260_TARGET_APH136G142_H
#define PC98260_TARGET_APH136G142_H

#include <stdint.h>

#define TARGET_NAME          "aph-an00-136-g142"
#define TARGET_KERNEL        "6.12.58-android16-6-g142ddad31e60-abogki576095858-4k"
#define TARGET_KERNEL_SHA256 "f445f7756756ad5c8a58b63c48b12efd69f2e829903e3f009ad2ad6bdfb17c56"

#define LINK_SELINUX_STATE   UINT64_C(0xffffffc0827e7a60)
#define LINK_INIT_USER_NS    UINT64_C(0xffffffc082572138)
#define LINK_ROOT_USER       UINT64_C(0xffffffc0825723b0)
#define LINK_INIT_GROUPS     UINT64_C(0xffffffc082573878)
#define LINK_INIT_UCOUNTS    UINT64_C(0xffffffc082573d48)

#define LINK_ALLOC_TIMER     UINT64_C(0xffffffc08051a918)
#define LINK_ALLOC_CRED      UINT64_C(0xffffffc0801935e4)
#define LINK_ALLOC_TASK      UINT64_C(0xffffffc0801f06e0)

#define GEO_TASK_CRED        0x900
#define GEO_TASK_REAL_CRED   0x8f8
#define GEO_TASK_COMM        0x910
#define GEO_KITIMER_IT       0x78
#define GEO_KITIMER_SIZE     0x100
#define GEO_KITIMER_STRIDE   0x100
#define GEO_CRED_SIZE        184
#define GEO_CRED_ALLOC_SIZE  192
#define GEO_CRED_UID         0x8
#define GEO_CRED_GID         0xc
#define GEO_CRED_SUID        0x10
#define GEO_CRED_SGID        0x14
#define GEO_CRED_EUID        0x18
#define GEO_CRED_EGID        0x1c
#define GEO_CRED_FSUID       0x20
#define GEO_CRED_FSGID       0x24
#define GEO_CRED_CAP_INH     0x30
#define GEO_CRED_CAP_PERM    0x38
#define GEO_CRED_CAP_EFF     0x40
#define GEO_CRED_CAP_BSET    0x48
#define GEO_CRED_CAP_AMB     0x50
#define GEO_CRED_SECURITY    0x80
#define GEO_CRED_USER        0x88
#define GEO_CRED_USER_NS     0x90
#define GEO_SELINUX_ENFORCE  0
#define GEO_SELINUX_INIT     1
#define GEO_SELINUX_WRITE    (-7)
#define GEO_SELINUX_SIZE     0x80

#define GEO_TASK_OBJSIZE             5696
#define GEO_TASK_OBJ_PER_SLAB        5
#define GEO_TASK_SLAB_PAGES          8
#define GEO_POSIX_TIMER_OBJSIZE      256
#define GEO_POSIX_TIMER_OBJ_PER_SLAB 32
#define GEO_POSIX_TIMER_SLAB_ORDER   1
#define GEO_PAGE_SIZE                4096
#define GEO_FRAG_PAGE_SIZE           8192

#define GEOM_PAGE_OFFSET     UINT64_C(0xffffff8000000000)
#define GEOM_VA_BITS         39
#define GEOM_MEMSTART_PFN    UINT64_C(0x80000)
#define GEOM_MEMSTART_ADDR   UINT64_C(0xffffffc08193b1b8)

#define CAP_FULL             UINT64_C(0x1ffffffffff)

#define BNE_LINK              UINT64_C(0xffffffc0806c8c58)
#define BNE_RELEASE_TASK_OFF  0x410
#define BNE_DOWN_READ_OFF     0x50c
#define BNE_TIMERS_EXIT_OFF   0x87c
#define BNE_EXIT_ITIMERS_OFF  0x88c

#endif
