/* Actual AArch64 production display/reset ABI code with local protocol mocks.
 * This fixture is never deployed and cannot reset a physical device. */
#include "../kbl_display.h"
static unsigned failed,checks,gets,sets,resets,hw=2,missing,lie,invalid_get;
static kefi_status get_error,set_error;
static kefi_status get(struct kbl_display_power_protocol*p,unsigned*out){
    (void)p;++gets;*out=invalid_get?0:hw;return get_error;
}
static kefi_status set(struct kbl_display_power_protocol*p,unsigned state){
    (void)p;++sets;if(!set_error&&!lie)hw=state;return set_error;
}
static struct kbl_display_power_protocol protocol={0x10000,set,get};
static kefi_status locate(const struct kefi_guid*g,void*unused,void**out){
    (void)unused;static const struct kefi_guid want=KBL_DISPLAY_POWER_GUID;
    const unsigned char*a=(const void*)g,*b=(const void*)&want;
    for(unsigned i=0;i<sizeof(want);++i)if(a[i]!=b[i]){failed=__LINE__;return KEFI_INVALID;}
    *out=missing?0:&protocol;return missing?KEFI_UNSUPPORTED:KEFI_SUCCESS;
}
static struct kefi_boot_services bs;
static struct kefi_system_table st;
static struct kbl_display d;
static char out[1024];
#define C(x) do{++checks;if(!(x)){failed=__LINE__;return failed;}}while(0)
static int has(const char*s){for(unsigned i=0;out[i];++i){unsigned n=0;while(s[n]&&s[n]==out[i+n])++n;if(!s[n])return 1;}return 0;}
static unsigned reset_kind;static kefi_status reset_status;static size_t reset_bytes;static void*reset_data;
static void reset(unsigned kind,kefi_status status,size_t bytes,void*data){++resets;reset_kind=kind;reset_status=status;reset_bytes=bytes;reset_data=data;}
static struct {struct kefi_header h;void*f[10];void(*reset)(unsigned,kefi_status,size_t,void*);} rt;
unsigned display_test_checks(void){return checks;}
unsigned display_test_current_el(void){uint64_t v;__asm__ volatile("mrs %0, CurrentEL":"=r"(v));return(unsigned)((v>>2)&3);}
int display_test_entry(void){
    st.boot_services=&bs;bs.locate_protocol=locate;
    C(!kbl_display_request(&d,&st,0)&&gets==1&&!sets&&d.state==2&&d.observed);
    C(!kbl_display_request(&d,&st,1)&&gets==3&&sets==1&&hw==1&&d.saved_valid&&d.saved==2);
    C(!kbl_display_request(&d,&st,1)&&gets==4&&sets==1&&d.state==1);
    C(kbl_display_describe(&d,out,sizeof(out))&&has("display_state=off")&&has("display_observed=1"));
    unsigned n=0,lines=0;for(unsigned i=0;out[i];++i){if(out[i]=='\n'){C(n<=56);++lines;n=0;}else ++n;}C(lines==10);
    C(!kbl_display_request(&d,&st,2)&&hw==2&&sets==2&&!d.saved_valid);
    C(!kbl_display_request(&d,&st,2)&&sets==2&&d.observed);
    hw=1;C(!kbl_display_request(&d,&st,0)&&d.state==1);hw=2;
    set_error=KEFI_ERROR(7);C(kbl_display_request(&d,&st,1)==set_error&&hw==2&&d.state==2);set_error=0;
    lie=1;C(kbl_display_request(&d,&st,1)==KEFI_ERROR(7)&&hw==2&&d.state==2);lie=0;
    get_error=KEFI_UNSUPPORTED;unsigned old=sets;C(kbl_display_request(&d,&st,1)==get_error&&sets==old&&!d.observed);get_error=0;
    invalid_get=1;C(kbl_display_request(&d,&st,1)==KEFI_ERROR(7)&&!d.observed&&sets==old+1);invalid_get=0;
    hw=0;d.saved_valid=0;C(!kbl_display_request(&d,&st,1)&&d.observed&&d.state==1&&!d.saved_valid);
    hw=0;C(kbl_display_request(&d,&st,0)==KEFI_UNSUPPORTED&&!d.observed);
    hw=2;old=sets;
    missing=1;C(kbl_display_request(&d,&st,1)==KEFI_UNSUPPORTED&&!d.provider&&!d.observed&&sets==old);missing=0;
    C(kbl_display_describe(&d,out,sizeof(out))&&has("display_provider=unsupported")&&has("display_state=unknown"));
    protocol.revision=1;C(!kbl_display_request(&d,&st,1)&&d.provider&&d.revision==1&&sets==old+1);
    protocol.revision=UINT32_MAX;C(!kbl_display_request(&d,&st,2)&&d.provider&&sets==old+2);protocol.revision=0x10000;old=sets;
    protocol.get=0;C(kbl_display_request(&d,&st,1)==KEFI_UNSUPPORTED&&sets==old);protocol.get=get;
    C(kbl_display_request(&d,&st,3)==KEFI_INVALID&&sets==old);
    C(!kbl_display_describe(&d,out,4)&&out[3]==0);
    C(kbl_power_shutdown(&st)==KEFI_UNSUPPORTED&&!resets);
    st.runtime_services=&rt;rt.reset=reset;rt.h.bytes=sizeof(rt);
    C(kbl_power_shutdown(&st)==KEFI_UNSUPPORTED&&!resets);
    rt.h.signature=UINT64_C(0x56524553544e5552);rt.h.bytes=0x68;
    C(kbl_power_shutdown(&st)==KEFI_UNSUPPORTED&&!resets);rt.h.bytes=sizeof(rt);
    C(kbl_power_shutdown(&st)==KEFI_ERROR(7)&&resets==1&&reset_kind==2&&!reset_status&&!reset_bytes&&!reset_data);
    C(!failed);return 0;
}
