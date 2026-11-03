/* SPDX-License-Identifier: MIT */
#include "kbl_usb_native.h"
static struct kbl_native_usb_binding *active;
static void zero(void*p,size_t n){unsigned char*d=p;while(n--)*d++=0;}
static void copy(void*p,const void*q,size_t n){unsigned char*d=p;const unsigned char*s=q;while(n--)*d++=*s++;}
static int terminal(const struct kbl_usb_event*e){return e->kind==KBL_USB_EVENT_TRANSFER&&e->bytes==24&&e->data.transfer.status>=KBL_USB_COMPLETE&&e->data.transfer.status<=KBL_USB_FAILED;}
static int shape(const struct kbl_usb_event*e){
    return e->kind==KBL_USB_EVENT_NONE?e->bytes==0:
      e->kind==KBL_USB_EVENT_STATE?e->bytes==4&&e->data.state<=KBL_USB_DISCONNECTED:
      e->kind==KBL_USB_EVENT_TRANSFER?e->bytes==24&&e->data.transfer.status<=KBL_USB_FAILED:
      e->kind==KBL_USB_EVENT_OEM?e->bytes==40:0;
}
static void retry_free(struct kbl_native_usb_binding*b){
    if(b->dma&&b->dma_released&&!b->sender.pending&&b->original->free(b->dma)==KEFI_SUCCESS){b->dma=0;b->dma_released=0;}
}
static int resources(const struct kbl_native_usb_binding*b){return b->sender.pending||b->dma||b->count||b->have_injected||b->final_queued||b->final_call||b->in_call||b->private_active||b->native_pending||b->fault||b->ambiguous;}
static void detach_if_done(struct kbl_native_usb_binding*b){
    if(b->private_active&&!b->sender.pending&&!b->dma&&!b->final_queued&&!b->in_call&&(b->aborted||b->final_observed)){
        b->private_active=0;b->sender.claimed=0;b->env.leave_fetch_fence(b->env.opaque);
    }
    if(!b->attached||!b->detach_requested||resources(b))return;
    if(*b->env.fbd_protocol_slot!=&b->clone){b->fault=1;return;}
    *b->env.fbd_protocol_slot=b->original;b->attached=0;active=0;b->sender.claimed=0;
}
static void observe_event(struct kbl_native_usb_binding*b,const struct kbl_usb_event*e){
    if(e->kind==KBL_USB_EVENT_STATE){++b->connection_epoch;b->idle_witness=0;b->command_ticket=0;return;}
    if(e->kind!=KBL_USB_EVENT_TRANSFER||e->bytes!=24)return;
    const struct kbl_usb_outcome*t=&e->data.transfer;
    if(t->endpoint==0x81){
        if(b->sender.pending&&t->buffer==b->sender.buffer)return;
        if(b->native_pending&&t->buffer==b->native_pending_buffer){
            if(terminal(e)){
                b->native_pending=0;b->idle_witness=t->status==KBL_USB_COMPLETE&&t->completed==b->native_pending_bytes&&b->native_tx_epoch==b->connection_epoch;
                if(b->start_ack_queued){b->start_ack_queued=0;b->start_ack_complete=b->idle_witness;}
                if(b->final_queued&&t->buffer==b->env.native_ack_buffer){
                    b->final_queued=0;b->final_observed=1;if(!b->idle_witness)b->aborted=1;
                }
            }
        }else if(terminal(e)){b->ambiguous=1;b->idle_witness=0;}
    }else if(t->endpoint==1){
        if(b->native_rx_pending&&t->buffer==b->native_rx_buffer&&terminal(e)){
            b->native_rx_pending=0;b->native_rx_abort=0;
        }
        if(t->status!=KBL_USB_COMPLETE||b->env.is_command_rx(b->env.opaque,t)!=1)return;
        if(b->private_active||b->command_ticket){b->ambiguous=1;b->idle_witness=0;}
        else if(b->idle_witness&&!b->native_pending&&!b->ambiguous){++b->rx_epoch;if(!b->rx_epoch)++b->rx_epoch;b->command_ticket=1;}
    }
}
static int raw_event(struct kbl_native_usb_binding*b,struct kbl_usb_event*e){
    zero(e,sizeof(*e));b->provider_event_status=KEFI_SUCCESS;
    if(b->have_injected){*e=b->injected;b->have_injected=0;return 0;}
    b->provider_event_status=b->original->handle_event(&e->kind,&e->bytes,&e->data);
    if(b->provider_event_status!=KEFI_SUCCESS){
        if(!b->first_event_error)b->first_event_error=b->provider_event_status;
        if(b->provider_errors!=UINT32_MAX)++b->provider_errors;
        return KBL_USB_IO;
    }
    if(!shape(e)){b->fault=1;return KBL_USB_BAD_EVENT;}
    if(b->extensions.event){int owned=b->extensions.event(b->extensions.opaque,e);
        if(owned){/* A private error remains private; native state is not fed a foreign endpoint. */
            zero(e,sizeof(*e));return 0;}}
    observe_event(b,e);return 0;
}
static int enqueue(void*p,const struct kbl_usb_event*e){
    struct kbl_native_usb_binding*b=p;
    /* The synchronous sender owns its intermediate terminal notifications;
     * these must never trigger native final-TX RX rearm, including errors. */
    if(terminal(e)&&e->data.transfer.endpoint==0x81&&e->data.transfer.buffer==b->sender.buffer)return 0;
    if(b->count==KBL_NATIVE_USB_QUEUE){b->fault=1;return KBL_USB_FORWARD;}
    b->events[(b->head+b->count)%KBL_NATIVE_USB_QUEUE]=*e;++b->count;return 0;
}
static int next_owned_event(void*p,struct kbl_usb_event*e){
    struct kbl_native_usb_binding*b=p;
    /* Refuse BEFORE consuming another provider event when replay is full. */
    if(b->count==KBL_NATIVE_USB_QUEUE&&!b->have_injected)return KBL_USB_FORWARD;
    return raw_event(b,e);
}
static int claim(void*p){struct kbl_native_usb_binding*b=p;return b->attached&&b->private_active&&active==b&&!b->fault&&!b->ambiguous?0:-1;}
static void release_claim(void*p){(void)p;/* Clone/fence remain until native replay/final completion. */}
static int allocate(void*p,size_t n,void**out){
    struct kbl_native_usb_binding*b=p;*out=0;retry_free(b);if(b->dma)return -1;
    void *memory=0;if(b->original->allocate(n,&memory)!=KEFI_SUCCESS||!memory)return -1;
    b->dma=memory;b->dma_released=0;*out=memory;return 0;
}
static void release_dma(void*p,void*memory){struct kbl_native_usb_binding*b=p;if(memory!=b->dma||b->dma_released){b->fault=1;return;}b->dma_released=1;retry_free(b);}
static int send_owned(void*p,uint8_t ep,size_t n,void*memory){
    struct kbl_native_usb_binding*b=p;if(ep!=0x81||memory!=b->dma)return -1;
    /* Native Send may ambiguously return error after submission. Normalize
     * that as accepted/pending; wait for an identified terminal notification,
     * or retain DMA indefinitely. Never free just because Send returned error. */
    (void)b->original->send(ep,n,memory);return 0;
}
static int abort_owned(void*p,uint8_t ep){struct kbl_native_usb_binding*b=p;return b->original->abort&&b->original->abort(ep)==KEFI_SUCCESS?0:-1;}
static uint64_t now(void*p){struct kbl_native_usb_binding*b=p;return b->env.now_us(b->env.opaque);}
static void relax(void*p){struct kbl_native_usb_binding*b=p;if(b->env.relax)b->env.relax(b->env.opaque);}
static kefi_status native_send(uint8_t ep,size_t n,void*memory){
    struct kbl_native_usb_binding*b=active;if(!b||!b->attached)return KEFI_ACCESS_DENIED;
    if(b->final_call){
        if(ep!=0x81||memory!=b->env.native_ack_buffer||n<4||n>64||b->native_sends++){
            b->fault=1;return KEFI_ACCESS_DENIED;
        }
        b->final_bytes=n;b->final_queued=1;
    }
    /* Native RX rearm and events are handled by the original state machine;
     * no command parser, kernel poll, Start/Stop or synthetic RX is injected. */
    if(ep==0x81){
        if((b->private_active&&!b->final_call)||b->native_pending)b->ambiguous=1;
        if(!b->native_pending){b->native_pending=1;b->native_pending_buffer=memory;b->native_pending_bytes=n;b->native_tx_epoch=b->connection_epoch;}
        b->idle_witness=0;b->command_ticket=0;
        if(b->start_ack_wait&&!b->start_ack_complete&&memory==b->env.native_ack_buffer&&n>=4&&n<=64&&
           ((const unsigned char*)memory)[0]=='O'&&((const unsigned char*)memory)[1]=='K'&&
           ((const unsigned char*)memory)[2]=='A'&&((const unsigned char*)memory)[3]=='Y')
            b->start_ack_queued=1;
    }else if(ep==1){
        if(b->native_rx_pending)b->ambiguous=1;
        b->native_rx_pending=1;b->native_rx_abort=0;b->native_rx_buffer=memory;b->native_rx_bytes=n;
    }
    kefi_status rc=b->original->send(ep,n,memory);if(b->final_call)b->final_status=rc;return rc;
}
static kefi_status replay_event(uint32_t*kind,size_t*bytes,void*data){
    if(!kind||!bytes||!data)return KEFI_INVALID;
    /* The native provider defines NONE even on a lower-provider error. The
     * original Fastboot loop reads this output without testing return X0;
     * leaving its prior transfer here can replay a stale FAILED completion. */
    *kind=KBL_USB_EVENT_NONE;*bytes=0;
    struct kbl_native_usb_binding*b=active;if(!b||!b->attached)return KEFI_INVALID;
    if(b->in_call){b->fault=1;return KEFI_ACCESS_DENIED;}
    retry_free(b);struct kbl_usb_event e;zero(&e,sizeof(e));
    if(b->count){e=b->events[b->head];b->head=(b->head+1)%KBL_NATIVE_USB_QUEUE;--b->count;}
    else{
        int rc=raw_event(b,&e);if(rc)return b->provider_event_status!=KEFI_SUCCESS?b->provider_event_status:KEFI_ERROR(7);
        if(terminal(&e)&&b->sender.pending&&e.data.transfer.endpoint==0x81&&e.data.transfer.buffer==b->sender.buffer){
            b->injected=e;b->have_injected=1;(void)kbl_usb_reap(&b->sender);zero(&e,sizeof(e));
        }
    }
    *kind=e.kind;*bytes=e.bytes;copy(data,&e.data,e.bytes);
    if(terminal(&e)&&e.data.transfer.endpoint==0x81&&e.data.transfer.buffer==b->env.native_ack_buffer&&b->start_ack_complete)
        b->start_ack_forwarded=1;
    if(e.kind==KBL_USB_EVENT_NONE&&b->extensions.idle)b->extensions.idle(b->extensions.opaque);
    /* Only terminal private events are consumed. State/RX/OEM events, with
     * original Context ownership, are replayed once in original order. */
    detach_if_done(b);return KEFI_SUCCESS;
}
static int link_info(void*p,const char*s){(void)p;(void)s;return KBL_S_UNAVAILABLE;}
static int link_data(void*p,uint32_t n){
    struct kbl_native_usb_binding*b=p;char header[12]={'D','A','T','A'};const char*h="0123456789abcdef";
    if(!b->attached||!b->private_active||b->aborted||b->fault||b->ambiguous)return -1;
    for(unsigned i=0;i<8;++i)header[4+i]=h[(n>>((7-i)*4))&15];
    b->in_call=1;int rc=kbl_usb_send(&b->sender,header,sizeof(header),KBL_USB_INTERMEDIATE);b->in_call=0;return rc?KBL_S_IO:0;
}
static int link_write(void*p,const void*data,size_t n,size_t*written){
    struct kbl_native_usb_binding*b=p;if(!written)return -1;*written=0;
    if(!b->attached||!b->private_active||b->aborted||b->fault||b->ambiguous)return -1;
    size_t chunk=n>b->sender.max_bytes?b->sender.max_bytes:n;
    b->in_call=1;int rc=kbl_usb_send(&b->sender,data,chunk,KBL_USB_INTERMEDIATE);b->in_call=0;
    if(!rc)*written=chunk;return rc?KBL_S_IO:0;
}
static int link_final(void*p,int okay,const char*s){
    struct kbl_native_usb_binding*b=p;
    if(!b->attached||!b->private_active||b->aborted||b->fault||b->ambiguous||b->sender.pending||b->dma||b->final_queued||!s)return KBL_S_IO;
    size_t n=0;while(n<61&&s[n]){if((unsigned char)s[n]<32||(unsigned char)s[n]>126)return KBL_S_IO;++n;}if(n>60)return KBL_S_IO;
    b->in_call=1;b->final_call=1;b->native_sends=0;b->final_status=KEFI_ACCESS_DENIED;
    b->env.native_final(b->env.opaque,okay,s);b->final_call=0;b->in_call=0;b->sender.claimed=0;
    if(b->native_sends!=1){b->fault=1;return KBL_S_IO;}
    return b->final_status==KEFI_SUCCESS?0:KBL_S_IO;
}
static void link_abort(void*p){
    struct kbl_native_usb_binding*b=p;b->aborted=1;b->command_ticket=0;b->idle_witness=0;
    if(b->sender.pending&&!b->sender.abort_requested){b->sender.abort_requested=1;(void)abort_owned(b,0x81);}
    b->sender.claimed=0;retry_free(b);detach_if_done(b);
}
static int link_quiet(void*p){struct kbl_native_usb_binding*b=p;return !resources(b);}
int kbl_native_usb_init(struct kbl_native_usb_binding*b,const struct kbl_native_usb_environment*e,
                        size_t max_send,uint64_t timeout,uint64_t abort_timeout,unsigned limit){
    if(!b||!e||b->initialized||!e->fbd_protocol_slot||!e->native_ack_buffer||!e->enter_fetch_fence||!e->leave_fetch_fence||!e->is_command_rx||!e->native_final||!e->now_us)return KBL_USB_UNAVAILABLE;
    struct kbl_usb_device_protocol*d=*e->fbd_protocol_slot;
    /* A provider's numeric revision is informational. Probe the operations we
     * actually consume; failure is local to explicit KBL activation. */
    if(!d||!d->send||!d->handle_event||!d->allocate||!d->free)return KBL_USB_UNAVAILABLE;
    b->env=*e;b->original=d;
    const struct kbl_usb_ops o={b,claim,release_claim,allocate,release_dma,send_owned,next_owned_event,abort_owned,now,relax,enqueue};
    int rc=kbl_usb_init(&b->sender,&o,max_send,timeout,abort_timeout,limit);if(rc)return rc;
    b->link=(struct kbl_firmware_link){1,sizeof(b->link),b,link_info,link_final,link_data,link_write,link_abort,link_quiet,KBL_LINK_DEFER_INFO};
    b->initialized=1;return 0;
}
int kbl_native_usb_observe(struct kbl_native_usb_binding*b){
    if(!b||!b->initialized||b->attached||active||resources(b)||*b->env.fbd_protocol_slot!=b->original)return KBL_USB_STATE;
    b->clone=*b->original;b->clone.handle_event=replay_event;b->clone.send=native_send;
    b->aborted=b->final_observed=b->native_sends=b->idle_witness=b->command_ticket=b->detach_requested=0;
    b->attached=1;active=b;*b->env.fbd_protocol_slot=&b->clone;return 0;
}
int kbl_native_usb_begin(struct kbl_native_usb_binding*b){
    if(!b||!b->initialized||!b->attached||active!=b||resources(b)||*b->env.fbd_protocol_slot!=&b->clone)return KBL_USB_STATE;
    if(!b->idle_witness||!b->command_ticket||b->rx_epoch==b->used_rx_epoch||b->env.enter_fetch_fence(b->env.opaque)!=1)return KBL_USB_UNAVAILABLE;
    b->private_active=1;b->used_rx_epoch=b->rx_epoch;b->command_ticket=0;b->aborted=b->final_observed=b->native_sends=0;
    int rc=kbl_usb_begin(&b->sender);if(rc){b->aborted=1;detach_if_done(b);}return rc;
}
int kbl_native_usb_stop_observing(struct kbl_native_usb_binding*b){
    if(!b||!b->initialized||!b->attached)return KBL_USB_STATE;
    b->detach_requested=1;retry_free(b);detach_if_done(b);return b->attached?KBL_USB_RETAINED:0;
}
const struct kbl_firmware_link *kbl_native_usb_link(struct kbl_native_usb_binding*b){return b&&b->initialized&&b->attached&&b->private_active?&b->link:0;}
int kbl_native_usb_can_release(const struct kbl_native_usb_binding*b){return b&&b->initialized&&!b->attached&&!resources(b)&&active!=b;}
int kbl_native_usb_extensions(struct kbl_native_usb_binding*b,const struct kbl_native_usb_extensions*e){
    if(!b||!b->initialized||!e||b->in_call||b->private_active)return KBL_USB_STATE;b->extensions=*e;return 0;
}
int kbl_native_usb_arm_start_ack(struct kbl_native_usb_binding*b){
    if(!b||!b->initialized||!b->attached||b->in_call||b->native_pending||b->private_active||b->fault||b->ambiguous)return KBL_USB_STATE;
    b->start_ack_wait=1;b->start_ack_queued=b->start_ack_complete=b->start_ack_forwarded=0;return 0;
}
int kbl_native_usb_start_ack_ready(struct kbl_native_usb_binding*b,int quiesce_rx){
    if(!b||!b->initialized||!b->attached||!b->start_ack_wait||!b->start_ack_complete||!b->start_ack_forwarded||
       b->in_call||b->private_active||b->count||b->have_injected||b->native_pending||!b->idle_witness||b->fault||b->ambiguous)return 0;
    if(quiesce_rx&&b->native_rx_pending){
        if(!b->native_rx_abort&&b->original->abort){b->native_rx_abort=1;(void)b->original->abort(1);}
        return 0;
    }
    return 1;
}
int kbl_native_usb_resume_rx(struct kbl_native_usb_binding*b){
    if(!b||!b->initialized||!b->attached||b->in_call||b->private_active||b->native_pending||b->fault||b->ambiguous)return KBL_USB_STATE;
    if(b->native_rx_pending)return KBL_USB_OK;
    if(!b->native_rx_buffer||!b->native_rx_bytes)return KBL_USB_UNAVAILABLE;
    return native_send(1,b->native_rx_bytes,b->native_rx_buffer)==KEFI_SUCCESS?KBL_USB_OK:KBL_USB_IO;
}
