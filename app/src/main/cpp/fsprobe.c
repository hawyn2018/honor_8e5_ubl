/* Read-only filesystem geometry probe. No filesystem or partition mutations. */
#ifndef _WIN32
#define _GNU_SOURCE
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64
#endif

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#ifdef _WIN32
#include <io.h>
#include <malloc.h>
#define PROBE_OPEN(path) _open((path), _O_RDONLY | _O_BINARY)
#define PROBE_CLOSE(fd) _close(fd)
#else
#include <linux/fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#define PROBE_OPEN(path) open((path), O_RDONLY | O_CLOEXEC)
#define PROBE_CLOSE(fd) close(fd)
#endif

#define SUPER_BYTES 3072U
#define DIRECT_CHUNK 65536U
#define F2FS_MAGIC UINT32_C(0xf2f52010)
#define EXT4_MAGIC UINT16_C(0xef53)
#define EXT4_FEATURE_INCOMPAT_64BIT UINT32_C(0x80)

typedef struct {
    const char *type;
    uint64_t block_size;
    uint64_t block_count;
    uint64_t bytes;
    uint32_t log_blocks_per_segment;
    uint32_t segments_per_section;
} Geometry;
typedef struct { int fd, direct; uint64_t bytes; uint32_t alignment; } ProbeReader;

static uint16_t le16(const unsigned char *p) {
    return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8));
}

