/* SPDX-License-Identifier: MIT -- explicit, lazy UEFI composite ADB transport. */
#include "kbl_adb_usb.h"
#define AGAIN 1
#define ERROR (-1)
#define CONTROL_BYTES 2048u
#define VENDOR_CODE 0x20u
static void zero(void*p,size_t n){unsigned char*d=p;while(n--)*d++=0;}
static void copy(void*p,const void*q,size_t n){unsigned char*d=p;const unsigned char*s=q;while(n--)*d++=*s++;}
static uint16_t u16(const void*p){const unsigned char*b=p;return(uint16_t)(b[0]|b[1]<<8);}
static uint32_t u32(const void*p){const unsigned char*b=p;return(uint32_t)b[0]|(uint32_t)b[1]<<8|(uint32_t)b[2]<<16|(uint32_t)b[3]<<24;}
static void w16(void*p,unsigned n){unsigned char*b=p;b[0]=(unsigned char)n;b[1]=(unsigned char)(n>>8);}
static void w32(void*p,uint32_t n){unsigned char*b=p;for(unsigned i=0;i<4;++i)b[i]=(unsigned char)(n>>(8*i));}
static void debug_identity(unsigned char*d){w16(d+10,KBL_ADB_USB_DEBUG_PID);w16(d+12,KBL_ADB_USB_DEBUG_BCD_DEVICE);}
static int equal(const void*a,const void*b,size_t n){const unsigned char*x=a,*y=b;while(n--)if(*x++!=*y++)return 0;return 1;}
static const unsigned char ms_uuid[16]={0xdf,0x60,0xdd,0xd8,0x89,0x45,0xc7,0x4c,0x9c,0xd2,0x65,0x9d,0x9e,0x64,0x8a,0x9f};
static int take(void*opaque,int(*span)(void*,const void*,size_t),void*dst,const void*src,size_t n){if(!src||!span(opaque,src,n))return 0;copy(dst,src,n);return 1;}
struct cfg_scan {unsigned endpoints,interfaces,max_interface;unsigned char fastboot;unsigned have_fastboot;};
static int scan_config(const unsigned char*b,size_t n,int ss,struct cfg_scan*out){
    if(n<9||b[0]!=9||b[1]!=2||u16(b+2)!=n||!b[4])return 0;
    unsigned flags=0,ifnum=0,endpoints=0,declared=0,seen=0;unsigned char ids[256];zero(ids,sizeof(ids));
    for(size_t at=9;at<n;){unsigned len=b[at],kind=at+1<n?b[at+1]:0;if(len<2||len>n-at)return 0;
        if(kind==4){if(len!=9||endpoints!=declared)return 0;ifnum=b[at+2];declared=b[at+4];endpoints=0;
            if(!b[at+3]){if(ids[ifnum])return 0;ids[ifnum]=1;++seen;}if(ifnum>out->max_interface)out->max_interface=ifnum;
            flags=b[at+5]==0xff&&b[at+6]==0x42&&b[at+7]==3;
            if(flags){if(out->have_fastboot&&out->fastboot!=ifnum)return 0;out->fastboot=(uint8_t)ifnum;out->have_fastboot=1;}
        }else if(kind==5){if(len!=7||!declared||++endpoints>declared)return 0;unsigned ep=b[at+2],index=ep&15;if(!index||(ep&0x70))return 0;
            out->endpoints|=1u<<(index+((ep&128)?16:0));
            if(flags){if((ep!=1&&ep!=0x81)||(b[at+3]&3)!=2)return 0;out->interfaces|=ep==1?1u:2u;}
            if(ss&&(at+len+6>n||b[at+len]!=6||b[at+len+1]!=48))return 0;
        }else if(kind==48){if(!ss||len!=6)return 0;}
        at+=len;
    }
    return endpoints==declared&&seen==b[4]&&out->have_fastboot&&out->interfaces==3;
}
static size_t append_interface(unsigned char*b,size_t n,unsigned iface,unsigned out,unsigned in,unsigned string,int ss){
    unsigned char desc[35]={9,4,0,0,2,0xff,0x42,1,0,7,5,0,2,0,0,0};
    desc[2]=(uint8_t)iface;desc[8]=(uint8_t)string;desc[11]=(uint8_t)out;w16(desc+13,ss?1024:512);
    size_t at=16;if(ss){unsigned char c[6]={6,48,0,0,0,0};copy(desc+at,c,6);at+=6;}
    unsigned char ep[7]={7,5,0,2,0,0,0};ep[2]=(uint8_t)in;w16(ep+4,ss?1024:512);copy(desc+at,ep,7);at+=7;
    if(ss){unsigned char c[6]={6,48,0,0,0,0};copy(desc+at,c,6);at+=6;}
    if(n>KBL_ADB_USB_CONFIG_BYTES-at)return 0;copy(b+n,desc,at);w16(b+2,(unsigned)(n+at));++b[4];return n+at;
}
static int add_bos(struct kbl_adb_usb_descriptors*d,size_t original_bytes){
    size_t n=original_bytes;if(!n){zero(d->bos,sizeof(d->bos));d->bos[0]=5;d->bos[1]=15;n=5;w16(d->bos+2,5);}
    if(n<5||d->bos[0]!=5||d->bos[1]!=15||u16(d->bos+2)!=n||n>sizeof(d->bos)-28)return 0;
    unsigned count=0;for(size_t at=5;at<n;){unsigned length=d->bos[at];if(length<3||length>n-at||d->bos[at+1]!=16)return 0;
        /* Do not replace another OS-descriptor vendor channel. */
        if(length>=20&&d->bos[at+2]==5&&equal(d->bos+at+4,ms_uuid,16))return 0;
        ++count;at+=length;
    }if(count!=d->bos[4]||count==255)return 0;
    unsigned char cap[28]={28,16,5,0};copy(cap+4,ms_uuid,16);w32(cap+20,0x06030000);w16(cap+24,10+d->configs*328);cap[26]=VENDOR_CODE;
    copy(d->bos+n,cap,28);w16(d->bos+2,(unsigned)n+28);d->bos[4]=(uint8_t)(count+1);return 1;
}
int kbl_adb_usb_descriptors(struct kbl_adb_usb_descriptors*d,const struct kbl_usb_descriptor_set*source,
                          void*opaque,int(*span)(void*,const void*,size_t)){
    if(!d||!source||!span||!span(opaque,source,sizeof(*source)))return ERROR;
    struct kbl_usb_descriptor_set s=*source;zero(d,sizeof(*d));
    if(!take(opaque,span,d->old_device,s.device,18)||d->old_device[0]!=18||d->old_device[1]!=1)return ERROR;
    unsigned count=d->old_device[17];if(!count||count>KBL_ADB_USB_CONFIGS||!span(opaque,s.configs,count*sizeof(void*)))return ERROR;
    d->configs=count;copy(d->device,d->old_device,18);debug_identity(d->device);d->device[4]=d->device[5]=d->device[6]=0;if(u16(d->device+2)<0x201)w16(d->device+2,0x201);
    d->has_ss=s.ss_device!=0;if(d->has_ss){if(!take(opaque,span,d->old_ss_device,s.ss_device,18)||d->old_ss_device[0]!=18||d->old_ss_device[1]!=1||d->old_ss_device[17]!=count||!span(opaque,s.ss_configs,count*sizeof(void*)))return ERROR;
        copy(d->ss_device,d->old_ss_device,18);debug_identity(d->ss_device);d->ss_device[4]=d->ss_device[5]=d->ss_device[6]=0;}
    struct cfg_scan scan={0};
    for(unsigned i=0;i<count;++i){unsigned char h[9];if(!take(opaque,span,h,s.configs[i],9))return ERROR;unsigned n=u16(h+2);if(n<9||n>KBL_ADB_USB_CONFIG_BYTES||!take(opaque,span,d->old_config[i],s.configs[i],n))return ERROR;
        struct cfg_scan one={0};if(!scan_config(d->old_config[i],n,0,&one))return ERROR;
        if(i&&one.fastboot!=scan.fastboot)return ERROR;scan.endpoints|=one.endpoints;if(one.max_interface>scan.max_interface)scan.max_interface=one.max_interface;scan.fastboot=one.fastboot;
        copy(d->config[i],d->old_config[i],n);d->config_ptr[i]=d->config[i];d->old_config_ptr[i]=d->old_config[i];
        if(d->has_ss){if(!take(opaque,span,h,s.ss_configs[i],9))return ERROR;n=u16(h+2);if(n<9||n>KBL_ADB_USB_CONFIG_BYTES||!take(opaque,span,d->old_ss_config[i],s.ss_configs[i],n))return ERROR;
            struct cfg_scan ss={0};if(!scan_config(d->old_ss_config[i],n,1,&ss)||ss.fastboot!=scan.fastboot)return ERROR;scan.endpoints|=ss.endpoints;if(ss.max_interface>scan.max_interface)scan.max_interface=ss.max_interface;
            copy(d->ss_config[i],d->old_ss_config[i],n);d->ss_config_ptr[i]=d->ss_config[i];d->old_ss_config_ptr[i]=d->old_ss_config[i];}
    }
    if(scan.max_interface==255)return ERROR;d->adb_interface=(uint8_t)(scan.max_interface+1);d->fastboot_interface=scan.fastboot;
    unsigned ep;for(ep=2;ep<16;++ep)if(!(scan.endpoints&(1u<<ep))&&!(scan.endpoints&(1u<<(ep+16))))break;if(ep==16)return ERROR;d->adb_out=(uint8_t)ep;d->adb_in=(uint8_t)(ep|128);
    if(s.string_count>KBL_ADB_USB_STRINGS||(s.string_count&&(!s.strings||!span(opaque,s.strings,s.string_count*sizeof(void*)))))return ERROR;
    d->strings=s.string_count;for(unsigned i=0;i<s.string_count;++i){unsigned char h[2];if(!take(opaque,span,h,s.strings[i],2)||h[0]<2||(h[0]&1)||h[1]!=3||!take(opaque,span,d->string[i],s.strings[i],h[0]))return ERROR;d->string_ptr[i]=d->string[i];}
    if(!d->strings){unsigned char lang[4]={4,3,9,4};copy(d->string[0],lang,4);d->string_ptr[0]=d->string[0];d->strings=1;}
    unsigned string=0;if(d->strings<KBL_ADB_USB_STRINGS){string=d->strings++;unsigned char label[16]={16,3,'K',0,'B',0,'L',0,' ',0,'A',0,'D',0,'B',0};copy(d->string[string],label,16);d->string_ptr[string]=d->string[string];}
    for(unsigned i=0;i<count;++i){if(!append_interface(d->config[i],u16(d->config[i]+2),d->adb_interface,d->adb_out,d->adb_in,string,0))return ERROR;
        if(d->has_ss&&!append_interface(d->ss_config[i],u16(d->ss_config[i]+2),d->adb_interface,d->adb_out,d->adb_in,string,1))return ERROR;}
    size_t bos_bytes=0;if(s.bos){unsigned char h[5];if(!take(opaque,span,h,s.bos,5))return ERROR;bos_bytes=u16(h+2);if(bos_bytes<5||bos_bytes>sizeof(d->old_bos)||!take(opaque,span,d->old_bos,s.bos,bos_bytes))return ERROR;copy(d->bos,d->old_bos,bos_bytes);}
    if(!add_bos(d,bos_bytes))return ERROR;
    if(s.qualifier){if(!take(opaque,span,d->old_qualifier,s.qualifier,10)||d->old_qualifier[0]!=10||d->old_qualifier[1]!=6)return ERROR;copy(d->qualifier,d->old_qualifier,10);d->qualifier[4]=d->qualifier[5]=d->qualifier[6]=0;}
    d->original=(struct kbl_usb_descriptor_set){d->old_device,d->old_config_ptr,d->has_ss?d->old_ss_device:0,d->has_ss?d->old_ss_config_ptr:0,s.qualifier?d->old_qualifier:0,bos_bytes?d->old_bos:0,s.string_count,s.string_count?d->string_ptr:0};
    d->composite=(struct kbl_usb_descriptor_set){d->device,d->config_ptr,d->has_ss?d->ss_device:0,d->has_ss?d->ss_config_ptr:0,s.qualifier?d->qualifier:0,d->bos,(uint8_t)d->strings,d->string_ptr};
    return 0;
}
static size_t utf16(unsigned char*out,const char*s,int double_zero){size_t n=0;do{out[n++]=(unsigned char)*s;out[n++]=0;}while(*s++);if(double_zero){out[n++]=0;out[n++]=0;}return n;}
static size_t property(unsigned char*out,int os2){
    const char*name="DeviceInterfaceGUIDs",*guid="{F72FE0D4-CBCB-407D-8814-9ED673D0DD6B}";
    if(os2){size_t at=8;at+=utf16(out+at,name,0);w16(out+6,(unsigned)(at-8));size_t p=at;at+=2;at+=utf16(out+at,guid,1);w16(out+p,(unsigned)(at-p-2));w16(out,(unsigned)at);w16(out+2,4);w16(out+4,7);return at;}
    size_t at=10;at+=utf16(out+at,name,0);w16(out+8,(unsigned)(at-10));size_t p=at;at+=4;at+=utf16(out+at,guid,1);w32(out+p,(uint32_t)(at-p-4));w32(out,(uint32_t)at);w32(out+4,7);return at;
}
static size_t os2_descriptor(struct kbl_adb_usb*s,unsigned char*out){
    /* Microsoft OS 2.0 uses a zero-based configuration index here; the
     * similarly named USB configuration descriptor field remains unchanged. */
    size_t at=10;for(unsigned c=0;c<s->descriptors.configs;++c){size_t cfg=at;at+=8;w16(out+cfg,8);w16(out+cfg+2,1);out[cfg+4]=(uint8_t)c;
        for(unsigned n=0;n<2;++n){size_t fn=at;at+=8;w16(out+fn,8);w16(out+fn+2,2);out[fn+4]=n?s->descriptors.adb_interface:s->descriptors.fastboot_interface;
            w16(out+at,20);w16(out+at+2,3);copy(out+at+4,"WINUSB",6);at+=20;at+=property(out+at,1);w16(out+fn+6,(unsigned)(at-fn));}
        w16(out+cfg+6,(unsigned)(at-cfg));
    }w16(out,10);w16(out+2,0);w32(out+4,0x06030000);w16(out+8,(unsigned)at);return at;
}
static size_t control_reply(struct kbl_adb_usb*s,const unsigned char*q,unsigned char*out){
    unsigned value=u16(q+2),index=u16(q+4);zero(out,CONTROL_BYTES);
    if(q[0]==0x80&&q[1]==6&&value==0x03ee){unsigned char ms[18]={18,3,'M',0,'S',0,'F',0,'T',0,'1',0,'0',0,'0',0,VENDOR_CODE,0};copy(out,ms,18);return 18;}
    if(q[1]!=VENDOR_CODE)return 0;
    if(q[0]==0xc0&&index==7&&!value)return os2_descriptor(s,out);
    if(q[0]==0xc0&&index==4&&!value){w32(out,64);w16(out+4,0x100);w16(out+6,4);out[8]=2;
        for(unsigned n=0;n<2;++n){size_t at=16+n*24;out[at]=n?s->descriptors.adb_interface:s->descriptors.fastboot_interface;out[at+1]=1;copy(out+at+2,"WINUSB",6);}return 64;}
    if(q[0]==0xc1&&index==5&&(value==s->descriptors.adb_interface||value==s->descriptors.fastboot_interface)){
        size_t n=property(out+10,0);w32(out,(uint32_t)(10+n));w16(out+4,0x100);w16(out+6,5);w16(out+8,1);return 10+n;}
    return 0;
}
static int control_recognizes(const struct kbl_adb_usb*s,const unsigned char*q){
    unsigned value=u16(q+2),index=u16(q+4);if(!u16(q+6))return 0;
    if(q[0]==0x80&&q[1]==6&&value==0x03ee)return 1;
    if(q[1]!=VENDOR_CODE)return 0;
    return(q[0]==0xc0&&!value&&(index==4||index==7))||
        (q[0]==0xc1&&index==5&&(value==s->descriptors.adb_interface||value==s->descriptors.fastboot_interface));
}
static int terminal(const struct kbl_usb_outcome*t){return t->status>=KBL_USB_COMPLETE&&t->status<=KBL_USB_FAILED;}
static int release_buffers(struct kbl_adb_usb*s){
    if(!s->rx_pending&&s->rx&&s->env.device->free(s->rx)==KEFI_SUCCESS)s->rx=0;
    if(!s->tx_pending&&s->tx&&s->env.device->free(s->tx)==KEFI_SUCCESS)s->tx=0;
    /* EP0 remains available in resident mode for descriptor queries. */
    if(!s->composite_started&&!s->control_pending&&s->control&&s->env.device->free(s->control)==KEFI_SUCCESS)s->control=0;
    return s->rx||s->tx||(!s->composite_started&&s->control)?AGAIN:0;
}
int kbl_adb_usb_init(struct kbl_adb_usb*s,const struct kbl_adb_usb_environment*e,const struct kbl_usb_descriptor_set*d,
    void*opaque,int(*span)(void*,const void*,size_t)){
    if(!s||!e||s->initialized||!e->device||!e->usbfn||!e->activation_fence||!e->control_router||!e->feed||!e->poll||!e->output||!e->output_done||!e->disconnect||
       !e->device->start_ex||!e->device->send||!e->device->allocate||!e->device->free||!e->device->abort||!e->device->stall||!e->usbfn->transfer||!e->usbfn->abort_transfer||!e->usbfn->max_transfer)return ERROR;
    if(kbl_adb_usb_descriptors(&s->descriptors,d,opaque,span))return ERROR;s->env=*e;s->initialized=1;s->phase=KBL_ADB_USB_IDLE;return 0;
}
int kbl_adb_usb_arm(struct kbl_adb_usb*s){if(!s||!s->initialized||(s->phase!=KBL_ADB_USB_IDLE&&s->phase!=KBL_ADB_USB_RESIDENT))return ERROR;s->stop_requested=0;s->stop_stalled=0;s->frame_remaining=0;s->fault=0;s->phase=KBL_ADB_USB_ARMED;return 0;}
static int allocate_buffers(struct kbl_adb_usb*s){
    if(!s->rx&&(s->last_status=s->env.device->allocate(KBL_ADB_USB_DMA_BYTES,&s->rx))!=KEFI_SUCCESS)return ERROR;
    if(!s->tx&&(s->last_status=s->env.device->allocate(KBL_ADB_USB_DMA_BYTES,&s->tx))!=KEFI_SUCCESS)return ERROR;
    if(!s->control&&(s->last_status=s->env.device->allocate(CONTROL_BYTES,&s->control))!=KEFI_SUCCESS)return ERROR;
    return s->rx&&s->tx&&s->control?0:ERROR;
}
static int stall(struct kbl_adb_usb*s,int value){kefi_status(*fn)(uint8_t,uint8_t)=(void*)s->env.device->stall;
    kefi_status a=fn(s->descriptors.adb_out,(uint8_t)value),b=fn(s->descriptors.adb_in,(uint8_t)value);s->last_status=a?a:b;return a||b?ERROR:0;}
