/* SPDX-License-Identifier: MIT */
#ifndef HNU_KBL_NATIVE_IO_H
#define HNU_KBL_NATIVE_IO_H
#include "kbl_native.h"
/* Explicit serialized external I/O capability, for an already-started native
 * environment. Does not mark a host stack as a job/fiber. File calls are
 * bounded synchronous operations; checkpoint never context-switches here.
 * The returned table belongs to the session until end. */
int kbl_native_io_begin(void *,const struct kbl_program_api **);
int kbl_native_io_end(void *,const struct kbl_program_api *);
int kbl_native_io_list(void *,const struct kbl_program_api *,const char *,uint64_t,
                       char *,size_t,struct kbl_file_stat *,int *end);
int kbl_native_service_submit(void *,const unsigned char *,size_t,uint64_t *);
int kbl_native_service_poll(void *,uint64_t,unsigned,unsigned char *,size_t,size_t *,int *,int *);
/* One shared world, isolated connection state. A successful submit owns a
 * copied command exactly once; polling a queued job advances the FIFO world
 * executor. No command/write is retried by this queue. IO begin queues a stable
 * request and returns AGAIN until its turn. End each complete sync transaction
 * so an idle connection holds no executor lease. Close cancels only this
 * session, including its suspended job; AGAIN retains ownership for retry. */
int kbl_native_session_create(void *,void **);
int kbl_native_session_close(void *,void *);
int kbl_native_session_submit(void *,void *,const unsigned char *,size_t,uint64_t *);
int kbl_native_session_poll(void *,void *,uint64_t,unsigned,unsigned char *,size_t,size_t *,int *,int *);
int kbl_native_session_io_begin(void *,void *,const struct kbl_program_api **);
int kbl_native_session_io_end(void *,void *,const struct kbl_program_api *);
#endif
