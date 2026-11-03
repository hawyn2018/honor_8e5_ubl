/* SPDX-License-Identifier: MIT
 * Synthetic USB/UEFI provider. No device, firmware patch or USB hardware. */
#if defined(KBL_NATIVE_USB_FREESTANDING)
#include "native_usb_fixture_libc.h"
#else
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#endif
#include "../kbl_usb_native.c"
static unsigned assertions,cases,send_count,rx_rearms,allocations,frees,oem_frees,enters,leaves;
static unsigned allow_fence=1,complete_send=1,fail_send,fail_free,fail_alloc,download_mode;
static unsigned rx_abort_calls,extension_idle_calls,extension_core_calls,extension_event_calls;
static kefi_status event_error;
static void*last_rx_pointer;static size_t last_rx_bytes;static unsigned char adb_rx[512];
static uint64_t tick;
static int (*wire_sink)(const void*,size_t);
static unsigned char ack[64],rx[512],trace[4096];static size_t trace_n;
static struct kbl_usb_event events[128];static unsigned event_head,event_count;
static struct kbl_native_usb_binding b;
static struct kbl_usb_device_protocol device,*slot;
#define CHECK(x)do{++assertions;if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}}while(0)
static void push(struct kbl_usb_event e){CHECK(event_count<128);events[(event_head+event_count)%128]=e;++event_count;}
static struct kbl_usb_event completion(uint8_t ep,void*p,size_t n,unsigned status){struct kbl_usb_event e={0};e.kind=2;e.bytes=24;e.data.transfer=(struct kbl_usb_outcome){status,ep,{0,0,0},n,p};return e;}
static kefi_status usb_send(uint8_t ep,size_t n,void*p){++send_count;if(ep==1){last_rx_pointer=p;last_rx_bytes=n;}if(ep==0x81){if(wire_sink&&wire_sink(p,n))return KEFI_ERROR(7);if(trace_n+n<=sizeof(trace)){memcpy(trace+trace_n,p,n);trace_n+=n;}if(complete_send)push(completion(ep,p,n,1));}return fail_send?KEFI_ERROR(7):0;}
static kefi_status usb_event(uint32_t*k,size_t*n,void*p){if(event_error){kefi_status rc=event_error;event_error=0;return rc;}if(!event_count){*k=0;*n=0;return 0;}struct kbl_usb_event e=events[event_head];event_head=(event_head+1)%128;--event_count;*k=e.kind;*n=e.bytes;memcpy(p,&e.data,e.bytes);return 0;}
static kefi_status usb_allocate(size_t n,void**p){if(fail_alloc){*p=0;return KEFI_OUT_OF_RESOURCES;}*p=malloc(n);CHECK(*p);++allocations;return 0;}
static kefi_status usb_free(void*p){CHECK(p);if(fail_free)return KEFI_ERROR(7);++frees;free(p);return 0;}
static kefi_status usb_abort(uint8_t ep){CHECK(ep==0x81||ep==1);if(ep==1)++rx_abort_calls;return 0;}
static int enter(void*p){CHECK(p==&b);++enters;return allow_fence;}
static void leave(void*p){CHECK(p==&b);++leaves;}
static int command_rx(void*p,const struct kbl_usb_outcome*t){CHECK(p==&b);return !download_mode&&t->buffer==rx;}
static void native_final_fixture(void*p,int okay,const char*s){CHECK(p==&b);size_t n=strlen(s);CHECK(n<=60);memcpy(ack,okay?"OKAY":"FAIL",4);memcpy(ack+4,s,n);(void)slot->send(0x81,n+4,ack);}
static uint64_t clock_fixture(void*p){CHECK(p==&b);return ++tick;}
static void poll_native(void){uint32_t kind=0;size_t n=0;unsigned char payload[40]={0};CHECK(slot->handle_event(&kind,&n,payload)==0);if(kind==2){struct kbl_usb_outcome*t=(void*)payload;if(t->endpoint==0x81&&t->status==1){++rx_rearms;CHECK(slot->send(1,511,rx)==0);}}else if(kind==3){void*p;memcpy(&p,payload+32,8);free(p);++oem_frees;}}
static void fresh(void){
    /* Test-only fixture teardown, never a production recovery path. Models
     * with deliberately ambiguous foreign events retain observer ownership. */
    CHECK(!b.dma&&!b.sender.pending);active=0;memset(&b,0,sizeof(b));memset(&device,0,sizeof(device));
    device.revision=0x10000001;device.send=usb_send;device.handle_event=usb_event;device.allocate=usb_allocate;device.free=usb_free;device.abort=usb_abort;slot=&device;
    allow_fence=complete_send=1;fail_send=fail_free=fail_alloc=download_mode=0;event_head=event_count=0;trace_n=0;
    rx_abort_calls=extension_idle_calls=extension_core_calls=extension_event_calls=0;last_rx_pointer=0;last_rx_bytes=0;
    event_error=0;
    struct kbl_native_usb_environment e={&b,&slot,ack,enter,leave,command_rx,native_final_fixture,clock_fixture,0};
    unsigned sc=send_count,a=allocations;CHECK(kbl_native_usb_init(&b,&e,4096,4,4,8)==0);CHECK(send_count==sc&&allocations==a&&slot==&device);++cases;
}
static void witness(void){CHECK(kbl_native_usb_observe(&b)==0);CHECK(kbl_native_usb_begin(&b)==KBL_USB_UNAVAILABLE);native_final_fixture(&b,1,"");CHECK(b.native_pending&&!b.idle_witness);CHECK(kbl_native_usb_begin(&b)==KBL_USB_STATE);poll_native();CHECK(b.idle_witness&&!b.native_pending);}
static void command(void){push(completion(1,rx,16,1));poll_native();CHECK(b.command_ticket);}
static const struct kbl_firmware_link *begin(void){witness();command();CHECK(kbl_native_usb_begin(&b)==0);const struct kbl_firmware_link*l=kbl_native_usb_link(&b);CHECK(l&&b.private_active);return l;}
static void finish(const struct kbl_firmware_link*l){unsigned before=rx_rearms;CHECK(l->final(l->opaque,1,"")==0);CHECK(b.final_queued&&b.native_pending&&rx_rearms==before);poll_native();CHECK(rx_rearms==before+1&&!b.private_active&&!b.native_pending&&b.idle_witness);CHECK(slot==&b.clone);CHECK(kbl_native_usb_stop_observing(&b)==0&&slot==&device&&kbl_native_usb_can_release(&b));}
static void case_success(void){fresh();const struct kbl_firmware_link*l=begin();unsigned before=rx_rearms;CHECK(l->data(l->opaque,5)==0);size_t written=0;CHECK(l->write(l->opaque,"hello",5,&written)==0&&written==5);CHECK(rx_rearms==before&&!b.dma&&!b.sender.pending);finish(l);CHECK(trace_n>=25&&!memcmp(trace+4,"DATA00000005helloOKAY",21));}
static void case_replay(void){fresh();const struct kbl_firmware_link*l=begin();struct kbl_usb_event e={0};e.kind=3;e.bytes=40;void*context=malloc(8);CHECK(context);memcpy(e.data.opaque+32,&context,8);push(e);e.kind=1;e.bytes=4;e.data.state=0;push(e);unsigned before=oem_frees;CHECK(l->data(l->opaque,1)==0&&b.count==2&&oem_frees==before);CHECK(l->final(l->opaque,1,"")==0);poll_native();CHECK(oem_frees==before+1&&b.count==1);poll_native();CHECK(b.count==0&&b.final_queued);poll_native();CHECK(!b.private_active);CHECK(kbl_native_usb_stop_observing(&b)==0);}
static void case_passthrough(void){fresh();witness();unsigned a=allocations;for(unsigned i=0;i<4;++i){command();native_final_fixture(&b,1,"ordinary");poll_native();CHECK(b.idle_witness&&!b.native_pending&&!b.command_ticket);}CHECK(allocations==a&&!b.private_active);CHECK(kbl_native_usb_stop_observing(&b)==0);}
static void case_fence(void){fresh();witness();command();allow_fence=0;CHECK(kbl_native_usb_begin(&b)==KBL_USB_UNAVAILABLE&&!b.private_active);allow_fence=1;CHECK(kbl_native_usb_begin(&b)==0);finish(kbl_native_usb_link(&b));}
static void case_download(void){fresh();witness();download_mode=1;push(completion(1,rx,32,1));poll_native();CHECK(!b.command_ticket&&kbl_native_usb_begin(&b)==KBL_USB_UNAVAILABLE);download_mode=0;command();CHECK(kbl_native_usb_begin(&b)==0);finish(kbl_native_usb_link(&b));}
static void case_retained(void){fresh();const struct kbl_firmware_link*l=begin();complete_send=0;size_t written=99;CHECK(l->write(l->opaque,"abc",3,&written)!=0&&written==0&&b.sender.pending&&b.dma);l->abort(l->opaque);CHECK(b.private_active&&!l->quiescent(l->opaque));push(completion(0x81,b.sender.buffer,3,2));poll_native();CHECK(!b.sender.pending&&!b.dma&&!b.private_active&&b.aborted);CHECK(kbl_native_usb_stop_observing(&b)==0);}
static void case_ambiguous_send_error(void){fresh();const struct kbl_firmware_link*l=begin();fail_send=1;CHECK(l->data(l->opaque,3)==0);CHECK(!b.dma&&!b.sender.pending);fail_send=0;finish(l);}
static void case_free_failure(void){fresh();const struct kbl_firmware_link*l=begin();fail_free=1;CHECK(l->data(l->opaque,3)==0&&b.dma&&b.dma_released);CHECK(l->final(l->opaque,1,"")!=0);l->abort(l->opaque);CHECK(b.private_active);fail_free=0;poll_native();CHECK(!b.dma&&!b.private_active);CHECK(kbl_native_usb_stop_observing(&b)==0);}
static void case_alloc_failure(void){fresh();const struct kbl_firmware_link*l=begin();fail_alloc=1;CHECK(l->data(l->opaque,1)!=0&&!b.dma);l->abort(l->opaque);CHECK(!b.private_active&&kbl_native_usb_stop_observing(&b)==0);}
static void case_queue_full(void){fresh();const struct kbl_firmware_link*l=begin();complete_send=0;b.sender.timeout_us=b.sender.abort_timeout_us=1000;b.sender.poll_limit=64;struct kbl_usb_event e={0};e.kind=1;e.bytes=4;e.data.state=0;for(unsigned i=0;i<33;++i)push(e);CHECK(l->data(l->opaque,1)!=0&&b.sender.pending&&b.count==32&&event_count==1);l->abort(l->opaque);for(unsigned i=0;i<32;++i)poll_native();CHECK(!b.count&&event_count==1);push(completion(0x81,b.sender.buffer,12,2));poll_native();poll_native();CHECK(!b.dma&&!b.private_active);CHECK(kbl_native_usb_stop_observing(&b)==0);}
static void case_ordinary_overlap(void){fresh();witness();complete_send=0;CHECK(slot->send(0x81,4,ack)==0);CHECK(slot->send(0x81,4,ack)==0);CHECK(b.ambiguous);push(completion(0x81,ack,4,1));poll_native();push(completion(1,rx,12,1));poll_native();CHECK(kbl_native_usb_begin(&b)!=0&&!b.private_active);CHECK(kbl_native_usb_stop_observing(&b)==KBL_USB_RETAINED);}
static void case_stop_pending(void){fresh();CHECK(kbl_native_usb_observe(&b)==0);native_final_fixture(&b,1,"");CHECK(kbl_native_usb_stop_observing(&b)==KBL_USB_RETAINED&&slot==&b.clone);unsigned before=rx_rearms;poll_native();CHECK(slot==&device&&rx_rearms==before+1&&kbl_native_usb_can_release(&b));}
static void case_final_error(void){fresh();const struct kbl_firmware_link*l=begin();fail_send=1;unsigned before=send_count;CHECK(l->final(l->opaque,1,"")!=0&&b.final_queued&&send_count==before+1);CHECK(l->final(l->opaque,1,"")!=0&&send_count==before+1);fail_send=0;poll_native();CHECK(!b.private_active&&!b.native_pending);CHECK(kbl_native_usb_stop_observing(&b)==0);}
static void case_foreign_completion(void){fresh();const struct kbl_firmware_link*l=begin();complete_send=0;push(completion(0x81,ack,4,1));CHECK(l->data(l->opaque,1)!=0&&b.sender.pending&&b.ambiguous);l->abort(l->opaque);poll_native();push(completion(0x81,b.sender.buffer,12,2));poll_native();CHECK(!b.dma&&!b.sender.pending&&b.ambiguous);CHECK(kbl_native_usb_stop_observing(&b)==KBL_USB_RETAINED);}
static void case_short_native_completion(void){fresh();CHECK(kbl_native_usb_observe(&b)==0);complete_send=0;native_final_fixture(&b,1,"abc");push(completion(0x81,ack,4,1));poll_native();CHECK(!b.native_pending&&!b.idle_witness);push(completion(1,rx,16,1));poll_native();CHECK(!b.command_ticket&&kbl_native_usb_begin(&b)==KBL_USB_UNAVAILABLE);CHECK(kbl_native_usb_stop_observing(&b)==0);}
static void case_reconnect(void){fresh();witness();command();struct kbl_usb_event e={0};e.kind=1;e.bytes=4;e.data.state=1;push(e);poll_native();e.data.state=0;push(e);poll_native();CHECK(!b.idle_witness&&!b.command_ticket);push(completion(1,rx,16,1));poll_native();CHECK(kbl_native_usb_begin(&b)==KBL_USB_UNAVAILABLE);native_final_fixture(&b,1,"reconnected");poll_native();command();CHECK(kbl_native_usb_begin(&b)==0);finish(kbl_native_usb_link(&b));}
static void case_old_connection_ack(void){fresh();CHECK(kbl_native_usb_observe(&b)==0);complete_send=0;native_final_fixture(&b,1,"");struct kbl_usb_event e={0};e.kind=1;e.bytes=4;e.data.state=1;push(e);e.data.state=0;push(e);push(completion(0x81,ack,4,1));poll_native();poll_native();poll_native();CHECK(!b.native_pending&&!b.idle_witness);push(completion(1,rx,16,1));poll_native();CHECK(kbl_native_usb_begin(&b)==KBL_USB_UNAVAILABLE);CHECK(kbl_native_usb_stop_observing(&b)==0);}
static void case_partial_write(void){fresh();const struct kbl_firmware_link*l=begin();CHECK(l->data(l->opaque,10)==0);b.sender.max_bytes=4;const char*p="0123456789";size_t offset=0;unsigned chunks=0;while(offset<10){size_t n=0;CHECK(l->write(l->opaque,p+offset,10-offset,&n)==0&&n&&n<=4);offset+=n;++chunks;}CHECK(chunks==3);finish(l);}
static int extension_event(void*p,const struct kbl_usb_event*e){CHECK(p==&b);++extension_event_calls;
    if(e->kind==KBL_USB_EVENT_TRANSFER&&e->bytes==24&&e->data.transfer.endpoint==2&&e->data.transfer.buffer==adb_rx){++extension_core_calls;return 1;}return 0;}
