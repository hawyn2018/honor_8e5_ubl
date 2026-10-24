/* BootForge device-mapper table query, with all key material kept off stdout/stderr.
 * ABI: Linux v6.12 include/uapi/linux/dm-ioctl.h and drivers/md/dm-ioctl.c.
 * Parameters: AOSP fs_mgr/libdm/dm_target.cpp (linear, crypt, default-key v1/v2):
 * https://android.googlesource.com/platform/system/core/+/d076857c4f6c15a70f1186c4214592837f46c57d/fs_mgr/libdm/dm_target.cpp
 * Only DM_TABLE_STATUS is issued, requesting the live table and no flush.
 * Windows builds expose in-memory self-tests only.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifndef _WIN32
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <linux/dm-ioctl.h>
#endif

#define REPLY_CAPACITY 65536U
#define NAME_BYTES 128U
#define TYPE_BYTES 16U
#define TABLE_FLAG (1U << 4)
#define ACTIVE_FLAG (1U << 5)
#define BUFFER_FULL_FLAG (1U << 8)
#define NOFLUSH_FLAG (1U << 11)
#define QUERY_INACTIVE_FLAG (1U << 12)
#define SECURE_DATA_FLAG (1U << 15)
#define MAX_ARGS 64U

/* Fixed-width mirror permits the decoder to be tested on Windows as well. */
struct wire_header {
    uint32_t version[3], data_size, data_start, target_count;
    int32_t open_count;
    uint32_t flags, event_nr, padding;
    uint64_t dev;
    char name[NAME_BYTES], uuid[129], data[7];
};
struct wire_target {
    uint64_t start, length;
    int32_t status;
    uint32_t next;
    char type[TYPE_BYTES];
};
#ifndef _WIN32
_Static_assert(sizeof(struct wire_header) == sizeof(struct dm_ioctl), "dm ioctl ABI size");
_Static_assert(offsetof(struct wire_header, name) == offsetof(struct dm_ioctl, name), "dm name ABI");
_Static_assert(offsetof(struct wire_header, flags) == offsetof(struct dm_ioctl, flags), "dm flags ABI");
_Static_assert(sizeof(struct wire_target) == sizeof(struct dm_target_spec), "dm target ABI size");
_Static_assert(TABLE_FLAG == DM_STATUS_TABLE_FLAG && ACTIVE_FLAG == DM_ACTIVE_PRESENT_FLAG &&
               BUFFER_FULL_FLAG == DM_BUFFER_FULL_FLAG && NOFLUSH_FLAG == DM_NOFLUSH_FLAG &&
               QUERY_INACTIVE_FLAG == DM_QUERY_INACTIVE_TABLE_FLAG && SECURE_DATA_FLAG == DM_SECURE_DATA_FLAG,
               "dm flags ABI");
#endif
struct span { const char *text; size_t length; };
struct reduced_table { uint64_t start, length, offset; bool has_offset; char type[TYPE_BYTES]; };

