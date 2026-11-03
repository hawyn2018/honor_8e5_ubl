/* SPDX-License-Identifier: MIT -- offline disk-image contract tests. */
#include "../kbl_ext4.h"
#include <ext4_blockdev.h>
#include <ext4_mkfs.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
struct disk {
  unsigned char *p;
  size_t n, writes, flushes;
  int write_fail, flush_fail;
  size_t flush_fail_at, read_limit;
};
static size_t live;
static long allocation_fail_after = -1;
static void *allocate(void *x, size_t n, size_t a) {
  (void)x;
  (void)a;
  if (allocation_fail_after == 0) return 0;
  if (allocation_fail_after > 0) --allocation_fail_after;
  void *p = malloc(n);
  if (p)
    ++live;
  return p;
}
static void release(void *x, void *p, size_t n) {
  (void)x;
  (void)n;
  --live;
  free(p);
}
static int rd(void *x, uint64_t o, void *p, size_t n, size_t *d) {
  struct disk *v = x;
  *d = 0;
  if (o > v->n || n > v->n - o)
    return -5;
  if (v->read_limit && n > v->read_limit)
    n = v->read_limit;
  memcpy(p, v->p + o, n);
  *d = n;
  return 0;
}
static int wr(void *x, uint64_t o, const void *p, size_t n, size_t *d) {
  struct disk *v = x;
  *d = 0;
  ++v->writes;
  if (v->write_fail)
    return -5;
  if (o > v->n || n > v->n - o)
    return -5;
  memcpy(v->p + o, p, n);
  *d = n;
  return 0;
}
static int fl(void *x) {
  struct disk *v = x;
  ++v->flushes;
  return (v->flush_fail || v->flushes == v->flush_fail_at) ? -5 : 0;
}
static int bo(struct ext4_blockdev *b) {
  (void)b;
  return 0;
}
static int br(struct ext4_blockdev *b, void *p, uint64_t o, uint32_t n) {
  size_t d;
  return rd(b->bdif->p_user, o * 512, p, (size_t)n * 512, &d) ? 5 : 0;
}
static int bw(struct ext4_blockdev *b, const void *p, uint64_t o, uint32_t n) {
  size_t d;
  return wr(b->bdif->p_user, o * 512, p, (size_t)n * 512, &d) ? 5 : 0;
}
#define CHECK(c)                                                               \
  do {                                                                         \
    if (!(c)) {                                                                \
      fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #c);                     \
      exit(1);                                                                 \
    }                                                                          \
  } while (0)
