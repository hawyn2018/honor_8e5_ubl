/* SPDX-License-Identifier: MIT -- read-only standard UEFI protocol inventory.
 * Only public Boot Services are called. Qseecom is probed by GUID presence;
 * its methods and all unknown protocol ABIs are never invoked. Two entries
 * per page are returned through standard ExitData (EFI_ABORTED by design).
 */
#include "../../uefi/kbl_efi_types.h"
typedef kefi_status (*locate_handles_fn)(unsigned, const struct kefi_guid *,
                                         void *, size_t *, kefi_handle **);
typedef kefi_status (*protocols_fn)(kefi_handle, struct kefi_guid ***,
                                    size_t *);
typedef kefi_status (*exit_fn)(kefi_handle, kefi_status, size_t, uint16_t *);
kefi_status EfiMain(kefi_handle, struct kefi_system_table *);
static kefi_status (*volatile relocation_anchor)(
    kefi_handle, struct kefi_system_table *) = EfiMain;
static const struct kefi_guid loaded = KEFI_LOADED_GUID;
static const struct kefi_guid qseecom = {
    0xa74862ce,
    0x680f,
    0x4fe1,
    {0xa3, 0x11, 0xdf, 0x41, 0xf4, 0x03, 0x03, 0x91}};
struct item {
  size_t handle, index;
  struct kefi_guid guid;
};
/* Fixed two-record page: uint64/size_t decimal fields and two GUIDs fit
 * within384 UTF-16 units, including the terminating NUL. */
