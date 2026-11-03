/* SPDX-License-Identifier: MIT -- explicit mount namespace, never automatic. */
struct mount_mem {void *raw;size_t bytes;};
#define FS_OWNED_CALL(c,rc,call) do{++(c)->fs_busy;(rc)=(call);--(c)->fs_busy;(void)v_el(c);}while(0)
#define FS_CALL(c,rc,call) do{++(c)->fs_busy;(rc)=(call);--(c)->fs_busy;if(v_el(c))(rc)=-KBL_EIO;}while(0)
static int path_under(const char *root,const char *path){if(equal(root,"/"))return 1;while(*root&&*path==*root){++root;++path;}return !*root&&(!*path||*path=='/');}
static struct mount*route_mount(struct native*c,const char*full,const char**relative){
    struct mount*best=0;size_t longest=0;
    for(struct mount*m=c->mounts;m;m=m->next){if(m->active!=1)continue;size_t n=length(m->path);
        if(n>longest&&length(full)>=n&&path_under(m->path,full)){best=m;longest=n;}}
    if(best&&relative)*relative=full[longest]?full+longest:"/";return best;
}
static int mounted_path(struct native*c,const char*path){AUTO(char*,full);int rc=canonical(c,path,&full);return rc?rc:route_mount(c,full,0)!=0;}
static int ram_mutation_busy(struct native*c,struct node*n){
    for(struct mount*m=c->mounts;m;m=m->next){if(m->active&&
        ((m->source_node&&ancestor(n,m->source_node))||(m->point&&ancestor(n,m->point))))return 1;}return 0;
}
static int raw_mutation_busy(struct native*c,const char*full){
    for(struct mount*m=c->mounts;m;m=m->next)if(m->active&&!m->source_node&&equal(full,m->source))return 1;return 0;
}
static void*mount_allocate(void*p,size_t bytes,size_t alignment){
    struct native*c=p;if(!bytes||!alignment||(alignment&(alignment-1))||bytes>SIZE_MAX-sizeof(struct mount_mem)-(alignment-1))return 0;
    void*raw=alloc_owned(c,bytes+sizeof(struct mount_mem)+alignment-1);if(!raw)return 0;
    uintptr_t at=((uintptr_t)raw+sizeof(struct mount_mem)+alignment-1)&~(uintptr_t)(alignment-1);
    struct mount_mem*h=(void*)(at-sizeof(*h));h->raw=raw;h->bytes=bytes;return(void*)at;
}
static void mount_release(void*p,void*memory,size_t bytes){
    if(!memory)return;struct native*c=p;struct mount_mem*h=(void*)((uintptr_t)memory-sizeof(*h));
    if(h->bytes!=bytes){c->fault=1;return;}free_owned(c,h->raw);
}
static int mount_device_read(void*p,uint64_t off,void*buffer,size_t n,size_t*got){
    struct mount*m=p;*got=0;if(!m||!m->active||v_el(m->owner)||off>m->device.bytes||n>m->device.bytes-off)return -KBL_EIO;
    if(m->source_node){copy(buffer,m->source_node->data+(size_t)off,n);*got=n;return 0;}
    int rc=m->block.reader.read_at(m->block.reader.opaque,off,buffer,n,got);if(*got>n){*got=0;rc=-KBL_EIO;}return v_el(m->owner)||rc?-KBL_EIO:0;
}
static int mount_device_write(void*p,uint64_t off,const void*buffer,size_t n,size_t*put){
    struct mount*m=p;*put=0;if(!m||!m->active||v_el(m->owner))return -KBL_EIO;
    if(m->flags&KBL_FS_READ_ONLY)return -KBL_EROFS;if(off>m->device.bytes||n>m->device.bytes-off)return -KBL_ENOSPC;
    if(m->source_node){copy(m->source_node->data+(size_t)off,buffer,n);*put=n;return 0;}
    int rc=m->block.write_at(m->block.reader.opaque,off,buffer,n,put);if(*put>n){*put=0;rc=-KBL_EIO;}return v_el(m->owner)||rc?-KBL_EIO:0;
}
static int mount_device_flush(void*p){struct mount*m=p;if(!m||!m->active||v_el(m->owner))return -KBL_EIO;
    if(m->flags&KBL_FS_READ_ONLY)return 0;if(m->source_node)return 0;
    int rc=m->block.flush(m->block.reader.opaque);return v_el(m->owner)||rc?-KBL_EIO:0;
}
static int mount_ops_valid(const struct kbl_fs*f){const struct kbl_fs_ops*o=f->ops;return f->opaque&&f->type&&o&&o->unmount&&o->open&&o->close&&o->read_at&&o->write_at&&o->fstat&&o->stat&&o->list&&o->mkdir&&o->unlink&&o->rename&&o->sync;}
static void mount_reset(struct native*c,struct mount*m){
    struct mount*next=m->next;free_owned(c,m->path);free_owned(c,m->source);zero(m,sizeof(*m));m->next=next;
}
static int mount_finish(struct native*c,struct mount*m){
    int rc;FS_CALL(c,rc,m->fs.ops->sync(m->fs.opaque,0));if(rc)return rc;
    FS_OWNED_CALL(c,rc,m->fs.ops->unmount(m->fs.opaque));if(rc)return rc;
    if(m->block.reader.close)m->block.reader.close(m->block.reader.opaque);mount_reset(c,m);return v_el(c);
}
static int mount_stop_all(struct native*c){
    for(struct mount*m=c->mounts;m;m=m->next)if(m->active){if(m->active!=1)return -KBL_EBUSY;int rc=mount_finish(c,m);if(rc)return rc;}return 0;
}
static int mount_add(struct native*c,const char*source,const char*target,const char*type,unsigned flags){
    AUTO(char*,full);AUTO(char*,src);int rc=canonical(c,target,&full);if(rc)return rc;if((rc=canonical(c,source,&src)))return rc;
    if(equal(full,"/")||reserved_path(full))return -KBL_EINVAL;
    struct node*point=resolve(c,full,0,0);if(!point||!point->directory)return -KBL_ENOTDIR;if(point->children)return -KBL_ENOTEMPTY;
    struct mount*slot=0;
    for(struct mount*m=c->mounts;m;m=m->next){if(!m->active){if(!slot)slot=m;continue;}
        if(path_under(full,m->path)||path_under(m->path,full)||equal(src,m->source))return -KBL_EBUSY;}
    if(route_mount(c,src,0))return -KBL_EBUSY;
    for(struct file_slot*f=c->files;f;f=f->next)if(f->active&&equal(f->path,src))return -KBL_EBUSY;
    if(!slot){slot=alloc_owned(c,sizeof(*slot));if(!slot)return -KBL_ENOMEM;slot->next=c->mounts;c->mounts=slot;}
    struct mount*m=slot;mount_reset(c,m);m->owner=c;m->active=2;m->flags=flags;m->point=point;
    m->path=full;full=0;m->source=src;src=0;
    const char*label=0;int block=block_label(m->source,&label);if(block<0){mount_reset(c,m);return block;}
    if(block){
        if(!c->p.block_open){mount_reset(c,m);return -KBL_ENODEV;}
        unsigned access=KBL_BLOCK_READ|((flags&KBL_FS_READ_ONLY)?0:KBL_BLOCK_WRITE);
        int status=c->p.block_open(c->p.opaque,label,access,&m->block);
        if(v_el(c)||status||!m->block.reader.close||!m->block.reader.read_at||!m->block.reader.size||!m->block.block_bytes){
            if(m->block.reader.close)m->block.reader.close(m->block.reader.opaque);mount_reset(c,m);return -KBL_EIO;}
        if(!(flags&KBL_FS_READ_ONLY)&&((m->block.flags&KBL_BLOCK_READ_ONLY)||!m->block.write_at||!m->block.flush)){
            m->block.reader.close(m->block.reader.opaque);mount_reset(c,m);return -KBL_EROFS;}
        m->device.bytes=m->block.reader.size;m->device.sector_bytes=m->block.block_bytes;
    }else{
        struct node*n=resolve(c,m->source,0,0);if(!n||n->directory||!n->bytes||n->reserved||ram_mutation_busy(c,n)){mount_reset(c,m);return -KBL_EINVAL;}
        for(struct file_slot*f=c->files;f;f=f->next)if(f->active&&f->node==n){mount_reset(c,m);return -KBL_EBUSY;}
        m->source_node=n;m->device.bytes=n->bytes;m->device.sector_bytes=512;
    }
    m->device.opaque=m;m->device.flags=flags;m->device.read_at=mount_device_read;
    m->device.write_at=mount_device_write;m->device.flush=mount_device_flush;
    const struct kbl_fs_host host={c,mount_allocate,mount_release};
    FS_OWNED_CALL(c,rc,kbl_fs_mount(&host,&m->device,type,flags,&m->fs));
    if(rc){if(m->block.reader.close)m->block.reader.close(m->block.reader.opaque);mount_reset(c,m);return rc;}
    if(!mount_ops_valid(&m->fs)){/* Valid backends always supply the complete table. */
        if(m->fs.ops&&m->fs.ops->unmount){int cleanup;FS_OWNED_CALL(c,cleanup,m->fs.ops->unmount(m->fs.opaque));if(cleanup){m->active=2;return -KBL_EIO;}}
        if(m->block.reader.close)m->block.reader.close(m->block.reader.opaque);mount_reset(c,m);return -KBL_EIO;}
    m->active=1;return v_el(c);
}
static int mount_remove(struct native*c,const char*target){
    AUTO(char*,full);int rc=canonical(c,target,&full);if(rc)return rc;struct mount*m=0;
    for(struct mount*at=c->mounts;at;at=at->next)if(at->active&&equal(at->path,full))m=at;
    if(!m||m->active!=1)return -KBL_ENOENT;if(all_cwd_under(c,m->path)||c->io_owner)return -KBL_EBUSY;
    for(struct file_slot*f=c->files;f;f=f->next)if(f->active&&f->mount==m)return -KBL_EBUSY;
    for(struct mount*at=c->mounts;at;at=at->next)if(at!=m&&at->active&&path_under(m->path,at->path))return -KBL_EBUSY;
    return mount_finish(c,m);
}
static int mount_command(struct native*c){
    if(c->argc==1){for(struct mount*m=c->mounts;m;m=m->next){if(m->active!=1)continue;
        if(text(c,m->source)||text(c," on ")||text(c,m->path)||text(c," type ")||text(c,m->fs.type)||text(c,m->flags&KBL_FS_READ_ONLY?" (ro)\n":" (rw)\n"))return 1;}return 0;}
    const char*type="auto",*source=0,*target=0;unsigned flags=0;
    for(int i=1;i<c->argc;++i){const char*a=c->argv[i];if(equal(a,"-t")){if(++i==c->argc)return error(c,"mount missing type");type=c->argv[i];}
        else if(equal(a,"-o")){if(++i==c->argc)return error(c,"mount missing options");if(equal(c->argv[i],"ro"))flags=KBL_FS_READ_ONLY;else if(equal(c->argv[i],"rw"))flags=0;else return error(c,"mount supports -o ro or rw");}
        else if(a[0]=='-')return error(c,"unsupported mount option");else if(!source)source=a;else if(!target)target=a;else return error(c,"mount has too many arguments");}
    if(!source||!target)return error(c,"usage: mount [-t TYPE] [-o ro|rw] SOURCE TARGET");
    int rc=mount_add(c,source,target,type,flags);if(rc){text(c,"mount failed errno=");number(c,(uint64_t)-rc);text(c,"\n");return 1;}return 0;
}
