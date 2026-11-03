/* SPDX-License-Identifier: GPL-3.0-or-later
 * Explicit, synchronous lwext4 adapter. No firmware calls, background tasks,
 * journal recovery, formatter, driver registration or pre-KBL initialization.
 */
#include "kbl_ext4.h"
#include <ext4.h>
#include <ext4_errno.h>
#include <ext4_inode.h>
#include <ext4_oflags.h>
#include <ext4_super.h>
#include <string.h>

struct volume {
  struct kbl_fs_host host;
  struct kbl_fs_device dev;
  struct ext4_blockdev bd;
  struct ext4_blockdev_iface bi;
  struct ext4_sblock *sb;
  char name[2 * sizeof(uintptr_t) + 2], mp[2 * sizeof(uintptr_t) + 4];
  unsigned ro, write_fault, read_fault, journal, mounted;
  size_t open_count;
};
struct handle {
  struct volume *v;
  ext4_file f;
  unsigned flags;
};
/* API calls are synchronous and serialized by the KBL executor. Allocations
 * retain their allocator, so free does not depend on this routing context. */
static const struct kbl_fs_host *allocator;
int ext4_kbl_abort_mount(const char *);
int ext4_kbl_dir_next(ext4_dir *, const ext4_direntry **);
struct alloc_header {
  struct kbl_fs_host host;
  size_t bytes, size;
  uint64_t align;
};
void *kbl_ext4_memcpy(void *d, const void *s, size_t n) {
  unsigned char *x = d;
  const unsigned char *y = s;
  while (n--)
    *x++ = *y++;
  return d;
}
void *kbl_ext4_memmove(void *d, const void *s, size_t n) {
  unsigned char *x = d;
  const unsigned char *y = s;
  if ((uintptr_t)x > (uintptr_t)y) {
    while (n) {
      --n;
      x[n] = y[n];
    }
  } else {
    while (n--)
      *x++ = *y++;
  }
  return d;
}
void *kbl_ext4_memset(void *d, int c, size_t n) {
  unsigned char *x = d;
  while (n--)
    *x++ = (unsigned char)c;
  return d;
}
int kbl_ext4_memcmp(const void *a, const void *b, size_t n) {
  const unsigned char *x = a, *y = b;
  while (n--) {
    if (*x != *y)
      return *x - *y;
    ++x;
    ++y;
  }
  return 0;
}
size_t kbl_ext4_strlen(const char *s) {
  size_t n = 0;
  while (s[n])
    ++n;
  return n;
}
int kbl_ext4_strcmp(const char *a, const char *b) {
  while (*a && *a == *b) {
    ++a;
    ++b;
  }
  return (unsigned char)*a - (unsigned char)*b;
}
int kbl_ext4_strncmp(const char *a, const char *b, size_t n) {
  while (n--) {
    if (*a != *b || !*a)
      return (unsigned char)*a - (unsigned char)*b;
    ++a;
    ++b;
  }
  return 0;
}
char *kbl_ext4_strcpy(char *d, const char *s) {
  char *r = d;
  do {
    *d++ = *s;
  } while (*s++);
  return r;
}
char *kbl_ext4_strncpy(char *d, const char *s, size_t n) {
  char *r = d;
  while (n && *s) {
    *d++ = *s++;
    --n;
  }
  while (n--)
    *d++ = 0;
  return r;
}
char *kbl_ext4_strcat(char *d, const char *s) {
  kbl_ext4_strcpy(d + kbl_ext4_strlen(d), s);
  return d;
}
char *kbl_ext4_strchr(const char *s, int c) {
  do {
    if (*s == (char)c)
      return (char *)s;
  } while (*s++);
  return 0;
}
char *kbl_ext4_strrchr(const char *s, int c) {
  const char *r = 0;
  do {
    if (*s == (char)c)
      r = s;
  } while (*s++);
  return (char *)r;
}
char *kbl_ext4_strstr(const char *s, const char *t) {
  size_t n = strlen(t);
  if (!n)
    return (char *)s;
  for (; *s; ++s)
    if (!strncmp(s, t, n))
      return (char *)s;
  return 0;
}
static void swap_bytes(unsigned char *a, unsigned char *b, size_t z) {
  while (z--) {
    unsigned char t = *a;
    *a++ = *b;
    *b++ = t;
  }
}
static void sift(unsigned char *a, size_t n, size_t z, size_t i,
                 int (*cmp)(const void *, const void *)) {
  for (;;) {
    size_t k;
    if (i >= (n / 2))
      break;
    k = i * 2 + 1;
    if (k + 1 < n && cmp(a + k * z, a + (k + 1) * z) < 0)
      ++k;
    if (cmp(a + i * z, a + k * z) >= 0)
      break;
    swap_bytes(a + i * z, a + k * z, z);
    i = k;
  }
}
void kbl_ext4_qsort(void *p, size_t n, size_t z,
                    int (*cmp)(const void *, const void *)) {
  unsigned char *a = p;
  size_t i;
  if (n < 2 || !z || n > SIZE_MAX / z)
    return;
  i = n / 2;
  while (i)
    sift(a, n, z, --i, cmp);
  while (n > 1) {
    swap_bytes(a, a + (n - 1) * z, z);
    sift(a, --n, z, 0, cmp);
  }
}
void *ext4_user_malloc(size_t n) {
  struct alloc_header *h;
  size_t total;
  if (!allocator || n > SIZE_MAX - sizeof(*h))
    return 0;
  total = n + sizeof(*h);
  h = allocator->allocate(allocator->opaque, total, 16);
  if (!h)
    return 0;
  h->host = *allocator;
  h->bytes = total;
  h->size = n;
  return h + 1;
}
void ext4_user_free(void *p) {
  struct alloc_header *h;
  if (!p)
    return;
  h = (struct alloc_header *)p - 1;
  h->host.release(h->host.opaque, h, h->bytes);
}
void *ext4_user_calloc(size_t n, size_t s) {
  void *p;
  if (s && n > SIZE_MAX / s)
    return 0;
  p = ext4_user_malloc(n * s);
  if (p)
    memset(p, 0, n * s);
  return p;
}
void *ext4_user_realloc(void *p, size_t n) {
  void *q;
  struct alloc_header *h;
  if (!p)
    return ext4_user_malloc(n);
  if (!n) {
    ext4_user_free(p);
    return 0;
  }
  h = (struct alloc_header *)p - 1;
  q = ext4_user_malloc(n);
  if (q) {
    memcpy(q, p, n < h->size ? n : h->size);
    ext4_user_free(p);
  }
  return q;
}
#ifdef KBL_EXT4_HOST_TEST
void kbl_ext4_test_allocator(const struct kbl_fs_host *h) { allocator = h; }
#endif
static void enter(struct volume *v) { allocator = &v->host; }
static int status(int e) {
  if (!e)
    return 0;
  switch (e) {
  case ENOTSUP:
    return -KBL_ENOSYS;
  case ENXIO:
    return -KBL_ENODEV;
  case ERANGE:
    return -KBL_EOVERFLOW;
  case ENODATA:
    return -KBL_ENOENT;
  default:
    return e > 0 ? -e : e;
  }
}
static int bopen(struct ext4_blockdev *b) {
  (void)b;
  return 0;
}
static int exact_read(const struct kbl_fs_device *d, uint64_t off, void *p,
                      size_t n) {
  size_t at = 0;
  if (off > d->bytes || n > d->bytes - off)
    return -KBL_EIO;
  while (at < n) {
    size_t got = 0;
    int r =
        d->read_at(d->opaque, off + at, (unsigned char *)p + at, n - at, &got);
    if (r || !got || got > n - at)
      return -KBL_EIO;
    at += got;
  }
  return 0;
}
static int bread(struct ext4_blockdev *b, void *p, uint64_t id, uint32_t cnt) {
  struct volume *v = b->bdif->p_user;
  uint64_t off, n;
  if (id > UINT64_MAX / b->bdif->ph_bsize)
    return EIO;
  off = id * b->bdif->ph_bsize;
  n = (uint64_t)cnt * b->bdif->ph_bsize;
  if (n > SIZE_MAX || exact_read(&v->dev, off, p, (size_t)n)) {
    ++v->read_fault;
    return EIO;
  }
  return 0;
}
static int bwrite(struct ext4_blockdev *b, const void *p, uint64_t id,
                  uint32_t cnt) {
  struct volume *v = b->bdif->p_user;
  uint64_t off, n;
  size_t done = 0;
  int r;
  if (v->ro)
    return EROFS;
  if (v->write_fault)
    return EIO;
  if (id > UINT64_MAX / b->bdif->ph_bsize)
    return EIO;
  off = id * b->bdif->ph_bsize;
  n = (uint64_t)cnt * b->bdif->ph_bsize;
  if (off > v->dev.bytes || n > v->dev.bytes - off || n > SIZE_MAX)
    return EIO;
  r = v->dev.write_at(v->dev.opaque, off, p, (size_t)n, &done);
  if (r || done != n) {
    v->write_fault = 1;
    return EIO;
  }
  return 0;
}
static int bclose(struct ext4_blockdev *b) {
  (void)b;
  return 0;
}
static int pathof(struct volume *v, const char *p, char **out) {
  size_t n, m;
  *out = 0;
  if (!v->mounted) return -KBL_EIO;
  if (!p || *p != '/') return -KBL_EINVAL;
  n = strlen(p); m = strlen(v->mp);
  /* The native router canonicalizes; standalone callers still reject dot traversal. */
  if (strstr(p, "/../") || strstr(p, "/./") || !strcmp(p, "/..") ||
      !strcmp(p, "/.") || (n >= 3 && !strcmp(p + n - 3, "/..")) ||
      (n >= 2 && !strcmp(p + n - 2, "/."))) return -KBL_EINVAL;
  if (n > SIZE_MAX - m) return -KBL_EOVERFLOW;
  char *q = ext4_user_malloc(n + m);
  if (!q) return -KBL_ENOMEM;
  memcpy(q, v->mp, m); memcpy(q + m, p + 1, n);
  *out = q; return 0;
}
static int rawstat(struct volume *v, const char *p, struct kbl_file_stat *s) {
  struct ext4_inode i;
  uint32_t ino, mode;
  int r = ext4_raw_inode_fill(p, &ino, &i);
  if (r)
    return status(r);
  if (ext4_inode_get_flags(&i) & 0x800u)
    return -KBL_EACCES;
  mode = ext4_inode_get_mode(v->sb, &i) & EXT4_INODE_MODE_TYPE_MASK;
  memset(s, 0, sizeof(*s));
  s->type = mode == EXT4_INODE_MODE_DIRECTORY ? KBL_FILE_DIRECTORY
            : mode == EXT4_INODE_MODE_FILE    ? KBL_FILE_REGULAR
                                              : 0;
  if (!s->type)
    return -KBL_ENOSYS;
  s->bytes = ext4_inode_get_size(v->sb, &i);
  s->flags = v->ro ? KBL_FILE_READ_ONLY : 0;
  s->block_bytes = ext4_sb_get_block_size(v->sb);
  return 0;
}
static int vstat(void *x, const char *p, struct kbl_file_stat *s) {
  struct volume *v = x; char *q = 0; enter(v);
  int r = pathof(v, p, &q); if (!r) r = rawstat(v, q, s);
  ext4_user_free(q); return r;
}
static int syncv(void *x, void *f) {
  struct volume *v = x;
  int r;
  (void)f;
  enter(v);
  if (v->write_fault)
    return -KBL_EIO;
  if (v->ro)
    return 0;
  if (!v->mounted)
    return v->dev.flush ? v->dev.flush(v->dev.opaque) : 0;
  r = ext4_cache_flush(v->mp);
  if (r)
    return status(r);
  return v->dev.flush ? v->dev.flush(v->dev.opaque) : 0;
}
static int closev(void *x, void *y) {
  struct volume *v = x;
  struct handle *h = y;
  int r;
  enter(v);
  if (!h || h->v != v)
    return -KBL_EBADF;
  if (v->write_fault)
    return -KBL_EIO;
  r = ext4_fclose(&h->f);
  if (r)
    return status(r);
  --v->open_count;
  ext4_user_free(h);
  return 0;
}
static int parent_exists(struct volume *, const char *);
static int openv(void *x, const char *p, unsigned flags, void **out) {
  struct volume *v = x; struct handle *h = 0; struct kbl_file_stat s;
  char *q = 0; int r, exists, of;
  enter(v); *out = 0;
  if (!(flags & (KBL_FILE_READ | KBL_FILE_WRITE)) || flags & ~63u) return -KBL_EINVAL;
  if ((flags & (KBL_FILE_CREATE | KBL_FILE_TRUNC | KBL_FILE_APPEND)) &&
      !(flags & KBL_FILE_WRITE)) return -KBL_EINVAL;
  if ((flags & (KBL_FILE_WRITE | KBL_FILE_CREATE | KBL_FILE_TRUNC | KBL_FILE_APPEND)) &&
      (v->ro || v->write_fault)) return v->ro ? -KBL_EROFS : -KBL_EIO;
  if (v->open_count == SIZE_MAX) return -KBL_EOVERFLOW;
  r = pathof(v, p, &q); if (r) return r;
  r = rawstat(v, q, &s); exists = !r;
  if (!r && s.type == KBL_FILE_DIRECTORY) { r = -KBL_EISDIR; goto Done; }
  if (r && r != -KBL_ENOENT) goto Done;
  if (r && !(flags & KBL_FILE_CREATE)) goto Done;
  if (r == -KBL_ENOENT && (r = parent_exists(v, q))) goto Done;
  if ((flags & KBL_FILE_EXCL) && exists) { r = -KBL_EEXIST; goto Done; }
  h = ext4_user_calloc(1, sizeof(*h));
  if (!h) { r = -KBL_ENOMEM; goto Done; }
  h->v = v; h->flags = flags;
  of = (flags & KBL_FILE_WRITE) ? ((flags & KBL_FILE_READ) ? O_RDWR : O_WRONLY) : O_RDONLY;
  if (flags & KBL_FILE_CREATE) of |= O_CREAT;
  if (flags & KBL_FILE_TRUNC) of |= O_TRUNC;
  if (flags & KBL_FILE_APPEND) of |= O_APPEND;
  if (flags & KBL_FILE_EXCL) of |= O_EXCL;
  r = ext4_fopen2(&h->f, q, of);
  if (r || v->write_fault) {
    if (!r) ext4_fclose(&h->f);
    r = v->write_fault ? -KBL_EIO : status(r);
    ext4_user_free(h); goto Done;
  }
  ++v->open_count; *out = h; r = 0;
Done:
  ext4_user_free(q); return r;
}
static int readv(void *x, void *y, uint64_t off, void *p, size_t n,
                 size_t *done) {
  struct volume *v = x;
  struct handle *h = y;
  int r;
  *done = 0;
  enter(v);
  if (!v->mounted)
    return -KBL_EIO;
  if (!h || h->v != v || !(h->flags & KBL_FILE_READ))
    return -KBL_EBADF;
  if (off > INT64_MAX)
    return -KBL_EOVERFLOW;
  if (off >= ext4_fsize(&h->f))
    return 0;
  r = ext4_fseek(&h->f, (int64_t)off, SEEK_SET);
  if (!r)
    r = ext4_fread(&h->f, p, n, done);
  return status(r);
}
static int writev(void *x, void *y, uint64_t off, const void *p, size_t n,
                  size_t *done) {
  struct volume *v = x;
  struct handle *h = y;
  int r;
  *done = 0;
  enter(v);
  if (!v->mounted)
    return -KBL_EIO;
  if (!h || h->v != v || !(h->flags & KBL_FILE_WRITE))
    return -KBL_EBADF;
  if (v->ro)
    return -KBL_EROFS;
  if (v->write_fault)
    return -KBL_EIO;
  if (off > INT64_MAX || n > INT64_MAX - off)
    return -KBL_EOVERFLOW;
  r = ext4_fseek(&h->f, (int64_t)off, SEEK_SET);
  if (!r)
    r = ext4_fwrite(&h->f, p, n, done);
  return v->write_fault ? -KBL_EIO : status(r);
}
static int fstatv(void *x, void *y, struct kbl_file_stat *s) {
  struct volume *v = x;
  struct handle *h = y;
  enter(v);
  if (!h || h->v != v)
    return -KBL_EBADF;
  memset(s, 0, sizeof(*s));
  s->bytes = ext4_fsize(&h->f);
  s->type = KBL_FILE_REGULAR;
  s->flags = v->ro ? KBL_FILE_READ_ONLY : 0;
  s->block_bytes = ext4_sb_get_block_size(v->sb);
  return 0;
}
static int listv(void *x, const char *p, uint64_t index, char *name, size_t cap,
                 struct kbl_file_stat *s, int *end) {
  struct volume *v = x; ext4_dir d; const ext4_direntry *e;
  char *q = 0, *child = 0; int r; unsigned rf = v->read_fault; uint64_t k = 0;
  enter(v); *end = 0;
  r = pathof(v, p, &q); if (r) return r;
  r = ext4_dir_open(&d, q); if (r) { r = status(r); goto Done; }
  for (;;) {
    r = ext4_kbl_dir_next(&d, &e);
    if (r) { r = status(r); goto Closed; }
    if (!e) break;
    size_t n = e->name_length;
    for (size_t ni = 0; ni < n; ++ni) if (!e->name[ni] || e->name[ni] == '/') { r = -KBL_EIO; goto Closed; }
    if ((n == 1 && e->name[0] == '.') || (n == 2 && e->name[0] == '.' && e->name[1] == '.')) continue;
    if (k++ != index) continue;
    if (n >= cap) { r = -KBL_ENAMETOOLONG; goto Closed; }
    memcpy(name, e->name, n); name[n] = 0;
    size_t qn = strlen(q), slash = q[qn - 1] != '/';
    if (qn > SIZE_MAX - slash - 1 || n > SIZE_MAX - qn - slash - 1) { r = -KBL_EOVERFLOW; goto Closed; }
    child = ext4_user_malloc(qn + slash + n + 1);
    if (!child) { r = -KBL_ENOMEM; goto Closed; }
    memcpy(child, q, qn); if (slash) child[qn++] = '/'; memcpy(child + qn, name, n + 1);
    r = rawstat(v, child, s); goto Closed;
  }
  r = status(ext4_dir_close(&d));
  if (v->read_fault != rf) r = -KBL_EIO;
  if (!r) *end = 1;
  goto Done;
Closed:
  ext4_dir_close(&d);
Done:
  ext4_user_free(child); ext4_user_free(q); return r;
}
static int writable(struct volume *v) {
  enter(v);
  return !v->mounted      ? -KBL_EIO
         : v->ro          ? -KBL_EROFS
         : v->write_fault ? -KBL_EIO
                          : 0;
}
static int parent_exists(struct volume *v, const char *q) {
  size_t n = strlen(q); char *p = ext4_user_malloc(n + 1);
  struct kbl_file_stat s; int r;
  if (!p) return -KBL_ENOMEM;
  memcpy(p, q, n + 1); char *slash = strrchr(p, '/');
  if (!slash) { ext4_user_free(p); return -KBL_EINVAL; }
  if (slash < p + strlen(v->mp)) strcpy(p, v->mp); else *slash = 0;
  r = rawstat(v, p, &s); ext4_user_free(p);
  return r ? r : s.type == KBL_FILE_DIRECTORY ? 0 : -KBL_ENOTDIR;
}
static int mkdirv(void *x, const char *p) {
  struct volume *v = x; char *q = 0; struct kbl_file_stat s;
  int r = writable(v); if (r) return r;
  r = pathof(v, p, &q); if (r) return r;
  r = rawstat(v, q, &s);
  if (!r) r = -KBL_EEXIST;
  else if (r == -KBL_ENOENT && !(r = parent_exists(v, q))) {
    r = ext4_dir_mk(q); r = v->write_fault ? -KBL_EIO : status(r);
  }
  ext4_user_free(q); return r;
}
static int unlinkv(void *x, const char *p) {
  struct volume *v = x; char *q = 0, name[256]; struct kbl_file_stat s, t; int r, end;
  r = writable(v); if (r) return r;
  if (!strcmp(p, "/")) return -KBL_EBUSY;
  r = pathof(v, p, &q); if (r) return r;
  r = rawstat(v, q, &s); if (r) goto Done;
  if (s.type == KBL_FILE_DIRECTORY) {
    r = listv(v, p, 0, name, sizeof(name), &t, &end); if (r) goto Done;
    if (!end) { r = -KBL_ENOTEMPTY; goto Done; }
    r = ext4_dir_rm(q);
  } else r = ext4_fremove(q);
  r = v->write_fault ? -KBL_EIO : status(r);
Done:
  ext4_user_free(q); return r;
}
static int renamev(void *x, const char *a, const char *b) {
  struct volume *v = x; char *q = 0, *z = 0; struct kbl_file_stat s, t;
  int r = writable(v); if (r) return r;
  if (!strcmp(a, "/") || !strcmp(b, "/")) return -KBL_EBUSY;
  if ((r = pathof(v, a, &q)) || (r = pathof(v, b, &z))) goto Done;
  if ((r = rawstat(v, q, &s))) goto Done;
  r = rawstat(v, z, &t);
  if (!r) { r = -KBL_EEXIST; goto Done; }
  if (r != -KBL_ENOENT || (r = parent_exists(v, z))) goto Done;
  r = s.type == KBL_FILE_DIRECTORY ? ext4_dir_mv(q, z) : ext4_frename(q, z);
  r = v->write_fault ? -KBL_EIO : status(r);
Done:
  ext4_user_free(z); ext4_user_free(q); return r;
}
static int unmountv(void *x) {
  struct volume *v = x;
  int r;
  enter(v);
  if (v->open_count)
    return -KBL_EBUSY;
  if (v->write_fault)
    return -KBL_EIO;
  if (v->mounted) {
    r = syncv(v, 0);
    if (r)
      return r;
    if (v->journal) {
      r = ext4_journal_stop(v->mp);
      if (r) {
        v->write_fault = 1;
        return status(r);
      }
      v->journal = 0;
    }
    r = ext4_umount(v->mp);
    if (r)
      return status(r);
    v->mounted = 0;
  } /* Fini writes the clean superblock: persist before source release. */
  if (!v->ro && v->dev.flush) {
    r = v->dev.flush(v->dev.opaque);
    if (r)
      return r;
  }
  ext4_device_unregister(v->name);
  ext4_user_free(v->bi.ph_bbuf);
  ext4_user_free(v);
  return 0;
}
static const struct kbl_fs_ops ops = {unmountv, openv,   closev,  readv,
                                      writev,   fstatv,  vstat,   listv,
                                      mkdirv,   unlinkv, renamev, syncv};
