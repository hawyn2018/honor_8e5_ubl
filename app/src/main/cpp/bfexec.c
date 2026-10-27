/* bfexec: KernelSU GRANT_ROOT -> exec a command with real stdout/stderr/exit status.
 * No argument runs id; one argument is a base64-encoded shell command.
 * Exit 2: invalid argument; 125: no KernelSU fd; 126: grant denied; 127: exec failure.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/fsuid.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#define KSU_MAGIC_1 0xdeadbeefUL
#define KSU_MAGIC_2_LOCAL 0xcafebabeUL
#define KSU_MAGIC_2_REF 0xdeadcafeUL
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
    long reboot_rc = syscall(SYS_reboot, KSU_MAGIC_1, KSU_MAGIC_2_LOCAL, 0, &fd);
    int reboot_errno = errno;
    if (fd < 0) {
        errno = 0;
        reboot_rc = syscall(SYS_reboot, KSU_MAGIC_1, KSU_MAGIC_2_REF, 0, &fd);
        reboot_errno = errno;
    }
    if (fd < 0) {
        fd = open("/dev/ksu", O_RDWR | O_CLOEXEC);
        reboot_errno = errno;
    }
    if (fd < 0) {
        fprintf(stderr, "BF_NO_KSU_FD rc=%ld errno=%d(%s)\n", reboot_rc, reboot_errno, strerror(reboot_errno));
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
    int rc_gid = setresgid(0, 0, 0);
    int rc_uid = setresuid(0, 0, 0);
    setfsgid(0);
    setfsuid(0);
    if (rc_gid != 0 || rc_uid != 0) {
        fprintf(stderr, "BF_SETRESUID_FAIL\n");
        return 126;
    }

    execl("/system/bin/sh", "sh", "-c", command, (char *)NULL);
    fprintf(stderr, "BF_EXEC_FAIL errno=%d(%s)\n", errno, strerror(errno));
    return 127;
}
