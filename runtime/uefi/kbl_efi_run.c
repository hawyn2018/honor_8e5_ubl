/* SPDX-License-Identifier: MIT
 * Explicit standard UEFI application launcher. Source PE preflight is bounds
 * checking, not a sandbox, Secure Boot bypass, custom loader or fault catcher.
 * No startup hooks, timers, USB registration, ExitBootServices or EL
 * transition.
 */
#include "kbl_efi_run.h"
#include "../kbl_file.h"
#define NOT_FOUND KEFI_ERROR(14)
#define LOAD_ERROR KEFI_ERROR(1)
#define BAD_FORMAT KEFI_ERROR(11)
#define SECURITY_VIOLATION KEFI_ERROR(26)
typedef kefi_status (*load_fn)(unsigned char, kefi_handle, void *, void *,
                               size_t, kefi_handle *);
typedef kefi_status (*start_fn)(kefi_handle, size_t *, uint16_t **);
typedef kefi_status (*unload_fn)(kefi_handle);
static const struct kefi_guid loaded_guid = KEFI_LOADED_GUID;
static void clear(void *p, size_t n) {
  unsigned char *d = p;
  while (n--)
    *d++ = 0;
}
static void copy(void *p, const void *q, size_t n) {
  unsigned char *d = p;
  const unsigned char *s = q;
  while (n--)
    *d++ = *s++;
}
static uint16_t u16(const unsigned char *p) {
  return (uint16_t)p[0] | (uint16_t)p[1] << 8;
}
static uint32_t u32(const unsigned char *p) {
  return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 |
         (uint32_t)p[3] << 24;
}
static uint64_t u64(const unsigned char *p) {
  return (uint64_t)u32(p) | (uint64_t)u32(p + 4) << 32;
}
static int bounds(uint64_t off, uint64_t n, uint64_t cap) {
  return off <= cap && n <= cap - off;
}
static int power2(uint32_t n) { return n && !(n & (n - 1)); }
static int overlap(uint64_t a, uint64_t n, uint64_t b, uint64_t z) {
  return n && z && (a <= b ? b - a < n : a - b < z);
}
static int mapped(const unsigned char *b, size_t table, unsigned count,
                  uint32_t rva, uint32_t bytes, uint32_t headers, size_t file,
                  size_t *out) {
  if (rva < headers && bounds(rva, bytes, headers) &&
      bounds(rva, bytes, file)) {
    *out = rva;
    return 1;
  }
  for (unsigned i = 0; i < count; ++i) {
    const unsigned char *s = b + table + i * 40;
    uint32_t va = u32(s + 12), raw = u32(s + 16), ptr = u32(s + 20);
    if (rva >= va && bounds((uint64_t)rva - va, bytes, raw) &&
        bounds(ptr, (uint64_t)raw, file)) {
      *out = (size_t)ptr + (size_t)(rva - va);
      return 1;
    }
  }
  return 0;
}
int kbl_efi_pe_validate(const void *memory, size_t bytes) {
  const unsigned char *b = memory;
  size_t pe, op, table, reloc_file;
  uint32_t image, headers, entry, sa, fa, dirs;
  unsigned sections, optional;
  int executable = 0;
  if (!b || bytes < 64 || bytes > UINTPTR_MAX - (uintptr_t)b || b[0] != 'M' ||
      b[1] != 'Z')
    return -KBL_EINVAL;
  pe = u32(b + 60);
  if (pe < 64 || !bounds(pe, 24, bytes) || u32(b + pe) != 0x00004550)
    return -KBL_EINVAL;
  if (u16(b + pe + 4) != 0xaa64)
    return -KBL_ENOSYS;
  sections = u16(b + pe + 6);
  optional = u16(b + pe + 20);
  op = pe + 24;
  if (!sections || optional < 112 ||
      !bounds(op, optional, bytes))
    return -KBL_EINVAL;
  if (u16(b + op) != 0x20b || u16(b + op + 68) != 10)
    return -KBL_ENOSYS;
  image = u32(b + op + 56);
  headers = u32(b + op + 60);
  entry = u32(b + op + 16);
  sa = u32(b + op + 32);
  fa = u32(b + op + 36);
  dirs = u32(b + op + 108);
  table = op + optional;
  if (!image || !headers || headers > image ||
      headers > bytes || !entry || entry >= image || !power2(sa) ||
      !power2(fa) || sa < fa || fa > 65536 || image % sa || headers % fa ||
      u64(b + op + 24) > UINT64_MAX - image || dirs > (optional - 112) / 8 ||
      !bounds(table, (size_t)sections * 40, headers))
    return -KBL_EINVAL;
  for (unsigned i = 0; i < sections; ++i) {
    const unsigned char *s = b + table + i * 40;
    uint32_t vs = u32(s + 8), va = u32(s + 12), raw = u32(s + 16),
             ptr = u32(s + 20), span = vs > raw ? vs : raw;
    if (!bounds(va, span, image) || (span && (va < headers || va % sa)) ||
        (raw &&
         (!bounds(ptr, raw, bytes) || ptr < headers || ptr % fa || raw % fa)))
      return -KBL_EINVAL;
    if (entry >= va && (uint64_t)entry - va < raw &&
        (u32(s + 36) & 0x20000000u))
      executable = 1;
    for (unsigned j = 0; j < i; ++j) {
      const unsigned char *t = b + table + j * 40;
      uint32_t v = u32(t + 8), r = u32(t + 16);
      if (overlap(va, span, u32(t + 12), v > r ? v : r) ||
          overlap(ptr, raw, u32(t + 20), r))
        return -KBL_EINVAL;
    }
  }
  if (!executable)
    return -KBL_EINVAL;
  for (uint32_t i = 0; i < dirs; ++i) {
    uint32_t rva = u32(b + op + 112 + i * 8), n = u32(b + op + 116 + i * 8);
    if (!rva && !n)
      continue;
    if (!rva || !n || !bounds(rva, n, i == 4 ? bytes : image))
      return -KBL_EINVAL;
  }
  if (dirs > 5) {
    uint32_t rva = u32(b + op + 112 + 5 * 8), n = u32(b + op + 116 + 5 * 8);
    if (n) {
      size_t at = 0;
      if (!mapped(b, table, sections, rva, n, headers, bytes, &reloc_file))
        return -KBL_EINVAL;
      while (at < n) {
        const unsigned char *r = b + reloc_file + at;
        uint32_t page, block;
        if (n - at < 8)
          return -KBL_EINVAL;
        page = u32(r);
        block = u32(r + 4);
        if (page % 4096 || block < 8 || (block & 3) || block > n - at)
          return -KBL_EINVAL;
        for (size_t k = 8; k < block; k += 2) {
          uint16_t e = u16(r + k);
          unsigned kind = e >> 12;
          uint64_t target = (uint64_t)page + (e & 4095);
          if (kind == 0)
            continue;
          if (kind != 10)
            return -KBL_ENOSYS;
          if (!bounds(target, 8, image))
            return -KBL_EINVAL;
        }
        at += block;
      }
    }
  }
  return 0;
}
void kbl_efi_launcher_init(struct kbl_efi_launcher *l, kefi_handle parent,
                           struct kefi_system_table *st, void *o,
                           unsigned (*el)(void *), unsigned expected) {
  clear(l, sizeof(*l));
  l->parent = parent;
  l->system = st;
  l->opaque = o;
  l->current_el = el;
  l->expected_el = expected;
}
static int services(struct kbl_efi_launcher *l,
                    struct kefi_boot_services **out) {
  struct kefi_boot_services *b;
  if (!l || !l->parent || !l->system ||
      l->system->header.signature != KEFI_SYSTEM_SIGNATURE ||
      l->system->header.bytes < sizeof(*l->system) ||
      !(b = l->system->boot_services) ||
      b->header.signature != KEFI_BOOT_SIGNATURE ||
      b->header.bytes < offsetof(struct kefi_boot_services, unload_image) +
                            sizeof(b->unload_image) ||
      !b->load_image || !b->start_image || !b->unload_image ||
      !b->handle_protocol || !b->allocate_pool || !b->free_pool ||
      !l->current_el)
    return -KBL_ENOSYS;
  *out = b;
  return 0;
}
static int gone(kefi_status status, void *p) {
  return !p && (status == KEFI_INVALID || status == KEFI_UNSUPPORTED ||
                status == NOT_FOUND);
}
int kbl_efi_can_unload(const struct kbl_efi_launcher *l) {
  return !l || (!l->active && !l->exit_borrowed && !l->child && !l->file_buffer && !l->options &&
                !l->exit_data);
}
static int cleanup(struct kbl_efi_launcher *l) {
  struct kefi_boot_services *b;
  kefi_status s;
  void *p = 0;
  int e;
  if (!l)
    return -KBL_EINVAL;
  if (l->active)
    return -KBL_EBUSY;
  if (kbl_efi_can_unload(l)) {
    l->last_cleanup = 0;
    return 0;
  }
  if (!l->current_el || l->current_el(l->opaque) != l->expected_el) {
    l->last_cleanup = KEFI_ACCESS_DENIED;
    return -KBL_EPERM;
  }
  if ((e = services(l, &b))) {
    l->last_cleanup = KEFI_UNSUPPORTED;
    return e;
  }
  l->last_cleanup = 0;
  if (l->child) {
    s = ((unload_fn)b->unload_image)(l->child);
    if (!s)
      l->child = 0;
    else if (s == KEFI_INVALID || s == NOT_FOUND) {
      /* A standard UEFI application is automatically unloaded on return/Exit.
       * Confirm that the old LoadedImage handle is gone before releasing
       * options. */
      kefi_status probe = b->handle_protocol(l->child, &loaded_guid, &p);
      if (gone(probe, p))
        l->child = 0;
      else {
        l->last_cleanup = s;
        return -KBL_EIO;
      }
    } else {
      l->last_cleanup = s;
      return -KBL_EIO;
    }
  }
  l->prepared = 0;
  void **owned[3] = {&l->exit_data, &l->options, &l->file_buffer};
  for (unsigned i = 0; i < 3; ++i)
    if (*owned[i] && !(i == 0 && l->exit_borrowed)) {
      s = b->free_pool(*owned[i]);
      if (s) {
        l->last_cleanup = s;
        return -KBL_EIO;
      }
      *owned[i] = 0;
    }
  if (!l->exit_data)
    l->exit_bytes = 0;
  l->options_bytes = l->file_bytes = 0;
  return 0;
}
int kbl_efi_cleanup(struct kbl_efi_launcher *l) {
  if (l && l->exit_borrowed)
    return -KBL_EBUSY;
  return cleanup(l);
}
static int release_exit(void *owner) {
  struct kbl_efi_launcher *l = owner;
  if (!l)
    return -KBL_EINVAL;
  l->exit_borrowed = 0;
  return kbl_efi_cleanup(l);
}
static int options_valid(const uint16_t *p, size_t bytes) {
  if (!bytes)
    return p ? -KBL_EINVAL : 0;
  if (!p || bytes < 2 || bytes > UINT32_MAX || bytes > UINTPTR_MAX - (uintptr_t)p || (bytes & 1))
    return -KBL_EINVAL;
  size_t n = bytes / 2;
  if (p[n - 1])
    return -KBL_EINVAL;
  for (size_t i = 0; i < n - 1; ++i) {
    uint16_t c = p[i];
    if (!c)
      return -KBL_EINVAL;
    if (c >= 0xd800 && c <= 0xdbff) {
      if (i + 1 >= n - 1 || p[i + 1] < 0xdc00 || p[i + 1] > 0xdfff)
        return -KBL_EINVAL;
      ++i;
    } else if (c >= 0xdc00 && c <= 0xdfff)
      return -KBL_EINVAL;
  }
  return 0;
}
static void capture_exit(struct kbl_efi_launcher *l,
                         struct kbl_efi_run_result *r) {
  const unsigned char *p = l->exit_data;
  size_t units = l->exit_bytes / 2;
  if (!p)
    return;
  for (size_t i = 0; i < units; ++i) {
    uint16_t c = u16(p + i * 2);
    if (!c)
      return;
    if (r->exit_units + 1 >= KBL_EFI_EXIT_UNITS) {
      r->exit_truncated = 1;
      return;
    }
    r->exit_text[r->exit_units++] = c;
  }
  if (l->exit_bytes & 1)
    r->exit_truncated = 1;
}
static int run(struct kbl_efi_launcher *l, const void *image,
                size_t image_bytes, const uint16_t *options,
                size_t options_bytes, struct kbl_efi_run_result *r,
                struct kbl_efi_run_full_result *full, int prepare_only) {
  struct kefi_boot_services *b;
  struct kefi_loaded_image *loaded = 0;
  uint16_t *exit_data = 0;
  kefi_status s;
  int e, rc = 0;
  unsigned before;
  if (!r)
    return -KBL_EINVAL;
  clear(r, sizeof(*r));
  r->stage = KBL_EFI_VALIDATE;
  if (!l)
    return -KBL_EINVAL;
  if (l->active)
    return -KBL_EBUSY;
  if ((e = services(l, &b)))
    return e;
  if ((e = kbl_efi_cleanup(l))) {
    r->cleanup_status = l->last_cleanup;
    r->stage = KBL_EFI_CLEANUP;
    return e;
  }
  if ((e = kbl_efi_pe_validate(image, image_bytes)) ||
      (e = options_valid(options, options_bytes)))
    return e;
  before = l->current_el(l->opaque);
  r->el_before = before;
  if (!before || before != l->expected_el)
    return -KBL_EPERM;
  l->active = 1;
  r->stage = KBL_EFI_LOAD;
  s = b->allocate_pool(4, image_bytes, &l->file_buffer);
  if (s || !l->file_buffer) {
    r->status = s ? s : KEFI_OUT_OF_RESOURCES;
    rc = -KBL_ENOMEM;
    goto cleanup;
  }
  l->file_bytes = image_bytes;
  copy(l->file_buffer, image, image_bytes);
  if (options_bytes) {
    s = b->allocate_pool(4, options_bytes, &l->options);
    if (s || !l->options) {
      r->status = s ? s : KEFI_OUT_OF_RESOURCES;
      rc = -KBL_ENOMEM;
      goto cleanup;
    }
    l->options_bytes = options_bytes;
    copy(l->options, options, options_bytes);
  }
  if (l->current_el(l->opaque) != before) {
    rc = -KBL_EPERM;
    goto cleanup;
  }
  s = ((load_fn)b->load_image)(0, l->parent, 0, l->file_buffer, image_bytes,
                               &l->child);
  r->status = s;
  if (l->current_el(l->opaque) != before) {
    r->el_after = l->current_el(l->opaque);
    rc = -KBL_EPERM;
    goto cleanup;
  }
  if (s || !l->child) {
    rc = s == KEFI_OUT_OF_RESOURCES ? -KBL_ENOMEM : -KBL_EIO;
    goto cleanup;
  }
  r->stage = KBL_EFI_OPTIONS;
  s = b->handle_protocol(l->child, &loaded_guid, (void **)&loaded);
  r->status = s;
  if (s || !loaded) {
    rc = -KBL_EIO;
    goto cleanup;
  }
  if (loaded->code_type != 1 || loaded->system_table != l->system) {
    r->status = KEFI_UNSUPPORTED;
    rc = -KBL_ENOSYS;
    goto cleanup;
  }
  loaded->options = l->options;
  loaded->options_bytes = (uint32_t)options_bytes;
  if (loaded->options != l->options || loaded->options_bytes != options_bytes ||
      l->current_el(l->opaque) != before) {
    rc = -KBL_EPERM;
    goto cleanup;
  }
  if (prepare_only) {
    l->active = 0;
    l->prepared = 1;
    return 0;
  }
  r->stage = KBL_EFI_START;
  s = ((start_fn)b->start_image)(l->child, &l->exit_bytes, &exit_data);
  l->exit_data = exit_data;
  r->status = s;
  r->stage = KBL_EFI_RETURN;
  r->el_after = l->current_el(l->opaque);
  if (r->el_after != before) {
    rc = -KBL_EPERM;
  } else if (l->exit_data && l->exit_bytes > UINTPTR_MAX - (uintptr_t)l->exit_data) {
    rc = -KBL_EIO;
  } else {
    capture_exit(l, r);
    if (full && l->exit_data && l->exit_bytes) {
      l->exit_borrowed = 1;
      full->exit_text = l->exit_data;
      full->exit_units = l->exit_bytes / sizeof(uint16_t);
      full->exit_owner = l;
      full->release_exit = release_exit;
    }
  }
cleanup:
  if (!r->el_after)
    r->el_after = l->current_el(l->opaque);
  l->active = 0;
  e = cleanup(l);
  r->cleanup_status = l->last_cleanup;
  if (e) {
    r->stage = KBL_EFI_CLEANUP;
    if (!rc)
      rc = e;
  }
  return rc;
}
int kbl_efi_run(struct kbl_efi_launcher *l, const void *image,
                size_t image_bytes, const uint16_t *options,
                size_t options_bytes, struct kbl_efi_run_result *r) {
  return run(l,image,image_bytes,options,options_bytes,r,0,0);
}
int kbl_efi_run_full(struct kbl_efi_launcher *l, const void *image,
                size_t image_bytes, const uint16_t *options,
                size_t options_bytes, struct kbl_efi_run_full_result *r) {
  if (!r)
    return -KBL_EINVAL;
  clear(r,sizeof(*r));
  return run(l,image,image_bytes,options,options_bytes,&r->result,r,0);
}

