/* SPDX-License-Identifier: MIT -- ordinary C streaming SHA-256 utility. */
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <stdint.h>
#include <kbl_port.h>
#include "kbl_sha256.h"
int main(int argc,char**argv){
    if(argc<2){fprintf(stderr,"usage: port_hash FILE...\n");return 2;}
    unsigned char*buffer=malloc(4096);if(!buffer){perror("malloc");return 1;}int result=0;
    for(int i=1;i<argc;++i){
        FILE*f=fopen(argv[i],"rb");if(!f){perror(argv[i]);result=1;continue;}
        struct kbl_sha256 state;kbl_sha256_init(&state);uint64_t total=0;int failed=0;
        for(;;){size_t n=fread(buffer,1,4096,f);if(n){if(total>UINT64_MAX-n||kbl_sha256_update(&state,buffer,n)){failed=1;break;}total+=n;}
            if(ferror(f)){failed=1;break;}if(feof(f))break;if(!n||kbl_checkpoint()){failed=1;break;}
        }
        if(fclose(f))failed=1;
        unsigned char digest[32];char text[65];
        if(failed||kbl_sha256_final(&state,digest)){fprintf(stderr,"hash failed: %s\n",argv[i]);result=1;continue;}
        kbl_sha256_hex(digest,text);
        if(printf("%s  %s bytes=%llu EL%u\n",text,argv[i],(unsigned long long)total,kbl_current_el())<0){result=1;break;}
    }
    free(buffer);return result;
}