static uint32_t le32(const unsigned char *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint64_t le64(const unsigned char *p) {
    return (uint64_t)le32(p) | ((uint64_t)le32(p + 4) << 32);
}

static int multiply_u64(uint64_t a, uint64_t b, uint64_t *out) {
    if (b != 0 && a > UINT64_MAX / b) return 0;
    *out = a * b;
    return 1;
}

static int parse_f2fs(const unsigned char *sb, size_t length,
                      Geometry *g, const char **error) {
    if (length < 44) { *error = "F2FS_SUPER_TRUNCATED"; return 0; }
    if (le32(sb) != F2FS_MAGIC) { *error = "F2FS_MAGIC_MISMATCH"; return 0; }
    uint32_t log_block_size = le32(sb + 16);
    uint32_t log_blocks_per_segment = le32(sb + 20);
    uint32_t segments_per_section = le32(sb + 24);
    /* F2FS uses 4096-byte blocks; reject unsupported geometry conservatively. */
    if (log_block_size != 12 || log_blocks_per_segment == 0 ||
        log_blocks_per_segment > 31 || segments_per_section == 0) {
        *error = "F2FS_INVALID_GEOMETRY"; return 0;
    }
    g->type = "f2fs";
    g->block_size = UINT64_C(1) << log_block_size;
    /* F2FS superblock is packed: block_count is unaligned, at byte 36. */
    g->block_count = le64(sb + 36);
    g->log_blocks_per_segment = log_blocks_per_segment;
    g->segments_per_section = segments_per_section;
    if (g->block_count == 0 ||
        !multiply_u64(g->block_count, g->block_size, &g->bytes)) {
        *error = "F2FS_SIZE_OVERFLOW_OR_ZERO"; return 0;
    }
    uint64_t blocks_per_section;
    if (!multiply_u64(UINT64_C(1) << log_blocks_per_segment,
                      segments_per_section, &blocks_per_section) ||
        blocks_per_section > g->block_count) {
        *error = "F2FS_INVALID_SECTION_GEOMETRY"; return 0;
    }
    return 1;
}

static int parse_ext4(const unsigned char *sb, size_t length,
                      Geometry *g, const char **error) {
    if (length < 100) { *error = "EXT4_SUPER_TRUNCATED"; return 0; }
    if (le16(sb + 56) != EXT4_MAGIC) { *error = "EXT4_MAGIC_MISMATCH"; return 0; }
    uint32_t log_block_size = le32(sb + 24);
    if (log_block_size > 6) { *error = "EXT4_INVALID_BLOCK_SIZE"; return 0; }
    uint64_t count = le32(sb + 4);
    if ((le32(sb + 96) & EXT4_FEATURE_INCOMPAT_64BIT) != 0) {
        if (length < 0x154) { *error = "EXT4_SUPER_TRUNCATED"; return 0; }
        count |= (uint64_t)le32(sb + 0x150) << 32;
    }
    g->type = "ext4";
    g->block_size = UINT64_C(1024) << log_block_size;
    g->block_count = count;
    g->log_blocks_per_segment = 0;
    g->segments_per_section = 0;
    if (count == 0 || !multiply_u64(count, g->block_size, &g->bytes)) {
        *error = "EXT4_SIZE_OVERFLOW_OR_ZERO"; return 0;
    }
    return 1;
}

static int parse_geometry(const unsigned char *primary, size_t primary_length,
                          const unsigned char *backup, size_t backup_length,
                          uint64_t device_bytes, uint32_t sector_size,
                          Geometry *g, const char **error) {
    if (primary_length < 4) { *error = "SUPER_TRUNCATED"; return 0; }
    if (le32(primary) == F2FS_MAGIC) {
        Geometry second;
        if (!parse_f2fs(primary, primary_length, g, error) ||
            !parse_f2fs(backup, backup_length, &second, error)) return 0;
        if (g->block_size != second.block_size ||
            g->block_count != second.block_count ||
            g->log_blocks_per_segment != second.log_blocks_per_segment ||
            g->segments_per_section != second.segments_per_section) {
            *error = "F2FS_SUPER_GEOMETRY_MISMATCH"; return 0;
        }
        if (primary_length != backup_length || memcmp(primary, backup, primary_length)) {
            *error = "F2FS_SUPER_PAIR_MISMATCH"; return 0;
        }
    } else if (primary_length >= 58 && le16(primary + 56) == EXT4_MAGIC) {
        if (!parse_ext4(primary, primary_length, g, error)) return 0;
    } else {
        *error = "UNKNOWN_FILESYSTEM"; return 0;
    }
    if (sector_size < 512 || sector_size > 65536 ||
        (sector_size & (sector_size - 1)) != 0 ||
        g->block_size < sector_size || g->block_size % sector_size != 0) {
        *error = "SECTOR_GEOMETRY_MISMATCH"; return 0;
    }
    if (device_bytes == 0 || g->bytes > device_bytes) {
        *error = "FILESYSTEM_EXCEEDS_DEVICE"; return 0;
    }
    return 1;
}

static int read_buffered_at(int fd, unsigned char *out, size_t length, uint64_t offset) {
    size_t done = 0;
    while (done < length) {
#ifdef _WIN32
        if (offset + done > INT64_MAX ||
            _lseeki64(fd, (int64_t)(offset + done), SEEK_SET) < 0) return 0;
        int n = _read(fd, out + done, (unsigned int)(length - done));
#else
        if (offset + done > INT64_MAX) return 0;
        ssize_t n = pread(fd, out + done, length - done, (off_t)(offset + done));
#endif
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        done += (size_t)n;
    }
    return 1;
}

typedef int64_t (*probe_pread_fn)(int, void *, size_t, uint64_t, void *);
static int64_t probe_system_pread(int fd, void *buffer, size_t length, uint64_t offset, void *opaque) {
    (void)opaque;
#ifdef _WIN32
    if (_lseeki64(fd, (int64_t)offset, SEEK_SET) < 0) return -1;
    return _read(fd, buffer, (unsigned int)length);
#else
    return pread(fd, buffer, length, (off_t)offset);
#endif
}
static int probe_aligned_slice(const ProbeReader *reader, unsigned char *out, size_t length, uint64_t offset,
                               probe_pread_fn read_fn, void *opaque) {
    uint32_t alignment = reader->alignment;
    if (!length || alignment < 4096 || alignment > DIRECT_CHUNK || (alignment & (alignment - 1)) ||
        offset > INT64_MAX || length > (uint64_t)INT64_MAX - offset || offset + length > reader->bytes ||
        offset + length > UINT64_MAX - (alignment - 1)) { errno = EINVAL; return 0; }
    uint64_t end = offset + length;
    uint64_t aligned_end = (end + alignment - 1) / alignment * alignment;
    if (aligned_end > reader->bytes || aligned_end > INT64_MAX) { errno = EINVAL; return 0; }
    uint64_t at = offset / alignment * alignment;
    void *bounce = NULL;
#ifdef _WIN32
    bounce = _aligned_malloc(DIRECT_CHUNK, alignment);
    if (!bounce) { errno = ENOMEM; return 0; }
#else
    int allocated = posix_memalign(&bounce, alignment, DIRECT_CHUNK);
    if (allocated) { errno = allocated; return 0; }
#endif
    int success = 1;
    while (at < aligned_end) {
        size_t count = aligned_end - at > DIRECT_CHUNK ? DIRECT_CHUNK : (size_t)(aligned_end - at);
        int64_t got;
        do { got = read_fn(reader->fd, bounce, count, at, opaque); } while (got < 0 && errno == EINTR);
        if (got != (int64_t)count) { if (got >= 0) errno = EIO; success = 0; break; }
        uint64_t first = at > offset ? at : offset;
        uint64_t last = at + count < end ? at + count : end;
        if (last > first) memcpy(out + (size_t)(first - offset), (unsigned char *)bounce + (size_t)(first - at), (size_t)(last - first));
        at += count;
    }
    int saved = errno;
#ifdef _WIN32
    _aligned_free(bounce);
#else
    free(bounce);
#endif
    errno = saved;
    return success;
}
static int read_at(const ProbeReader *reader, unsigned char *out, size_t length, uint64_t offset) {
    if (reader->direct) return probe_aligned_slice(reader, out, length, offset, probe_system_pread, NULL);
    return read_buffered_at(reader->fd, out, length, offset);
}

static int probe(const char *path) {
    const char *error = NULL;
    int fd = PROBE_OPEN(path);
    if (fd < 0) { error = "OPEN_FAILED"; goto fail; }
    uint64_t device_bytes = 0;
    uint32_t sector_size = 512;
    int direct = 0;
#ifdef _WIN32
    struct _stat64 st;
    if (_fstat64(fd, &st) != 0) { error = "STAT_FAILED"; goto close_fail; }
    if ((st.st_mode & _S_IFMT) != _S_IFREG || st.st_size <= 0) {
        error = "REGULAR_FIXTURE_REQUIRED_ON_HOST"; goto close_fail;
    }
    device_bytes = (uint64_t)st.st_size;
#else
    struct stat st;
    if (fstat(fd, &st) != 0) { error = "STAT_FAILED"; goto close_fail; }
    if (S_ISBLK(st.st_mode)) {
        int logical_sector_size = 0;
        /* Both ioctls query geometry and do not modify device state. */
        if (ioctl(fd, BLKGETSIZE64, &device_bytes) != 0 ||
            ioctl(fd, BLKSSZGET, &logical_sector_size) != 0 ||
            logical_sector_size <= 0) {
            error = "BLOCK_GEOMETRY_QUERY_FAILED"; goto close_fail;
        }
        sector_size = (uint32_t)logical_sector_size;
        if (sector_size < 512 || sector_size > DIRECT_CHUNK || (sector_size & (sector_size - 1))) {
            error = "BLOCK_SECTOR_ALIGNMENT_INVALID"; goto close_fail;
        }
        int direct_fd = open(path, O_RDONLY | O_DIRECT | O_CLOEXEC);
        if (direct_fd < 0) { error = "DIRECT_OPEN_FAILED_NO_BUFFERED_FALLBACK"; goto close_fail; }
        struct stat direct_st;
        int direct_flags = fcntl(direct_fd, F_GETFL);
        if (fstat(direct_fd, &direct_st) || !S_ISBLK(direct_st.st_mode) || direct_st.st_rdev != st.st_rdev ||
            direct_flags < 0 || (direct_flags & O_DIRECT) != O_DIRECT) {
            close(direct_fd); error = "DIRECT_IDENTITY_OR_MODE_MISMATCH"; goto close_fail;
        }
        close(fd); fd = direct_fd; direct = 1;
    } else if (S_ISREG(st.st_mode) && st.st_size > 0) {
        device_bytes = (uint64_t)st.st_size;
    } else {
        error = "BLOCK_DEVICE_OR_REGULAR_FIXTURE_REQUIRED"; goto close_fail;
    }
#endif
    ProbeReader reader = { fd, direct, device_bytes, sector_size > 4096 ? sector_size : 4096 };
    unsigned char primary[SUPER_BYTES], backup[SUPER_BYTES];
    if (!read_at(&reader, primary, sizeof(primary), 1024)) {
        error = direct ? "DIRECT_PRIMARY_READ_FAILED_NO_BUFFERED_FALLBACK" : "PRIMARY_SUPER_READ_FAILED"; goto close_fail;
    }
    size_t backup_length = 0;
    if (le32(primary) == F2FS_MAGIC) {
        if (!read_at(&reader, backup, sizeof(backup), 5120)) {
            error = direct ? "DIRECT_BACKUP_READ_FAILED_NO_BUFFERED_FALLBACK" : "F2FS_BACKUP_SUPER_READ_FAILED"; goto close_fail;
        }
        backup_length = sizeof(backup);
    }
    Geometry g;
    if (!parse_geometry(primary, sizeof(primary), backup, backup_length,
                        device_bytes, sector_size, &g, &error)) goto close_fail;
    PROBE_CLOSE(fd);
    printf("DEVICE_BYTES=%" PRIu64 "\n", device_bytes);
    printf("DEVICE_SECTOR_SIZE=%" PRIu32 "\n", sector_size);
    printf("FS_READ_MODE=%s\n", direct ? "direct" : "fixture-buffered");
    printf("FS_TYPE=%s\n", g.type);
    printf("FS_BYTES=%" PRIu64 "\n", g.bytes);
    printf("FS_BLOCK_SIZE=%" PRIu64 "\n", g.block_size);
    printf("FS_BLOCK_COUNT=%" PRIu64 "\n", g.block_count);
    if (strcmp(g.type, "f2fs") == 0) {
        printf("FS_LOG_BLOCKS_PER_SEGMENT=%" PRIu32 "\n", g.log_blocks_per_segment);
        printf("FS_SEGMENTS_PER_SECTION=%" PRIu32 "\n", g.segments_per_section);
    }
    puts("FS_MATCH=1\nRESULT=success");
    return 0;
close_fail:
    PROBE_CLOSE(fd);
fail:
    printf("ERROR=%s\nFS_MATCH=0\n", error);
    return 1;
}

static void put32(unsigned char *p, uint32_t value) {
    p[0] = (unsigned char)value; p[1] = (unsigned char)(value >> 8);
    p[2] = (unsigned char)(value >> 16); p[3] = (unsigned char)(value >> 24);
}

static void put64(unsigned char *p, uint64_t value) {
    put32(p, (uint32_t)value); put32(p + 4, (uint32_t)(value >> 32));
}

static void fixture_f2fs(unsigned char *p, uint64_t blocks) {
    memset(p, 0, SUPER_BYTES);
    put32(p, F2FS_MAGIC); put32(p + 16, 12); put32(p + 20, 9);
    put32(p + 24, 1); put64(p + 36, blocks);
}

static void fixture_ext4(unsigned char *p, uint64_t blocks, int has_64bit) {
    memset(p, 0, SUPER_BYTES);
    p[56] = 0x53; p[57] = 0xef;
    put32(p + 24, 2); put32(p + 4, (uint32_t)blocks);
    put32(p + 0x150, (uint32_t)(blocks >> 32));
    put32(p + 96, has_64bit ? EXT4_FEATURE_INCOMPAT_64BIT : 0);
}

static int self_test(void) {
    unsigned char a[SUPER_BYTES], b[SUPER_BYTES];
    Geometry g;
    const char *error = NULL;
    unsigned int tests = 0, failed = 0;
#define CHECK(condition, name) do { \
    ++tests; \
    if (!(condition)) { ++failed; fprintf(stderr, "SELF_TEST_FAILED=%s\n", name); } \
} while (0)
#define PARSE(bytes) parse_geometry(a, sizeof(a), b, sizeof(b), (bytes), 512, &g, &error)
    uint64_t count64 = (UINT64_C(1) << 32) + 1024;
    fixture_f2fs(a, count64); memcpy(b, a, sizeof(b));
    CHECK(PARSE(count64 * 4096) && g.block_count == count64 &&
          g.bytes == count64 * 4096, "f2fs_unaligned_u64");
    put64(b + 36, count64 - 1);
    CHECK(!PARSE(UINT64_MAX) && strcmp(error, "F2FS_SUPER_GEOMETRY_MISMATCH") == 0,
          "f2fs_backup_count_mismatch");
    memcpy(b, a, sizeof(b)); put32(b + 24, 2);
    CHECK(!PARSE(UINT64_MAX), "f2fs_backup_section_mismatch");
    memcpy(b, a, sizeof(b)); put32(b, 0);
    CHECK(!PARSE(UINT64_MAX), "f2fs_backup_magic_mismatch");
    fixture_f2fs(a, UINT64_MAX); memcpy(b, a, sizeof(b));
    CHECK(!PARSE(UINT64_MAX), "f2fs_byte_count_overflow");
    fixture_f2fs(a, 0); memcpy(b, a, sizeof(b));
    CHECK(!PARSE(UINT64_MAX), "f2fs_zero_blocks");
    fixture_f2fs(a, 1024); memcpy(b, a, sizeof(b));
    CHECK(!PARSE(1024 * 4096 - 1), "f2fs_exceeds_device");
    put32(a + 16, 63); memcpy(b, a, sizeof(b));
    CHECK(!PARSE(UINT64_MAX), "f2fs_bad_block_shift");
    fixture_f2fs(a, 1024); put32(a + 20, 64); memcpy(b, a, sizeof(b));
    CHECK(!PARSE(UINT64_MAX), "f2fs_bad_segment_shift");
    fixture_ext4(a, count64, 1);
    CHECK(PARSE(count64 * 4096) && g.block_count == count64 &&
          g.block_size == 4096, "ext4_64bit_count");
    fixture_ext4(a, count64, 0);
    CHECK(PARSE(UINT64_MAX) && g.block_count == 1024, "ext4_high_ignored_without_feature");
    fixture_ext4(a, UINT64_MAX, 1);
    CHECK(!PARSE(UINT64_MAX), "ext4_byte_count_overflow");
    fixture_ext4(a, 1024, 0); put32(a + 24, 32);
    CHECK(!PARSE(UINT64_MAX), "ext4_bad_block_shift");
    fixture_ext4(a, 0, 0);
    CHECK(!PARSE(UINT64_MAX), "ext4_zero_blocks");
    fixture_ext4(a, 1024, 0);
    CHECK(!PARSE(1024 * 4096 - 1), "ext4_exceeds_device");
    fixture_ext4(a, count64, 1);
    CHECK(!parse_ext4(a, 100, &g, &error), "ext4_truncated_high_count");
    memset(a, 0, sizeof(a));
    CHECK(!PARSE(UINT64_MAX), "unknown_filesystem");
    CHECK(!parse_f2fs(a, 40, &g, &error), "f2fs_truncated");
    fixture_ext4(a, 1024, 0);
    CHECK(!parse_geometry(a, sizeof(a), b, sizeof(b), UINT64_MAX, 768, &g, &error),
          "invalid_sector_size");
#undef PARSE
#undef CHECK
    printf("SELF_TEST_CASES=%u\nSELF_TEST_RESULT=%s\n", tests, failed ? "FAIL" : "PASS");
    return failed ? 1 : 0;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--self-test") == 0) return self_test();
    if (argc != 2 || argv[1][0] == '-') {
        puts("ERROR=USAGE_fsprobe_BLOCK_DEVICE_OR_FIXTURE\nFS_MATCH=0");
        return 1;
    }
    return probe(argv[1]);
}
