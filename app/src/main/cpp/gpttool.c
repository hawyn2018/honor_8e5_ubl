/* honor_ubl 2.3: bounded GPT snapshot/planning/apply helper.
 * Independent primary/backup entry attributes are preserved byte for byte.
 * No filesystem resize, partition reread, image flash, format or reboot API.
 * Real writes exist only in `apply ... --execute`; host tests require fixtures.
 */
#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#ifdef _WIN32
#include <io.h>
#define close _close
#define open _open
#define O_CLOEXEC 0
#define O_NOFOLLOW 0
#define O_BINARY _O_BINARY
typedef struct _stat64 file_stat;
#define fd_stat _fstat64
#define IS_REG(m) (((m) & _S_IFMT) == _S_IFREG)
#else
#include <unistd.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <linux/fs.h>
#define O_BINARY 0
typedef struct stat file_stat;
#define fd_stat fstat
#define IS_REG(m) S_ISREG(m)
_Static_assert(sizeof(off_t) >= 8, "64-bit file offsets required");
#endif

#define CONTAINER 512U
#define MAX_ENTRIES_BYTES (16U * 1024U * 1024U)
/* Observed vendor GPT attribute differences: SDA at bit 50; SDE at bit 51.
 * This whitelist admits only those two observed flags, never other metadata. */