static struct kbl_fs_host host = {0, allocate, release};
static struct kbl_fs_device device(struct disk *d) {
  struct kbl_fs_device v = {d, d->n, 512, 0, rd, wr, fl};
  return v;
}
static void format(struct disk *d, int type, int journal) {
  struct ext4_mkfs_info info = {0};
  struct ext4_fs fs = {0};
  unsigned char buf[512];
  struct ext4_blockdev_iface bi = {0};
  struct ext4_blockdev bd = {0};
  kbl_ext4_test_allocator(&host);
  bi.open = bo;
  bi.close = bo;
  bi.bread = br;
  bi.bwrite = bw;
  bi.ph_bsize = 512;
  bi.ph_bcnt = d->n / 512;
  bi.ph_bbuf = buf;
  bi.p_user = d;
  bd.bdif = &bi;
  bd.part_size = d->n;
  info.block_size = 4096;
  info.journal = journal;
  info.label = "KBL-offline";
  CHECK(ext4_mkfs(&fs, &bd, &info, type) == 0);
}
int main(int argc, char **argv) {
  struct disk d = {0};
  struct kbl_fs_device dev;
  struct kbl_fs m = {0};
  void *f = 0;
  size_t n, w0;
  int e, end;
  char buf[128], name[256];
  struct kbl_file_stat s;
  int type = argc > 1 ? atoi(argv[1]) : 4;
  int journal = argc > 2 ? atoi(argv[2]) : 0;
  d.n = (argc > 4 ? (size_t)atoi(argv[4]) : 64u) * 1024u * 1024u;
  d.p = calloc(1, d.n);
  CHECK(d.p);
  if (argc > 5) {
    FILE *src = fopen(argv[5], "rb");
    CHECK(src);
    CHECK(fread(d.p, 1, d.n, src) == d.n);
    fclose(src);
  } else
    format(&d, type, journal);
  dev = device(&d);
  CHECK(kbl_ext4_mount(&host, &dev, "ext4", 0, &m) == 0);
  CHECK(m.ops->mkdir(m.opaque, "/folder") == 0);
  CHECK(m.ops->open(m.opaque, "/not-created/deep",
                    KBL_FILE_WRITE | KBL_FILE_CREATE, &f) == -KBL_ENOENT);
  CHECK(m.ops->mkdir(m.opaque, "/missing/deep") == -KBL_ENOENT);
  CHECK(m.ops->open(m.opaque, "/folder/sample",
                    KBL_FILE_READ | KBL_FILE_WRITE | KBL_FILE_CREATE |
                        KBL_FILE_EXCL,
                    &f) == 0);
  CHECK(m.ops->write_at(m.opaque, f, 0, "hello ext4", 10, &n) == 0 && n == 10);
  CHECK(m.ops->write_at(m.opaque, f, 10, "!", 1, &n) == 0 && n == 1);
  CHECK(m.ops->read_at(m.opaque, f, 0, buf, sizeof(buf), &n) == 0 && n == 11 &&
        !memcmp(buf, "hello ext4!", 11));
  CHECK(m.ops->read_at(m.opaque, f, 999, buf, sizeof(buf), &n) == 0 && n == 0);
  CHECK(m.ops->fstat(m.opaque, f, &s) == 0 && s.bytes == 11);
  CHECK(m.ops->unmount(m.opaque) == -KBL_EBUSY);
  CHECK(m.ops->sync(m.opaque, f) == 0);
  CHECK(m.ops->close(m.opaque, f) == 0);
  CHECK(m.ops->list(m.opaque, "/folder", 0, name, sizeof(name), &s, &end) ==
            0 &&
        !end && !strcmp(name, "sample"));
  CHECK(m.ops->list(m.opaque, "/folder", 1, name, sizeof(name), &s, &end) ==
            0 &&
        end);
  CHECK(m.ops->unlink(m.opaque, "/folder") == -KBL_ENOTEMPTY);
  CHECK(m.ops->rename(m.opaque, "/folder/sample", "/folder/renamed") == 0);
  CHECK(m.ops->stat(m.opaque, "/folder/sample", &s) == -KBL_ENOENT);
  CHECK(m.ops->unmount(m.opaque) == 0);
  CHECK(live == 0);
  w0 = d.writes;
  d.read_limit = 127;
  CHECK(kbl_ext4_mount(&host, &dev, "ext4", KBL_FS_READ_ONLY, &m) == 0);
  CHECK(m.ops->stat(m.opaque, "/folder/renamed", &s) == 0 && s.bytes == 11);
  CHECK(m.ops->open(m.opaque, "/folder/renamed", KBL_FILE_READ, &f) == 0);
  CHECK(m.ops->read_at(m.opaque, f, 0, buf, sizeof(buf), &n) == 0 && n == 11);
  CHECK(m.ops->close(m.opaque, f) == 0);
  CHECK(m.ops->mkdir(m.opaque, "/forbidden") == -KBL_EROFS);
  CHECK(m.ops->unmount(m.opaque) == 0);
  CHECK(d.writes == w0 && live == 0);
  d.read_limit = 0;
  CHECK(kbl_ext4_mount(&host, &dev, "ext4", 0, &m) == 0);
  CHECK(m.ops->open(m.opaque, "/folder/renamed",
                    KBL_FILE_WRITE | KBL_FILE_TRUNC, &f) == 0);
  CHECK(m.ops->fstat(m.opaque, f, &s) == 0 && s.bytes == 0);
  CHECK(m.ops->close(m.opaque, f) == 0);
  CHECK(m.ops->unlink(m.opaque, "/folder/renamed") == 0);
  CHECK(m.ops->unlink(m.opaque, "/folder") == 0);
  d.flush_fail = 1;
  CHECK(m.ops->unmount(m.opaque) == -KBL_EIO && live > 0);
  d.flush_fail = 0;
  CHECK(m.ops->unmount(m.opaque) == 0 && live == 0);
  CHECK(kbl_ext4_mount(&host, &dev, "ext4", 0, &m) == 0);
  d.flush_fail_at = d.flushes + 2;
  CHECK(m.ops->unmount(m.opaque) == -KBL_EIO && live > 0);
  CHECK(m.ops->stat(m.opaque, "/", &s) == -KBL_EIO);
  d.flush_fail_at = 0;
  CHECK(m.ops->unmount(m.opaque) == 0 && live == 0);
  if (argc > 3) {
    FILE *o = fopen(argv[3], "wb");
    CHECK(o);
    CHECK(fwrite(d.p, 1, d.n, o) == d.n);
    fclose(o);
  } /* Dirty mount refusal must occur before the first write. */
  d.p[1024 + 58] = 2;
  w0 = d.writes;
  CHECK(kbl_ext4_mount(&host, &dev, "ext4", 0, &m) == -KBL_EIO &&
        m.opaque == 0 && d.writes == w0 && live == 0);
  d.p[1024 + 58] = 1;
  /* First failed span latches the volume. Neither sync, close nor OFF replays
   * it. */
  CHECK(kbl_ext4_mount(&host, &dev, "ext4", 0, &m) == 0);
  CHECK(m.ops->open(m.opaque, "/failure", KBL_FILE_CREATE | KBL_FILE_WRITE,
                    &f) == 0);
  d.write_fail = 1;
  e = m.ops->write_at(m.opaque, f, 0, "fail", 4, &n);
  CHECK(e == -KBL_EIO);
  w0 = d.writes;
  CHECK(m.ops->sync(m.opaque, f) == -KBL_EIO);
  CHECK(m.ops->close(m.opaque, f) == -KBL_EIO);
  CHECK(m.ops->unmount(m.opaque) != 0 && live > 0);
  CHECK(d.writes == w0);
  printf("ext%d journal=%d RW+RO-zero-write+lifecycle+failure-latch PASS "
         "writes=%zu held_after_fault=%zu\n",
         type, journal, d.writes, live);
  free(d.p);
  return 0;
}
