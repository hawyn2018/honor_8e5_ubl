/* SPDX-License-Identifier: MIT
 * Actual AArch64 backend/fiber tests. Only platform callbacks are emulator
 * providers; these do not represent physical UEFI or device acceptance. */
#include "../kbl_native.h"
#include "../kbl_context.h"
#include "../kbl_native_io.h"
#include "efi_bootloader_fixture.h"
extern void *test_allocate(void *,size_t,size_t);
extern void test_release(void *,void *,size_t);
extern unsigned test_current_el(void *);
extern int test_elf_allocate(void *,size_t,size_t,struct kbl_elf_allocation *);
extern void test_elf_release(void *,struct kbl_elf_allocation *);
extern int test_protect(void *,struct kbl_elf_allocation *,size_t,size_t,unsigned);
extern int test_icache(void *,struct kbl_elf_allocation *,size_t,size_t);
extern unsigned test_live_allocations(void);
extern void test_fail_after(unsigned);
extern void test_fail_clear(void);
extern const unsigned char demo_program[];
extern const unsigned long demo_program_bytes;
extern const unsigned char file_program[];
extern const unsigned long file_program_bytes;
extern const unsigned char memory_program[],memory_port_program[];
extern const unsigned long memory_program_bytes,memory_port_program_bytes;

