/* SPDX-License-Identifier: MIT -- optional invocation-owned SDK storage. */
static void *sdk_memory_allocate(void *opaque,size_t bytes){
    struct native*c=context(opaque);
    if(!c||!c->in_job||!c->job_active||!c->job_id)return 0;
    if(c->p.current_el(c->p.opaque)!=c->el){c->fault=1;return 0;}
    if(!bytes)bytes=1;
    /* Tag before zeroing/checkpoints: cancelling a suspended allocation also
     * releases it. Runtime-internal owned allocations keep the zero tag. */
    return alloc_owned_tagged(c,bytes,c->job_id);
}
static int sdk_memory_release(void *opaque,void *memory,size_t bytes){
    struct native*c=context(opaque);
    if(!c||!c->in_job||!c->job_active||!c->job_id)return -KBL_EBUSY;
    if(c->p.current_el(c->p.opaque)!=c->el){c->fault=1;return -KBL_EIO;}
    if(!memory)return -KBL_EINVAL;
    for(struct owned*a=c->owned;a;a=a->next)
        if((void*)(a+1)==memory){
            if(a->owner!=c||a->program_job!=c->job_id||a->bytes!=(bytes?bytes:1))return -KBL_EINVAL;
            free_owned(c,memory);return 0;
        }
    return -KBL_EINVAL;
}
static void release_job_memory(struct native*c,uint64_t job){
    if(!job)return;
    if(c->p.current_el(c->p.opaque)!=c->el){c->fault=1;return;}
    struct owned*a=c->owned;
    while(a){struct owned*next=a->next;if(a->program_job==job)free_owned(c,a+1);a=next;}
}
