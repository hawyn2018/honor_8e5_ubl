/* SPDX-License-Identifier: MIT */
#include "kbl_fetch.h"
static int number(const unsigned char*s,size_t n,uint64_t*out){
    uint64_t v=0;size_t i=0;if(n>=2&&s[0]=='0'&&(s[1]=='x'||s[1]=='X'))i=2;
    if(i==n)return 0;for(;i<n;++i){unsigned c=s[i],d=c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:99;
        if(d>=16||v>(UINT64_MAX-d)/16)return 0;v=v*16+d;}*out=v;return 1;
}
static int range_error(struct kbl_reader*r,const char**why,const char*message){
    if(r->close)r->close(r->opaque);*r=(struct kbl_reader){0};*why=message;return -1;
}
/* Opens a fully bounded source without payload I/O. DATA has a 32-bit length. */
static int open_range(const unsigned char*r,size_t n,const struct kbl_fetch_source*source,
                      struct kbl_reader*reader,uint64_t*offset,uint64_t*length,const char**why){
    static const char prefix[]="fetch:";size_t i,begin;
    if(!r||n<sizeof(prefix)-1)return 0;
    for(i=0;i<sizeof(prefix)-1;++i)if(r[i]!=(unsigned char)prefix[i])return 0;
    if(n>KBL_WIRE_BYTES||!source||!source->open){*why="Fetch unavailable or frame too long";return -1;}
    char name[KBL_WIRE_BYTES+1];begin=sizeof(prefix)-1;i=begin;
    while(i<n&&r[i]!=':'){unsigned c=r[i];if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='_'||c=='-'||c=='.')){
            *why="Invalid fetch source";return -1;}++i;}
    size_t count=i-begin;if(!count){*why="Missing fetch source";return -1;}
    for(size_t j=0;j<count;++j)name[j]=(char)r[begin+j];name[count]=0;
    int explicit_length=0;*offset=*length=0;
    if(i<n){begin=++i;while(i<n&&r[i]!=':')++i;if(!number(r+begin,i-begin,offset)){*why="Invalid fetch offset";return -1;}
        if(i<n){begin=++i;explicit_length=1;if(!number(r+begin,n-begin,length)){*why="Invalid fetch length";return -1;}}}
    if(source->open(source->opaque,name,reader)!=KBL_OK)return range_error(reader,why,"Fetch source unavailable");
    if(!reader->read_at||!reader->close)return range_error(reader,why,"Invalid fetch reader");
    if(*offset>reader->size||(explicit_length&&*length>reader->size-*offset))return range_error(reader,why,"Fetch outside source");
    if(!explicit_length)*length=reader->size-*offset;
    if(!*length||*length>UINT32_MAX||*length>SIZE_MAX)return range_error(reader,why,"Invalid fetch chunk size");
    return 1;
}
int kbl_fetch_capacity(const unsigned char*r,size_t n,const struct kbl_fetch_source*source,size_t*bytes){
    if(!bytes)return KBL_ERROR;*bytes=0;struct kbl_reader reader={0};uint64_t offset,length;const char*why=0;
    int status=open_range(r,n,source,&reader,&offset,&length,&why);if(status!=1)return KBL_ERROR;
    reader.close(reader.opaque);*bytes=(size_t)length;return KBL_OK;
}
int kbl_fetch(const unsigned char*r,size_t n,const struct kbl_fetch_source*source,
              const struct kbl_fetch_wire*wire,void*work,size_t cap){
    if(!wire||!wire->data||!wire->write||!wire->final||!wire->abort)return 0;
    if(!work||!cap){static const unsigned char prefix[]={'f','e','t','c','h',':'};if(!r||n<6)return 0;for(size_t i=0;i<6;++i)if(r[i]!=prefix[i])return 0;wire->final(wire->opaque,0,"Fetch unavailable or frame too long");return 1;}
    struct kbl_reader reader={0};uint64_t offset,length;const char*why=0;
    int status=open_range(r,n,source,&reader,&offset,&length,&why);if(!status)return 0;
    if(status<0){wire->final(wire->opaque,0,why);return 1;}
    if(!work||!cap||length>cap){reader.close(reader.opaque);wire->final(wire->opaque,0,"Invalid fetch chunk size");return 1;}
    if(kbl_read_exact(&reader,offset,work,(size_t)length)!=KBL_OK){reader.close(reader.opaque);wire->final(wire->opaque,0,"Fetch source read failed");return 1;}
    reader.close(reader.opaque);
    if(wire->data(wire->opaque,(uint32_t)length)!=KBL_OK){wire->abort(wire->opaque);return 1;}
    size_t sent=0;while(sent<length){size_t written=0;
        if(wire->write(wire->opaque,(unsigned char*)work+sent,(size_t)length-sent,&written)!=KBL_OK||!written||written>length-sent){wire->abort(wire->opaque);return 1;}
        sent+=written;}
    wire->final(wire->opaque,1,"");return 1;
}

