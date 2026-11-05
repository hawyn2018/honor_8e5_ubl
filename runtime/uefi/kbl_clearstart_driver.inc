/* SPDX-License-Identifier: MIT -- explicit two-phase efi -cs only. */
static int clearstart_unload(kefi_handle *handle){
    if(!*handle)return 0;
    kefi_status s=((kefi_status(*)(kefi_handle))g.bs->unload_image)(*handle);
    if(s){
        void *loaded=0;kefi_status probe=g.bs->handle_protocol(*handle,&loaded_guid,&loaded);
        if(loaded||(probe!=KEFI_INVALID&&probe!=KEFI_UNSUPPORTED&&probe!=KEFI_ERROR(14))){g.cs.cleanup=s;return -KBL_EIO;}
    }
    *handle=0;return 0;
}
static int clearstart_dispose(void){
    if(g.cs.phase==CS_START)return -KBL_EBUSY;
    if(g.cs.context){
        /* The separate application may have returned with a failed release.
         * Replace its local callback before its code can be unloaded. */
        g.cs.context->target.current_el=current_el;g.cs.context->target.opaque=&g;
        if(kbl_efi_cleanup(&g.cs.context->target)){g.cs.cleanup=g.cs.context->target.last_cleanup;return -KBL_EIO;}
    }
    if(clearstart_unload(&g.cs.helper))return -KBL_EIO;
    if(g.cs.exit_data){g.cs.cleanup=g.bs->free_pool(g.cs.exit_data);if(g.cs.cleanup)return -KBL_EIO;g.cs.exit_data=0;}
    if(g.cs.context){g.cs.cleanup=g.bs->free_pool(g.cs.context);if(g.cs.cleanup)return -KBL_EIO;g.cs.context=0;}
    g.cs.cleanup=0;return 0;
}
static void clearstart_resume_rx(void){
    if(!g.cs.rx_stopped)return;
    if(g.usb.initialized&&!g.usb.native_rx_pending&&g.usb.native_rx_buffer){
        if(g.usb.attached)g.cs.rx_resume=kbl_native_usb_resume_rx(&g.usb)?KEFI_ERROR(7):0;
        else g.cs.rx_resume=g.usb.original->send(1,g.usb.native_rx_bytes,g.usb.native_rx_buffer);
        if(g.cs.rx_resume){g.cs.phase=CS_FAILED;g.cs.error="Fastboot RX resume error";}
    }
    g.cs.rx_stopped=0;
}
static void clearstart_fail(const char *reason,kefi_status status){
    g.cs.error=reason;g.cs.status=status;g.cs.phase=CS_FAILED;g.cs.active=0;
    (void)clearstart_dispose();
    /* No target execution on a cleanup failure. Driver/parent/Fastboot code
     * stays mapped, with the exact pending ownership visible in status. */
    clearstart_resume_rx();
}
static int native_efi_clearstart(void*opaque,const void*image,size_t bytes,
                               const uint16_t*options,size_t option_bytes,
                               struct kbl_efi_run_result*result){
    if(opaque!=&g||!live()||!result||!((g.in_request&&g.current_operation==KBL_S_POLL)||g.adb_call))return -KBL_EACCES;
    if(g.cs.active||g.transport_hold||g.pending_work||!kbl_efi_can_unload(&efi_launcher))return -KBL_EBUSY;
    if(clearstart_dispose())return -KBL_EBUSY;
    zero(&g.cs,sizeof(g.cs));g.cs.phase=CS_QUEUED;
    if(!g.bs->load_image||!g.bs->start_image||!g.bs->unload_image){clearstart_fail("firmware services",KEFI_UNSUPPORTED);return -KBL_ENOSYS;}
    kefi_status s=g.bs->allocate_pool(4,sizeof(*g.cs.context),(void**)&g.cs.context);
    if(g.cs.context)zero(g.cs.context,sizeof(*g.cs.context));
    if(s||!g.cs.context){result->status=s?s:KEFI_OUT_OF_RESOURCES;clearstart_fail("context allocation",result->status);return -KBL_ENOMEM;}
    struct kbl_handoff *h=g.cs.context;zero(h,sizeof(*h));h->magic=KBL_HANDOFF_MAGIC;
    kbl_efi_launcher_init(&h->target,g.image,g.st,&g,current_el,current_el(&g));
    int rc=kbl_efi_prepare(&h->target,image,bytes,options,option_bytes,result);
    if(rc){g.cs.result=*result;clearstart_fail("target preparation",result->status);return rc;}
    s=((kefi_status(*)(unsigned char,kefi_handle,void*,void*,size_t,kefi_handle*))g.bs->load_image)
        (0,g.image,0,(void*)kbl_handoff_image,kbl_handoff_image_bytes,&g.cs.helper);
    result->status=s;
    if(s||!g.cs.helper){clearstart_fail("handoff LoadImage",s?s:KEFI_ERROR(1));return -KBL_EIO;}
    struct kefi_loaded_image *li=0;s=g.bs->handle_protocol(g.cs.helper,&loaded_guid,(void**)&li);
    if(s||!li||li->code_type!=1||li->system_table!=g.st){clearstart_fail("handoff LoadedImage",s?s:KEFI_UNSUPPORTED);return -KBL_EIO;}
    li->options=h;li->options_bytes=sizeof(*h);
    if(li->options!=h||li->options_bytes!=sizeof(*h)){clearstart_fail("handoff options",KEFI_ACCESS_DENIED);return -KBL_EIO;}
    g.cs.active=1;g.cs.from_adb=g.adb_call!=0;
    return 0; /* All target/options ownership is outside native allocations. */
}
static int native_clear_errorcount(void *opaque){
    if(opaque!=&g||!live()||g.cs.active||!g.loaded||!g.loaded->parent)return -KBL_EACCES;
    struct kefi_loaded_image *parent=0;
    kefi_status s=g.bs->handle_protocol(g.loaded->parent,&loaded_guid,(void**)&parent);
    zero(&g.errorcount,sizeof(g.errorcount));g.errorcount_called=1;
    if(s||!parent||parent->system_table!=g.st||!parent->image_base||!parent->image_bytes||parent->image_bytes>SIZE_MAX){
        g.errorcount_status=s?s:KEFI_INVALID;return -KBL_EIO;
    }
    g.errorcount_status=kbl_errorcount_clear(g.st,parent->image_base,(size_t)parent->image_bytes,&g.errorcount);
    return g.errorcount_status?-KBL_EIO:0;
}
static void clearstart_diagnostics(void){
    static const char *phases[]={"idle","queued","native-stop","adb-release","usb-release","starting","returned","failed"};
    char line[57],*p=put(line,"KBL1 clearstart=");p=put(p,phases[g.cs.phase<=CS_FAILED?g.cs.phase:CS_FAILED]);*p=0;output_info(0,line);
    if(g.cs.phase){
        p=put(line,"KBL1 cs_status=");p=hex64(p,g.cs.status);*p=0;output_info(0,line);
        p=put(line,"KBL1 cs_cleanup=");p=hex64(p,g.cs.cleanup);*p=0;output_info(0,line);
        if(g.cs.error){p=put(line,"KBL1 cs_error=");p=put(p,g.cs.error);*p=0;output_info(0,line);}
        p=put(line,"KBL1 cs_rx_resume=");p=hex64(p,g.cs.rx_resume);*p=0;output_info(0,line);
        p=put(line,"KBL1 cs_stage=");p=decimal(p,g.cs.result.stage);*p=0;output_info(0,line);
        output_info(0,"KBL1 cs_resident=parent,driver");
    }
    if(g.errorcount_called){
        p=put(line,"KBL1 counter_phase=");p=put(p,kbl_errorcount_phase_name(g.errorcount.phase));*p=0;output_info(0,line);
        p=put(line,"KBL1 counter_status=");p=hex64(p,g.errorcount_status);*p=0;output_info(0,line);
        const char*keys[]={"get","set","verify","cache_status"};
        kefi_status values[]={g.errorcount.get_status,g.errorcount.set_status,g.errorcount.verify_status,g.errorcount.cache_status};
        for(unsigned i=0;i<4;++i){p=put(line,"KBL1 counter_");p=put(p,keys[i]);*p++='=';p=hex64(p,values[i]);*p=0;output_info(0,line);}
        output_info(0,g.errorcount.verified?"KBL1 counter_verified=1":"KBL1 counter_verified=0");
        output_info(0,g.errorcount.write_attempted?"KBL1 counter_write_attempted=1":"KBL1 counter_write_attempted=0");
        p=put(line,"KBL1 counter_before=");p=g.errorcount.before_known?hex64(p,g.errorcount.before):put(p,"unknown");*p=0;output_info(0,line);
        p=put(line,"KBL1 counter_after=");p=g.errorcount.after_known?hex64(p,g.errorcount.after):put(p,"unknown");*p=0;output_info(0,line);
        output_info(0,g.errorcount.cache_found?(g.errorcount.cache_synced?"KBL1 counter_cache=synced":"KBL1 counter_cache=unchanged"):"KBL1 counter_cache=absent");
    }
}
static void clearstart_step(int transport_quiet){
    if(!g.cs.active||g.in_request||g.adb_call||g.adb_in_idle||g.cs.phase==CS_START)return;
    if(g.cs.phase==CS_QUEUED){
        struct kbl_native_stats st;
        if(!g.kernel.native||kbl_native_get_stats(g.kernel.native,&st)){clearstart_fail("native state",KEFI_ERROR(7));return;}
        /* The preparing command must finish, output must drain, and every
         * native poll/ADB callback must have unwound before any stop. */
        if(st.in_job||st.job_active||g.session.phase==KBL_BUSY||(!g.cs.from_adb&&!g.cs.reply_drained))return;
        if(g.adb){const void *data=0;size_t n=0;if(g.adb_usb.tx_pending||
           (kbl_adb_output(g.adb,&data,&n)==KBL_OK&&n))return;}
        g.cs.phase=CS_NATIVE;g.cs.begun=g.counter_frequency?usb_now(&g):0;
    }
    if(++g.cs.steps==65536||(g.cs.begun&&usb_now(&g)-g.cs.begun>UINT64_C(10000000))){clearstart_fail("cleanup timeout",KEFI_ERROR(18));return;}
    if(!transport_quiet||g.transport_hold||g.pending_work)return;
    if(g.cs.phase==CS_NATIVE){
        clear_reply();
        const struct kbl_output silent={0,adb_silent_info,adb_silent_final};
        static const unsigned char off[]="oem kbloff";
        (void)kbl_handle(&g.session,&backend,off,sizeof(off)-1,&silent);
        if(g.session.phase!=KBL_STOPPED||g.kernel.native)return;
        if(g.pools||g.pages||g.ownership_error){clearstart_fail("native ownership",KEFI_ACCESS_DENIED);return;}
        g.cs.phase=CS_ADB;
    }
    if(g.cs.phase==CS_ADB){
        /* Parent RX must also be terminal before StartEx restores descriptors.
         * Keep the observer installed to consume exact abort completions. */
        if(g.usb.initialized&&g.usb.attached&&!g.cs.rx_stopped){
            int rc=kbl_native_usb_clearstart_quiesce(&g.usb);
            if(rc==KBL_USB_RETAINED)return;
            if(rc<0){clearstart_fail("USB pre-release",KEFI_ACCESS_DENIED);return;}
            g.cs.rx_stopped=1;
        }
        if(g.adb_usb.initialized&&!kbl_adb_usb_can_release(&g.adb_usb)){
            kbl_adb_usb_request_stop(&g.adb_usb);
            int rc=kbl_adb_usb_step(&g.adb_usb,KBL_QUANTUM_US);
            if(rc<0){clearstart_fail("ADB stop",g.adb_usb.last_status);return;}
            if(g.adb_usb.phase!=KBL_ADB_USB_RESIDENT&&g.adb_usb.phase!=KBL_ADB_USB_IDLE)return;
            rc=kbl_adb_usb_clearstart_release(&g.adb_usb);
            if(rc<0){clearstart_fail("ADB release",g.adb_usb.last_status);return;}if(rc)return;
        }
        if(g.adb&&adb_destroy_core()!=KBL_OK)return;
        adb_retry_pools();if(g.adb_pools)return;
        g.adb_stop=g.adb_native_stop=g.adb_armed=g.adb_pending_init=g.adb_ack_ready=0;g.adb_phase="off";
        g.cs.phase=CS_USB;
    }
    if(g.cs.phase==CS_USB){
        if(g.usb.initialized&&g.usb.attached){
            int rc=kbl_native_usb_clearstart_quiesce(&g.usb);
            if(rc==KBL_USB_RETAINED)return;
            if(rc<0){clearstart_fail("USB quiesce",KEFI_ACCESS_DENIED);return;}if(rc)return;
            g.cs.rx_stopped=1;
            if(kbl_native_usb_stop_observing(&g.usb)!=KBL_USB_OK)return;
        }
        if(g.usb.initialized&&!kbl_native_usb_can_release(&g.usb))return;
        g.cs.phase=CS_START;
        size_t exit_bytes=0;uint16_t *exit_data=0;
        /* Independent PE owns target execution; no native job stack remains.
         * Never ExitBootServices or unload our own active driver/parent. */
        g.cs.status=((kefi_status(*)(kefi_handle,size_t*,uint16_t**))g.bs->start_image)(g.cs.helper,&exit_bytes,&exit_data);
        g.cs.exit_data=exit_data;g.cs.phase=CS_RETURN;g.cs.active=0;
        g.cs.result=g.cs.context->result;
        if(!g.cs.context->entered){g.cs.phase=CS_FAILED;g.cs.error="handoff did not enter";}
        else if(g.cs.context->outcome||g.cs.result.status>>63){g.cs.phase=CS_FAILED;g.cs.error="target returned error";}
        if(clearstart_dispose()){g.cs.phase=CS_FAILED;g.cs.error="return cleanup pending";}
        clearstart_resume_rx();
    }
}
