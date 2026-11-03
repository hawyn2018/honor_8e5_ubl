/* Synthetic sources and wire callbacks, linked against the real fetch module.
 * No UEFI, USB, microkernel, real partition or device access is implemented here.
 */
#include "kbl_fetch.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>

static unsigned checks, callback_errors;
#define OLD_FETCH_BYTES (8u*1024u*1024u)
#define WORK_BYTES (OLD_FETCH_BYTES+1u)
#define CHECK(x) do { ++checks; if (!(x)) { \
    fprintf(stderr, "%s:%d: %s\n", __func__, __LINE__, #x); return 0; \
} } while (0)

struct fixture {
    struct kbl_fetch_source source;
    struct kbl_fetch_wire wire;
    unsigned opens, reads, closes, data_calls, writes, finals, aborts;
    unsigned fail_read_at, zero_read_at, over_read_at, fail_write_at, zero_write_at, over_write_at;
    int open_rc, data_rc, no_read, no_close, final_okay;
    uint64_t size, start_offset;
    size_t read_chunk, write_chunk, read_bytes, sent;
    uint32_t announced;
    char name[64], final_text[96], events[128], last_event; size_t event_count;
};
static unsigned char *allocation, *work;
static void event(struct fixture *f, char c) {
    f->last_event = c;
    if (f->event_count < sizeof(f->events) - 1) f->events[f->event_count++] = c;
    f->events[f->event_count] = 0;
}
static unsigned char pattern(uint64_t offset) {
    if (offset >= 0x120 && offset < 0x12c) return (unsigned char)"FAILOKAYDATA"[offset - 0x120];
    return (unsigned char)(offset ^ (offset >> 8));
}
static int read_source(void *p, uint64_t offset, void *buffer, size_t bytes, size_t *read) {
    struct fixture *f = p; ++f->reads; event(f, 'R');
    if (f->closes) ++callback_errors;
    if (f->reads == 1) f->start_offset = offset;
    if (offset != f->start_offset + f->read_bytes) ++callback_errors;
    if (f->fail_read_at == f->reads) { *read = 0; return KBL_ERROR; }
    if (f->zero_read_at == f->reads) { *read = 0; return KBL_OK; }
    if (f->over_read_at == f->reads) { *read = bytes + 1; return KBL_OK; }
    size_t count = bytes < f->read_chunk ? bytes : f->read_chunk;
    for (size_t i = 0; i < count; ++i) ((unsigned char *)buffer)[i] = pattern(offset + i);
    f->read_bytes += count; *read = count; return KBL_OK;
}
static void close_source(void *p) { struct fixture *f = p; ++f->closes; event(f, 'C'); }
static int open_source(void *p, const char *name, struct kbl_reader *reader) {
    struct fixture *f = p; ++f->opens; event(f, 'O');
    if (strlen(name) >= sizeof(f->name)) { ++callback_errors; return KBL_ERROR; }
    strcpy(f->name, name);
    if (f->open_rc != KBL_OK) return f->open_rc;
    *reader = (struct kbl_reader){f, f->size, f->no_read ? 0 : read_source, f->no_close ? 0 : close_source};
    return KBL_OK;
}
static int data_header(void *p, uint32_t bytes) {
    struct fixture *f = p; ++f->data_calls; event(f, 'D'); f->announced = bytes;
    /* Every successful header must follow the complete read and close. */
    if (f->closes != 1 || f->read_bytes != bytes) ++callback_errors;
    return f->data_rc;
}
static int write_data(void *p, const void *data, size_t bytes, size_t *written) {
    struct fixture *f = p; ++f->writes; event(f, 'W');
    if (f->closes != 1 || f->data_calls != 1 || bytes != f->announced - f->sent) ++callback_errors;
    if (f->fail_write_at == f->writes) { *written = 0; return KBL_ERROR; }
    if (f->zero_write_at == f->writes) { *written = 0; return KBL_OK; }
    if (f->over_write_at == f->writes) { *written = bytes + 1; return KBL_OK; }
    size_t count = bytes < f->write_chunk ? bytes : f->write_chunk;
    for (size_t i = 0; i < count; ++i)
        if (((const unsigned char *)data)[i] != pattern(f->start_offset + f->sent + i)) ++callback_errors;
    f->sent += count; *written = count; return KBL_OK;
}
static void final_status(void *p, int okay, const char *text) {
    struct fixture *f = p; ++f->finals; event(f, 'F'); f->final_okay = okay;
    if (strlen(text) >= sizeof(f->final_text)) { ++callback_errors; return; }
    strcpy(f->final_text, text);
    if (f->data_calls && !okay) ++callback_errors; /* FAIL must never enter a DATA body. */
    if (okay && f->sent != f->announced) ++callback_errors;
}
static void abort_transfer(void *p) { struct fixture *f = p; ++f->aborts; event(f, 'A'); }
static void init(struct fixture *f) {
    memset(f, 0, sizeof(*f)); f->size = 4096; f->read_chunk = f->write_chunk = 1024;
    f->source = (struct kbl_fetch_source){f, open_source};
    f->wire = (struct kbl_fetch_wire){f, data_header, write_data, final_status, abort_transfer};
    memset(allocation, 0x9b, 32); memset(work + WORK_BYTES, 0x9b, 32);
}
static int guards(void) {
    for (size_t i = 0; i < 32; ++i) { CHECK(allocation[i] == 0x9b); CHECK(work[WORK_BYTES + i] == 0x9b); }
    CHECK(!callback_errors); return 1;
}
static int request(struct fixture *f, const char *text, size_t cap) {
    int result = kbl_fetch((const unsigned char *)text, strlen(text), &f->source, &f->wire, work, cap);
    CHECK(result == 1); CHECK(guards()); return 1;
}
static int failed_before_data(const struct fixture *f, unsigned opened) {
    CHECK(f->opens == opened); CHECK(f->closes == opened);
    CHECK(f->finals == 1 && !f->final_okay && !f->data_calls && !f->writes && !f->aborts); return 1;
}
static int successful(const struct fixture *f, size_t bytes) {
    CHECK(f->opens == 1 && f->closes == 1 && f->data_calls == 1);
    CHECK(f->finals == 1 && f->final_okay && !f->aborts);
    CHECK(f->read_bytes == bytes && f->announced == bytes && f->sent == bytes);
    CHECK(f->last_event == 'F'); return 1;
}
static int aborted_after_data(const struct fixture *f) {
    CHECK(f->opens == 1 && f->closes == 1 && f->data_calls == 1);
    CHECK(f->aborts == 1 && !f->finals && f->last_event == 'A'); return 1;
}

