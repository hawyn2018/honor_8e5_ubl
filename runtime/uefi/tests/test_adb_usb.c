/* SPDX-License-Identifier: MIT -- controller/DMA callbacks are explicit models. */
#include "../kbl_adb_usb.h"
#if defined(_WIN32)
#define FIXTURE_API __declspec(dllexport)
#else
#define FIXTURE_API
#endif
static unsigned checks;
#define CHECK(x) do{++checks;if(!(x))return __LINE__;}while(0)
static void z(void*p,size_t n){unsigned char*d=p;while(n--)*d++=0;}
static void cp(void*p,const void*q,size_t n){unsigned char*d=p;const unsigned char*s=q;while(n--)*d++=*s++;}
#if !defined(_WIN32)
/* Freestanding ARM64 aggregate copies may lower to this libc operation. */
void *memcpy(void*p,const void*q,size_t n){cp(p,q,n);return p;}
#endif
static int eq(const void*p,const void*q,size_t n){const unsigned char*a=p,*b=q;while(n--)if(*a++!=*b++)return 0;return 1;}
static unsigned r16(const void*p){const unsigned char*b=p;return b[0]+256u*b[1];}
static unsigned char dev[18],ssdev[18],cfg[100],sscfg[120],lang[4],label[6],qual[10];
static void *cfgp[3],*ssp[3],*strp[2];static struct kbl_usb_descriptor_set set;
static unsigned char multiple_cfg[3][100],multiple_sscfg[3][120];
static struct kbl_adb_usb_descriptors built;
static struct kbl_adb_usb transport;
static unsigned allocations,frees,starts,stops,hooks,sends,aborts,stalls,fn_sends,fn_aborts,feeds,polls,done,disconnects;
static unsigned fence,fail_start,fail_rollback,fail_send,fail_alloc,feed_limit,output_bytes,output_at,span_fail;
static int last_output_status;
static size_t max_transfer;static unsigned max_queries;
static unsigned disconnect_busy,stall_reject_active,active_stall_rejections,fail_stall_ep,stall_out_attempts,stall_in_attempts;
static unsigned char dma[3][KBL_ADB_USB_DMA_BYTES],out[70000];static unsigned used[3];
static unsigned last_ep,last_fn_direction;static size_t last_fn_bytes;static struct kbl_usb_descriptor_set*last_set;
static struct kbl_usb_device_protocol device;static struct kbl_usbfn_protocol usbfn;
static struct kbl_adb*core;
static unsigned core_periodic_stop;
static void fixture(void){
    z(dev,sizeof(dev));dev[0]=18;dev[1]=1;dev[3]=2;dev[7]=64;dev[8]=0x12;dev[9]=0x34;dev[10]=0x56;dev[11]=0x78;dev[14]=dev[15]=dev[16]=1;dev[17]=1;
    cp(ssdev,dev,18);ssdev[3]=3;ssdev[7]=9;
    unsigned char c[]={9,2,32,0,1,1,0,0x80,50,9,4,0,0,2,0xff,0x42,3,1,7,5,1,2,0,2,0,7,5,0x81,2,0,2,0};cp(cfg,c,32);
    unsigned char s[]={9,2,44,0,1,1,0,0x80,50,9,4,0,0,2,0xff,0x42,3,1,7,5,1,2,0,4,0,6,48,0,0,0,0,7,5,0x81,2,0,4,0,6,48,0,0,0,0};cp(sscfg,s,44);
    unsigned char l[]={4,3,9,4},t[]={6,3,'F',0,'B',0},q[]={10,6,0,2,0,0,0,64,1,0};cp(lang,l,4);cp(label,t,6);cp(qual,q,10);
    cfgp[0]=cfg;ssp[0]=sscfg;strp[0]=lang;strp[1]=label;set=(struct kbl_usb_descriptor_set){dev,cfgp,ssdev,ssp,qual,0,2,strp};span_fail=0;
}
static int span(void*o,const void*p,size_t n){(void)o;if(span_fail)return 0;
    const void*ptrs[]={&set,dev,ssdev,cfg,sscfg,lang,label,qual,cfgp,ssp,strp,multiple_cfg,multiple_sscfg};
    const size_t sizes[]={sizeof(set),sizeof(dev),sizeof(ssdev),sizeof(cfg),sizeof(sscfg),sizeof(lang),sizeof(label),sizeof(qual),sizeof(cfgp),sizeof(ssp),sizeof(strp),sizeof(multiple_cfg),sizeof(multiple_sscfg)};
    for(unsigned i=0;i<sizeof(ptrs)/sizeof(ptrs[0]);++i)if((uintptr_t)p>=(uintptr_t)ptrs[i]&&(uintptr_t)p-(uintptr_t)ptrs[i]<=sizes[i]&&n<=sizes[i]-((uintptr_t)p-(uintptr_t)ptrs[i]))return 1;
    return 0;
}
static kefi_status allocate(size_t n,void**p){++allocations;if(fail_alloc||n>KBL_ADB_USB_DMA_BYTES)return KEFI_OUT_OF_RESOURCES;for(unsigned i=0;i<3;++i)if(!used[i]){used[i]=1;*p=dma[i];return 0;}return KEFI_OUT_OF_RESOURCES;}
static kefi_status release(void*p){++frees;for(unsigned i=0;i<3;++i)if(p==dma[i]&&used[i]){used[i]=0;return 0;}return KEFI_INVALID;}
static kefi_status start(struct kbl_usb_descriptor_set*d){++starts;last_set=d;if(starts==1&&fail_start)return KEFI_INVALID;if(starts==2&&fail_rollback)return KEFI_INVALID;return 0;}
static kefi_status stop(void){++stops;return 0;}
static kefi_status send(uint8_t ep,size_t n,void*p){(void)n;(void)p;++sends;last_ep=ep;return fail_send?KEFI_INVALID:0;}
static kefi_status abort_ep(uint8_t ep){(void)ep;++aborts;return 0;}
static kefi_status stall_ep(uint8_t ep,uint8_t v){if(ep==1||ep==0x81)return KEFI_INVALID;++stalls;
    if(v){if(ep==2)++stall_out_attempts;else if(ep==0x82)++stall_in_attempts;}
    if(v&&ep==transport.descriptors.adb_out&&transport.rx_pending&&stall_reject_active){++active_stall_rejections;return KEFI_ERROR(6);}
    return fail_stall_ep==ep?KEFI_ERROR(7):0;}