static void extension_idle(void*p){CHECK(p==&b);++extension_idle_calls;}
static void extensions(void){const struct kbl_native_usb_extensions e={&b,extension_event,extension_idle};CHECK(kbl_native_usb_extensions(&b,&e)==0);}
static void case_start_reject_fail_info(void){fresh();CHECK(kbl_native_usb_observe(&b)==0);extensions();CHECK(kbl_native_usb_arm_start_ack(&b)==0);
    native_final_fixture(&b,0,"denied");poll_native();CHECK(!b.start_ack_complete&&!b.start_ack_forwarded&&!kbl_native_usb_start_ack_ready(&b,0));
    CHECK(kbl_native_usb_stop_observing(&b)==0);fresh();CHECK(kbl_native_usb_observe(&b)==0);extensions();CHECK(kbl_native_usb_arm_start_ack(&b)==0);
    memcpy(ack,"INFO",4);CHECK(slot->send(0x81,4,ack)==0);poll_native();CHECK(!b.start_ack_complete&&!kbl_native_usb_start_ack_ready(&b,0));
    CHECK(kbl_native_usb_stop_observing(&b)==0);}
static void case_start_forward_idle_abort_resume(void){fresh();CHECK(kbl_native_usb_observe(&b)==0);extensions();CHECK(kbl_native_usb_arm_start_ack(&b)==0);
    native_final_fixture(&b,1,"");CHECK(!kbl_native_usb_start_ack_ready(&b,1));struct kbl_usb_event e;
    CHECK(raw_event(&b,&e)==0&&b.start_ack_complete&&!b.start_ack_forwarded);CHECK(!kbl_native_usb_start_ack_ready(&b,0)&&!extension_idle_calls);
    CHECK(enqueue(&b,&e)==0);poll_native();CHECK(b.start_ack_forwarded&&b.native_rx_pending&&!extension_idle_calls);
    poll_native();CHECK(extension_idle_calls==1);CHECK(!kbl_native_usb_start_ack_ready(&b,1)&&rx_abort_calls==1&&b.native_rx_pending);
    CHECK(!kbl_native_usb_start_ack_ready(&b,1)&&rx_abort_calls==1);push(completion(1,rx,0,KBL_USB_ACTIVE));poll_native();CHECK(b.native_rx_pending);
    push(completion(1,adb_rx,0,KBL_USB_CANCELLED));poll_native();CHECK(b.native_rx_pending&&!kbl_native_usb_start_ack_ready(&b,1));
    push(completion(1,rx,0,KBL_USB_CANCELLED));poll_native();CHECK(!b.native_rx_pending&&kbl_native_usb_start_ack_ready(&b,1));
    unsigned before=send_count;CHECK(kbl_native_usb_resume_rx(&b)==0&&send_count==before+1&&b.native_rx_pending&&last_rx_pointer==rx&&last_rx_bytes==511);
    CHECK(kbl_native_usb_resume_rx(&b)==0&&send_count==before+1);CHECK(kbl_native_usb_stop_observing(&b)==0);}
