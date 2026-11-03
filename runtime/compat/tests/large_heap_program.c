/* Test program: real linked allocator, no firmware or filesystem operations. */
#include <kbl_port.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
int main(int argc,char **argv){
    (void)argc;(void)argv;size_t n=16u*1024u*1024u+1u;
    unsigned char*p=malloc(n);if(!p)return 11;
    p[0]=0x55;p[n-1]=0xaa;if(p[0]!=0x55||p[n-1]!=0xaa)return 12;
    free(p);errno=0;if(malloc(SIZE_MAX)||errno!=ENOMEM)return 13;
    return printf("large heap allocation passed\n")<0?14:0;
}
