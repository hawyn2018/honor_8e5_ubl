/* SPDX-License-Identifier: MIT -- standard protocol-inventory model only. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define KBL_EFI_APPS_TEST 1
#define EfiMain protocols_main
#include "../../examples/efi_protocols/main.c"
#undef EfiMain
static unsigned checks, el = 1, allocated, released, exits, locates, lists,
                        probes;
static struct kefi_system_table test_st;
static struct kefi_boot_services test_bs;
static struct kefi_loaded_image test_li;
static struct kefi_guid ids[4] = {
    {0x12345678, 0xabcd, 0xef01, {2, 3, 4, 5, 6, 7, 8, 9}},
    {0x87654321, 0, 0, {0}},
    {0xabcdef01, 0, 0, {0}},
    {0x10203040, 0, 0, {0}}};
static uint16_t message[REPORT_UNITS], args[8193];
static kefi_status status_out;
static size_t exit_bytes;
static size_t model_handles=2,model_protocols=2;
#define CHECK(x)                                                               \
  do {                                                                         \
    ++checks;                                                                  \
    if (!(x)) {                                                                \
      fprintf(stderr, "FAIL %d: %s\n", __LINE__, #x);                          \
      exit(1);                                                                 \
    }                                                                          \
  } while (0)
unsigned kbl_efi_apps_test_current_el(void) { return el; }
static kefi_status handle(kefi_handle h, const struct kefi_guid *g,
                          void **out) {
  CHECK(h == (void *)0x9876 && g->a == loaded.a);
  *out = &test_li;
  return 0;
}
static kefi_status probe(const struct kefi_guid *g, void *unused, void **out) {
  CHECK(!unused && !memcmp(g, &qseecom, sizeof(*g)));
  ++probes;
  *out = (void *)0xdeadbeef;
  return 0;
}
static kefi_status handles(unsigned kind, const struct kefi_guid *g, void *key,
                           size_t *n, kefi_handle **out) {
  CHECK(kind == 0 && !g && !key);
  ++locates;
  *n = model_handles;
  *out = malloc(*n>SIZE_MAX/sizeof(**out)?1:*n * sizeof(**out));
  CHECK(*out);
  ++allocated;
  if(*n<=SIZE_MAX/sizeof(**out))for(size_t i=0;i<*n;++i)(*out)[i]=i&1?(void*)2:(void*)1;
  return 0;
}
static kefi_status protocols(kefi_handle h, struct kefi_guid ***out,
                             size_t *n) {
  CHECK(h == (void *)1 || h == (void *)2);
  ++lists;
  *n = model_protocols;
  *out = malloc(*n>SIZE_MAX/sizeof(**out)?1:*n * sizeof(**out));
  CHECK(*out);
  ++allocated;
  size_t index = h == (void *)1 ? 0 : 2;
  if(*n<=SIZE_MAX/sizeof(**out))for(size_t i=0;i<*n;++i)(*out)[i]=&ids[(index+i)%4];
  return 0;
}
static kefi_status freepool(void *p) {
  CHECK(p);
  ++released;
  free(p);
  return 0;
}
static kefi_status app_exit(kefi_handle h, kefi_status s, size_t n,
                            uint16_t *text) {
  CHECK(h == (void *)0x9876 && s == KEFI_ERROR(21) && n <= sizeof(message) &&
        !(n & 1));
  ++exits;
  status_out = s;
  exit_bytes = n;
  memcpy(message, text, n);
  return s;
}
static void options_text(const char *s) {
  size_t n = strlen(s);
  CHECK(n < sizeof(args)/sizeof(args[0]));
  for (size_t i = 0; i <= n; ++i)
    args[i] = (unsigned char)s[i];
  test_li.options = args;
  test_li.options_bytes = (uint32_t)((n + 1) * 2);
}
static int contains(const char *s) {
  char b[REPORT_UNITS];
  for (size_t i = 0; i < REPORT_UNITS; ++i) {
    b[i] = (char)message[i];
    if (!b[i])
      break;
  }
  return strstr(b, s) != 0;
}
int main(void) {
  test_bs.header =
      (struct kefi_header){KEFI_BOOT_SIGNATURE, 0, sizeof(test_bs), 0, 0};
  test_st.header =
      (struct kefi_header){KEFI_SYSTEM_SIGNATURE, 0, sizeof(test_st), 0, 0};
  test_st.boot_services = &test_bs;
  test_bs.handle_protocol = handle;
  test_bs.locate_protocol = probe;
  test_bs.locate_handle_buffer = (void *)handles;
  test_bs.protocols_per_handle = (void *)protocols;
  test_bs.free_pool = freepool;
  test_bs.exit_image = (void *)app_exit;
  options_text("/protocols.efi skip=0");
  CHECK(protocols_main((void *)0x9876, &test_st) == KEFI_ERROR(21));
  CHECK(contains("skip=0 next=2 total=4 EL=1 qseecom=yes") &&
        contains("12345678-abcd-ef01-0203-040506070809") &&
        contains("87654321") && !contains("abcdef01"));
  CHECK(allocated == released && exits == 1 && probes == 1 && locates == 1 &&
        lists == 2 && status_out == KEFI_ERROR(21) && exit_bytes > 2);
  options_text("/protocols.efi skip=2");
  CHECK(protocols_main((void *)0x9876, &test_st) == KEFI_ERROR(21));
  CHECK(contains("skip=2 next=4 total=4") && contains("abcdef01") &&
        contains("10203040") && !contains("12345678"));
  CHECK(allocated == released);
  options_text("/protocols.efi skip=4");
  CHECK(protocols_main((void *)0x9876, &test_st) == KEFI_ERROR(21));
  CHECK(contains("skip=4 next=4 total=4") && !contains("h="));
  CHECK(allocated == released);
  options_text("/protocols.efi skip=0");model_handles=65537;model_protocols=1;
  CHECK(protocols_main((void *)0x9876,&test_st)==KEFI_ERROR(21)&&contains("total=65537")&&contains("errors=0")&&allocated==released);
  model_handles=1;model_protocols=65537;
  CHECK(protocols_main((void *)0x9876,&test_st)==KEFI_ERROR(21)&&contains("total=65537")&&contains("errors=0")&&allocated==released);
  model_handles=SIZE_MAX;model_protocols=1;
  CHECK(protocols_main((void *)0x9876,&test_st)==KEFI_UNSUPPORTED&&allocated==released);
  model_handles=1;model_protocols=SIZE_MAX;
  CHECK(protocols_main((void *)0x9876,&test_st)==KEFI_ERROR(21)&&contains("errors=1")&&allocated==released);
  model_handles=model_protocols=2;
  options_text("/protocols.efi skip=42949672960");
  CHECK(protocols_main((void *)0x9876,&test_st)==KEFI_ERROR(21)&&contains("skip=42949672960 next=4 total=4")&&allocated==released);
  for(size_t i=0;i<4096;++i)args[i]=' ';args[4096]=0;test_li.options=args;test_li.options_bytes=8194;
  CHECK(protocols_main((void *)0x9876,&test_st)==KEFI_ERROR(21)&&contains("total=4")&&allocated==released);
  options_text("/protocols.efi skip=184467440737095516160");
  unsigned a = allocated, e = exits;
  CHECK(protocols_main((void *)0x9876,&test_st)==KEFI_INVALID&&allocated==a&&exits==e);
  options_text("/protocols.efi skip=0");
  test_bs.protocols_per_handle = 0;
  CHECK(protocols_main((void *)0x9876, &test_st) == KEFI_UNSUPPORTED &&
        allocated == a);
  printf("{\"passed\":true,\"assertions\":%u,\"physicalDeviceIO\":false}\n",
         checks);
  return 0;
}
