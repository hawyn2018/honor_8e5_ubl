/* SPDX-License-Identifier: MIT -- sparse metadata arithmetic regression. */
#include <config.h>
#include "../../../third_party/erofs-utils/lib/zmap.c"
int printf(const char *,...);
static struct z_erofs_lcluster_index index_entry;
static erofs_off_t last_offset;
void *erofs_read_metabuf(struct erofs_buf *b,struct erofs_sb_info*s,erofs_off_t o,bool box){(void)b;(void)s;(void)box;last_offset=o;return &index_entry;}
void *erofs_bread(struct erofs_buf*b,erofs_off_t o,bool map){(void)b;(void)o;(void)map;return &index_entry;}
int main(void){
  struct erofs_sb_info sb={.blkszbits=12};
  const u64 cases[]={1,((u64)1<<20)+3,((u64)1<<32)+3};
  index_entry.di_advise=cpu_to_le16(Z_EROFS_LCLUSTER_TYPE_HEAD1);
  index_entry.di_clusterofs=0;index_entry.di_u.blkaddr=cpu_to_le32(7);
  for(unsigned i=0;i<sizeof(cases)/sizeof(cases[0]);++i){
    struct erofs_inode vi={.sbi=&sb,.nid=1,.datalayout=EROFS_INODE_COMPRESSED_FULL,.z_lclusterbits=12,.i_size=(cases[i]+2)<<12};
    struct erofs_map_blocks map={.buf=__EROFS_BUF_INITIALIZER,.m_la=(cases[i]<<12)+64};
    erofs_off_t expected=Z_EROFS_FULL_INDEX_START(erofs_iloc(&vi)+vi.inode_isize+vi.xattr_isize)+cases[i]*sizeof(index_entry);
    int r=z_erofs_map_blocks_fo(&vi,&map,0);
    if(r||map.m_la!=(cases[i]<<12)||map.m_llen!=4096||map.m_pa!=7*4096||last_offset!=expected)return 1;
  }
  printf("EROFS LLP64 cluster offsets across 4GiB and 16TiB PASS long=%u-bit\n",(unsigned)(sizeof(long)*8));
  return 0;
}
