/* Host-only contract tests. The backend below is an explicit deterministic fake.
 * Passing these tests does not constitute a kernel, UEFI integration, real EL
 * measurement, USB test or a phone boot test. No devices are accessed.
 */
#include "kbl_api.h"
#include <stdio.h>
#include <string.h>
#include <inttypes.h>
#include <limits.h>
#include <stdlib.h>

static unsigned checks, callback_errors;
#define CHECK(x) do { ++checks; if (!(x)) { \
    fprintf(stderr, "%s:%d: %s\n", __func__, __LINE__, #x); return 0; \
} } while (0)

struct fake {
    unsigned el, el_calls, starts, submits, polls, stops;
    int start_rc, submit_rc, poll_rc, stop_rc, null_context, failed_start_context;
    unsigned el_after_start, el_after_submit, el_after_poll;
    uint64_t next_job, last_job;
    unsigned budget;
    size_t capacity, command_bytes, output_bytes;
    unsigned char command[KBL_LINE_BYTES], output[KBL_OUTPUT_BYTES];
    int done, result_rc;
    unsigned dynamic_commands, fail_allocation, live_allocations;
    unsigned char *captured;
};
struct output {
    unsigned infos, finals;
    int okay;
    char lines[16][57], final_text[96];
};
struct fixture {
    uint64_t before;
    struct kbl_session s;
    uint64_t after;
    struct fake f;
    struct output o;
    struct kbl_backend b;
    struct kbl_output out;
};
static unsigned current_el(void *p) { struct fake *f = p; ++f->el_calls; return f->el; }
static int start(void *p, void **context) {
    struct fake *f = p; ++f->starts;
    *context = (!f->null_context && (f->start_rc == KBL_OK || f->failed_start_context)) ? f : 0;
    if (f->el_after_start) f->el = f->el_after_start;
    return f->start_rc;
}
static int submit(void *p, const unsigned char *line, size_t bytes, uint64_t *job) {
    struct fake *f = p; ++f->submits;
    if (bytes > sizeof(f->command)) {
        if(!f->dynamic_commands) { ++callback_errors; return KBL_ERROR; }
        free(f->captured);f->captured=malloc(bytes);if(!f->captured)return KBL_ERROR;
        memcpy(f->captured,line,bytes);
    } else memcpy(f->command, line, bytes);
    f->command_bytes = bytes; *job = f->next_job;
    if (f->el_after_submit) f->el = f->el_after_submit;
    return f->submit_rc;
}
static int poll(void *p, uint64_t job, unsigned budget, unsigned char *out,
                size_t cap, size_t *written, int *done, int *rc) {
    struct fake *f = p; ++f->polls;
    f->last_job = job; f->budget = budget; f->capacity = cap;
    size_t n = f->output_bytes < cap ? f->output_bytes : cap;
    memcpy(out, f->output, n); *written = f->output_bytes; *done = f->done; *rc = f->result_rc;
    if (f->el_after_poll) f->el = f->el_after_poll;
    return f->poll_rc;
}
static int stop(void *p) { struct fake *f = p; ++f->stops;if(f->live_allocations)++callback_errors; return f->stop_rc; }
static void *allocate_command(void*p,size_t bytes){struct fake*f=p;if(f->fail_allocation)return 0;void*q=malloc(bytes);if(q)++f->live_allocations;return q;}
static void release_command(void*p,void*memory,size_t bytes){struct fake*f=p;(void)bytes;if(!f->live_allocations)++callback_errors;else --f->live_allocations;free(memory);}
static void info(void *p, const char *text) {
    struct output *o = p; size_t n = strlen(text);
    if (n > 56 || o->infos >= 16) { ++callback_errors; return; }
    for (size_t i = 0; i < n; ++i) if ((unsigned char)text[i] < 32 || (unsigned char)text[i] > 126) ++callback_errors;
    memcpy(o->lines[o->infos++], text, n + 1);
}
static void final(void *p, int okay, const char *text) {
    struct output *o = p; ++o->finals; o->okay = okay;
    size_t n = strlen(text);
    if (n >= sizeof(o->final_text)) { ++callback_errors; return; }
    memcpy(o->final_text, text, n + 1);
}
static void init(struct fixture *x) {
    memset(x, 0, sizeof(*x)); x->before = x->after = UINT64_C(0xa17d23f7c9e105b6);
    x->f.el = 1; x->f.next_job = UINT64_C(0x0102030405060708);
    x->b = (struct kbl_backend){&x->f, current_el, start, submit, poll, stop, 0, 0};
    x->out = (struct kbl_output){&x->o, info, final};
}
static int raw(struct fixture *x, const unsigned char *request, size_t n, int handled, int okay) {
    memset(&x->o, 0, sizeof(x->o));
    int result = kbl_handle(&x->s, &x->b, request, n, &x->out);
    CHECK(result == handled); CHECK(x->o.finals == (unsigned)handled);
    CHECK(!handled || x->o.okay == okay);
    CHECK(handled || !x->o.infos);
    CHECK(x->before == UINT64_C(0xa17d23f7c9e105b6)); CHECK(x->after == x->before);
    CHECK(!callback_errors); return 1;
}
static int request(struct fixture *x, const char *text, int handled, int okay) {
    return raw(x, (const unsigned char *)text, strlen(text), handled, okay);
}
static int begin(struct fixture *x) { return request(x, "oem kblstart", 1, 1); }
static int command(struct fixture *x, const unsigned char *line, size_t n, int okay) {
    char wire[128]; const char *h = "0123456789abcdef";
    if (n > 50) return 0;
    memcpy(wire, "oem kblcmd ", 11);
    for (size_t i = 0; i < n; ++i) { wire[11 + i * 2] = h[line[i] >> 4]; wire[12 + i * 2] = h[line[i] & 15]; }
    wire[11 + n * 2] = 0; return request(x, wire, 1, okay);
}
static int cmd(struct fixture *x, const char *text, int okay) {
    return command(x, (const unsigned char *)text, strlen(text), okay);
}
static int upload_data(struct fixture *x, uint64_t id, unsigned offset,
                       const unsigned char *data, size_t n, int okay) {
    char wire[128]; const char *h = "0123456789abcdef";
    int at = snprintf(wire, sizeof(wire), "oem kblput D%016" PRIx64 ":%04x:", id, offset);
    if (at < 0 || n > 30) return 0;
    for (size_t i = 0; i < n; ++i) { wire[at + i * 2] = h[data[i] >> 4]; wire[at + i * 2 + 1] = h[data[i] & 15]; }
    wire[at + n * 2] = 0; return request(x, wire, 1, okay);
}
static int upload_end(struct fixture *x, uint64_t id, unsigned bytes, int okay) {
    char wire[80]; snprintf(wire, sizeof(wire), "oem kblput E%016" PRIx64 ":%04x", id, bytes);
    return request(x, wire, 1, okay);
}
static int poll_job(struct fixture *x, uint64_t job, int okay) {
    char wire[80]; snprintf(wire, sizeof(wire), "oem kblpoll %016" PRIx64, job);
    return request(x, wire, 1, okay);
}
static int all_zero(const void *p, size_t bytes) {
    const unsigned char *d = p; for (size_t i = 0; i < bytes; ++i) if (d[i]) return 0; return 1;
}

