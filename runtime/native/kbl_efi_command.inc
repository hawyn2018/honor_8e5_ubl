/* SPDX-License-Identifier: MIT -- explicitly requested UEFI application. */
static void efi_hex(struct native*c,uint64_t value){
    static const char digits[]="0123456789abcdef";char out[18];out[0]='0';out[1]='x';
    for(unsigned i=0;i<16;++i)out[2+i]=digits[(value>>(60-i*4))&15];(void)emit(c,out,sizeof(out));
}
static int efi_options_unit(uint16_t*out,size_t*units,uint16_t value){
    /* LoadedImage.LoadOptionsSize is a 32-bit byte count, including NUL. */
    if(*units>=UINT32_MAX/sizeof(*out)-1)return -KBL_EOVERFLOW;if(out)out[*units]=value;++*units;return 0;
}
static int efi_options(struct native*c,uint16_t*out,size_t*bytes,int first){
    size_t units=0;
    for(int i=first;i<c->argc;++i){
        if(i>first&&efi_options_unit(out,&units,' '))return -KBL_ENAMETOOLONG;
        const unsigned char*p=(const unsigned char*)c->argv[i];unsigned quote=!*p;
        for(const unsigned char*q=p;*q;++q)if(*q==' '||*q=='\t'||*q=='"')quote=1;
        if(quote&&efi_options_unit(out,&units,'"'))return -KBL_ENAMETOOLONG;
        while(*p){uint32_t cp=*p++;unsigned remaining=0;
            if(cp>=0x80){uint32_t minimum;
                if(cp>=0xc2&&cp<=0xdf){cp&=31;remaining=1;minimum=0x80;}
                else if(cp>=0xe0&&cp<=0xef){cp&=15;remaining=2;minimum=0x800;}
                else if(cp>=0xf0&&cp<=0xf4){cp&=7;remaining=3;minimum=0x10000;}
                else return -KBL_EINVAL;
                for(unsigned j=0;j<remaining;++j){unsigned ch=*p;if((ch&0xc0)!=0x80)return -KBL_EINVAL;++p;cp=(cp<<6)|(ch&63);}
                if(cp<minimum||cp>0x10ffff||(cp>=0xd800&&cp<=0xdfff))return -KBL_EINVAL;
            }
            if(quote&&(cp=='"'||cp=='\\')&&efi_options_unit(out,&units,'\\'))return -KBL_ENAMETOOLONG;
            if(cp>0xffff){cp-=0x10000;if(efi_options_unit(out,&units,(uint16_t)(0xd800+(cp>>10))))return -KBL_ENAMETOOLONG;cp=0xdc00+(cp&1023);}
            if(efi_options_unit(out,&units,(uint16_t)cp))return -KBL_ENAMETOOLONG;
        }
        if(quote&&efi_options_unit(out,&units,'"'))return -KBL_ENAMETOOLONG;
    }
    if(out)out[units]=0;++units;*bytes=units*sizeof(*out);return 0;
}
static int efi_exit_text(struct native*c,const uint16_t*text,size_t n){
    for(size_t i=0;i<n;++i){uint32_t cp=text[i];if(!cp)break;
        if(cp>=0xd800&&cp<=0xdbff&&i+1<n&&text[i+1]>=0xdc00&&text[i+1]<=0xdfff){cp=0x10000+((cp-0xd800)<<10)+(text[++i]-0xdc00);}
        else if(cp>=0xd800&&cp<=0xdfff)cp=0xfffd;
        unsigned char b[4];size_t bytes;
        if(cp<0x80){b[0]=(unsigned char)cp;bytes=1;}
        else if(cp<0x800){b[0]=(unsigned char)(0xc0|(cp>>6));b[1]=(unsigned char)(0x80|(cp&63));bytes=2;}
        else if(cp<0x10000){b[0]=(unsigned char)(0xe0|(cp>>12));b[1]=(unsigned char)(0x80|((cp>>6)&63));b[2]=(unsigned char)(0x80|(cp&63));bytes=3;}
        else{b[0]=(unsigned char)(0xf0|(cp>>18));b[1]=(unsigned char)(0x80|((cp>>12)&63));b[2]=(unsigned char)(0x80|((cp>>6)&63));b[3]=(unsigned char)(0x80|(cp&63));bytes=4;}
        if(emit(c,b,bytes))return 1;
    }return 0;
}
static int efi_command(struct native*c){
    if(c->argc<2)return error(c,"usage: efi [-bl|--bootloader] PATH [ARGS...]");
    int path=1,bootloader=0;
    if(equal(c->argv[path],"-bl")||equal(c->argv[path],"--bootloader")){
        bootloader=1;++path;
        if(path>=c->argc||!c->argv[path][0])return error(c,"EFI bootloader option requires PATH");
        if(equal(c->argv[path],"-bl")||equal(c->argv[path],"--bootloader"))return error(c,"duplicate EFI bootloader option before PATH");
    }
    if(!c->p.efi_run&&!c->p.efi_run_full)return error(c,"UEFI application provider unavailable");
    if(c->io_owner||c->fs_busy)return error(c,"UEFI transaction busy");
    struct kbl_file_stat st;int rc=v_stat(c,c->argv[path],&st);
    if(rc||st.type!=KBL_FILE_REGULAR||!st.bytes||st.bytes>SIZE_MAX)return error(c,"EFI file missing, empty or not addressable");
    size_t options_bytes=0;rc=efi_options(c,0,&options_bytes,path+1);
    if(rc)return error(c,"EFI options invalid UTF-8 or not addressable");
    unsigned char*image=alloc_owned(c,(size_t)st.bytes);uint16_t*options=alloc_owned(c,options_bytes);
    if(!image||!options){free_owned(c,image);free_owned(c,options);return error(c,"EFI allocation failed");}
    rc=efi_options(c,options,&options_bytes,path+1);
    if(rc){free_owned(c,image);free_owned(c,options);return error(c,"EFI options invalid UTF-8 or not addressable");}
    kbl_file_handle h=0;rc=v_open(c,c->argv[path],KBL_FILE_READ,&h);size_t at=0;
    while(!rc&&at<(size_t)st.bytes){size_t got=0;rc=v_read(c,h,image+at,(size_t)st.bytes-at,&got);at+=got;if(!got&&!rc)rc=-KBL_EIO;}
    if(h){int closed=v_close(c,h);if(!rc)rc=closed;}
    if(rc){free_owned(c,image);free_owned(c,options);return error(c,"EFI file read failed");}
    if(bootloader){
        struct kbl_efi_bootloader_result prepared;zero(&prepared,sizeof(prepared));
        rc=kbl_efi_force_bootloader(image,(size_t)st.bytes,&prepared);
        if(rc){free_owned(c,image);free_owned(c,options);text(c,"EFI bootloader preparation: ");return error(c,kbl_efi_bootloader_error(rc));}
        text(c,"EFI bootloader RAM override offset=");number(c,prepared.file_offset);text(c," RVA=");efi_hex(c,prepared.load_rva);
        text(c," before=");efi_hex(c,prepared.before);text(c," after=");efi_hex(c,prepared.after);
        text(c,prepared.already_forced?" already-forced\n":" prepared\n");
    }
    struct kbl_efi_run_full_result full;zero(&full,sizeof(full));
    struct kbl_efi_run_result*result=&full.result;
    /* No USB polling, yielding or filesystem library reentry across firmware. */
    ++c->fs_busy;
    if(c->p.efi_run_full)rc=c->p.efi_run_full(c->p.opaque,image,(size_t)st.bytes,options,options_bytes,&full);
    else rc=c->p.efi_run(c->p.opaque,image,(size_t)st.bytes,options,options_bytes,result);
    --c->fs_busy;
    free_owned(c,image);free_owned(c,options);
    int outcome=v_el(c)?126:((rc||result->status>>63)?1:0);
    if(outcome!=126){
        text(c,"EFI stage=");number(c,result->stage);text(c," status=");efi_hex(c,result->status);
        text(c," cleanup=");efi_hex(c,result->cleanup_status);text(c," EL=");number(c,result->el_before);text(c,"->");number(c,result->el_after);text(c,"\n");
        const uint16_t*exit=full.exit_text?full.exit_text:result->exit_text;
        size_t units=full.exit_text?full.exit_units:result->exit_units;
        if(!full.exit_text&&units>KBL_EFI_EXIT_UNITS)units=KBL_EFI_EXIT_UNITS;
        if(units){text(c,"EFI exit data: ");if(efi_exit_text(c,exit,units))outcome=1;
            if(!full.exit_text&&result->exit_truncated)text(c," [legacy provider truncated]");text(c,"\n");}
        if(rc){text(c,"EFI provider errno=");number(c,(uint64_t)(rc<0?-(int64_t)rc:rc));text(c,"\n");}
    }
    if(full.release_exit&&full.release_exit(full.exit_owner)){if(outcome!=126)error(c,"EFI exit cleanup pending");outcome=outcome==126?126:1;}
    return outcome;
}