static int native_requests_are_unhandled_and_lazy(void) {
    const char *requests[] = {"", "fetch", "fetchx:abl_b", "Fetch:abl_b", "getvar:all", "download:00000010",
        "flash:abl_b", "erase:userdata", "oem kblstart", "oem kblin", "oem superunlock", "reboot"};
    for (size_t i = 0; i < sizeof(requests)/sizeof(requests[0]); ++i) {
        struct fixture f; init(&f);
        CHECK(kbl_fetch((const unsigned char *)requests[i], strlen(requests[i]), &f.source, &f.wire, work, 4096) == 0);
        CHECK(!f.opens && !f.reads && !f.closes && !f.data_calls && !f.writes && !f.finals && !f.aborts);
    }
    CHECK(guards()); return 1;
}
static int whole_source_is_binary_exact(void) {
    struct fixture f; init(&f); CHECK(request(&f, "fetch:abl_b", 4096));
    CHECK(successful(&f, 4096)); CHECK(!strcmp(f.name, "abl_b")); return 1;
}
static int partial_reader_and_writer_preserve_bytes(void) {
    struct fixture f; init(&f); f.read_chunk = 3; f.write_chunk = 2;
    CHECK(request(&f, "fetch:kbl.0001:0x5:0x11", 4096)); CHECK(successful(&f, 17));
    CHECK(f.start_offset == 5 && f.reads == 6 && f.writes == 9);
    CHECK(!strcmp(f.name, "kbl.0001")); CHECK(!strcmp(f.events, "ORRRRRRCDWWWWWWWWWF")); return 1;
}
static int omitted_length_reads_remaining_bytes(void) {
    struct fixture f; init(&f); f.size = 32;
    CHECK(request(&f, "fetch:init_boot_b:0x10", 4096)); CHECK(successful(&f, 16)); CHECK(f.start_offset == 16); return 1;
}
static int hexadecimal_formats_are_supported(void) {
    const char *requests[] = {"fetch:abl_b:a:10", "fetch:abl_b:0XA:0X10", "fetch:abl_b:000a:0010"};
    for (size_t i = 0; i < sizeof(requests)/sizeof(requests[0]); ++i) {
        struct fixture f; init(&f); CHECK(request(&f, requests[i], 4096));
        CHECK(successful(&f, 16)); CHECK(f.start_offset == 10);
    }
    return 1;
}
static int invalid_names_fail_without_open(void) {
    const char *requests[] = {"fetch:", "fetch:/dev/abl_b", "fetch:../abl_b", "fetch:abl b", "fetch:abl_b\n", "fetch:abl\\b"};
    for (size_t i = 0; i < sizeof(requests)/sizeof(requests[0]); ++i) {
        struct fixture f; init(&f); CHECK(request(&f, requests[i], 4096)); CHECK(failed_before_data(&f, 0));
    }
    return 1;
}
static int invalid_numbers_fail_without_open(void) {
    const char *requests[] = {"fetch:abl_b:", "fetch:abl_b:0x", "fetch:abl_b:-1", "fetch:abl_b:gg",
        "fetch:abl_b:10000000000000000", "fetch:abl_b:0:", "fetch:abl_b:0:0x", "fetch:abl_b:0:0:1"};
    for (size_t i = 0; i < sizeof(requests)/sizeof(requests[0]); ++i) {
        struct fixture f; init(&f); CHECK(request(&f, requests[i], 4096)); CHECK(failed_before_data(&f, 0));
    }
    return 1;
}
static int oversized_frames_and_missing_services_fail_early(void) {
    struct fixture f; init(&f); char frame[80]; memset(frame, 'a', sizeof(frame)); memcpy(frame, "fetch:", 6);
    CHECK(kbl_fetch((unsigned char *)frame, sizeof(frame), &f.source, &f.wire, work, 4096) == 1);
    CHECK(failed_before_data(&f, 0)); init(&f);
    CHECK(kbl_fetch((unsigned char *)"fetch:abl_b", 11, 0, &f.wire, work, 4096) == 1);
    CHECK(failed_before_data(&f, 0)); init(&f); f.source.open = 0;
    CHECK(request(&f, "fetch:abl_b", 4096)); CHECK(failed_before_data(&f, 0)); init(&f);
    CHECK(kbl_fetch((unsigned char *)"fetch:abl_b", 11, &f.source, &f.wire, 0, 4096) == 1);
    CHECK(failed_before_data(&f, 0)); CHECK(guards()); return 1;
}
static int incomplete_wire_is_not_called(void) {
    struct fixture f; init(&f); f.wire.abort = 0;
    CHECK(kbl_fetch((unsigned char *)"fetch:abl_b", 11, &f.source, &f.wire, work, 4096) == 0);
    CHECK(!f.opens && !f.finals && !f.data_calls && !f.aborts); return 1;
}
static int unavailable_source_is_not_read_or_closed(void) {
    struct fixture f; init(&f); f.open_rc = KBL_ERROR;
    CHECK(request(&f, "fetch:kbl.expired", 4096));
    CHECK(f.opens == 1 && !f.closes && !f.reads && f.finals == 1 && !f.final_okay && !f.data_calls);
    CHECK(!strcmp(f.name, "kbl.expired")); return 1;
}
static int invalid_reader_closes_existing_close_once(void) {
    struct fixture f; init(&f); f.no_read = 1;
    CHECK(request(&f, "fetch:abl_b", 4096)); CHECK(failed_before_data(&f, 1)); CHECK(!f.reads);
    /* A broken backend that omits close cannot be cleaned by a nonexistent callback. */
    init(&f); f.no_close = 1; CHECK(request(&f, "fetch:abl_b", 4096));
    CHECK(f.opens == 1 && !f.closes && !f.reads && f.finals == 1 && !f.final_okay && !f.data_calls); return 1;
}
static int out_of_bounds_and_overflow_are_rejected(void) {
    const char *requests[] = {"fetch:abl_b:1001", "fetch:abl_b:fff:2", "fetch:abl_b:ffffffffffffffff:1",
        "fetch:abl_b:1:ffffffffffffffff"};
    for (size_t i = 0; i < sizeof(requests)/sizeof(requests[0]); ++i) {
        struct fixture f; init(&f); CHECK(request(&f, requests[i], 4096)); CHECK(failed_before_data(&f, 1)); CHECK(!f.reads);
    }
    struct fixture f; init(&f); f.size = UINT64_MAX;
    CHECK(request(&f, "fetch:abl_b:fffffffffffffffd:3", 4096)); CHECK(failed_before_data(&f, 1)); CHECK(!f.reads); return 1;
}
static int empty_source_and_zero_ranges_are_explicit_failures(void) {
    struct fixture f; init(&f); f.size = 0;
    CHECK(request(&f, "fetch:empty", 4096)); CHECK(failed_before_data(&f, 1)); CHECK(!f.reads);
    init(&f); CHECK(request(&f, "fetch:abl_b:1000", 4096)); CHECK(failed_before_data(&f, 1));
    init(&f); CHECK(request(&f, "fetch:abl_b:0:0", 4096)); CHECK(failed_before_data(&f, 1)); return 1;
}
static int workspace_capacity_is_respected(void) {
    struct fixture f; init(&f); CHECK(request(&f, "fetch:abl_b:0:1000", 4095));
    CHECK(failed_before_data(&f, 1)); CHECK(!f.reads);
    init(&f); CHECK(request(&f, "fetch:abl_b", 0)); CHECK(failed_before_data(&f, 0)); return 1;
}
static int exact_eight_mib_chunk_succeeds(void) {
    struct fixture f; init(&f); f.size = OLD_FETCH_BYTES; f.read_chunk = f.write_chunk = 1024 * 1024;
    CHECK(request(&f, "fetch:large:0:800000", WORK_BYTES));
    CHECK(successful(&f, OLD_FETCH_BYTES)); CHECK(f.reads == 8 && f.writes == 8); return 1;
}
static int larger_than_eight_mib_is_rejected_without_read(void) {
    struct fixture f; init(&f); f.size = WORK_BYTES; f.read_chunk=f.write_chunk=1024*1024;
    CHECK(request(&f, "fetch:large:0:800001", WORK_BYTES)); CHECK(successful(&f,WORK_BYTES));
    CHECK(f.reads==9&&f.writes==9); return 1;
}
static int actual_data_field_bounds_and_capacity_query(void){
    struct fixture f;init(&f);size_t bytes=0;f.size=UINT32_MAX;
    CHECK(kbl_fetch_capacity((const unsigned char*)"fetch:large",11,&f.source,&bytes)==KBL_OK);
    CHECK(bytes==UINT32_MAX&&f.opens==1&&f.closes==1&&!f.reads&&!f.data_calls);
    init(&f);f.size=(uint64_t)UINT32_MAX+1;
    CHECK(request(&f,"fetch:large",WORK_BYTES));CHECK(failed_before_data(&f,1)&&!f.reads);
    init(&f);f.size=WORK_BYTES;
    CHECK(kbl_fetch_capacity((const unsigned char*)"fetch:large:0:800001",20,&f.source,&bytes)==KBL_OK);
    CHECK(bytes==WORK_BYTES&&!f.reads&&f.opens==f.closes);return 1;
}
static int read_failure_never_announces_data(void) {
    for (unsigned mode = 0; mode < 3; ++mode) {
        struct fixture f; init(&f); f.read_chunk = 3;
        if (mode == 0) f.fail_read_at = 2;
        if (mode == 1) f.zero_read_at = 2;
        if (mode == 2) f.over_read_at = 2;
        CHECK(request(&f, "fetch:abl_b:0:10", 4096)); CHECK(failed_before_data(&f, 1));
        CHECK(f.reads == 2 && f.read_bytes == 3);
    }
    return 1;
}
static int header_failure_aborts_without_final(void) {
    struct fixture f; init(&f); f.data_rc = KBL_ERROR;
    CHECK(request(&f, "fetch:abl_b:0:10", 4096)); CHECK(aborted_after_data(&f)); CHECK(!f.writes && !f.sent); return 1;
}
static int write_error_zero_and_oversize_abort_without_fail_packet(void) {
    for (unsigned mode = 0; mode < 3; ++mode) {
        struct fixture f; init(&f); f.write_chunk = 3;
        if (mode == 0) f.fail_write_at = 2;
        if (mode == 1) f.zero_write_at = 2;
        if (mode == 2) f.over_write_at = 2;
        CHECK(request(&f, "fetch:abl_b:0:10", 4096)); CHECK(aborted_after_data(&f)); CHECK(f.writes == 2 && f.sent == 3);
    }
    return 1;
}
static int binary_body_includes_zero_ff_and_status_looking_bytes(void) {
    struct fixture f; init(&f); f.size = 512; f.read_chunk = f.write_chunk = 1;
    CHECK(request(&f, "fetch:binary:0:200", 512)); CHECK(successful(&f, 512));
    CHECK(f.reads == 512 && f.writes == 512); return 1;
}
struct test { const char *name; int (*run)(void); };
#define TEST(n) {#n, n}
int main(void) {
    const struct test tests[] = {
        TEST(native_requests_are_unhandled_and_lazy), TEST(whole_source_is_binary_exact),
        TEST(partial_reader_and_writer_preserve_bytes), TEST(omitted_length_reads_remaining_bytes),
        TEST(hexadecimal_formats_are_supported), TEST(invalid_names_fail_without_open), TEST(invalid_numbers_fail_without_open),
        TEST(oversized_frames_and_missing_services_fail_early), TEST(incomplete_wire_is_not_called),
        TEST(unavailable_source_is_not_read_or_closed), TEST(invalid_reader_closes_existing_close_once),
        TEST(out_of_bounds_and_overflow_are_rejected), TEST(empty_source_and_zero_ranges_are_explicit_failures),
        TEST(workspace_capacity_is_respected), TEST(exact_eight_mib_chunk_succeeds),
        TEST(larger_than_eight_mib_is_rejected_without_read), TEST(read_failure_never_announces_data),
        {"actual_data_field_bounds_and_capacity_query", actual_data_field_bounds_and_capacity_query},
        TEST(header_failure_aborts_without_final), TEST(write_error_zero_and_oversize_abort_without_fail_packet),
        TEST(binary_body_includes_zero_ff_and_status_looking_bytes)
    };
    allocation = malloc((size_t)WORK_BYTES + 64);
    if (!allocation) return 2;
    work = allocation + 32;
    unsigned failed = 0;
    puts("FIXTURE: synthetic reader/wire; no UEFI, USB, real partition or kernel claim");
    for (size_t i = 0; i < sizeof(tests)/sizeof(tests[0]); ++i) {
        int okay = tests[i].run(); if (!okay) ++failed;
        printf("%s %s\n", okay ? "PASS" : "FAIL", tests[i].name);
    }
    printf("RESULT tests=%u failed=%u assertions=%u callback_errors=%u\n",
        (unsigned)(sizeof(tests)/sizeof(tests[0])), failed, checks, callback_errors);
    free(allocation); return failed || callback_errors ? 1 : 0;
}