static int native_requests_do_not_execute_backend(void) {
    static const char *native[] = {"", "getvar:all", "getvar:current-slot", "flash:abl_b", "erase:userdata",
        "reboot", "reboot-bootloader", "continue", "download:00000010", "oem superunlock", "oem blsh",
        "flashing unlock", "flashing lock", "set_active:a", "oem hwdog certify close"};
    struct fixture x; init(&x); struct kbl_session before = x.s;
    for (size_t i = 0; i < sizeof(native) / sizeof(native[0]); ++i) CHECK(request(&x, native[i], 0, 0));
    CHECK(!memcmp(&before, &x.s, sizeof(before)));
    CHECK(x.f.el_calls + x.f.starts + x.f.submits + x.f.polls + x.f.stops == 0); return 1;
}
static int namespace_requires_exact_boundary(void) {
    const char *bad[] = {"oem kblstartx", "oem kblin2", "oem kbloffline", "oem kblcmdx 65", "oem kblpolling",
        "oem kblputty", "oem kblstatusx", "oem kbl", "OEM kblstart", "oem\tkblstart", " oem kblstart"};
    struct fixture x; init(&x);
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); ++i) CHECK(request(&x, bad[i], 0, 0));
    CHECK(x.f.el_calls + x.f.starts + x.f.submits + x.f.polls + x.f.stops == 0); return 1;
}
static int invalid_recognized_frames_never_start(void) {
    struct fixture x; init(&x);
    CHECK(request(&x, "oem kblstart unexpected", 1, 0));
    CHECK(request(&x, "oem kblin bad", 1, 0));
    unsigned char embedded[] = "oem kblstart \0";
    CHECK(raw(&x, embedded, sizeof(embedded) - 1, 1, 0));
    CHECK(request(&x, "oem kblstart \n", 1, 0));
    char longframe[80]; memset(longframe, ' ', sizeof(longframe)); memcpy(longframe, "oem kblstart", 12);
    CHECK(raw(&x, (unsigned char *)longframe, sizeof(longframe), 1, 0));
    CHECK(!x.f.starts && !x.f.el_calls); return 1;
}
static int snapshots_are_lazy(void) {
    struct fixture x; init(&x); CHECK(request(&x, "oem kblin", 1, 0));
    CHECK(request(&x, "oem kblstatus", 1, 1)); CHECK(strstr(x.o.lines[0], "status=stopped") != 0);
    CHECK(x.f.el_calls + x.f.starts + x.f.submits + x.f.polls + x.f.stops == 0); return 1;
}
static int missing_backend_is_explicit_failure(void) {
    struct fixture x; init(&x); x.b.start = 0;
    CHECK(request(&x, "oem kblstart", 1, 0)); CHECK(strstr(x.o.final_text, "unavailable") != 0);
    CHECK(!x.f.starts && x.s.phase == KBL_STOPPED); return 1;
}
static int start_is_idempotent_and_attach_has_no_runtime_calls(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); CHECK(x.s.phase == KBL_RUNNING);
    CHECK(x.s.context == &x.f && x.s.generation == 1 && x.s.el == 1); CHECK(x.f.starts == 1 && x.f.el_calls == 2);
    struct fake before = x.f; CHECK(begin(&x)); CHECK(request(&x, "oem kblin", 1, 1));
    CHECK(request(&x, "oem kblstatus", 1, 1)); CHECK(!memcmp(&before, &x.f, sizeof(before)));
    CHECK(!x.f.submits && !x.f.polls && !x.f.stops); return 1;
}
static int only_supported_el_can_start(void) {
    const unsigned levels[] = {0, 3, 4};
    for (size_t i = 0; i < sizeof(levels) / sizeof(levels[0]); ++i) {
        struct fixture x; init(&x); x.f.el = levels[i]; CHECK(request(&x, "oem kblstart", 1, 0));
        CHECK(!x.f.starts && x.s.phase == KBL_STOPPED && !x.s.context);
    }
    struct fixture y; init(&y); y.f.el = 2; CHECK(begin(&y)); CHECK(y.s.el == 2); return 1;
}
static int failed_start_is_not_fake_success(void) {
    struct fixture x; init(&x); x.f.start_rc = KBL_ERROR;
    CHECK(request(&x, "oem kblstart", 1, 0)); CHECK(x.s.phase == KBL_STOPPED && !x.s.context && !x.s.generation);
    CHECK(!x.f.stops); x.f.start_rc = KBL_OK; CHECK(begin(&x)); CHECK(x.f.starts == 2); return 1;
}
static int null_start_context_is_rejected(void) {
    struct fixture x; init(&x); x.f.null_context = 1;
    CHECK(request(&x, "oem kblstart", 1, 0)); CHECK(x.s.phase == KBL_STOPPED && !x.s.context); return 1;
}
static int failed_start_retains_partial_context_for_explicit_cleanup(void) {
    struct fixture x; init(&x); x.f.start_rc = KBL_ERROR; x.f.failed_start_context = 1;
    CHECK(request(&x, "oem kblstart", 1, 0)); CHECK(x.s.phase == KBL_FAULTED && x.s.context == &x.f);
    CHECK(!x.s.generation && !x.f.stops);
    CHECK(request(&x, "oem kblstart", 1, 0)); CHECK(x.f.starts == 1 && x.s.context == &x.f);
    CHECK(request(&x, "oem kbloff", 1, 1)); CHECK(x.f.stops == 1 && !x.s.context && x.s.phase == KBL_STOPPED);
    x.f.start_rc = KBL_OK; x.f.failed_start_context = 0; CHECK(begin(&x)); CHECK(x.s.generation == 1); return 1;
}
static int el_change_during_start_keeps_cleanup_context(void) {
    struct fixture x; init(&x); x.f.el_after_start = 2;
    CHECK(request(&x, "oem kblstart", 1, 0)); CHECK(x.s.phase == KBL_FAULTED && x.s.context == &x.f);
    CHECK(!x.f.stops); CHECK(request(&x, "oem kbloff", 1, 0)); CHECK(!x.f.stops && x.s.context == &x.f);
    x.f.el = 1; CHECK(request(&x, "oem kbloff", 1, 1)); CHECK(x.f.stops == 1); return 1;
}
static int off_is_explicit_and_idempotent(void) {
    struct fixture x; init(&x); CHECK(request(&x, "oem kbloff", 1, 1)); CHECK(!x.f.stops);
    CHECK(begin(&x)); CHECK(request(&x, "oem kblput B", 1, 1));
    CHECK(upload_data(&x, x.s.upload, 0, (const unsigned char *)"sensitive", 9, 1));
    CHECK(request(&x, "oem kbloff", 1, 1)); CHECK(x.f.stops == 1 && x.s.phase == KBL_STOPPED);
    CHECK(!x.s.context && !x.s.has_upload && !x.s.has_job && !x.s.line_bytes && !x.s.job && !x.s.upload);
    CHECK(all_zero(x.s.line, sizeof(x.s.line))); CHECK(request(&x, "oem kbloff", 1, 1)); CHECK(x.f.stops == 1); return 1;
}
static int cleanup_can_yield_then_resume(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); x.f.stop_rc = KBL_AGAIN;
    CHECK(request(&x, "oem kbloff", 1, 0)); CHECK(x.s.phase == KBL_STOPPING && x.s.context == &x.f);
    unsigned calls = x.f.stops; CHECK(request(&x, "oem kblin", 1, 0)); CHECK(x.f.stops == calls);
    x.f.stop_rc = KBL_OK; CHECK(request(&x, "oem kbloff", 1, 1)); CHECK(x.s.phase == KBL_STOPPED && x.f.stops == 2); return 1;
}
static int failed_cleanup_preserves_context_and_blocks_restart(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); x.f.stop_rc = KBL_ERROR;
    CHECK(request(&x, "oem kbloff", 1, 0)); CHECK(x.s.phase == KBL_FAULTED && x.s.context == &x.f);
    CHECK(request(&x, "oem kblstart", 1, 0)); CHECK(x.f.starts == 1);
    x.f.stop_rc = KBL_OK; CHECK(request(&x, "oem kbloff", 1, 1)); return 1;
}
static int exit_and_quit_retain_context(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); void *context = x.s.context; uint64_t generation = x.s.generation;
    CHECK(cmd(&x, "exit", 1)); CHECK(strstr(x.o.final_text, "retained") != 0);
    CHECK(cmd(&x, "quit", 1)); CHECK(x.s.context == context && x.s.generation == generation && x.s.phase == KBL_RUNNING);
    CHECK(!x.f.submits && !x.f.polls && !x.f.stops); CHECK(request(&x, "oem kblin", 1, 1)); return 1;
}
static int disconnect_then_attach_does_not_stop_or_resubmit(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); CHECK(cmd(&x, "echo hello", 1));
    struct kbl_session before = x.s; struct fake old = x.f;
    CHECK(request(&x, "getvar:version", 0, 0)); /* Native fastboot traffic between host connections. */
    CHECK(request(&x, "oem kblin", 1, 1));
    CHECK(!memcmp(&before, &x.s, sizeof(before)) && !memcmp(&old, &x.f, sizeof(old))); return 1;
}
static int job_poll_is_bounded_and_resumable(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); CHECK(cmd(&x, "echo hello", 1));
    CHECK(x.s.phase == KBL_BUSY && x.s.has_job && x.f.submits == 1);
    CHECK(x.f.command_bytes == 10 && !memcmp(x.f.command, "echo hello", 10));
    x.f.output_bytes = KBL_OUTPUT_BYTES;
    for (size_t i = 0; i < KBL_OUTPUT_BYTES; ++i) x.f.output[i] = (unsigned char)(i * 11);
    CHECK(poll_job(&x, x.s.job, 1));
    CHECK(x.f.polls == 1 && x.f.budget == KBL_QUANTUM_US && x.f.capacity == KBL_OUTPUT_BYTES);
    CHECK(x.o.infos == 3 && strlen(x.o.lines[0]) == 55 && strlen(x.o.lines[1]) == 11);
    CHECK(strstr(x.o.lines[2], "done=0 rc=0") != 0 && x.s.phase == KBL_BUSY);
    uint64_t job = x.s.job; CHECK(request(&x, "oem kblin", 1, 1)); CHECK(x.s.job == job && x.f.submits == 1);
    x.f.output_bytes = 0; x.f.done = 1; x.f.result_rc = INT_MIN;
    CHECK(poll_job(&x, job, 1)); CHECK(strstr(x.o.lines[0], "done=1 rc=-2147483648") != 0);
    CHECK(x.s.phase == KBL_RUNNING && !x.s.job && !x.s.has_job && x.f.polls == 2); return 1;
}
static int stale_or_malformed_job_never_polls(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); CHECK(cmd(&x, "pwd", 1));
    CHECK(poll_job(&x, x.s.job + 1, 0)); CHECK(request(&x, "oem kblpoll g", 1, 0));
    CHECK(request(&x, "oem kblpoll 12345678901234567", 1, 0)); CHECK(!x.f.polls); return 1;
}
static int failed_submit_does_not_claim_job(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); x.f.submit_rc = KBL_ERROR;
    CHECK(cmd(&x, "pwd", 0)); CHECK(x.s.phase == KBL_RUNNING && !x.s.has_job && !x.s.job);
    x.f.submit_rc = KBL_OK; x.f.next_job = 0; CHECK(cmd(&x, "pwd", 0)); CHECK(!x.s.has_job); return 1;
}
static int command_validation_is_atomic(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); memset(x.s.line, 0xa5, sizeof(x.s.line)); x.s.line_bytes = 7;
    const char *bad[] = {"oem kblcmd", "oem kblcmd 1", "oem kblcmd 65gg", "oem kblcmd 6500", "oem kblcmd xz"};
    for (size_t i = 0; i < sizeof(bad) / sizeof(bad[0]); ++i) {
        struct kbl_session before = x.s; CHECK(request(&x, bad[i], 1, 0));
        CHECK(!memcmp(&before, &x.s, sizeof(before)));
    }
    CHECK(!x.f.submits); return 1;
}
static int busy_session_rejects_new_commands_and_uploads(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); CHECK(cmd(&x, "sleep 1", 1));
    struct kbl_session before = x.s; CHECK(cmd(&x, "pwd", 0)); CHECK(request(&x, "oem kblput B", 1, 0));
    CHECK(!memcmp(&before, &x.s, sizeof(before)) && x.f.submits == 1); return 1;
}
static int multipart_command_is_assembled_exactly(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); CHECK(request(&x, "oem kblput B", 1, 1));
    uint64_t id = x.s.upload; CHECK(id && x.s.has_upload);
    CHECK(upload_data(&x, id, 0, (const unsigned char *)"printf ", 7, 1));
    CHECK(upload_data(&x, id, 7, (const unsigned char *)"'%s\\n' hello", 12, 1));
    CHECK(upload_end(&x, id, 19, 1));
    CHECK(x.f.submits == 1 && x.f.command_bytes == 19 && !memcmp(x.f.command, "printf '%s\\n' hello", 19));
    CHECK(!x.s.has_upload && x.s.has_job); return 1;
}
static int duplicate_and_out_of_order_chunks_are_rejected(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); CHECK(request(&x, "oem kblput B", 1, 1)); uint64_t id = x.s.upload;
    CHECK(upload_data(&x, id, 0, (const unsigned char *)"abc", 3, 1));
    CHECK(upload_data(&x, id, 0, (const unsigned char *)"abc", 3, 0));
    CHECK(upload_data(&x, id, 5, (const unsigned char *)"xyz", 3, 0));
    CHECK(x.s.line_bytes == 3 && !memcmp(x.s.line, "abc", 3)); CHECK(!x.f.submits); return 1;
}
static int incomplete_length_cannot_commit(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); CHECK(request(&x, "oem kblput B", 1, 1)); uint64_t id = x.s.upload;
    CHECK(upload_data(&x, id, 0, (const unsigned char *)"abc", 3, 1));
    CHECK(upload_end(&x, id, 4, 0)); CHECK(upload_end(&x, id, 2, 0)); CHECK(!x.f.submits);
    CHECK(x.s.has_upload && x.s.line_bytes == 3); CHECK(upload_end(&x, id, 3, 1)); return 1;
}
static int invalid_chunk_is_atomic_and_can_be_retried(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); CHECK(request(&x, "oem kblput B", 1, 1)); uint64_t id = x.s.upload;
    CHECK(upload_data(&x, id, 0, (const unsigned char *)"abc", 3, 1));
    char bad[90]; snprintf(bad, sizeof(bad), "oem kblput D%016" PRIx64 ":0003:6465zz", id);
    struct kbl_session before = x.s; CHECK(request(&x, bad, 1, 0)); CHECK(!memcmp(&before, &x.s, sizeof(before)));
    CHECK(upload_data(&x, id, 3, (const unsigned char *)"def", 3, 1)); CHECK(upload_end(&x, id, 6, 1));
    CHECK(x.f.command_bytes == 6 && !memcmp(x.f.command, "abcdef", 6)); return 1;
}
static int stale_upload_and_old_session_id_are_rejected(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); CHECK(request(&x, "oem kblput B", 1, 1)); uint64_t old = x.s.upload;
    CHECK(request(&x, "oem kblput B", 1, 1)); CHECK(x.s.upload != old);
    CHECK(upload_data(&x, old, 0, (const unsigned char *)"bad", 3, 0)); CHECK(upload_end(&x, old, 0, 0));
    CHECK(request(&x, "oem kbloff", 1, 1)); CHECK(begin(&x)); CHECK(request(&x, "oem kblput B", 1, 1));
    CHECK(x.s.upload != old); CHECK(upload_data(&x, old, 0, (const unsigned char *)"bad", 3, 0));
    CHECK(!x.s.line_bytes && !x.f.submits); return 1;
}
static int full_upload_capacity_and_overflow(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); CHECK(request(&x, "oem kblput B", 1, 1)); uint64_t id = x.s.upload;
    unsigned char chunk[14]; memset(chunk, 'x', sizeof(chunk));
    size_t offset = 0;
    while (offset < KBL_LINE_BYTES) {
        size_t n = KBL_LINE_BYTES - offset; if (n > sizeof(chunk)) n = sizeof(chunk);
        CHECK(upload_data(&x, id, (unsigned)offset, chunk, n, 1)); offset += n;
    }
    CHECK(x.s.line_bytes == KBL_LINE_BYTES); CHECK(upload_data(&x, id, KBL_LINE_BYTES, chunk, 1, 0));
    CHECK(x.s.line_bytes == KBL_LINE_BYTES); CHECK(upload_end(&x, id, KBL_LINE_BYTES, 1));
    CHECK(x.f.command_bytes == KBL_LINE_BYTES);
    for (size_t i = 0; i < KBL_LINE_BYTES; ++i) CHECK(x.f.command[i] == 'x'); return 1;
}
static int multipart_exit_retains_without_backend(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); CHECK(request(&x, "oem kblput B", 1, 1)); uint64_t id = x.s.upload;
    CHECK(upload_data(&x, id, 0, (const unsigned char *)"exit", 4, 1)); CHECK(upload_end(&x, id, 4, 1));
    CHECK(!x.f.submits && !x.f.stops && x.s.context == &x.f && !x.s.has_upload); return 1;
}
static int el_change_before_submit_defers_execution(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); x.f.el = 2;
    CHECK(cmd(&x, "pwd", 0)); CHECK(!x.f.submits && x.s.phase == KBL_FAULTED && x.s.context == &x.f);
    CHECK(request(&x, "oem kbloff", 1, 0)); CHECK(!x.f.stops); x.f.el = 1;
    CHECK(request(&x, "oem kbloff", 1, 1)); CHECK(x.f.stops == 1); return 1;
}
static int el_change_during_submit_preserves_job_cleanup(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); x.f.el_after_submit = 2;
    CHECK(cmd(&x, "pwd", 0)); CHECK(x.s.phase == KBL_FAULTED && x.s.context == &x.f && x.s.has_job);
    CHECK(request(&x, "oem kblin", 1, 0)); CHECK(!x.f.polls && !x.f.stops); x.f.el = 1;
    CHECK(request(&x, "oem kbloff", 1, 1)); return 1;
}
static int el_change_before_poll_defers_execution(void) {
    struct fixture x; init(&x); CHECK(begin(&x)); CHECK(cmd(&x, "pwd", 1)); x.f.el = 2;
    CHECK(poll_job(&x, x.s.job, 0)); CHECK(!x.f.polls && x.s.phase == KBL_FAULTED && x.s.has_job); return 1;
}
static int poll_failures_preserve_context(void) {
    for (unsigned mode = 0; mode < 4; ++mode) {
        struct fixture x; init(&x); CHECK(begin(&x)); CHECK(cmd(&x, "pwd", 1));
        if (mode == 0) x.f.poll_rc = KBL_ERROR;
        if (mode == 1) x.f.output_bytes = KBL_OUTPUT_BYTES + 1;
        if (mode == 2) x.f.done = 2;
        if (mode == 3) x.f.el_after_poll = 2;
        CHECK(poll_job(&x, x.s.job, 0)); CHECK(x.s.phase == KBL_FAULTED && x.s.context == &x.f && x.s.has_job);
        CHECK(!x.f.stops); x.f.el = 1; CHECK(request(&x, "oem kbloff", 1, 1)); CHECK(x.f.stops == 1);
    }
    return 1;
}

