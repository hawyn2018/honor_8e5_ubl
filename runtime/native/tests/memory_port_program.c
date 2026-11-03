/* Default-build malloc uses optional host memory instead of private64KiB. */
#include <kbl_port.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
int main(int argc,char**argv){
    size_t n=16u*1024u*1024u+1u;
    if(argc>1&&argv[1][0]=='o'){errno=0;void*p=malloc(n);if(p||errno!=ENOMEM)return 109;return printf("compat host OOM passed\n")<0?110:0;}
    unsigned char*p=malloc(n),*q=malloc(70001);if(!p||!q)return 101;
    p[0]=0x55;p[n-1]=0xaa;q[0]=17;q[70000]=23;
    if(kbl_checkpoint()||p[0]!=0x55||p[n-1]!=0xaa)return 102;
    q=realloc(q,100003);if(!q||q[0]!=17||q[70000]!=23)return 103;
    unsigned char*z=calloc(32,1);if(!z)return 104;for(unsigned i=0;i<32;++i)if(z[i])return 105;
    free(z);free(q);free(p);errno=0;if(malloc(SIZE_MAX)||errno!=ENOMEM)return 106;
    q=malloc(70001);if(!q)return 107;q[0]=1; /* leaked deliberately for native cleanup */
    return printf("compat host memory passed\n")<0?108:0;
}
