/* SPDX-License-Identifier: GPL-3.0-or-later
 * KBL in-place F2FS writer. The parser is GNU GRUB GPLv3+; the write path below
 * is independently implemented from the on-disk layout, not copied from the
 * GPLv2-only f2fs-tools fsck/sload writer. No code runs before explicit mount.
 */
#include "f2fs_rw_core.h"
#define kbl_f2fs_mount kbl_f2fs_rw_reader_mount
#include "f2fs.c"
#undef kbl_f2fs_mount

static uint16_t rw_u16(const unsigned char*p){return(uint16_t)p[0]|(uint16_t)p[1]<<8;}
static uint32_t rw_u32(const unsigned char*p){return(uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24;}
static void rw_put32(unsigned char*p,uint32_t n){p[0]=(unsigned char)n;p[1]=(unsigned char)(n>>8);p[2]=(unsigned char)(n>>16);p[3]=(unsigned char)(n>>24);}
/* Reflected CRC32 update, no final complement. F2FS inode checksum includes
 * UUID-derived seed, footer inode number, generation, then the full 4KiB node
 * with the checksum field treated as zero; inode footer bytes are preserved. */
static uint32_t rw_crc(uint32_t crc,const unsigned char*p,size_t n){
    size_t i;unsigned bit;for(i=0;i<n;i++){crc^=p[i];for(bit=0;bit<8;bit++)crc=(crc>>1)^((0u-(crc&1u))&0xedb88320u);}return crc;
}
static uint32_t inode_crc(struct f2_volume*v,const unsigned char*node){
    static const unsigned char zeros[4]={0,0,0,0};uint32_t c;
    c=rw_crc(0xffffffffu,v->data->sblock.uuid,16);
    c=rw_crc(c,node+4076,4);c=rw_crc(c,node+68,4);
    c=rw_crc(c,node,368);c=rw_crc(c,zeros,4);return rw_crc(c,node+372,4096-372);
}
int kbl_f2fs_existing_writable(void*reader_volume){
    struct f2_volume*v=reader_volume;uint32_t flags;unsigned char*copies;size_t n,i;int e;
    if(!v||!v->data||!v->device.write_at||!v->device.flush)return-KBL_EROFS;
    if(v->device.flags&KBL_FS_READ_ONLY)return-KBL_EROFS;
    flags=grub_le_to_cpu32(v->data->ckpt.ckpt_flags);
    /* Offline writes address the committed snapshot. An unclean checkpoint
     * may require roll-forward; this minimal writer never performs recovery. */
    if(!(flags&1u)||(flags&(2u|8u|0x10u|0x1000u|0x2000u|0x4000u)))return-KBL_EIO;
    /* RO may recover a bad primary from its backup. RW requires matching
     * superblock geometry/features/UUID/device map, so no copy is silently
     * repaired or chosen as a writable authority. */
    activate(v);n=sizeof(struct grub_f2fs_superblock);copies=grub_malloc(n*2);if(!copies)return grub_errno;
    e=grub_disk_read(v,2,0,n,copies);
    if(e){grub_free(copies);return e;}
    e=grub_disk_read(v,10,0,n,copies+n);
    if(e){grub_free(copies);return e;}
    for(i=0;i<n;i++)if(copies[i]!=copies[n+i]){grub_free(copies);return-KBL_EIO;}
    grub_free(copies);
    return 0;
}
static int refresh_checked(struct f2_volume*v,struct f2_file*f,uint32_t*inode_block){
    uint32_t block,features,flags;unsigned char*node;int e;
    activate(v);block=get_node_blkaddr(v->data,f->node.ino);if(grub_errno)return grub_errno;
    if(!block||block<grub_le_to_cpu32(v->data->sblock.main_blkaddr)||block>=v->device.bytes/4096)return-KBL_EIO;
    e=grub_f2fs_block_read(v->data,block,&f->node.inode);if(e)return e;f->node.inode_read=1;
    node=(unsigned char*)&f->node.inode;
    if(rw_u32(node+4072)!=f->node.ino||rw_u32(node+4076)!=f->node.ino||(rw_u32(node+4080)>>3))return-KBL_EIO;
    if((e=node_check(&f->node)))return e;
    features=grub_le_to_cpu32(v->data->sblock.feature);
    if(features&0x20u){
        if(!(node[3]&0x20u)||rw_u16(node+360)<12||rw_u32(node+368)!=inode_crc(v,node))return-KBL_EIO;
    }
    if(node_type(&f->node)!=KBL_FILE_REGULAR)return-KBL_EISDIR;
    flags=rw_u32(node+80);
    if(inode_block&&(flags&(0x10u|0x20u|0x00100000u|0x80000000u)))return-KBL_EPERM;
    if(inode_block&&(node[2]&0x40u))return-KBL_EPERM; /* fs-verity advice */
    if(inode_block)*inode_block=block;return 0;
}
int kbl_f2fs_refresh_existing(void*reader_volume,void*reader_file){
    struct f2_volume*v=reader_volume;struct f2_file*f;
    if(!v||!v->data)return-KBL_EINVAL;f=file_check(v,reader_file);if(!f)return-KBL_EBADF;
    return refresh_checked(v,f,0);
}
struct rw_span{uint64_t disk;size_t bytes,buffer;};
static int checked_map(struct f2_volume*v,struct f2_file*f,uint64_t logical,
                       uint64_t*data,uint32_t metadata[64],size_t*metadata_count){
    uint32_t offsets[4],node_offsets[4],nid,physical;int level,step,e;
    struct grub_f2fs_node*page;const unsigned char*bytes;size_t j;
    if(logical>0xffffffffu)return-KBL_ENOSYS;
    level=grub_get_node_path(&f->node.inode.i,(uint32_t)logical,offsets,node_offsets);
    if(level<0)return-KBL_ENOSYS;
    if(!level){*data=grub_le_to_cpu32(f->node.inode.i.i_addr[offsets[0]+kbl_f2fs_extra_words(&f->node.inode.i)]);return 0;}
    page=grub_malloc(4096);if(!page)return grub_errno;
    nid=get_node_id(&f->node.inode,offsets[0],1);
    for(step=1;step<=level;step++){
        if(!nid){grub_free(page);return-KBL_ENOSYS;}
        physical=get_node_blkaddr(v->data,nid);if(grub_errno){e=grub_errno;grub_free(page);return e;}
        if(physical<grub_le_to_cpu32(v->data->sblock.main_blkaddr)||physical>=v->device.bytes/4096){grub_free(page);return-KBL_EIO;}
        e=grub_f2fs_block_read(v->data,physical,page);if(e){grub_free(page);return e;}
        bytes=(const unsigned char*)page;
        if(rw_u32(bytes+4072)!=nid||rw_u32(bytes+4076)!=f->node.ino||
           (rw_u32(bytes+4080)>>3)!=node_offsets[step]){grub_free(page);return-KBL_EIO;}
        for(j=0;j<*metadata_count&&metadata[j]!=physical;j++){}
        if(j==*metadata_count){if(*metadata_count>=64){grub_free(page);return-KBL_EOVERFLOW;}metadata[(*metadata_count)++]=physical;}
        if(step<level)nid=get_node_id(page,offsets[step],0);
    }
    *data=grub_le_to_cpu32(page->dn.addr[offsets[level]]);grub_free(page);return 0;
}
int kbl_f2fs_write_existing(void*reader_volume,void*reader_file,uint64_t offset,const void*buffer,size_t bytes,size_t*done){
    struct f2_volume*v=reader_volume;struct f2_file*f;struct rw_span spans[17];
    uint64_t size,pos,block;size_t n,index=0,count=0,wrote,at;uint32_t inode_block;
    unsigned char*node;unsigned char inode_copy[4096];uint32_t metadata[64];size_t metadata_count=1,j,inline_start,inline_bytes;int e;
    if(!done)return-KBL_EINVAL;*done=0;if(!v||!v->data)return-KBL_EINVAL;
    if((e=kbl_f2fs_existing_writable(v)))return e;
    f=file_check(v,reader_file);if(!f)return-KBL_EBADF;if(bytes&&!buffer)return-KBL_EINVAL;
    if(bytes>65536)bytes=65536;if(!bytes)return 0;
    if((e=refresh_checked(v,f,&inode_block)))return e;
    size=grub_f2fs_file_size(&f->node.inode.i);
    if(offset>size||bytes>size-offset)return-KBL_ENOSYS;
    node=(unsigned char*)&f->node.inode;
    if(node[3]&F2FS_INLINE_DATA){
        at=(size_t)((unsigned char*)get_inline_addr(&f->node.inode.i)-node)+(size_t)offset;
        if(at>4096||bytes>4096-at)return-KBL_EIO;
        grub_memcpy(inode_copy,node,4096);grub_memcpy(inode_copy+at,buffer,bytes);
        /* DATA_EXIST is an inode invariant, not merely a write hint. A
         * previously all-zero inline inode may legitimately have it clear;
         * any nonzero inline contents require setting it before the CRC. */
        inline_start=(size_t)((unsigned char*)get_inline_addr(&f->node.inode.i)-node);
        inline_bytes=kbl_f2fs_inline_bytes(&f->node.inode.i);
        if(inline_start>4096||inline_bytes>4096-inline_start)return-KBL_EIO;
        for(j=0;j<inline_bytes;j++)if(inode_copy[inline_start+j]){inode_copy[3]|=F2FS_DATA_EXIST;break;}
        if(grub_le_to_cpu32(v->data->sblock.feature)&0x20u)rw_put32(inode_copy+368,inode_crc(v,inode_copy));
        wrote=0;e=v->device.write_at(v->device.opaque,(uint64_t)inode_block*4096,inode_copy,4096,&wrote);
        if(wrote>4096)return-KBL_EIO;
        if(wrote>at){*done=wrote-at;if(*done>bytes)*done=bytes;}
        if(e||wrote!=4096)return-KBL_EIO;
        grub_memcpy(node,inode_copy,4096);*done=bytes;return 0;
    }
    /* Preflight every address before the first write, including holes and
     * sentinel/compressed addresses; no allocation or partial feature fallback. */
    pos=offset;at=0;metadata[0]=inode_block;
    while(at<bytes){
        if(count>=17)return-KBL_EOVERFLOW;
        if((e=checked_map(v,f,pos/4096,&block,metadata,&metadata_count)))return e;
        if(!block||block>=0xfffffffeu)return-KBL_ENOSYS;
        if(block<grub_le_to_cpu32(v->data->sblock.main_blkaddr)||block>=v->device.bytes/4096)return-KBL_EIO;
        n=4096-(size_t)(pos%4096);if(n>bytes-at)n=bytes-at;
        spans[count].disk=block*4096+pos%4096;spans[count].bytes=n;spans[count].buffer=at;
        for(index=0;index<count;index++)if(spans[index].disk/4096==block)return-KBL_EIO;
        count++;at+=n;pos+=n;
    }
    for(index=0;index<count;index++)for(j=0;j<metadata_count;j++)
        if(spans[index].disk/4096==metadata[j])return-KBL_EIO;
    for(index=0;index<count;index++){
        wrote=0;e=v->device.write_at(v->device.opaque,spans[index].disk,
            (const unsigned char*)buffer+spans[index].buffer,spans[index].bytes,&wrote);
        if(wrote>spans[index].bytes)return-KBL_EIO;*done+=wrote;
        if(e||wrote!=spans[index].bytes)return-KBL_EIO;
    }
    return 0;
}