struct reader_fake { unsigned calls, closes; size_t chunk; int mode; uint64_t offsets[40]; size_t requests[40]; };
static int read_at(void *p, uint64_t offset, void *buffer, size_t bytes, size_t *read) {
    struct reader_fake *f = p; unsigned call = f->calls++;
    if (call >= 40) { ++callback_errors; return KBL_ERROR; }
    f->offsets[call] = offset; f->requests[call] = bytes;
    if (f->mode == 1) { *read = 0; return KBL_OK; }
    if (f->mode == 2) { *read = 0; return KBL_ERROR; }
    if (f->mode == 3) { *read = bytes + 1; return KBL_OK; }
    size_t n = bytes < f->chunk ? bytes : f->chunk;
    for (size_t i = 0; i < n; ++i) ((unsigned char *)buffer)[i] = (unsigned char)(offset + i);
    *read = n; return KBL_OK;
}
static void close_reader(void *p) { ++((struct reader_fake *)p)->closes; }
static int read_exact_handles_partial_reads(void) {
    struct reader_fake f = {0}; f.chunk = 3;
    struct kbl_reader r = {&f, 100, read_at, close_reader}; unsigned char out[10];
    CHECK(kbl_read_exact(&r, 7, out, sizeof(out)) == KBL_OK); CHECK(f.calls == 4 && !f.closes);
    CHECK(f.offsets[0] == 7 && f.offsets[1] == 10 && f.offsets[2] == 13 && f.offsets[3] == 16);
    CHECK(f.requests[0] == 10 && f.requests[1] == 7 && f.requests[2] == 4 && f.requests[3] == 1);
    for (size_t i = 0; i < sizeof(out); ++i) CHECK(out[i] == i + 7); return 1;
}
static int read_exact_rejects_zero_error_and_oversize(void) {
    for (int mode = 1; mode <= 3; ++mode) {
        struct reader_fake f = {0}; f.chunk = 3; f.mode = mode;
        struct kbl_reader r = {&f, 100, read_at, close_reader}; unsigned char out[10];
        CHECK(kbl_read_exact(&r, 0, out, sizeof(out)) == KBL_ERROR); CHECK(f.calls == 1 && !f.closes);
    }
    return 1;
}
static int read_exact_validates_bounds_before_callback(void) {
    struct reader_fake f = {0}; f.chunk = 3;
    struct kbl_reader r = {&f, 10, read_at, close_reader}; unsigned char out[10];
    CHECK(kbl_read_exact(&r, 11, out, 0) == KBL_ERROR);
    CHECK(kbl_read_exact(&r, 9, out, 2) == KBL_ERROR);
    CHECK(kbl_read_exact(&r, UINT64_MAX, out, 1) == KBL_ERROR);
    CHECK(kbl_read_exact(&r, 0, 0, 1) == KBL_ERROR); CHECK(!f.calls);
    CHECK(kbl_read_exact(&r, 10, 0, 0) == KBL_OK); CHECK(!f.calls);
    CHECK(kbl_read_exact(0, 0, out, 0) == KBL_ERROR);
    r.read_at = 0; CHECK(kbl_read_exact(&r, 0, out, 1) == KBL_ERROR); return 1;
}
static int read_exact_near_uint64_boundary(void) {
    struct reader_fake f = {0}; f.chunk = 3;
    struct kbl_reader r = {&f, UINT64_MAX, read_at, close_reader}; unsigned char out[4];
    CHECK(kbl_read_exact(&r, UINT64_MAX - 1, out, 1) == KBL_OK); CHECK(out[0] == 254);
    CHECK(kbl_read_exact(&r, UINT64_MAX, 0, 0) == KBL_OK); unsigned calls = f.calls;
    CHECK(kbl_read_exact(&r, UINT64_MAX, out, 1) == KBL_ERROR); CHECK(f.calls == calls);
    CHECK(kbl_read_exact(&r, UINT64_MAX - 2, out, 3) == KBL_ERROR); CHECK(f.calls == calls); return 1;
}

