/* SPDX-License-Identifier: MIT -- explicit filesystem selection, no probing at boot. */
#include "kbl_fs.h"

int kbl_fat_mount(const struct kbl_fs_host *,const struct kbl_fs_device *,const char *,unsigned,struct kbl_fs *);
int kbl_ext4_mount(const struct kbl_fs_host *,const struct kbl_fs_device *,const char *,unsigned,struct kbl_fs *);
int kbl_f2fs_mount(const struct kbl_fs_host *,const struct kbl_fs_device *,const char *,unsigned,struct kbl_fs *);
int kbl_f2fs_rw_mount(const struct kbl_fs_host *,const struct kbl_fs_device *,const char *,unsigned,struct kbl_fs *);
int kbl_erofs_mount(const struct kbl_fs_host *,const struct kbl_fs_device *,const char *,unsigned,struct kbl_fs *);

static int same(const char *a,const char *b){while(*a&&*a==*b){++a;++b;}return *a==*b;}
static uint32_t le32(const unsigned char *p){return (uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24;}
static int exact(const struct kbl_fs_device *d,uint64_t offset,void *buffer,size_t bytes){
    if(offset>d->bytes||bytes>d->bytes-offset)return -KBL_EIO;
    size_t at=0;while(at<bytes){size_t n=0;int rc=d->read_at(d->opaque,offset+at,(unsigned char*)buffer+at,bytes-at,&n);
        if(n>bytes-at||rc||!n)return -KBL_EIO;at+=n;}
    return 0;
}

int kbl_fs_mount(const struct kbl_fs_host *h,const struct kbl_fs_device *d,
                 const char *type,unsigned flags,struct kbl_fs *out){
    if(out)*out=(struct kbl_fs){0};
    if(!h||!h->allocate||!h->release||!d||!d->read_at||!type||!out||
       (flags&~KBL_FS_READ_ONLY)||d->bytes<512||d->sector_bytes<512||d->sector_bytes>4096||
       (d->sector_bytes&(d->sector_bytes-1)))return -KBL_EINVAL;
    if(!(flags&KBL_FS_READ_ONLY)&&((d->flags&KBL_FS_READ_ONLY)||!d->write_at||!d->flush))return -KBL_EROFS;
    if(same(type,"auto")){
        unsigned char b[2048];size_t n=d->bytes<sizeof(b)?(size_t)d->bytes:sizeof(b);
        int rc=exact(d,0,b,n);if(rc)return rc;
        if(n>=1028&&le32(b+1024)==UINT32_C(0xf2f52010))type="f2fs";
        else if(n>=1028&&le32(b+1024)==UINT32_C(0xe0f5e1e2))type="erofs";
        else if(n>=1082&&b[1080]==0x53&&b[1081]==0xef)type="ext4";
        else if(n>=512&&b[3]=='E'&&b[4]=='X'&&b[5]=='F'&&b[6]=='A'&&b[7]=='T'&&b[8]==' '&&b[9]==' '&&b[10]==' ')type="exfat";
        else if(n>=512&&(b[0]==0xeb||b[0]==0xe9)&&b[510]==0x55&&b[511]==0xaa)type="fat";
        else{
            /* F2FS has a second superblock; a damaged primary need not hide it.
             * The actual reader validates checkpoint/node/extent data itself. */
            unsigned char magic[4];
            if(d->bytes>=5124&&!exact(d,5120,magic,sizeof(magic))&&le32(magic)==UINT32_C(0xf2f52010))type="f2fs";
            else return -KBL_ENOSYS;
        }
    }
    if(same(type,"fat")||same(type,"vfat")||same(type,"fat12")||same(type,"fat16")||same(type,"fat32"))return kbl_fat_mount(h,d,"fat",flags,out);
    if(same(type,"exfat"))return kbl_fat_mount(h,d,"exfat",flags,out);
    if(same(type,"ext2")||same(type,"ext3")||same(type,"ext4"))return kbl_ext4_mount(h,d,"ext4",flags,out);
    if(same(type,"f2fs"))return (flags&KBL_FS_READ_ONLY)?kbl_f2fs_mount(h,d,"f2fs",flags,out):kbl_f2fs_rw_mount(h,d,"f2fs",flags,out);
    if(same(type,"erofs"))return kbl_erofs_mount(h,d,"erofs",flags,out);
    return -KBL_ENOSYS;
}
