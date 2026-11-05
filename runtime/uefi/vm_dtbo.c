#include "vm_dtbo.h"

struct span { const uint8_t *p; uint32_t n; };
struct identity { struct span board,name,domain,vmid; };
static uint32_t be(const uint8_t *p) {
    return (uint32_t)p[0]<<24|(uint32_t)p[1]<<16|(uint32_t)p[2]<<8|p[3];
}
static int range(uint32_t at,uint32_t n,uint32_t limit) { return at<=limit&&n<=limit-at; }
static int eq(struct span s,const char *p) {
    uint32_t i=0; for(;i<s.n&&p[i];i++) if(s.p[i]!=(uint8_t)p[i])return 0;
    return i+1==s.n&&!p[i]&&!s.p[i];
}
static int put(struct span *s,const uint8_t *p,uint32_t n) {
    if(s->p)return 0; s->p=p;s->n=n;return 1;
}
/* Strict token walker: names are bounded by their own string/structure block,
 * property values never become C strings, duplicate identity fields are errors.
 * A path tag prevents a peer's image-name or vmid from becoming guest identity. */
static int fdt(const uint8_t *b,uint32_t bytes,struct identity *id) {
    if(bytes<40||be(b)!=0xd00dfeed)return 0;
    uint32_t total=be(b+4),at=be(b+8),strings=be(b+12),ns=be(b+32),nt=be(b+36);
    if(total<40||total>bytes||be(b+20)<17||be(b+24)>17||at<40||strings<40||
       !range(at,nt,total)||!range(strings,ns,total)||at%4||
       (at<strings+ns&&strings<at+nt))return 0;
    uint32_t end=at+nt,depth=0,config_depth=0,overlay_depth=0,vm_depth=0;int ended=0,root_seen=0;
    while(range(at,4,end)) {
        uint32_t token=be(b+at);at+=4;
        if(token==1) {
            uint32_t z=at;while(z<end&&b[z])z++;if(z==end)return 0;
            struct span name={b+at,z-at+1};
            if(depth==0) { if(name.n!=1||root_seen)return 0;root_seen=1; }
            else if(depth==1&&eq(name,"qcom,vm-config"))config_depth=depth+1;
            /* Standard overlay host declaration. Parent may be fragment@N. */
            if(depth>0&&eq(name,"__overlay__"))overlay_depth=depth+1;
            if(overlay_depth==depth&&eq(name,"qcom,gunyah-vm"))vm_depth=depth+1;
            depth++;if(z>0xfffffffbU)return 0;at=(z+4)&~3u;if(at>end)return 0;
        } else if(token==2) {
            if(!depth)return 0;
            if(config_depth==depth)config_depth=0;
            if(overlay_depth==depth)overlay_depth=0;
            if(vm_depth==depth)vm_depth=0;
            depth--;
        }
        else if(token==3) {
            if(!depth||!range(at,8,end))return 0;
            uint32_t n=be(b+at),key=be(b+at+4);at+=8;
            if(!range(at,n,end)||key>=ns)return 0;
            uint32_t z=key;while(z<ns&&b[strings+z])z++;if(z==ns)return 0;
            struct span name={b+strings+key,z-key+1};struct span *out=0;
            if(depth==1&&eq(name,"qcom,board-id"))out=&id->board;
            if(depth==config_depth) {
                if(eq(name,"image-name"))out=&id->name;
                if(eq(name,"qcom,secdomain-ids"))out=&id->domain;
            }
            if(depth==vm_depth&&eq(name,"qcom,vmid"))out=&id->vmid;
            if(out&&!put(out,b+at,n))return 0;
            if(n>0xfffffffcU-at)return 0;at=(at+n+3)&~3u;if(at>end)return 0;
        } else if(token==4) { }
        else if(token==9) { if(depth)return 0;ended=1;break; }
        else return 0;
    }
    return ended;
}
static int role_matches(const struct identity *id,uint32_t domain,int role) {
    if(!eq(id->name,role?"qcom,oemvm":"qcom,trustedvm")||
       id->domain.n!=4||be(id->domain.p)!=domain||id->vmid.n!=4||be(id->vmid.p)!=domain||
       !id->board.n||id->board.n%8)return 0;
    return 1;
}
static int board_matches(struct span board_ids,uint32_t board,uint32_t revision) {
    for(uint32_t i=0;i<board_ids.n;i+=8)
        if(be(board_ids.p+i)==board&&be(board_ids.p+i+4)==revision)return 1;
    return 0;
}
static int shared_board(struct span a,struct span b) {
    for(uint32_t i=0;i<a.n;i+=8)for(uint32_t j=0;j<b.n;j+=8)
        if(be(a.p+i)==be(b.p+j)&&be(a.p+i+4)==be(b.p+j+4))return 1;
    return 0;
}

