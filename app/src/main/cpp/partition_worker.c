/* BootForge bounded storage worker. No GPT, dm-table, format or reboot APIs.
 * F2FS disk layout and resize rounding are checked against Linux v6.12:
 * include/linux/f2fs_fs.h and fs/f2fs/{gc,checkpoint}.c.
 * An ioctl success verifies this filesystem only, not vendor cache coherence.
 * Host Windows builds expose self-test and explicit regular-file fixtures only.
 */
#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#ifdef _WIN32
#include <io.h>
#include <malloc.h>
#include <windows.h>
#define close _close
#define open _open
#define O_CLOEXEC 0
#define O_BINARY _O_BINARY
typedef struct _stat64 file_stat;
#define fd_stat _fstat64
#define IS_REG(m) (((m) & _S_IFMT) == _S_IFREG)
#else
#include <unistd.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/sysmacros.h>
#include <linux/fs.h>
#include <linux/f2fs.h>
#include <linux/magic.h>
#define O_BINARY 0
typedef struct stat file_stat;
#define fd_stat fstat
#define IS_REG(m) S_ISREG(m)
_Static_assert(sizeof(off_t) >= 8, "64-bit offsets required");
#endif

#define FS_BLOCK UINT64_C(4096)
#define SB_BYTES 3072U
#define F2FS_MAGIC UINT32_C(0xf2f52010)
#define IO_CHUNK 65536U
#define CP_BAD_FLAGS UINT32_C(0x7818)

struct super_info {
    unsigned char raw[SB_BYTES];
    uint64_t blocks, section_blocks, section_bytes;
    uint32_t sections, segments, main_segments, segs_per_sec, cp_block, main_block;
};
struct resize_plan {
    uint64_t maximum, margin, allowed_blocks, argument, expected, removed_sections;
};
struct checkpoint_info {
    uint64_t version, last_version, user_blocks, valid_blocks, pack_block;
    uint32_t flags, pack_blocks, checksum_offset, first_stored, first_computed, last_stored, last_computed;
    bool first_read, last_read, valid;
    int read_errno;
    unsigned index;
};
struct device_file { int fd, direct_fd; uint64_t bytes; uint32_t sector, direct_alignment; bool fixture; file_stat st; };

