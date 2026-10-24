/*
 * bfexec.c —— BootForge 的 KernelSU GRANT_ROOT 执行器。
 *
 * 提权链第 6 步加载打过补丁的 KernelSU 模块（finit_module 参数 allow_shell=1），
 * 因此 UID 2000 的进程可以通过 KernelSU 的 reboot 钩子取得 root fd，
 * 再用 KSU_IOCTL_GRANT_ROOT 把本进程提升为 root，最后 exec 目标命令。
 * 与 MagicRoot 的 ksu_grant 同一机制；区别是它 exec 的是 root_stage.sh
 * 安装 ksud，而本程序直接 exec 调用方给出的命令——不安装 ksud、
 * 不加冕 KernelSU 管理器，root 只属于经本应用无线 ADB Shell 通道
 * （UID 2000，TLS 身份由本应用持有）发起的进程。
 *
 * 用法:
 *   bfexec              提升后执行 id（链路自检，等价 MagicRoot 的 root_id）
 *   bfexec <base64>     提升后执行 /system/bin/sh -c <base64 解码后的命令>
 *
 * 退出码: 命令自身退出码；2=参数无法解码；125=未找到 KernelSU 钩子；
 *         126=GRANT_ROOT 被拒绝；127=exec 失败。
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#define KSU_MAGIC_1 0xdeadbeefUL
#define KSU_MAGIC_2 0xcafebabeUL
#define KSU_IOCTL_GRANT_ROOT 0x4b01UL

static int b64value(int c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

/* 解码标准 base64（允许 '=' 填充）；返回带 NUL 终止的 malloc 缓冲，失败返回 NULL。 */
static char *b64decode(const char *text) {
    size_t len = strlen(text);
    if (len == 0 || (len & 3) != 0) return NULL;
    unsigned char *out = malloc((len / 4) * 3 + 1);
    if (!out) return NULL;
    size_t o = 0;
    for (size_t i = 0; i < len; i += 4) {
        int v0 = b64value(text[i]);
        int v1 = b64value(text[i + 1]);
        int v2 = text[i + 2] == '=' ? -2 : b64value(text[i + 2]);
        int v3 = text[i + 3] == '=' ? -2 : b64value(text[i + 3]);
        if (v0 < 0 || v1 < 0 || v2 == -1 || v3 == -1) { free(out); return NULL; }
        unsigned n = ((unsigned)v0 << 18) | ((unsigned)v1 << 12) |
                     (v2 < 0 ? 0u : (unsigned)v2 << 6) | (v3 < 0 ? 0u : (unsigned)v3);
        out[o++] = (unsigned char)(n >> 16);
        if (v2 >= 0) out[o++] = (unsigned char)(n >> 8);
        if (v3 >= 0) out[o++] = (unsigned char)n;
    }
    out[o] = 0;
    return (char *)out;
}

int main(int argc, char **argv) {
    const char *command = "id";
    char *decoded = NULL;
    if (argc > 1) {
        decoded = b64decode(argv[1]);
        if (!decoded || !decoded[0]) {
            fprintf(stderr, "BF_BAD_ARGUMENT\n");
            return 2;
        }
        command = decoded;
    }

    int fd = -1;
    errno = 0;
    long reboot_rc = syscall(SYS_reboot, KSU_MAGIC_1, KSU_MAGIC_2, 0, &fd);
    if (fd < 0) {
        fprintf(stderr, "BF_NO_KSU_FD rc=%ld errno=%d(%s)\n", reboot_rc, errno, strerror(errno));
        return 125;
    }
    errno = 0;
    long grant_rc = ioctl(fd, KSU_IOCTL_GRANT_ROOT, 0);
    int grant_errno = errno;
    close(fd);
    if (grant_rc != 0 || geteuid() != 0) {
        fprintf(stderr, "BF_GRANT_DENIED rc=%ld errno=%d(%s)\n", grant_rc, grant_errno, strerror(grant_errno));
        return 126;
    }
    /* 统一真实/有效/保存 uid，保证 id -u 与文件访问都表现为 root。 */
    int rc_gid = setresgid(0, 0, 0);
    int rc_uid = setresuid(0, 0, 0);
    if (rc_gid != 0 || rc_uid != 0) {
        fprintf(stderr, "BF_SETRESUID_FAIL\n");
        return 126;
    }

    execl("/system/bin/sh", "sh", "-c", command, (char *)NULL);
    fprintf(stderr, "BF_EXEC_FAIL errno=%d(%s)\n", errno, strerror(errno));
    return 127;
}
