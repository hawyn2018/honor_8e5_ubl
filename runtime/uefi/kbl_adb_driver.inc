/* SPDX-License-Identifier: MIT -- included by the single-owner EFI driver.
 * Nothing in this file runs from EfiMain or ordinary Fastboot registration. */
struct adb_span {uint64_t begin,end;};
static int adb_span_owns(void*p,uint64_t at,uint64_t n){
    const struct adb_span*s=p;return at>=s->begin&&at<=s->end&&n<=s->end-at;
}
static int adb_span_access(const void*pointer,size_t n,unsigned denied){
    uintptr_t at=(uintptr_t)pointer;if(!at||!n||n>65536||at>UINTPTR_MAX-n)return 0;
#if defined(KBL_DRIVER_ADB_TEST)
    extern int kbl_driver_test_span(const void*,size_t,unsigned);
    (void)adb_span_owns;return kbl_driver_test_span(pointer,n,denied);
#elif defined(KBL_DRIVER_TEST)
    /* Existing host driver fixtures do not supply real page tables. Native
     * ADB remains unavailable there; integration fixtures inject real walks. */
    (void)denied;(void)adb_span_owns;return 0;
#else
    struct adb_span s={at&~UINT64_C(4095),(at+n+4095)&~UINT64_C(4095)};
    if(s.end<at+n)return 0;struct kbl_arch_memory arch;kbl_arch_memory_native(&arch,&s,adb_span_owns);
    for(uint64_t page=s.begin;page<s.end;page+=4096){struct kbl_arch_page facts;
        if(!kbl_arch_memory_page(&arch,page,&facts)||(facts.attributes&denied))return 0;}
    return 1;
#endif
}
static int adb_span_read(void*unused,const void*p,size_t n){(void)unused;return adb_span_access(p,n,KEFI_RP);}
static void *adb_allocate(void*unused,size_t bytes,size_t alignment){
    (void)unused;if(!g.bs||!g.bs->allocate_pool||!g.bs->free_pool||!bytes||!alignment||(alignment&(alignment-1))||
       alignment-1>SIZE_MAX-sizeof(struct pool)||bytes>SIZE_MAX-sizeof(struct pool)-(alignment-1))return 0;
    void *raw=0;if(g.bs->allocate_pool(4,sizeof(struct pool)+bytes+alignment-1,&raw)!=KEFI_SUCCESS||!raw)return 0;
    struct pool*p=raw;zero(p,sizeof(*p));p->bytes=bytes;p->user=(void*)(((uintptr_t)(p+1)+alignment-1)&~(uintptr_t)(alignment-1));
    p->next=g.adb_pools;if(g.adb_pools)g.adb_pools->previous=p;g.adb_pools=p;return p->user;
}
static void adb_retry_pools(void){
    struct pool*p=g.adb_pools;while(p){struct pool*next=p->next;
        if(p->released){struct pool*previous=p->previous;
            if(g.bs->free_pool(p)==KEFI_SUCCESS){if(previous)previous->next=next;else g.adb_pools=next;if(next)next->previous=previous;}}
        p=next;}
}
static void adb_release(void*unused,void*memory,size_t bytes){
    (void)unused;if(!memory)return;
    for(struct pool*p=g.adb_pools;p;p=p->next){if(p->user==memory){
        if(p->released||p->bytes!=bytes){g.adb_error=1;return;}p->released=1;adb_retry_pools();return;}}
    g.adb_error=1;
}
static int adb_submit(void*p,const unsigned char*s,size_t n,uint64_t*job){
    if(p!=&g||!live()||g.in_request||g.adb_call!=1)return KBL_ERROR;
    return kbl_native_service_submit(g.kernel.native,s,n,job);
}
static int adb_poll_job(void*p,uint64_t job,unsigned budget,unsigned char*out,size_t cap,size_t*n,int*done,int*exit){
    if(p!=&g||!live()||g.in_request||g.adb_call!=1)return KBL_ERROR;
    return kbl_native_service_poll(g.kernel.native,job,budget,out,cap,n,done,exit);
}
static int adb_list(void*token,const char*path,uint64_t index,char*name,size_t cap,struct kbl_file_stat*st,int*end){
    if(!live()||g.in_request||g.adb_call!=1)return -KBL_EBUSY;
    return kbl_native_io_list(g.kernel.native,token,path,index,name,cap,st,end);
}
static int adb_files_begin(void*p,struct kbl_adb_files*out){
    if(p!=&g||!out||!live()||g.in_request||g.adb_call!=1)return -KBL_EBUSY;
    const struct kbl_program_api*api=0;int rc=kbl_native_io_begin(g.kernel.native,&api);
    if(rc)return rc;*out=(struct kbl_adb_files){(void*)api,api,adb_list};return 0;
}
static int adb_files_end(void*p,struct kbl_adb_files*f){
    if(p!=&g||!f||!g.kernel.native||g.adb_call!=1)return -KBL_EBUSY;
    return kbl_native_io_end(g.kernel.native,f->api);
}
static int adb_session_create(void*p,void**session){
    if(p!=&g||!session||!live()||g.in_request||g.adb_call!=1)return KBL_ERROR;
    return kbl_native_session_create(g.kernel.native,session);
}
static int adb_session_close(void*p,void*session){
    if(p!=&g||!g.kernel.native||g.adb_call!=1)return KBL_AGAIN;
    return kbl_native_session_close(g.kernel.native,session);
}
static int adb_session_submit(void*p,void*session,const unsigned char*line,size_t n,uint64_t*job){
    if(p!=&g||!live()||g.in_request||g.adb_call!=1)return KBL_ERROR;
    return kbl_native_session_submit(g.kernel.native,session,line,n,job);
}
static int adb_session_poll_job(void*p,void*session,uint64_t job,unsigned budget,unsigned char*out,size_t cap,size_t*n,int*done,int*exit){
    if(p!=&g||!live()||g.in_request||g.adb_call!=1)return KBL_ERROR;
    return kbl_native_session_poll(g.kernel.native,session,job,budget,out,cap,n,done,exit);
}
static int adb_session_files_begin(void*p,void*session,struct kbl_adb_files*out){
    if(p!=&g||!out||!live()||g.in_request||g.adb_call!=1)return KBL_AGAIN;
    const struct kbl_program_api*api=0;int r=kbl_native_session_io_begin(g.kernel.native,session,&api);
    if(r)return r;*out=(struct kbl_adb_files){(void*)api,api,adb_list};return 0;
}
static int adb_session_files_end(void*p,void*session,struct kbl_adb_files*files){
    if(p!=&g||!files||!g.kernel.native||g.adb_call!=1)return KBL_AGAIN;
    return kbl_native_session_io_end(g.kernel.native,session,files->api);
}
static void adb_stop_request(void*p){if(p==&g){g.adb_stop=g.adb_native_stop=1;g.adb_phase="stopping";}}
static int adb_feed(void*p,const void*bytes,size_t n,size_t*used){
    if(p!=&g||!g.adb||g.adb_call)return KBL_ERROR;g.adb_call=1;
    int rc=kbl_adb_feed(g.adb,bytes,n,used);g.adb_call=0;return rc;
}
static int adb_poll(void*p,unsigned budget){
    if(p!=&g||!g.adb||g.adb_call)return KBL_ERROR;g.adb_call=1;
    int rc=kbl_adb_poll(g.adb,budget);g.adb_call=0;return rc;
}
static int adb_output(void*p,const void**data,size_t*n){return p==&g&&g.adb?kbl_adb_output(g.adb,data,n):KBL_ERROR;}
static int adb_output_done(void*p,size_t n,int status){
    if(p!=&g||!g.adb||g.adb_call)return KBL_ERROR;g.adb_call=1;
    int rc=kbl_adb_output_done(g.adb,n,status);g.adb_call=0;return rc;
}
static int adb_disconnect(void*p){
    if(p!=&g||!g.adb)return 0;if(g.adb_call)return KBL_ERROR;g.adb_call=1;
    int rc=kbl_adb_disconnect(g.adb);g.adb_call=0;return rc;
}
static int adb_activation_fence(void*p){
    if(p!=&g||g.in_request||!live()||!g.adb||!g.adb_armed||!g.adb_ack_ready)return 0;
    if(*(const unsigned char*)(g.usb_facts.parent_base+g.usb_facts.download_state_byte_rva)!=g.usb_facts.command_state_value)return 0;
    int ready=kbl_native_usb_start_ack_ready(&g.usb,!g.adb_usb.composite_started);if(ready)g.adb_armed=0;return ready;
}
static kefi_status adb_fn_event(struct kbl_usbfn_protocol*self,uint32_t*message,size_t*bytes,void*data){
    if(self!=g.usbfn||!g.usbfn_event||!message||!bytes||!data)return KEFI_INVALID;
    size_t capacity=*bytes;kefi_status rc=g.usbfn_event(self,message,bytes,data);
    if(rc==KEFI_SUCCESS&&*bytes<=capacity){int owned=kbl_adb_usb_fn_event(&g.adb_usb,*message,*bytes,data);
        if(owned){*message=0;*bytes=0;if(owned<0){g.adb_error=1;g.adb_phase="control-error";}}}
    return rc;
}
static int adb_control_router(void*p,int enable){
    if(p!=&g||!g.usbfn)return KBL_ERROR;
    if(enable&&g.adb_router)return 0;if(!enable&&!g.adb_router)return 0;
    if((uintptr_t)&g.usbfn->event&7)return KBL_ERROR;
    if(!adb_span_access(&g.usbfn->event,sizeof(g.usbfn->event),KEFI_RP|KEFI_RO))return KBL_ERROR;
    kefi_status(*expected)(struct kbl_usbfn_protocol*,uint32_t*,size_t*,void*)=enable?g.usbfn_event:adb_fn_event;
    kefi_status(*desired)(struct kbl_usbfn_protocol*,uint32_t*,size_t*,void*)=enable?adb_fn_event:g.usbfn_event;
    if(!__atomic_compare_exchange_n(&g.usbfn->event,&expected,desired,0,__ATOMIC_ACQ_REL,__ATOMIC_ACQUIRE))return KBL_ERROR;
    g.adb_router=enable!=0;return 0;
}
static int adb_usb_event(void*p,const struct kbl_usb_event*e){
    if(p!=&g||!g.adb_usb.initialized)return 0;int rc=kbl_adb_usb_event(&g.adb_usb,e);
    if(!rc&&g.adb_usb.composite_started&&e->kind==KBL_USB_EVENT_TRANSFER&&e->bytes==24&&
       (e->data.transfer.endpoint==g.adb_usb.descriptors.adb_in||e->data.transfer.endpoint==g.adb_usb.descriptors.adb_out))
        rc=KBL_ERROR; /* Private endpoint uncertainty must never enter native Fastboot parsing. */
    if(rc<0){g.adb_error=1;g.adb_phase="transport-error";}return rc;
}
static int adb_transport_released(void){return !g.adb_usb.initialized||kbl_adb_usb_can_release(&g.adb_usb);}
static int adb_can_park(void){
    return g.adb&&!g.adb_error&&kbl_adb_healthy(g.adb)&&g.adb_usb.initialized&&
        g.adb_usb.phase==KBL_ADB_USB_ACTIVE&&g.adb_usb.composite_started&&
        !g.adb_usb.fault&&!g.adb_usb.stop_requested&&!g.adb_usb.configuration_uncertain;
}
static int adb_stop_gate(void){
    g.adb_native_stop=1;
    if(g.adb_pending_init){g.adb_pending_init=g.adb_armed=g.adb_ack_ready=0;g.adb_phase="off";}
    if(g.adb){
        g.adb_stop=1;
        if(!g.adb_call){g.adb_call=1;(void)kbl_adb_request_stop(g.adb);g.adb_call=0;}
        /* A stopped responder may still have RX/TX and refusal traffic. That
         * wire state owns no native files/jobs and must not block native STOP. */
        return adb_can_park()&&kbl_adb_native_quiescent(g.adb)?KBL_OK:KBL_AGAIN;
    }
    if(g.adb_pools){adb_retry_pools();if(g.adb_pools)return KBL_AGAIN;}
    return KBL_OK;
}
static int adb_native_stopped(void){
    if(!g.adb)return KBL_OK;
    if(!adb_can_park()||g.adb_call)return KBL_AGAIN;
    g.adb_call=1;int rc=kbl_adb_native_stopped(g.adb);g.adb_call=0;
    if(rc==KBL_OK)g.adb_phase="off-parked";
    return rc;
}
static void adb_silent_info(void*p,const char*s){(void)p;(void)s;}
static void adb_silent_final(void*p,int okay,const char*s){(void)p;(void)okay;(void)s;}
static void adb_activate_after_ack(void);
static int adb_destroy_core(void){
    if(!g.adb)return KBL_OK;if(g.adb_call)return KBL_AGAIN;g.adb_call=1;
    int rc=kbl_adb_destroy(g.adb);g.adb_call=0;if(rc==KBL_OK)g.adb=0;return rc;
}
static void adb_idle(void*p){
    if(p!=&g||g.in_request||g.adb_in_idle)return;g.adb_in_idle=1;
    if(g.adb_pending_init&&live()&&kbl_native_usb_start_ack_ready(&g.usb,0)){
        g.adb_pending_init=0;g.adb_ack_ready=1;adb_activate_after_ack();
    }
    if(g.adb){
        if(g.adb_stop&&!g.adb_call){g.adb_call=1;(void)kbl_adb_request_stop(g.adb);g.adb_call=0;}
        int rc=kbl_adb_usb_step(&g.adb_usb,KBL_QUANTUM_US);
        if(rc<0){g.adb_error=1;g.adb_phase="transport-error";
            if(!g.adb_usb.composite_started&&!g.adb_usb.configuration_uncertain)
                (void)kbl_native_usb_resume_rx(&g.usb);}
        /* Healthy OFF parks only the native environment. Keep bulk endpoints,
         * sequence state, descriptors and the minimal stopped ADB responder.
         * Sticky faults retain the original terminal-DMA teardown path. */
        int park=adb_can_park();
        if(g.adb_stop&&!park&&kbl_adb_stop_ready(g.adb))kbl_adb_usb_request_stop(&g.adb_usb);
        if(g.adb_stop&&!park&&kbl_adb_stop_ready(g.adb)&&kbl_adb_usb_dma_quiet(&g.adb_usb)&&
           (g.adb_usb.phase==KBL_ADB_USB_RESIDENT||g.adb_usb.phase==KBL_ADB_USB_IDLE||
            (g.adb_usb.phase==KBL_ADB_USB_FAILED&&kbl_adb_usb_can_release(&g.adb_usb)))){
            if(adb_destroy_core()==KBL_OK){adb_retry_pools();g.adb_phase=g.adb_usb.composite_started?"off-resident":"off";}
        }else if(!g.adb_stop&&!g.adb_error&&g.adb_usb.phase==KBL_ADB_USB_ACTIVE)g.adb_phase="active";
    }
    if(g.adb_native_stop&&!g.adb_pending_init&&g.session.phase!=KBL_STOPPED&&
       (!g.adb||(adb_can_park()&&kbl_adb_native_quiescent(g.adb)))){
        const struct kbl_output silent={0,adb_silent_info,adb_silent_final};
        static const unsigned char off[]="oem kbloff";(void)kbl_handle(&g.session,&backend,off,sizeof(off)-1,&silent);
    }
    g.adb_in_idle=0;
}
static void adb_prepare_start(void){
    /* Only explicit kbladb reaches this point. A parked responder stays
     * stopped across kblstart, then resumes after this operation's ACK. */
    if(g.adb_pending_init)return;
    if(g.adb&&!kbl_adb_parked(g.adb))return;
    if(g.adb&&!adb_can_park()){g.adb_phase="unavailable-resume";return;}
    if(!g.usb_facts.descriptor_set_rva||!g.usb.initialized||!g.usb.attached){g.adb_phase="unavailable-descriptors";return;}
    if(kbl_native_usb_arm_start_ack(&g.usb)!=KBL_USB_OK){g.adb_phase="unavailable-ack";return;}
    if(!g.adb)g.adb_stop=0;
    g.adb_pending_init=1;g.adb_armed=1;g.adb_ack_ready=0;g.adb_phase="waiting-start-ack";
}
static void adb_activate_after_ack(void){
    /* The exact native START OKAY has completed and was forwarded before any
     * protocol discovery, descriptor read, allocation or USB hook occurs. */
    if(g.adb){
        if(!adb_can_park()||!kbl_adb_parked(g.adb)||g.adb_call){g.adb_phase="unavailable-resume";return;}
        g.adb_call=1;int rc=kbl_adb_resume(g.adb);g.adb_call=0;
        if(rc==KBL_OK){g.adb_stop=0;g.adb_armed=0;g.adb_phase="active";}
        else{g.adb_stop=1;g.adb_phase="unavailable-resume";}
        return; /* No arm, ClearStall, DMA reset, StartEx or USB discovery. */
    }
    if(g.adb_usb.initialized&&kbl_adb_usb_can_release(&g.adb_usb))zero(&g.adb_usb,sizeof(g.adb_usb));
    if(!g.adb_usb.initialized){
        static const struct kefi_guid guid={0x32d2963a,0xfe5d,0x4f30,{0xb6,0x33,0x6e,0x5d,0xc5,0x58,3,0xcc}};
        void*fn=0;if(!g.bs->locate_protocol||g.bs->locate_protocol(&guid,0,&fn)!=KEFI_SUCCESS||
            !adb_span_read(0,fn,sizeof(struct kbl_usbfn_protocol))){g.adb_phase="unavailable-usbfn";return;}
        g.usbfn=fn;g.usbfn_event=g.usbfn->event;
        if(!g.usbfn_event||!adb_span_access(&g.usbfn->event,sizeof(g.usbfn->event),KEFI_RP|KEFI_RO)||
           !adb_span_access((const void*)g.usbfn_event,4,KEFI_RP|KEFI_XP)){
            g.adb_phase="unavailable-router";return;}
        struct kbl_adb_usb_environment e={&g,g.usb.original,g.usbfn,adb_activation_fence,adb_control_router,
            adb_feed,adb_poll,adb_output,adb_output_done,adb_disconnect};
        const struct kbl_usb_descriptor_set*d=(void*)(g.usb_facts.parent_base+g.usb_facts.descriptor_set_rva);
        if(kbl_adb_usb_init(&g.adb_usb,&e,d,0,adb_span_read)){g.adb_phase="unavailable-descriptors";return;}
    }
    const struct kbl_adb_config c={.opaque=&g,.allocate=adb_allocate,.release=adb_release,.current_el=current_el,
        .submit=adb_submit,.poll_job=adb_poll_job,.files_begin=adb_files_begin,.files_end=adb_files_end,
        .stop_request=adb_stop_request,.explicit_usb_debug=1,
        .session_create=adb_session_create,.session_close=adb_session_close,
        .session_submit=adb_session_submit,.session_poll_job=adb_session_poll_job,
        .session_files_begin=adb_session_files_begin,.session_files_end=adb_session_files_end};
    g.adb_stop=0;g.adb_error=0;
    if(kbl_adb_create(&c,&g.adb)!=KBL_OK){g.adb_phase="unavailable-memory";return;}
    if(kbl_adb_usb_arm(&g.adb_usb)){
        (void)adb_destroy_core();adb_retry_pools();g.adb_phase="unavailable-arm";return;}
    g.adb_phase="waiting-usb-fence";
}
static void adb_diagnostics(void){
    /* Four bounded RAM-only status lines. No USB/protocol probing, job poll,
     * allocation or controller command is performed by a status request. */
    char line[57],*p=put(line,"KBL1 adb_usb=");
    const unsigned values[]={g.adb_usb.phase,g.adb_usb.composite_started,g.adb_router,g.adb_usb.connected,
        g.adb_usb.fault,g.adb_usb.rx_pending,g.adb_usb.tx_pending,g.adb_usb.control_pending,g.adb_usb.stop_requested};
    for(unsigned i=0;i<sizeof(values)/sizeof(values[0]);++i){if(i)*p++=',';p=decimal(p,values[i]);}*p=0;output_info(0,line);
    p=put(line,"KBL1 adb_flags=");
    const unsigned flags[]={g.adb_error,g.adb_stop,(unsigned)kbl_adb_stop_requested(g.adb),(unsigned)kbl_adb_stop_ready(g.adb),
        g.adb_usb.rx_held,g.adb_usb.disconnected,g.adb_usb.stop_stalled,g.adb_usb.configuration_uncertain};
    for(unsigned i=0;i<sizeof(flags)/sizeof(flags[0]);++i){if(i)*p++=',';p=decimal(p,flags[i]);}*p=0;output_info(0,line);
    p=put(line,"KBL1 adb_fw=");p=hex64(p,g.adb_usb.last_status);*p++=',';p=hex64(p,g.usb.first_event_error);*p=0;output_info(0,line);
    p=put(line,"KBL1 usb_errors=");p=decimal(p,g.usb.provider_errors);*p=0;output_info(0,line);
}