static int failure(const char *message) {
    fprintf(stderr, "ERROR=%s\nRESULT=error\n", message);
    return 1;
}
static int system_failure(const char *message) {
    int saved = errno;
    fprintf(stderr, "ERROR=%s\nERRNO=%d\nERRNO_TEXT=%s\nRESULT=error\n", message, saved, strerror(saved));
    return 1;
}
static bool add64(uint64_t a, uint64_t b, uint64_t *out) {
    if (UINT64_MAX - a < b) return false;
    *out = a + b; return true;
}
static bool mul64(uint64_t a, uint64_t b, uint64_t *out) {
    if (a && b > UINT64_MAX / a) return false;
    *out = a * b; return true;
}
static bool parse64(const char *text, uint64_t *value) {
    uint64_t n = 0;
    if (!text || !*text) return false;
    for (const unsigned char *p = (const unsigned char *)text; *p; p++) {
        if (*p < '0' || *p > '9' || !mul64(n, 10, &n) || !add64(n, *p - '0', &n)) return false;
    }
    *value = n; return true;
}
static uint32_t le32(const unsigned char *p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static uint64_t le64(const unsigned char *p) { return le32(p) | (uint64_t)le32(p + 4) << 32; }
static uint32_t disk_crc(uint32_t crc, const unsigned char *p, size_t size) {
    for (size_t i = 0; i < size; i++) {
        crc ^= p[i];
        for (unsigned j = 0; j < 8; j++) crc = (crc >> 1) ^ ((crc & 1) ? UINT32_C(0xedb88320) : 0);
    }
    return crc;
}
static bool zero_bytes(const unsigned char *p, size_t size) {
    for (size_t i = 0; i < size; i++) if (p[i]) return false;
    return true;
}
static int read_at(int fd, void *buffer, size_t size, uint64_t offset) {
    unsigned char *p = buffer;
    uint64_t end;
    if (!add64(offset, size, &end) || end > INT64_MAX) { errno = EOVERFLOW; return -1; }
    while (size) {
#ifdef _WIN32
        if (_lseeki64(fd, (__int64)offset, SEEK_SET) < 0) return -1;
        int n = _read(fd, p, (unsigned)size);
#else
        ssize_t n = pread(fd, p, size, (off_t)offset);
#endif
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { if (!n) errno = EIO; return -1; }
        p += n; size -= (size_t)n; offset += (uint64_t)n;
    }
    return 0;
}

/* A direct descriptor is never sent an unaligned pointer, offset or length.
 * The 1024/5120-byte superblock offsets are slices of complete 4 KiB pages.
 * A short/error direct read is an error, never a request to retry buffered I/O. */
typedef int64_t (*aligned_pread_fn)(int, void *, size_t, uint64_t, void *);
static int64_t aligned_system_pread(int fd, void *buffer, size_t size, uint64_t offset, void *opaque) {
    (void)opaque;
#ifdef _WIN32
    if (_lseeki64(fd, (__int64)offset, SEEK_SET) < 0) return -1;
    return _read(fd, buffer, (unsigned)size);
#else
    return pread(fd, buffer, size, (off_t)offset);
#endif
}
static int aligned_read_slice(int fd, uint64_t bytes, uint32_t alignment, void *output,
                              size_t size, uint64_t offset, aligned_pread_fn reader, void *opaque) {
    uint64_t end, aligned_end;
    if (!size || alignment < 512 || alignment > IO_CHUNK || (alignment & (alignment - 1)) ||
        !add64(offset, size, &end) || end > bytes || end > INT64_MAX ||
        !add64(end, alignment - 1, &aligned_end)) { errno = EINVAL; return -1; }
    aligned_end -= aligned_end % alignment;
    if (aligned_end > bytes || aligned_end > INT64_MAX) { errno = EINVAL; return -1; }
    uint64_t at = offset - offset % alignment;
    void *bounce = NULL;
#ifdef _WIN32
    bounce = _aligned_malloc(IO_CHUNK, alignment < 4096 ? 4096 : alignment);
    if (!bounce) { errno = ENOMEM; return -1; }
#else
    int allocated = posix_memalign(&bounce, alignment < 4096 ? 4096 : alignment, IO_CHUNK);
    if (allocated) { errno = allocated; return -1; }
#endif
    int rc = 0;
    while (at < aligned_end) {
        size_t count = aligned_end - at > IO_CHUNK ? IO_CHUNK : (size_t)(aligned_end - at);
        int64_t got;
        do { got = reader(fd, bounce, count, at, opaque); } while (got < 0 && errno == EINTR);
        if (got != (int64_t)count) { if (got >= 0) errno = EIO; rc = -1; break; }
        uint64_t copy_start = at > offset ? at : offset;
        uint64_t copy_end = at + count < end ? at + count : end;
        if (copy_end > copy_start) memcpy((unsigned char *)output + (size_t)(copy_start - offset),
            (unsigned char *)bounce + (size_t)(copy_start - at), (size_t)(copy_end - copy_start));
        at += count;
    }
    int saved = errno;
#ifdef _WIN32
    _aligned_free(bounce);
#else
    free(bounce);
#endif
    errno = saved;
    return rc;
}
static int read_target(const struct device_file *dev, void *buffer, size_t size, uint64_t offset) {
    if (dev->fixture) return read_at(dev->fd, buffer, size, offset);
    if (dev->direct_fd < 0) { errno = EBADF; return -1; }
    uint32_t alignment = dev->direct_alignment < 4096 ? 4096 : dev->direct_alignment;
    uint64_t end, padded;
    /* Prefer full 4 KiB pages. A device whose final sector is not a whole page
     * still has a legal direct-readable tail at its queried logical sector. */
    if (dev->sector < alignment && add64(offset, size, &end) && add64(end, alignment - 1, &padded) &&
        padded - padded % alignment > dev->bytes) alignment = dev->sector;
    return aligned_read_slice(dev->direct_fd, dev->bytes, alignment, buffer, size, offset,
        aligned_system_pread, NULL);
}
#if !defined(_WIN32) || defined(BOOTFORGE_F2FS_FIXTURE_TEST)
static int read_f2fs(const struct device_file *dev, void *buffer, size_t size, uint64_t offset) {
    if (dev->direct_fd < 0 || (!dev->fixture && !dev->direct_alignment)) { errno = EBADF; return -1; }
    return aligned_read_slice(dev->direct_fd, dev->bytes, dev->direct_alignment, buffer, size, offset,
        aligned_system_pread, NULL);
}
#endif
static int write_at(int fd, const void *buffer, size_t size, uint64_t offset) {
    const unsigned char *p = buffer;
    uint64_t end;
    if (!add64(offset, size, &end) || end > INT64_MAX) { errno = EOVERFLOW; return -1; }
    while (size) {
#ifdef _WIN32
        if (_lseeki64(fd, (__int64)offset, SEEK_SET) < 0) return -1;
        int n = _write(fd, p, (unsigned)size);
#else
        ssize_t n = pwrite(fd, p, size, (off_t)offset);
#endif
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { if (!n) errno = EIO; return -1; }
        p += n; size -= (size_t)n; offset += (uint64_t)n;
    }
    return 0;
}
static int flush_fd(int fd) {
#ifdef _WIN32
    return _commit(fd);
#else
    int rc;
    do { rc = fsync(fd); } while (rc < 0 && errno == EINTR);
    return rc;
#endif
}

static int open_device(const char *path, bool execute, bool fixture, struct device_file *dev) {
    memset(dev, 0, sizeof(*dev)); dev->fd = -1; dev->direct_fd = -1; dev->fixture = fixture;
    int fd = open(path, (execute ? O_RDWR : O_RDONLY) | O_CLOEXEC | O_BINARY);
    if (fd < 0) return system_failure("target open failed");
    dev->fd = fd;
    if (fd_stat(fd, &dev->st)) return system_failure("target fstat failed");
    if (fixture) {
        if (!IS_REG(dev->st.st_mode) || dev->st.st_size <= 0) return failure("fixture target must be an existing nonempty regular file");
        dev->bytes = (uint64_t)dev->st.st_size; dev->sector = 512;
    } else {
#ifdef _WIN32
        return failure("host build accepts regular-file fixtures only");
#else
        if (!S_ISBLK(dev->st.st_mode)) return failure("real target must be a block device; regular files require --fixture");
        unsigned int sector = 0;
        if (ioctl(fd, BLKGETSIZE64, &dev->bytes) < 0 || ioctl(fd, BLKSSZGET, &sector) < 0) return system_failure("block capacity or logical sector query failed");
        if (sector != 512 && sector != 4096) return failure("block logical sector must be 512 or 4096 bytes");
        dev->sector = sector;
        if (execute) {
            int read_only = 1;
            if (geteuid() != 0) return failure("block writes require root");
            if (ioctl(fd, BLKROGET, &read_only) < 0) return system_failure("block read-only query failed");
            if (read_only) return failure("block target is read-only");
        }
#endif
    }
    if (!dev->bytes || dev->bytes > INT64_MAX || dev->bytes % dev->sector) return failure("target capacity is invalid or not sector aligned");
#ifndef _WIN32
    if (flock(fd, (execute ? LOCK_EX : LOCK_SH) | LOCK_NB) < 0) return system_failure("target advisory lock failed");
#endif
    return 0;
}
static int open_direct_reader(const char *path, struct device_file *dev, uint32_t minimum_alignment) {
    if (dev->fixture) return 0;
#ifdef _WIN32
    (void)path; (void)minimum_alignment;
    return failure("host build accepts regular-file fixtures only");
#else
    file_stat direct_st;
    dev->direct_alignment = dev->sector > minimum_alignment ? dev->sector : minimum_alignment;
    dev->direct_fd = open(path, O_RDONLY | O_DIRECT | O_CLOEXEC);
    if (dev->direct_fd < 0) return system_failure("O_RDONLY|O_DIRECT open failed; buffered fallback is prohibited");
    if (fd_stat(dev->direct_fd, &direct_st) < 0) return system_failure("direct descriptor identity query failed");
    int flags = fcntl(dev->direct_fd, F_GETFL);
    if (!S_ISBLK(direct_st.st_mode) || direct_st.st_rdev != dev->st.st_rdev || flags < 0 ||
        (flags & O_DIRECT) != O_DIRECT)
        return failure("direct descriptor identity or O_DIRECT mode rejected");
    return 0;
#endif
}
static bool range_valid(const struct device_file *dev, uint64_t offset, uint64_t length, bool aligned_length) {
    uint64_t end;
    return length && offset % dev->sector == 0 && (!aligned_length || length % dev->sector == 0) &&
        add64(offset, length, &end) && end <= dev->bytes && end <= INT64_MAX;
}
static bool same_file(int left, int right, const file_stat *a, const file_stat *b) {
#ifdef _WIN32
    BY_HANDLE_FILE_INFORMATION x, y;
    (void)a; (void)b;
    if (!GetFileInformationByHandle((HANDLE)_get_osfhandle(left), &x) ||
        !GetFileInformationByHandle((HANDLE)_get_osfhandle(right), &y)) return true;
    return x.dwVolumeSerialNumber == y.dwVolumeSerialNumber &&
        x.nFileIndexHigh == y.nFileIndexHigh && x.nFileIndexLow == y.nFileIndexLow;
#else
    (void)left; (void)right;
    return a->st_dev == b->st_dev && a->st_ino == b->st_ino;
#endif
}
static int write_range(const char *target, uint64_t offset, uint64_t capacity, const char *image, bool execute, bool fixture) {
    struct device_file dev;
    int image_fd = -1, rc = open_device(target, execute, fixture, &dev);
    file_stat image_st, image_after;
    if (rc) goto out;
    if (!range_valid(&dev, offset, capacity, true)) { rc = failure("write capacity or offset is unaligned, overflowing or outside target"); goto out; }
    image_fd = open(image, O_RDONLY | O_CLOEXEC | O_BINARY);
    if (image_fd < 0) { rc = system_failure("image open failed"); goto out; }
    if (fd_stat(image_fd, &image_st) < 0) { rc = system_failure("image fstat failed"); goto out; }
    if (!IS_REG(image_st.st_mode) || image_st.st_size <= 0 || (uint64_t)image_st.st_size > capacity) { rc = failure("image must be a nonempty regular file within bounded capacity"); goto out; }
    if (same_file(dev.fd, image_fd, &dev.st, &image_st)) { rc = failure("image aliases target"); goto out; }
    uint64_t image_bytes = (uint64_t)image_st.st_size;
    printf("OFFSET=%" PRIu64 "\nCAPACITY=%" PRIu64 "\nIMAGE_BYTES=%" PRIu64 "\nLOGICAL_SECTOR=%" PRIu32 "\nFIXTURE=%d\n", offset, capacity, image_bytes, dev.sector, fixture ? 1 : 0);
    if (!execute) { puts("RESULT=planned"); goto out; }
    if ((rc = open_direct_reader(target, &dev, dev.sector))) goto out;
    printf("READ_MODE=%s\n", fixture ? "fixture-buffered" : "direct");
    /* All full writes are sector aligned. Preserve the unused bytes of the
     * last sector instead of padding or truncating the target. */
    unsigned char buffer[IO_CHUNK], compare[IO_CHUNK];
    uint64_t done = 0;
    while (done < image_bytes) {
        uint64_t remain = image_bytes - done;
        size_t count = remain > IO_CHUNK ? IO_CHUNK : (size_t)remain;
        size_t written = count;
        if (count % dev.sector) {
            written = (count / dev.sector + 1) * dev.sector;
            if (read_target(&dev, buffer, written, offset + done) < 0) { rc = system_failure("last sector direct preservation read failed"); goto out; }
        }
        if (read_at(image_fd, buffer, count, done) < 0 || write_at(dev.fd, buffer, written, offset + done) < 0) { rc = system_failure("bounded image write failed"); goto out; }
        done += count;
    }
    if (flush_fd(dev.fd) < 0) { rc = system_failure("target fsync or device flush failed"); goto out; }
    for (done = 0; done < image_bytes;) {
        size_t count = image_bytes - done > IO_CHUNK ? IO_CHUNK : (size_t)(image_bytes - done);
        if (read_at(image_fd, buffer, count, done) < 0 || read_target(&dev, compare, count, offset + done) < 0) { rc = system_failure("direct write readback failed"); goto out; }
        if (memcmp(buffer, compare, count)) { rc = failure("write readback differs from image"); goto out; }
        done += count;
    }
    if (fd_stat(image_fd, &image_after) < 0 || image_after.st_size != image_st.st_size || image_after.st_mtime != image_st.st_mtime) { rc = failure("image changed during write"); goto out; }
    printf("BYTES_WRITTEN=%" PRIu64 "\nREADBACK=match\nFLUSH=fsync\nRESULT=success\n", image_bytes);
out:
    if (image_fd >= 0) close(image_fd);
    if (dev.direct_fd >= 0) close(dev.direct_fd);
    if (dev.fd >= 0 && close(dev.fd) < 0 && !rc) rc = system_failure("target close failed");
    return rc;
}
static int read_range(const char *target, uint64_t offset, uint64_t length, bool fixture) {
    struct device_file dev;
    int rc = open_device(target, false, fixture, &dev);
    if (rc) goto out;
    if (!range_valid(&dev, offset, length, false)) { rc = failure("read offset or length is unaligned, overflowing or outside target"); goto out; }
    if ((rc = open_direct_reader(target, &dev, dev.sector))) goto out;
    fprintf(stderr, "READ_MODE=%s\n", fixture ? "fixture-buffered" : "direct");
#ifdef _WIN32
    if (_setmode(_fileno(stdout), _O_BINARY) < 0) { rc = system_failure("binary stdout setup failed"); goto out; }
#endif
    unsigned char buffer[IO_CHUNK];
    for (uint64_t done = 0; done < length;) {
        size_t count = length - done > IO_CHUNK ? IO_CHUNK : (size_t)(length - done);
        if (read_target(&dev, buffer, count, offset + done) < 0) { rc = system_failure("bounded direct read failed"); goto out; }
        if (fwrite(buffer, 1, count, stdout) != count) { rc = system_failure("binary stdout write failed"); goto out; }
        done += count;
    }
    if (fflush(stdout)) rc = system_failure("binary stdout flush failed");
out:
    if (dev.direct_fd >= 0) close(dev.direct_fd);
    if (dev.fd >= 0) close(dev.fd);
    return rc;
}

static bool decode_super(const unsigned char raw[SB_BYTES], uint64_t device_bytes, struct super_info *s) {
    uint64_t segments_sum = 0, segment_end, main_end, bytes;
    if (le32(raw) != F2FS_MAGIC || le32(raw + 16) != 12 || le32(raw + 20) != 9) return false;
    uint32_t log_sector = le32(raw + 8), log_spb = le32(raw + 12);
    if (log_sector < 9 || log_sector > 12 || log_spb > 3 || log_sector + log_spb != 12) return false;
    uint32_t features = le32(raw + 2180);
    if ((features & (UINT32_C(0x2) | UINT32_C(0x4000))) || !zero_bytes(raw + 2201, 8 * 68) ||
        !zero_bytes(raw + 2762, 32 + 16) || zero_bytes(raw + 108, 16)) return false;
    if (features & UINT32_C(0x800)) {
        if (le32(raw + 32) != 3068 || disk_crc(F2FS_MAGIC, raw, 3068) != le32(raw + 3068)) return false;
    }
    memset(s, 0, sizeof(*s)); memcpy(s->raw, raw, SB_BYTES);
    s->blocks = le64(raw + 36); s->sections = le32(raw + 44);
    s->segments = le32(raw + 48); s->main_segments = le32(raw + 68);
    s->segs_per_sec = le32(raw + 24); s->cp_block = le32(raw + 76); s->main_block = le32(raw + 92);
    if (!s->blocks || !s->sections || !s->segments || !s->main_segments || !s->segs_per_sec || !le32(raw + 28)) return false;
    if (!mul64(512, s->segs_per_sec, &s->section_blocks) || s->section_blocks > UINT32_MAX ||
        !mul64(s->section_blocks, FS_BLOCK, &s->section_bytes) ||
        !mul64(s->blocks, FS_BLOCK, &bytes) || bytes > device_bytes || bytes > INT64_MAX) return false;
    for (size_t off = 52; off <= 68; off += 4) if (!add64(segments_sum, le32(raw + off), &segments_sum)) return false;
    if (segments_sum != s->segments || (uint64_t)s->sections * s->segs_per_sec != s->main_segments) return false;
    if (!mul64(s->segments, 512, &segment_end) || !add64(segment_end, le32(raw + 72), &segment_end) || segment_end > s->blocks) return false;
    if (!mul64(s->main_segments, 512, &main_end) || !add64(main_end, s->main_block, &main_end) || main_end > s->blocks) return false;
    if (s->cp_block != le32(raw + 72) || s->cp_block < 2 || le32(raw + 52) != 2 ||
        !le32(raw + 56) || le32(raw + 56) % 2 || !le32(raw + 60) || le32(raw + 60) % 2 ||
        !le32(raw + 64) || s->blocks - segment_end >= 512) return false;
    uint64_t boundary = s->cp_block;
    for (size_t field = 52, address = 80; field <= 64; field += 4, address += 4) {
        uint64_t span;
        if (!mul64(le32(raw + field), 512, &span) || !add64(boundary, span, &boundary) ||
            boundary != le32(raw + address)) return false;
    }
    return true;
}
static bool build_resize_plan(const struct super_info *s, uint64_t maximum, struct resize_plan *p) {
    memset(p, 0, sizeof(*p)); p->maximum = maximum; p->margin = s->section_bytes;
    if (!maximum || maximum % FS_BLOCK || maximum <= p->margin || !s->section_blocks) return false;
    p->allowed_blocks = (maximum - p->margin) / FS_BLOCK;
    if (s->blocks <= p->allowed_blocks) { p->expected = s->blocks; return true; }
    uint64_t diff = s->blocks - p->allowed_blocks, removed;
    p->removed_sections = diff / s->section_blocks + (diff % s->section_blocks ? 1 : 0);
    if (!mul64(p->removed_sections, s->section_blocks, &removed) || removed >= s->blocks || p->removed_sections >= s->sections) return false;
    p->expected = s->blocks - removed;
    p->argument = p->expected / s->section_blocks * s->section_blocks;
    if (!p->argument || p->argument >= s->blocks || p->expected > p->allowed_blocks ||
        (s->blocks - p->argument) / s->section_blocks != p->removed_sections) return false;
    uint64_t expected_bytes, expected_main_end;
    return mul64(p->expected, FS_BLOCK, &expected_bytes) && expected_bytes <= maximum - p->margin &&
        mul64((uint64_t)s->main_segments - p->removed_sections * s->segs_per_sec, 512, &expected_main_end) &&
        add64(expected_main_end, s->main_block, &expected_main_end) && expected_main_end <= p->expected;
}

#if !defined(_WIN32) || defined(BOOTFORGE_F2FS_FIXTURE_TEST)
static void dump_super(const char *label, unsigned index, const unsigned char raw[SB_BYTES]) {
    printf("FS_%s_SB%u_MAGIC=%08" PRIx32 "\nFS_%s_SB%u_BLOCK_COUNT=%" PRIu64
        "\nFS_%s_SB%u_SECTIONS=%" PRIu32 "\nFS_%s_SB%u_SEGMENTS=%" PRIu32
        "\nFS_%s_SB%u_MAIN_SEGMENTS=%" PRIu32 "\nFS_%s_SB%u_SEGS_PER_SEC=%" PRIu32
        "\nFS_%s_SB%u_CP_BLOCK=%" PRIu32 "\nFS_%s_SB%u_MAIN_BLOCK=%" PRIu32
        "\nFS_%s_SB%u_FEATURES=%08" PRIx32 "\nFS_%s_SB%u_CHECKSUM_OFFSET=%" PRIu32
        "\nFS_%s_SB%u_CRC_STORED=%08" PRIx32 "\nFS_%s_SB%u_CRC_COMPUTED=%08" PRIx32 "\n",
        label, index, le32(raw), label, index, le64(raw + 36), label, index, le32(raw + 44),
        label, index, le32(raw + 48), label, index, le32(raw + 68), label, index, le32(raw + 24),
        label, index, le32(raw + 76), label, index, le32(raw + 92), label, index, le32(raw + 2180),
        label, index, le32(raw + 32), label, index, le32(raw + 3068), label, index, disk_crc(F2FS_MAGIC, raw, 3068));
    static const struct { unsigned offset; const char *name; } fields[] = {
        { 8, "LOG_SECTOR_SIZE" }, { 12, "LOG_SECTORS_PER_BLOCK" }, { 16, "LOG_BLOCK_SIZE" },
        { 20, "LOG_BLOCKS_PER_SEGMENT" }, { 28, "SECTIONS_PER_ZONE" }, { 52, "CP_SEGMENTS" },
        { 56, "SIT_SEGMENTS" }, { 60, "NAT_SEGMENTS" }, { 64, "SSA_SEGMENTS" },
        { 72, "SEGMENT0_BLOCK" }, { 80, "SIT_BLOCK" }, { 84, "NAT_BLOCK" }, { 88, "SSA_BLOCK" },
    };
    for (size_t i = 0; i < sizeof(fields) / sizeof(fields[0]); i++)
        printf("FS_%s_SB%u_%s=%" PRIu32 "\n", label, index, fields[i].name, le32(raw + fields[i].offset));
    bool crc_required = !!(le32(raw + 2180) & UINT32_C(0x800));
    printf("FS_%s_SB%u_CRC_REQUIRED=%d\nFS_%s_SB%u_CRC_VALID=%d\n", label, index, crc_required,
        label, index, !crc_required || (le32(raw + 32) == 3068 && le32(raw + 3068) == disk_crc(F2FS_MAGIC, raw, 3068)));
    printf("FS_%s_SB%u_UUID=", label, index);
    for (size_t i = 108; i < 124; i++) printf("%02x", raw[i]);
    putchar('\n');
}
static int read_super_pair(const struct device_file *dev, struct super_info *s, const char *label) {
    unsigned char first[SB_BYTES], second[SB_BYTES];
    if (read_f2fs(dev, first, sizeof(first), 1024) < 0 || read_f2fs(dev, second, sizeof(second), 5120) < 0)
        return system_failure("F2FS direct superblock read failed; buffered fallback is prohibited");
    dump_super(label, 0, first); dump_super(label, 1, second);
    printf("FS_%s_SB_PAIR_MATCH=%d\n", label, !memcmp(first, second, SB_BYTES));
    if (memcmp(first, second, SB_BYTES)) return failure("F2FS primary and backup superblocks differ");
    if (!decode_super(first, dev->bytes, s)) return failure("F2FS superblock geometry, identity, features, health or checksum rejected; see per-copy fields");
    return 0;
}
static bool cp_block_valid(const unsigned char raw[4096], uint32_t *stored, uint32_t *computed) {
    uint32_t offset = le32(raw + 164);
    if (offset < 192 || offset > 4092) return false;
    uint32_t crc = disk_crc(F2FS_MAGIC, raw, offset);
    if (offset < 4092) crc = disk_crc(crc, raw + offset + 4, 4096 - offset - 4);
    *stored = le32(raw + offset); *computed = crc;
    return crc == *stored;
}
static bool read_checkpoint_pack(const struct device_file *dev, uint64_t block, unsigned index, struct checkpoint_info *cp) {
    unsigned char first[4096], last[4096];
    uint64_t offset, last_offset, last_block;
    memset(cp, 0, sizeof(*cp)); cp->pack_block = block; cp->index = index;
    if (!mul64(block, FS_BLOCK, &offset)) { cp->read_errno = EOVERFLOW; return false; }
    if (read_f2fs(dev, first, sizeof(first), offset) < 0) { cp->read_errno = errno; return false; }
    cp->first_read = true;
    cp->version = le64(first); cp->user_blocks = le64(first + 8); cp->valid_blocks = le64(first + 16);
    cp->flags = le32(first + 132); cp->pack_blocks = le32(first + 136); cp->checksum_offset = le32(first + 164);
    bool first_valid = cp_block_valid(first, &cp->first_stored, &cp->first_computed);
    uint32_t count = le32(first + 136);
    if (count <= 2 || count > 512 || !add64(block, count - 1, &last_block) || !mul64(last_block, FS_BLOCK, &last_offset) ||
        last_offset > dev->bytes || FS_BLOCK > dev->bytes - last_offset) return false;
    if (read_f2fs(dev, last, sizeof(last), last_offset) < 0) { cp->read_errno = errno; return false; }
    cp->last_read = true; cp->last_version = le64(last);
    bool last_valid = cp_block_valid(last, &cp->last_stored, &cp->last_computed);
    cp->valid = first_valid && last_valid && cp->version == cp->last_version;
    return cp->valid;
}
static void dump_checkpoint(const char *label, const struct checkpoint_info *cp) {
    unsigned i = cp->index;
    printf("FS_%s_CP%u_PACK_BLOCK=%" PRIu64 "\nFS_%s_CP%u_PACK_BLOCKS=%" PRIu32
        "\nFS_%s_CP%u_VERSION=%" PRIu64 "\nFS_%s_CP%u_LAST_VERSION=%" PRIu64
        "\nFS_%s_CP%u_USER_BLOCKS=%" PRIu64 "\nFS_%s_CP%u_VALID_BLOCKS=%" PRIu64
        "\nFS_%s_CP%u_FLAGS=%" PRIu32 "\nFS_%s_CP%u_CHECKSUM_OFFSET=%" PRIu32
        "\nFS_%s_CP%u_FIRST_CRC_STORED=%08" PRIx32 "\nFS_%s_CP%u_FIRST_CRC_COMPUTED=%08" PRIx32
        "\nFS_%s_CP%u_LAST_CRC_STORED=%08" PRIx32 "\nFS_%s_CP%u_LAST_CRC_COMPUTED=%08" PRIx32
        "\nFS_%s_CP%u_FIRST_READ=%d\nFS_%s_CP%u_LAST_READ=%d\nFS_%s_CP%u_CRC_VALID=%d\nFS_%s_CP%u_READ_ERRNO=%d\n",
        label, i, cp->pack_block, label, i, cp->pack_blocks, label, i, cp->version, label, i, cp->last_version,
        label, i, cp->user_blocks, label, i, cp->valid_blocks, label, i, cp->flags, label, i, cp->checksum_offset,
        label, i, cp->first_stored, label, i, cp->first_computed, label, i, cp->last_stored, label, i, cp->last_computed,
        label, i, cp->first_read, label, i, cp->last_read, label, i, cp->valid, label, i, cp->read_errno);
}
static int read_checkpoint(const struct device_file *dev, const struct super_info *s, struct checkpoint_info *cp, const char *label) {
    struct checkpoint_info first, second;
    bool a = read_checkpoint_pack(dev, s->cp_block, 0, &first);
    bool b = read_checkpoint_pack(dev, (uint64_t)s->cp_block + 512, 1, &second);
    dump_checkpoint(label, &first); dump_checkpoint(label, &second);
    if (first.read_errno || second.read_errno) { errno = first.read_errno ? first.read_errno : second.read_errno;
        return system_failure("F2FS direct checkpoint read failed; no buffered fallback or stale-pack substitution"); }
    if (!a && !b) return failure("F2FS has no complete CRC-valid checkpoint pack");
    if (a && b && second.version != first.version && second.version - first.version < (UINT64_C(1) << 63)) *cp = second;
    else *cp = a ? first : second;
    printf("FS_%s_CP_SELECTED=%u\nFS_%s_CP_SELECTED_VERSION=%" PRIu64 "\n", label, cp->index, label, cp->version);
    printf("FS_%s_CP_REJECTED_FLAG_MASK=%" PRIu32 "\nFS_%s_CP_MAIN_CAPACITY_BLOCKS=%" PRIu64 "\n",
        label, cp->flags & CP_BAD_FLAGS, label, (uint64_t)s->main_segments * 512);
    if ((cp->flags & CP_BAD_FLAGS) || !cp->user_blocks || cp->valid_blocks > cp->user_blocks ||
        cp->user_blocks > (uint64_t)s->main_segments * 512) return failure("F2FS checkpoint health or capacity rejected");
    return 0;
}
static bool unchanged_super_identity(const struct super_info *before, const struct super_info *after, const struct resize_plan *p) {
    uint64_t removed_segments = p->removed_sections * before->segs_per_sec;
    if (after->blocks != p->expected || after->sections != before->sections - p->removed_sections ||
        after->segments != before->segments - removed_segments || after->main_segments != before->main_segments - removed_segments) return false;
    unsigned char a[SB_BYTES], b[SB_BYTES];
    memcpy(a, before->raw, sizeof(a)); memcpy(b, after->raw, sizeof(b));
    memset(a + 36, 0, 16); memset(b + 36, 0, 16);
    memset(a + 68, 0, 4); memset(b + 68, 0, 4);
    memset(a + 3068, 0, 4); memset(b + 3068, 0, 4);
    return !memcmp(a, b, sizeof(a));
}
static void report_f2fs(const struct device_file *dev, const struct super_info *s, const struct checkpoint_info *cp) {
    printf("DEVICE_BYTES=%" PRIu64 "\nDEVICE_SECTOR_SIZE=%" PRIu32 "\nFS_TYPE=f2fs\nFS_BYTES=%" PRIu64
        "\nFS_BLOCK_COUNT=%" PRIu64 "\nFS_BLOCK_SIZE=4096\nFS_SECTION_BLOCKS=%" PRIu64
        "\nFS_CP_VERSION=%" PRIu64 "\nFS_CP_USER_BLOCKS=%" PRIu64 "\nFS_CP_VALID_BLOCKS=%" PRIu64
        "\nFS_CP_FLAGS=%" PRIu32 "\nFS_CP_PACK=%u\nFS_CP_CRC_VALID=1\nFS_MATCH=1\n",
        dev->bytes, dev->sector, s->blocks * FS_BLOCK, s->blocks, s->section_blocks,
        cp->version, cp->user_blocks, cp->valid_blocks, cp->flags, cp->index);
}
#endif
#ifndef _WIN32
static int check_mount(int mount_fd, const char *mount, const struct device_file *dev) {
    struct stat st;
    struct statfs fs;
    struct statvfs vfs;
    if (fstat(mount_fd, &st) || fstatfs(mount_fd, &fs) || fstatvfs(mount_fd, &vfs)) return system_failure("mount identity query failed");
    if (!S_ISDIR(st.st_mode) || st.st_dev != dev->st.st_rdev || (uint64_t)fs.f_type != F2FS_MAGIC ||
        fs.f_bsize != 4096 || (vfs.f_flag & ST_RDONLY) || sysconf(_SC_PAGESIZE) != 4096) return failure("mount must be writable 4 KiB F2FS on the exact supplied block device");
    char resolved[PATH_MAX], line[16384];
    if (!realpath(mount, resolved)) return system_failure("mount realpath failed");
    if (strpbrk(resolved, " \t\r\n\\")) return failure("mount path must have unescaped simple components");
    FILE *file = fopen("/proc/self/mountinfo", "re");
    if (!file) return system_failure("mountinfo open failed");
    unsigned found = 0;
    while (fgets(line, sizeof(line), file)) {
        unsigned major_id, minor_id;
        char root[PATH_MAX], point[PATH_MAX], options[1024];
        if (sscanf(line, "%*u %*u %u:%u %4095s %4095s %1023s", &major_id, &minor_id, root, point, options) == 5 &&
            !strcmp(point, resolved)) {
            char *separator = strstr(line, " - f2fs ");
            if (strcmp(root, "/") || !separator || major_id != major(dev->st.st_rdev) || minor_id != minor(dev->st.st_rdev) ||
                (strncmp(options, "rw,", 3) && strcmp(options, "rw"))) { fclose(file); return failure("exact F2FS mountinfo record rejected"); }
            found++;
        }
    }
    int rc = ferror(file) ? system_failure("mountinfo read failed") : 0;
    fclose(file);
    if (!rc && found != 1) rc = failure("exact mountpoint must have one unambiguous mountinfo record");
    return rc;
}
static int inspect_f2fs(const char *mount, const char *mapped, bool fixture) {
    if (fixture) return failure("mounted F2FS inspection does not accept regular-file fixtures");
    struct device_file dev;
    int mount_fd = -1, rc = open_device(mapped, false, false, &dev);
    if (rc) goto out;
    if ((rc = open_direct_reader(mapped, &dev, 4096))) goto out;
    printf("F2FS_READ_MODE=O_DIRECT\nFS_READ_MODE=direct\nF2FS_READ_ALIGNMENT=%" PRIu32 "\n", dev.direct_alignment);
    mount_fd = open(mount, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (mount_fd < 0) { rc = system_failure("mount open failed"); goto out; }
    if ((rc = check_mount(mount_fd, mount, &dev))) goto out;
    struct super_info s;
    struct checkpoint_info cp;
    if ((rc = read_super_pair(&dev, &s, "INSPECT")) || (rc = read_checkpoint(&dev, &s, &cp, "INSPECT")) ||
        (rc = check_mount(mount_fd, mount, &dev))) goto out;
    report_f2fs(&dev, &s, &cp);
    puts("FS_MOUNT_IDENTITY=match\nFS_INSPECTION=read-only\nRESULT=success");
out:
    if (mount_fd >= 0) close(mount_fd);
    if (dev.direct_fd >= 0) close(dev.direct_fd);
    if (dev.fd >= 0) close(dev.fd);
    return rc;
}
static int shrink_f2fs(const char *mount, const char *mapped, uint64_t maximum, bool execute, bool fixture) {
    if (fixture) return failure("F2FS resize never accepts regular-file fixture execution");
    struct device_file dev;
    int mount_fd = -1, rc = open_device(mapped, execute, false, &dev);
    if (rc) goto out;
    if ((rc = open_direct_reader(mapped, &dev, 4096))) goto out;
    printf("F2FS_READ_MODE=O_DIRECT\nFS_READ_MODE=direct\nF2FS_READ_ALIGNMENT=%" PRIu32 "\n", dev.direct_alignment);
    if (maximum > dev.bytes) { rc = failure("maximum filesystem boundary exceeds mapped device capacity"); goto out; }
    mount_fd = open(mount, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (mount_fd < 0) { rc = system_failure("mount open failed"); goto out; }
    if ((rc = check_mount(mount_fd, mount, &dev))) goto out;
    struct super_info before, after;
    struct checkpoint_info checkpoint_before, checkpoint_synced, checkpoint_after;
    struct resize_plan plan;
    if ((rc = read_super_pair(&dev, &before, "BEFORE")) || (rc = read_checkpoint(&dev, &before, &checkpoint_before, "BEFORE"))) goto out;
    if (!build_resize_plan(&before, maximum, &plan)) { rc = failure("F2FS section-aligned shrink geometry rejected"); goto out; }
    uint64_t removed = before.blocks - plan.expected;
    if (removed >= checkpoint_before.user_blocks || checkpoint_before.valid_blocks > checkpoint_before.user_blocks - removed) { rc = failure("checkpoint data does not fit proposed filesystem capacity"); goto out; }
    printf("F2FS_OLD_BLOCKS=%" PRIu64 "\nF2FS_EXPECTED_BLOCKS=%" PRIu64 "\nF2FS_IOCTL_BLOCKS=%" PRIu64 "\nF2FS_BLOCK_BYTES=4096\nF2FS_SECTION_BLOCKS=%" PRIu64 "\nF2FS_MARGIN_BYTES=%" PRIu64 "\nF2FS_MAX_BYTES=%" PRIu64 "\nF2FS_REMOVED_SECTIONS=%" PRIu64 "\n", before.blocks, plan.expected, plan.argument, before.section_blocks, plan.margin, maximum, plan.removed_sections);
    printf("FS_EXPECTED_BYTES=%" PRIu64 "\nF2FS_EXPECTED_BYTES=%" PRIu64 "\n", plan.expected * FS_BLOCK, plan.expected * FS_BLOCK);
    report_f2fs(&dev, &before, &checkpoint_before);
    if (!execute) {
        if ((rc = check_mount(mount_fd, mount, &dev))) goto out;
        puts("FS_PLAN=read-only\nRESULT=planned"); goto out;
    }
    if (syncfs(mount_fd) < 0 || flush_fd(dev.fd) < 0) { rc = system_failure("pre-resize syncfs or mapped-device flush failed"); goto out; }
    /* Revalidate immutable input after syncing; a concurrent resize is a hard
     * stop. The orchestrator must hold its cross-command transaction lock. */
    if ((rc = check_mount(mount_fd, mount, &dev)) || (rc = read_super_pair(&dev, &after, "PRESYNC")) ||
        (rc = read_checkpoint(&dev, &after, &checkpoint_synced, "PRESYNC"))) goto out;
    if (memcmp(before.raw, after.raw, SB_BYTES)) { rc = failure("F2FS superblock changed before resize"); goto out; }
    /* syncfs may legitimately commit a newer checkpoint and different live
     * allocation. Recheck that fresh direct-read state immediately before the
     * resize, without requiring checkpoint versions/valid counts to stay fixed. */
    if (checkpoint_synced.user_blocks != checkpoint_before.user_blocks || removed >= checkpoint_synced.user_blocks ||
        checkpoint_synced.valid_blocks > checkpoint_synced.user_blocks - removed) {
        rc = failure("F2FS presync checkpoint capacity changed or data does not fit resize target; see PRESYNC fields"); goto out;
    }
    checkpoint_before = checkpoint_synced;
    if (plan.removed_sections) {
        uint64_t argument = plan.argument;
        if (ioctl(mount_fd, F2FS_IOC_RESIZE_FS, &argument) < 0) { rc = system_failure("F2FS resize ioctl failed; inspect filesystem before further actions"); goto out; }
    }
    if (syncfs(mount_fd) < 0 || flush_fd(dev.fd) < 0) { rc = system_failure("post-resize syncfs or mapped-device flush failed"); goto out; }
    if ((rc = check_mount(mount_fd, mount, &dev)) || (rc = read_super_pair(&dev, &after, "AFTER")) ||
        (rc = read_checkpoint(&dev, &after, &checkpoint_after, "AFTER"))) goto out;
    if (!unchanged_super_identity(&before, &after, &plan) || checkpoint_after.user_blocks != checkpoint_before.user_blocks - removed ||
        after.blocks > plan.allowed_blocks) {
        printf("F2FS_REJECT_EXPECTED_BLOCKS=%" PRIu64 "\nF2FS_REJECT_ACTUAL_BLOCKS=%" PRIu64
            "\nF2FS_REJECT_EXPECTED_CP_USER_BLOCKS=%" PRIu64 "\nF2FS_REJECT_ACTUAL_CP_USER_BLOCKS=%" PRIu64
            "\nF2FS_REJECT_IDENTITY_MATCH=%d\n", plan.expected, after.blocks,
            checkpoint_before.user_blocks - removed, checkpoint_after.user_blocks,
            unchanged_super_identity(&before, &after, &plan));
        rc = failure("resized direct-read superblocks or checkpoint differ from predicted result; see before/after fields"); goto out;
    }
    printf("F2FS_ACTUAL_BLOCKS=%" PRIu64 "\nF2FS_CP_USER_BLOCKS=%" PRIu64 "\nF2FS_CP_FLAGS=%" PRIu32 "\nF2FS_STATUS=%s\nFLUSH=syncfs+fsync\nRESULT=success\n", after.blocks, checkpoint_after.user_blocks, checkpoint_after.flags, removed ? "shrunk-verified" : "already-sufficient");
out:
    if (mount_fd >= 0) close(mount_fd);
    if (dev.direct_fd >= 0) close(dev.direct_fd);
    if (dev.fd >= 0) close(dev.fd);
    return rc;
}
#else
static int inspect_f2fs(const char *mount, const char *mapped, bool fixture) {
    (void)mount; (void)mapped; (void)fixture;
    return failure("host build has no mounted-filesystem inspection command; use the snapshot harness");
}
static int shrink_f2fs(const char *mount, const char *mapped, uint64_t maximum, bool execute, bool fixture) {
    (void)mount; (void)mapped; (void)maximum; (void)execute; (void)fixture;
    return failure("host build has no filesystem resize ioctl command");
}
#endif

static void put32(unsigned char *p, uint32_t n) { for (unsigned i = 0; i < 4; i++) p[i] = (unsigned char)(n >> (8 * i)); }
static void put64(unsigned char *p, uint64_t n) { put32(p, (uint32_t)n); put32(p + 4, (uint32_t)(n >> 32)); }
struct alignment_test { unsigned calls; bool bad_alignment; bool short_read; };
static int64_t alignment_test_read(int fd, void *buffer, size_t size, uint64_t offset, void *opaque) {
    (void)fd;
    struct alignment_test *state = opaque;
    state->calls++;
    if ((uintptr_t)buffer % 4096 || offset % 4096 || size % 4096) { state->bad_alignment = true; errno = EINVAL; return -1; }
    if (state->short_read) return (int64_t)size - 1;
    for (size_t i = 0; i < size; i++) ((unsigned char *)buffer)[i] = (unsigned char)((offset + i) % 251);
    return (int64_t)size;
}
static int self_test(void) {
    unsigned tested = 0;
#define TEST(condition) do { tested++; if (!(condition)) { fprintf(stderr, "SELF_TEST_FAILED_LINE=%d\n", __LINE__); return 1; } } while (0)
    uint64_t n;
    TEST(!add64(UINT64_MAX, 1, &n)); TEST(!mul64(UINT64_MAX, 4096, &n));
    TEST(parse64("960046694400", &n) && n == UINT64_C(960046694400));
    TEST(!parse64("18446744073709551616", &n)); TEST(!parse64("-1", &n)); TEST(!parse64("1x", &n));
    struct alignment_test aligned = { 0, false, false };
    unsigned char slice[SB_BYTES];
    TEST(!aligned_read_slice(-1, 65536, 4096, slice, sizeof(slice), 1024, alignment_test_read, &aligned));
    TEST(!aligned.bad_alignment && aligned.calls == 1 && slice[0] == (unsigned char)(1024 % 251) && slice[SB_BYTES - 1] == (unsigned char)(4095 % 251));
    TEST(!aligned_read_slice(-1, 65536, 4096, slice, sizeof(slice), 5120, alignment_test_read, &aligned));
    TEST(!aligned.bad_alignment && aligned.calls == 2 && slice[0] == (unsigned char)(5120 % 251));
    TEST(aligned_read_slice(-1, 65536, 4096, slice, sizeof(slice), UINT64_MAX - 2, alignment_test_read, &aligned) < 0);
    TEST(aligned_read_slice(-1, 6144, 4096, slice, sizeof(slice), 2048, alignment_test_read, &aligned) < 0);
    aligned.short_read = true;
    TEST(aligned_read_slice(-1, 65536, 4096, slice, sizeof(slice), 1024, alignment_test_read, &aligned) < 0 && errno == EIO);
    struct super_info s; memset(&s, 0, sizeof(s));
    s.blocks = UINT64_C(234386400); s.section_blocks = 512; s.section_bytes = UINT64_C(2097152);
    s.sections = 456768; s.main_segments = 456768; s.segs_per_sec = 1; s.main_block = 520192;
    struct resize_plan p;
    TEST(build_resize_plan(&s, UINT64_C(959979585536), &p));
    TEST(p.expected == UINT64_C(234369504)); TEST(p.argument == UINT64_C(234369024)); TEST(p.removed_sections == 33);
    TEST(p.expected * FS_BLOCK + p.margin == UINT64_C(959979585536));
    TEST(!build_resize_plan(&s, 1, &p)); TEST(!build_resize_plan(&s, UINT64_C(959979585537), &p));
    TEST(build_resize_plan(&s, UINT64_C(960048791552), &p) && !p.removed_sections && p.expected == s.blocks);
    s.blocks = 1024; s.sections = 1;
    TEST(!build_resize_plan(&s, UINT64_C(2097152), &p));
    struct device_file dev; memset(&dev, 0, sizeof(dev)); dev.bytes = 8192; dev.sector = 4096;
    TEST(range_valid(&dev, 4096, 4096, true)); TEST(!range_valid(&dev, 4096, 4097, true));
    TEST(!range_valid(&dev, UINT64_MAX - 4095, 4096, true)); TEST(!range_valid(&dev, 1, 4096, true));
    TEST(range_valid(&dev, 0, 2015, false));
    unsigned char raw[SB_BYTES]; memset(raw, 0, sizeof(raw));
    put32(raw, F2FS_MAGIC); put32(raw + 8, 9); put32(raw + 12, 3); put32(raw + 16, 12); put32(raw + 20, 9);
    put32(raw + 24, 1); put32(raw + 28, 1); put64(raw + 36, 8192); put32(raw + 44, 8); put32(raw + 48, 15);
    put32(raw + 52, 2); put32(raw + 56, 2); put32(raw + 60, 2); put32(raw + 64, 1); put32(raw + 68, 8);
    put32(raw + 72, 512); put32(raw + 76, 512); put32(raw + 80, 1536); put32(raw + 84, 2560);
    put32(raw + 88, 3584); put32(raw + 92, 4096); raw[108] = 1;
    TEST(decode_super(raw, UINT64_C(33554432), &s));
    put32(raw + 2180, 0x800); put32(raw + 32, 3068); put32(raw + 3068, disk_crc(F2FS_MAGIC, raw, 3068));
    TEST(decode_super(raw, UINT64_C(33554432), &s)); raw[120] ^= 1;
    TEST(!decode_super(raw, UINT64_C(33554432), &s));
    printf("SELF_TESTS=%u\nRESULT=success\n", tested);
#undef TEST
    return 0;
}

static int usage(void) {
    fputs("Usage:\n  partition_worker inspect-f2fs MOUNT MAPPED_DEVICE\n  partition_worker plan-f2fs MOUNT MAPPED_DEVICE MAX_FS_BYTES\n  partition_worker shrink-f2fs MOUNT MAPPED_DEVICE MAX_FS_BYTES [--execute]\n  partition_worker write-range BLOCK_DEVICE OFFSET CAPACITY IMAGE [--execute] [--fixture]\n  partition_worker read-range BLOCK_DEVICE OFFSET LENGTH [--fixture]\n  partition_worker --self-test\n", stderr);
    return 2;
}
int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--self-test")) return self_test();
    if (argc < 2) return usage();
    bool execute = false, fixture = false;
    int required;
    if (!strcmp(argv[1], "write-range")) required = 6;
    else if (!strcmp(argv[1], "inspect-f2fs")) required = 4;
    else if (!strcmp(argv[1], "read-range") || !strcmp(argv[1], "shrink-f2fs") || !strcmp(argv[1], "plan-f2fs")) required = 5;
    else return usage();
    if (argc < required) return usage();
    for (int i = required; i < argc; i++) {
        if (!strcmp(argv[i], "--execute") && !execute) execute = true;
        else if (!strcmp(argv[i], "--fixture") && !fixture) fixture = true;
        else return usage();
    }
    uint64_t a, b;
    if (!strcmp(argv[1], "inspect-f2fs")) {
        if (execute || fixture) return usage();
        return inspect_f2fs(argv[2], argv[3], false);
    }
    if (!strcmp(argv[1], "shrink-f2fs") || !strcmp(argv[1], "plan-f2fs")) {
        if (!strcmp(argv[1], "plan-f2fs") && (execute || fixture)) return usage();
        if (!parse64(argv[4], &a)) return failure("MAX_FS_BYTES must be an unsigned decimal 64-bit integer");
        return shrink_f2fs(argv[2], argv[3], a, execute, fixture);
    }
    if (!parse64(argv[3], &a) || !parse64(argv[4], &b)) return failure("range values must be unsigned decimal 64-bit integers");
    if (!strcmp(argv[1], "write-range")) return write_range(argv[2], a, b, argv[5], execute, fixture);
    if (execute) return usage();
    return read_range(argv[2], a, b, fixture);
}
