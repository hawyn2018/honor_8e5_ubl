/* SPDX-License-Identifier: MIT */
#include "internal.h"
#include "include/fcntl.h"
#include "include/unistd.h"
#include "include/sys/stat.h"
/* FILE addresses stay stable; capacity is allocator-owned rather than a table. */
struct descriptor {struct descriptor *next;kbl_file_handle handle;int fd,pending;unsigned flags;};
struct stream_node {struct stream_node *next;FILE stream;};
static struct descriptor *descriptors;
static struct stream_node *streams;
static FILE standard_streams[3];
FILE *stdin=&standard_streams[0],*stdout=&standard_streams[1],*stderr=&standard_streams[2];
void kbl_io_reset(void){descriptors=0;streams=0;memset(standard_streams,0,sizeof(standard_streams));for(int i=0;i<3;++i){standard_streams[i].fd=i;standard_streams[i].used=1;}}
static int error(int code){errno=code;return-1;}
static int status(int rc){if(!kbl_check())return-1;if(rc>=0)return rc?error(EIO):0;return error(-rc);}
static struct descriptor *find_descriptor(int fd){for(struct descriptor*d=descriptors;d;d=d->next)if(d->fd==fd)return d;return 0;}
static struct descriptor *descriptor(int fd){struct descriptor*d=fd>=3?find_descriptor(fd):0;if(!d)errno=EBADF;return d;}
static int deferred(struct descriptor*d){if(!d->pending)return 0;int e=d->pending;d->pending=0;return error(e);}
int open(const char*path,int flags,...){
    if(!path)return error(EINVAL);if(!kbl_has_files())return-1;
    unsigned allowed=3|O_CREAT|O_EXCL|O_TRUNC|O_APPEND;if((flags&~(int)allowed)||(flags&3)==3)return error(EINVAL);
    unsigned f=(flags&3)==O_RDONLY?KBL_FILE_READ:(flags&3)==O_WRONLY?KBL_FILE_WRITE:KBL_FILE_READ|KBL_FILE_WRITE;
    if(flags&O_CREAT)f|=KBL_FILE_CREATE;if(flags&O_EXCL)f|=KBL_FILE_EXCL;if(flags&O_TRUNC)f|=KBL_FILE_TRUNC;if(flags&O_APPEND)f|=KBL_FILE_APPEND;
    int fd=3;while(find_descriptor(fd)){if(fd==INT_MAX)return error(EMFILE);++fd;}
    struct descriptor*d=malloc(sizeof(*d));if(!d)return-1;
    kbl_file_handle h=0;int rc=kbl_api->file_open(kbl_api->host,path,f,&h);if(status(rc)){free(d);return-1;}
    if(!h){free(d);return error(EIO);}*d=(struct descriptor){descriptors,h,fd,0,f};descriptors=d;return fd;
}
ssize_t read(int fd,void*buffer,size_t n){
    if((!buffer&&n)||n>INTPTR_MAX)return error(EINVAL);if(fd==0)return error(ENOSYS);
    struct descriptor*d=descriptor(fd);if(!d||!kbl_has_files()||deferred(d))return-1;
    size_t done=0;int rc=kbl_api->file_read(kbl_api->host,d->handle,buffer,n,&done);
    if(!kbl_check())return-1;if(done>n)return error(EIO);if(rc){int e=rc<0?-rc:EIO;if(done){d->pending=e;errno=e;return(ssize_t)done;}return error(e);}
    if(!kbl_check())return-1;return(ssize_t)done;
}
ssize_t write(int fd,const void*buffer,size_t n){
    if((!buffer&&n)||n>INTPTR_MAX)return error(EINVAL);if(!kbl_check())return-1;
    if(fd==1||fd==2){if(kbl_api->write(kbl_api->host,buffer,n)||!kbl_check())return error(EIO);return(ssize_t)n;}
    struct descriptor*d=descriptor(fd);if(!d||!kbl_has_files()||deferred(d))return-1;
    size_t done=0;int rc=kbl_api->file_write(kbl_api->host,d->handle,buffer,n,&done);
    if(!kbl_check())return-1;if(done>n)return error(EIO);if(rc){int e=rc<0?-rc:EIO;if(done){d->pending=e;errno=e;return(ssize_t)done;}return error(e);}
    if(!kbl_check())return-1;return(ssize_t)done;
}
off_t lseek(int fd,off_t offset,int whence){
    struct descriptor*d=descriptor(fd);if(!d||!kbl_has_files())return-1;if(whence<0||whence>2)return error(EINVAL);
    uint64_t position=0;int rc=kbl_api->file_seek(kbl_api->host,d->handle,offset,(unsigned)whence,&position);
    if(status(rc))return-1;if(position>INT64_MAX)return error(EOVERFLOW);return(off_t)position;
}
int fsync(int fd){struct descriptor*d=descriptor(fd);if(!d||!kbl_has_files())return-1;return status(kbl_api->file_sync(kbl_api->host,d->handle));}
int close(int fd){
    struct descriptor*d=descriptor(fd);if(!d||!kbl_has_files())return-1;
    kbl_file_handle h=d->handle;int pending=d->pending;
    struct descriptor**link=&descriptors;while(*link!=d)link=&(*link)->next;*link=d->next;free(d);
    int rc=kbl_api->file_close(kbl_api->host,h);if(status(rc))return-1;return pending?error(pending):0;
}
int kbl_io_close_all(void){int result=0;while(descriptors){int fd=descriptors->fd;if(close(fd)){
    result=-1;/* On lost EL/API access close leaves the descriptor unconsumed. */
    if(descriptors&&descriptors->fd==fd){struct descriptor*d=descriptors;descriptors=d->next;free(d);}
}}while(streams){struct stream_node*n=streams;streams=n->next;free(n);}return result;}
int stat(const char*path,struct stat*out){
    if(!path||!out)return error(EINVAL);if(!kbl_has_files())return-1;struct kbl_file_stat s={0};
    if(status(kbl_api->file_stat(kbl_api->host,path,&s)))return-1;
    if(s.bytes>INT64_MAX)return error(EOVERFLOW);
    mode_t type=s.type==KBL_FILE_REGULAR?S_IFREG:s.type==KBL_FILE_DIRECTORY?S_IFDIR:s.type==KBL_FILE_BLOCK?S_IFBLK:0;
    if(!type)return error(EIO);*out=(struct stat){(off_t)s.bytes,type|(s.flags&KBL_FILE_READ_ONLY?0444u:0666u),s.block_bytes};return 0;
}
int mkdir(const char*path,mode_t mode){(void)mode;if(!path)return error(EINVAL);if(!kbl_has_files())return-1;return status(kbl_api->file_mkdir(kbl_api->host,path));}
int unlink(const char*path){if(!path)return error(EINVAL);if(!kbl_has_files())return-1;return status(kbl_api->file_unlink(kbl_api->host,path));}
int remove(const char*path){return unlink(path);}
pid_t fork(void){return error(ENOSYS);}int execve(const char*p,char*const a[],char*const e[]){(void)p;(void)a;(void)e;return error(ENOSYS);}
int usleep(unsigned n){(void)n;return error(ENOSYS);}unsigned sleep(unsigned n){errno=ENOSYS;return n;}
static int valid(FILE*f){if(!f){errno=EINVAL;return 0;}for(int i=0;i<3;++i)if(f==&standard_streams[i]&&f->used)return 1;for(struct stream_node*n=streams;n;n=n->next)if(f==&n->stream&&f->used)return 1;errno=EBADF;return 0;}
FILE *fopen(const char*path,const char*mode){
    if(!mode||!*mode){errno=EINVAL;return 0;}int flags=mode[0]=='r'?O_RDONLY:mode[0]=='w'?O_WRONLY|O_CREAT|O_TRUNC:mode[0]=='a'?O_WRONLY|O_CREAT|O_APPEND:-1;
    if(flags<0){errno=EINVAL;return 0;}unsigned seen=0;
    for(const char*p=mode+1;*p;++p){unsigned bit=*p=='+'?1:*p=='b'?2:*p=='x'?4:0;if(!bit||(seen&bit)||(*p=='x'&&mode[0]!='w')){errno=EINVAL;return 0;}seen|=bit;}
    if(seen&1)flags=(flags&~3)|O_RDWR;if(seen&4)flags|=O_EXCL;
    struct stream_node*n=malloc(sizeof(*n));if(!n)return 0;
    int fd=open(path,flags,0666);if(fd<0){free(n);return 0;}n->stream=(FILE){fd,1,0,0};n->next=streams;streams=n;return &n->stream;
}
int fclose(FILE*f){if(!valid(f))return EOF;if(f->fd<3){errno=EBADF;return EOF;}int fd=f->fd;struct stream_node**link=&streams;while(&(*link)->stream!=f)link=&(*link)->next;struct stream_node*n=*link;*link=n->next;free(n);return close(fd)?EOF:0;}
int fflush(FILE*f){if(!f){int rc=0;for(struct stream_node*n=streams;n;n=n->next)if(fflush(&n->stream))rc=EOF;return rc;}if(!valid(f))return EOF;if(f->fd<3)return 0;struct descriptor*d=descriptor(f->fd);if(!d){f->error=1;return EOF;}if(!(d->flags&KBL_FILE_WRITE))return 0;int rc=fsync(f->fd);if(rc)f->error=1;return rc?EOF:0;}
size_t fread(void*buffer,size_t size,size_t count,FILE*f){
    if(!valid(f))return 0;if(!buffer&&size&&count){f->error=1;errno=EINVAL;return 0;}if(size&&count>SIZE_MAX/size){f->error=1;errno=EOVERFLOW;return 0;}if(!size||!count)return 0;
    size_t total=size*count,done=0;while(done<total){ssize_t n=read(f->fd,(unsigned char*)buffer+done,total-done);if(n<0){f->error=1;break;}if(!n){f->eof=1;break;}done+=(size_t)n;struct descriptor*d=f->fd>=3?descriptor(f->fd):0;if(d&&d->pending){f->error=1;break;}}
    return done/size;
}
size_t fwrite(const void*buffer,size_t size,size_t count,FILE*f){
    if(!valid(f))return 0;if(!buffer&&size&&count){f->error=1;errno=EINVAL;return 0;}if(size&&count>SIZE_MAX/size){f->error=1;errno=EOVERFLOW;return 0;}if(!size||!count)return 0;
    size_t total=size*count,done=0;while(done<total){ssize_t n=write(f->fd,(const unsigned char*)buffer+done,total-done);if(n<=0){f->error=1;if(!n)errno=EIO;break;}done+=(size_t)n;struct descriptor*d=f->fd>=3?descriptor(f->fd):0;if(d&&d->pending){f->error=1;break;}}
    return done/size;
}
int fputc(int c,FILE*f){unsigned char b=(unsigned char)c;return fwrite(&b,1,1,f)==1?b:EOF;}
int putchar(int c){return fputc(c,stdout);}int fgetc(FILE*f){unsigned char b;return fread(&b,1,1,f)==1?b:EOF;}
int fputs(const char*s,FILE*f){size_t n=strlen(s);return fwrite(s,1,n,f)==n?0:EOF;}
int puts(const char*s){return fputs(s,stdout)||fputc('\n',stdout)==EOF?EOF:0;}
int fseeko(FILE*f,off_t off,int whence){if(!valid(f))return-1;if(lseek(f->fd,off,whence)<0){f->error=1;return-1;}f->eof=0;return 0;}
int fseek(FILE*f,long off,int whence){return fseeko(f,(off_t)off,whence);}
off_t ftello(FILE*f){return valid(f)?lseek(f->fd,0,SEEK_CUR):-1;}
long ftell(FILE*f){off_t n=ftello(f);if(n>LONG_MAX)return error(EOVERFLOW);return(long)n;}
void rewind(FILE*f){if(!fseeko(f,0,SEEK_SET))clearerr(f);}
int feof(FILE*f){return valid(f)?f->eof:0;}int ferror(FILE*f){return valid(f)?f->error:1;}
void clearerr(FILE*f){if(valid(f))f->error=f->eof=0;}
int fileno(FILE*f){return valid(f)?f->fd:-1;}
char *fgets(char*s,int n,FILE*f){if(!s||n<=0||!valid(f)){errno=EINVAL;return 0;}int at=0,c=0;while(at<n-1&&(c=fgetc(f))!=EOF){s[at++]=(char)c;if(c=='\n')break;}s[at]=0;return at||n==1?s:0;}
void perror(const char*s){int e=errno;if(s&&*s){fputs(s,stderr);fputs(": ",stderr);}fputs(strerror(e),stderr);fputc('\n',stderr);}
