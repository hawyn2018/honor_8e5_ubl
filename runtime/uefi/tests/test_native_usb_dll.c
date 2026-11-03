/* Synthetic provider for real stock Fastboot TCP interoperability. */
#include "test_native_usb.c"
int fixture_start(void){fresh();b.sender.max_bytes=65536;witness();return 0;}
void fixture_set_sink(int(*sink)(const void*,size_t)){wire_sink=sink;}
int fixture_response(int okay,const char*text){command();native_final_fixture(&b,okay,text);poll_native();return b.fault||b.ambiguous?-1:0;}
int fixture_fetch(const void*data,size_t bytes){
    command();if(kbl_native_usb_begin(&b))return -1;
    const struct kbl_firmware_link*l=kbl_native_usb_link(&b);if(!l||bytes>UINT32_MAX||l->data(l->opaque,(uint32_t)bytes))return -2;
    size_t sent=0;while(sent<bytes){size_t n=0;if(l->write(l->opaque,(const unsigned char*)data+sent,bytes-sent,&n)||!n||n>bytes-sent)return -3;sent+=n;}
    if(l->final(l->opaque,1,""))return -4;poll_native();return b.private_active||b.dma?-5:0;
}
int fixture_stop(uint64_t*out){
    if(!out)return -1;out[0]=allocations;out[1]=frees;out[2]=send_count;out[3]=rx_rearms;out[4]=enters;out[5]=leaves;
    wire_sink=0;return kbl_native_usb_stop_observing(&b)||!kbl_native_usb_can_release(&b)?-2:0;
}