int kbl_efi_prepare(struct kbl_efi_launcher *l, const void *image,
                   size_t bytes, const uint16_t *options, size_t option_bytes,
                   struct kbl_efi_run_result *r) {
  return run(l,image,bytes,options,option_bytes,r,0,1);
}
int kbl_efi_start_prepared(struct kbl_efi_launcher *l,
                          struct kbl_efi_run_result *r) {
  struct kefi_boot_services *b;
  uint16_t *exit_data=0;
  int e,rc=0;
  if(!l||!r)return -KBL_EINVAL;
  clear(r,sizeof(*r));r->stage=KBL_EFI_START;
  if(l->active||l->exit_borrowed)return -KBL_EBUSY;
  if(!l->prepared||!l->child)return -KBL_EINVAL;
  if((e=services(l,&b)))return e;
  unsigned before=l->current_el(l->opaque);r->el_before=before;
  if(!before||before!=l->expected_el)return -KBL_EPERM;
  l->active=1;l->prepared=0;
  r->status=((start_fn)b->start_image)(l->child,&l->exit_bytes,&exit_data);
  l->exit_data=exit_data;r->stage=KBL_EFI_RETURN;
  r->el_after=l->current_el(l->opaque);
  if(r->el_after!=before)rc=-KBL_EPERM;
  else if(l->exit_data&&l->exit_bytes>UINTPTR_MAX-(uintptr_t)l->exit_data)rc=-KBL_EIO;
  else capture_exit(l,r);
  l->active=0;e=cleanup(l);r->cleanup_status=l->last_cleanup;
  if(e){r->stage=KBL_EFI_CLEANUP;if(!rc)rc=e;}
  return rc;
}
