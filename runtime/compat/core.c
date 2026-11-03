/* SPDX-License-Identifier: MIT -- independently implemented KBL C subset. */
#include "internal.h"
#include "include/ctype.h"
const struct kbl_program_api *kbl_api;
int errno;
static unsigned original_el,work;
static int failed;
static unsigned actual_el(void){
#if defined(__aarch64__)
    uint64_t raw;__asm__ volatile("mrs %0, CurrentEL":"=r"(raw));return(unsigned)((raw>>2)&3);
#else
    return kbl_api&&kbl_api->current_el?kbl_api->current_el(kbl_api->host):0;
#endif
}
int kbl_check(void){
    if(!kbl_api){errno=ENOSYS;return 0;}
    if(failed||actual_el()!=original_el||kbl_api->current_el(kbl_api->host)!=original_el){failed=1;errno=EIO;return 0;}
    return 1;
}
int kbl_has_files(void){
    if(!kbl_check())return 0;
    if(kbl_api->struct_bytes<KBL_PROGRAM_API_FILE_BYTES){errno=ENOSYS;return 0;}
    if(!kbl_api->file_open||!kbl_api->file_read||!kbl_api->file_write||!kbl_api->file_seek||!kbl_api->file_close||
       !kbl_api->file_stat||!kbl_api->file_mkdir||!kbl_api->file_unlink||!kbl_api->file_sync){errno=ENOSYS;return 0;}
    return 1;
}
int kbl_checkpoint(void){if(!kbl_check())return-1;if(kbl_api->yield(kbl_api->host,1000)){failed=1;errno=EIO;return-1;}return kbl_check()?0:-1;}
void kbl_tick(void){if(kbl_api&&++work>=1024){work=0;(void)kbl_checkpoint();}}
unsigned kbl_current_el(void){return kbl_check()?actual_el():0;}
int kbl_compat_enter(const struct kbl_program_api*a){
    kbl_api=0;errno=0;failed=0;work=0;
    if(!a||a->abi_version!=KBL_PROGRAM_ABI||a->struct_bytes<KBL_PROGRAM_API_BASE_BYTES||!a->write||!a->yield||!a->current_el){errno=EINVAL;return-1;}
    kbl_api=a;original_el=a->current_el(a->host);
    if((original_el!=1&&original_el!=2)||!kbl_check()){kbl_api=0;errno=EIO;return-1;}
    kbl_heap_reset();kbl_io_reset();return 0;
}
int kbl_compat_leave(void){int rc=kbl_io_close_all();if(!kbl_check())rc=-1;kbl_api=0;return rc;}

