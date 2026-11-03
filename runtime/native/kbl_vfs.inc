/* SPDX-License-Identifier: MIT -- RAM, raw block and explicit filesystem VFS. */
static int v_el(struct native*c){if(!c||c->p.current_el(c->p.opaque)!=c->el){if(c)c->fault=1;return -KBL_EIO;}return 0;}
static int v_gate(struct native*c){if(!c||(!c->in_job&&!(c->io_owner&&c->io_entered)))return -KBL_EBUSY;if(c->fault)return -KBL_EIO;return v_el(c);}
static int canonical(struct native*c,const char*path,char**out){
    if(!out||!path||!*path)return -KBL_EINVAL;*out=0;
    const char*cwd=current_path(c);size_t prefix=*path=='/'?0:length(cwd),n=length(path);
    if(n>SIZE_MAX-2||prefix>SIZE_MAX-n-2)return -KBL_EOVERFLOW;
    AUTO(char*,buffer);buffer=alloc_owned(c,prefix+n+2);if(!buffer)return -KBL_ENOMEM;
    size_t pos=prefix?prefix:1;if(prefix)copy(buffer,cwd,prefix);else buffer[0]='/';
    const char*at=path;while(*at){while(*at=='/')++at;if(!*at)break;const char*begin=at;while(*at&&*at!='/')++at;size_t len=(size_t)(at-begin);
        if(len==1&&begin[0]=='.')continue;
        if(len==2&&begin[0]=='.'&&begin[1]=='.'){if(pos>1){while(pos>1&&buffer[pos-1]!='/')--pos;if(pos>1)--pos;}continue;}
        if(pos>1)buffer[pos++]='/';copy(buffer+pos,begin,len);pos+=len;
    }buffer[pos]=0;*out=buffer;buffer=0;return 0;
}
static int block_label(const char*path,const char**label){
    static const char prefix[]="/dev/block/by-name/";size_t i=0;while(prefix[i]&&path[i]==prefix[i])++i;
    if(prefix[i])return 0;const char*p=path+i;size_t n=length(p);
    if(!n||n>=KBL_BLOCK_LABEL_BYTES||slash(p))return -KBL_EINVAL;*label=p;return 1;
}
static int reserved_path(const char*p){return equal(p,"/dev")||(p[0]=='/'&&p[1]=='d'&&p[2]=='e'&&p[3]=='v'&&p[4]=='/');}
#include "kbl_mount.inc"
static int listed(struct native*,struct kbl_block_info**,size_t*);
static struct file_slot*slot(struct native*c,kbl_file_handle h){
    if(!h)return 0;
    for(struct file_slot*f=c->files;f;f=f->next)if(f->id==h)
        return f->active==1&&f->job==(c->file_owner?c->file_owner:c->job_id)?f:0;
    return 0;
}
static int dispose_file(struct native*c,struct file_slot*f){
    if(v_el(c))return -KBL_EIO;int rc=0;
    if(f->mount){FS_OWNED_CALL(c,rc,f->mount->fs.ops->close(f->mount->fs.opaque,f->file));if(rc){f->active=2;return rc;}}
    else if(f->block.reader.close)f->block.reader.close(f->block.reader.opaque);
    struct file_slot*next=f->next;free_owned(c,f->path);
    zero(f,sizeof(*f));f->next=next;return v_el(c);
}
static int v_close(void*opaque,kbl_file_handle h){
    struct native*c=context(opaque);if(!c||(!c->in_job&&!(c->io_owner&&c->io_entered)))return -KBL_EBUSY;
    struct file_slot*f=slot(c,h);if(!f)return -KBL_EBADF;f->active=2;return dispose_file(c,f);
}
static int close_files(struct native*c){
    if(v_el(c))return -KBL_EIO;int failed=0;
    for(struct file_slot*f=c->files;f;f=f->next)if(f->active){int rc=dispose_file(c,f);if(rc&&!failed)failed=rc;}
    return failed;
}
static int close_owner_files(struct native*c,uint64_t owner){
    if(v_el(c))return -KBL_EIO;int failed=0;
    for(struct file_slot*f=c->files;f;f=f->next)if(f->active&&f->job==owner){int rc=dispose_file(c,f);if(rc&&!failed)failed=rc;}
    return failed;
}
static int file_info(struct native*c,struct file_slot*f,struct kbl_file_stat*out){
    if(f->mount){int rc;FS_CALL(c,rc,f->mount->fs.ops->fstat(f->mount->fs.opaque,f->file,out));return rc;}
    if(f->node)*out=(struct kbl_file_stat){f->node->bytes,KBL_FILE_REGULAR,0,0,0};
    else *out=(struct kbl_file_stat){f->block.reader.size,KBL_FILE_BLOCK,f->block.flags&KBL_BLOCK_READ_ONLY?KBL_FILE_READ_ONLY:0,f->block.block_bytes,0};return 0;
}
static int v_open(void*opaque,const char*path,unsigned flags,kbl_file_handle*out){
    struct native*c=context(opaque);if(out)*out=0;int gate=v_gate(c);if(gate)return gate;
    if(!out||!(flags&(KBL_FILE_READ|KBL_FILE_WRITE))||(flags&~63u)||
        ((flags&(KBL_FILE_TRUNC|KBL_FILE_APPEND|KBL_FILE_CREATE))&&!(flags&KBL_FILE_WRITE))||
        ((flags&KBL_FILE_EXCL)&&!(flags&KBL_FILE_CREATE)))return -KBL_EINVAL;
    if(c->file_seed==UINT64_MAX)return -KBL_EOVERFLOW;
    struct file_slot*f=c->files;while(f&&f->active)f=f->next;
    AUTO(struct file_slot*,fresh);if(!f){fresh=alloc_owned(c,sizeof(*fresh));if(!fresh)return -KBL_ENOMEM;f=fresh;}
    AUTO(char*,full);int status=canonical(c,path,&full);if(status)return status;
    const char*relative=0;struct mount*m=route_mount(c,full,&relative);
    struct kbl_block block;zero(&block,sizeof(block));struct node*node=0;void*file=0;
    if(m){
        if((flags&KBL_FILE_WRITE)&&(m->flags&KBL_FS_READ_ONLY))return -KBL_EROFS;
        FS_OWNED_CALL(c,status,m->fs.ops->open(m->fs.opaque,relative,flags,&file));if(status)return status;
        if(!file)return -KBL_EIO;
    }else{const char*label=0;int special=block_label(full,&label);if(special<0)return special;
        if(special){
            if(flags&~(KBL_FILE_READ|KBL_FILE_WRITE))return -KBL_EINVAL;
            if((flags&KBL_FILE_WRITE)&&raw_mutation_busy(c,full))return -KBL_EBUSY;
            if(!c->p.block_open)return -KBL_ENODEV;
            status=c->p.block_open(c->p.opaque,label,flags&(KBL_BLOCK_READ|KBL_BLOCK_WRITE),&block);
            if(v_el(c)){if(block.reader.close)block.reader.close(block.reader.opaque);return -KBL_EIO;}
            if(status!=KBL_OK||!block.reader.close||!block.reader.read_at||!block.block_bytes){
                if(block.reader.close)block.reader.close(block.reader.opaque);return flags&KBL_FILE_WRITE&&block.flags&KBL_BLOCK_READ_ONLY?-KBL_EROFS:-KBL_EIO;}
            if((flags&KBL_FILE_WRITE)&&((block.flags&KBL_BLOCK_READ_ONLY)||!block.write_at)){
                block.reader.close(block.reader.opaque);return -KBL_EROFS;}
        }else{
            node=resolve(c,full,0,0);if(node&&node->directory)return -KBL_EISDIR;
            if(reserved_path(full))return -KBL_EROFS;if(node&&(flags&KBL_FILE_WRITE)&&ram_mutation_busy(c,node))return -KBL_EBUSY;
            if(node&&(flags&KBL_FILE_CREATE)&&(flags&KBL_FILE_EXCL))return -KBL_EEXIST;
            if(!node){if(!(flags&KBL_FILE_CREATE))return -KBL_ENOENT;AUTO(char*,name);struct node*p=resolve(c,full,1,&name);
                if(!p)return -KBL_ENOENT;node=new_node(c,p,name,0);if(!node)return -KBL_ENOSPC;}
            if((flags&KBL_FILE_TRUNC)&&replace(c,node,0,0))return -KBL_ENOMEM;
        }
    }
    f->id=++c->file_seed;f->active=1;f->flags=flags;f->position=0;f->node=node;f->block=block;f->job=c->file_owner?c->file_owner:c->job_id;f->mount=m;f->file=file;
    f->path=full;full=0;if(fresh){f->next=c->files;c->files=f;fresh=0;}
    if(flags&KBL_FILE_APPEND){struct kbl_file_stat info;status=file_info(c,f,&info);if(status){f->active=2;(void)dispose_file(c,f);return status;}f->position=info.bytes;}
    *out=f->id;return v_el(c);
}
static int v_read(void*opaque,kbl_file_handle h,void*buffer,size_t bytes,size_t*done){
    struct native*c=context(opaque);if(done)*done=0;int gate=v_gate(c);if(gate)return gate;
    if(!done||(bytes&&!buffer))return -KBL_EINVAL;struct file_slot*f=slot(c,h);if(!f||!(f->flags&KBL_FILE_READ))return -KBL_EBADF;
    if(bytes>FILE_CHUNK)bytes=FILE_CHUNK;struct kbl_file_stat info;int status=file_info(c,f,&info);if(status)return status;
    if(f->position>=info.bytes||!bytes)return 0;if(bytes>info.bytes-f->position)bytes=(size_t)(info.bytes-f->position);
    if(f->mount){FS_CALL(c,status,f->mount->fs.ops->read_at(f->mount->fs.opaque,f->file,f->position,buffer,bytes,done));}
    else if(f->node){copy(buffer,f->node->data+(size_t)f->position,bytes);*done=bytes;}
    else{status=f->block.reader.read_at(f->block.reader.opaque,f->position,buffer,bytes,done);if(status)status=-KBL_EIO;}
    if(*done>bytes){*done=0;status=-KBL_EIO;}if(!*done&&bytes&&!status)status=-KBL_EIO;
    f->position+=*done;if(v_el(c)||checkpoint(c))return -KBL_EIO;return status;
}
static int ram_grow(struct native*c,struct node*f,size_t bytes){
    if(bytes<=f->capacity)return 0;size_t capacity=f->capacity?f->capacity:256;
    while(capacity<bytes){if(capacity>c->p.max_file_bytes/2){capacity=c->p.max_file_bytes;break;}capacity*=2;}
    if(capacity<bytes)return -KBL_EFBIG;unsigned char*fresh=alloc_owned(c,capacity);if(!fresh)return -KBL_ENOMEM;
    for(size_t at=0;at<f->bytes;){size_t n=f->bytes-at;if(n>FILE_CHUNK)n=FILE_CHUNK;copy(fresh+at,f->data+at,n);at+=n;
        if(checkpoint(c)){free_owned(c,fresh);return -KBL_EIO;}}
    unsigned char*old=f->data;f->data=fresh;f->capacity=capacity;free_owned(c,old);return 0;
}
static int v_write(void*opaque,kbl_file_handle h,const void*buffer,size_t bytes,size_t*done){
    struct native*c=context(opaque);if(done)*done=0;int gate=v_gate(c);if(gate)return gate;
    if(!done||(bytes&&!buffer))return -KBL_EINVAL;struct file_slot*f=slot(c,h);if(!f||!(f->flags&KBL_FILE_WRITE))return -KBL_EBADF;
    if(bytes>FILE_CHUNK)bytes=FILE_CHUNK;if(!bytes)return 0;int status=0;
    if(f->mount){
        if(f->mount->flags&KBL_FS_READ_ONLY)return -KBL_EROFS;
        if(f->flags&KBL_FILE_APPEND){struct kbl_file_stat info;status=file_info(c,f,&info);if(status)return status;f->position=info.bytes;}
        if(bytes>UINT64_MAX-f->position)return -KBL_EOVERFLOW;
        FS_CALL(c,status,f->mount->fs.ops->write_at(f->mount->fs.opaque,f->file,f->position,buffer,bytes,done));
    }else if(f->node){struct node*n=f->node;if(ram_mutation_busy(c,n))return -KBL_EBUSY;if(f->flags&KBL_FILE_APPEND)f->position=n->bytes;
        if(f->position>c->p.max_file_bytes||bytes>c->p.max_file_bytes-f->position)return -KBL_EFBIG;
        size_t end=(size_t)f->position+bytes,new_size=end>n->bytes?end:n->bytes,extra=new_size-n->bytes;
        if(c->ramfs_bytes>c->p.max_ramfs_bytes||extra>c->p.max_ramfs_bytes-c->ramfs_bytes)return -KBL_ENOSPC;status=ram_grow(c,n,new_size);if(status)return status;
        for(size_t at=n->bytes;at<f->position;){size_t amount=(size_t)f->position-at;if(amount>FILE_CHUNK)amount=FILE_CHUNK;
            zero(n->data+at,amount);at+=amount;if(checkpoint(c))return -KBL_EIO;}
        copy(n->data+(size_t)f->position,buffer,bytes);n->bytes=new_size;c->ramfs_bytes+=extra;*done=bytes;
    }else{
        if(f->block.flags&KBL_BLOCK_READ_ONLY)return -KBL_EROFS;if(raw_mutation_busy(c,f->path))return -KBL_EBUSY;
        if(f->position>=f->block.reader.size)return -KBL_ENOSPC;
        if(bytes>f->block.reader.size-f->position)bytes=(size_t)(f->block.reader.size-f->position);
        status=f->block.write_at(f->block.reader.opaque,f->position,buffer,bytes,done);if(status)status=-KBL_EIO;
    }
    if(*done>bytes){*done=0;status=-KBL_EIO;}if(!*done&&!status)status=-KBL_EIO;
    f->position+=*done;if(v_el(c)||checkpoint(c))return -KBL_EIO;return status;
}
static int v_seek(void*opaque,kbl_file_handle h,int64_t offset,unsigned whence,uint64_t*out){
    struct native*c=context(opaque);int gate=v_gate(c);if(gate)return gate;if(!out||whence>2)return -KBL_EINVAL;
    struct file_slot*f=slot(c,h);if(!f)return -KBL_EBADF;struct kbl_file_stat info;int rc=file_info(c,f,&info);if(rc)return rc;
    uint64_t base=whence==KBL_SEEK_SET?0:whence==KBL_SEEK_CUR?f->position:info.bytes,value;
    if(offset<0){uint64_t n=(uint64_t)(-(offset+1))+1;if(n>base)return -KBL_EINVAL;value=base-n;}
    else{if((uint64_t)offset>UINT64_MAX-base)return -KBL_EOVERFLOW;value=base+(uint64_t)offset;}
    uint64_t limit=f->mount?INT64_MAX:f->node?c->p.max_file_bytes:info.bytes;
    if(value>limit)return -KBL_EFBIG;f->position=value;*out=value;return v_el(c);
}
static int v_stat(void*opaque,const char*path,struct kbl_file_stat*out){
    struct native*c=context(opaque);int gate=v_gate(c);if(gate)return gate;if(!out)return -KBL_EINVAL;
    AUTO(char*,full);int rc=canonical(c,path,&full);if(rc)return rc;zero(out,sizeof(*out));
    const char*relative=0;struct mount*m=route_mount(c,full,&relative);if(m){FS_CALL(c,rc,m->fs.ops->stat(m->fs.opaque,relative,out));return rc;}
    const char*label=0;int special=block_label(full,&label);if(special<0)return special;
    if(special){kbl_file_handle h;rc=v_open(c,full,KBL_FILE_READ,&h);if(rc)return rc;struct file_slot*f=slot(c,h);rc=file_info(c,f,out);int closed=v_close(c,h);return rc?rc:closed;}
    struct node*n=resolve(c,full,0,0);if(!n)return -KBL_ENOENT;
    *out=(struct kbl_file_stat){n->bytes,n->directory?KBL_FILE_DIRECTORY:KBL_FILE_REGULAR,n->reserved?KBL_FILE_READ_ONLY:0,0,0};return v_el(c);
}
static int v_mkdir(void*opaque,const char*path){
    struct native*c=context(opaque);int gate=v_gate(c);if(gate)return gate;AUTO(char*,full);AUTO(char*,name);int rc=canonical(c,path,&full);if(rc)return rc;
    const char*relative=0;struct mount*m=route_mount(c,full,&relative);if(m){if(m->flags&KBL_FS_READ_ONLY)return -KBL_EROFS;FS_CALL(c,rc,m->fs.ops->mkdir(m->fs.opaque,relative));return rc;}
    if(reserved_path(full))return -KBL_EROFS;if(resolve(c,full,0,0))return -KBL_EEXIST;
    struct node*p=resolve(c,full,1,&name);if(!p)return -KBL_ENOENT;if(!new_node(c,p,name,1))return -KBL_ENOSPC;return v_el(c);
}
static int v_unlink(void*opaque,const char*path){
    struct native*c=context(opaque);int gate=v_gate(c);if(gate)return gate;AUTO(char*,full);int rc=canonical(c,path,&full);if(rc)return rc;
    if(all_cwd_under(c,full))return -KBL_EBUSY;
    for(struct file_slot*f=c->files;f;f=f->next)if(f->active&&path_under(full,f->path))return -KBL_EBUSY;
    const char*relative=0;struct mount*m=route_mount(c,full,&relative);if(m){if(equal(relative,"/"))return -KBL_EBUSY;if(m->flags&KBL_FS_READ_ONLY)return -KBL_EROFS;FS_CALL(c,rc,m->fs.ops->unlink(m->fs.opaque,relative));return rc;}
    if(reserved_path(full))return -KBL_EROFS;struct node*n=resolve(c,full,0,0);if(!n)return -KBL_ENOENT;
    if(n==c->root||ram_mutation_busy(c,n))return -KBL_EBUSY;if(n->children)return -KBL_ENOTEMPTY;
    unlink_node(n);c->ramfs_bytes-=n->bytes;free_owned(c,n->data);--c->nodes;free_owned(c,n->name);free_owned(c,n);return v_el(c);
}
static int v_rename(struct native*c,const char*source,const char*target){
    int gate=v_gate(c);if(gate)return gate;AUTO(char*,src);AUTO(char*,dst);int rc=canonical(c,source,&src);if(rc)return rc;if((rc=canonical(c,target,&dst)))return rc;if(equal(src,dst))return 0;
    if(all_cwd_under(c,src)||all_cwd_under(c,dst))return -KBL_EBUSY;
    for(struct file_slot*f=c->files;f;f=f->next)if(f->active&&(path_under(src,f->path)||path_under(dst,f->path)))return -KBL_EBUSY;
    const char*sr=0,*dr=0;struct mount*sm=route_mount(c,src,&sr),*dm=route_mount(c,dst,&dr);if(sm!=dm)return -KBL_EXDEV;
    if(sm){if(equal(sr,"/")||equal(dr,"/"))return -KBL_EBUSY;if(sm->flags&KBL_FS_READ_ONLY)return -KBL_EROFS;FS_CALL(c,rc,sm->fs.ops->rename(sm->fs.opaque,sr,dr));return rc;}
    if(reserved_path(src)||reserved_path(dst))return -KBL_EROFS;struct node*s=resolve(c,src,0,0);if(!s)return -KBL_ENOENT;
    AUTO(char*,name);struct node*p=resolve(c,dst,1,&name);if(!p)return -KBL_ENOENT;if(s==c->root||ram_mutation_busy(c,s)||ancestor(s,p))return -KBL_EBUSY;
    struct node*d=child(p,name);if(d){if(ram_mutation_busy(c,d))return -KBL_EBUSY;if(d->directory||s->directory)return -KBL_EEXIST;unlink_node(d);c->ramfs_bytes-=d->bytes;free_owned(c,d->data);--c->nodes;free_owned(c,d->name);free_owned(c,d);}
    unlink_node(s);s->parent=p;s->next=p->children;p->children=s;free_owned(c,s->name);s->name=name;name=0;return 0;
}
static int v_list(struct native*c,const char*path,uint64_t index,char*name,size_t cap,struct kbl_file_stat*st,int*end){
    int rc=v_gate(c);if(rc)return rc;if(!name||!cap||!st||!end)return -KBL_EINVAL;*end=0;name[0]=0;zero(st,sizeof(*st));
    AUTO(char*,full);if((rc=canonical(c,path,&full)))return rc;const char*relative=0;struct mount*m=route_mount(c,full,&relative);
    if(m){FS_CALL(c,rc,m->fs.ops->list(m->fs.opaque,relative,index,name,cap,st,end));if(!rc&&!*end){size_t len=0;while(len<cap&&name[len])++len;if(!len||len==cap||slash(name)||equal(name,".")||equal(name,".."))return -KBL_EIO;}return rc;}
    struct node*n=resolve(c,full,0,0);if(!n)return -KBL_ENOENT;if(!n->directory)return -KBL_ENOTDIR;const char*label=0;
    struct kbl_block_info*items=0;size_t count=0;
    if(n->reserved==3){rc=listed(c,&items,&count);if(rc)return rc;if(index>=count){free_owned(c,items);*end=1;return 0;}struct kbl_block_info*b=&items[index];label=b->label;*st=(struct kbl_file_stat){b->bytes,KBL_FILE_BLOCK,b->flags&KBL_BLOCK_READ_ONLY?KBL_FILE_READ_ONLY:0,b->block_bytes,0};}
    else {struct node*f=n->children;while(f&&index){f=f->next;--index;}if(!f){*end=1;return 0;}label=f->name;*st=(struct kbl_file_stat){f->bytes,f->directory?KBL_FILE_DIRECTORY:KBL_FILE_REGULAR,f->reserved?KBL_FILE_READ_ONLY:0,0,0};}
    size_t len=length(label);if(len>=cap){free_owned(c,items);return -KBL_ENAMETOOLONG;}copy(name,label,len+1);free_owned(c,items);return 0;
}
static int v_sync(void*opaque,kbl_file_handle h){
    struct native*c=context(opaque);int gate=v_gate(c);if(gate)return gate;
    if(h){struct file_slot*f=slot(c,h);if(!f)return -KBL_EBADF;if(!(f->flags&KBL_FILE_WRITE))return -KBL_EBADF;
        if(f->mount){int rc;FS_CALL(c,rc,f->mount->fs.ops->sync(f->mount->fs.opaque,f->file));return rc;}
        if(f->node)return 0;if(!f->block.flush)return -KBL_ENOSYS;
        int rc=f->block.flush(f->block.reader.opaque);if(v_el(c))return -KBL_EIO;return rc?-KBL_EIO:0;}
    int failed=0;for(struct file_slot*f=c->files;f;f=f->next)if(f->active==1&&(f->flags&KBL_FILE_WRITE)){
        int rc=v_sync(c,f->id);if(rc&&!failed)failed=rc;if(checkpoint(c))return -KBL_EIO;}
    for(struct mount*m=c->mounts;m;m=m->next)if(m->active==1){int rc;FS_CALL(c,rc,m->fs.ops->sync(m->fs.opaque,0));if(rc&&!failed)failed=rc;if(checkpoint(c))return -KBL_EIO;}
    return failed;
}
static int v_destination(struct native*c,const char*source,const char*target,char**out){
    AUTO(char*,dest);int rc=canonical(c,target,&dest);if(rc)return rc;struct kbl_file_stat st;rc=v_stat(c,dest,&st);if(rc&&rc!=-KBL_ENOENT)return rc;
    if(!rc&&st.type==KBL_FILE_DIRECTORY){AUTO(char*,src);rc=canonical(c,source,&src);if(rc)return rc;const char*base=src;
        for(const char*p=src;*p;++p)if(*p=='/')base=p+1;size_t n=length(dest),len=length(base);
        if(!len)return -KBL_EINVAL;if(len>SIZE_MAX-2||n>SIZE_MAX-len-2)return -KBL_EOVERFLOW;
        AUTO(char*,joined);joined=alloc_owned(c,n+len+2);if(!joined)return -KBL_ENOMEM;copy(joined,dest,n);
        if(n>1)joined[n++]='/';copy(joined+n,base,len+1);free_owned(c,dest);dest=joined;joined=0;
    }*out=dest;dest=0;return 0;
}
static int copy_command(struct native*c,const char*source,const char*target){
    AUTO(char*,src);AUTO(char*,dst);if(canonical(c,source,&src)||v_destination(c,source,target,&dst))return error(c,"invalid destination");if(equal(src,dst))return 0;
    if(!mounted_path(c,src)&&!mounted_path(c,dst)){
        struct node*source_node=resolve(c,src,0,0);if(source_node&&!source_node->directory){
            AUTO(char*,name);struct node*p=resolve(c,dst,1,&name);if(!p||p->reserved)return error(c,"invalid destination");
            struct node*d=child(p,name);if(d&&(d->directory||ram_mutation_busy(c,d)))return error(c,"destination busy");unsigned fresh=0;
            if(!d){d=new_node(c,p,name,0);fresh=1;if(d)unlink_node(d);}if(!d)return error(c,"copy allocation failed");
            int result=replace(c,d,source_node->data,source_node->bytes);if(result&&fresh){--c->nodes;free_owned(c,d->name);free_owned(c,d);}
            else if(fresh){d->next=p->children;p->children=d;}return result?error(c,"copy failed"):0;
        }
    }
    kbl_file_handle in=0,out=0;int rc=v_open(c,src,KBL_FILE_READ,&in);if(rc)return error(c,"source open failed");
    rc=v_open(c,dst,KBL_FILE_WRITE|KBL_FILE_CREATE|KBL_FILE_TRUNC,&out);if(rc){v_close(c,in);return error(c,"destination open failed");}
    unsigned char*buffer=alloc_owned(c,FILE_CHUNK);if(!buffer){v_close(c,in);v_close(c,out);return error(c,"copy allocation failed");}
    int failed=0;for(;;){size_t got=0;rc=v_read(c,in,buffer,FILE_CHUNK,&got);size_t at=0;while(at<got){size_t put=0;int wr=v_write(c,out,buffer+at,got-at,&put);at+=put;if(wr||!put){failed=wr?wr:-KBL_EIO;break;}}
        if(failed||rc||!got){if(rc&&!failed)failed=rc;break;}}
    if(!failed)failed=v_sync(c,out);free_owned(c,buffer);if(v_close(c,in)&&!failed)failed=-KBL_EIO;if(v_close(c,out)&&!failed)failed=-KBL_EIO;
    return failed?error(c,"copy failed"):0;
}
static struct node*v_load_node(struct native*c,const char*path){
    if(!mounted_path(c,path))return resolve(c,path,0,0);
    free_owned(c,c->scratch);c->scratch=0;zero(&c->scratch_node,sizeof(c->scratch_node));struct kbl_file_stat st;
    if(v_stat(c,path,&st)||st.type!=KBL_FILE_REGULAR||st.bytes>c->p.max_file_bytes||st.bytes>SIZE_MAX)return 0;
    kbl_file_handle h=0;if(v_open(c,path,KBL_FILE_READ,&h))return 0;
    unsigned char*data=st.bytes?alloc_owned(c,(size_t)st.bytes):0;if(st.bytes&&!data){v_close(c,h);return 0;}
    size_t at=0;int failed=0;while(at<st.bytes){size_t got=0;int rc=v_read(c,h,data+at,(size_t)st.bytes-at,&got);at+=got;if(rc||!got){failed=1;break;}}
    if(v_close(c,h))failed=1;if(failed){free_owned(c,data);return 0;}c->scratch=data;c->scratch_node.data=data;c->scratch_node.bytes=(size_t)st.bytes;return &c->scratch_node;
}
static int listed(struct native*c,struct kbl_block_info**out,size_t*count){
    *out=0;*count=0;if(!c->p.block_list)return -KBL_ENODEV;
    if(v_el(c)||c->p.block_list(c->p.opaque,0,0,count)!=KBL_OK||v_el(c)||*count>SIZE_MAX/sizeof(**out))return -KBL_EIO;
    if(!*count)return 0;size_t capacity=*count;*out=alloc_owned(c,capacity*sizeof(**out));if(!*out)return -KBL_ENOMEM;
    if(c->p.block_list(c->p.opaque,*out,capacity,count)!=KBL_OK||v_el(c)||*count>capacity){free_owned(c,*out);*out=0;return -KBL_EIO;}
    for(size_t i=0;i<*count;++i){size_t n=0;while(n<KBL_BLOCK_LABEL_BYTES&&(*out)[i].label[n])++n;
        if(!n||n==KBL_BLOCK_LABEL_BYTES||slash((*out)[i].label)){free_owned(c,*out);*out=0;return -KBL_EIO;}}
    return 0;
}
static int scaled(const char*s,uint64_t*out){
    size_t n=length(s);if(!n)return 0;uint64_t scale=1;char last=s[n-1];
    if(last=='k'||last=='K'||last=='M'||last=='G'){--n;scale=last=='k'||last=='K'?1024:last=='M'?1048576:1073741824;}
    size_t at=0;unsigned base=10;if(n>=2&&s[0]=='0'&&(s[1]=='x'||s[1]=='X')){at=2;base=16;}if(at==n)return 0;
    uint64_t value=0;for(;at<n;++at){unsigned char ch=(unsigned char)s[at];unsigned d=ch>='0'&&ch<='9'?ch-'0':ch>='a'&&ch<='f'?ch-'a'+10:ch>='A'&&ch<='F'?ch-'A'+10:99;
        if(d>=base||value>(UINT64_MAX-d)/base)return 0;value=value*base+d;}
    if(value>UINT64_MAX/scale)return 0;*out=value*scale;return 1;
}
static int prefix_value(const char*s,const char*prefix,const char**out){while(*prefix&&*s==*prefix){++s;++prefix;}if(*prefix)return 0;*out=s;return 1;}
static int dd_conversions(const char*s,unsigned*out){
    unsigned flags=0;if(!*s)return 0;while(*s){const char*start=s;while(*s&&*s!=',')++s;size_t n=(size_t)(s-start);unsigned bit=0;
        if(n==5&&start[0]=='f'&&start[1]=='s'&&start[2]=='y'&&start[3]=='n'&&start[4]=='c')bit=1;
        else if(n==7&&start[0]=='n'&&start[1]=='o'&&start[2]=='t'&&start[3]=='r'&&start[4]=='u'&&start[5]=='n'&&start[6]=='c')bit=2;
        else return 0;if(flags&bit)return 0;flags|=bit;if(*s){++s;if(!*s)return 0;}}
    *out=flags;return 1;
}
static int dd_command(struct native*c){
    const char*input=0,*output=0;uint64_t bs=512,count=UINT64_MAX,skip=0,seek=0;unsigned conversion=0,seen=0;
    for(int i=1;i<c->argc;++i){const char*v=0;unsigned bit=0;
        if(prefix_value(c->argv[i],"if=",&v)){bit=1;input=v;}
        else if(prefix_value(c->argv[i],"of=",&v)){bit=2;output=v;}
        else if(prefix_value(c->argv[i],"bs=",&v)){bit=4;if(!scaled(v,&bs))return error(c,"dd invalid bs");}
        else if(prefix_value(c->argv[i],"count=",&v)){bit=8;if(!scaled(v,&count))return error(c,"dd invalid count");}
        else if(prefix_value(c->argv[i],"skip=",&v)){bit=16;if(!scaled(v,&skip))return error(c,"dd invalid skip");}
        else if(prefix_value(c->argv[i],"seek=",&v)){bit=32;if(!scaled(v,&seek))return error(c,"dd invalid seek");}
        else if(prefix_value(c->argv[i],"conv=",&v)){bit=64;if(!dd_conversions(v,&conversion))return error(c,"dd supports conv=fsync,notrunc");}
        else return error(c,"dd supports if= of= bs= count= skip= seek= conv=fsync,notrunc");
        if(seen&bit)return error(c,"dd duplicate argument");seen|=bit;
    }
    if(!input||!output||!bs||skip>INT64_MAX/bs||seek>INT64_MAX/bs)return error(c,"dd invalid arguments or offset overflow");
    AUTO(char*,inpath);AUTO(char*,outpath);if(canonical(c,input,&inpath)||canonical(c,output,&outpath)||equal(inpath,outpath))return error(c,"dd invalid or identical paths");
    kbl_file_handle in=0,out=0;int rc=v_open(c,inpath,KBL_FILE_READ,&in);if(rc)return error(c,"dd input open failed");
    const char*label=0;int target=block_label(outpath,&label);unsigned mode=KBL_FILE_WRITE;
    if(!target){if(conversion&2){struct kbl_file_stat st;int exists=v_stat(c,outpath,&st);
            if(exists==-KBL_ENOENT)mode|=KBL_FILE_CREATE;else if(exists){v_close(c,in);return error(c,"dd output stat failed");}}
        else mode|=KBL_FILE_CREATE|(seek?0:KBL_FILE_TRUNC);}

    rc=v_open(c,outpath,mode,&out);if(rc){v_close(c,in);return error(c,"dd output open failed");}
    uint64_t position=0;if(v_seek(c,in,(int64_t)(skip*bs),KBL_SEEK_SET,&position)||v_seek(c,out,(int64_t)(seek*bs),KBL_SEEK_SET,&position)){v_close(c,in);v_close(c,out);return error(c,"dd offset outside file");}
    unsigned char*buffer=alloc_owned(c,FILE_CHUNK);if(!buffer){v_close(c,in);v_close(c,out);return error(c,"dd allocation failed");}
    uint64_t copied=0,records=0;int failed=0,eof=0;
    while(records<count&&!failed&&!eof){uint64_t within=0;
        while(within<bs){size_t want=bs-within>FILE_CHUNK?FILE_CHUNK:(size_t)(bs-within),got=0;
            int read_status=v_read(c,in,buffer,want,&got);if(!got){if(read_status)failed=read_status;else eof=1;break;}
            size_t written=0;while(written<got){size_t done=0;int write_status=v_write(c,out,buffer+written,got-written,&done);
                written+=done;if(copied>UINT64_MAX-done){failed=-KBL_EOVERFLOW;break;}copied+=done;
                if(write_status||!done){failed=write_status?write_status:-KBL_EIO;break;}}
            within+=got;if(failed)break;if(read_status){failed=read_status;break;}if(checkpoint(c)){failed=-KBL_EIO;break;}
        }++records;
    }
    if(conversion&1){int status=v_sync(c,out);if(status&&!failed)failed=status;}
    free_owned(c,buffer);if(v_close(c,in)&&!failed)failed=-KBL_EIO;if(v_close(c,out)&&!failed)failed=-KBL_EIO;
    text(c,"dd: copied=");number(c,copied);text(c," bytes\n");if(failed){text(c,"dd: I/O failed errno=");number(c,(uint64_t)(-failed));text(c,"\n");return 1;}return 0;
}
static int sync_command(struct native*c){
    if(c->argc>1){for(int i=1;i<c->argc;++i){kbl_file_handle h;int rc=v_open(c,c->argv[i],KBL_FILE_WRITE,&h);if(rc)return error(c,"sync open failed");
        rc=v_sync(c,h);if(v_close(c,h))rc=-KBL_EIO;if(rc)return error(c,"sync failed");}return 0;}
    if(v_sync(c,0))return error(c,"filesystem sync failed");
    struct kbl_block_info*items;size_t count;int rc=listed(c,&items,&count);if(rc)return error(c,"sync enumeration failed");
    int failed=0;for(size_t i=0;i<count;++i){if(items[i].flags&KBL_BLOCK_READ_ONLY)continue;AUTO(char*,path);const char*prefix="/dev/block/by-name/";size_t n=length(prefix);path=alloc_owned(c,n+length(items[i].label)+1);if(!path){failed=-KBL_ENOMEM;break;}copy(path,prefix,n);copy(path+n,items[i].label,length(items[i].label)+1);
        if(raw_mutation_busy(c,path))continue;
        kbl_file_handle h;rc=v_open(c,path,KBL_FILE_WRITE,&h);if(!rc){rc=v_sync(c,h);if(v_close(c,h))rc=-KBL_EIO;}if(rc)failed=1;if(checkpoint(c)){failed=1;break;}}
    free_owned(c,items);return failed?error(c,"sync failed"):0;
}
