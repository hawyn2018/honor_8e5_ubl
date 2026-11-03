/* SPDX-License-Identifier: MIT -- ordinary C main ported through KBL libc. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <errno.h>
#include <kbl_port.h>
int main(int argc,char**argv){
    if(printf("port_text EL%u argc=%d\n",kbl_current_el(),argc)<0)return 1;
    for(int i=1;i<argc;++i){char*s=strdup(argv[i]);if(!s){perror("strdup");return 2;}
        for(size_t j=0;s[j];++j){s[j]=(char)toupper((unsigned char)s[j]);if(kbl_checkpoint()){free(s);return 3;}}
        if(printf("%d: %s (%zu bytes)\n",i,s,strlen(s))<0){free(s);return 4;}free(s);
    }
    if(!getenv("KBL_NO_ENVIRONMENT")&&system("not-executed")==-1&&errno==ENOSYS){
        if(puts("unsupported process API: ENOSYS")<0)return 5;
    }else return 6;
    return 0;
}