void *memcpy(void*d_,const void*s_,size_t n){unsigned char*d=d_;const unsigned char*s=s_;for(size_t i=0;i<n;++i){d[i]=s[i];kbl_tick();}return d_;}
void *memmove(void*d_,const void*s_,size_t n){unsigned char*d=d_;const unsigned char*s=s_;if((uintptr_t)d<(uintptr_t)s)return memcpy(d,s,n);while(n){--n;d[n]=s[n];kbl_tick();}return d_;}
void *memset(void*d_,int c,size_t n){unsigned char*d=d_;while(n--){*d++=(unsigned char)c;kbl_tick();}return d_;}
int memcmp(const void*a_,const void*b_,size_t n){const unsigned char*a=a_,*b=b_;for(size_t i=0;i<n;++i){kbl_tick();if(a[i]!=b[i])return(int)a[i]-(int)b[i];}return 0;}
void *memchr(const void*p_,int c,size_t n){const unsigned char*p=p_;for(size_t i=0;i<n;++i){kbl_tick();if(p[i]==(unsigned char)c)return(void*)(p+i);}return 0;}
size_t strlen(const char*s){size_t n=0;while(s[n]){++n;kbl_tick();}return n;}
size_t strnlen(const char*s,size_t cap){size_t n=0;while(n<cap&&s[n]){++n;kbl_tick();}return n;}
int strcmp(const char*a,const char*b){while(*a&&*a==*b){++a;++b;kbl_tick();}return(unsigned char)*a-(unsigned char)*b;}
int strncmp(const char*a,const char*b,size_t n){while(n--){kbl_tick();if(*a!=*b||!*a)return(unsigned char)*a-(unsigned char)*b;++a;++b;}return 0;}
char *strcpy(char*d,const char*s){char*r=d;for(;;){char c=*s++;*d++=c;kbl_tick();if(!c)break;}return r;}
char *strncpy(char*d,const char*s,size_t n){size_t i=0;for(;i<n&&s[i];++i){d[i]=s[i];kbl_tick();}for(;i<n;++i){d[i]=0;kbl_tick();}return d;}
char *strcat(char*d,const char*s){strcpy(d+strlen(d),s);return d;}
char *strncat(char*d,const char*s,size_t n){size_t i=strlen(d),j=0;while(j<n&&s[j]){d[i+j]=s[j];++j;kbl_tick();}d[i+j]=0;return d;}
char *strchr(const char*s,int c){for(;;++s){kbl_tick();if((unsigned char)*s==(unsigned char)c)return(char*)s;if(!*s)return 0;}}
char *strrchr(const char*s,int c){const char*r=0;do{if((unsigned char)*s==(unsigned char)c)r=s;kbl_tick();}while(*s++);return(char*)r;}
char *strstr(const char*s,const char*q){size_t n=strlen(q);if(!n)return(char*)s;for(;*s;++s){kbl_tick();if(!strncmp(s,q,n))return(char*)s;}return 0;}
char *strdup(const char*s){size_t n=strlen(s);if(n==SIZE_MAX){errno=ENOMEM;return 0;}char*d=malloc(n+1);if(d)memcpy(d,s,n+1);return d;}
char *strerror(int e){switch(e){case 0:return"Success";case EPERM:return"Operation not permitted";case ENOENT:return"No such file";case EIO:return"I/O error";case EBADF:return"Bad file descriptor";case ENOMEM:return"Out of memory";case EACCES:return"Access denied";case EBUSY:return"Resource busy";case EEXIST:return"File exists";case ENODEV:return"No such device";case ENOTDIR:return"Not a directory";case EISDIR:return"Is a directory";case EINVAL:return"Invalid argument";case EMFILE:return"Too many open files";case EFBIG:return"File too large";case ENOSPC:return"No space left";case EROFS:return"Read-only filesystem";case ERANGE:return"Result out of range";case ENAMETOOLONG:return"Name too long";case ENOSYS:return"Function not implemented";case ENOTEMPTY:return"Directory not empty";case EOVERFLOW:return"Value too large";default:return"Unknown error";}}
int isspace(int c){return c==' '||(c>='\t'&&c<='\r');}
int isdigit(int c){return c>='0'&&c<='9';}
int islower(int c){return c>='a'&&c<='z';}
int isupper(int c){return c>='A'&&c<='Z';}
int isalpha(int c){return islower(c)||isupper(c);}
int isalnum(int c){return isalpha(c)||isdigit(c);}
int isxdigit(int c){return isdigit(c)||(c>='a'&&c<='f')||(c>='A'&&c<='F');}
int isprint(int c){return c>=32&&c<=126;}
int tolower(int c){return isupper(c)?c+32:c;}
int toupper(int c){return islower(c)?c-32:c;}

