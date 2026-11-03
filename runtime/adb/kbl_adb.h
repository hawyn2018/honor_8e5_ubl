/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_ADB_H
#define HNU_KBL_ADB_H
#include "../kbl_api.h"
#include "../sdk/kbl_program.h"
#define KBL_ADB_MAX_PAYLOAD 65536u
struct kbl_adb;
struct kbl_adb_files {
    void *token;
    const struct kbl_program_api *api;
    /* One entry per call; zero-based index, end=1 at EOF. Names are NUL
     * terminated. Returns zero or negative KBL_E*. Same owned I/O session. */
    int (*list)(void *,const char *,uint64_t,char *,size_t,struct kbl_file_stat *,int *end);
};
struct kbl_adb_config {
    void *opaque;
    void *(*allocate)(void *,size_t,size_t);
    void (*release)(void *,void *,size_t);
    unsigned (*current_el)(void *);
    int (*submit)(void *,const unsigned char *,size_t,uint64_t *job);
    int (*poll_job)(void *,uint64_t,unsigned,unsigned char *,size_t,size_t *,int *done,int *rc);
    int (*files_begin)(void *,struct kbl_adb_files *);
    int (*files_end)(void *,struct kbl_adb_files *);
    /* Notification only. Caller destroys KBL later, after stop_ready and
     * transport DMA quiescence; never from a job stack or this callback. */
    void (*stop_request)(void *);
    /* Optional native AUTH capability. Both required when authentication is
     * enabled. token fills fresh 20 bytes; verify returns zero for accepted
     * RSA signature. No Android framework/UI authorization is implied. */
    int (*auth_token)(void *,unsigned char token[20]);
    int (*auth_verify)(void *,const unsigned char token[20],const void *,size_t);
    /* Required if AUTH callbacks are absent: this is an explicit USB-enabled
     * KBL debug session, not Android's authorized adbd or a network listener. */
    unsigned explicit_usb_debug;
    /* Optional all-or-none per-channel native capabilities. Created only for
     * explicit ADB OPEN after START. AGAIN means no accepted action (except
     * stable IO ticket enqueue); callbacks never replay partial file writes.
     * close cancels only this session and consumes it only on KBL_OK. */
    int (*session_create)(void *,void **);
    int (*session_close)(void *,void *);
    int (*session_submit)(void *,void *,const unsigned char *,size_t,uint64_t *);
    int (*session_poll_job)(void *,void *,uint64_t,unsigned,unsigned char *,size_t,size_t *,int *,int *);
    int (*session_files_begin)(void *,void *,struct kbl_adb_files *);
    int (*session_files_end)(void *,void *,struct kbl_adb_files *);
};
/* Only after successful explicit KBL start. No USB/network/EL initialization. */
int kbl_adb_create(const struct kbl_adb_config *,struct kbl_adb **);
int kbl_adb_feed(struct kbl_adb *,const void *,size_t,size_t *consumed);
/* Invoke only for the enabled ADB session, never ordinary Fastboot work. */
int kbl_adb_poll(struct kbl_adb *,unsigned budget_us);
/* Borrowed persistent TX bytes. output_done confirms terminal DMA ownership
 * return of that span; partial completions advance the frame. Never free or
 * reuse a borrowed span before completion/terminal abort. */
int kbl_adb_output(struct kbl_adb *,const void **,size_t *);
int kbl_adb_output_done(struct kbl_adb *,size_t confirmed,int status);
int kbl_adb_stop_requested(const struct kbl_adb *);
/* External Fastboot stop request uses the same orderly protocol drain. */
int kbl_adb_request_stop(struct kbl_adb *);
int kbl_adb_stop_ready(const struct kbl_adb *);
/* Healthy OFF may retain this core and its USB buffers. Native quiescence
 * excludes transport/rejection traffic: every native file session is closed,
 * STOP prevents new callbacks, and no core call is executing. A suspended job
 * still belongs to the caller's native backend until its actual STOP succeeds. */
int kbl_adb_healthy(const struct kbl_adb *);
int kbl_adb_native_quiescent(const struct kbl_adb *);
/* Caller attests that the actual native backend has already been destroyed.
 * Only after native_quiescent. This clears stale job state, not wire/DMA state. */
int kbl_adb_native_stopped(struct kbl_adb *);
int kbl_adb_parked(const struct kbl_adb *);
/* Caller has created the replacement native backend and completed the explicit
 * START acknowledgement. Resume the same healthy core/connection; no USB
 * action, file/job replay, or clearing of sticky faults is performed here. */
int kbl_adb_resume(struct kbl_adb *);
/* Disconnect is called after all borrowed DMA is returned. It closes sync
 * handles, retains any suspended native job, and does not destroy KBL. */
int kbl_adb_disconnect(struct kbl_adb *);
int kbl_adb_destroy(struct kbl_adb *);
#endif