#define REPORT_UNITS 384u
static unsigned current_el(void) {
#ifdef KBL_EFI_APPS_TEST
  extern unsigned kbl_efi_apps_test_current_el(void);
  return kbl_efi_apps_test_current_el();
#else
  uint64_t n;
  __asm__ volatile("mrs %0, CurrentEL" : "=r"(n));
  return (unsigned)((n >> 2) & 3);
#endif
}
static void text(uint16_t *out, size_t *at, const char *s) {
  while (*s && *at < REPORT_UNITS - 1)
    out[(*at)++] = (unsigned char)*s++;
  out[*at] = 0;
}
static void decimal(uint16_t *out, size_t *at, uint64_t n) {
  char b[21];
  size_t p = 20;
  b[p] = 0;
  do {
    b[--p] = (char)('0' + n % 10);
    n /= 10;
  } while (n);
  text(out, at, b + p);
}
static void hex(uint16_t *out, size_t *at, uint64_t n, unsigned digits) {
  const char *chars = "0123456789abcdef";
  while (digits && *at < REPORT_UNITS - 1) {
    --digits;
    out[(*at)++] = chars[(n >> (digits * 4)) & 15];
  }
  out[*at] = 0;
}
static void guid_text(uint16_t *out, size_t *at, const struct kefi_guid *g) {
  hex(out, at, g->a, 8);
  text(out, at, "-");
  hex(out, at, g->b, 4);
  text(out, at, "-");
  hex(out, at, g->c, 4);
  text(out, at, "-");
  for (unsigned i = 0; i < 8; ++i) {
    if (i == 2)
      text(out, at, "-");
    hex(out, at, g->d[i], 2);
  }
}
static int parse_skip(struct kefi_loaded_image *li, size_t *out) {
  const uint16_t *p = li->options;
  size_t n = li->options_bytes / 2;
  *out = 0;
  if (!li->options_bytes)
    return 1;
  if (!p || (li->options_bytes & 1) || p[n - 1])
    return 0;
  for (size_t i = 0; i + 5 < n; ++i)
    if (p[i] == 's' && p[i + 1] == 'k' && p[i + 2] == 'i' && p[i + 3] == 'p' &&
        p[i + 4] == '=') {
      size_t j = i + 5, v = 0;
      if (j >= n || p[j] < '0' || p[j] > '9')
        return 0;
      while (j < n && p[j] >= '0' && p[j] <= '9') {
        if (v > (SIZE_MAX - (p[j] - '0')) / 10)
          return 0;
        v = v * 10 + p[j++] - '0';
      }
      if (j < n && p[j] && p[j] != ' ')
        return 0;
      *out = v;
      return 1;
    }
  return 1;
}
kefi_status EfiMain(kefi_handle image, struct kefi_system_table *st) {
  struct kefi_boot_services *bs;
  struct kefi_loaded_image *li = 0;
  kefi_handle *handles = 0;
  size_t count = 0, skip = 0, total = 0, found = 0, failed = 0;
  struct item page[2];
  kefi_status s, cleanup = 0;
  unsigned before = current_el();
  void *qsee = 0;
  unsigned has_qsee = 0;
  uint16_t report[REPORT_UNITS];
  size_t at = 0;
  if (!image || !st || st->header.signature != KEFI_SYSTEM_SIGNATURE ||
      before < 1 || before > 3 || relocation_anchor != EfiMain ||
      !(bs = st->boot_services) ||
      bs->header.signature != KEFI_BOOT_SIGNATURE ||
      bs->header.bytes < offsetof(struct kefi_boot_services, locate_protocol) +
                             sizeof(bs->locate_protocol) ||
      !bs->handle_protocol || !bs->locate_handle_buffer ||
      !bs->protocols_per_handle || !bs->free_pool || !bs->exit_image)
    return KEFI_UNSUPPORTED;
  s = bs->handle_protocol(image, &loaded, (void **)&li);
  if (s || !li || !parse_skip(li, &skip))
    return KEFI_INVALID;
  if (bs->locate_protocol)
    has_qsee = bs->locate_protocol(&qseecom, 0, &qsee) == 0 && qsee != 0;
  s = ((locate_handles_fn)bs->locate_handle_buffer)(0, 0, 0, &count, &handles);
  if (s || !handles || count > SIZE_MAX / sizeof(*handles)) {
    if (handles)
      (void)bs->free_pool(handles);
    return s ? s : KEFI_UNSUPPORTED;
  }
  for (size_t i = 0; i < count; ++i) {
    struct kefi_guid **guids = 0;
    size_t n = 0;
    s = ((protocols_fn)bs->protocols_per_handle)(handles[i], &guids, &n);
    if (s || !guids || n > SIZE_MAX / sizeof(*guids)) {
      if(failed==SIZE_MAX)cleanup=KEFI_OUT_OF_RESOURCES;else ++failed;
      if (guids && (s = bs->free_pool(guids)) != 0) {
        cleanup = s;
        break;
      }
      if(cleanup)break;
      continue;
    }
    for (size_t j = 0; j < n; ++j) {
      if (!guids[j]) {
        if(failed==SIZE_MAX){cleanup=KEFI_OUT_OF_RESOURCES;break;}++failed;
        continue;
      }
      if (total >= skip && found < 2) {
        page[found].handle = i;
        page[found].index = j;
        page[found].guid = *guids[j];
        ++found;
      }
      if(total==SIZE_MAX){cleanup=KEFI_OUT_OF_RESOURCES;break;}
      ++total;
    }
    s = bs->free_pool(guids);
    if (s) {
      cleanup = s;
      break;
    }
    if(cleanup)break;
  }
  s = bs->free_pool(handles);
  if (s && !cleanup)
    cleanup = s;
  if (current_el() != before)
    return KEFI_ACCESS_DENIED;
  if (cleanup)
    return cleanup;
  report[0] = 0;
  text(report, &at, "skip=");
  decimal(report, &at, skip);
  text(report, &at, " next=");
  decimal(report, &at, skip < total ? skip + found : total);
  text(report, &at, " total=");
  decimal(report, &at, total);
  text(report, &at, " EL=");
  decimal(report, &at, before);
  text(report, &at, has_qsee ? " qseecom=yes" : " qseecom=no");
  text(report, &at, " errors=");
  decimal(report, &at, failed);
  text(report, &at, "\r\n");
  for (size_t i = 0; i < found; ++i) {
    text(report, &at, "h=");
    decimal(report, &at, page[i].handle);
    text(report, &at, " p=");
    decimal(report, &at, page[i].index);
    text(report, &at, " ");
    guid_text(report, &at, &page[i].guid);
    text(report, &at, "\r\n");
  }
  /* Standard Exit copies the diagnostic text for StartImage's caller. It uses
   * EFI_ABORTED so firmware preserves ExitData; this is an inventory result. */
  return ((exit_fn)bs->exit_image)(image, KEFI_ERROR(21), (at + 1) * 2, report);
}