int kbl_ext4_mount(const struct kbl_fs_host *host,
                   const struct kbl_fs_device *dev, const char *type,
                   unsigned flags, struct kbl_fs *out) {
  struct volume *v;
  struct ext4_sblock sb;
  uint32_t incompat, rocompat, bs;
  uint64_t blocks;
  int r;
  if (!out)
    return -KBL_EINVAL;
  memset(out, 0, sizeof(*out));
  if (!host || !host->allocate || !host->release || !dev || !dev->read_at ||
      flags & ~KBL_FS_READ_ONLY)
    return -KBL_EINVAL;
  if (type && strcmp(type, "ext4") && strcmp(type, "ext3") &&
      strcmp(type, "ext2") && strcmp(type, "auto"))
    return -KBL_ENOSYS;
  if (dev->bytes < 2048 || !dev->sector_bytes || dev->sector_bytes > 4096 ||
      (dev->sector_bytes & (dev->sector_bytes - 1)))
    return -KBL_EINVAL;
  r = exact_read(dev, 1024, &sb, sizeof(sb));
  if (r)
    return -KBL_EIO;
  if (sb.magic != 0xef53)
    return -KBL_ENODEV;
  if (sb.log_block_size > 6)
    return -KBL_ENOSYS;
  bs = 1024u << sb.log_block_size;
  if (bs < dev->sector_bytes)
    return -KBL_ENOSYS;
  blocks = sb.blocks_count_lo;
  if (sb.features_incompatible & 0x80u)
    blocks |= (uint64_t)sb.blocks_count_hi << 32;
  if (blocks > dev->bytes / bs)
    return -KBL_EIO;
  incompat = sb.features_incompatible;
  rocompat = sb.features_read_only;
  if (incompat & ~CONFIG_SUPPORTED_FINCOM)
    return -KBL_ENOSYS;
  flags |= dev->flags & KBL_FS_READ_ONLY;
  if (!(flags & KBL_FS_READ_ONLY)) {
    if (!dev->write_at || !dev->flush)
      return -KBL_EROFS;
    if (rocompat & ~CONFIG_SUPPORTED_FRO_COM)
      return -KBL_ENOSYS; /* No recovery/replay is invoked here. Explicit repair
                             belongs to an offline tool. */
    if (!(sb.state & 1u) || (sb.state & 2u) || (incompat & 4u))
      return -KBL_EIO;
  }
  allocator = host;
  v = ext4_user_calloc(1, sizeof(*v));
  if (!v)
    return -KBL_ENOMEM;
  v->host = *host;
  v->dev = *dev;
  v->ro = !!(flags & KBL_FS_READ_ONLY);
  /* Distinct live volume allocations have distinct pointer-derived internal
   * names; no mount counter/table or model-specific bound is required. */
  v->name[0] = 'k';
  for (size_t i = 0; i < 2 * sizeof(uintptr_t); ++i)
    v->name[i + 1] = "0123456789abcdef"[((uintptr_t)v >> (4 * (2 * sizeof(uintptr_t) - i - 1))) & 15];
  v->name[2 * sizeof(uintptr_t) + 1] = 0;
  v->mp[0] = '/'; strcpy(v->mp + 1, v->name); strcat(v->mp, "/");
  v->bi.ph_bsize = dev->sector_bytes;
  v->bi.ph_bcnt = dev->bytes / dev->sector_bytes;
  v->bi.ph_bbuf = ext4_user_malloc(dev->sector_bytes);
  if (!v->bi.ph_bbuf) {
    ext4_user_free(v);
    return -KBL_ENOMEM;
  }
  v->bi.open = bopen;
  v->bi.bread = bread;
  v->bi.bwrite = bwrite;
  v->bi.close = bclose;
  v->bi.p_user = v;
  v->bd.bdif = &v->bi;
  v->bd.part_size = v->bi.ph_bcnt * v->bi.ph_bsize;
  r = ext4_device_register(&v->bd, v->name);
  if (r)
    goto fail;
  r = ext4_mount(v->name, v->mp, v->ro != 0);
  if (r) {
    ext4_device_unregister(v->name);
    goto fail;
  }
  v->mounted = 1;
  r = ext4_get_sblock(v->mp, &v->sb);
  if (!r && !v->ro) {
    r = ext4_journal_start(v->mp);
    if (!r)
      v->journal = !!(sb.features_compatible & 4u);
  }
  if (r) { /* No retry/flush after an uncertain mount failure. */
    v->ro = 1;
    ext4_kbl_abort_mount(v->mp);
    ext4_device_unregister(v->name);
    goto fail;
  }
  out->ops = &ops;
  out->opaque = v;
  out->flags = flags;
  out->type = "ext4";
  return 0;
fail:
  ext4_user_free(v->bi.ph_bbuf);
  ext4_user_free(v);
  return status(r);
}