volatile unsigned native_test_checks, native_test_failed;
volatile unsigned native_test_groups;
volatile unsigned native_memory_fail_large;
volatile unsigned native_test_observed_el;
volatile unsigned native_test_sessions,native_test_arguments,native_test_command_bytes,native_test_path_bytes,native_test_options_bytes,native_test_exit_units;
static unsigned char output[16384];
static size_t output_bytes;
static struct kbl_backend b;
static struct kbl_native_platform p;
static void *ctx;
static unsigned clock_enabled,last_polls;static uint64_t clock_value,clock_step;
static uint64_t fixture_clock(void*opaque){(void)opaque;clock_value+=clock_step;return clock_value;}
#define CHECK(x) do { ++native_test_checks; if(!(x)) { native_test_failed=__LINE__; return 0; } }while(0)
static size_t len(const char *s) { size_t n=0; while(s[n])++n; return n; }
static int same(const void *a_,const void *b_,size_t n) { const unsigned char *a=a_,*d=b_; while(n--)if(*a++!=*d++)return 0;return 1; }
static int contains(const char *needle) {
    size_t n=len(needle); for(size_t at=0;at+n<=output_bytes;++at)if(same(output+at,needle,n))return 1;return 0;
}
static int init(void) {
    p=(struct kbl_native_platform){0};
    p.allocate=test_allocate; p.release=test_release; p.current_el=test_current_el;
    p.now_us=clock_enabled?fixture_clock:0;
    p.stack_bytes=32768; p.output_bytes=256; p.max_file_bytes=65536; p.max_ramfs_bytes=131072; p.max_nodes=32;
    p.elf=(struct kbl_elf_host){0,4096,65536,test_elf_allocate,test_elf_release,test_protect,test_icache};
    CHECK(kbl_native_backend(&b,&p)==KBL_OK);
    CHECK(!test_live_allocations()); ctx=0; CHECK(b.start(b.opaque,&ctx)==KBL_OK && ctx); return 1;
}
static int finish(void) { CHECK(b.stop(ctx)==KBL_OK); ctx=0; CHECK(!test_live_allocations()); return 1; }
static int run(const char *cmd,int want_rc) {
    uint64_t job=0; CHECK(b.submit(ctx,(const unsigned char *)cmd,len(cmd),&job)==KBL_OK && job);
    output_bytes=0; int done=0,rc=-777; unsigned polls=0;
    while(!done && polls++<20000) {
        size_t n=0; CHECK(output_bytes+7<sizeof(output));
        CHECK(b.poll(ctx,job,2000,output+output_bytes,7,&n,&done,&rc)==KBL_OK);
        CHECK(n<=7); output_bytes+=n;
    }
    last_polls=polls;CHECK(done && rc==want_rc); return 1;
}
static int file(const char *name,const unsigned char *expected,size_t bytes) {
    unsigned char buffer[512]; size_t got=0; uint64_t size=0;
    CHECK(bytes<=sizeof(buffer)); CHECK(kbl_native_get(ctx,name,0,buffer,sizeof(buffer),&got,&size)==KBL_OK);
    CHECK(got==bytes && size==bytes && same(buffer,expected,bytes)); return 1;
}
static int files_and_commands(void) {
    CHECK(init());
    const unsigned char binary[]={0,1,2,0xff,'A','\n',0,'Z'};
    CHECK(kbl_native_put(ctx,"/bin",binary,sizeof(binary))==KBL_OK);
    CHECK(file("/bin",binary,sizeof(binary)));
    CHECK(run("pwd",0) && output_bytes==2 && same(output,"/\n",2));
    CHECK(run("mkdir /work",0)); CHECK(run("cd /work",0));
    CHECK(run("pwd",0) && contains("/work\n"));
    CHECK(run("touch empty",0)); CHECK(file("/work/empty",binary,0));
    CHECK(run("cp /bin ./copy",0)); CHECK(file("/work/copy",binary,sizeof(binary)));
    CHECK(run("mv copy renamed",0)); CHECK(file("/work/renamed",binary,sizeof(binary)));
    CHECK(run("cat renamed",0) && output_bytes==sizeof(binary) && same(output,binary,sizeof(binary)));
    CHECK(run("hex renamed",0) && contains("00 01 02 ff 41 0a 00 5a"));
    CHECK(run("ls",0) && contains("renamed") && contains("empty"));
    CHECK(run("stat renamed",0) && contains("type=file size=8"));
    CHECK(run("echo 'a b' \"c d\" e\\ f",0) && contains("a b c d e f\n"));
    CHECK(run("echo -n xyz",0) && output_bytes==3 && same(output,"xyz",3));
    CHECK(run("true",0)); CHECK(run("false",1));
    CHECK(run("exit",0) && contains("runtime retained"));
    CHECK(run("pwd",0) && contains("/work\n"));
    CHECK(run("rm renamed",0)); CHECK(run("rm /work",1));
    CHECK(run("cd /",0)); CHECK(run("rm /work",1));
    CHECK(run("rm /work/empty",0)); CHECK(run("rm /work",0));
    CHECK(run("el",0) && contains("EL")); CHECK(finish()); ++native_test_groups; return 1;
}
static int snapshots_and_atomicity(void) {
    CHECK(init()); const unsigned char old[]={0,1,2,3},fresh[]={4,5,6};
    CHECK(kbl_native_put(ctx,"/file",old,sizeof(old))==KBL_OK);
    struct kbl_reader reader={0}; CHECK(kbl_native_open_snapshot(ctx,"/file",&reader)==KBL_OK);
    void *original_snapshot=reader.opaque;
    CHECK(kbl_native_open_snapshot(ctx,"/file",&reader)==KBL_ERROR && reader.opaque==original_snapshot);
    CHECK(reader.size==sizeof(old)); CHECK(b.stop(ctx)==KBL_AGAIN);
    CHECK(kbl_native_put(ctx,"/file",fresh,sizeof(fresh))==KBL_OK);
    unsigned char got[8]; size_t n=0; CHECK(reader.read_at(reader.opaque,0,got,sizeof(got),&n)==KBL_OK);
    CHECK(n==sizeof(old) && same(got,old,n)); reader.close(reader.opaque);
    CHECK(file("/file",fresh,sizeof(fresh)));
    test_fail_after(0); CHECK(kbl_native_put(ctx,"/file",old,sizeof(old))==KBL_ERROR); test_fail_clear();
    CHECK(file("/file",fresh,sizeof(fresh)));
    uint64_t job; CHECK(b.submit(ctx,(const unsigned char *)"echo queued",11,&job)==KBL_OK);
    CHECK(kbl_native_put(ctx,"/file",old,sizeof(old))==KBL_AGAIN);
    CHECK(file("/file",fresh,sizeof(fresh)));
    CHECK(finish()); ++native_test_groups; return 1;
}
static int unavailable_execution_provider(void) {
    CHECK(init()); CHECK(finish());
    p.elf.protect=0; ctx=0; CHECK(b.start(b.opaque,&ctx)==KBL_OK);
    CHECK(kbl_native_put(ctx,"/hello",demo_program,demo_program_bytes)==KBL_OK);
    CHECK(run("./hello",126) && contains("ELF load failed"));
    CHECK(run("echo still-alive",0) && contains("still-alive"));
    CHECK(finish()); ++native_test_groups; return 1;
}
static int backpressure_and_stop(void) {
    CHECK(init()); unsigned char source[1024]; for(size_t i=0;i<sizeof(source);++i)source[i]=(unsigned char)i;
    CHECK(kbl_native_put(ctx,"/large",source,sizeof(source))==KBL_OK);
    CHECK(run("cat /large",0)); CHECK(output_bytes==sizeof(source) && same(output,source,sizeof(source)));
    uint64_t job; CHECK(b.submit(ctx,(const unsigned char *)"cat /large",10,&job)==KBL_OK);
    size_t n=0; int done=0,rc=0;
    CHECK(b.poll(ctx,job,2000,output,3,&n,&done,&rc)==KBL_OK && !done);
    /* Discarding a cooperatively suspended job is explicit stop only. */
    CHECK(finish()); ++native_test_groups; return 1;
}
static int copying_publishes_complete_files(void) {
    CHECK(init()); unsigned char source[400],old[]={9,8,7,6};
    for(size_t i=0;i<sizeof(source);++i)source[i]=(unsigned char)(i*3);
    CHECK(kbl_native_put(ctx,"/source",source,sizeof(source))==KBL_OK);
    CHECK(kbl_native_put(ctx,"/existing",old,sizeof(old))==KBL_OK);
    const char *commands[]={"cp /source /new","cp /source /existing"};
    for(unsigned which=0;which<2;++which) {
        uint64_t job; int done=0,rc=0; size_t n=0; unsigned polls=0;
        CHECK(b.submit(ctx,(const unsigned char *)commands[which],len(commands[which]),&job)==KBL_OK);
        CHECK(b.poll(ctx,job,2000,output,7,&n,&done,&rc)==KBL_OK && !done && !n);
        if(which==0) {
            uint64_t size=0; CHECK(kbl_native_get(ctx,"/new",0,output,8,&n,&size)==KBL_ERROR);
        } else CHECK(file("/existing",old,sizeof(old)));
        while(!done && polls++<1000) CHECK(b.poll(ctx,job,2000,output,7,&n,&done,&rc)==KBL_OK);
        CHECK(done && !rc);
        CHECK(file(which?"/existing":"/new",source,sizeof(source)));
    }
    CHECK(finish()); ++native_test_groups; return 1;
}
static int invalid_and_cleanup(void) {
    CHECK(init()); uint64_t job;
    const unsigned char nul[]={'e','c','h','o',0,'x'};
    CHECK(b.submit(ctx,nul,sizeof(nul),&job)==KBL_ERROR);
    CHECK(b.submit(ctx,(const unsigned char *)"echo a | cat",12,&job)==KBL_ERROR);
    CHECK(b.submit(ctx,(const unsigned char *)"echo '",6,&job)==KBL_ERROR);
    CHECK(run("mv / /bad",1)); CHECK(run("mkdir /a",0)); CHECK(run("mkdir /a/b",0));
    CHECK(run("mv /a /a/b/new",1)); CHECK(run("cat /missing",1));
    CHECK(run("unknown",1)); CHECK(run("./missing",1));
    CHECK(kbl_native_put(ctx,"/bad",nul,sizeof(nul))==KBL_OK); CHECK(run("./bad",126));
    CHECK(finish());
    /* All start-failure resources are retained in ctx for explicit stop. */
    for(unsigned fail=0;fail<4;++fail) {
        ctx=0; test_fail_after(fail); int result=b.start(b.opaque,&ctx); test_fail_clear();
        if(ctx) CHECK(b.stop(ctx)==KBL_OK);
        CHECK(result==KBL_OK || result==KBL_ERROR); CHECK(!test_live_allocations());
    }
    ++native_test_groups; return 1;
}
static int program_and_resume(void) {
    CHECK(init()); CHECK(kbl_native_put(ctx,"/hello",demo_program,demo_program_bytes)==KBL_OK);
    CHECK(run("./hello one 'two three'",0));
    CHECK(contains("KBL demo before yield") && contains("KBL demo after yield"));
    CHECK(contains("CurrentEL=") && contains("argv[0]=./hello"));
    CHECK(kbl_native_put(ctx,"/vfs-test",file_program,file_program_bytes)==KBL_OK);
    CHECK(run("./vfs-test",0)&&contains("SDK VFS passed"));
    struct kbl_native_stats stats;CHECK(!kbl_native_get_stats(ctx,&stats)&&!stats.open_files);
    const unsigned char expected[]={'a',0,'b'};CHECK(file("/sdk-file",expected,sizeof(expected)));
    CHECK(finish()); ++native_test_groups; return 1;
}
static int hashes_and_word_counts(void) {
    CHECK(init());const unsigned char lines[]="alpha beta\nGamma\nalpha.beta\nlast";
    CHECK(kbl_native_put(ctx,"/lines",lines,sizeof(lines)-1)==KBL_OK);
    CHECK(kbl_native_put(ctx,"/abc","abc",3)==KBL_OK);CHECK(kbl_native_put(ctx,"/empty",0,0)==KBL_OK);
    CHECK(run("sha256sum /abc /empty",0));
    CHECK(contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  /abc\n"));
    CHECK(contains("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  /empty\n"));
    CHECK(run("wc /lines",0)&&contains("3 5 32 /lines\n"));
    CHECK(run("wc -l /lines",0)&&contains("3 /lines\n"));
    CHECK(run("wc -wc /lines /abc",0)&&contains("6 35 total\n"));
    CHECK(run("wc -c -- /empty",0)&&contains("0 /empty\n"));
    CHECK(run("sha256sum /missing",1));CHECK(run("wc -z /lines",1));
    CHECK(finish());++native_test_groups;return 1;
}
static int line_edges_and_literal_grep(void) {
    CHECK(init());const unsigned char lines[]="alpha beta\nGamma\nalpha.beta\nlast";
    const unsigned char binary[]={'a',0,'b','\n','c'};
    CHECK(kbl_native_put(ctx,"/lines",lines,sizeof(lines)-1)==KBL_OK);
    CHECK(kbl_native_put(ctx,"/binary",binary,sizeof(binary))==KBL_OK);
    CHECK(kbl_native_put(ctx,"/newlines","a\n\n",3)==KBL_OK);
    CHECK(kbl_native_put(ctx,"/empty",0,0)==KBL_OK);
    CHECK(run("head -n 1 /lines",0)&&output_bytes==11&&same(output,lines,11));
    CHECK(run("tail -n 1 /lines",0)&&output_bytes==4&&same(output,"last",4));
    CHECK(run("tail -n 1 /newlines",0)&&output_bytes==1&&output[0]=='\n');
    CHECK(run("tail -n 100 /lines",0)&&output_bytes==sizeof(lines)-1&&same(output,lines,sizeof(lines)-1));
    CHECK(run("head -n 0 /lines",0)&&!output_bytes);CHECK(run("tail -c 0 /lines",0)&&!output_bytes);
    CHECK(run("head -c 3 /binary",0)&&output_bytes==3&&same(output,binary,3));
    CHECK(run("tail -c 2 /binary",0)&&output_bytes==2&&same(output,binary+3,2));
    CHECK(run("head /empty",0)&&!output_bytes);CHECK(run("tail /empty",0)&&!output_bytes);
    CHECK(run("head -n 18446744073709551616 /lines",1));CHECK(run("tail -n -1 /lines",1));
    CHECK(run("grep -n alpha /lines",0)&&contains("1:alpha beta\n3:alpha.beta\n"));
    CHECK(run("grep -i gamma /lines",0)&&output_bytes==6&&same(output,"Gamma\n",6));
    CHECK(run("grep 'alpha.' /lines",0)&&output_bytes==11&&same(output,"alpha.beta\n",11));
    CHECK(run("grep '[a]' /lines",1)&&!output_bytes);
    CHECK(run("grep -cv alpha /lines",0)&&output_bytes==2&&same(output,"2\n",2));
    CHECK(run("grep '' /empty",1)&&!output_bytes);
    CHECK(run("grep '' /lines",0)&&output_bytes==sizeof(lines)&&same(output,lines,sizeof(lines)-1)&&output[sizeof(lines)-1]=='\n');
    CHECK(run("grep b /binary",0)&&output_bytes==4&&same(output,binary,4));
    CHECK(run("grep -E x /lines",2));CHECK(run("grep x /missing",2));
    CHECK(finish());++native_test_groups;return 1;
}
static int dumps_paths_and_formats(void) {
    CHECK(init());unsigned char data[512];for(size_t i=0;i<sizeof(data);++i)data[i]=(unsigned char)i;
    CHECK(kbl_native_put(ctx,"/binary",data,sizeof(data))==KBL_OK);
    CHECK(run("hexdump -C /binary",0)&&contains("000001f0")&&contains("|................|"));
    CHECK(output_bytes>256);
    CHECK(run("xxd -l 8 /binary",0)&&contains("00 01 02 03 04 05 06 07")&&!contains("00000010"));
    CHECK(run("hexdump -n 0 /binary",0)&&!output_bytes);
    CHECK(run("basename /tmp/file.img .img",0)&&output_bytes==5&&same(output,"file\n",5));
    CHECK(run("basename a///",0)&&output_bytes==2&&same(output,"a\n",2));
    CHECK(run("basename ////",0)&&output_bytes==2&&same(output,"/\n",2));
    CHECK(run("dirname /tmp/file",0)&&output_bytes==5&&same(output,"/tmp\n",5));
    CHECK(run("dirname file",0)&&output_bytes==2&&same(output,".\n",2));
    CHECK(run("dirname /file",0)&&output_bytes==2&&same(output,"/\n",2));
    CHECK(run("printf 'n=%d u=%u x=%x X=%X c=%c %s %%\\n' -7 18446744073709551615 255 16 Z ok",0));
    CHECK(contains("n=-7 u=18446744073709551615 x=ff X=10 c=Z ok %\n"));
    CHECK(run("printf '%d' -9223372036854775808",0)&&contains("-9223372036854775808"));
    CHECK(run("printf '%d' -0",0)&&output_bytes==1&&output[0]=='0');
    CHECK(run("printf '%c' ''",0)&&output_bytes==1&&!output[0]);
    CHECK(run("printf 'prefix%q' value",1)&&!contains("prefix"));
    CHECK(run("printf '%d' 9223372036854775808",1));
    CHECK(run("printf '%s' x y",1));
    CHECK(run("help",0)&&contains("sha256sum")&&contains("literal bytes"));
    CHECK(finish());++native_test_groups;return 1;
}
static int large_command_checkpoints(void) {
    CHECK(init());unsigned char data[4096];for(size_t i=0;i<sizeof(data);++i)data[i]='a';
    CHECK(kbl_native_put(ctx,"/large",data,sizeof(data))==KBL_OK);
    const char *commands[]={"sha256sum /large","wc /large","tail -n 10 /large","head -n 10 /large","grep b /large"};
    for(unsigned i=0;i<sizeof(commands)/sizeof(commands[0]);++i){uint64_t job=0;size_t n=99;int done=0,rc=0;
        CHECK(b.submit(ctx,(const unsigned char*)commands[i],len(commands[i]),&job)==KBL_OK);
        /* Reading/scanning yields before output even without output-ring pressure. */
        CHECK(b.poll(ctx,job,2000,output,7,&n,&done,&rc)==KBL_OK&&!done&&!n);
        CHECK(finish());CHECK(init());CHECK(kbl_native_put(ctx,"/large",data,sizeof(data))==KBL_OK);
    }
    /* A repeated-prefix literal exercises checkpoints inside the match loop. */
    CHECK(run("grep aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab /large",1)&&!output_bytes);
    CHECK(run("head -c 4096 /large",0)&&output_bytes==sizeof(data)&&same(output,data,sizeof(data)));
    CHECK(run("tail -c 4096 /large",0)&&output_bytes==sizeof(data)&&same(output,data,sizeof(data)));
    CHECK(finish());++native_test_groups;return 1;
}
static int external_io_session(void){
    CHECK(init());const struct kbl_program_api*api=0;CHECK(!kbl_native_io_begin(ctx,&api)&&api);
    struct kbl_native_stats stats;CHECK(!kbl_native_get_stats(ctx,&stats)&&!stats.in_job);
    uint64_t job=0;CHECK(b.submit(ctx,(const unsigned char*)"true",4,&job)==KBL_AGAIN);CHECK(b.stop(ctx)==KBL_AGAIN);
    kbl_file_handle h=0;size_t n=0;CHECK(!api->file_open(api->host,"/external-io",KBL_FILE_WRITE|KBL_FILE_CREATE,&h));
    CHECK(!api->file_write(api->host,h,"external",8,&n)&&n==8);
    CHECK(!kbl_native_get_stats(ctx,&stats)&&!stats.in_job&&stats.open_files==1);
    char name[64];struct kbl_file_stat st;int end=0;CHECK(!kbl_native_io_list(ctx,api,"/",0,name,sizeof(name),&st,&end)&&!end);
    CHECK(!kbl_native_io_end(ctx,api));CHECK(!kbl_native_get_stats(ctx,&stats)&&!stats.open_files&&!stats.in_job);
    CHECK(run("cat /external-io",0)&&output_bytes==8&&same(output,"external",8));CHECK(finish());++native_test_groups;return 1;
}
static int deadline_checkpoints(void){
    clock_enabled=1;clock_value=clock_step=0;CHECK(init());
    CHECK(kbl_native_put(ctx,"/vfs-test",file_program,file_program_bytes)==KBL_OK);
    CHECK(run("./vfs-test",0));unsigned within_deadline=last_polls;
    CHECK(run("./vfs-test force",0)&&last_polls==within_deadline+1);
    clock_step=5000;CHECK(run("./vfs-test",0)&&last_polls>within_deadline);
    CHECK(finish());clock_enabled=0;clock_step=0;++native_test_groups;return 1;
}
static int session_run(void*s,const char*line,int want){
    uint64_t job=0;CHECK(!kbl_native_session_submit(ctx,s,(const unsigned char*)line,len(line),&job));
    output_bytes=0;int done=0,rc=0;unsigned count=0;
    while(!done&&count++<20000){size_t n=0;CHECK(output_bytes+7<sizeof(output));
        CHECK(!kbl_native_session_poll(ctx,s,job,2000,output+output_bytes,7,&n,&done,&rc));output_bytes+=n;}
    CHECK(done&&rc==want);return 1;
}
static int independent_sessions(void){
    CHECK(init());void*a=0,*d=0,*io1=0,*io2=0;
    CHECK(!kbl_native_session_create(ctx,&a));CHECK(!kbl_native_session_create(ctx,&d));
    CHECK(!kbl_native_session_create(ctx,&io1));CHECK(!kbl_native_session_create(ctx,&io2));
    CHECK(b.stop(ctx)==KBL_AGAIN);const struct kbl_program_api*api=0,*same_api=0;
    /* An idle Shell owns no filesystem transaction and does not block pull. */
    CHECK(!kbl_native_session_io_begin(ctx,io1,&api));CHECK(!kbl_native_session_io_end(ctx,io1,api));
    CHECK(session_run(a,"mkdir /first",0));CHECK(session_run(a,"cd /first",0));
    CHECK(session_run(d,"pwd",0)&&output_bytes==2&&same(output,"/\n",2));
    CHECK(session_run(d,"rm /first",1));CHECK(session_run(d,"mv /first /renamed",1));CHECK(session_run(a,"touch shared",0));
    CHECK(session_run(d,"stat /first/shared",0)&&contains("size=0"));
    uint64_t aj=0,dj=0;
    CHECK(!kbl_native_session_submit(ctx,a,(const unsigned char*)"echo first",10,&aj));
    CHECK(!kbl_native_session_submit(ctx,d,(const unsigned char*)"echo second",11,&dj));
    output_bytes=0;int done=0,rc=0;unsigned attempts=0;
    while(!done&&attempts++<100){size_t n=0;CHECK(!kbl_native_session_poll(ctx,d,dj,2000,output+output_bytes,7,&n,&done,&rc));output_bytes+=n;}
    CHECK(done&&!rc&&output_bytes==7&&same(output,"second\n",7));
    output_bytes=0;done=0;attempts=0;
    while(!done&&attempts++<100){size_t n=0;CHECK(!kbl_native_session_poll(ctx,a,aj,2000,output+output_bytes,7,&n,&done,&rc));output_bytes+=n;}
    CHECK(done&&!rc&&output_bytes==6&&same(output,"first\n",6));
    unsigned char bytes[512];for(size_t i=0;i<sizeof(bytes);++i)bytes[i]=(unsigned char)i;
    CHECK(!kbl_native_put(ctx,"/bytes",bytes,sizeof(bytes)));
    CHECK(!kbl_native_session_submit(ctx,a,(const unsigned char*)"cat /bytes",10,&aj));
    size_t n=0;done=0;CHECK(!kbl_native_session_poll(ctx,a,aj,2000,output,7,&n,&done,&rc)&&!done);
    CHECK(kbl_native_session_io_begin(ctx,io1,&api)==KBL_AGAIN);
    CHECK(!kbl_native_session_submit(ctx,d,(const unsigned char*)"echo after",10,&dj));
    CHECK(kbl_native_session_io_begin(ctx,io2,&same_api)==KBL_AGAIN);
    CHECK(kbl_native_session_io_begin(ctx,io1,&api)==KBL_AGAIN);
    /* Detach only this suspended job; its queued followers survive. */
    CHECK(!kbl_native_session_close(ctx,a));a=0;
    CHECK(!kbl_native_session_io_begin(ctx,io1,&api));
    CHECK(!kbl_native_session_io_begin(ctx,io1,&same_api)&&api==same_api);
    kbl_file_handle handle=0;CHECK(!api->file_open(api->host,"/from-sync",KBL_FILE_WRITE|KBL_FILE_CREATE,&handle));
    CHECK(!api->file_write(api->host,handle,"once",4,&n)&&n==4);
    CHECK(!kbl_native_session_close(ctx,io2));io2=0;
    CHECK(!api->file_write(api->host,handle,"!",1,&n)&&n==1);
    CHECK(!kbl_native_session_io_end(ctx,io1,api));
    output_bytes=0;done=0;attempts=0;
    while(!done&&attempts++<100){n=0;CHECK(!kbl_native_session_poll(ctx,d,dj,2000,output+output_bytes,7,&n,&done,&rc));output_bytes+=n;}
    CHECK(done&&!rc&&output_bytes==6&&same(output,"after\n",6));
    CHECK(!kbl_native_session_io_begin(ctx,io1,&api));
    CHECK(api->file_write(api->host,handle,"X",1,&n)==-KBL_EBADF);
    void*cancelled=0;CHECK(!kbl_native_session_create(ctx,&cancelled));uint64_t cj=0;
    CHECK(!kbl_native_session_submit(ctx,cancelled,(const unsigned char*)"mkdir /cancelled",16,&cj));
    CHECK(!kbl_native_session_close(ctx,cancelled));
    CHECK(!kbl_native_session_io_end(ctx,io1,api));
    CHECK(session_run(d,"cat /from-sync",0)&&output_bytes==5&&same(output,"once!",5));
    CHECK(session_run(d,"stat /cancelled",1));
    CHECK(!kbl_native_session_close(ctx,d));CHECK(!kbl_native_session_close(ctx,io1));
    CHECK(run("rm /first/shared",0));CHECK(run("rm /first",0));
    CHECK(finish());++native_test_groups;return 1;
}
static unsigned errorcount_calls,errorcount_failure;
static int fixture_errorcount(void*opaque){(void)opaque;++errorcount_calls;return errorcount_failure?-KBL_EIO:0;}
static unsigned efi_calls,efi_error,efi_provider_error;
static const unsigned char*efi_expected_image=(const unsigned char*)"MZ!!";
static size_t efi_expected_image_bytes=4;
static const uint16_t efi_default_options[]={'o','n','e',' ','"','a',' ','b','"',0};
static const uint16_t*efi_expected_options=efi_default_options;
static size_t efi_expected_options_bytes=sizeof(efi_default_options);
static int fixture_efi(void*opaque,const void*image,size_t image_bytes,const uint16_t*options,size_t options_bytes,struct kbl_efi_run_result*r){
    (void)opaque;++efi_calls;
    if(image_bytes!=efi_expected_image_bytes||!same(image,efi_expected_image,image_bytes)||
        options_bytes!=efi_expected_options_bytes||!same(options,efi_expected_options,options_bytes))return -KBL_EINVAL;
    if(efi_provider_error)return -KBL_EIO;
    r->stage=KBL_EFI_RETURN;r->el_before=r->el_after=test_current_el(0);r->exit_units=2;r->exit_text[0]='O';r->exit_text[1]='K';
    r->status=efi_error?UINT64_C(0x8000000000000007):0;return 0;
}
static int explicit_efi_application(void){
    CHECK(init());CHECK(!efi_calls);CHECK(kbl_native_put(ctx,"/tool.efi","MZ!!",4)==KBL_OK);
    CHECK(run("efi /tool.efi",1)&&contains("provider unavailable"));
    CHECK(run("clear errorcount",1)&&contains("provider unavailable")&&!errorcount_calls);CHECK(finish());
    p.efi_run=fixture_efi;ctx=0;CHECK(!b.start(b.opaque,&ctx));
    CHECK(!efi_calls);CHECK(kbl_native_put(ctx,"/tool.efi","MZ!!",4)==KBL_OK);
    CHECK(run("true",0)&&!efi_calls);CHECK(run("efi /tool.efi one 'a b'",0)&&efi_calls==1&&contains("EFI exit data: OK"));
    CHECK(contains("status=0x0000000000000000"));efi_error=1;
    CHECK(run("efi /tool.efi one 'a b'",1)&&efi_calls==2&&contains("status=0x8000000000000007"));efi_error=0;
    CHECK(run("efi -bl",1)&&contains("requires PATH")&&efi_calls==2);
    CHECK(run("efi --bootloader ''",1)&&contains("requires PATH")&&efi_calls==2);
    CHECK(run("efi -bl --bootloader /tool.efi",1)&&contains("duplicate")&&efi_calls==2);
    CHECK(run("efi --bootloader -bl /tool.efi",1)&&contains("duplicate")&&efi_calls==2);
    CHECK(run("efi -bl /missing.efi",1)&&contains("file missing")&&efi_calls==2);
    struct kbl_native_stats before,after;CHECK(!kbl_native_get_stats(ctx,&before));
    CHECK(run("efi -bl /tool.efi one 'a b'",1)&&contains("EFI bootloader preparation")&&efi_calls==2);
    CHECK(!kbl_native_get_stats(ctx,&after));CHECK(before.owned_allocations==after.owned_allocations&&before.owned_bytes==after.owned_bytes);
    CHECK(file("/tool.efi",(const unsigned char*)"MZ!!",4));
    /* Default efi neither validates nor modifies the file through the optional
     * bootloader preparer, including a child argument spelled like our switch. */
    static const uint16_t child_flags[]={'-','b','l',' ','-','-','b','o','o','t','l','o','a','d','e','r',0};
    efi_expected_options=child_flags;efi_expected_options_bytes=sizeof(child_flags);
    CHECK(run("efi /tool.efi -bl --bootloader",0)&&efi_calls==3);
    efi_expected_options=efi_default_options;efi_expected_options_bytes=sizeof(efi_default_options);
    efi_provider_error=1;CHECK(run("efi /tool.efi one 'a b'",1)&&efi_calls==4&&contains("provider errno"));efi_provider_error=0;
    CHECK(!kbl_native_get_stats(ctx,&after));CHECK(before.owned_allocations==after.owned_allocations&&before.owned_bytes==after.owned_bytes);
    CHECK(run("efi /tool.efi '\xc0\xaf'",1)&&contains("invalid UTF-8")&&efi_calls==4);
    CHECK(!kbl_native_get_stats(ctx,&after));CHECK(before.owned_allocations==after.owned_allocations&&before.owned_bytes==after.owned_bytes);
    /* -cs is opt-in, options stop at PATH, and both aliases preserve args. */
    CHECK(run("efi -cs /tool.efi one 'a b'",1)&&contains("provider unavailable"));
    CHECK(finish());p.efi_clearstart=fixture_efi;p.clear_errorcount=fixture_errorcount;ctx=0;CHECK(!b.start(b.opaque,&ctx));
    CHECK(!kbl_native_put(ctx,"/tool.efi","MZ!!",4));unsigned calls=efi_calls;
    CHECK(run("efi -cs /tool.efi one 'a b'",0)&&efi_calls==calls+1&&contains("clearstart queued"));
    CHECK(run("efi --clearstart -- /tool.efi one 'a b'",0)&&efi_calls==calls+2);
    CHECK(run("efi -cs --clearstart /tool.efi",1)&&contains("duplicate")&&efi_calls==calls+2);
    CHECK(run("efi --clearstart",1)&&contains("requires PATH"));
    CHECK(!errorcount_calls);CHECK(run("clear errorcount",0)&&errorcount_calls==1&&contains("cleared and verified"));
    errorcount_failure=1;CHECK(run("clear errorcount",1)&&errorcount_calls==2&&contains("reset failed"));errorcount_failure=0;
    CHECK(run("clear errorcount extra",1)&&errorcount_calls==2);
    efi_provider_error=1;CHECK(run("efi -cs /tool.efi one 'a b'",1)&&contains("Fastboot retained"));efi_provider_error=0;
    CHECK(run("echo survived",0)&&contains("survived"));CHECK(finish());++native_test_groups;return 1;
}
static unsigned char boot_source[0x1400],boot_prepared[0x1400],boot_check[0x1400];
static int explicit_efi_bootloader(void){
    CHECK(init());p.efi_run=fixture_efi;CHECK(finish());ctx=0;CHECK(!b.start(b.opaque,&ctx));
    CHECK(bl_fixture_pe(boot_source,sizeof(boot_source),0,8,0x33)==sizeof(boot_source));
    for(size_t i=0;i<sizeof(boot_source);++i)boot_prepared[i]=boot_source[i];
    bl_fixture_word(boot_prepared,0x440,0x52800028u);
    CHECK(kbl_native_put(ctx,"/boot.img",boot_source,sizeof(boot_source))==KBL_OK);
    efi_expected_image=boot_source;efi_expected_image_bytes=sizeof(boot_source);
    efi_expected_options=efi_default_options;efi_expected_options_bytes=sizeof(efi_default_options);
    unsigned calls=efi_calls;
    CHECK(run("efi /boot.img one 'a b'",0)&&efi_calls==calls+1&&!contains("RAM override"));
    efi_expected_image=boot_prepared;
    CHECK(run("efi -bl /boot.img one 'a b'",0)&&efi_calls==calls+2&&contains("RAM override")&&contains(" prepared"));
    static const uint16_t unicode_options[]={'"',0x4e2d,0x6587,' ',0xd83d,0xde00,'"',' ','-','b','l',0};
    efi_expected_options=unicode_options;efi_expected_options_bytes=sizeof(unicode_options);
    CHECK(run("efi --bootloader /boot.img '\xe4\xb8\xad\xe6\x96\x87 \xf0\x9f\x98\x80' -bl",0)&&efi_calls==calls+3);
    size_t got=0;uint64_t bytes=0;
    CHECK(kbl_native_get(ctx,"/boot.img",0,boot_check,sizeof(boot_check),&got,&bytes)==KBL_OK);
    CHECK(got==sizeof(boot_source)&&bytes==sizeof(boot_source)&&same(boot_check,boot_source,sizeof(boot_source)));
    CHECK(kbl_native_put(ctx,"/forced.img",boot_prepared,sizeof(boot_prepared))==KBL_OK);
    efi_expected_options=efi_default_options;efi_expected_options_bytes=sizeof(efi_default_options);
    CHECK(run("efi -bl /forced.img one 'a b'",0)&&efi_calls==calls+4&&contains("already-forced"));
    struct kbl_native_stats before,after;
    /* A valid PE with no proved target never reaches efi_run. */
    bl_fixture_word(boot_check,0x440,0xd503201fu);
    CHECK(kbl_native_put(ctx,"/no-target.img",boot_check,sizeof(boot_check))==KBL_OK);
    CHECK(!kbl_native_get_stats(ctx,&before));
    CHECK(run("efi -bl /no-target.img one 'a b'",1)&&efi_calls==calls+4&&contains("preparation"));
    CHECK(!kbl_native_get_stats(ctx,&after));
    CHECK(after.owned_allocations==before.owned_allocations&&after.owned_bytes==before.owned_bytes);
    for(size_t i=0;i<sizeof(boot_source);++i)boot_check[i]=boot_source[i];
    bl_fixture_word(boot_check,0x640,0x39400000u|(0x33u<<10)|(26u<<5)|9u);
    bl_fixture_word(boot_check,0x644,bl_fixture_tb(0x1244,0x1280,9,0,1));
    bl_fixture_word(boot_check,0x648,bl_fixture_branch(0x1248,0x1300,1));
    bl_fixture_word(boot_check,0x680,bl_fixture_tb(0x1280,0x12c0,19,63,1));
    bl_fixture_word(boot_check,0x684,bl_fixture_adrp(1,0x1284,0x5000));
    bl_fixture_word(boot_check,0x688,0x91000021u);bl_fixture_word(boot_check,0x68c,0x52800800u);
    bl_fixture_word(boot_check,0x690,bl_fixture_branch(0x1290,0x1300,1));bl_fixture_word(boot_check,0x6c0,0xd65f03c0u);
    CHECK(kbl_native_put(ctx,"/no-target.img",boot_check,sizeof(boot_check))==KBL_OK);
    CHECK(!kbl_native_get_stats(ctx,&before));
    CHECK(run("efi --bootloader /no-target.img one 'a b'",1)&&efi_calls==calls+4&&contains("preparation"));
    CHECK(!kbl_native_get_stats(ctx,&after));CHECK(after.owned_allocations==before.owned_allocations&&after.owned_bytes==before.owned_bytes);
    CHECK(run("echo survived-bootloader",0)&&contains("survived-bootloader"));
    CHECK(finish());p.efi_clearstart=fixture_efi;ctx=0;CHECK(!b.start(b.opaque,&ctx));
    CHECK(!kbl_native_put(ctx,"/boot.img",boot_source,sizeof(boot_source)));
    efi_expected_image=boot_prepared;
    CHECK(run("efi -cs --bootloader /boot.img one 'a b'",0)&&contains("RAM override")&&contains("clearstart queued"));
    CHECK(run("efi -bl --clearstart /boot.img one 'a b'",0)&&contains("clearstart queued"));
    efi_expected_image=(const unsigned char*)"MZ!!";efi_expected_image_bytes=4;
    CHECK(finish());++native_test_groups;return 1;
}
static int session_failure_boundaries(void){
    CHECK(init());void*owners[33]={0};void*extra=0;
    struct kbl_native_stats before,after;CHECK(!kbl_native_get_stats(ctx,&before));
    for(unsigned fail=0;fail<2;++fail){test_fail_after(fail);CHECK(kbl_native_session_create(ctx,&extra)==KBL_ERROR&&!extra);test_fail_clear();
        CHECK(!kbl_native_get_stats(ctx,&after)&&after.owned_allocations==before.owned_allocations&&after.owned_bytes==before.owned_bytes);}
    for(unsigned i=0;i<33;++i)CHECK(!kbl_native_session_create(ctx,&owners[i]));
    CHECK(!kbl_native_session_create(ctx,&extra)&&extra);native_test_sessions=34;
    CHECK(!kbl_native_session_close(ctx,extra));extra=0;
    CHECK(!kbl_native_get_stats(ctx,&before));
    for(unsigned fail=0;fail<2;++fail){uint64_t job=0;test_fail_after(fail);
        CHECK(kbl_native_session_submit(ctx,owners[0],(const unsigned char*)"true",4,&job)==KBL_ERROR);test_fail_clear();
        CHECK(!job);CHECK(!kbl_native_get_stats(ctx,&after)&&after.owned_allocations==before.owned_allocations&&after.owned_bytes==before.owned_bytes);}
    const struct kbl_program_api*api=0;test_fail_after(0);
    CHECK(kbl_native_session_io_begin(ctx,owners[0],&api)==KBL_ERROR&&!api);test_fail_clear();
    /* A rejected IO request must leave no stale FIFO head. */
    CHECK(session_run(owners[1],"echo queue-alive",0)&&contains("queue-alive"));
    CHECK(kbl_native_put(ctx,"/hello",demo_program,demo_program_bytes)==KBL_OK);uint64_t job=0;
    CHECK(!kbl_native_session_submit(ctx,owners[0],(const unsigned char*)"./hello",7,&job));
    size_t n=0;int done=0,rc=0;CHECK(!kbl_native_session_poll(ctx,owners[0],job,2000,output,7,&n,&done,&rc)&&!done);
    CHECK(!kbl_native_session_close(ctx,owners[0]));owners[0]=0;
    for(unsigned i=1;i<33;++i)CHECK(!kbl_native_session_close(ctx,owners[i]));
    CHECK(finish());++native_test_groups;return 1;
}
static char capacity_command[8192],capacity_path[768];
static int dynamic_commands_and_paths(void){
    CHECK(init());size_t at=0;
    const char *prefix="echo";while(*prefix)capacity_command[at++]=*prefix++;
    for(unsigned i=0;i<120;++i){capacity_command[at++]=' ';capacity_command[at++]='x';}
    capacity_command[at]=0;CHECK(run(capacity_command,0)&&output_bytes==240);native_test_arguments=121;
    for(size_t i=0;i<239;++i)CHECK(output[i]==(i%2?' ':'x'));CHECK(output[239]=='\n');
    prefix="echo ";at=0;while(*prefix)capacity_command[at++]=*prefix++;
    for(unsigned i=0;i<6000;++i)capacity_command[at++]='L';capacity_command[at]=0;
    CHECK(run(capacity_command,0)&&output_bytes==6001);native_test_command_bytes=(unsigned)at;
    for(size_t i=0;i<6000;++i)CHECK(output[i]=='L');CHECK(output[6000]=='\n');
    capacity_path[0]='/';for(unsigned i=1;i<=600;++i)capacity_path[i]='n';capacity_path[601]=0;
    at=0;prefix="touch ";while(*prefix)capacity_command[at++]=*prefix++;
    for(size_t i=0;capacity_path[i];++i)capacity_command[at++]=capacity_path[i];capacity_command[at]=0;
    CHECK(run(capacity_command,0));native_test_path_bytes=601;
    at=0;prefix="stat ";while(*prefix)capacity_command[at++]=*prefix++;
    for(size_t i=0;capacity_path[i];++i)capacity_command[at++]=capacity_path[i];capacity_command[at]=0;
    CHECK(run(capacity_command,0)&&contains("size=0"));
    CHECK(finish());++native_test_groups;return 1;
}
static uint16_t full_exit_fixture[6000],long_option_fixture[6001];
static unsigned full_exit_releases,full_exit_release_error;
static int fixture_exit_release(void*owner){if(owner!=full_exit_fixture)return -KBL_EINVAL;++full_exit_releases;return full_exit_release_error?-KBL_EIO:0;}
static int fixture_efi_full(void*opaque,const void*image,size_t image_bytes,const uint16_t*options,size_t option_bytes,struct kbl_efi_run_full_result*r){
    int rc=fixture_efi(opaque,image,image_bytes,options,option_bytes,&r->result);if(rc)return rc;
    r->result.exit_truncated=1;r->exit_text=full_exit_fixture;r->exit_units=6000;r->exit_owner=full_exit_fixture;r->release_exit=fixture_exit_release;return 0;
}
static int dynamic_efi_options_and_exit(void){
    CHECK(init());CHECK(finish());p.efi_run=fixture_efi;p.efi_run_full=fixture_efi_full;ctx=0;CHECK(!b.start(b.opaque,&ctx));
    CHECK(!kbl_native_put(ctx,"/tool.efi","MZ!!",4));
    for(unsigned i=0;i<6000;++i)full_exit_fixture[i]='Q';
    efi_expected_image=(const unsigned char*)"MZ!!";efi_expected_image_bytes=4;
    efi_expected_options=efi_default_options;efi_expected_options_bytes=sizeof(efi_default_options);
    CHECK(run("efi /tool.efi one 'a b'",0)&&full_exit_releases==1&&!contains("legacy provider truncated"));
    size_t q=0;for(size_t i=0;i<output_bytes;++i)if(output[i]=='Q')++q;CHECK(q==6000);native_test_exit_units=(unsigned)q;
    for(unsigned i=0;i<6000;++i)long_option_fixture[i]='B';long_option_fixture[6000]=0;
    efi_expected_options=long_option_fixture;efi_expected_options_bytes=sizeof(long_option_fixture);
    const char*prefix="efi /tool.efi ";size_t at=0;while(*prefix)capacity_command[at++]=*prefix++;
    for(unsigned i=0;i<6000;++i)capacity_command[at++]='B';capacity_command[at]=0;
    CHECK(run(capacity_command,0)&&full_exit_releases==2);native_test_options_bytes=sizeof(long_option_fixture);
    struct kbl_native_stats before,after;CHECK(!kbl_native_get_stats(ctx,&before));
    full_exit_release_error=1;CHECK(run(capacity_command,1)&&full_exit_releases==3&&contains("cleanup pending"));full_exit_release_error=0;
    CHECK(!kbl_native_get_stats(ctx,&after));CHECK(before.owned_allocations==after.owned_allocations&&before.owned_bytes==after.owned_bytes);
    CHECK(run("echo after-full-exit",0)&&contains("after-full-exit"));
    efi_expected_options=efi_default_options;efi_expected_options_bytes=sizeof(efi_default_options);
    CHECK(finish());++native_test_groups;return 1;
}
static unsigned display_calls,display_op,power_calls,power_flushes,power_dirty,power_flush_error,power_list_error;
static unsigned char power_disk[512];
static int power_read(void*unused,uint64_t at,void*data,size_t bytes,size_t*read){
    (void)unused;if(at>512||bytes>512-at)return KBL_ERROR;for(size_t i=0;i<bytes;++i)((unsigned char*)data)[i]=power_disk[at+i];*read=bytes;return KBL_OK;
}
static int power_write(void*unused,uint64_t at,const void*data,size_t bytes,size_t*written){
    (void)unused;if(at>512||bytes>512-at)return KBL_ERROR;for(size_t i=0;i<bytes;++i)power_disk[at+i]=((const unsigned char*)data)[i];*written=bytes;power_dirty=1;return KBL_OK;
}
static int power_flush(void*unused){(void)unused;++power_flushes;if(power_flush_error)return KBL_ERROR;power_dirty=0;return KBL_OK;}
static void power_close(void*unused){(void)unused;}
static int power_open(void*unused,const char*label,unsigned flags,struct kbl_block*out){
    (void)unused;(void)flags;if(!same(label,"power",6))return KBL_ERROR;
    *out=(struct kbl_block){{0,512,power_read,power_close},512,0,power_write,power_flush};return KBL_OK;
}
static int power_list(void*unused,struct kbl_block_info*items,size_t cap,size_t*count){
    (void)unused;if(power_list_error)return KBL_ERROR;*count=1;if(!items&&!cap)return KBL_OK;
    if(!items||cap<1)return KBL_ERROR;
    for(size_t i=0;i<sizeof(*items);++i)((unsigned char*)items)[i]=0;
    for(unsigned i=0;i<6;++i)items[0].label[i]="power"[i];items[0].bytes=512;items[0].block_bytes=512;return KBL_OK;
}
static int fixture_display(void*unused,unsigned op,char*reply,size_t cap){
    (void)unused;++display_calls;display_op=op;
    const char*s="KBL1 display_provider=fixture\nKBL1 display_observed=1\n";size_t n=len(s);
    if(cap<=n)return KBL_ERROR;for(size_t i=0;i<=n;++i)reply[i]=s[i];return KBL_OK;
}
static int fixture_poweroff(void*unused){(void)unused;++power_calls;return KBL_ERROR;}
static int explicit_display_and_shutdown(void){
    CHECK(init());CHECK(run("screenoff",1)&&contains("provider unavailable"));
    CHECK(run("poweroff",1)&&contains("provider unavailable"));CHECK(finish());
    p.display=fixture_display;p.poweroff=fixture_poweroff;p.block_list=power_list;p.block_open=power_open;ctx=0;CHECK(!b.start(b.opaque,&ctx));
    CHECK(run("true",0)&&!display_calls&&!power_calls);
    CHECK(run("exit",0)&&!display_calls&&!power_calls);
    CHECK(run("screenoff extra",1)&&!display_calls);
    CHECK(run("screenoff",0)&&display_calls==1&&display_op==1&&contains("display_provider=fixture"));
    CHECK(run("screenon",0)&&display_calls==2&&display_op==2);
    CHECK(run("screenstatus",0)&&display_calls==3&&!display_op);
    CHECK(run("screenoffx",1)&&display_calls==3&&!power_calls);
    CHECK(run("poweroff extra",1)&&!power_calls);
    CHECK(run("poweroffx",1)&&!power_calls);
    CHECK(kbl_native_put(ctx,"/power-byte","X",1)==KBL_OK);
    CHECK(run("dd if=/power-byte of=/dev/block/by-name/power bs=1 count=1",0)&&power_dirty&&!power_flushes);
    power_flush_error=1;CHECK(run("poweroff",1)&&!power_calls&&power_dirty&&power_flushes==1&&contains("shutdown cancelled"));power_flush_error=0;
    power_list_error=1;CHECK(run("poweroff",1)&&!power_calls&&power_dirty&&power_flushes==1);power_list_error=0;
    CHECK(run("poweroff",1)&&power_calls==1&&!power_dirty&&power_flushes==2&&contains("shutdown returned"));
    CHECK(run("echo retained",0)&&contains("retained"));CHECK(finish());++native_test_groups;return 1;
}
static int dynamic_program_memory(void){
    CHECK(init());CHECK(finish());p.elf.max_image_bytes=0;ctx=0;CHECK(!b.start(b.opaque,&ctx));
    CHECK(!kbl_native_put(ctx,"/mem",memory_program,memory_program_bytes));
    CHECK(!kbl_native_put(ctx,"/memport",memory_port_program,memory_port_program_bytes));
    struct kbl_native_stats before,after;CHECK(!kbl_native_get_stats(ctx,&before));
    CHECK(run("./mem",0)&&contains("SDK memory passed"));CHECK(!kbl_native_get_stats(ctx,&after));
    CHECK(after.owned_allocations==before.owned_allocations&&after.owned_bytes==before.owned_bytes);
    CHECK(run("./memport",0)&&contains("compat host memory passed"));CHECK(!kbl_native_get_stats(ctx,&after));
    CHECK(after.owned_allocations==before.owned_allocations&&after.owned_bytes==before.owned_bytes);
    native_memory_fail_large=1;
    CHECK(run("./mem oom",0)&&contains("SDK memory OOM passed"));
    CHECK(run("./memport oom",0)&&contains("compat host OOM passed"));native_memory_fail_large=0;
    CHECK(!kbl_native_get_stats(ctx,&after));CHECK(after.owned_allocations==before.owned_allocations&&after.owned_bytes==before.owned_bytes);
    /* Cancellation while the large allocation itself is suspended zeroing. */
    void*allocating=0;CHECK(!kbl_native_session_create(ctx,&allocating));uint64_t allocating_job=0;
    CHECK(!kbl_native_session_submit(ctx,allocating,(const unsigned char*)"./memport",9,&allocating_job));
    size_t piece=0;int complete=0,result=0;CHECK(!kbl_native_session_poll(ctx,allocating,allocating_job,2000,output,sizeof(output),&piece,&complete,&result)&&!complete);
    CHECK(!kbl_native_session_close(ctx,allocating));CHECK(!kbl_native_get_stats(ctx,&after));
    CHECK(after.owned_allocations==before.owned_allocations&&after.owned_bytes==before.owned_bytes);
    void*owner=0;CHECK(!kbl_native_session_create(ctx,&owner));uint64_t job=0;
    CHECK(!kbl_native_session_submit(ctx,owner,(const unsigned char*)"./mem hold",10,&job));
    unsigned polls=0;int done=0,rc=0;output_bytes=0;
    while(!contains("holding memory")&&polls++<1000){size_t n=0;CHECK(!kbl_native_session_poll(ctx,owner,job,2000,output+output_bytes,sizeof(output)-output_bytes,&n,&done,&rc));output_bytes+=n;CHECK(!done);}
    CHECK(contains("holding memory"));CHECK(!kbl_native_session_close(ctx,owner));CHECK(!kbl_native_get_stats(ctx,&after));
    CHECK(after.owned_allocations==before.owned_allocations&&after.owned_bytes==before.owned_bytes);
    CHECK(finish());++native_test_groups;return 1;
}
int native_test_entry(void) {
    native_test_observed_el=test_current_el(0);
    if(!files_and_commands())return 1;
    if(!snapshots_and_atomicity())return 2;
    if(!backpressure_and_stop())return 3;
    if(!invalid_and_cleanup())return 4;
    if(!program_and_resume())return 5;
    if(!unavailable_execution_provider())return 6;
    if(!copying_publishes_complete_files())return 7;
    if(!hashes_and_word_counts())return 8;
    if(!line_edges_and_literal_grep())return 9;
    if(!dumps_paths_and_formats())return 10;
    if(!large_command_checkpoints())return 11;
    if(!deadline_checkpoints())return 12;
    if(!external_io_session())return 13;
    if(!independent_sessions())return 14;
    if(!explicit_efi_application())return 15;
    if(!explicit_efi_bootloader())return 18;
    if(!session_failure_boundaries())return 16;
    if(!dynamic_commands_and_paths())return 19;
    if(!dynamic_efi_options_and_exit())return 20;
    if(!explicit_display_and_shutdown())return 17;
    if(!dynamic_program_memory())return 21;
    return 0;
}