#ifndef KBL_COMPAT_HEAP_BYTES
#define KBL_COMPAT_HEAP_BYTES 65536u
#endif
union block {struct{size_t size;union block*next;unsigned free;}h;long double alignment;unsigned char pad[32];};
static _Alignas(16) unsigned char heap[KBL_COMPAT_HEAP_BYTES];
static union block *first;
struct host_block {struct host_block *next;size_t bytes;long double alignment;};
static struct host_block *host_first;
static int host_memory(void){return kbl_api&&kbl_api->struct_bytes>=KBL_PROGRAM_API_MEMORY_BYTES&&kbl_api->memory_allocate&&kbl_api->memory_release;}
static struct host_block *host_block_of(void*p){for(struct host_block*b=host_first;b;b=b->next){if((void*)(b+1)==p)return b;kbl_tick();}return 0;}
_Static_assert(sizeof(union block)==32,"private heap header");
_Static_assert(sizeof(heap)>=sizeof(union block)+16,"private heap requires one usable block");
void kbl_heap_reset(void){host_first=0;first=(void*)heap;first->h.size=sizeof(heap)-sizeof(*first);first->h.next=0;first->h.free=1;}
static union block *block_of(void*p){for(union block*b=first;b;b=b->h.next){if((void*)(b+1)==p)return b;kbl_tick();}return 0;}
void *malloc(size_t n){
    if(host_memory()){
        if(!kbl_check())return 0;if(!n)n=1;
        if(n>SIZE_MAX-sizeof(struct host_block)){errno=ENOMEM;return 0;}
        size_t bytes=sizeof(struct host_block)+n;
        struct host_block*b=kbl_api->memory_allocate(kbl_api->host,bytes);
        if(!b){errno=ENOMEM;return 0;}
        if((uintptr_t)b&15u){(void)kbl_api->memory_release(kbl_api->host,b,bytes);errno=EIO;return 0;}
        b->bytes=bytes;b->next=host_first;host_first=b;return b+1;
    }
    if(!first)kbl_heap_reset();if(!n)n=1;if(n>SIZE_MAX-15){errno=ENOMEM;return 0;}n=(n+15)&~(size_t)15;
    for(union block*b=first;b;b=b->h.next){kbl_tick();if(!b->h.free||b->h.size<n)continue;
        if(b->h.size-n>=sizeof(*b)+16){union block*split=(void*)((unsigned char*)(b+1)+n);split->h.size=b->h.size-n-sizeof(*b);split->h.next=b->h.next;split->h.free=1;b->h.next=split;b->h.size=n;}
        b->h.free=0;return b+1;
    }errno=ENOMEM;return 0;
}
void free(void*p){if(!p)return;
    if(host_memory()){
        if(!kbl_check())return;struct host_block**at=&host_first;while(*at&&(void*)(*at+1)!=p){at=&(*at)->next;kbl_tick();}
        struct host_block*b=*at;if(!b){errno=EINVAL;return;}
        struct host_block*next=b->next;int rc=kbl_api->memory_release(kbl_api->host,b,b->bytes);
        if(rc){int64_t e=-(int64_t)rc;errno=e>0&&e<=INT_MAX?(int)e:EIO;return;}*at=next;return;
    }
    union block*b=block_of(p);if(!b||b->h.free){errno=EINVAL;return;}b->h.free=1;
    for(b=first;b&&b->h.next;){kbl_tick();if(b->h.free&&b->h.next->h.free){b->h.size+=sizeof(*b)+b->h.next->h.size;b->h.next=b->h.next->h.next;}else b=b->h.next;}}
void *calloc(size_t n,size_t s){if(s&&n>SIZE_MAX/s){errno=ENOMEM;return 0;}size_t bytes=n*s;void*p=malloc(bytes);if(p)memset(p,0,bytes);return p;}
void *realloc(void*p,size_t n){if(!p)return malloc(n);if(!n){free(p);return 0;}
    if(host_memory()){
        struct host_block*b=host_block_of(p);if(!b){errno=EINVAL;return 0;}size_t old=b->bytes-sizeof(*b);if(n<=old)return p;
        void*q=malloc(n);if(q){memcpy(q,p,old);free(p);}return q;
    }
    union block*b=block_of(p);if(!b||b->h.free){errno=EINVAL;return 0;}if(n<=b->h.size)return p;void*q=malloc(n);if(q){memcpy(q,p,b->h.size);free(p);}return q;}