static kefi_status fn_transfer(struct kbl_usbfn_protocol*f,uint8_t ep,unsigned dir,size_t*n,void*p){(void)f;(void)p;if(ep)return KEFI_INVALID;++fn_sends;last_fn_direction=dir;last_fn_bytes=*n;return 0;}
static kefi_status fn_abort(struct kbl_usbfn_protocol*f,uint8_t ep,unsigned dir){(void)f;(void)ep;(void)dir;++fn_aborts;return 0;}
static kefi_status fn_max(struct kbl_usbfn_protocol*f,size_t*n){(void)f;++max_queries;*n=max_transfer;return 0;}
static int gate(void*o){(void)o;return fence;}
static int hook(void*o,int on){(void)o;if(on)++hooks;else --hooks;return 0;}
static int feed(void*o,const void*p,size_t n,size_t*usedn){(void)o;(void)p;++feeds;if(core)return kbl_adb_feed(core,p,n,usedn);*usedn=n<feed_limit?n:feed_limit;return 0;}
static int poll(void*o,unsigned b){(void)o;(void)b;++polls;if(core){if(core_periodic_stop&&kbl_adb_stop_requested(core))(void)kbl_adb_request_stop(core);return kbl_adb_poll(core,b);}return 0;}
static int output(void*o,const void**p,size_t*n){(void)o;if(core)return kbl_adb_output(core,p,n);*n=output_bytes-output_at;*p=*n?out+output_at:0;return 0;}
static int output_done(void*o,size_t n,int status){(void)o;++done;last_output_status=status;if(core)return kbl_adb_output_done(core,n,status);output_at+=(unsigned)n;return 0;}
static void model_frame(unsigned n){output_bytes=24+n;output_at=0;z(out,24);cp(out,"WRTE",4);for(unsigned i=0;i<4;++i)out[12+i]=(unsigned char)(n>>(i*8));}
static int disconnect(void*o){(void)o;++disconnects;if(disconnect_busy){--disconnect_busy;return 1;}return core?kbl_adb_disconnect(core):0;}
static int setup(void){fixture();z(&transport,sizeof(transport));z(used,sizeof(used));
    allocations=frees=starts=stops=hooks=sends=aborts=stalls=fn_sends=fn_aborts=feeds=polls=done=disconnects=0;
    fence=fail_start=fail_rollback=fail_send=fail_alloc=output_bytes=output_at=0;feed_limit=65536;last_set=0;
    last_output_status=0;
    max_transfer=65536;max_queries=0;
    disconnect_busy=active_stall_rejections=fail_stall_ep=stall_out_attempts=stall_in_attempts=0;stall_reject_active=1;
    z(&device,sizeof(device));device.revision=0x10000001;device.start_ex=(void*)start;device.stop=(void*)stop;device.send=send;device.allocate=allocate;device.free=release;device.abort=abort_ep;device.stall=(void*)stall_ep;
    z(&usbfn,sizeof(usbfn));usbfn.transfer=fn_transfer;usbfn.abort_transfer=fn_abort;usbfn.max_transfer=fn_max;
    struct kbl_adb_usb_environment e={0,&device,&usbfn,gate,hook,feed,poll,output,output_done,disconnect};
    return kbl_adb_usb_init(&transport,&e,&set,0,span);
}
static int connect_event(unsigned state){struct kbl_usb_event e={0};e.kind=KBL_USB_EVENT_STATE;e.bytes=4;e.data.state=state;return kbl_adb_usb_event(&transport,&e);}
static int event(unsigned ep,void*p,size_t n,unsigned status){struct kbl_usb_event e={0};e.kind=KBL_USB_EVENT_TRANSFER;e.bytes=24;e.data.transfer=(struct kbl_usb_outcome){status,(uint8_t)ep,{0,0,0},n,p};return kbl_adb_usb_event(&transport,&e);}
static int fn_event(unsigned dir,unsigned status,size_t n){struct kbl_usbfn_result r={n,status,0,dir,transport.control};return kbl_adb_usb_fn_event(&transport,dir?3:2,sizeof(r),&r);}
static int activate(void){if(kbl_adb_usb_arm(&transport))return 1;fence=1;if(kbl_adb_usb_step(&transport,10)<0)return 1;connect_event(KBL_USB_CONNECTED);return kbl_adb_usb_step(&transport,10)<0;}
static int wide_equal(const unsigned char*p,const char*s){while(*s){if(*p++!=(unsigned char)*s++||*p++)return 0;}return !p[0]&&!p[1];}
static int os2_valid(const unsigned char*p,size_t n,unsigned configurations){
    if(n!=10+328*configurations||r16(p)!=10||r16(p+2)||!eq(p+4,"\0\0\3\6",4)||r16(p+8)!=n)return 0;
    size_t at=10;for(unsigned c=0;c<configurations;++c){if(r16(p+at)!=8||r16(p+at+2)!=1||p[at+4]!=c||p[at+5]||r16(p+at+6)!=328)return 0;at+=8;
        for(unsigned fn=0;fn<2;++fn){if(r16(p+at)!=8||r16(p+at+2)!=2||p[at+4]!=fn||p[at+5]||r16(p+at+6)!=160)return 0;at+=8;
            if(r16(p+at)!=20||r16(p+at+2)!=3||!eq(p+at+4,"WINUSB\0\0",8))return 0;for(unsigned i=12;i<20;++i)if(p[at+i])return 0;at+=20;
            if(r16(p+at)!=132||r16(p+at+2)!=4||r16(p+at+4)!=7||r16(p+at+6)!=42||!wide_equal(p+at+8,"DeviceInterfaceGUIDs")||r16(p+at+50)!=80||
               !wide_equal(p+at+52,"{F72FE0D4-CBCB-407D-8814-9ED673D0DD6B}")||p[at+130]||p[at+131])return 0;
            at+=132;
        }
    }return at==n;
}
FIXTURE_API unsigned kbl_adb_usb_test_checks(void){return checks;}
FIXTURE_API void*kbl_adb_usb_test_config(unsigned ss){return ss?built.ss_config[0]:built.config[0];}
FIXTURE_API void*kbl_adb_usb_test_device(unsigned ss){return ss?built.ss_device:built.device;}
FIXTURE_API void*kbl_adb_usb_test_descriptors(void){return &built.composite;}
FIXTURE_API int kbl_adb_usb_test_run(void){checks=0;fixture();
    const uint64_t revisions[]={0,UINT64_C(0x10000002),UINT64_MAX};
    for(unsigned i=0;i<sizeof(revisions)/sizeof(revisions[0]);++i){
        CHECK(setup()==0);struct kbl_adb_usb_environment e=transport.env;
        z(&transport,sizeof(transport));device.revision=revisions[i];
        CHECK(kbl_adb_usb_init(&transport,&e,&set,0,span)==0&&!starts&&!sends);
        z(&transport,sizeof(transport));device.send=0;
        CHECK(kbl_adb_usb_init(&transport,&e,&set,0,span)<0&&!starts&&!sends);
    }
    CHECK(kbl_adb_usb_descriptors(&built,&set,0,span)==0);CHECK(built.adb_out==2&&built.adb_in==0x82&&built.adb_interface==1);
    CHECK(r16(built.config[0]+2)==55&&r16(built.ss_config[0]+2)==79&&built.config[0][4]==2);
    CHECK(eq(built.old_config[0],cfg,32));CHECK(eq(built.config[0]+9,cfg+9,23));
    CHECK(eq(built.ss_config[0]+9,sscfg+9,35));CHECK(eq(built.device+8,dev+8,2)&&eq(built.device+14,dev+14,4));CHECK(r16(built.bos+2)==33&&r16(built.bos+29)==338);
    CHECK(r16(built.device+10)==KBL_ADB_USB_DEBUG_PID&&r16(built.ss_device+10)==KBL_ADB_USB_DEBUG_PID&&r16(built.device+12)==KBL_ADB_USB_DEBUG_BCD_DEVICE&&r16(built.ss_device+12)==KBL_ADB_USB_DEBUG_BCD_DEVICE);
    CHECK(eq(built.original.device,dev,18)&&eq(built.original.ss_device,ssdev,18)&&r16(dev+10)==0x7856&&r16(ssdev+10)==0x7856);
    CHECK(built.device[16]==dev[16]&&eq(built.original.strings[1],label,6)&&eq(built.composite.strings[1],label,6));
    CHECK(built.original.device!=set.device&&built.original.configs[0]!=set.configs[0]&&eq(built.original.configs[0],cfg,32));
    unsigned char extra[]={9,4,1,0,2,0xfe,0,0,0,7,5,2,2,0,2,0,7,5,0x82,2,0,2,0};cp(cfg+32,extra,23);cfg[2]=55;cfg[4]=2;set.ss_device=0;set.ss_configs=0;
    CHECK(kbl_adb_usb_descriptors(&built,&set,0,span)==0&&built.adb_out==3&&built.adb_interface==2);
    fixture();cfg[2]=31;CHECK(kbl_adb_usb_descriptors(&built,&set,0,span)<0);fixture();span_fail=1;CHECK(kbl_adb_usb_descriptors(&built,&set,0,span)<0);fixture();
    CHECK(setup()==0);CHECK(!allocations&&!starts&&!hooks&&!max_queries);CHECK(kbl_adb_usb_arm(&transport)==0);CHECK(kbl_adb_usb_step(&transport,5)>0);CHECK(!allocations&&!starts&&!hooks&&!max_queries);
    kbl_adb_usb_request_stop(&transport);CHECK(kbl_adb_usb_step(&transport,5)==0&&!starts&&!allocations);CHECK(transport.phase==KBL_ADB_USB_IDLE);
    CHECK(activate()==0);CHECK(starts==1&&allocations==3&&hooks==1&&transport.rx_pending);CHECK(last_set==&transport.descriptors.composite);
    CHECK(event(1,transport.rx,1,KBL_USB_COMPLETE)==0);CHECK(event(2,out,1,KBL_USB_COMPLETE)==0);CHECK(transport.rx_pending);
    CHECK(event(2,transport.rx,9,KBL_USB_ACTIVE)==1&&transport.rx_pending);CHECK(event(2,transport.rx,9,KBL_USB_COMPLETE)==1&&!transport.rx_pending);
    feed_limit=4;CHECK(kbl_adb_usb_step(&transport,5)==0&&transport.rx_held&&transport.rx_at==4);CHECK(kbl_adb_usb_step(&transport,5)==0&&transport.rx_at==8);
    CHECK(kbl_adb_usb_step(&transport,5)==0&&!transport.rx_held&&transport.rx_pending);
    model_frame(65536);CHECK(kbl_adb_usb_step(&transport,5)==0&&transport.tx_pending&&transport.tx_bytes==24&&last_ep==0x82);
    CHECK(event(0x82,transport.tx,24,KBL_USB_COMPLETE)==1&&done==1&&output_at==24);
    CHECK(kbl_adb_usb_step(&transport,5)==0&&transport.tx_bytes==65536);kbl_adb_usb_request_stop(&transport);CHECK(kbl_adb_usb_step(&transport,5)>0&&!aborts&&!frees);
    CHECK(event(0x82,transport.tx,65536,KBL_USB_COMPLETE)==1&&output_at==65560);CHECK(kbl_adb_usb_step(&transport,5)>0&&aborts==1&&!frees&&transport.rx_pending&&!stalls&&!active_stall_rejections);
    CHECK(kbl_adb_usb_step(&transport,5)>0&&aborts==1&&!stalls);CHECK(event(2,transport.rx,0,KBL_USB_ACTIVE)==1&&transport.rx_pending);
    CHECK(kbl_adb_usb_step(&transport,5)>0&&!stalls);CHECK(event(2,transport.rx,0,KBL_USB_CANCELLED)==1);disconnect_busy=2;
    CHECK(kbl_adb_usb_step(&transport,5)>0&&stalls==2&&transport.stop_stalled==3&&!active_stall_rejections);
    CHECK(kbl_adb_usb_step(&transport,5)>0&&stalls==2&&stall_out_attempts==1&&stall_in_attempts==1);
    CHECK(kbl_adb_usb_step(&transport,5)==0);CHECK(transport.phase==KBL_ADB_USB_RESIDENT&&starts==1&&stops==0&&hooks==1&&frees==2);
    CHECK(transport.control&&!transport.rx&&!transport.tx&&!kbl_adb_usb_can_release(&transport));unsigned keep_stalls=stalls;
    kbl_adb_usb_request_stop(&transport);CHECK(transport.phase==KBL_ADB_USB_RESIDENT);
    CHECK(kbl_adb_usb_step(&transport,5)==0&&stalls==keep_stalls);CHECK(kbl_adb_usb_arm(&transport)==0);CHECK(kbl_adb_usb_step(&transport,5)==0&&starts==1&&transport.rx_pending);
    unsigned char q[8]={0x80,6,0xee,3,0,0,18,0};CHECK(kbl_adb_usb_fn_event(&transport,1,8,q)==1&&last_fn_bytes==18&&last_fn_direction==1&&fn_sends==1);
    CHECK(((unsigned char*)transport.control)[16]==0x20);CHECK(fn_event(1,1,18)==1&&transport.control_pending&&last_fn_bytes==0&&last_fn_direction==0);
    CHECK(fn_event(0,1,0)==1&&!transport.control_pending);
    unsigned char setup_union[32];for(unsigned i=0;i<sizeof(setup_union);++i)setup_union[i]=0xa5;cp(setup_union,q,8);
    CHECK(kbl_adb_usb_fn_event(&transport,1,7,setup_union)==0&&!transport.control_pending);
    CHECK(kbl_adb_usb_fn_event(&transport,1,32,setup_union)==1&&last_fn_bytes==18&&transport.control_pending);
    const unsigned char os_string[18]={18,3,'M',0,'S',0,'F',0,'T',0,'1',0,'0',0,'0',0,0x20,0};
    CHECK(eq(transport.control,os_string,18));CHECK(fn_event(1,1,18)==1&&last_fn_direction==0&&last_fn_bytes==0);
    CHECK(fn_event(0,1,0)==1&&!transport.control_pending);
    unsigned char os2[8]={0xc0,0x20,0,0,7,0,0xff,0xff};CHECK(kbl_adb_usb_fn_event(&transport,1,8,os2)==1&&last_fn_bytes==338);CHECK(r16((unsigned char*)transport.control+8)==338);
    CHECK(os2_valid(transport.control,last_fn_bytes,1)&&transport.descriptors.config[0][5]==1&&transport.descriptors.ss_config[0][5]==1);
    CHECK(fn_event(1,1,338)==1&&fn_event(0,1,0)==1);
    unsigned char compat[8]={0xc0,0x20,0,0,4,0,64,0};CHECK(kbl_adb_usb_fn_event(&transport,1,8,compat)==1&&last_fn_bytes==64);
    unsigned char foreign[8]={0x80,6,0,1,0,0,18,0};CHECK(kbl_adb_usb_fn_event(&transport,1,8,foreign)==0&&fn_aborts==1&&transport.control_pending);
    CHECK(fn_event(1,2,0)==1&&!transport.control_pending);CHECK(kbl_adb_usb_fn_event(&transport,1,8,q)==1);
    CHECK(kbl_adb_usb_fn_event(&transport,1,8,os2)==1&&transport.control_queued&&fn_aborts==2);CHECK(fn_event(1,2,0)==1&&transport.control_pending&&last_fn_bytes==338);
    CHECK(fn_event(1,1,338)==1&&fn_event(0,1,0)==1&&!transport.fault);
    CHECK(setup()==0);fail_start=1;CHECK(kbl_adb_usb_arm(&transport)==0);fence=1;CHECK(kbl_adb_usb_step(&transport,1)<0&&starts==2&&last_set==&transport.descriptors.original);
    CHECK(!hooks&&frees==3&&kbl_adb_usb_can_release(&transport));CHECK(setup()==0);fail_start=fail_rollback=1;CHECK(kbl_adb_usb_arm(&transport)==0);fence=1;
    CHECK(eq(transport.descriptors.original.device,dev,18)&&eq(transport.descriptors.original.ss_device,ssdev,18));
    CHECK(kbl_adb_usb_step(&transport,1)<0&&transport.phase==KBL_ADB_USB_ROLLBACK&&hooks==1&&frees==0&&!kbl_adb_usb_can_release(&transport));
    kbl_adb_usb_request_stop(&transport);CHECK(kbl_adb_usb_step(&transport,1)<0&&transport.configuration_uncertain&&hooks==1&&frees==0&&!kbl_adb_usb_can_release(&transport));
    CHECK(setup()==0);CHECK(activate()==0);fail_send=1;model_frame(9);CHECK(kbl_adb_usb_step(&transport,1)<0&&transport.tx_pending&&!done&&!frees);
    CHECK(event(0x82,transport.tx,3,KBL_USB_FAILED)==1&&done==1&&output_at==3&&!transport.tx_pending);CHECK(!stops);
    CHECK(setup()==0);max_transfer=1024;CHECK(activate()==0&&transport.transfer_limit==1024);model_frame(2000);
    CHECK(kbl_adb_usb_step(&transport,1)==0&&transport.tx_bytes==24);CHECK(event(0x82,transport.tx,24,KBL_USB_COMPLETE)==1);
    CHECK(kbl_adb_usb_step(&transport,1)==0&&transport.tx_bytes==1024&&max_queries==1);CHECK(event(0x82,transport.tx,1024,KBL_USB_COMPLETE)==1);
    CHECK(kbl_adb_usb_step(&transport,1)==0&&transport.tx_bytes==976);CHECK(event(0x82,transport.tx,976,KBL_USB_COMPLETE)==1&&transport.frame_remaining==0&&output_at==2024);
    CHECK(setup()==0);max_transfer=64;CHECK(kbl_adb_usb_arm(&transport)==0);fence=1;CHECK(kbl_adb_usb_step(&transport,1)<0&&transport.phase==KBL_ADB_USB_FAILED&&!starts&&!allocations);
    CHECK(setup()==0);CHECK(activate()==0);model_frame(113);CHECK(kbl_adb_usb_step(&transport,1)==0&&transport.tx_bytes==24);
    CHECK(event(0x82,transport.tx,23,KBL_USB_COMPLETE)==1&&transport.fault&&last_output_status<0&&output_at==23);
    unsigned short_sends=sends;CHECK(kbl_adb_usb_step(&transport,1)<0&&sends==short_sends&&!transport.tx_pending);
    CHECK(setup()==0);dev[17]=ssdev[17]=3;const unsigned char values[3]={1,4,7};
    for(unsigned c=0;c<3;++c){cp(multiple_cfg[c],cfg,32);cp(multiple_sscfg[c],sscfg,44);multiple_cfg[c][5]=multiple_sscfg[c][5]=values[c];cfgp[c]=multiple_cfg[c];ssp[c]=multiple_sscfg[c];}
    CHECK(kbl_adb_usb_descriptors(&transport.descriptors,&set,0,span)==0);CHECK(activate()==0);
    CHECK(kbl_adb_usb_fn_event(&transport,1,8,os2)==1&&last_fn_bytes==994&&os2_valid(transport.control,last_fn_bytes,3));
    CHECK(fn_event(1,1,994)==1&&fn_event(0,1,0)==1);
    for(unsigned c=0;c<3;++c)CHECK(transport.descriptors.config[c][5]==values[c]&&transport.descriptors.ss_config[c][5]==values[c]&&eq(transport.descriptors.original.configs[c],multiple_cfg[c],32));
    CHECK(setup()==0);CHECK(activate()==0);kbl_adb_usb_request_stop(&transport);CHECK(kbl_adb_usb_step(&transport,1)>0&&!stalls);
    CHECK(event(2,transport.rx,0,KBL_USB_CANCELLED)==1);fail_stall_ep=0x82;
    CHECK(kbl_adb_usb_step(&transport,1)<0&&transport.stop_stalled==1&&stall_out_attempts==1&&stall_in_attempts==1&&!frees);
    CHECK(kbl_adb_usb_step(&transport,1)<0&&stall_out_attempts==1&&stall_in_attempts==2&&!frees);
    fail_stall_ep=0;CHECK(kbl_adb_usb_step(&transport,1)==0&&stall_out_attempts==1&&stall_in_attempts==3&&transport.phase==KBL_ADB_USB_RESIDENT);
    /* Leave a clean descriptor fixture available to the real provider test. */
    fixture();CHECK(kbl_adb_usb_descriptors(&built,&set,0,span)==0);return 0;
}
static _Alignas(16) unsigned char core_memory[1048576];static unsigned core_allocated,stop_notifications,job_active;
static size_t core_used;static unsigned core_allocator_errors;
static struct{void*pointer;size_t bytes;}core_blocks[64];
static void*core_allocate(void*o,size_t n,size_t align){(void)o;
    if(!align||align>16||(align&(align-1))||core_used>SIZE_MAX-15)return 0;
    size_t at=(core_used+15)&~(size_t)15;if(n>sizeof(core_memory)-at)return 0;
    for(unsigned i=0;i<64;++i)if(!core_blocks[i].pointer){void*p=core_memory+at;core_blocks[i].pointer=p;core_blocks[i].bytes=n;core_used=at+n;++core_allocated;return p;}
    return 0;
}
static void core_release(void*o,void*p,size_t n){(void)o;
    for(unsigned i=0;i<64;++i)if(core_blocks[i].pointer==p&&core_blocks[i].bytes==n){core_blocks[i].pointer=0;--core_allocated;return;}
    ++core_allocator_errors;
}
static unsigned core_el(void*o){(void)o;
#if defined(__aarch64__)
    uint64_t v;__asm__ volatile("mrs %0, CurrentEL":"=r"(v));return(unsigned)(v>>2);
#else
    return 1; /* Host test model only. */
#endif
}
static int core_submit(void*o,const unsigned char*p,size_t n,uint64_t*job){(void)o;if(job_active||n!=2||!eq(p,"el",2))return -1;job_active=1;*job=1;return 0;}
static int core_job(void*o,uint64_t job,unsigned budget,unsigned char*p,size_t cap,size_t*n,int*finished,int*rc){
    (void)o;(void)budget;if(!job_active||job!=1||cap<4)return -1;cp(p,"EL1\n",4);*n=4;*finished=1;*rc=0;job_active=0;return 0;}
