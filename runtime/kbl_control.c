/* SPDX-License-Identifier: MIT */
#include "kbl_api.h"

static size_t textlen(const char *s) { size_t n = 0; while (s[n]) ++n; return n; }
static int equal(const unsigned char *s, size_t n, const char *t) {
    size_t z = textlen(t), i; if (n != z) return 0;
    for (i = 0; i < n; ++i) if (s[i] != (unsigned char)t[i]) return 0;
    return 1;
}
static int prefix(const unsigned char *s, size_t n, const char *t) {
    size_t z = textlen(t), i; if (n < z) return 0;
    for (i = 0; i < z; ++i) if (s[i] != (unsigned char)t[i]) return 0;
    return 1;
}
static void emit(const struct kbl_output *o, const char *s) { o->info(o->opaque, s); }
static void final(const struct kbl_output *o, int ok, const char *s) { o->final(o->opaque, ok, s); }
static unsigned hex(unsigned c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return 16;
}
static char *put(char *d, const char *s) { while (*s) *d++ = *s++; return d; }
static char *uhex(char *d, uint64_t v) {
    const char *digits = "0123456789abcdef"; int i;
    for (i = 15; i >= 0; --i) *d++ = digits[(v >> (i * 4)) & 15];
    return d;
}
static char *decimal(char *d, int v) {
    char digits[12]; size_t n = 0; uint64_t x;
    if (v < 0) { *d++ = '-'; x = (uint64_t)(-(int64_t)v); } else x = (unsigned)v;
    do { digits[n++] = (char)('0' + x % 10); x /= 10; } while (x);
    while (n) *d++ = digits[--n];
    return d;
}
static int parse_hex(const unsigned char *p, size_t n, uint64_t *v) {
    size_t i; uint64_t x = 0; if (!n || n > 16) return 0;
    for (i = 0; i < n; ++i) { unsigned h = hex(p[i]); if (h > 15) return 0; x = (x << 4) | h; }
    *v = x; return 1;
}
static int decode(const unsigned char *p, size_t n, unsigned char *d, size_t cap, size_t *used) {
    size_t i; if (!n || (n & 1) || n / 2 > cap) return 0;
    /* Validate the whole frame before changing its staging buffer. */
    for (i = 0; i < n; i += 2)
        if (hex(p[i]) > 15 || hex(p[i + 1]) > 15 || !(hex(p[i]) * 16 + hex(p[i + 1]))) return 0;
    for (i = 0; i < n; i += 2) d[i / 2] = (unsigned char)(hex(p[i]) * 16 + hex(p[i + 1]));
    *used = n / 2; return 1;
}
static unsigned char *line_data(struct kbl_session *s) { return s->dynamic_line ? s->dynamic_line : s->line; }
static size_t line_capacity(const struct kbl_session *s) { return s->dynamic_line ? s->line_capacity : sizeof(s->line); }
static void release_line(struct kbl_session *s, const struct kbl_backend *b) {
    if (s->dynamic_line) {
        b->release(b->opaque, s->dynamic_line, s->line_capacity);
        s->dynamic_line = 0; s->line_capacity = 0; s->line_bytes = 0;
    }
}
static int reserve_line(struct kbl_session *s, const struct kbl_backend *b, size_t bytes) {
    size_t capacity = line_capacity(s);
    if (bytes <= capacity) return 1;
    if (!b->allocate || !b->release) return 0;
    while (capacity < bytes) {
        if (capacity > SIZE_MAX / 2) { capacity = bytes; break; }
        capacity *= 2;
    }
    unsigned char *fresh = b->allocate(b->opaque, capacity);
    if (!fresh) {
        release_line(s, b); s->line_bytes = 0; s->has_upload = 0;
        return 0;
    }
    size_t used = s->line_bytes;
    for (size_t i = 0; i < used; ++i) fresh[i] = line_data(s)[i];
    release_line(s, b);
    s->dynamic_line = fresh; s->line_capacity = capacity; s->line_bytes = used;
    return 1;
}
static int valid_encoding(const unsigned char *p, size_t n) {
    if (!n || (n & 1)) return 0;
    for (size_t i = 0; i < n; i += 2)
        if (hex(p[i]) > 15 || hex(p[i+1]) > 15 || !(hex(p[i])*16+hex(p[i+1]))) return 0;
    return 1;
}
static int alive(const struct kbl_session *s) { return s->phase == KBL_RUNNING || s->phase == KBL_BUSY; }
static void status(const struct kbl_session *s, const struct kbl_backend *backend, const struct kbl_output *o) {
    char b[57], *p; const char *name = "error";
    if (s->phase == KBL_STOPPED) name = "stopped";
    else if (s->phase == KBL_RUNNING) name = "running";
    else if (s->phase == KBL_BUSY) name = "busy";
    else if (s->phase == KBL_STARTING) name = "starting";
    else if (s->phase == KBL_STOPPING) name = "stopping";
    p = put(b, "KBL1 status="); p = put(p, name); p = put(p, " id="); p = uhex(p, s->generation);
    p = put(p, " el="); *p++ = (char)('0' + s->el); *p = 0; emit(o, b);
    if (s->has_job) { p = put(b, "KBL1 job="); p = uhex(p, s->job); *p = 0; emit(o, b); }
    if (backend->allocate && backend->release) emit(o, "KBL1 command64=1");
}
static int same_el(struct kbl_session *s, const struct kbl_backend *b) {
    if (b->current_el(b->opaque) == s->el) return 1;
    s->phase = KBL_FAULTED; return 0;
}
static void submit(struct kbl_session *s, const struct kbl_backend *b, const struct kbl_output *o) {
    uint64_t job = 0;
    if (!alive(s) || s->has_job) { final(o, 0, "KBL is stopped or busy"); return; }
    if (!s->line_bytes) { final(o, 0, "Empty command"); return; }
    if (!same_el(s, b)) { final(o, 0, "KBL EL changed"); return; }
    if (b->submit(s->context, line_data(s), s->line_bytes, &job) != KBL_OK || !job) {
        if (s->dynamic_line) { release_line(s, b); s->has_upload = 0; }
        final(o, 0, "KBL submit failed"); return;
    }
    release_line(s, b);
    s->has_upload = 0; s->has_job = 1; s->job = job; s->phase = KBL_BUSY;
    if (!same_el(s, b)) { final(o, 0, "KBL EL changed"); return; }
    status(s, b, o); final(o, 1, "");
}