static void case_start_resident_keeps_rx(void){fresh();CHECK(kbl_native_usb_observe(&b)==0);extensions();CHECK(kbl_native_usb_arm_start_ack(&b)==0);
    native_final_fixture(&b,1,"");poll_native();poll_native();CHECK(b.native_rx_pending&&kbl_native_usb_start_ack_ready(&b,0)&&!rx_abort_calls);
    CHECK(b.native_rx_pending&&last_rx_pointer==rx&&last_rx_bytes==511);CHECK(kbl_native_usb_stop_observing(&b)==0);}
static void case_extensions_private_vs_foreign(void){fresh();witness();extensions();unsigned idle=extension_idle_calls,rx_before=rx_rearms;uint64_t epoch=b.connection_epoch;
    push(completion(2,adb_rx,20,KBL_USB_COMPLETE));poll_native();CHECK(extension_core_calls==1&&extension_idle_calls==idle+1&&rx_rearms==rx_before&&b.connection_epoch==epoch&&!b.ambiguous);
    idle=extension_idle_calls;push(completion(2,rx,20,KBL_USB_COMPLETE));poll_native();CHECK(extension_core_calls==1&&extension_idle_calls==idle&&!b.ambiguous);
    struct kbl_usb_event state={0};state.kind=KBL_USB_EVENT_STATE;state.bytes=4;state.data.state=KBL_USB_CONNECTED;push(state);poll_native();
    CHECK(extension_core_calls==1&&extension_idle_calls==idle&&b.connection_epoch==epoch+1);CHECK(kbl_native_usb_stop_observing(&b)==0);}
