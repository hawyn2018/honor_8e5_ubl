/* SPDX-License-Identifier: MIT -- fake Boot Services model, no hardware. */
#include "../../kbl_file.h"
#include "../kbl_efi_run.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static unsigned checks, cases,
    el = 1, live, auto_exit, fail_alloc, alloc_calls, allocs, frees, load_calls,
    start_calls, unload_calls, handle_calls, fail_unload, fail_free,
    fail_handle, el_in_load, el_in_start, exit_units, nested,
    fail_alloc_with_buffer, null_child_on_load_error;
static size_t largest_pool_request, tested_source_bytes, tested_mapping_bytes;
static size_t tested_sections, tested_options_bytes, tested_full_exit_units;
static kefi_status load_status, start_status;
static struct kefi_system_table st;
static struct kefi_boot_services bs;
static struct kefi_loaded_image li;
static struct kbl_efi_launcher launcher;
static unsigned char *pe;
static size_t pe_bytes;
static const uint16_t options[] = {'r', 'e', 't',    'u',    'r',
                                   'n', ' ', 0x4e2d, 0x6587, 0};
static const uint16_t *expected_options = options;
static size_t expected_options_bytes = sizeof(options);
#define CHECK(x)                                                               \
  do {                                                                         \
    ++checks;                                                                  \
    if (!(x)) {                                                                \
      fprintf(stderr, "FAIL %d: %s\n", __LINE__, #x);                          \
      exit(1);                                                                 \
    }                                                                          \
  } while (0)
static unsigned read_el(void *p) {
  CHECK(p == &el);
  return el;
}
static kefi_status allocate(unsigned type, size_t n, void **p) {
  CHECK(type == 4);
  *p = 0;
  if (n > largest_pool_request)
    largest_pool_request = n;
  int failed = ++alloc_calls == fail_alloc;
  if (failed && !fail_alloc_with_buffer)
    return KEFI_OUT_OF_RESOURCES;
  *p = malloc(n);
  if (!*p)
    return KEFI_OUT_OF_RESOURCES;
  ++allocs;
  return failed ? KEFI_OUT_OF_RESOURCES : 0;
}
static kefi_status release(void *p) {
  CHECK(p);
  if (fail_free)
    return KEFI_ACCESS_DENIED;
  free(p);
  ++frees;
  return 0;
}
static kefi_status load(unsigned char policy, kefi_handle parent, void *path,
                        void *buffer, size_t n, kefi_handle *out) {
  CHECK(!policy && parent == (void *)0x1234 && !path && buffer != pe &&
        n == pe_bytes && !memcmp(buffer, pe, n));
  CHECK(launcher.active);
  ++load_calls;
  if (load_status && null_child_on_load_error) {
    *out = 0;
    live = 0;
    return load_status;
  }
  live = 1;
  *out = (void *)0x5678;
  li.code_type = 1;
  li.system_table = &st;
  if (el_in_load)
    el = 2;
  return load_status;
}
static kefi_status handle(kefi_handle h, const struct kefi_guid *g,
                          void **out) {
  CHECK(h == (void *)0x5678 && g->a == 0x5b1b31a1);
  ++handle_calls;
  *out = 0;
  if (!live)
    return KEFI_INVALID;
  if (fail_handle)
    return KEFI_ACCESS_DENIED;
  *out = &li;
  return 0;
}
static kefi_status start(kefi_handle h, size_t *n, uint16_t **exit_data) {
  CHECK(h == (void *)0x5678 && live && launcher.active);
  ++start_calls;
  CHECK(li.options_bytes == expected_options_bytes && li.options != expected_options &&
        !memcmp(li.options, expected_options, expected_options_bytes));
  if (nested) {
    struct kbl_efi_run_result r;
    unsigned before = load_calls;
    CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), &r) ==
              -KBL_EBUSY &&
          load_calls == before);
  }
  *n = 0;
  *exit_data = 0;
  if (exit_units) {
    kefi_status allocation_status = allocate(4, exit_units * 2, (void **)exit_data);
    if (allocation_status)
      return allocation_status;
    for (unsigned i = 0; i < exit_units; ++i)
      (*exit_data)[i] = 'A';
    *n = exit_units * 2;
  }
  if (auto_exit)
    live = 0;
  if (el_in_start)
    el = 2;
  return start_status;
}
static kefi_status unload(kefi_handle h) {
  CHECK(h == (void *)0x5678);
  ++unload_calls;
  if (!live)
    return KEFI_INVALID;
  if (fail_unload)
    return KEFI_ACCESS_DENIED;
  live = 0;
  return 0;
}
static void fresh(void) {
  CHECK(kbl_efi_can_unload(&launcher));
  CHECK(allocs == frees);
  memset(&bs, 0, sizeof(bs));
  memset(&st, 0, sizeof(st));
  memset(&li, 0, sizeof(li));
  el = 1;
  live = 0;
  auto_exit = fail_alloc = alloc_calls = load_calls = start_calls =
      unload_calls = handle_calls = fail_unload = fail_free = fail_handle =
          el_in_load = el_in_start = exit_units = nested = 0;
  fail_alloc_with_buffer = null_child_on_load_error = 0;
  load_status = start_status = 0;
  bs.header = (struct kefi_header){KEFI_BOOT_SIGNATURE, 0, sizeof(bs), 0, 0};
  bs.allocate_pool = allocate;
  bs.free_pool = release;
  bs.handle_protocol = handle;
  bs.load_image = (void *)load;
  bs.start_image = (void *)start;
  bs.unload_image = (void *)unload;
  st.header = (struct kefi_header){KEFI_SYSTEM_SIGNATURE, 0, sizeof(st), 0, 0};
  st.boot_services = &bs;
  kbl_efi_launcher_init(&launcher, (void *)0x1234, &st, &el, read_el, 1);
  ++cases;
}
static void ended(void) {
  CHECK(kbl_efi_can_unload(&launcher) && allocs == frees && !live);
}
static void put16(unsigned char *p, unsigned n) {
  p[0] = n;
  p[1] = n >> 8;
}
static void put32(unsigned char *p, unsigned n) {
  put16(p, n);
  put16(p + 2, n >> 16);
}
static unsigned get32(const unsigned char *p) {
  return p[0] | (unsigned)p[1] << 8 | (unsigned)p[2] << 16 |
         (unsigned)p[3] << 24;
}
static void unlimited_size_cases(struct kbl_efi_run_result *r) {
  unsigned char *original = pe;
  size_t original_bytes = pe_bytes;
  size_t old_limit = (size_t)64 * 1024 * 1024;
  tested_source_bytes = old_limit + 4096;
  unsigned char *large = calloc(1, tested_source_bytes);
  CHECK(large && original_bytes < tested_source_bytes);
  memcpy(large, original, original_bytes);
  large[tested_source_bytes - 1] = 0x5a;
  pe = large;
  pe_bytes = tested_source_bytes;
  fresh();
  CHECK(!kbl_efi_pe_validate(pe, pe_bytes));
  CHECK(!kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), r));
  CHECK(load_calls == 1 && start_calls == 1 && r->el_before == 1 && r->el_after == 1);
  ended();
  for (unsigned n = 1; n <= 2; ++n) {
    fresh();
    fail_alloc = n;
    CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), r) == -KBL_ENOMEM);
    CHECK(!load_calls && !start_calls);
    ended();
  }
  fresh();
  load_status = KEFI_OUT_OF_RESOURCES;
  CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), r) == -KBL_ENOMEM);
  CHECK(load_calls == 1 && !start_calls && unload_calls == 1);
  ended();
  free(large);
  pe = original;
  pe_bytes = original_bytes;

  unsigned char *mapped = malloc(original_bytes);
  CHECK(mapped);
  memcpy(mapped, original, original_bytes);
  size_t op = (size_t)get32(mapped + 60) + 24;
  unsigned sa = get32(mapped + op + 32);
  tested_mapping_bytes = old_limit + sa;
  CHECK(tested_mapping_bytes <= UINT32_MAX);
  put32(mapped + op + 56, (unsigned)tested_mapping_bytes);
  pe = mapped;
  fresh();
  CHECK(!kbl_efi_pe_validate(pe, pe_bytes));
  CHECK(!kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), r));
  CHECK(load_calls == 1 && start_calls == 1);
  ended();
  /* Accept the largest aligned image extent representable by its PE field;
   * firmware still owns mapping and can report genuine resource exhaustion. */
  put32(mapped + op + 56, UINT32_MAX - UINT32_MAX % sa);
  fresh();
  CHECK(!kbl_efi_pe_validate(pe, pe_bytes));
  load_status = KEFI_OUT_OF_RESOURCES;
  null_child_on_load_error = 1;
  CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), r) == -KBL_ENOMEM);
  CHECK(load_calls == 1 && !start_calls && !unload_calls);
  ended();
  free(mapped);
  pe = original;

  /* An allocation error with a non-null buffer must release that buffer,
   * together with any source/options allocation already acquired. */
  for (unsigned n = 1; n <= 2; ++n) {
    fresh();
    fail_alloc = n;
    fail_alloc_with_buffer = 1;
    CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), r) == -KBL_ENOMEM);
    CHECK(!load_calls && !start_calls);
    ended();
  }
  fresh();
  const void *wrapped = (const void *)(uintptr_t)(UINTPTR_MAX - 31);
  CHECK(kbl_efi_pe_validate(wrapped, 64) == -KBL_EINVAL);
  CHECK(kbl_efi_pe_validate(pe, SIZE_MAX) == -KBL_EINVAL);
  CHECK(kbl_efi_run(&launcher, wrapped, 64, options, sizeof(options), r) == -KBL_EINVAL);
  CHECK(!alloc_calls && !load_calls);
  ended();
}
static void sections_and_full_output_cases(void) {
  unsigned char *original = pe;
  size_t original_bytes = pe_bytes;
  unsigned sections = 97;
  tested_sections=sections;
  pe_bytes = 0x2000 + sections * 0x200;
  pe = calloc(1, pe_bytes);
  CHECK(pe);
  memcpy(pe,"MZ",2);put32(pe+60,0x80);put32(pe+0x80,0x4550);
  put16(pe+0x84,0xaa64);put16(pe+0x86,sections);put16(pe+0x94,240);put16(pe+0x98,0x20b);
  put32(pe+0xa8,0x2000);put32(pe+0xb8,4096);put32(pe+0xbc,512);
  put32(pe+0xd0,(sections+2)*4096);put32(pe+0xd4,0x2000);put16(pe+0xdc,10);
  for(unsigned i=0;i<sections;++i) {
    unsigned char *s=pe+0x188+i*40;memcpy(s,".text",5);
    put32(s+8,512);put32(s+12,0x2000+i*4096);put32(s+16,512);put32(s+20,0x2000+i*512);
    put32(s+36,0x60000020);put32(pe+0x2000+i*512,0xd65f03c0);
  }
  struct kbl_efi_run_result r;
  fresh();CHECK(!kbl_efi_run(&launcher,pe,pe_bytes,options,sizeof(options),&r));
  CHECK(load_calls==1&&start_calls==1);ended();
  free(pe);pe=original;pe_bytes=original_bytes;

  size_t units=6001;uint16_t *long_options=calloc(units,sizeof(*long_options));CHECK(long_options);
  for(size_t i=0;i<units-1;++i)long_options[i]='B';
  long_options[units-3]=0xd83d;long_options[units-2]=0xde00;
  expected_options=long_options;expected_options_bytes=units*sizeof(*long_options);
  tested_options_bytes=expected_options_bytes;
  fresh();CHECK(!kbl_efi_run(&launcher,pe,pe_bytes,long_options,expected_options_bytes,&r));
  CHECK(load_calls==1&&start_calls==1);ended();
  fresh();CHECK(kbl_efi_run(&launcher,pe,pe_bytes,long_options,(size_t)UINT32_MAX+1,&r)==-KBL_EINVAL);
  CHECK(kbl_efi_run(&launcher,pe,pe_bytes,(const uint16_t*)(uintptr_t)(UINTPTR_MAX-1),4,&r)==-KBL_EINVAL);
  CHECK(!alloc_calls&&!load_calls);ended();
  free(long_options);expected_options=options;expected_options_bytes=sizeof(options);

  struct kbl_efi_run_full_result full,second;
  fresh();exit_units=6000;
  tested_full_exit_units=exit_units;
  CHECK(!kbl_efi_run_full(&launcher,pe,pe_bytes,options,sizeof(options),&full));
  CHECK(full.result.exit_truncated&&full.result.exit_units==255);
  CHECK(full.exit_text==launcher.exit_data&&full.exit_units==6000&&full.release_exit);
  CHECK(full.exit_text[0]=='A'&&full.exit_text[5999]=='A'&&!kbl_efi_can_unload(&launcher));
  CHECK(kbl_efi_cleanup(&launcher)==-KBL_EBUSY);
  CHECK(kbl_efi_run_full(&launcher,pe,pe_bytes,options,sizeof(options),&second)==-KBL_EBUSY);
  CHECK(!full.release_exit(full.exit_owner));ended();

  fresh();exit_units=6000;
  CHECK(!kbl_efi_run_full(&launcher,pe,pe_bytes,options,sizeof(options),&full));
  fail_free=1;CHECK(full.release_exit(full.exit_owner)==-KBL_EIO);
  CHECK(launcher.exit_data&&!launcher.exit_borrowed&&!kbl_efi_can_unload(&launcher));
  fail_free=0;CHECK(!kbl_efi_cleanup(&launcher));ended();

  fresh();exit_units=6000;el_in_start=1;
  CHECK(kbl_efi_run_full(&launcher,pe,pe_bytes,options,sizeof(options),&full)==-KBL_EPERM);
  CHECK(!full.exit_text&&!full.release_exit&&launcher.exit_data);
  el=1;CHECK(!kbl_efi_cleanup(&launcher));ended();

  fresh();exit_units=6000;fail_alloc=3;
  CHECK(!kbl_efi_run_full(&launcher,pe,pe_bytes,options,sizeof(options),&full));
  CHECK(full.result.status==KEFI_OUT_OF_RESOURCES&&!full.exit_text&&!full.release_exit);
  ended();
}
int main(int argc, char **argv) {
  CHECK(argc == 2);
  FILE *f = fopen(argv[1], "rb");
  CHECK(f);
  fseek(f, 0, SEEK_END);
  pe_bytes = (size_t)ftell(f);
  rewind(f);
  pe = malloc(pe_bytes);
  CHECK(pe && fread(pe, 1, pe_bytes, f) == pe_bytes);
  fclose(f);
  CHECK(!kbl_efi_pe_validate(pe, pe_bytes));
  struct kbl_efi_run_result r;
  fresh();
  CHECK(!kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), &r));
  CHECK(r.stage == KBL_EFI_RETURN && r.el_before == 1 && r.el_after == 1 &&
        !r.status && !r.cleanup_status && start_calls == 1 &&
        unload_calls == 1);
  ended();
  fresh();
  auto_exit = 1;
  nested = 1;
  exit_units = 300;
  start_status = KEFI_ACCESS_DENIED;
  CHECK(!kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), &r));
  CHECK(r.status == KEFI_ACCESS_DENIED && r.exit_units == 255 &&
        r.exit_truncated && r.exit_text[255] == 0 && unload_calls == 1 &&
        handle_calls == 2);
  ended();
  for (unsigned n = 1; n <= 2; ++n) {
    fresh();
    fail_alloc = n;
    CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), &r) ==
          -KBL_ENOMEM);
    CHECK(!load_calls && !start_calls);
    ended();
  }
  fresh();
  load_status = KEFI_ERROR(26);
  CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), &r) ==
        -KBL_EIO);
  CHECK(!start_calls && unload_calls == 1 && r.status == load_status);
  ended();
  fresh();
  fail_handle = 1;
  CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), &r) ==
        -KBL_EIO);
  CHECK(!start_calls && unload_calls == 1);
  ended();
  fresh();
  fail_unload = 1;
  CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), &r) ==
        -KBL_EIO);
  CHECK(r.stage == KBL_EFI_CLEANUP && r.cleanup_status == KEFI_ACCESS_DENIED &&
        launcher.child && launcher.options && launcher.file_buffer &&
        allocs > frees && !kbl_efi_can_unload(&launcher));
  unsigned a = allocs;
  CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), &r) ==
            -KBL_EIO &&
        allocs == a);
  fail_unload = 0;
  CHECK(!kbl_efi_cleanup(&launcher));
  ended();
  fresh();
  fail_free = 1;
  CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), &r) ==
        -KBL_EIO);
  CHECK(!launcher.child && launcher.options && launcher.file_buffer &&
        r.cleanup_status == KEFI_ACCESS_DENIED);
  fail_free = 0;
  CHECK(!kbl_efi_cleanup(&launcher));
  ended();
  fresh();
  el_in_start = 1;
  CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), &r) ==
        -KBL_EPERM);
  CHECK(r.el_before == 1 && r.el_after == 2 && !unload_calls && launcher.child);
  el = 1;
  CHECK(!kbl_efi_cleanup(&launcher));
  ended();
  fresh();
  el_in_load = 1;
  CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), &r) ==
        -KBL_EPERM);
  CHECK(!start_calls && !unload_calls && !handle_calls && launcher.child);
  el = 1;
  CHECK(!kbl_efi_cleanup(&launcher));
  ended();
  fresh();
  el = 2;
  CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, sizeof(options), &r) ==
            -KBL_EPERM &&
        !alloc_calls && !load_calls);
  el = 1;
  ended();
  fresh();
  uint16_t invalid[] = {0xd800, 0};
  CHECK(kbl_efi_run(&launcher, pe, pe_bytes, invalid, sizeof(invalid), &r) ==
            -KBL_EINVAL &&
        !load_calls);
  CHECK(kbl_efi_run(&launcher, pe, pe_bytes, options, 3, &r) == -KBL_EINVAL);
  ended();
  unlimited_size_cases(&r);
  sections_and_full_output_cases();
  unsigned char *bad = malloc(pe_bytes);
  CHECK(bad);
  size_t ph = get32(pe + 60), op = ph + 24,
         table = op + (pe[ph + 20] | (unsigned)pe[ph + 21] << 8);
  unsigned relrva = get32(pe + op + 152), relfile = 0;
  for (unsigned i = 0; i < (pe[ph + 6] | (unsigned)pe[ph + 7] << 8); ++i) {
    unsigned char *s = pe + table + i * 40;
    if (relrva >= get32(s + 12) && relrva - get32(s + 12) < get32(s + 16))
      relfile = get32(s + 20) + relrva - get32(s + 12);
  }
  CHECK(relfile);
  for (unsigned n = 0; n < 19; ++n) {
    memcpy(bad, pe, pe_bytes);
    switch (n) {
    case 0:
      bad[0] = 0;
      break;
    case 1:
      put32(bad + 60, (unsigned)pe_bytes - 1);
      break;
    case 2:
      put16(bad + ph + 4, 0x8664);
      break;
    case 3:
      put16(bad + op + 68, 11);
      break;
    case 4:
      put32(bad + op + 16, get32(bad + op + 56));
      break;
    case 5:
      put32(bad + table + 20, (unsigned)pe_bytes);
      break;
    case 6:
      put32(bad + relfile + 4, 7);
      break;
    case 7:
      put32(bad + op + 108, 0xffffffff);
      break;
    case 8:
      put32(bad + table + 12, get32(bad + op + 56));
      break;
    case 9:
      put32(bad + 60, 0xfffffff0u);
      break;
    case 10:
      put32(bad + 60, UINT32_MAX);
      break;
    case 11:
      put32(bad + op + 24, 0xfffffff0u);
      put32(bad + op + 28, UINT32_MAX);
      break;
    case 12:
      put32(bad + table + 12, 0xfffff000u);
      put32(bad + table + 8, 0x2000u);
      break;
    case 13:
      put32(bad + table + 20, 0xfffffff0u);
      put32(bad + table + 16, 0x200u);
      break;
    case 14:
      put32(bad + op + 56, 0);
      break;
    case 15:
      put32(bad + op + 112 + 4 * 8, 0xfffffff0u);
      put32(bad + op + 116 + 4 * 8, 0x40u);
      break;
    case 16:
      put16(bad + ph + 20, 0xffffu);
      break;
    case 17:
      put16(bad + ph + 6, 0xffffu);
      break;
    case 18:
      put32(bad + op + 56, UINT32_MAX);
      break;
    }
    fresh();
    CHECK(kbl_efi_run(&launcher, bad, pe_bytes, options, sizeof(options), &r) <
              0 &&
          !alloc_calls && !load_calls);
    ended();
  }
  free(bad);
  free(pe);
  printf(
      "{\"passed\":true,\"cases\":%u,\"assertions\":%u,\"largeSourceBytes\":%zu,"
      "\"largeSizeOfImage\":%zu,\"largestPoolRequest\":%zu,\"allocations\":%u,"
      "\"frees\":%u,\"sectionCount\":%zu,\"optionsBytes\":%zu,\"fullExitUnits\":%zu,\"hardware\":false}\n",
      cases, checks, tested_source_bytes, tested_mapping_bytes, largest_pool_request, allocs, frees,
      tested_sections,tested_options_bytes,tested_full_exit_units);
  return 0;
}
