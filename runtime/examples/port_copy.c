/* SPDX-License-Identifier: MIT -- explicit source/destination, no auto-flash. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <kbl_port.h>
static unsigned long long hash_update(unsigned long long h,const unsigned char*p,size_t n){
    for(size_t i=0;i<n;++i){h^=p[i];h*=1099511628211ULL;}return h;
}
int main(int argc,char**argv){
    if(argc!=3){fprintf(stderr,"usage: port_copy SOURCE DESTINATION\n");return 2;}
    if(!strcmp(argv[1],argv[2])){fprintf(stderr,"source and destination are identical\n");return 2;}
    int in=open(argv[1],O_RDONLY),out=-1,rc=1;
    if(in<0){perror("open source");return 1;}
    /* EXCL prevents accidental replacement of an existing file or partition.
     * Reading a partition source is supported; this example creates RAM files. */
    out=open(argv[2],O_WRONLY|O_CREAT|O_EXCL,0666);if(out<0){perror("create destination");close(in);return 1;}
    unsigned char*buffer=malloc(4096);if(!buffer){perror("malloc");goto done;}
    unsigned long long total=0,h=14695981039346656037ULL;
    for(;;){ssize_t n=read(in,buffer,4096);if(n<0){perror("read");goto free_buffer;}if(!n)break;
        h=hash_update(h,buffer,(size_t)n);size_t at=0;
        while(at<(size_t)n){ssize_t put=write(out,buffer+at,(size_t)n-at);if(put<=0){if(!put)errno=EIO;perror("write");goto free_buffer;}at+=(size_t)put;}
        total+=(unsigned long long)n;if(kbl_checkpoint())goto free_buffer;
    }
    if(fsync(out)){perror("fsync");goto free_buffer;}
    if(close(out)){out=-1;perror("close destination");goto free_buffer;}out=-1;
    int verify=open(argv[2],O_RDONLY);if(verify<0){perror("reopen destination");goto free_buffer;}
    unsigned long long checked=0,v=14695981039346656037ULL;int failed=0;
    for(;;){ssize_t n=read(verify,buffer,4096);if(n<0){failed=1;break;}if(!n)break;v=hash_update(v,buffer,(size_t)n);checked+=(unsigned long long)n;if(kbl_checkpoint()){failed=1;break;}}
    if(close(verify))failed=1;
    if(failed||checked!=total||v!=h){fprintf(stderr,"verification failed\n");goto free_buffer;}
    if(printf("copied=%llu fnv1a64=%016llx verified EL%u\n",total,h,kbl_current_el())<0)goto free_buffer;rc=0;
free_buffer:free(buffer);
done:if(out>=0&&close(out))rc=1;if(close(in))rc=1;return rc;
}