static int stall_stopped(struct kbl_adb_usb*s){kefi_status(*fn)(uint8_t,uint8_t)=(void*)s->env.device->stall;
    for(unsigned i=0;i<2;++i){unsigned bit=1u<<i;if(s->stop_stalled&bit)continue;
        s->last_status=fn(i?s->descriptors.adb_in:s->descriptors.adb_out,1);
        if(s->last_status)return ERROR;s->stop_stalled|=bit;
    }return 0;
}
static int queue_rx(struct kbl_adb_usb*s){if(s->rx_pending||s->rx_held||s->stop_requested||!s->connected)return 0;s->rx_pending=1;s->rx_abort=0;
    s->last_status=s->env.device->send(s->descriptors.adb_out,s->transfer_limit,s->rx);if(s->last_status){s->fault=1;return ERROR;}return 0;}
void kbl_adb_usb_request_stop(struct kbl_adb_usb*s){if(s&&s->initialized){s->stop_requested=1;if(s->composite_started&&s->phase!=KBL_ADB_USB_RESIDENT)s->phase=KBL_ADB_USB_STOPPING;}}
int kbl_adb_usb_dma_quiet(const struct kbl_adb_usb*s){return s&&!s->rx_pending&&!s->tx_pending&&!s->control_pending;}
int kbl_adb_usb_can_release(const struct kbl_adb_usb*s){return s&&s->initialized&&!s->configuration_uncertain&&!s->composite_started&&!s->router_installed&&!s->rx&&!s->tx&&!s->control&&kbl_adb_usb_dma_quiet(s);}
int kbl_adb_usb_step(struct kbl_adb_usb*s,unsigned budget){
    if(!s||!s->initialized)return ERROR;
    if(s->phase==KBL_ADB_USB_RESIDENT)return 0;
    /* A failed rollback does not prove either descriptor set was detached.
     * Keep both sets, the forwarding router and all DMA until explicit
     * controller recovery or reboot, even when a later STOP is requested. */
    if(s->configuration_uncertain)return ERROR;
    if(s->stop_requested&&!s->composite_started){
        if(s->router_installed){if(s->env.control_router(s->env.opaque,0))return AGAIN;s->router_installed=0;}
        if(release_buffers(s))return AGAIN;s->phase=KBL_ADB_USB_IDLE;return 0;
    }
    if(s->phase==KBL_ADB_USB_ARMED){if(s->env.activation_fence(s->env.opaque)!=1)return AGAIN;
        size_t limit=0;s->last_status=s->env.usbfn->max_transfer(s->env.usbfn,&limit);
        if(s->last_status||!limit){s->phase=KBL_ADB_USB_FAILED;return ERROR;}
        s->transfer_limit=limit<KBL_ADB_USB_DMA_BYTES?limit:KBL_ADB_USB_DMA_BYTES;
        /* The original set can enumerate at SS or HS. A conservative SS
         * multiple is also a valid HS multiple; never emit a short non-tail
         * body fragment merely because a modeled/provider cap is too small. */
        s->packet_bytes=s->descriptors.has_ss?1024u:512u;
        if(s->transfer_limit<s->packet_bytes){s->last_status=KEFI_UNSUPPORTED;s->phase=KBL_ADB_USB_FAILED;return ERROR;}
        if(allocate_buffers(s)){s->phase=KBL_ADB_USB_FAILED;(void)release_buffers(s);return ERROR;}
        if(!s->router_installed){if(s->env.control_router(s->env.opaque,1)){s->phase=KBL_ADB_USB_FAILED;(void)release_buffers(s);return ERROR;}s->router_installed=1;}
        if(!s->composite_started){++s->reconfig_calls;s->configuration_uncertain=1;s->last_status=((kefi_status(*)(struct kbl_usb_descriptor_set*))s->env.device->start_ex)(&s->descriptors.composite);
            if(s->last_status){s->phase=KBL_ADB_USB_ROLLBACK;s->last_status=((kefi_status(*)(struct kbl_usb_descriptor_set*))s->env.device->start_ex)(&s->descriptors.original);
                if(!s->last_status){s->configuration_uncertain=0;if(!s->env.control_router(s->env.opaque,0))s->router_installed=0;s->phase=KBL_ADB_USB_FAILED;(void)release_buffers(s);}return ERROR;}
            s->configuration_uncertain=0;s->composite_started=1;s->connected=0;s->phase=KBL_ADB_USB_ENUMERATING;
        }else{s->phase=s->connected?KBL_ADB_USB_ACTIVE:KBL_ADB_USB_ENUMERATING;if(stall(s,0)){s->fault=1;return ERROR;}}
    }
    if(s->stop_requested){s->phase=KBL_ADB_USB_STOPPING;
        /* Do not abort a final ADB reply in flight. Caller stops submitting
         * application output before requesting transport stop. */
        if(s->tx_pending)return AGAIN;
        if(s->rx_pending&&!s->rx_abort){s->rx_abort=1;s->last_status=s->env.device->abort(s->descriptors.adb_out);}
        if(s->rx_pending)return AGAIN;
        /* Do not issue synchronous endpoint commands against an active OUT
         * transfer. Abort is only a request; exact terminal ownership return
         * precedes both STALLs. Retain each successful STALL across retries. */
        if(stall_stopped(s))return ERROR;s->rx_held=0;s->rx_at=s->rx_used=0;
        if(s->env.disconnect(s->env.opaque))return AGAIN;s->frame_remaining=0;
        if(release_buffers(s))return AGAIN;s->phase=KBL_ADB_USB_RESIDENT;return 0;
    }
    if(s->disconnected){
        if(s->rx_pending&&!s->rx_abort){s->rx_abort=1;(void)s->env.device->abort(s->descriptors.adb_out);}
        if(s->tx_pending&&!s->tx_abort){s->tx_abort=1;(void)s->env.device->abort(s->descriptors.adb_in);}
        if(s->rx_pending||s->tx_pending)return AGAIN;s->rx_held=0;s->rx_at=s->rx_used=0;
        if(s->env.disconnect(s->env.opaque))return AGAIN;s->disconnected=0;s->frame_remaining=0;
    }
    if(s->phase!=KBL_ADB_USB_ACTIVE||!s->connected||s->fault)return s->fault?ERROR:AGAIN;
    if(s->rx_held){size_t used=0;int rc=s->env.feed(s->env.opaque,(unsigned char*)s->rx+s->rx_at,s->rx_used-s->rx_at,&used);
        if(used>s->rx_used-s->rx_at){s->fault=1;return ERROR;}s->rx_at+=used;if(rc<0){s->fault=1;return ERROR;}
        if(s->rx_at==s->rx_used){s->rx_held=0;s->rx_at=s->rx_used=0;}
    }
    if(s->env.poll(s->env.opaque,budget)<0){s->fault=1;return ERROR;}
    if(!s->tx_pending){const void*p=0;size_t bytes=0;int rc=s->env.output(s->env.opaque,&p,&bytes);
        if(rc<0){s->fault=1;return ERROR;}if(p&&bytes){
            size_t send_bytes;
            if(!s->frame_remaining){
                if(bytes<24||u32((const unsigned char*)p+12)>KBL_ADB_MAX_PAYLOAD||bytes!=24u+u32((const unsigned char*)p+12)){s->fault=1;return ERROR;}
                s->frame_remaining=bytes;send_bytes=24;
            }else{
                if(bytes!=s->frame_remaining){s->fault=1;return ERROR;}
                send_bytes=bytes<s->transfer_limit?bytes:s->transfer_limit;
                if(send_bytes<bytes)send_bytes-=send_bytes%s->packet_bytes;
                if(!send_bytes){s->fault=1;return ERROR;}
            }
            /* ADB USB is a packet transport: host legacy readers request the
             * 24-byte header separately (with an MPS-sized USB buffer). A
             * coalesced header/body is not a compatible byte stream. */
            copy(s->tx,p,send_bytes);s->tx_bytes=send_bytes;s->tx_pending=1;s->tx_abort=0;
            s->last_status=s->env.device->send(s->descriptors.adb_in,send_bytes,s->tx);if(s->last_status){s->fault=1;return ERROR;}}
    }
    return queue_rx(s);
}
int kbl_adb_usb_event(struct kbl_adb_usb*s,const struct kbl_usb_event*e){
    if(!s||!s->initialized||!e)return ERROR;
    if(e->kind==KBL_USB_EVENT_STATE&&e->bytes==4){
        if(e->data.state==KBL_USB_DISCONNECTED){s->connected=0;s->disconnected=1;}
        else if(e->data.state==KBL_USB_CONNECTED&&s->composite_started){s->connected=1;if(!s->stop_requested&&s->phase!=KBL_ADB_USB_RESIDENT)s->phase=KBL_ADB_USB_ACTIVE;}
        return 0;
    }
    if(e->kind!=KBL_USB_EVENT_TRANSFER||e->bytes!=24)return 0;const struct kbl_usb_outcome*t=&e->data.transfer;
    int rx=t->endpoint==s->descriptors.adb_out&&t->buffer==s->rx&&s->rx;
    int tx=t->endpoint==s->descriptors.adb_in&&t->buffer==s->tx&&s->tx;
    if(!rx&&!tx)return 0;if(!terminal(t))return 1;
    if(rx){if(!s->rx_pending||t->completed>s->transfer_limit){s->fault=1;return ERROR;}s->rx_pending=0;
        if(t->status==KBL_USB_COMPLETE&&!s->stop_requested&&!s->disconnected){s->rx_used=t->completed;s->rx_at=0;s->rx_held=t->completed!=0;}
        else if(t->status==KBL_USB_FAILED&&!s->disconnected)s->fault=1;
    }else{if(!s->tx_pending||t->completed>s->tx_bytes){s->fault=1;return ERROR;}s->tx_pending=0;
        int status=t->status==KBL_USB_COMPLETE&&t->completed==s->tx_bytes&&s->frame_remaining>=s->tx_bytes?0:ERROR;
        if(!status)s->frame_remaining-=s->tx_bytes;else s->fault=1;
        if(s->env.output_done(s->env.opaque,t->completed,status)<0)s->fault=1;s->tx_bytes=0;
    }return 1;
}
static void control_cancel(struct kbl_adb_usb*s){
    if(s->control_pending){s->control_cancelled=1;if(!s->control_abort){s->control_abort=1;
        s->last_status=s->env.usbfn->abort_transfer(s->env.usbfn,0,s->control_direction);}}
}
static int control_send(struct kbl_adb_usb*s,const unsigned char*q){
    unsigned char reply[CONTROL_BYTES];size_t n=control_reply(s,q,reply);if(!n)return 0;
    if(!s->control){s->fault=1;return ERROR;}if(n>u16(q+6))n=u16(q+6);
    copy(s->control,reply,n);s->control_bytes=n;s->control_direction=1;s->control_stage=1;s->control_pending=1;s->control_abort=0;s->control_cancelled=0;
    size_t transfer=n;s->last_status=s->env.usbfn->transfer(s->env.usbfn,0,1,&transfer,s->control);
    if(transfer!=n||s->last_status){s->fault=1;return ERROR;}return 1;
}
int kbl_adb_usb_fn_event(struct kbl_adb_usb*s,uint32_t message,size_t bytes,const void*payload){
    if(!s||!s->initialized||!s->router_installed||!payload)return 0;
    /* Actual UsbfnDwc3 reports the 32-byte payload union for SETUP although
     * only its first 8 bytes are copied. Other providers report 8 directly.
     * The outer event owner already checks returned bytes against capacity. */
    if(message==1&&bytes>=8){const unsigned char*q=payload;int owned=control_recognizes(s,q);s->control_queued=0;
        if(s->control_pending){if(owned){copy(s->control_setup,q,8);s->control_queued=1;}control_cancel(s);return owned?1:0;}
        return owned?control_send(s,q):0;
    }
    if(message==4||message==6){s->control_queued=0;control_cancel(s);return 0;}
    if((message==2||message==3)&&bytes==sizeof(struct kbl_usbfn_result)){
        const struct kbl_usbfn_result*r=payload;if(r->endpoint||r->buffer!=s->control||!s->control)return 0;
        if(!s->control_pending||r->direction!=s->control_direction){s->fault=1;return ERROR;}
        if(r->status==3||r->status==0)return 1;
        s->control_pending=0;
        if(s->control_cancelled){s->control_cancelled=s->control_abort=s->control_stage=0;
            if(s->control_queued){s->control_queued=0;return control_send(s,s->control_setup);}return 1;}
        if(r->status!=1||r->completed!=s->control_bytes){s->fault=1;return ERROR;}
        if(s->control_stage==1){s->control_stage=2;s->control_direction=0;s->control_bytes=0;s->control_pending=1;
            size_t n=0;s->last_status=s->env.usbfn->transfer(s->env.usbfn,0,0,&n,s->control);
            if(s->last_status||n){s->fault=1;return ERROR;}
        }else s->control_stage=0;
        return 1;
    }
    return 0;
}