static void core_stop(void*o){(void)o;++stop_notifications;}
static void put32(void*p,uint32_t v){unsigned char*d=p;for(unsigned i=0;i<4;++i)d[i]=(unsigned char)(v>>(i*8));}
static uint32_t get32(const void*p){const unsigned char*d=p;return(uint32_t)d[0]|(uint32_t)d[1]<<8|(uint32_t)d[2]<<16|(uint32_t)d[3]<<24;}
#define WORD(a,b,c,d) ((uint32_t)(a)|(uint32_t)(b)<<8|(uint32_t)(c)<<16|(uint32_t)(d)<<24)
static int core_receive(uint32_t command,uint32_t a,uint32_t b,const void*payload,size_t bytes){
    unsigned char frame[1024];if(bytes>sizeof(frame)-24)return -1;put32(frame,command);put32(frame+4,a);put32(frame+8,b);put32(frame+12,(uint32_t)bytes);put32(frame+16,0);put32(frame+20,~command);cp(frame+24,payload,bytes);
    for(size_t at=0;at<bytes+24;){if(!transport.rx_pending)return -2;size_t n=bytes+24-at;if(n>17)n=17;cp(transport.rx,frame+at,n);
        if(event(transport.descriptors.adb_out,transport.rx,n,KBL_USB_COMPLETE)!=1||kbl_adb_usb_step(&transport,100)<0)return -3;at+=n;}
    return 0;
}
static int core_take(unsigned char*frame,size_t capacity,size_t*bytes){*bytes=0;size_t need=24;
    for(unsigned attempts=0;attempts<100;++attempts){if(!transport.tx_pending&&kbl_adb_usb_step(&transport,100)<0)return -1;
        if(!transport.tx_pending)continue;size_t n=transport.tx_bytes;if(n>capacity-*bytes)return -2;cp(frame+*bytes,transport.tx,n);*bytes+=n;
        if(event(transport.descriptors.adb_in,transport.tx,n,KBL_USB_COMPLETE)!=1)return -3;
        if(*bytes>=24)need=24+get32(frame+12);if(*bytes==need)return 0;if(*bytes>need)return -4;
    }return -5;
}
FIXTURE_API int kbl_adb_usb_core_test_run(void){checks=0;core=0;core_periodic_stop=1;core_allocated=stop_notifications=job_active=0;core_used=core_allocator_errors=0;z(core_blocks,sizeof(core_blocks));CHECK(setup()==0);
    struct kbl_adb_config c={0};c.allocate=core_allocate;c.release=core_release;c.current_el=core_el;c.submit=core_submit;c.poll_job=core_job;c.stop_request=core_stop;c.explicit_usb_debug=1;
    CHECK(kbl_adb_create(&c,&core)==0&&core);max_transfer=1024;CHECK(activate()==0);unsigned char frame[1024];size_t n=0;
    CHECK(core_receive(WORD('C','N','X','N'),0x01000001,65536,0,0)==0);CHECK(core_take(frame,sizeof(frame),&n)==0&&get32(frame)==WORD('C','N','X','N')&&n>64);
    const char command[]="shell,v2,raw:el";CHECK(core_receive(WORD('O','P','E','N'),41,0,command,sizeof(command))==0);
    CHECK(core_take(frame,sizeof(frame),&n)==0&&get32(frame)==WORD('O','K','A','Y'));unsigned local=get32(frame+4);CHECK(get32(frame+8)==41);
    CHECK(core_take(frame,sizeof(frame),&n)==0&&get32(frame)==WORD('W','R','T','E')&&frame[24]==1&&get32(frame+25)==4&&eq(frame+29,"EL1\n",4));
    CHECK(core_receive(WORD('O','K','A','Y'),41,local,0,0)==0);CHECK(core_take(frame,sizeof(frame),&n)==0&&get32(frame)==WORD('W','R','T','E')&&frame[24]==3&&frame[29]==0);
    CHECK(core_receive(WORD('O','K','A','Y'),41,local,0,0)==0);CHECK(core_take(frame,sizeof(frame),&n)==0&&get32(frame)==WORD('C','L','S','E'));
    CHECK(core_receive(WORD('C','L','S','E'),41,local,0,0)==0);const char off[]="shell,v2,raw:kbl off";
    CHECK(core_receive(WORD('O','P','E','N'),42,0,off,sizeof(off))==0&&stop_notifications==1&&kbl_adb_stop_requested(core));
    CHECK(core_take(frame,sizeof(frame),&n)==0&&get32(frame)==WORD('O','K','A','Y'));local=get32(frame+4);
    CHECK(core_take(frame,sizeof(frame),&n)==0&&get32(frame)==WORD('W','R','T','E'));CHECK(!kbl_adb_stop_ready(core));
    CHECK(core_receive(WORD('O','K','A','Y'),42,local,0,0)==0);CHECK(core_take(frame,sizeof(frame),&n)==0&&get32(frame)==WORD('W','R','T','E'));
    CHECK(core_receive(WORD('O','K','A','Y'),42,local,0,0)==0);CHECK(core_take(frame,sizeof(frame),&n)==0&&get32(frame)==WORD('C','L','S','E'));
    CHECK(core_receive(WORD('C','L','S','E'),42,local,0,0)==0);CHECK(kbl_adb_stop_ready(core));
    kbl_adb_usb_request_stop(&transport);CHECK(kbl_adb_usb_step(&transport,1)>0&&transport.rx_pending);CHECK(event(2,transport.rx,0,KBL_USB_CANCELLED)==1);
    CHECK(kbl_adb_usb_step(&transport,1)==0&&transport.phase==KBL_ADB_USB_RESIDENT&&starts==1&&stops==0&&hooks==1);
    CHECK(kbl_adb_destroy(core)==0&&!core_allocated&&!core_allocator_errors);core=0;return 0;
}
static size_t legacy_usb_read(size_t expected,size_t packet){
    size_t capacity=(expected+packet-1)/packet*packet,received=0;
    for(unsigned attempts=0;attempts<1000;++attempts){if(!transport.tx_pending&&kbl_adb_usb_step(&transport,100)<0)return SIZE_MAX;
        if(!transport.tx_pending)continue;size_t n=transport.tx_bytes;
        if(n>capacity-received)return SIZE_MAX;received+=n;
        if(event(transport.descriptors.adb_in,transport.tx,n,KBL_USB_COMPLETE)!=1)return SIZE_MAX;
        if(n%packet||received==capacity)return received;
    }return SIZE_MAX;
}
static size_t legacy_first_read(size_t expected,size_t packet,size_t transfer){
    size_t capacity=(expected+packet-1)/packet*packet;return transfer<capacity?transfer:capacity;
}
FIXTURE_API int kbl_adb_usb_legacy_test_run(void){checks=0;core=0;
    /* The old coalesced CNXN 24+113 ends the padded header read at137, which
     * violates the real Windows reader's exact24-byte header contract. */
    CHECK(legacy_first_read(24,1024,137)==137);CHECK(legacy_first_read(24,1024,24)==24);
    const unsigned payloads[]={0,113,512,1024,2000,65536},caps[]={1024,65536};
    for(unsigned ss=0;ss<2;++ss)for(unsigned cap=0;cap<2;++cap)for(unsigned n=0;n<6;++n){
        CHECK(setup()==0);if(!ss){set.ss_device=0;set.ss_configs=0;CHECK(kbl_adb_usb_descriptors(&transport.descriptors,&set,0,span)==0);}
        max_transfer=caps[cap];CHECK(activate()==0);model_frame(payloads[n]);size_t packet=ss?1024:512;
        CHECK(legacy_usb_read(24,packet)==24&&output_at==24);if(payloads[n])CHECK(legacy_usb_read(payloads[n],packet)==payloads[n]);
        CHECK(output_at==24+payloads[n]&&transport.frame_remaining==0&&!transport.fault);
        unsigned before=sends;CHECK(kbl_adb_usb_step(&transport,100)==0&&!transport.tx_pending&&sends==before); /* No extra ZLP. */
    }
    CHECK(setup()==0);max_transfer=1500;CHECK(activate()==0);model_frame(2000);
    CHECK(legacy_usb_read(24,1024)==24);CHECK(kbl_adb_usb_step(&transport,100)==0&&transport.tx_bytes==1024);
    CHECK(event(0x82,transport.tx,1024,KBL_USB_COMPLETE)==1);CHECK(kbl_adb_usb_step(&transport,100)==0&&transport.tx_bytes==976);
    return 0;
}
