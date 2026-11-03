/* SPDX-License-Identifier: MIT -- integer/text formatting; no float ABI. */
#include "internal.h"
struct sink {FILE*file;char*buffer;size_t capacity,total,used;char chunk[128];int error;};
static int flush(struct sink*s){if(s->file&&s->used){if(fwrite(s->chunk,1,s->used,s->file)!=s->used){s->error=1;return-1;}s->used=0;}return 0;}
static int emit(struct sink*s,char c){if(s->error)return-1;if(s->total==INT_MAX){errno=EOVERFLOW;s->error=1;return-1;}
    if(s->file){s->chunk[s->used++]=c;if(s->used==sizeof(s->chunk)&&flush(s))return-1;}
    else if(s->capacity&&s->total<s->capacity-1)s->buffer[s->total]=c;
    ++s->total;kbl_tick();return 0;
}
static void repeat(struct sink*s,char c,size_t n){
    if(s->error)return;
    if(n>(size_t)INT_MAX-s->total){errno=EOVERFLOW;s->error=1;return;}
    /* Truncated snprintf still counts the entire field. Do not spend billions
     * of iterations producing padding that has no destination. */
    if(!s->file){
        if(s->capacity&&s->total<s->capacity-1){size_t take=s->capacity-1-s->total;if(take>n)take=n;
            memset(s->buffer+s->total,c,take);}
        s->total+=n;kbl_tick();return;
    }
    while(n--&&!s->error)(void)emit(s,c);
}
static void span(struct sink*s,const char*p,size_t n){for(size_t i=0;i<n&&!s->error;++i)(void)emit(s,p[i]);}
static int bounded_number(const char**format){int n=0;while(**format>='0'&&**format<='9'){unsigned d=(unsigned)(*(*format)++-'0');if(n>(INT_MAX-(int)d)/10)return-1;n=n*10+(int)d;}return n;}
static int format(struct sink*s,const char*f,va_list args){
    if(!f){errno=EINVAL;return-1;}
    while(*f&&!s->error){if(*f!='%'){(void)emit(s,*f++);continue;}++f;
        int left=0,zero=0,plus=0,space=0,alt=0;
        for(;;){if(*f=='-')left=1;else if(*f=='0')zero=1;else if(*f=='+')plus=1;else if(*f==' ')space=1;else if(*f=='#')alt=1;else break;++f;}
        int width=0,precision=-1;
        if(*f=='*'){width=va_arg(args,int);++f;if(width==INT_MIN){errno=EOVERFLOW;return-1;}if(width<0){left=1;width=-width;}}
        else width=bounded_number(&f);
        if(width<0){errno=EOVERFLOW;return-1;}
        if(*f=='.'){++f;if(*f=='*'){precision=va_arg(args,int);++f;if(precision<0)precision=-1;}else{precision=bounded_number(&f);if(precision<0){errno=EOVERFLOW;return-1;}}}
        unsigned length=0;if(*f=='l'){length=1;++f;if(*f=='l'){length=2;++f;}}else if(*f=='z'){length=3;++f;}else if(*f=='h'){length=4;++f;if(*f=='h'){length=5;++f;}}
        char spec=*f;if(!spec){errno=EINVAL;return-1;}++f;
        if(spec=='%'||spec=='c'||spec=='s'){
            if(length){errno=EINVAL;return-1;}
            char c=spec=='%'?'%':spec=='c'?(char)va_arg(args,int):0;
            const char*p=spec=='s'?va_arg(args,const char*):&c;if(!p)p="(null)";
            size_t n=spec=='s'?(precision<0?strlen(p):strnlen(p,(size_t)precision)):1;
            size_t pad=(size_t)width>n?(size_t)width-n:0;if(!left)repeat(s,' ',pad);span(s,p,n);if(left)repeat(s,' ',pad);continue;
        }
        if(spec=='f'||spec=='F'||spec=='e'||spec=='E'||spec=='g'||spec=='G'||spec=='a'||spec=='A'){errno=ENOSYS;return-1;}
        if(spec!='d'&&spec!='i'&&spec!='u'&&spec!='x'&&spec!='X'&&spec!='o'&&spec!='p'){errno=EINVAL;return-1;}
        int sign=spec=='d'||spec=='i',negative=0;uint64_t value;
        if(spec=='p'){if(length){errno=EINVAL;return-1;}value=(uintptr_t)va_arg(args,void*);alt=1;}
        else if(sign){int64_t v=length==2?va_arg(args,long long):length==1?va_arg(args,long):length==3?va_arg(args,intptr_t):va_arg(args,int);if(length==4)v=(short)v;if(length==5)v=(signed char)v;negative=v<0;value=negative?UINT64_C(0)-(uint64_t)v:(uint64_t)v;}
        else{value=length==2?va_arg(args,unsigned long long):length==1?va_arg(args,unsigned long):length==3?va_arg(args,size_t):va_arg(args,unsigned int);if(length==4)value=(unsigned short)value;if(length==5)value=(unsigned char)value;}
        unsigned base=spec=='o'?8:spec=='x'||spec=='X'||spec=='p'?16:10;
        char digits[65],prefix[3];size_t count=0,prefix_n=0;uint64_t original=value;
        if(sign){if(negative)prefix[prefix_n++]='-';else if(plus)prefix[prefix_n++]='+';else if(space)prefix[prefix_n++]=' ';}
        if(alt&&base==16&&(original||spec=='p')){prefix[prefix_n++]='0';prefix[prefix_n++]=spec=='X'?'X':'x';}
        if(value||precision!=0)do{unsigned d=(unsigned)(value%base);digits[count++]=(char)(d<10?'0'+d:(spec=='X'?'A':'a')+d-10);value/=base;}while(value);
        if(alt&&base==8&&(!count||digits[count-1]!='0')&&precision<=(int)count)precision=(int)count+1;
        size_t zeros=precision>(int)count?(size_t)precision-count:0;
        size_t n=prefix_n+zeros+count,pad=(size_t)width>n?(size_t)width-n:0;
        if(!left&&(!zero||precision>=0))repeat(s,' ',pad);span(s,prefix,prefix_n);if(!left&&zero&&precision<0)repeat(s,'0',pad);repeat(s,'0',zeros);
        while(count&&!s->error)(void)emit(s,digits[--count]);if(left)repeat(s,' ',pad);
    }
    if(flush(s))return-1;return s->error?-1:(int)s->total;
}
int vsnprintf(char*buffer,size_t n,const char*f,va_list args){
    if(!buffer&&n){errno=EINVAL;return-1;}struct sink s={0};s.buffer=buffer;s.capacity=n;va_list copy;va_copy(copy,args);int rc=format(&s,f,copy);va_end(copy);
    if(n)buffer[s.total<n?s.total:n-1]=0;return rc;
}
int snprintf(char*b,size_t n,const char*f,...){va_list args;va_start(args,f);int rc=vsnprintf(b,n,f,args);va_end(args);return rc;}
int vfprintf(FILE*file,const char*f,va_list args){if(!file){errno=EINVAL;return-1;}struct sink s={0};s.file=file;va_list copy;va_copy(copy,args);int rc=format(&s,f,copy);va_end(copy);return rc;}
int fprintf(FILE*file,const char*f,...){va_list args;va_start(args,f);int rc=vfprintf(file,f,args);va_end(args);return rc;}
int vprintf(const char*f,va_list args){return vfprintf(stdout,f,args);}
int printf(const char*f,...){va_list args;va_start(args,f);int rc=vfprintf(stdout,f,args);va_end(args);return rc;}
