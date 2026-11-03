/* Explicit host block/media/EL models. Production VFS/commands execute. */
#include "../kbl_native.c"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include <malloc.h>
static unsigned allocations,level=1,opens,closes,reads,writes,flushes,partial,partial_read,flip;
static unsigned checks,groups;
#define CHECK(x) do{++checks;assert(x);}while(0)
static unsigned char media[131072];
struct opened {unsigned access,ro,large;};
static char large_digest[65];
static uint64_t media_size(unsigned large){return large==1?UINT64_C(64)*1024*1024+31:large==2?(UINT64_C(1)<<33)+17:sizeof(media);}
static void*memory(void*p,size_t n,size_t a){(void)p;void*v=_aligned_malloc(n,a);if(v)++allocations;return v;}
static void release(void*p,void*v,size_t n){(void)p;(void)n;if(v){CHECK(allocations>0);--allocations;_aligned_free(v);}}
static unsigned current(void*p){(void)p;return level;}
static uint64_t now(void*p){(void)p;return 0;}
static int bread(void*p,uint64_t off,void*out,size_t n,size_t*done){struct opened*h=p;*done=0;++reads;
    if(!(h->access&KBL_BLOCK_READ)||off>media_size(h->large)||n>media_size(h->large)-off)return KBL_ERROR;
    if(partial_read&&n>partial_read)n=partial_read;
    if(h->large){for(size_t i=0;i<n;++i)((unsigned char*)out)[i]=(unsigned char)(off+i);}else copy(out,media+(size_t)off,n);
    *done=n;if(flip)level=2;return partial_read?KBL_ERROR:KBL_OK;}
static int bwrite(void*p,uint64_t off,const void*in,size_t n,size_t*done){struct opened*h=p;*done=0;++writes;
    if(h->ro||!(h->access&KBL_BLOCK_WRITE)||off>sizeof(media)||n>sizeof(media)-off)return KBL_ERROR;
    if(partial&&n>partial)n=partial;copy(media+(size_t)off,in,n);*done=n;return partial?KBL_ERROR:KBL_OK;}
static int bflush(void*p){struct opened*h=p;CHECK(h->access&KBL_BLOCK_WRITE);++flushes;return KBL_OK;}
static void bclose(void*p){++closes;free(p);}
static int bopen(void*p,const char*label,unsigned access,struct kbl_block*out){(void)p;zero(out,sizeof(*out));unsigned large=equal(label,"large")?1:equal(label,"giant")?2:0,ro=large||equal(label,"locked");
    if(!ro&&!equal(label,"test"))return KBL_ERROR;out->flags=ro?KBL_BLOCK_READ_ONLY:0;
    if(ro&&(access&KBL_BLOCK_WRITE))return KBL_ERROR;struct opened*h=malloc(sizeof(*h));CHECK(h);*h=(struct opened){access,ro,large};++opens;
    *out=(struct kbl_block){{h,media_size(large),bread,bclose},512,ro?KBL_BLOCK_READ_ONLY:0,bwrite,bflush};return KBL_OK;}
static int blist(void*p,struct kbl_block_info*out,size_t cap,size_t*count){(void)p;*count=2;if(!cap)return KBL_OK;if(cap<2)return KBL_ERROR;
    zero(out,2*sizeof(*out));copy(out[0].label,"test",5);copy(out[1].label,"locked",7);
    out[0].bytes=out[1].bytes=sizeof(media);out[0].block_bytes=out[1].block_bytes=512;out[1].flags=KBL_BLOCK_READ_ONLY;return KBL_OK;}
