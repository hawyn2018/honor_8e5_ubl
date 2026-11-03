#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/time.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stddef.h>
#include <limits.h>

/* Private local IPC. This executable retains its caller's original app UID. */
enum { EXIT_UNKNOWN = 125, EXIT_NOT_SUBMITTED = 126 };
enum { FRAME_STDOUT = 1, FRAME_STDERR = 2, FRAME_EXIT = 3,
       FRAME_ERROR = 4, FRAME_STARTED = 16 };
static const unsigned char auth_magic[8] = "HNUBLRA1";
static const unsigned char request_magic[8] = "HNUBLRT1";
static const unsigned char response_magic[8] = "HNUBLRS1";

static void put_be32(unsigned char out[4], uint32_t value) {
    out[0] = (unsigned char)(value >> 24);
    out[1] = (unsigned char)(value >> 16);
    out[2] = (unsigned char)(value >> 8);
    out[3] = (unsigned char)value;
}

static uint32_t get_be32(const unsigned char in[4]) {
    return ((uint32_t)in[0] << 24) | ((uint32_t)in[1] << 16) |
           ((uint32_t)in[2] << 8) | (uint32_t)in[3];
}

static int read_all(int fd, void *data, size_t size) {
    unsigned char *out = data;
    while (size != 0) {
        ssize_t count = read(fd, out, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        out += (size_t)count;
        size -= (size_t)count;
    }
    return 0;
}

static int write_all(int fd, const void *data, size_t size) {
    const unsigned char *in = data;
    while (size != 0) {
        ssize_t count = write(fd, in, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        in += (size_t)count;
        size -= (size_t)count;
    }
    return 0;
}

static int timeout_authentication(int fd, long seconds) {
    struct timeval value = { .tv_sec = seconds, .tv_usec = 0 };
    return setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &value, sizeof(value));
}

static int diagnostic(int code, const char *message) {
    fprintf(stderr, "[hnubl-module] %s\n", message);
    return code;
}

static int exchange(int fd, const char *command, uint32_t command_size) {
    unsigned char magic[8], length[4];
    /* No command bytes are submitted until the root server authenticates us. */
    if (timeout_authentication(fd, 30) != 0 || read_all(fd, magic, sizeof(magic)) != 0 ||
        memcmp(magic, auth_magic, sizeof(magic)) != 0) {
        return diagnostic(EXIT_NOT_SUBMITTED, "Root broker authentication failed; command not submitted");
    }
    if (timeout_authentication(fd, 0) != 0) {
        return diagnostic(EXIT_NOT_SUBMITTED, "Root broker timeout configuration failed; command not submitted");
    }
    put_be32(length, command_size);
    if (write_all(fd, request_magic, sizeof(request_magic)) != 0 ||
        write_all(fd, length, sizeof(length)) != 0 ||
        write_all(fd, command, command_size) != 0) {
        /* The server parses the complete request before creating any process. */
        return diagnostic(EXIT_NOT_SUBMITTED, "Root request was incomplete; command not submitted");
    }
    /* From here onward a broken link means the result is unknown. Never replay. */
    if (read_all(fd, magic, sizeof(magic)) != 0 ||
        memcmp(magic, response_magic, sizeof(magic)) != 0) {
        return diagnostic(EXIT_UNKNOWN, "Root response missing; result unknown, do not replay command");
    }
    int started = 0;
    int remote_error = 0;
    int output_failed = 0;
    unsigned char buffer[32768];
    for (;;) {
        unsigned char header[5];
        if (read_all(fd, header, sizeof(header)) != 0) break;
        uint32_t size = get_be32(header + 1);
        unsigned int type = header[0];
        if (type == FRAME_STARTED) {
            if (size != 0 || started || remote_error) break;
            started = 1;
            continue;
        }
        if (type == FRAME_EXIT) {
            unsigned char status[4];
            if (size != sizeof(status) || read_all(fd, status, sizeof(status)) != 0) break;
            uint32_t result = get_be32(status);
            if (result > 255 || (!started && !remote_error)) break;
            if (output_failed) return diagnostic(EXIT_UNKNOWN, "Command output was lost; do not replay command");
            return (int)result;
        }
        if (type != FRAME_STDOUT && type != FRAME_STDERR && type != FRAME_ERROR) break;
        if ((type == FRAME_STDOUT || type == FRAME_STDERR) && !started) break;
        if (type == FRAME_ERROR) {
            if (started) break;
            remote_error = 1;
            if (write_all(STDERR_FILENO, "[hnubl-module] ", 15) != 0) output_failed = 1;
        }
        int target = type == FRAME_STDOUT ? STDOUT_FILENO : STDERR_FILENO;
        while (size != 0) {
            size_t part = size < sizeof(buffer) ? size : sizeof(buffer);
            if (read_all(fd, buffer, part) != 0) {
                return diagnostic(EXIT_UNKNOWN, "Root response interrupted; result unknown, do not replay command");
            }
            /* Drain the socket even when our consumer exits. An already started
             * write must not block on an abandoned stdout/stderr pipe. */
            if (write_all(target, buffer, part) != 0) output_failed = 1;
            size -= (uint32_t)part;
        }
        if (type == FRAME_ERROR && write_all(STDERR_FILENO, "\n", 1) != 0) output_failed = 1;
    }
    return diagnostic(EXIT_UNKNOWN, "Root protocol ended before exit status; result unknown, do not replay command");
}

static int self_test(void) {
    const uint32_t values[] = { 0, 1, 255, 256, 65535, 0x12345678u, UINT32_MAX };
    for (size_t index = 0; index < sizeof(values) / sizeof(values[0]); ++index) {
        unsigned char encoded[4];
        put_be32(encoded, values[index]);
        if (get_be32(encoded) != values[index]) return 1;
    }
    puts("HNUBL_MODULE_CLIENT_SELF_TEST=success");
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--self-test") == 0) return self_test();
    if (argc != 4 || strcmp(argv[2], "-c") != 0) {
        return diagnostic(EXIT_NOT_SUBMITTED, "Usage: hnubl_module_client SOCKET -c COMMAND");
    }
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    size_t path_size = strlen(argv[1]);
    size_t command_size = strlen(argv[3]);
    if (argv[1][0] != '/' || path_size >= sizeof(address.sun_path) || command_size > INT32_MAX) {
        return diagnostic(EXIT_NOT_SUBMITTED, "Request exceeds the Unix socket or protocol address range");
    }
    signal(SIGPIPE, SIG_IGN);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return diagnostic(EXIT_NOT_SUBMITTED, "Root socket creation failed; command not submitted");
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path, argv[1], path_size + 1);
    socklen_t address_size = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + path_size + 1);
    if (connect(fd, (struct sockaddr *)&address, address_size) != 0) {
        close(fd);
        return diagnostic(EXIT_NOT_SUBMITTED, "Root broker is unavailable; command not submitted");
    }
    struct ucred peer;
    socklen_t peer_size = sizeof(peer);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) != 0 ||
        peer_size != sizeof(peer) || peer.uid != 0) {
        close(fd);
        return diagnostic(EXIT_NOT_SUBMITTED, "Root broker UID rejected; command not submitted");
    }
    int result = exchange(fd, argv[3], (uint32_t)command_size);
    close(fd);
    return result;
}
