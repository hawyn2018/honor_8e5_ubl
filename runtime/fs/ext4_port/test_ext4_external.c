/* SPDX-License-Identifier: MIT -- independent mke2fs-created image test. */
#define main lwext4_fixture_unused_main
#include "test_ext4_backend.c"
#undef main
int main(int argc, char **argv) {
  struct disk d = {0};
  struct kbl_fs_device dev;
  struct kbl_fs m = {0};
  void *f;
  FILE *in;
  char *data, *copy;
  size_t n;
  struct kbl_file_stat s;
  CHECK(argc == 2);
  in = fopen(argv[1], "rb");
  CHECK(in);
  fseek(in, 0, SEEK_END);
  d.n = (size_t)ftell(in);
  rewind(in);
  d.p = malloc(d.n);
  CHECK(d.p);
  CHECK(fread(d.p, 1, d.n, in) == d.n);
  fclose(in);
  data = malloc(131073);
  copy = malloc(131073);
  CHECK(data && copy);
  for (size_t i = 0; i < 131073; ++i)
    data[i] = (char)(i * 17u + 9u);
  dev = device(&d);
  CHECK(kbl_ext4_mount(&host, &dev, "ext4", 0, &m) == 0);
  CHECK(m.ops->mkdir(m.opaque, "/kbl-test") == 0);
  CHECK(m.ops->open(m.opaque, "/kbl-test/data",
                    KBL_FILE_READ | KBL_FILE_WRITE | KBL_FILE_CREATE, &f) == 0);
  CHECK(m.ops->write_at(m.opaque, f, 0, data, 131072, &n) == 0 && n == 131072);
  CHECK(m.ops->write_at(m.opaque, f, 131072, data + 131072, 1, &n) == 0 &&
        n == 1);
  CHECK(m.ops->close(m.opaque, f) == 0);
  CHECK(m.ops->rename(m.opaque, "/kbl-test/data", "/kbl-test/verified.bin") ==
        0);
  CHECK(m.ops->sync(m.opaque, 0) == 0);
  CHECK(m.ops->unmount(m.opaque) == 0 && live == 0);
  size_t writes = d.writes;
  CHECK(kbl_ext4_mount(&host, &dev, "ext4", KBL_FS_READ_ONLY, &m) == 0);
  CHECK(m.ops->stat(m.opaque, "/kbl-test/verified.bin", &s) == 0 &&
        s.bytes == 131073);
  CHECK(m.ops->open(m.opaque, "/kbl-test/verified.bin", KBL_FILE_READ, &f) ==
        0);
  CHECK(m.ops->read_at(m.opaque, f, 0, copy, 131073, &n) == 0 && n == 131073 &&
        !memcmp(copy, data, n));
  CHECK(m.ops->close(m.opaque, f) == 0);
  CHECK(m.ops->unmount(m.opaque) == 0 && live == 0 && d.writes == writes);
  in = fopen(argv[1], "wb");
  CHECK(in);
  CHECK(fwrite(d.p, 1, d.n, in) == d.n);
  fclose(in);
  printf("independent mke2fs image RW/RO-remount 131073-byte patterned content "
         "PASS writes=%zu\n",
         writes);
  free(d.p);
  free(data);
  free(copy);
  return 0;
}