static void setup(struct native*c){zero(c,sizeof(*c));c->cookie=NATIVE_COOKIE;c->el=1;c->job_id=1;c->in_job=c->polling=1;c->deadline=UINT64_MAX;
    c->p=(struct kbl_native_platform){0};c->p.allocate=memory;c->p.release=release;c->p.current_el=current;c->p.now_us=now;
    c->p.max_file_bytes=1048576;c->p.max_ramfs_bytes=2097152;c->p.max_nodes=64;c->p.block_open=bopen;c->p.block_list=blist;
    c->root=new_node(c,0,"/",1);c->cwd=c->root;c->cwd_path=owned_string(c,"/");c->output_capacity=4096;c->output=alloc_owned(c,c->output_capacity);
    c->default_session.cookie=SESSION_COOKIE;c->default_session.c=c;c->default_session.cwd=c->root;c->default_session.cwd_path=c->cwd_path;c->default_session.output=c->output;c->sessions=&c->default_session;
    struct node*d=new_node(c,c->root,"dev",1),*b=new_node(c,d,"block",1),*n=new_node(c,b,"by-name",1);CHECK(n);d->reserved=1;b->reserved=2;n->reserved=3;
    opens=closes=reads=writes=flushes=partial=partial_read=flip=0;level=1;
}
static void finish(struct native*c){CHECK(!close_files(c));CHECK(opens==closes);while(c->owned)free_owned(c,c->owned+1);CHECK(!allocations);}
static int run(struct native*c,const char*line){
    c->line=alloc_owned(c,length(line)+1);copy(c->line,line,length(line)+1);CHECK(!parse(c));c->output_count=c->output_read=0;
    int rc=command(c);CHECK(!close_files(c));free_owned(c,c->scratch);c->scratch=0;zero(&c->scratch_node,sizeof(c->scratch_node));free_owned(c,c->line);c->line=0;free_owned(c,c->argv);c->argv=0;return rc;
}
static void ram(void){struct native c;setup(&c);kbl_file_handle h,h2;size_t n;char out[32];uint64_t pos;
    CHECK(!v_open(&c,"/hello",KBL_FILE_READ|KBL_FILE_WRITE|KBL_FILE_CREATE,&h));CHECK(!v_write(&c,h,"abc",3,&n)&&n==3);
    CHECK(!v_seek(&c,h,8,KBL_SEEK_SET,&pos)&&pos==8);CHECK(!v_write(&c,h,"Z",1,&n)&&n==1);
    CHECK(!v_seek(&c,h,0,KBL_SEEK_SET,&pos));CHECK(!v_read(&c,h,out,sizeof(out),&n)&&n==9&&out[0]=='a'&&out[3]==0&&out[8]=='Z');
    CHECK(v_unlink(&c,"/hello")==-KBL_EBUSY);CHECK(!v_close(&c,h));CHECK(v_close(&c,h)==-KBL_EBADF);
    CHECK(!v_open(&c,"/hello",KBL_FILE_READ,&h2)&&h2!=h);CHECK(v_read(&c,h,out,1,&n)==-KBL_EBADF);
    CHECK(!v_close(&c,h2));CHECK(!v_unlink(&c,"/hello"));CHECK(v_open(&c,"/hello",KBL_FILE_READ,&h)==-KBL_ENOENT);
    CHECK(v_open(&c,"/dev/block/by-name/../../bad",KBL_FILE_WRITE|KBL_FILE_CREATE,&h)==-KBL_EROFS);
    CHECK(!v_mkdir(&c,"/folder"));CHECK(v_mkdir(&c,"/folder")==-KBL_EEXIST);CHECK(!v_unlink(&c,"/folder"));
    finish(&c);++groups;
}
static void partitions(void){struct native c;setup(&c);kbl_file_handle h;size_t n;char data[32];uint64_t pos;struct kbl_file_stat stat;
    CHECK(!v_stat(&c,"/dev/block/by-name/test",&stat)&&stat.type==KBL_FILE_BLOCK&&stat.bytes==sizeof(media)&&stat.block_bytes==512);
    CHECK(v_open(&c,"/dev/block/by-name/locked",KBL_FILE_WRITE,&h)==-KBL_EROFS&&!h&&!writes);
    CHECK(!v_open(&c,"/dev/block/by-name/test",KBL_FILE_READ|KBL_FILE_WRITE,&h));CHECK(!v_write(&c,h,"payload",7,&n)&&n==7&&!flushes);
    CHECK(!v_seek(&c,h,-7,KBL_SEEK_CUR,&pos));CHECK(!v_read(&c,h,data,7,&n)&&n==7&&!memcmp(data,"payload",7));
    CHECK(!v_sync(&c,h)&&flushes==1);CHECK(!v_seek(&c,h,0,KBL_SEEK_END,&pos)&&pos==sizeof(media));
    CHECK(v_write(&c,h,"x",1,&n)==-KBL_ENOSPC&&n==0);CHECK(v_seek(&c,h,INT64_MAX,KBL_SEEK_CUR,&pos)==-KBL_EFBIG);
    CHECK(!v_close(&c,h)&&flushes==1);finish(&c);++groups;
}
static void partial_and_el(void){struct native c;setup(&c);kbl_file_handle h;size_t n;char out[32];
    CHECK(!v_open(&c,"/dev/block/by-name/test",KBL_FILE_READ|KBL_FILE_WRITE,&h));partial=3;
    CHECK(v_write(&c,h,"123456",6,&n)==-KBL_EIO&&n==3&&slot(&c,h)->position==3&&!flushes);
    partial=0;flip=1;CHECK(v_read(&c,h,out,6,&n)==-KBL_EIO&&n==6&&c.fault);
    unsigned before=reads;CHECK(v_read(&c,h,out,6,&n)==-KBL_EIO&&reads==before);level=1;flip=0;c.fault=0;
    CHECK(!close_files(&c));CHECK(v_read(&c,h,out,1,&n)==-KBL_EBADF);finish(&c);++groups;
}
static void generation_and_cleanup(void){struct native c;setup(&c);kbl_file_handle handles[65],extra;size_t n;char out;
    for(unsigned i=0;i<65;++i)CHECK(!v_open(&c,"/many",KBL_FILE_CREATE|KBL_FILE_READ|KBL_FILE_WRITE,&handles[i]));
    CHECK(!v_open(&c,"/many",KBL_FILE_READ,&extra));CHECK(!close_files(&c));++c.job_id;
    CHECK(v_read(&c,handles[0],&out,1,&n)==-KBL_EBADF);c.file_seed=UINT32_MAX;
    CHECK(!v_open(&c,"/many",KBL_FILE_READ,&extra)&&extra==(uint64_t)UINT32_MAX+1);CHECK(!v_close(&c,extra));
    c.in_job=0;CHECK(v_open(&c,"/many",KBL_FILE_READ,&extra)==-KBL_EBUSY);c.in_job=1;
    struct kbl_reader reader={0};unsigned before=opens;c.in_job=c.polling=0;
    CHECK(kbl_native_open_snapshot(&c,"/dev/block/by-name/test",&reader)==KBL_ERROR&&opens==before);c.in_job=c.polling=1;
    finish(&c);++groups;
}
static void commands(void){struct native c;setup(&c);for(size_t i=0;i<sizeof(media);++i)media[i]=(unsigned char)i;
    CHECK(!run(&c,"dd if=/dev/block/by-name/test of=/copy bs=64K count=2"));struct node*f=resolve(&c,"/copy",0,0);
    CHECK(f&&f->bytes==sizeof(media)&&!memcmp(f->data,media,sizeof(media))&&!writes&&!flushes);
    zero(media,sizeof(media));CHECK(!run(&c,"dd if=/copy of=/dev/block/by-name/test bs=512 count=3 skip=1 seek=2 conv=fsync"));
    CHECK(writes==3&&flushes==1&&!memcmp(media+1024,f->data+512,1536));
    CHECK(run(&c,"dd if=/copy of=/dev/block/by-name/locked count=1")==1);
    CHECK(run(&c,"dd if=/copy of=/copy count=1")==1&&f->bytes==sizeof(media));
    CHECK(run(&c,"dd if=/copy of=/x bs=2G skip=9223372036854775807")==1);
    CHECK(!run(&c,"sha256sum /dev/block/by-name/test"));CHECK(!run(&c,"ls /dev/block/by-name"));
    CHECK(!run(&c,"stat /dev/block/by-name/test"));CHECK(!run(&c,"sync")&&flushes==2);
    CHECK(!kbl_native_put(&c,"/small","abc",3));CHECK(!kbl_native_put(&c,"/preserve","123456789",9));
    CHECK(!run(&c,"dd if=/small of=/preserve conv=notrunc,fsync"));struct node*preserved=resolve(&c,"/preserve",0,0);CHECK(preserved&&preserved->bytes==9&&!memcmp(preserved->data,"abc456789",9));
    CHECK(run(&c,"dd if=/small of=/preserve conv=notrunc,notrunc")==1);CHECK(run(&c,"dd if=/small of=/preserve conv=notrunc,")==1);

    CHECK(run(&c,"rm /dev/block/by-name")==1);CHECK(run(&c,"mv /dev /renamed")==1);
    finish(&c);++groups;
}
static int output_has(struct native*c,const char*needle){size_t n=length(needle);for(size_t i=0;i+n<=c->output_count;++i)if(!memcmp(c->output+i,needle,n))return 1;return 0;}
static void large_and_partial_commands(void){struct native c;setup(&c);kbl_file_handle h;uint64_t position;size_t n;unsigned char buffer[32];
    CHECK(!v_open(&c,"/dev/block/by-name/giant",KBL_FILE_READ,&h));
    CHECK(!v_seek(&c,h,(INT64_C(5)<<30)+7,KBL_SEEK_SET,&position)&&position==((UINT64_C(5)<<30)+7));
    CHECK(!v_read(&c,h,buffer,17,&n)&&n==17&&buffer[0]==7&&buffer[16]==23);CHECK(!v_close(&c,h));
    unsigned before=reads;CHECK(!run(&c,"sha256sum /dev/block/by-name/large"));
    CHECK(reads-before==1025&&c.ramfs_bytes==0&&c.owned_bytes<10000);copy(large_digest,c.output,64);large_digest[64]=0;
    CHECK(!kbl_native_put(&c,"/source","123456789",9));partial=3;
    CHECK(run(&c,"dd if=/source of=/dev/block/by-name/test bs=9 count=1 conv=fsync")==1&&output_has(&c,"copied=3 bytes"));
    CHECK(flushes==1&&writes==1);partial=0;partial_read=5;
    CHECK(run(&c,"dd if=/dev/block/by-name/test of=/partial bs=16 count=1")==1&&output_has(&c,"copied=5 bytes"));
    struct node*file=resolve(&c,"/partial",0,0);CHECK(file&&file->bytes==5);partial_read=0;
    finish(&c);++groups;
}
static void external_io(void){struct native c;setup(&c);c.in_job=c.polling=0;c.p.now_us=0;const struct kbl_program_api*a=0,*again=0;
    CHECK(!kbl_native_io_begin(&c,&a)&&a&&c.io_owner&&!c.in_job&&!c.io_entered);
    CHECK(kbl_native_io_begin(&c,&again)==KBL_AGAIN);uint64_t job=0;
    CHECK(kbl_native_service_submit(&c,(const unsigned char*)"true",4,&job)==KBL_AGAIN);
    CHECK(kbl_native_put(&c,"/locked-out",0,0)==KBL_AGAIN);CHECK(backend_stop(&c)==KBL_AGAIN);
    kbl_file_handle h=0;size_t n=0;CHECK(!a->file_open(a->host,"/external",KBL_FILE_WRITE|KBL_FILE_CREATE,&h));
    CHECK(!a->file_write(a->host,h,"external",8,&n)&&n==8&&!c.in_job&&!c.io_entered);
    char name[128];struct kbl_file_stat st;int end=0;
    CHECK(!kbl_native_io_list(&c,a,"/dev/block/by-name",0,name,sizeof(name),&st,&end)&&!end&&!strcmp(name,"test"));
    CHECK(!kbl_native_io_list(&c,a,"/dev/block/by-name",1,name,sizeof(name),&st,&end)&&!end&&!strcmp(name,"locked"));
    CHECK(!kbl_native_io_list(&c,a,"/dev/block/by-name",2,name,sizeof(name),&st,&end)&&end);
    CHECK(!kbl_native_io_end(&c,a)&&!c.io_owner&&!c.io_entered);CHECK(kbl_native_io_end(&c,a)==KBL_ERROR);
    c.in_job=c.polling=1;CHECK(!v_unlink(&c,"/external"));finish(&c);++groups;
}
int main(void){CHECK(KBL_PROGRAM_API_UEFI_BYTES==64&&KBL_PROGRAM_API_FILE_BYTES==136);ram();partitions();partial_and_el();generation_and_cleanup();commands();external_io();
    large_and_partial_commands();printf("{\"passed\":true,\"groups\":%u,\"assertions\":%u,\"largeDigest\":\"%s\"}\n",groups,checks,large_digest);return 0;}