static unsigned digit(unsigned char c){return c>='0'&&c<='9'?c-'0':c>='a'&&c<='z'?c-'a'+10:c>='A'&&c<='Z'?c-'A'+10:255;}
static unsigned long long parse_unsigned(const char*s,char**end,int base,int*negative){
    const char*start=s;while(isspace((unsigned char)*s))++s;*negative=*s=='-';if(*s=='+'||*s=='-')++s;
    if(base&& (base<2||base>36)){errno=EINVAL;if(end)*end=(char*)start;return 0;}
    if((base==0||base==16)&&s[0]=='0'&&(s[1]=='x'||s[1]=='X')&&digit((unsigned char)s[2])<16){base=16;s+=2;}
    if(!base)base=*s=='0'?8:10;const char*begin=s;unsigned long long value=0;int over=0;
    while(digit((unsigned char)*s)<(unsigned)base){unsigned d=digit((unsigned char)*s++);if(value>(ULLONG_MAX-d)/(unsigned)base)over=1;else if(!over)value=value*(unsigned)base+d;kbl_tick();}
    if(end)*end=(char*)(s==begin?start:s);if(over){errno=ERANGE;return ULLONG_MAX;}return value;
}
unsigned long long strtoull(const char*s,char**end,int base){int negative;int old=errno;errno=0;unsigned long long v=parse_unsigned(s,end,base,&negative);if(errno==ERANGE)return ULLONG_MAX;if(!errno)errno=old;return negative?0-v:v;}
long long strtoll(const char*s,char**end,int base){int negative;int old=errno;errno=0;unsigned long long v=parse_unsigned(s,end,base,&negative),limit=(unsigned long long)LLONG_MAX+(unsigned)negative;if(errno==ERANGE||v>limit){errno=ERANGE;return negative?LLONG_MIN:LLONG_MAX;}if(!errno)errno=old;return negative?(v==limit?LLONG_MIN:-(long long)v):(long long)v;}
unsigned long strtoul(const char*s,char**end,int base){int negative,old=errno;errno=0;unsigned long long v=parse_unsigned(s,end,base,&negative);if(errno==ERANGE||v>ULONG_MAX){errno=ERANGE;return ULONG_MAX;}if(!errno)errno=old;return negative?0UL-(unsigned long)v:(unsigned long)v;}
long strtol(const char*s,char**end,int base){long long v=strtoll(s,end,base);if(v>LONG_MAX){errno=ERANGE;return LONG_MAX;}if(v<LONG_MIN){errno=ERANGE;return LONG_MIN;}return(long)v;}
int atoi(const char*s){return(int)strtol(s,0,10);}long atol(const char*s){return strtol(s,0,10);}
void qsort(void*base,size_t n,size_t width,int(*cmp)(const void*,const void*)){
    if(!width||!cmp||(width&&n>SIZE_MAX/width)){errno=EINVAL;return;}unsigned char*p=base;
    for(size_t i=1;i<n;++i)for(size_t j=i;j&&cmp(p+(j-1)*width,p+j*width)>0;--j)for(size_t k=0;k<width;++k){unsigned char t=p[(j-1)*width+k];p[(j-1)*width+k]=p[j*width+k];p[j*width+k]=t;kbl_tick();}
}
void *bsearch(const void*key,const void*base,size_t n,size_t width,int(*cmp)(const void*,const void*)){
    if(!width||!cmp||(width&&n>SIZE_MAX/width)){errno=EINVAL;return 0;}const unsigned char*p=base;
    while(n){size_t mid=n/2;int r=cmp(key,p+mid*width);kbl_tick();if(!r)return(void*)(p+mid*width);if(r<0)n=mid;else{p+=(mid+1)*width;n-=mid+1;}}return 0;
}
int abs(int n){return n<0?-n:n;}long labs(long n){return n<0?-n:n;}long long llabs(long long n){return n<0?-n:n;}
char *getenv(const char*s){(void)s;return 0;}int setenv(const char*a,const char*b,int c){(void)a;(void)b;(void)c;errno=ENOSYS;return-1;}int unsetenv(const char*s){(void)s;errno=ENOSYS;return-1;}
int system(const char*s){if(!s)return 0;errno=ENOSYS;return-1;}