struct test { const char *name; int (*run)(void); };
static int dynamic_upload_crosses_old_buffer_and_position_limits(void) {
    struct fixture x;init(&x);x.b.allocate=allocate_command;x.b.release=release_command;x.f.dynamic_commands=1;
    CHECK(begin(&x));CHECK(request(&x,"oem kblput B",1,1));uint64_t id=x.s.upload;
    const unsigned char chunk[8]={'x','x','x','x','x','x','x','x'};
    unsigned offset=0;
    while(offset<65537u){size_t n=65537u-offset;if(n>sizeof(chunk))n=sizeof(chunk);
        CHECK(upload_data(&x,id,offset,chunk,n,1));offset+=(unsigned)n;}
    CHECK(x.s.dynamic_line&&x.s.line_bytes==65537u);
    CHECK(upload_end(&x,id,65537u,1));CHECK(x.f.command_bytes==65537u&&x.f.captured);
    for(size_t i=0;i<x.f.command_bytes;++i)CHECK(x.f.captured[i]=='x');
    CHECK(!x.s.dynamic_line&&!x.s.line_capacity&&!x.f.live_allocations);
    free(x.f.captured);x.f.captured=0;CHECK(request(&x,"oem kbloff",1,1));return 1;
}
static int dynamic_upload_failure_reset_and_off_release_staging(void) {
    struct fixture x;init(&x);x.b.allocate=allocate_command;x.b.release=release_command;x.f.dynamic_commands=1;
    CHECK(begin(&x));
    for(unsigned mode=0;mode<4;++mode){
        CHECK(request(&x,"oem kblput B",1,1));uint64_t id=x.s.upload;unsigned offset=0;const unsigned char a[8]={'x','x','x','x','x','x','x','x'};
        while(offset<4096){CHECK(upload_data(&x,id,offset,a,sizeof(a),1));offset+=sizeof(a);}
        x.f.fail_allocation=mode==0;CHECK(upload_data(&x,id,offset,a,1,mode?1:0));x.f.fail_allocation=0;
        if(!mode){CHECK(!x.s.has_upload&&!x.s.dynamic_line&&!x.f.live_allocations);continue;}
        if(mode==1)CHECK(request(&x,"oem kblput B",1,1));
        if(mode==2){x.f.submit_rc=KBL_ERROR;CHECK(upload_end(&x,id,4097,0));x.f.submit_rc=KBL_OK;free(x.f.captured);x.f.captured=0;}
        if(mode==3)CHECK(request(&x,"oem kbloff",1,1));
        CHECK(!x.s.dynamic_line&&!x.f.live_allocations);
    }
    return 1;
}
static int adb_optin_keeps_existing_core_and_job(void) {
    struct fixture x;init(&x);
    CHECK(request(&x,"oem kbladb",1,1));CHECK(x.f.starts==1&&x.s.phase==KBL_RUNNING);
    uint64_t generation=x.s.generation;void *context=x.s.context;
    CHECK(request(&x,"oem kblcmd 656c",1,1));CHECK(x.s.has_job&&x.s.phase==KBL_BUSY);
    uint64_t job=x.s.job;
    CHECK(request(&x,"oem kbladb",1,1));CHECK(x.f.starts==1&&x.s.context==context);
    CHECK(x.s.generation==generation&&x.s.job==job&&x.s.has_job&&x.s.phase==KBL_BUSY);
    CHECK(request(&x,"oem kbladb extra",1,0));CHECK(request(&x,"oem kbladbx",0,0));
    CHECK(x.f.starts==1&&x.s.job==job);return 1;
}
#define TEST(n) {#n, n}
int main(void) {
    const struct test tests[] = {
        TEST(dynamic_upload_crosses_old_buffer_and_position_limits), TEST(dynamic_upload_failure_reset_and_off_release_staging),
        TEST(adb_optin_keeps_existing_core_and_job),
        TEST(native_requests_do_not_execute_backend), TEST(namespace_requires_exact_boundary),
        TEST(invalid_recognized_frames_never_start), TEST(snapshots_are_lazy), TEST(missing_backend_is_explicit_failure),
        TEST(start_is_idempotent_and_attach_has_no_runtime_calls), TEST(only_supported_el_can_start),
        TEST(failed_start_is_not_fake_success), TEST(null_start_context_is_rejected),
        TEST(failed_start_retains_partial_context_for_explicit_cleanup), TEST(el_change_during_start_keeps_cleanup_context),
        TEST(off_is_explicit_and_idempotent), TEST(cleanup_can_yield_then_resume), TEST(failed_cleanup_preserves_context_and_blocks_restart),
        TEST(exit_and_quit_retain_context), TEST(disconnect_then_attach_does_not_stop_or_resubmit),
        TEST(job_poll_is_bounded_and_resumable), TEST(stale_or_malformed_job_never_polls), TEST(failed_submit_does_not_claim_job),
        TEST(command_validation_is_atomic), TEST(busy_session_rejects_new_commands_and_uploads),
        TEST(multipart_command_is_assembled_exactly), TEST(duplicate_and_out_of_order_chunks_are_rejected),
        TEST(incomplete_length_cannot_commit), TEST(invalid_chunk_is_atomic_and_can_be_retried),
        TEST(stale_upload_and_old_session_id_are_rejected), TEST(full_upload_capacity_and_overflow), TEST(multipart_exit_retains_without_backend),
        TEST(el_change_before_submit_defers_execution), TEST(el_change_during_submit_preserves_job_cleanup),
        TEST(el_change_before_poll_defers_execution), TEST(poll_failures_preserve_context),
        TEST(read_exact_handles_partial_reads), TEST(read_exact_rejects_zero_error_and_oversize),
        TEST(read_exact_validates_bounds_before_callback), TEST(read_exact_near_uint64_boundary)
    };
    unsigned failed = 0;
    puts("FIXTURE: deterministic fake backend; no microkernel/UEFI/phone boot claim");
    for (size_t i = 0; i < sizeof(tests) / sizeof(tests[0]); ++i) {
        int ok = tests[i].run(); if (!ok) ++failed;
        printf("%s %s\n", ok ? "PASS" : "FAIL", tests[i].name);
    }
    printf("RESULT tests=%u failed=%u assertions=%u callback_errors=%u\n",
           (unsigned)(sizeof(tests) / sizeof(tests[0])), failed, checks, callback_errors);
    return failed || callback_errors ? 1 : 0;
}
