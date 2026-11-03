/* SPDX-License-Identifier: MIT -- explicit callback models, not phone I/O. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <kbl_port.h>
#include <limits.h>
#include <malloc.h>
extern int _write(int,const void *,unsigned);
static unsigned checks,yields,closes,reads,writes,syncs,opens;
static char output[4096];static size_t output_bytes;
static unsigned char file[256];static size_t file_size;static uint64_t position;
static int partial_read,partial_write,close_error;static unsigned el=1;
#define CHECK(x) do{++checks;if(!(x))return __LINE__;}while(0)
static unsigned current(void*p){(void)p;return el;}
static int emit(void*p,const void*b,size_t n){(void)p;if(n>sizeof(output)-output_bytes)return 1;memcpy(output+output_bytes,b,n);output_bytes+=n;return 0;}
static int yield(void*p,unsigned n){(void)p;(void)n;++yields;return 0;}
static int f_open(void*p,const char*path,unsigned flags,kbl_file_handle*h){(void)p;++opens;if(strcmp(path,"/file"))return-KBL_ENOENT;if(flags&KBL_FILE_TRUNC)file_size=0;position=flags&KBL_FILE_APPEND?file_size:0;*h=0x100000001ULL;return 0;}
static int f_read(void*p,kbl_file_handle h,void*b,size_t n,size_t*done){(void)p;++reads;*done=0;if(h!=0x100000001ULL)return-KBL_EBADF;
    if(position>=file_size)return 0;if(n>file_size-position)n=file_size-(size_t)position;if(partial_read&&n>3)n=3;memcpy(b,file+position,n);position+=n;*done=n;return partial_read?-KBL_EIO:0;}
static int f_write(void*p,kbl_file_handle h,const void*b,size_t n,size_t*done){(void)p;++writes;*done=0;if(h!=0x100000001ULL)return-KBL_EBADF;if(n>sizeof(file)-position)return-KBL_ENOSPC;
    if(partial_write&&n>3)n=3;memcpy(file+position,b,n);position+=n;if(position>file_size)file_size=(size_t)position;*done=n;return partial_write?-KBL_EIO:0;}
static int f_seek(void*p,kbl_file_handle h,int64_t off,unsigned whence,uint64_t*out){(void)p;if(h!=0x100000001ULL)return-KBL_EBADF;int64_t base=whence==0?0:whence==1?(int64_t)position:(int64_t)file_size;if(off<0&&off< -base)return-KBL_EINVAL;if(off>0&&base>INT64_MAX-off)return-KBL_EOVERFLOW;position=(uint64_t)(base+off);*out=position;return 0;}
static int f_close(void*p,kbl_file_handle h){(void)p;(void)h;++closes;return close_error?-KBL_EIO:0;}
static int f_stat(void*p,const char*path,struct kbl_file_stat*s){(void)p;if(strcmp(path,"/file"))return-KBL_ENOENT;*s=(struct kbl_file_stat){file_size,KBL_FILE_REGULAR,0,1,0};return 0;}
static int f_path(void*p,const char*s){(void)p;(void)s;return 0;}
static int f_sync(void*p,kbl_file_handle h){(void)p;(void)h;++syncs;return 0;}
static struct kbl_program_api api={KBL_PROGRAM_ABI,sizeof(api),0,emit,yield,current,0,0,0,f_open,f_read,f_write,f_seek,f_close,f_stat,f_path,f_path,f_sync,0,0};
static unsigned host_live,host_alloc_fail,host_release_calls;static int host_release_error;
static void*host_memory_allocate(void*x,size_t n){(void)x;if(host_alloc_fail)return 0;void*p=_aligned_malloc(n,16);if(p)++host_live;return p;}
static int host_memory_release(void*x,void*p,size_t n){(void)x;(void)n;++host_release_calls;if(host_release_error)return host_release_error;if(!p||!host_live)return-KBL_EINVAL;--host_live;_aligned_free(p);return 0;}
static int compare(const void*a,const void*b){return*(const int*)a-*(const int*)b;}
int main(void){
    CHECK(kbl_compat_enter(&api)==0);CHECK(kbl_current_el()==1);
    char b[128];CHECK(snprintf(b,sizeof(b),"%s %d %llu %#x %08x %.3s %zu","abc",-42,18446744073709551615ULL,0x2au,0x12u,"abcdef",(size_t)9)==48);
    CHECK(!strcmp(b,"abc -42 18446744073709551615 0x2a 00000012 abc 9"));
    CHECK(snprintf(b,4,"abcdef")==6&&!strcmp(b,"abc"));CHECK(snprintf(0,0,"%lld",LLONG_MIN)==20);
    CHECK(snprintf(b,sizeof(b),"%+.0d %#.0o %*.*s",0,0u,-5,2,"abcd")==9&&!strcmp(b,"+ 0 ab   "));
    CHECK(snprintf(b,sizeof(b),"%.10000010s","x")==1&&!strcmp(b,"x"));
    CHECK(snprintf(b,sizeof(b),"%1000001s","x")==1000001&&b[0]==' '&&b[sizeof(b)-1]==0);
    CHECK(snprintf(b,sizeof(b),"%.1000001d",1)==1000001&&b[0]=='0'&&b[sizeof(b)-1]==0);
    CHECK(snprintf(0,0,"%2147483647s","")==INT_MAX);
    CHECK(snprintf(0,0,"%.2147483647d",1)==INT_MAX);
    errno=0;CHECK(snprintf(b,sizeof(b),"%2147483648s","x")==-1&&errno==EOVERFLOW);
    errno=0;CHECK(snprintf(b,sizeof(b),"%.2147483648d",1)==-1&&errno==EOVERFLOW);
    errno=0;CHECK(snprintf(0,0,"%2147483647sx","")==-1&&errno==EOVERFLOW);
    errno=0;CHECK(snprintf(0,0,"%.2147483647d",-1)==-1&&errno==EOVERFLOW);
    errno=0;CHECK(snprintf(0,0,"%*s",INT_MIN,"x")==-1&&errno==EOVERFLOW);
    errno=0;CHECK(snprintf(b,sizeof(b),"%f",1)==-1&&errno==ENOSYS);
    char*end=0;errno=0;CHECK(strtoull("18446744073709551615",&end,10)==ULLONG_MAX&&!*end&&!errno);
    errno=0;CHECK(strtoull("18446744073709551616",&end,10)==ULLONG_MAX&&errno==ERANGE);
    errno=0;CHECK(strtoll("-9223372036854775808",&end,10)==LLONG_MIN&&!*end&&!errno);
    errno=0;CHECK(strtoll("9223372036854775808",&end,10)==LLONG_MAX&&errno==ERANGE);
    errno=0;CHECK(strtoul("-1",&end,10)==ULONG_MAX&&!*end&&!errno);
    CHECK(strtol("  -0x7fZ",&end,0)==-127&&*end=='Z');CHECK(strtol("xyz",&end,0)==0&&*end=='x');
    void*a=malloc(12000),*c=malloc(12000);CHECK(a&&c&&a!=c&&((uintptr_t)a&15)==0);memset(a,0xa5,12000);a=realloc(a,20000);CHECK(a&&((unsigned char*)a)[11999]==0xa5);free(a);free(c);
    a=malloc(64000);CHECK(a);free(a);errno=0;CHECK(!calloc(SIZE_MAX,2)&&errno==ENOMEM);a=malloc(1);free(a);errno=0;free(a);CHECK(errno==EINVAL);
#if defined(KBL_COMPAT_HEAP_BYTES) && KBL_COMPAT_HEAP_BYTES>16u*1024u*1024u
    size_t big=16u*1024u*1024u+1u;a=malloc(big);CHECK(a);((unsigned char*)a)[0]=0x55;((unsigned char*)a)[big-1]=0xaa;
    CHECK(((unsigned char*)a)[0]==0x55&&((unsigned char*)a)[big-1]==0xaa);free(a);
    a=malloc(KBL_COMPAT_HEAP_BYTES-64);CHECK(a);errno=0;CHECK(!malloc(64)&&errno==ENOMEM);free(a);
    a=malloc(KBL_COMPAT_HEAP_BYTES-32);CHECK(a);free(a);errno=0;CHECK(!malloc(SIZE_MAX)&&errno==ENOMEM);
#endif
    char s[32]="abcd";memmove(s+1,s,4);CHECK(!memcmp(s,"aabcd",5));strcpy(s,"x");strcat(s,"yz");CHECK(!strcmp(s,"xyz")&&strchr(s,'y')==s+1&&strrchr(s,0)==s+3&&strstr(s,"yz")==s+1);
    CHECK(isspace('\n')&&isalpha('Z')&&!isalpha(-1)&&tolower('A')=='a'&&toupper('q')=='Q');
    int sorted[]={5,-1,9,0};qsort(sorted,4,sizeof(int),compare);int key=5;CHECK(sorted[0]==-1&&sorted[3]==9&&bsearch(&key,sorted,4,sizeof(int),compare)==sorted+2);
    int fd=open("/file",O_RDWR|O_TRUNC);CHECK(fd>=3);CHECK(write(fd,"abcdef",6)==6&&file_size==6);CHECK(lseek(fd,-3,SEEK_END)==3);CHECK(read(fd,b,9)==3&&!memcmp(b,"def",3));CHECK(read(fd,b,1)==0);CHECK(fsync(fd)==0&&syncs==1);CHECK(close(fd)==0);CHECK(close(fd)==-1&&errno==EBADF);
    struct stat st;CHECK(stat("/file",&st)==0&&st.st_size==6&&S_ISREG(st.st_mode));
    partial_read=1;fd=open("/file",O_RDONLY);unsigned prior=reads;CHECK(read(fd,b,6)==3);CHECK(read(fd,b,3)==-1&&errno==EIO&&reads==prior+1);partial_read=0;CHECK(close(fd)==0);
    partial_write=1;fd=open("/file",O_WRONLY|O_TRUNC);prior=writes;CHECK(write(fd,"abcdef",6)==3);CHECK(write(fd,"def",3)==-1&&errno==EIO&&writes==prior+1&&file_size==3);partial_write=0;CHECK(close(fd)==0);
    FILE*f=fopen("/file","w+");CHECK(f);CHECK(fwrite("line1\nline2",1,11,f)==11);CHECK(fseeko(f,0,SEEK_SET)==0);CHECK(fgets(b,sizeof(b),f)==b&&!strcmp(b,"line1\n"));CHECK(ftello(f)==6);CHECK(fread(b,1,64,f)==5&&feof(f));clearerr(f);CHECK(!feof(f)&&!ferror(f));CHECK(fclose(f)==0);
    f=fopen("/file","r");CHECK(f);prior=syncs;CHECK(fflush(f)==0&&syncs==prior);errno=0;CHECK(fread(0,1,1,f)==0&&ferror(f)&&errno==EINVAL);CHECK(fclose(f)==0);
    close_error=1;fd=open("/file",O_RDONLY);CHECK(fd>=3);prior=closes;
    CHECK(close(fd)==-1&&errno==EIO&&closes==prior+1);CHECK(close(fd)==-1&&errno==EBADF&&closes==prior+1);
    close_error=0;int consumed=fd;fd=open("/file",O_RDONLY);CHECK(fd==consumed&&close(fd)==0);
    f=fopen("/file","r");CHECK(f);close_error=1;prior=closes;CHECK(fclose(f)==EOF&&errno==EIO&&closes==prior+1);
    CHECK(fclose(f)==EOF&&errno==EBADF&&closes==prior+1);close_error=0;
    /* These live records cross both former table limits without relocating FILE*. */
    int manyfd[80];FILE*manyfile[40];
    for(unsigned i=0;i<80;++i){manyfd[i]=open("/file",O_RDONLY);CHECK(manyfd[i]>=3);for(unsigned j=0;j<i;++j)CHECK(manyfd[i]!=manyfd[j]);}
    for(unsigned i=0;i<40;++i){manyfile[i]=fopen("/file","r");CHECK(manyfile[i]);for(unsigned j=0;j<i;++j)CHECK(manyfile[i]!=manyfile[j]);}
    CHECK(fileno(manyfile[0])==83&&fseek(manyfile[0],0,SEEK_SET)==0);
    CHECK(close(manyfd[1])==0);int reused=open("/file",O_RDONLY);CHECK(reused==manyfd[1]);manyfd[1]=reused;
    CHECK(fclose(manyfile[1])==0);manyfile[1]=fopen("/file","r");CHECK(manyfile[1]&&fileno(manyfile[1])==84);
    prior=syncs;CHECK(fflush(0)==0&&syncs==prior);/* read-only streams need no sync */
    for(unsigned i=0;i<80;++i)CHECK(close(manyfd[i])==0);
    for(unsigned i=0;i<40;++i)CHECK(fclose(manyfile[i])==0);
    unsigned oldopens=opens;a=malloc(KBL_COMPAT_HEAP_BYTES-64);CHECK(a);errno=0;
    CHECK(open("/file",O_RDONLY)==-1&&errno==ENOMEM&&opens==oldopens);errno=0;
    CHECK(!fopen("/file","r")&&errno==ENOMEM&&opens==oldopens);free(a);
    CHECK(fprintf(stdout,"ok %u\n",7u)==5&&output_bytes==5&&!memcmp(output,"ok 7\n",5));
    CHECK(fork()==-1&&errno==ENOSYS);CHECK(execve("x",0,0)==-1&&errno==ENOSYS);CHECK(system(0)==0);CHECK(system("x")==-1&&errno==ENOSYS);CHECK(usleep(1)==-1&&errno==ENOSYS);
    fd=open("/file",O_RDONLY);CHECK(fd>=3);prior=closes;CHECK(kbl_compat_leave()==0&&closes==prior+1);
    api.memory_allocate=host_memory_allocate;api.memory_release=host_memory_release;
    CHECK(kbl_compat_enter(&api)==0);a=malloc(70001);CHECK(a&&host_live==1&&((uintptr_t)a&15)==0);((unsigned char*)a)[70000]=0x55;
    host_alloc_fail=1;CHECK(!malloc(1024*1024)&&errno==ENOMEM&&host_live==1&&((unsigned char*)a)[70000]==0x55);host_alloc_fail=0;
    host_release_error=INT_MIN;prior=host_release_calls;free(a);CHECK(errno==EIO&&host_live==1&&host_release_calls==prior+1);
    host_release_error=0;free(a);CHECK(!host_live);prior=host_release_calls;errno=0;free(a);CHECK(errno==EINVAL&&host_release_calls==prior);
    CHECK(kbl_compat_leave()==0);api.memory_allocate=0;api.memory_release=0;
    api.struct_bytes=64;CHECK(kbl_compat_enter(&api)==0);prior=reads;CHECK(open("/file",O_RDONLY)==-1&&errno==ENOSYS&&reads==prior);CHECK(printf("old ABI\n")==8);CHECK(kbl_compat_leave()==0);
    api.struct_bytes=sizeof(api);CHECK(kbl_compat_enter(&api)==0);el=2;CHECK(kbl_current_el()==0&&errno==EIO);CHECK(kbl_compat_leave()==-1);el=1;
    CHECK(yields>0);int report=snprintf(b,sizeof(b),"HOST PASS checks=%u\n",checks);_write(1,b,(unsigned)report);return 0;
}
