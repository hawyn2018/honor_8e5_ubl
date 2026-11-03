/* SPDX-License-Identifier: MIT -- explicitly overwrite an existing file range. */
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <kbl_port.h>
static unsigned long long digest(unsigned long long h,const unsigned char*p,size_t n){
    while(n--){h^=*p++;h*=1099511628211ULL;}return h;
}
int main(int argc,char**argv){
    if(argc!=4||!strcmp(argv[1],argv[2])){fprintf(stderr,"usage: port_overwrite SOURCE EXISTING_DEST OFFSET\n");return 2;}
    char*end;errno=0;unsigned long long offset=strtoull(argv[3],&end,0);
    if(errno||!*argv[3]||*end||argv[3][0]=='-'||offset>0x7fffffffffffffffULL){fprintf(stderr,"invalid offset\n");return 2;}
    int in=open(argv[1],O_RDONLY),out=-1,rc=1;if(in<0){perror("source");return 1;}
    out=open(argv[2],O_RDWR);if(out<0){perror("existing destination");close(in);return 1;}
    off_t size=lseek(in,0,SEEK_END),capacity=lseek(out,0,SEEK_END);
    if(size<0||capacity<0||offset>(unsigned long long)capacity||(unsigned long long)size>(unsigned long long)capacity-offset){fprintf(stderr,"range exceeds existing size\n");goto cleanup;}
    if(lseek(in,0,SEEK_SET)<0||lseek(out,(off_t)offset,SEEK_SET)<0){perror("seek");goto cleanup;}
    unsigned char buffer[4096];unsigned long long copied=0,h=14695981039346656037ULL,v=h;
    while(copied<(unsigned long long)size){
        size_t want=(unsigned long long)size-copied>sizeof(buffer)?sizeof(buffer):(size_t)((unsigned long long)size-copied);
        ssize_t got=read(in,buffer,want);if(got<=0){perror("read");goto cleanup;}
        size_t at=0;while(at<(size_t)got){ssize_t put=write(out,buffer+at,(size_t)got-at);if(put<=0){perror("write");goto cleanup;}at+=(size_t)put;}
        h=digest(h,buffer,(size_t)got);copied+=(unsigned long long)got;if(kbl_checkpoint())goto cleanup;
    }
    if(fsync(out)||lseek(out,(off_t)offset,SEEK_SET)<0){perror("sync/verify seek");goto cleanup;}
    unsigned long long checked=0;
    while(checked<copied){size_t want=copied-checked>sizeof(buffer)?sizeof(buffer):(size_t)(copied-checked);ssize_t n=read(out,buffer,want);if(n<=0){perror("verify read");goto cleanup;}v=digest(v,buffer,(size_t)n);checked+=(unsigned long long)n;if(kbl_checkpoint())goto cleanup;}
    if(h!=v){fprintf(stderr,"verify mismatch\n");goto cleanup;}
    printf("overwritten=%llu offset=%llu verified EL%u\n",copied,offset,kbl_current_el());rc=0;
cleanup:if(out>=0&&close(out))rc=1;if(close(in))rc=1;return rc;
}