static void case_provider_error_clears_stale_output(void){
    fresh();CHECK(kbl_native_usb_observe(&b)==0);extensions();
    const kefi_status statuses[]={KEFI_ERROR(21),KEFI_ERROR(7)};
    for(unsigned i=0;i<sizeof(statuses)/sizeof(statuses[0]);++i){
        uint32_t kind=KBL_USB_EVENT_TRANSFER;size_t bytes=24;
        struct kbl_usb_outcome stale={KBL_USB_FAILED,1,{0,0,0},0,rx},out=stale;
        event_error=statuses[i];CHECK(slot->handle_event(&kind,&bytes,&out)==statuses[i]);
        CHECK(kind==KBL_USB_EVENT_NONE&&bytes==0&&!memcmp(&out,&stale,sizeof(out)));
        CHECK(!b.fault&&!extension_core_calls&&!extension_idle_calls);
    }
    /* An original native endpoint cancellation remains an exact event, not
     * a blanket error suppression, and clears the per-call error status. */
    push(completion(1,rx,0,KBL_USB_CANCELLED));uint32_t kind=0;size_t bytes=0;struct kbl_usb_outcome out;
    CHECK(slot->handle_event(&kind,&bytes,&out)==KEFI_SUCCESS);
    CHECK(kind==KBL_USB_EVENT_TRANSFER&&bytes==24&&out.endpoint==1&&out.status==KBL_USB_CANCELLED);
    CHECK(kbl_native_usb_stop_observing(&b)==0);
}
static void case_malformed_after_provider_error(void){
    fresh();CHECK(kbl_native_usb_observe(&b)==0);uint32_t kind=2;size_t bytes=24;unsigned char data[40]={0};
    event_error=KEFI_ERROR(21);CHECK(slot->handle_event(&kind,&bytes,data)==KEFI_ERROR(21)&&!kind&&!bytes);
    struct kbl_usb_event bad={0};bad.kind=KBL_USB_EVENT_TRANSFER;bad.bytes=23;push(bad);
    kind=2;bytes=24;CHECK(slot->handle_event(&kind,&bytes,data)==KEFI_ERROR(7));
    CHECK(kind==KBL_USB_EVENT_NONE&&!bytes&&b.fault);
    CHECK(kbl_native_usb_stop_observing(&b)==KBL_USB_RETAINED);
}
static void case_revision_uses_required_operations(void){
    const uint64_t versions[]={0,UINT64_C(0x10000002),UINT64_MAX};
    for(unsigned i=0;i<sizeof(versions)/sizeof(versions[0]);++i){
        fresh();struct kbl_native_usb_environment e=b.env;memset(&b,0,sizeof(b));device.revision=versions[i];
        unsigned sends=send_count,a=allocations;
        CHECK(kbl_native_usb_init(&b,&e,4096,4,4,8)==0&&send_count==sends&&allocations==a);
        b.initialized=0;device.send=0;
        CHECK(kbl_native_usb_init(&b,&e,4096,4,4,8)==KBL_USB_UNAVAILABLE&&send_count==sends&&allocations==a);
    }
}
int main(void){case_revision_uses_required_operations();case_success();case_replay();case_passthrough();case_fence();case_download();case_retained();case_ambiguous_send_error();case_free_failure();case_alloc_failure();case_queue_full();case_ordinary_overlap();case_stop_pending();case_final_error();case_foreign_completion();case_short_native_completion();case_reconnect();case_old_connection_ack();case_partial_write();case_start_reject_fail_info();case_start_forward_idle_abort_resume();case_start_resident_keeps_rx();case_extensions_private_vs_foreign();case_provider_error_clears_stale_output();case_malformed_after_provider_error();CHECK(allocations==frees);printf("{\"scope\":\"synthetic USB/UEFI provider; actual observer/sender C modules\",\"cases\":%u,\"assertions\":%u,\"dma_allocations\":%u,\"dma_frees\":%u}\n",cases,assertions,allocations,frees);return 0;}
