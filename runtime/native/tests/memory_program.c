/* Raw SDK memory ownership and cancellation fixture. */
#include "kbl_program.h"
KBL_DECLARE_PROGRAM_WITH_API_BYTES(KBL_PROGRAM_API_MEMORY_BYTES);
int kbl_main(const struct kbl_program_api*a,void*opaque){
    if(a->struct_bytes<KBL_PROGRAM_API_MEMORY_BYTES||!a->memory_allocate||!a->memory_release)return 90;
    unsigned char*p=a->memory_allocate(a->host,256);if(!p||((uintptr_t)p&15))return 91;
    if(!a->memory_release(a->host,p+1,255)||!a->memory_release(a->host,p,255)||!a->memory_release(a->host,opaque,256))return 92;
    if(a->memory_release(a->host,p,256)||!a->memory_release(a->host,p,256))return 93;
    if(a->memory_allocate(a->host,SIZE_MAX))return 98;
    size_t n=1024u*1024u+1u;p=a->memory_allocate(a->host,n);
    if(a->argc>1&&a->argv[1][0]=='o')return p?99:a->write(a->host,"SDK memory OOM passed\n",22);
    if(!p)return 94;p[0]=0x55;p[n-1]=0xaa;
    if(a->yield(a->host,0)||p[0]!=0x55||p[n-1]!=0xaa)return 95;
    if(a->argc>1&&a->argv[1][0]=='h'){
        if(a->write(a->host,"holding memory\n",15))return 96;
        for(;;)if(a->yield(a->host,0)||p[0]!=0x55||p[n-1]!=0xaa)return 97;
    }
    return a->write(a->host,"SDK memory passed\n",18);
    /* p intentionally stays live; native invocation teardown owns it. */
}