#define ALLOWED_ATTRIBUTE_DIFF (UINT64_C(3) << 50)
struct disk { int fd; uint32_t sector; uint64_t bytes; bool direct; };
struct snapshot {
    unsigned char *data, *mbr, *ph, *pe, *bh, *be;
    size_t length, padded;
    uint32_t sector, count, entry_size;
    uint64_t bytes, primary_entries, backup_header, backup_entries, entry_bytes;
    uint64_t first, last, diff_mask;
    unsigned diff_count;
};
static const char *error_text = "unspecified failure";
static bool fail(const char *s) { error_text = s; return false; }
static uint32_t rd32(const unsigned char *p) { return (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24; }
static uint64_t rd64(const unsigned char *p) { return rd32(p) | (uint64_t)rd32(p+4)<<32; }
static void wr32(unsigned char *p, uint32_t n) { for (unsigned i=0;i<4;i++) p[i]=(unsigned char)(n>>(8*i)); }
static void wr64(unsigned char *p, uint64_t n) { for (unsigned i=0;i<8;i++) p[i]=(unsigned char)(n>>(8*i)); }
static bool zero(const unsigned char *p, size_t n) { for (size_t i=0;i<n;i++) if(p[i])return false; return true; }
static bool add64(uint64_t a,uint64_t b,uint64_t *r) { if(a>UINT64_MAX-b)return false; *r=a+b; return true; }
static bool mul64(uint64_t a,uint64_t b,uint64_t *r) { if(a && b>UINT64_MAX/a)return false; *r=a*b; return true; }
static bool decimal(const char *p,uint64_t *n) {
    uint64_t v=0; if(!p || !*p)return false;
    for(;*p;p++) if(*p<'0'||*p>'9'||!mul64(v,10,&v)||!add64(v,(unsigned)(*p-'0'),&v))return false;
    *n=v; return true;
}
static uint32_t crc32(const unsigned char *p,size_t n) {
    uint32_t crc=UINT32_MAX;
    for(size_t i=0;i<n;i++){ crc^=p[i]; for(unsigned j=0;j<8;j++)crc=(crc>>1)^((crc&1)?UINT32_C(0xedb88320):0); }
    return ~crc;
}
static uint32_t header_crc(const unsigned char *p) {
    unsigned char h[4096]; uint32_t n=rd32(p+12);
    if(n<92 || n>sizeof(h))return 0;
    memcpy(h,p,n); memset(h+16,0,4); return crc32(h,n);
}
static bool io_read(int fd,void *buf,size_t size,uint64_t off) {
    unsigned char *p=buf; uint64_t end;
    if(!add64(off,size,&end)||end>INT64_MAX)return fail("read offset overflow");
    while(size){
#ifdef _WIN32
        if(_lseeki64(fd,(__int64)off,SEEK_SET)<0)return fail("read seek failed");
        int n=_read(fd,p,(unsigned)size);
#else
        ssize_t n=pread(fd,p,size,(off_t)off);
#endif
        if(n<0 && errno==EINTR)continue;
        if(n<=0)return fail("short or failed read");
        p+=n; size-=(size_t)n; off+=(uint64_t)n;
    }
    return true;
}
static bool io_write(int fd,const void *buf,size_t size,uint64_t off) {
    const unsigned char *p=buf; uint64_t end;
    if(!add64(off,size,&end)||end>INT64_MAX)return fail("write offset overflow");
    while(size){
#ifdef _WIN32
        if(_lseeki64(fd,(__int64)off,SEEK_SET)<0)return fail("write seek failed");
        int n=_write(fd,p,(unsigned)size);
#else
        ssize_t n=pwrite(fd,p,size,(off_t)off);
#endif
        if(n<0 && errno==EINTR)continue;
        if(n<=0)return fail("short or failed write");
        p+=n; size-=(size_t)n; off+=(uint64_t)n;
    }
    return true;
}
static bool flush_fd(int fd) {
#ifdef _WIN32
    return _commit(fd)==0 || fail("fsync failed");
#else
    int n; do{n=fsync(fd);}while(n<0 && errno==EINTR); return n==0 || fail("fsync failed");
#endif
}
static bool open_disk(const char *path,bool rw,uint32_t fixture,struct disk *d) {
    file_stat st; d->fd=-1;
#ifdef _WIN32
    if(!fixture)return fail("Windows requires --fixture-sector and a regular file");
#endif
    int flags=(rw?O_RDWR:O_RDONLY)|O_CLOEXEC|O_BINARY;
    d->direct=!fixture;
#ifndef _WIN32
    /* A cached fallback would conceal a stale GPT. Real block targets must
     * support direct I/O, including all pre-write checks and final readback. */
    if(d->direct)flags|=O_DIRECT;
#endif
    d->fd=open(path,flags);
    if(d->fd<0)return fail("disk open failed");
    if(fd_stat(d->fd,&st))return fail("disk stat failed");
    if(fixture){
        if(!IS_REG(st.st_mode)||st.st_size<=0)return fail("fixture must be a nonempty regular file");
        d->bytes=(uint64_t)st.st_size; d->sector=fixture;
    }else{
#ifndef _WIN32
        unsigned sector=0;
        if(!S_ISBLK(st.st_mode))return fail("disk must be a block device; regular files require --fixture-sector");
        if(ioctl(d->fd,BLKGETSIZE64,&d->bytes)||ioctl(d->fd,BLKSSZGET,&sector))return fail("block geometry query failed");
        d->sector=sector;
        if(rw){int ro=1;if(geteuid()!=0)return fail("block writes require root");if(ioctl(d->fd,BLKROGET,&ro)||ro)return fail("block device is read-only");}
#endif
    }
    if((d->sector!=512 && d->sector!=4096)||d->bytes>INT64_MAX||d->bytes%d->sector||d->bytes/d->sector<6)return fail("invalid disk geometry");
#ifndef _WIN32
    if(flock(d->fd,(rw?LOCK_EX:LOCK_SH)|LOCK_NB))return fail("disk advisory lock failed");
#endif
    return true;
}
/* GPT buffers are often ordinary malloc or stack buffers. Copy through an
 * explicitly aligned bounce buffer on real disks rather than silently making
 * unaligned O_DIRECT calls or retrying through the page cache. */
static bool disk_io(struct disk *d,void *buffer,size_t size,uint64_t off,bool write) {
    if(!size||off%d->sector||size%d->sector||off>d->bytes||size>d->bytes-off)return fail("disk I/O is unaligned or outside capacity");
    if(!d->direct)return write?io_write(d->fd,buffer,size,off):io_read(d->fd,buffer,size,off);
#ifdef _WIN32
    return fail("Windows real-disk I/O is disabled");
#else
    void *aligned=NULL;
    if(posix_memalign(&aligned,4096,size))return fail("direct I/O aligned allocation failed");
    if(write)memcpy(aligned,buffer,size);
    bool ok=write?io_write(d->fd,aligned,size,off):io_read(d->fd,aligned,size,off);
    if(ok&&!write)memcpy(buffer,aligned,size);
    free(aligned);return ok;
#endif
}
static bool disk_read(struct disk *d,void *buffer,size_t size,uint64_t off) { return disk_io(d,buffer,size,off,false); }
static bool disk_write(struct disk *d,const void *buffer,size_t size,uint64_t off) { return disk_io(d,(void *)buffer,size,off,true); }
static void free_snapshot(struct snapshot *s) { free(s->data); memset(s,0,sizeof(*s)); }
static bool header_valid(const unsigned char *h,uint32_t sector) {
    uint32_t size=rd32(h+12);
    if(memcmp(h,"EFI PART",8)||rd32(h+8)!=UINT32_C(0x00010000)||size<92||size>sector||rd32(h+20))return fail("invalid GPT header signature/revision/size/reserved");
    if(header_crc(h)!=rd32(h+16))return fail("GPT header CRC mismatch");
    return true;
}
static bool configure(struct snapshot *s,uint32_t sector,uint64_t bytes,const unsigned char *ph,const unsigned char *bh) {
    uint64_t p_end,b_end,total;
    if(!header_valid(ph,sector)||!header_valid(bh,sector))return false;
    s->sector=sector; s->bytes=bytes; s->count=rd32(ph+80); s->entry_size=rd32(ph+84);
    s->first=rd64(ph+40); s->last=rd64(ph+48);
    s->primary_entries=rd64(ph+72); s->backup_entries=rd64(bh+72); s->backup_header=bytes/sector-1;
    if(rd64(ph+24)!=1||rd64(ph+32)!=s->backup_header||rd64(bh+24)!=s->backup_header||rd64(bh+32)!=1)return fail("GPT header LBA reciprocity mismatch");
    if(memcmp(ph+56,bh+56,16)||zero(ph+56,16)||rd64(bh+40)!=s->first||rd64(bh+48)!=s->last||rd32(bh+80)!=s->count||rd32(bh+84)!=s->entry_size||rd32(ph+12)!=rd32(bh+12))return fail("GPT headers disagree on disk identity/geometry");
    if(!s->count||s->count>65536||s->entry_size<128||s->entry_size>4096||s->entry_size%128||!mul64(s->count,s->entry_size,&s->entry_bytes)||s->entry_bytes>MAX_ENTRIES_BYTES)return fail("unsupported or excessive GPT array size");
    s->padded=(size_t)(((s->entry_bytes+sector-1)/sector)*sector);
    if(!add64(s->primary_entries,s->padded/sector,&p_end)||!add64(s->backup_entries,s->padded/sector,&b_end)||s->primary_entries<2||p_end>s->first||s->first>s->last||s->backup_entries<=s->last||b_end>s->backup_header||s->backup_entries<p_end)return fail("GPT metadata/usable ranges overlap or exceed disk");
    if(!add64(CONTAINER,(uint64_t)3*sector+(uint64_t)2*s->padded,&total)||total>SIZE_MAX)return fail("snapshot size overflow");
    s->length=(size_t)total; return true;
}
static void pointers(struct snapshot *s) {
    s->mbr=s->data+CONTAINER; s->ph=s->mbr+s->sector; s->pe=s->ph+s->sector;
    s->bh=s->pe+s->padded; s->be=s->bh+s->sector;
}
static void container_header(struct snapshot *s) {
    memcpy(s->data,"HUBLGT22",8); wr32(s->data+8,s->sector); wr32(s->data+12,1);
    wr64(s->data+16,s->bytes); wr64(s->data+24,1); wr64(s->data+32,s->primary_entries);
    wr64(s->data+40,s->backup_header); wr64(s->data+48,s->backup_entries); wr64(s->data+56,s->entry_bytes);
}
static bool mbr_valid(const struct snapshot *s) {
    if(s->mbr[510]!=0x55||s->mbr[511]!=0xaa)return fail("protective MBR signature mismatch");
    unsigned found=0; uint64_t sectors=s->bytes/s->sector-1;
    uint32_t wanted=sectors>UINT32_MAX?UINT32_MAX:(uint32_t)sectors;
    for(unsigned i=0;i<4;i++){
        const unsigned char *e=s->mbr+446+i*16;
        if(zero(e,16))continue;
        /* Some UFS vendors use saturated 0xffffffff even below 2 TiB. It is
         * still one protective (non-hybrid) entry and is never rewritten. */
        if(e[0]||e[4]!=0xee||rd32(e+8)!=1||(rd32(e+12)!=wanted&&rd32(e+12)!=UINT32_MAX))return fail("MBR is hybrid or has an invalid protective extent");
        found++;
    }
    return found==1 || fail("expected exactly one protective MBR entry");
}
struct partition_extent { uint64_t start,end;unsigned char guid[16]; };
static int compare_guid(const void *a,const void *b) { return memcmp(((const struct partition_extent *)a)->guid,((const struct partition_extent *)b)->guid,16); }
static int compare_start(const void *a,const void *b) {
    uint64_t x=((const struct partition_extent *)a)->start,y=((const struct partition_extent *)b)->start;return x<y?-1:x>y?1:0;
}
static bool entries_valid(const struct snapshot *s,const unsigned char *table) {
    struct partition_extent *extents=calloc(s->count,sizeof(*extents));size_t used=0;bool ok=false;
    if(!extents)return fail("partition validation allocation failed");
    for(uint32_t i=0;i<s->count;i++){
        const unsigned char *a=table+(size_t)i*s->entry_size;
        if(zero(a,16))continue;
        uint64_t start=rd64(a+32),end=rd64(a+40);
        if(zero(a+16,16)||start<s->first||end>s->last||start>end){fail("used GPT entry has invalid GUID or bounds");goto out;}
        extents[used].start=start;extents[used].end=end;memcpy(extents[used].guid,a+16,16);used++;
    }
    qsort(extents,used,sizeof(*extents),compare_guid);
    for(size_t i=1;i<used;i++)if(!memcmp(extents[i-1].guid,extents[i].guid,16)){fail("duplicate GPT partition GUID");goto out;}
    qsort(extents,used,sizeof(*extents),compare_start);
    for(size_t i=1;i<used;i++)if(extents[i].start<=extents[i-1].end){fail("GPT partitions overlap");goto out;}
    ok=true;
out:free(extents);return ok;
}
static bool validate(struct snapshot *s,bool strict) {
    if(!mbr_valid(s))return false;
    if(!strict)return true; /* export preserves array CRC failures verbatim */
    if(crc32(s->pe,(size_t)s->entry_bytes)!=rd32(s->ph+88)||crc32(s->be,(size_t)s->entry_bytes)!=rd32(s->bh+88))return fail("GPT entry array CRC mismatch");
    if(!entries_valid(s,s->pe)||!entries_valid(s,s->be))return false;
    s->diff_count=0; s->diff_mask=0;
    for(uint32_t i=0;i<s->count;i++){
        const unsigned char *p=s->pe+(size_t)i*s->entry_size,*b=s->be+(size_t)i*s->entry_size;
        if(memcmp(p,b,48)||memcmp(p+56,b+56,s->entry_size-56))return fail("primary/backup GPT entries differ outside attributes");
        uint64_t diff=rd64(p+48)^rd64(b+48);
        if(diff && (zero(p,16)||(diff&~ALLOWED_ATTRIBUTE_DIFF)))return fail("GPT attribute difference exceeds supported bits 50 and 51");
        if(diff){s->diff_count++;s->diff_mask|=diff;}
    }
    return true;
}
static bool capture(struct disk *d,struct snapshot *s,bool strict) {
    unsigned char ph[4096],bh[4096];
    memset(s,0,sizeof(*s));
    if(!disk_read(d,ph,d->sector,d->sector)||!disk_read(d,bh,d->sector,d->bytes-d->sector)||!configure(s,d->sector,d->bytes,ph,bh))return false;
    s->data=calloc(1,s->length); if(!s->data)return fail("snapshot allocation failed"); pointers(s); container_header(s);
    memcpy(s->ph,ph,d->sector); memcpy(s->bh,bh,d->sector);
    if(!disk_read(d,s->mbr,d->sector,0)||!disk_read(d,s->pe,s->padded,s->primary_entries*d->sector)||!disk_read(d,s->be,s->padded,s->backup_entries*d->sector))return false;
    return validate(s,strict);
}
static bool load_snapshot(const char *path,struct snapshot *s) {
    file_stat st; int fd=-1; bool ok=false; unsigned char header[CONTAINER]; struct snapshot probe={0};
    memset(s,0,sizeof(*s));
    fd=open(path,O_RDONLY|O_CLOEXEC|O_BINARY); if(fd<0)return fail("snapshot open failed");
    if(fd_stat(fd,&st)||!IS_REG(st.st_mode)||st.st_size<CONTAINER||st.st_size>(int64_t)(CONTAINER+3*4096+2*MAX_ENTRIES_BYTES+8192)){fail("snapshot must be a bounded regular file");goto out;}
    if(!io_read(fd,header,sizeof(header),0))goto out;
    uint32_t sector=rd32(header+8); uint64_t bytes=rd64(header+16),eb=rd64(header+56);
    if(memcmp(header,"HUBLGT22",8)||rd32(header+12)!=1||!zero(header+64,CONTAINER-64)||(sector!=512&&sector!=4096)||!bytes||bytes>INT64_MAX||bytes%sector||bytes/sector<6||!eb||eb>MAX_ENTRIES_BYTES){fail("invalid snapshot container");goto out;}
    size_t padded=(size_t)(((eb+sector-1)/sector)*sector);
    uint64_t length=CONTAINER+(uint64_t)3*sector+(uint64_t)2*padded;
    if((uint64_t)st.st_size!=length){fail("snapshot length mismatch");goto out;}
    s->data=malloc((size_t)length); if(!s->data){fail("snapshot allocation failed");goto out;}
    if(!io_read(fd,s->data,(size_t)length,0))goto out;
    if(memcmp(header,s->data,sizeof(header))){fail("snapshot changed while reading");goto out;}
    unsigned char *ph=s->data+CONTAINER+sector,*bh=ph+sector+padded;
    if(!configure(&probe,sector,bytes,ph,bh))goto out;
    if(probe.length!=length||rd64(header+24)!=1||rd64(header+32)!=probe.primary_entries||rd64(header+40)!=probe.backup_header||rd64(header+48)!=probe.backup_entries||eb!=probe.entry_bytes){fail("snapshot container disagrees with GPT headers");goto out;}
    unsigned char *data=s->data; *s=probe; s->data=data; pointers(s);
    ok=validate(s,true);
out:
    if(fd>=0)close(fd); if(!ok)free_snapshot(s); return ok;
}
static bool same_snapshot(const struct snapshot *a,const struct snapshot *b) { return a->length==b->length&&!memcmp(a->data,b->data,a->length); }
static bool clone_snapshot(const struct snapshot *src,struct snapshot *dst) {
    *dst=*src; dst->data=malloc(src->length); if(!dst->data)return fail("snapshot allocation failed");
    memcpy(dst->data,src->data,src->length); pointers(dst); return true;
}
static bool named(const unsigned char *e,const char *name) {
    size_t n=strlen(name); if(n>36)return false;
    for(size_t i=0;i<36;i++)if(e[56+i*2]!=(i<n?(unsigned char)name[i]:0)||e[57+i*2])return false;
    return true;
}
static int hex_digit(char c) { if(c>='0'&&c<='9')return c-'0';if(c>='a'&&c<='f')return c-'a'+10;if(c>='A'&&c<='F')return c-'A'+10;return -1; }
static bool parse_guid(const char *text,unsigned char *guid) {
    unsigned char bytes[16]; unsigned j=0;
    if(strlen(text)!=36)return false;
    for(unsigned i=0;i<36;){
        if(i==8||i==13||i==18||i==23){if(text[i++]!='-')return false;continue;}
        int a=hex_digit(text[i]),b=hex_digit(text[i+1]);if(a<0||b<0||j>=16)return false;
        bytes[j++]=(unsigned char)((a<<4)|b);i+=2;
    }
    if(j!=16||zero(bytes,16))return false;
    for(unsigned i=0;i<4;i++)guid[i]=bytes[3-i];
    guid[4]=bytes[5];guid[5]=bytes[4];guid[6]=bytes[7];guid[7]=bytes[6];memcpy(guid+8,bytes+8,8);return true;
}
static void format_guid(const unsigned char *guid,char out[37]) {
    static const unsigned order[16]={3,2,1,0,5,4,7,6,8,9,10,11,12,13,14,15};
    static const char hex[]="0123456789ABCDEF";unsigned at=0;
    for(unsigned i=0;i<16;i++){
        if(i==4||i==6||i==8||i==10)out[at++]='-';
        unsigned char b=guid[order[i]];out[at++]=hex[b>>4];out[at++]=hex[b&15];
    }
    out[at]=0;
}
/* Bound the one physically contiguous range available after userdata. Entry
 * numbers do not imply physical order. Existing efisp is occupied space too;
 * callers reusing it must use its existing geometry instead of this limit. */
static bool userdata_tail_limit(const struct snapshot *s,uint64_t index,uint64_t *limit) {
    if(!index||index>s->count)return fail("invalid userdata index for tail limit");
    const unsigned char *ud=s->pe+(size_t)(index-1)*s->entry_size;
    if(zero(ud,16)||!named(ud,"userdata"))return fail("tail limit index must identify userdata");
    uint64_t start=rd64(ud+32); *limit=s->last;
    for(uint32_t i=0;i<s->count;i++){
        if((uint64_t)i+1==index)continue;
        const unsigned char *e=s->pe+(size_t)i*s->entry_size;
        if(zero(e,16))continue;
        uint64_t next=rd64(e+32);
        if(next>start && next-1<*limit)*limit=next-1;
    }
    return *limit>=rd64(ud+40) || fail("userdata overlaps its next physical partition");
}
static bool inspect(const struct snapshot *s,uint64_t index,bool direct) {
    if(!index||index>s->count)return fail("invalid userdata index for inspect");
    const unsigned char *ud=s->pe+(size_t)(index-1)*s->entry_size,*bud=s->be+(size_t)(index-1)*s->entry_size;
    if(zero(ud,16)||!named(ud,"userdata"))return fail("inspect index must identify userdata");
    uint64_t tail_limit;
    if(!userdata_tail_limit(s,index,&tail_limit))return false;
    unsigned found=0;uint32_t efi_index=0;char guid[37],disk_guid[37],ud_type[37];
    for(uint32_t i=0;i<s->count;i++){
        const unsigned char *e=s->pe+(size_t)i*s->entry_size;
        if(!zero(e,16)&&named(e,"efisp")){found++;efi_index=i;}
    }
    format_guid(s->ph+56,disk_guid);format_guid(ud,ud_type);format_guid(ud+16,guid);
    printf("DISK_GUID=%s\nGPT_CAPACITY=%u\nGPT_ENTRY_SIZE=%u\nGPT_HEADER_SIZE=%u\nFIRST_USABLE=%" PRIu64 "\nLAST_USABLE=%" PRIu64 "\nPRIMARY_ENTRIES_LBA=%" PRIu64 "\nBACKUP_ENTRIES_LBA=%" PRIu64 "\nUD_TYPE=%s\nUD_LABEL=userdata\nPARTITION_INDICES=",disk_guid,s->count,s->entry_size,rd32(s->ph+12),s->first,s->last,s->primary_entries,s->backup_entries,ud_type);
    bool first_partition=true;
    for(uint32_t i=0;i<s->count;i++){
        if(zero(s->pe+(size_t)i*s->entry_size,16))continue;
        printf("%s%u",first_partition?"":" ",i+1);first_partition=false;
    }
    putchar('\n');
    printf("GPT_VALID=1\nGPT_ATTRIBUTE_DIFF_COUNT=%u\nGPT_ATTRIBUTE_DIFF_MASK=%016" PRIx64 "\nGPT_STATUS=%s\nDISK_READ_MODE=%s\nLOGICAL_SECTOR=%u\nDISK_BYTES=%" PRIu64 "\nUD_INDEX=%" PRIu64 "\nUD_START=%" PRIu64 "\nUD_END=%" PRIu64 "\nUD_BYTES=%" PRIu64 "\nUD_GUID=%s\nUD_ATTR=%016" PRIx64 "\nUD_ATTR_BACKUP=%016" PRIx64 "\nEFI_COUNT=%u\n",
        s->diff_count,s->diff_mask,s->diff_count?"attributes-differ":"clean",direct?"direct":"fixture",s->sector,s->bytes,index,rd64(ud+32),rd64(ud+40),(rd64(ud+40)-rd64(ud+32)+1)*s->sector,guid,rd64(ud+48),rd64(bud+48),found);
    printf("EFI_LIMIT_END=%" PRIu64 "\n",tail_limit);
    if(found==1){
        const unsigned char *e=s->pe+(size_t)efi_index*s->entry_size,*be=s->be+(size_t)efi_index*s->entry_size;char type[37];
        format_guid(e+16,guid);format_guid(e,type);
        printf("EFI_INDEX=%u\nEFI_START=%" PRIu64 "\nEFI_END=%" PRIu64 "\nEFI_BYTES=%" PRIu64 "\nEFI_GUID=%s\nEFI_TYPE=%s\nEFI_ATTR=%016" PRIx64 "\nEFI_ATTR_BACKUP=%016" PRIx64 "\nEFI_LABEL=efisp\n",efi_index+1,rd64(e+32),rd64(e+40),(rd64(e+40)-rd64(e+32)+1)*s->sector,guid,type,rd64(e+48),rd64(be+48));
    }
    puts("RESULT=success");return true;
}
static bool plan_range(const struct snapshot *before,uint64_t ud_index,uint64_t new_end,uint64_t efi_index,uint64_t efi_end,const unsigned char *guid,struct snapshot *after) {
    unsigned char type[16];
    if(!ud_index||ud_index>before->count||!efi_index||efi_index>before->count||ud_index==efi_index)return fail("invalid userdata/efisp indices");
    const unsigned char *ud=before->pe+(size_t)(ud_index-1)*before->entry_size,*efi=before->pe+(size_t)(efi_index-1)*before->entry_size;
    const unsigned char *b_efi=before->be+(size_t)(efi_index-1)*before->entry_size;
    uint64_t tail_limit;
    if(!userdata_tail_limit(before,ud_index,&tail_limit))return false;
    if(new_end<rd64(ud+32)||new_end>=efi_end||efi_end>tail_limit||
       !zero(efi,before->entry_size)||!zero(b_efi,before->entry_size)||zero(guid,16))
        return fail("plan requires bounded adjacent userdata/efisp extents and a zero empty slot");
    for(uint32_t i=0;i<before->count;i++){
        const unsigned char *e=before->pe+(size_t)i*before->entry_size;
        if(!zero(e,16)&&named(e,"efisp"))return fail("efisp already exists; refusing a repeated userdata shrink");
        if(!zero(e,16)&&!memcmp(e+16,guid,16))return fail("new efisp GUID duplicates an existing partition");
    }
    if(!clone_snapshot(before,after))return false;
    if(!parse_guid("C12A7328-F81F-11D2-BA4B-00A0C93EC93B",type))return fail("internal EFI GUID error");
    unsigned char *tables[2]={after->pe,after->be},*headers[2]={after->ph,after->bh};
    for(unsigned i=0;i<2;i++){
        unsigned char *table=tables[i],*e=table+(size_t)(efi_index-1)*after->entry_size;
        wr64(table+(size_t)(ud_index-1)*after->entry_size+40,new_end);
        memcpy(e,type,16);memcpy(e+16,guid,16);wr64(e+32,new_end+1);wr64(e+40,efi_end);
        const char *label="efisp";for(unsigned j=0;j<5;j++)e[56+j*2]=(unsigned char)label[j];
        wr32(headers[i]+88,crc32(table,(size_t)after->entry_bytes));wr32(headers[i]+16,header_crc(headers[i]));
    }
    return validate(after,true);
}
/* Keep the legacy CLI contract: it only divides the existing userdata tail. */
static bool plan(const struct snapshot *before,uint64_t ud_index,uint64_t new_end,uint64_t efi_index,const unsigned char *guid,struct snapshot *after) {
    if(!ud_index||ud_index>before->count)return fail("invalid userdata index");
    uint64_t old_end=rd64(before->pe+(size_t)(ud_index-1)*before->entry_size+40);
    if(new_end>=old_end)return fail("legacy plan requires a positive userdata tail shrink");
    return plan_range(before,ud_index,new_end,efi_index,old_end,guid,after);
}
static bool transition(const struct snapshot *before,const struct snapshot *after) {
    uint64_t ud=0,efi=0,end=0,efi_start=0,efi_end=0;const unsigned char *guid=NULL;struct snapshot expected={0};bool ok=false;
    if(before->length!=after->length||before->count!=after->count||before->entry_size!=after->entry_size)return fail("before/after geometry differs");
    for(uint32_t i=0;i<before->count;i++){
        const unsigned char *a=before->pe+(size_t)i*before->entry_size,*b=after->pe+(size_t)i*after->entry_size;
        if(!memcmp(a,b,before->entry_size))continue;
        if(!zero(a,16)&&named(a,"userdata")){if(ud)return fail("multiple changed userdata entries");ud=i+1;end=rd64(b+40);}
        else if(zero(a,before->entry_size)){if(efi)return fail("multiple new partitions");efi=i+1;guid=b+16;efi_start=rd64(b+32);efi_end=rd64(b+40);}
        else return fail("unexpected changed GPT entry");
    }
    if(!efi||!guid)return fail("expected one new efisp entry and an optional userdata end change");
    if(!ud){
        /* With external free space userdata need not change. Infer it from the
         * required adjacency; exact reconstruction below binds every byte. */
        if(!efi_start)return fail("new efisp start is invalid");
        for(uint32_t i=0;i<before->count;i++){
            const unsigned char *a=before->pe+(size_t)i*before->entry_size;
            if(zero(a,16)||!named(a,"userdata")||rd64(a+40)!=efi_start-1)continue;
            if(ud)return fail("new efisp has ambiguous userdata adjacency");
            ud=i+1;end=rd64(a+40);
        }
    }
    if(!ud)return fail("new efisp must be adjacent to userdata");
    if(!plan_range(before,ud,end,efi,efi_end,guid,&expected))goto out;
    if(!same_snapshot(&expected,after)){fail("after snapshot changes bytes outside the exact preserving plan");goto out;}
    ok=true;
out:free_snapshot(&expected);return ok;
}
static int exclusive_file(const char *path) {
    int fd=open(path,O_CREAT|O_EXCL|O_WRONLY|O_CLOEXEC|O_NOFOLLOW|O_BINARY,0600);
    if(fd<0)fail("snapshot destination must be a new regular file");return fd;
}
static bool save_pair(const char *before_path,const char *after_path,const struct snapshot *before,const struct snapshot *after) {
    int a=exclusive_file(before_path),b=-1;bool ok=false;
    if(a<0)return false;b=exclusive_file(after_path);if(b<0)goto out;
    if(!io_write(a,before->data,before->length,0)||!flush_fd(a)||!io_write(b,after->data,after->length,0)||!flush_fd(b))goto out;
    ok=true;
out:if(a>=0)close(a);if(b>=0)close(b);return ok;
}
static bool readback_region(struct disk *d,const unsigned char *expected,size_t n,uint64_t off) {
    unsigned char *data=malloc(n);if(!data)return fail("readback allocation failed");
    bool ok=disk_read(d,data,n,off);if(ok&&memcmp(data,expected,n))ok=fail("GPT region readback mismatch");free(data);return ok;
}
static bool apply(const char *path,uint32_t fixture,const struct snapshot *before,const struct snapshot *after,bool *started) {
    struct disk d={.fd=-1};struct snapshot current={0};bool ok=false;
    if(!transition(before,after))return false;
    /* First compare is read-only: malformed plans never open a block disk RW. */
    if(!open_disk(path,false,fixture,&d)||!capture(&d,&current,true))goto out;
    if(!same_snapshot(before,&current)){fail("current GPT differs from before snapshot");goto out;}
    free_snapshot(&current);close(d.fd);d.fd=-1;
    if(!open_disk(path,true,fixture,&d)||!capture(&d,&current,true))goto out;
    if(!same_snapshot(before,&current)){fail("current GPT changed before write");goto out;}
    free_snapshot(&current);
    /* Compare all source bytes once more immediately before the first write. */
    if(!capture(&d,&current,true))goto out;
    if(!same_snapshot(before,&current)){fail("current GPT changed at final write check");goto out;}
    free_snapshot(&current); *started=true;
    if(!disk_write(&d,after->be,after->padded,after->backup_entries*after->sector)||!flush_fd(d.fd)||
       !disk_write(&d,after->bh,after->sector,after->backup_header*after->sector)||!flush_fd(d.fd)||
       !readback_region(&d,after->be,after->padded,after->backup_entries*after->sector)||
       !readback_region(&d,after->bh,after->sector,after->backup_header*after->sector))goto out;
    if(!disk_write(&d,after->pe,after->padded,after->primary_entries*after->sector)||!flush_fd(d.fd)||
       !disk_write(&d,after->ph,after->sector,after->sector)||!flush_fd(d.fd)||!capture(&d,&current,true))goto out;
    if(!same_snapshot(after,&current)){fail("full GPT readback differs from after snapshot");goto out;}
    ok=true;
out:free_snapshot(&current);if(d.fd>=0)close(d.fd);return ok;
}
static bool self_test(void) {
    unsigned char p[8]={0},g[16];wr64(p,UINT64_C(0x123456789abcdef0));
    return (rd64(p)==UINT64_C(0x123456789abcdef0)&&crc32((const unsigned char *)"123456789",9)==UINT32_C(0xcbf43926)&&parse_guid("C12A7328-F81F-11D2-BA4B-00A0C93EC93B",g)&&g[0]==0x28&&g[3]==0xc1&&g[4]==0x1f&&g[5]==0xf8)||fail("self-test failed");
}
int main(int argc,char **argv) {
    uint32_t fixture=0;int pos=1;bool ok=false,started=false,binary=false;
    struct disk d={.fd=-1};struct snapshot before={0},after={0};uint64_t n;
    if(argc>1&&!strcmp(argv[1],"--fixture-sector")){
        if(argc<4||!decimal(argv[2],&n)||(n!=512&&n!=4096)){fail("--fixture-sector must be 512 or 4096");goto out;}
        fixture=(uint32_t)n;pos=3;
    }
    if(pos>=argc){fail("missing command");goto out;}
    const char *command=argv[pos++];
    if(!strcmp(command,"--self-test")&&pos==argc){ok=self_test();if(ok)puts("SELF_TEST_RESULT=PASS\nRESULT=success");goto out;}
    if(!strcmp(command,"verify")&&argc-pos==1){
        if(!open_disk(argv[pos],false,fixture,&d)||!capture(&d,&before,true))goto out;
        printf("GPT_VALID=1\nGPT_ATTRIBUTE_DIFF_COUNT=%u\nGPT_ATTRIBUTE_DIFF_MASK=%016" PRIx64 "\nGPT_STATUS=%s\nDISK_READ_MODE=%s\nRESULT=success\n",before.diff_count,before.diff_mask,before.diff_count?"attributes-differ":"clean",d.direct?"direct":"fixture");ok=true;
    }else if(!strcmp(command,"inspect")&&argc-pos==2){
        uint64_t index;
        if(!decimal(argv[pos+1],&index)){fail("invalid userdata index for inspect");goto out;}
        if(!open_disk(argv[pos],false,fixture,&d)||!capture(&d,&before,true))goto out;
        ok=inspect(&before,index,d.direct);
    }else if(!strcmp(command,"export")&&argc-pos==1){
        binary=true;
        if(!open_disk(argv[pos],false,fixture,&d)||!capture(&d,&before,false))goto out;
#ifdef _WIN32
        if(_setmode(_fileno(stdout),_O_BINARY)<0){fail("binary stdout setup failed");goto out;}
#endif
        if(fwrite(before.data,1,before.length,stdout)!=before.length||fflush(stdout)){fail("snapshot stdout failed");goto out;}
        fprintf(stderr,"SNAPSHOT_BYTES=%zu\nDISK_READ_MODE=%s\nRESULT=success\n",before.length,d.direct?"direct":"fixture");ok=true;
    }else if(!strcmp(command,"plan")&&argc-pos==7){
        uint64_t ud,end,efi;unsigned char guid[16];
        if(!decimal(argv[pos+1],&ud)||!decimal(argv[pos+2],&end)||!decimal(argv[pos+3],&efi)||!parse_guid(argv[pos+4],guid)){fail("invalid plan index/end/GUID");goto out;}
        if(!open_disk(argv[pos],false,fixture,&d)||!capture(&d,&before,true)||!plan(&before,ud,end,efi,guid,&after)||!save_pair(argv[pos+5],argv[pos+6],&before,&after))goto out;
        printf("GPT_ATTRIBUTE_DIFF_COUNT=%u\nGPT_ATTRIBUTE_DIFF_MASK=%016" PRIx64 "\nSNAPSHOT_BYTES=%zu\nDISK_READ_MODE=%s\nRESULT=success\n",before.diff_count,before.diff_mask,before.length,d.direct?"direct":"fixture");ok=true;
    }else if(!strcmp(command,"plan-range")&&argc-pos==8){
        uint64_t ud,end,efi,efi_end;unsigned char guid[16];
        if(!decimal(argv[pos+1],&ud)||!decimal(argv[pos+2],&end)||!decimal(argv[pos+3],&efi)||!decimal(argv[pos+4],&efi_end)||!parse_guid(argv[pos+5],guid)){fail("invalid plan-range index/end/GUID");goto out;}
        if(!open_disk(argv[pos],false,fixture,&d)||!capture(&d,&before,true)||!plan_range(&before,ud,end,efi,efi_end,guid,&after)||!save_pair(argv[pos+6],argv[pos+7],&before,&after))goto out;
        printf("GPT_ATTRIBUTE_DIFF_COUNT=%u\nGPT_ATTRIBUTE_DIFF_MASK=%016" PRIx64 "\nSNAPSHOT_BYTES=%zu\nDISK_READ_MODE=%s\nRESULT=success\n",before.diff_count,before.diff_mask,before.length,d.direct?"direct":"fixture");ok=true;
    }else if(!strcmp(command,"apply")&&argc-pos==4&&!strcmp(argv[pos+3],"--execute")){
        if(!load_snapshot(argv[pos+1],&before)||!load_snapshot(argv[pos+2],&after)||!apply(argv[pos],fixture,&before,&after,&started))goto out;
        printf("READBACK=match\nWRITE_ORDER=backup-then-primary\nDISK_READ_MODE=%s\nRESULT=success\n",fixture?"fixture":"direct");ok=true;
    }else fail("usage: [--fixture-sector 512|4096] verify|export DISK; inspect DISK UD_INDEX; plan DISK UD_INDEX NEW_UD_END EFI_INDEX EFI_GUID BEFORE_FILE AFTER_FILE; plan-range DISK UD_INDEX NEW_UD_END EFI_INDEX EFI_END EFI_GUID BEFORE_FILE AFTER_FILE; apply DISK BEFORE_FILE AFTER_FILE --execute; --self-test");
out:
    if(d.fd>=0)close(d.fd);free_snapshot(&before);free_snapshot(&after);
    if(!ok){fprintf(stderr,"ERROR=%s\nERRNO=%d\nRESULT=%s\n",error_text,errno,started?"partial":"error");if(!binary)printf("RESULT=%s\n",started?"partial":"error");}
    return ok?0:(started?2:1);
}