__attribute__((section(".text.vm_dtbo_select")))
int vm_dtbo_select(const void *table,size_t available,uint32_t board,uint32_t revision,
                   int32_t indices[2],const uint32_t domains[2],uint32_t mode) {
    /* This check precedes every table/index/domain dereference. */
    if(mode!=VM_DTBO_NORMAL_AFTER_KEYS)return VM_DTBO_MODE;
    if(!table||!indices||!domains||available<32)return VM_DTBO_MALFORMED;
    const uint8_t *b=table;uint32_t total=be(b+4),header=be(b+8),stride=be(b+12),
        count=be(b+16),entries=be(b+20);
    if(be(b)!=0xd7b7ab1e||total>available||total<32||header<32||stride<32||
       !count||entries<header||entries>total||count>(total-entries)/stride||
       domains[0]==domains[1])return VM_DTBO_MALFORMED;
    uint32_t version=be(b+28);if(version>1)return VM_DTBO_UNSUPPORTED;
    int32_t selected[2]={-1,-1};uint32_t hits[2]={0,0};int valid[2]={0,0},anchor_valid[2]={0,0};
    struct identity anchors[2]={0};
    /* Validate the complete table before changing either output. Any malformed
     * entry aborts the transaction, including entries after the best candidate. */
    for(uint32_t i=0;i<count;i++) {
        const uint8_t *e=b+entries+i*stride;uint32_t n=be(e),at=be(e+4);
        if(version==1&&(be(e+16)&15))return VM_DTBO_UNSUPPORTED;
        if(at<entries+count*stride||!range(at,n,total))return VM_DTBO_MALFORMED;
        struct identity id={0};if(!fdt(b+at,n,&id))return VM_DTBO_MALFORMED;
        for(int role=0;role<2;role++)if(role_matches(&id,domains[role],role)) {
            if(indices[role]==(int32_t)i){anchors[role]=id;anchor_valid[role]=1;}
            if(board_matches(id.board,board,revision)) {
                selected[role]=(int32_t)i;hits[role]++;
                if(indices[role]==(int32_t)i)valid[role]=1;
            }
        }
    }
    if(hits[0]&&hits[1]) {
        for(int r=0;r<2;r++)if(!valid[r]&&hits[r]!=1)return VM_DTBO_AMBIGUOUS;
        int changed=0;for(int r=0;r<2;r++)if(!valid[r]) { indices[r]=selected[r];changed=1; }
        return changed?VM_DTBO_FIXED:VM_DTBO_KEPT;
    }
    /* A partially present HW tuple is not permission to mix boards. The
     * anchored downgrade path is for HW tuples absent from this old DTBO. */
    if(hits[0]||hits[1])return VM_DTBO_NO_MATCH;
    if(anchor_valid[0]&&anchor_valid[1])
        return shared_board(anchors[0].board,anchors[1].board)?VM_DTBO_KEPT:VM_DTBO_AMBIGUOUS;
    if(!anchor_valid[0]&&!anchor_valid[1])return VM_DTBO_NO_MATCH;
    int anchor=anchor_valid[0]?0:1,missing=anchor^1;uint32_t matches=0;int32_t found=-1;
    for(uint32_t i=0;i<count;i++) {
        const uint8_t *e=b+entries+i*stride;struct identity id={0};
        if(!fdt(b+be(e+4),be(e),&id))return VM_DTBO_MALFORMED;
        if(role_matches(&id,domains[missing],missing)&&shared_board(anchors[anchor].board,id.board)) {
            matches++;found=(int32_t)i;
        }
    }
    if(!matches)return VM_DTBO_NO_MATCH;
    if(matches!=1)return VM_DTBO_AMBIGUOUS;
    indices[missing]=found;return VM_DTBO_FIXED;
}