static void wipe(void *buffer, size_t bytes) {
    volatile unsigned char *p = (volatile unsigned char *)buffer;
    while (bytes--) *p++ = 0;
}
static bool valid_name(const char *name) {
    size_t n = 0;
    if (!name) return false;
    for (; name[n]; n++) {
        unsigned char c = (unsigned char)name[n];
        if (n >= NAME_BYTES - 1 || c < 32 || c > 126 || c == '/') return false;
    }
    return n != 0;
}
static bool number(struct span s, uint64_t *out) {
    uint64_t value = 0;
    if (!s.length) return false;
    for (size_t i = 0; i < s.length; i++) {
        unsigned char c = (unsigned char)s.text[i];
        if (c < '0' || c > '9' || value > (UINT64_MAX - (uint64_t)(c - '0')) / 10) return false;
        value = value * 10 + (uint64_t)(c - '0');
    }
    *out = value;
    return true;
}
static bool device_token(struct span s) {
    if (s.length > 5 && !memcmp(s.text, "/dev/", 5)) return true;
    for (size_t i = 1; i + 1 < s.length; i++) {
        if (s.text[i] == ':') {
            uint64_t major, minor;
            return number((struct span){s.text, i}, &major) && major <= UINT32_MAX &&
                   number((struct span){s.text + i + 1, s.length - i - 1}, &minor) && minor <= UINT32_MAX;
        }
    }
    return false;
}
static bool tokens(const char *text, size_t length, struct span args[MAX_ARGS], size_t *count) {
    size_t at = 0, n = 0;
    while (at < length) {
        while (at < length && (text[at] == ' ' || text[at] == '\t')) at++;
        if (at == length) break;
        size_t first = at;
        while (at < length && text[at] != ' ' && text[at] != '\t') {
            unsigned char c = (unsigned char)text[at];
            if (c < 33 || c > 126) return false;
            at++;
        }
        if (n == MAX_ARGS || at - first > 4096) return false;
        args[n++] = (struct span){text + first, at - first};
    }
    *count = n;
    return true;
}
static bool parameters(const char *type, const char *text, size_t length, struct reduced_table *out) {
    /* Vendor ramcache has a vendor-specific table grammar. No offset is inferred. */
    if (!strcmp(type, "ramcache")) { out->has_offset = false; return true; }
    struct span args[MAX_ARGS];
    size_t count;
    uint64_t unused, optional;
    if (!tokens(text, length, args, &count)) return false;
    if (!strcmp(type, "linear")) {
        if (count != 2 || !device_token(args[0]) || !number(args[1], &out->offset)) return false;
    } else if (!strcmp(type, "crypt") || !strcmp(type, "default-key")) {
        size_t base = 5, device_at = 3, offset_at = 4;
        /* AOSP default-key v1 omits iv_offset. v2 and crypt both include it. */
        if (!strcmp(type, "default-key") && count >= 4 && device_token(args[2])) {
            base = 4; device_at = 2; offset_at = 3;
        } else if (count < 5 || !number(args[2], &unused)) return false;
        if (count < base || !device_token(args[device_at]) || !number(args[offset_at], &out->offset)) return false;
        if (count != base && (!number(args[base], &optional) || optional > MAX_ARGS || count != base + 1 + optional)) return false;
    } else return false;
    if (out->offset > UINT64_MAX - out->length) return false;
    out->has_offset = true;
    return true;
}
static bool decode(const unsigned char *buffer, size_t capacity, struct reduced_table *out) {
    struct wire_header header;
    struct wire_target target;
    if (capacity < sizeof(header)) return false;
    memcpy(&header, buffer, sizeof(header));
    if (header.version[0] != 4 || header.target_count != 1 || !(header.flags & ACTIVE_FLAG) ||
        (header.flags & (BUFFER_FULL_FLAG | QUERY_INACTIVE_FLAG)) || header.data_size > capacity ||
        header.data_start < sizeof(header) || (header.data_start & 7U) ||
        header.data_start > header.data_size || header.data_size - header.data_start <= sizeof(target)) return false;
    memcpy(&target, buffer + header.data_start, sizeof(target));
    if (target.status || !target.length || target.start > UINT64_MAX - target.length ||
        !memchr(target.type, 0, sizeof(target.type))) return false;
    if (strcmp(target.type, "linear") && strcmp(target.type, "crypt") &&
        strcmp(target.type, "default-key") && strcmp(target.type, "ramcache")) return false;
    const char *params = (const char *)buffer + header.data_start + sizeof(target);
    size_t available = header.data_size - header.data_start - sizeof(target);
    const char *end = (const char *)memchr(params, 0, available);
    if (!end || target.next < sizeof(target) + (size_t)(end - params) + 1 ||
        (target.next & 7U) || target.next > capacity - header.data_start) return false;
    memset(out, 0, sizeof(*out));
    out->start = target.start; out->length = target.length;
    memcpy(out->type, target.type, sizeof(out->type));
    return parameters(out->type, params, (size_t)(end - params), out);
}
static int table(const char *name) {
    if (!valid_name(name)) { fputs("ERROR=invalid_dm_name\n", stderr); return 2; }
#ifdef _WIN32
    fputs("ERROR=table_query_requires_linux\n", stderr);
    return 1;
#else
    int fd = open("/dev/mapper/control", O_RDONLY | O_CLOEXEC);
    if (fd < 0) fd = open("/dev/device-mapper", O_RDONLY | O_CLOEXEC);
    if (fd < 0) { fprintf(stderr, "ERROR=dm_control_open_failed\nERRNO=%d\n", errno); return 1; }
    struct stat st;
    if (fstat(fd, &st) || !S_ISCHR(st.st_mode)) { close(fd); fputs("ERROR=dm_control_not_character_device\n", stderr); return 1; }
    unsigned char *buffer = (unsigned char *)calloc(1, REPLY_CAPACITY);
    if (!buffer) { close(fd); fputs("ERROR=allocation_failed\n", stderr); return 1; }
    struct dm_ioctl *query = (struct dm_ioctl *)buffer;
    /* Request the earliest compatible v4 ABI; no separate version ioctl is needed. */
    query->version[0] = 4;
    query->data_size = REPLY_CAPACITY;
    query->data_start = sizeof(*query);
    query->flags = DM_STATUS_TABLE_FLAG | DM_NOFLUSH_FLAG | DM_SECURE_DATA_FLAG;
    memcpy(query->name, name, strlen(name) + 1);
    int result = ioctl(fd, DM_TABLE_STATUS, query);
    int saved = errno;
    close(fd);
    struct reduced_table reduced;
    bool okay = result == 0 && decode(buffer, REPLY_CAPACITY, &reduced);
    wipe(buffer, REPLY_CAPACITY);
    free(buffer);
    if (!okay) {
        if (result) fprintf(stderr, "ERROR=dm_table_query_failed\nERRNO=%d\n", saved);
        else fputs("ERROR=unsupported_or_malformed_dm_table\n", stderr);
        return 1;
    }
    if (reduced.has_offset) printf("%" PRIu64 " %" PRIu64 " %s %" PRIu64 "\n", reduced.start, reduced.length, reduced.type, reduced.offset);
    else printf("%" PRIu64 " %" PRIu64 " %s na\n", reduced.start, reduced.length, reduced.type);
    wipe(&reduced, sizeof(reduced));
    return 0;
#endif
}
static void fixture(unsigned char *buffer, size_t capacity, const char *type, const char *params) {
    memset(buffer, 0, capacity);
    struct wire_header h; memset(&h, 0, sizeof(h));
    struct wire_target t; memset(&t, 0, sizeof(t));
    h.version[0] = 4; h.target_count = 1; h.flags = ACTIVE_FLAG | TABLE_FLAG;
    h.data_start = sizeof(h); h.data_size = (uint32_t)(sizeof(h) + sizeof(t) + strlen(params) + 1);
    t.length = UINT64_C(1875091200);
    t.next = (uint32_t)((sizeof(t) + strlen(params) + 1 + 7) & ~(size_t)7);
    memcpy(t.type, type, strlen(type) + 1);
    memcpy(buffer, &h, sizeof(h)); memcpy(buffer + sizeof(h), &t, sizeof(t));
    memcpy(buffer + sizeof(h) + sizeof(t), params, strlen(params) + 1);
}
static int self_test(void) {
    unsigned int tested = 0;
    _Alignas(8) unsigned char buffer[2048];
    struct reduced_table reduced;
    struct wire_header *h = (struct wire_header *)buffer;
    struct wire_target *t = (struct wire_target *)(buffer + sizeof(*h));
#define TEST(condition) do { tested++; if (!(condition)) { fprintf(stderr, "SELF_TEST_FAILED_LINE=%d\n", __LINE__); return 1; } } while (0)
#define MAKE(kind, params) fixture(buffer, sizeof(buffer), kind, params)
#define DECODE() decode(buffer, sizeof(buffer), &reduced)
    TEST(valid_name("userdata")); TEST(valid_name("dm-name_0"));
    TEST(!valid_name("")); TEST(!valid_name("/dev/dm-0")); TEST(!valid_name("name\n"));
    char too_long[NAME_BYTES + 1]; memset(too_long, 'x', sizeof(too_long)); too_long[sizeof(too_long)-1] = 0;
    TEST(!valid_name(too_long));
    MAKE("linear", "253:70 128"); TEST(DECODE() && reduced.offset == 128 && reduced.has_offset);
    MAKE("linear", "/dev/block/sda65 0"); TEST(DECODE() && !reduced.offset);
    MAKE("crypt", "aes-xts-plain64 FIXTURE_KEY 0 253:70 0"); TEST(DECODE() && !reduced.offset);
    MAKE("default-key", "aes-xts-plain64 FIXTURE_KEY 0 253:70 4096 2 allow_discards wrappedkey_v0"); TEST(DECODE() && reduced.offset == 4096);
    MAKE("default-key", "AES-256-XTS FIXTURE_KEY 253:70 64 1 set_dun"); TEST(DECODE() && reduced.offset == 64);
    MAKE("ramcache", "vendor specific parameters"); TEST(DECODE() && !reduced.has_offset);
    MAKE("linear", "253:70 -1"); TEST(!DECODE());
    MAKE("linear", "253:70 18446744073709551616"); TEST(!DECODE());
    MAKE("linear", "253:70 18446744073709551615"); TEST(!DECODE());
    MAKE("linear", "253:70 0 extra"); TEST(!DECODE());
    MAKE("linear", "not-a-device 0"); TEST(!DECODE());
    MAKE("crypt", "aes KEY 0 253:70"); TEST(!DECODE());
    MAKE("crypt", "aes KEY bad 253:70 0"); TEST(!DECODE());
    MAKE("crypt", "aes KEY 0 253:70 0 2 one"); TEST(!DECODE());
    MAKE("crypt", "aes KEY 0 253:70 0 0"); TEST(DECODE());
    MAKE("default-key", "aes KEY 0 253:70 0\n"); TEST(!DECODE());
    MAKE("linear", "253:70 0"); h->target_count = 2; TEST(!DECODE());
    MAKE("linear", "253:70 0"); h->flags |= BUFFER_FULL_FLAG; TEST(!DECODE());
    MAKE("linear", "253:70 0"); h->flags |= QUERY_INACTIVE_FLAG; TEST(!DECODE());
    MAKE("linear", "253:70 0"); h->flags &= ~ACTIVE_FLAG; TEST(!DECODE());
    MAKE("linear", "253:70 0"); h->data_size = sizeof(buffer) + 1; TEST(!DECODE());
    MAKE("linear", "253:70 0"); h->data_start++; TEST(!DECODE());
    MAKE("linear", "253:70 0"); h->data_size = sizeof(*h) + sizeof(*t); TEST(!DECODE());
    MAKE("linear", "253:70 0"); t->next = 0; TEST(!DECODE());
    MAKE("linear", "253:70 0"); t->next = sizeof(buffer); TEST(!DECODE());
    MAKE("linear", "253:70 0"); t->length = 0; TEST(!DECODE());
    MAKE("linear", "253:70 0"); t->start = UINT64_MAX; TEST(!DECODE());
    MAKE("linear", "253:70 0"); t->status = -1; TEST(!DECODE());
    MAKE("linear", "253:70 0"); memset(t->type, 'x', sizeof(t->type)); TEST(!DECODE());
    MAKE("error", ""); TEST(!DECODE());
    MAKE("linear", "253:70 0"); buffer[h->data_size - 1] = 'x'; TEST(!DECODE());
    MAKE("linear", "253:70 0"); TEST(!decode(buffer, sizeof(*h)-1, &reduced));
    wipe(buffer, sizeof(buffer)); TEST(buffer[0] == 0 && buffer[sizeof(buffer)-1] == 0);
#undef MAKE
#undef DECODE
#undef TEST
    printf("SELF_TESTS=%u\nRESULT=success\n", tested);
    return 0;
}
int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--self-test")) return self_test();
    if (argc == 3 && !strcmp(argv[1], "table")) return table(argv[2]);
    fputs("Usage: dmprobe table DM_NAME | dmprobe --self-test\n", stderr);
    return 2;
}