int kbl_handle(struct kbl_session *s, const struct kbl_backend *b,
               const unsigned char *r, size_t n, const struct kbl_output *o) {
    enum { NONE, START, IN, OFF, STATUS, CMD, PUT, POLL } op = NONE;
    const unsigned char *arg = 0; size_t an = 0, i;
    struct { const char *name; int op; } names[] = {
        {"oem kblstart", START}, {"oem kbladb", START}, {"oem kblin", IN}, {"oem kbloff", OFF},
        {"oem kblstatus", STATUS}, {"oem kblcmd", CMD},
        {"oem kblput", PUT}, {"oem kblpoll", POLL}
    };
    if (!s || !b || !r || !o || !o->info || !o->final) return 0;
    for (i = 0; i < sizeof(names) / sizeof(names[0]); ++i) {
        size_t z = textlen(names[i].name);
        if (prefix(r, n, names[i].name) && (n == z || r[z] == ' ')) {
            op = names[i].op; arg = r + z; an = n - z; break;
        }
    }
    if (op == NONE) return 0;
    if (n > KBL_WIRE_BYTES) { final(o, 0, "KBL frame too long"); return 1; }
    for (i = 0; i < n; ++i) if (r[i] < 32 || r[i] > 126) { final(o, 0, "Invalid KBL frame"); return 1; }
    while (an && *arg == ' ') { ++arg; --an; }
    if (op <= STATUS && an) { final(o, 0, "Unexpected argument"); return 1; }
    if (op == STATUS || op == IN) {
        status(s, b, o); final(o, op == STATUS || alive(s), alive(s) ? "" : "KBL not running"); return 1;
    }
    if (!b->start || !b->stop || !b->submit || !b->poll || !b->current_el) {
        final(o, 0, "KBL backend unavailable"); return 1;
    }
    if (op == START) {
        if (alive(s)) { status(s, b, o); final(o, 1, ""); return 1; }
        if (s->phase != KBL_STOPPED) { final(o, 0, "KBL cleanup required"); return 1; }
        s->el = b->current_el(b->opaque);
        if (s->el < 1 || s->el > 2) { final(o, 0, "Unsupported KBL EL"); return 1; }
        s->phase = KBL_STARTING; s->context = 0;
        if (b->start(b->opaque, &s->context) != KBL_OK || !s->context) {
            s->phase = s->context ? KBL_FAULTED : KBL_STOPPED;
            final(o, 0, s->context ? "KBL initialization failed; cleanup required" : "KBL initialization failed"); return 1;
        }
        if (!same_el(s, b)) { final(o, 0, "KBL EL changed"); return 1; }
        ++s->generation; if (!s->generation) ++s->generation;
        s->job = 0; s->upload = s->generation << 32;
        s->has_job = s->has_upload = 0; s->line_bytes = 0;
        s->phase = KBL_RUNNING; status(s, b, o); final(o, 1, ""); return 1;
    }
    if (op == OFF) {
        if (s->phase == KBL_STOPPED) { status(s, b, o); final(o, 1, ""); return 1; }
        if (!s->context) { final(o, 0, "Missing KBL cleanup context"); return 1; }
        if (!same_el(s, b)) { final(o, 0, "KBL EL changed; cleanup deferred"); return 1; }
        s->phase = KBL_STOPPING;
        release_line(s, b); s->has_upload = 0; s->line_bytes = 0;
        int rc = b->stop(s->context);
        if (rc != KBL_OK) {
            s->phase = rc == KBL_AGAIN ? KBL_STOPPING : KBL_FAULTED;
            final(o, 0, rc == KBL_AGAIN ? "KBL cleanup pending" : "KBL cleanup failed"); return 1;
        }
        s->context = 0; s->has_job = s->has_upload = 0; s->line_bytes = 0; s->job = s->upload = 0;
        for (i = 0; i < KBL_LINE_BYTES; ++i) s->line[i] = 0;
        s->phase = KBL_STOPPED; status(s, b, o); final(o, 1, ""); return 1;
    }
    if (!alive(s)) { final(o, 0, "KBL not running"); return 1; }
    if (op == CMD) {
        if (s->has_job) { final(o, 0, "KBL busy"); return 1; }
        size_t used = 0;
        if (!decode(arg, an, line_data(s), line_capacity(s), &used)) { final(o, 0, "Invalid command encoding"); return 1; }
        s->line_bytes = used;
        if (equal(line_data(s), used, "exit") || equal(line_data(s), used, "quit")) {
            release_line(s, b); s->has_upload = 0;
            status(s, b, o); final(o, 1, "KBL retained"); return 1;
        }
        submit(s, b, o); return 1;
    }
    if (op == PUT) {
        uint64_t id; size_t used;
        if (s->has_job) { final(o, 0, "KBL busy"); return 1; }
        if (equal(arg, an, "B")) {
            release_line(s, b);
            s->has_upload = 1; s->line_bytes = 0; ++s->upload; if (!s->upload) ++s->upload;
            char out[40], *p = put(out, "KBL1 put="); p = uhex(p, s->upload); *p = 0; emit(o, out);
            if (b->allocate && b->release) emit(o, "KBL1 command64=1");
            final(o, 1, ""); return 1;
        }
        if (an < 17 || !s->has_upload || !parse_hex(arg + 1, 16, &id) || id != s->upload) {
            final(o, 0, "Invalid command upload"); return 1;
        }
        uint64_t position;
        size_t separator = 18;
        while (separator < an && arg[separator] != ':') ++separator;
        if (arg[0] == 'D' && an > 19 && arg[17] == ':' && separator < an &&
            parse_hex(arg + 18, separator - 18, &position) && position == s->line_bytes &&
            valid_encoding(arg + separator + 1, an - separator - 1)) {
            size_t added = (an - separator - 1) / 2;
            if (added > SIZE_MAX - s->line_bytes || !reserve_line(s, b, s->line_bytes + added)) {
                final(o, 0, "Command staging allocation failed"); return 1;
            }
            if (!decode(arg + separator + 1, an - separator - 1, line_data(s) + s->line_bytes,
                        line_capacity(s) - s->line_bytes, &used)) {
                final(o, 0, "Invalid command upload"); return 1;
            }
            s->line_bytes += used; final(o, 1, ""); return 1;
        }
        if (arg[0] == 'E' && an > 18 && arg[17] == ':' &&
            parse_hex(arg + 18, an - 18, &position) && position == s->line_bytes) {
            if (equal(line_data(s), s->line_bytes, "exit") || equal(line_data(s), s->line_bytes, "quit")) {
                release_line(s, b); s->has_upload = 0; status(s, b, o); final(o, 1, "KBL retained"); return 1;
            }
            submit(s, b, o); return 1;
        }
        final(o, 0, "Invalid command upload"); return 1;
    }
    if (op == POLL) {
        uint64_t job; unsigned char out[KBL_OUTPUT_BYTES]; size_t written = 0; int done = 0, rc = 0;
        if (!s->has_job || !parse_hex(arg, an, &job) || job != s->job) { final(o, 0, "Unknown KBL job"); return 1; }
        if (!same_el(s, b)) { final(o, 0, "KBL EL changed"); return 1; }
        int result = b->poll(s->context, job, KBL_QUANTUM_US, out, sizeof(out), &written, &done, &rc);
        if (!same_el(s, b) || result != KBL_OK || written > sizeof(out) || (done != 0 && done != 1)) {
            s->phase = KBL_FAULTED; final(o, 0, "KBL job failed"); return 1;
        }
        if (written) {
            char line[57], *p = put(line, "KBL1 out="); const char *h = "0123456789abcdef";
            /* 23 bytes maximum gives 9+46=55 ASCII bytes. */
            size_t at = 0;
            while (at < written) {
                p = put(line, "KBL1 out="); size_t end = written - at > 23 ? at + 23 : written;
                while (at < end) { *p++ = h[out[at] >> 4]; *p++ = h[out[at++] & 15]; }
                *p = 0; emit(o, line);
            }
        }
        char line[57], *p = put(line, done ? "KBL1 done=1 rc=" : "KBL1 done=0 rc=");
        p = decimal(p, rc); *p = 0; emit(o, line);
        if (done) { s->has_job = 0; s->job = 0; s->phase = KBL_RUNNING; }
        final(o, 1, ""); return 1;
    }
    final(o, 0, "Invalid KBL command"); return 1;
}

int kbl_read_exact(struct kbl_reader *r, uint64_t offset, void *buffer, size_t bytes) {
    size_t done = 0;
    if (!r || !r->read_at || (!buffer && bytes) || offset > r->size || (uint64_t)bytes > r->size - offset) return KBL_ERROR;
    while (done < bytes) {
        size_t count = 0;
        int rc = r->read_at(r->opaque, offset + done, (unsigned char *)buffer + done, bytes - done, &count);
        if (rc != KBL_OK || !count || count > bytes - done) return KBL_ERROR;
        done += count;
    }
    return KBL_OK;
}
