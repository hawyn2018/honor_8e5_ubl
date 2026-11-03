/* SPDX-License-Identifier: MIT */
#include "kbl_display.h"
static const struct kefi_guid display_guid=KBL_DISPLAY_POWER_GUID;
static int valid_state(unsigned v){return v==1||v==2;}
kefi_status kbl_display_request(struct kbl_display*d,struct kefi_system_table*st,unsigned op){
    if(!d||!st||op>2)return KEFI_INVALID;
    d->st=st;d->provider=d->revision=d->state=d->observed=d->set_attempted=0;
    d->get_status=d->set_status=d->locate_status=KEFI_UNSUPPORTED;
    if(!st->boot_services||!st->boot_services->locate_protocol)return KEFI_UNSUPPORTED;
    void*found=0;d->locate_status=st->boot_services->locate_protocol(&display_guid,0,&found);
    if(d->locate_status!=KEFI_SUCCESS)return d->locate_status;
    struct kbl_display_power_protocol*p=found;
    if(!p||!p->set||!p->get)return KEFI_UNSUPPORTED;
    d->provider=1;d->revision=p->revision;
    unsigned state=0;d->get_status=p->get(p,&state);
    if(d->get_status!=KEFI_SUCCESS)return d->get_status;
    if(!valid_state(state)&&state!=0)return KEFI_UNSUPPORTED;
    d->state=state;d->observed=valid_state(state);
    if(op==KBL_DISPLAY_STATUS)return d->observed?KEFI_SUCCESS:KEFI_UNSUPPORTED;
    if(op==KBL_DISPLAY_OFF&&d->observed&&!d->saved_valid){d->saved=state;d->saved_valid=1;}
    /* The published ABI has only OFF and MAXIMUM. screenon always opens the
     * display, even when a caller's first screenoff found it already off. */
    unsigned wanted=op==KBL_DISPLAY_OFF?1u:2u;
    if(!d->observed||state!=wanted){
        d->set_attempted=1;d->set_status=p->set(p,wanted);
        state=0;d->get_status=p->get(p,&state);
        d->observed=d->get_status==KEFI_SUCCESS&&valid_state(state);
        d->state=d->observed?state:0;
        if(d->set_status!=KEFI_SUCCESS)return d->set_status;
        if(d->get_status!=KEFI_SUCCESS)return d->get_status;
        if(!d->observed||d->state!=wanted)return KEFI_ERROR(7);
    }
    if(op==KBL_DISPLAY_ON)d->saved_valid=0;
    return KEFI_SUCCESS;
}
struct builder{char*p;size_t n,cap;unsigned bad;};
static void text(struct builder*b,const char*s){while(*s){if(b->n+1>=b->cap){b->p[b->n]=0;b->bad=1;return;}b->p[b->n++]=*s++;}b->p[b->n]=0;}
static void hex(struct builder*b,uint64_t v){char s[17];const char*h="0123456789abcdef";for(unsigned i=0;i<16;++i)s[i]=h[(v>>(60-4*i))&15];s[16]=0;text(b,s);}
static void value(struct builder*b,const char*k,const char*v){text(b,"KBL1 ");text(b,k);text(b,"=");text(b,v);text(b,"\n");}
static void status(struct builder*b,const char*k,uint64_t v){text(b,"KBL1 ");text(b,k);text(b,"=");hex(b,v);text(b,"\n");}
int kbl_display_describe(const struct kbl_display*d,char*out,size_t cap){
    if(!d||!out||!cap)return 0;out[0]=0;struct builder b={out,0,cap,0};
    value(&b,"display_provider",d->provider?"efi-display-power":"unsupported");
    status(&b,"display_revision",d->revision);
    status(&b,"display_locate_status",d->locate_status);
    status(&b,"display_get_status",d->get_status);
    value(&b,"display_state",!d->observed?"unknown":d->state==1?"off":"on");
    value(&b,"display_state_value",!d->observed?"0":d->state==1?"1":"2");
    value(&b,"display_set_attempted",d->set_attempted?"1":"0");
    status(&b,"display_set_status",d->set_status);
    value(&b,"display_saved",!d->saved_valid?"none":d->saved==1?"1":"2");
    value(&b,"display_observed",d->observed?"1":"0");
    return !b.bad;
}
struct runtime_services {
    struct kefi_header header;
    void *get_time,*set_time,*get_wakeup,*set_wakeup,*set_virtual_map,*convert_pointer;
    void *get_variable,*next_variable,*set_variable,*next_monotonic;
    void (*reset_system)(unsigned,kefi_status,size_t,void *);
};
_Static_assert(offsetof(struct runtime_services,reset_system)==0x68,"UEFI ResetSystem ABI");
kefi_status kbl_power_shutdown(struct kefi_system_table*st){
    if(!st||!st->runtime_services)return KEFI_UNSUPPORTED;
    struct runtime_services*r=st->runtime_services;
    if(r->header.signature!=UINT64_C(0x56524553544e5552)||r->header.bytes<sizeof(*r)||!r->reset_system)return KEFI_UNSUPPORTED;
    r->reset_system(2,KEFI_SUCCESS,0,0);
    /* ResetSystem is specified not to return. Returning is a failure, not an
     * invented successful shutdown; leave the existing runtime available. */
    return KEFI_ERROR(7);
}
