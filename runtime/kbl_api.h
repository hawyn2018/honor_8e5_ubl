/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_API_H
#define HNU_KBL_API_H

#include <stddef.h>
#include <stdint.h>

#define KBL_LINE_BYTES 4096u
#define KBL_WIRE_BYTES 63u
#define KBL_QUANTUM_US 2000u
#define KBL_OUTPUT_BYTES 24u

enum kbl_phase { KBL_STOPPED, KBL_STARTING, KBL_RUNNING, KBL_BUSY, KBL_STOPPING, KBL_FAULTED };
enum kbl_result { KBL_OK = 0, KBL_AGAIN = 1, KBL_ERROR = -1 };

/* No firmware constructors, timers, vectors or I/O hooks are installed here.
 * poll must cooperatively yield within budget; this API is not fault isolation.
 * A real same-EL backend is required; absent callbacks must fail, never pretend
 * a microkernel has started. On start failure, return a nonnull context if
 * cleanup is still required; the controller retains it for explicit kbloff.
 */
struct kbl_backend {
    void *opaque;
    unsigned (*current_el)(void *opaque);
    int (*start)(void *opaque, void **context);
    int (*submit)(void *context, const unsigned char *line, size_t bytes, uint64_t *job);
    int (*poll)(void *context, uint64_t job, unsigned budget_us,
                unsigned char *output, size_t capacity, size_t *written, int *done, int *rc);
    int (*stop)(void *context);
    /* Optional controller staging allocator; opaque outlives context/stop.
     * With these callbacks, successful submit copies line before returning. */
    void *(*allocate)(void *opaque, size_t bytes);
    void (*release)(void *opaque, void *memory, size_t bytes);
};

struct kbl_output {
    void *opaque;
    void (*info)(void *opaque, const char *ascii); /* <=56 bytes per callback */
    void (*final)(void *opaque, int okay, const char *ascii);
};

struct kbl_session {
    enum kbl_phase phase;
    void *context;
    uint64_t generation, job, upload;
    unsigned el;
    unsigned has_job, has_upload;
    size_t line_bytes;
    unsigned char line[KBL_LINE_BYTES];
    unsigned char *dynamic_line;
    size_t line_capacity;
};

/* Caller-owned zeroed session is stopped. Unknown/native Fastboot requests
 * return 0 with no callbacks, allocation or runtime execution. Known requests
 * return 1 and exactly one final response. kblin/status are snapshots only.
 */
int kbl_handle(struct kbl_session *, const struct kbl_backend *,
               const unsigned char *request, size_t bytes, const struct kbl_output *);

/* Reader service is separate from the kernel lifecycle. A partition source
 * can be used while KBL is stopped, without initializing KBL. File sources
 * require a live context and a backend-owned immutable/read-locked snapshot.
 * The transport must use binary DATA/fetch framing, not INFO hex for images.
 */
struct kbl_reader {
    void *opaque;
    uint64_t size;
    int (*read_at)(void *opaque, uint64_t offset, void *buffer, size_t bytes, size_t *read);
    void (*close)(void *opaque);
};
int kbl_read_exact(struct kbl_reader *, uint64_t offset, void *buffer, size_t bytes);

#endif
