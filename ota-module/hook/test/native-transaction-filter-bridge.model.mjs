// Exact-source bridge setup model plus compiled C dispatcher test. No device IPC.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
const root=process.argv[2]; assert(root,'Supply prepared bridge directory');
const recipe=JSON.parse(fs.readFileSync(new URL('../patches/native-transaction-filter-policy.json',import.meta.url),'utf8'));
const files={};
for(const item of recipe.files){
  let raw=fs.readFileSync(path.join(root,item.path),'utf8');
  const quick=JSON.parse(fs.readFileSync(new URL('../patches/art-quick-code-publication-policy.json',import.meta.url),'utf8')).files.find(entry=>entry.path===item.path);
  if(quick&&createHash('sha256').update(raw).digest('hex')===quick.afterSha256){
    for(const operation of [...quick.operations].reverse()){assert.equal(raw.split(operation.new).length-1,1);raw=raw.replace(operation.new,operation.old);}
    assert.equal(createHash('sha256').update(raw).digest('hex'),quick.beforeSha256);
  }
  assert.equal(createHash('sha256').update(raw).digest('hex'),item.afterSha256);files[item.path]=raw;
}
const part=(raw,start,end)=>{const a=raw.indexOf(start),b=raw.indexOf(end,a);assert(a>=0&&b>a,start);return raw.slice(a,b).replace(/^export /gm,'');};
const android=part(files['lib/android.js'],'// Successful native gates/callbacks','class ArtMethodMangler');
const suspension=part(files['lib/android.js'],'export function withAllArtThreadsSuspended (','\nclass ArtClassVisitor');
const cf=files['lib/class-factory.js'];
const verify=part(cf,'function verifyNativeTransactionFilterMethod (','function makeMethodCallable (');
const descriptors=part(cf,'methodPrototype = Object.create(Function.prototype, {','  methodName: {').replace('methodPrototype = Object.create(Function.prototype, {','const filterDescriptors = {')+'};';
let checks=0;
function test(name,body){body();checks++;console.log('PASS '+name);}
function fixture({flags=0x100,pathName='/system/lib64/libandroid_runtime.so',ownerName='libandroid_runtime.so',jniExecutable=true,compileFail=false,entryCloaked=false,slotOwned=true,offset=24,clonePrivate=true,publishOwned=true,probeCounterFail=false,probePublishFail=false}={}){
  const slots=new Map(),events=[],allocated=new Set(),nativeCounts={ordinary:0,matched:0};let compileSource='',cmDisposed=false,stopped=false,nextAllocation=0x800000;
  class Pointer{
    constructor(value){this.value=value;}
    add(n){return new Pointer(this.value+n);}sub(other){return new Pointer(this.value-other.value);}
    and(n){return new Pointer(this.value&n);}isNull(){return this.value===0;}
    equals(other){return other instanceof Pointer&&other.value===this.value;}toString(){return '0x'+this.value.toString(16);}
    readPointer(){return slots.get(this.value);}writePointer(value){assert(!stopped);assert(allocated.has(this.value),'bare live JNI pointer publication is forbidden');slots.set(this.value,value);events.push('initialize-scratch');}
    writeS32(value){events.push('code:'+value);}
    writeU32(value){assert(!stopped);assert.equal(value,0);events.push('initialize-counter');}
  }
  const impl=new Pointer(0x900000),mangler={originalMethod:{accessFlags:flags,jniCode:new Pointer(0x100010)},methodId:new Pointer(0x300000),hookedMethodId:new Pointer(0x300000),replacementMethodId:new Pointer(0x200000)};
  if(!clonePrivate)mangler.replacementMethodId=mangler.methodId;
  slots.set(0x200000+offset,slotOwned?impl:new Pointer(99));
  const context={pointerSize:8,NULL:new Pointer(0),kAccNative:0x100,kAccStatic:8,kAccFastNative:0x80000,kAccCriticalNative:0x200000,
    getApi(){assert(!stopped);return {flavor:'art',artThreadList:{},'art::ThreadList::SuspendAll'(){assert(!stopped);events.push('suspend');stopped=true;},'art::ThreadList::ResumeAll'(){assert(stopped);events.push('resume');stopped=false;}};},getArtMethodSpec(){assert(!stopped);return {offset:{jniCode:offset}};},
    Process:{findModuleByAddress:()=>({name:ownerName,path:pathName,base:new Pointer(0x100000)}),findRangeByAddress(p){events.push('range:'+p.value);return p.value===0x400000&&entryCloaked?null:{protection:jniExecutable?'r-x':'rw-'};}},
    Memory:{alloc(){assert(!stopped);events.push('allocate-code');const p=new Pointer(nextAllocation);nextAllocation+=0x100;allocated.add(p.value);return p;},allocUtf8String(s){assert(!stopped);return s;}},
    CModule:class{constructor(source,symbols){assert(!stopped);events.push('compile');compileSource=source;if(compileFail)throw Error('compiler failure');assert.strictEqual(symbols.original_transact,mangler.originalMethod.jniCode);assert.strictEqual(symbols.guarded_transact,impl);this.transact_filter=new Pointer(0x400000);this.read_ordinary=new Pointer(1);this.read_matched=new Pointer(2);this.publish_filter=new Pointer(3);}dispose(){assert(!stopped);cmDisposed=true;}},
    NativeFunction:function(p){assert(!stopped);if(p.value===3)return (slot,expected,entry)=>{if(allocated.has(slot.value)){assert(!stopped);events.push('probe-publish');if(probePublishFail)return -1;}else{assert(stopped);assert.equal(vm.runInContext('retainedNativeTransactionGates.length',context),1,'root code/callbacks before publishing');events.push('atomic-publish');if(!publishOwned)return 0;}if(!slots.get(slot.value).equals(expected))return 0;slots.set(slot.value,entry);return 1;};return ()=>{if(probeCounterFail)return 9;return p.value===1?nativeCounts.ordinary:nativeCounts.matched;};}
  };
  vm.createContext(context);vm.runInContext(android+'\n'+suspension,context);
  return {run:code=>context.installNativeTransactionCodeFilter(mangler,impl,code,{}),mangler,impl,events,slots,context,nativeCounts,source:()=>compileSource,disposed:()=>cmDisposed};
}
test('runtime method offset/module proof precedes one aligned pointer publication',()=>{
  const f=fixture({offset:40}),g=f.run(77);f.nativeCounts.ordinary=123;f.nativeCounts.matched=4;assert.equal(g.mode,'native_code_filter');assert.equal(g.inspect().ready,true);assert.equal(g.inspect().native_fastpass,123);assert.equal(g.inspect().native_matched,4);assert.equal(g.inspect().original_jni_rva,'0x10');assert.deepEqual(f.events.slice(-3),['suspend','atomic-publish','resume']);assert.equal(f.slots.get(0x200000+40).value,0x400000);assert(!f.disposed());
});
test('same implementation/code reuses the one retained gate',()=>{const f=fixture(),g=f.run(7);assert.strictEqual(f.run(7),g);assert.equal(f.events.filter(e=>e==='compile').length,1);assert.throws(()=>f.run(8),/identity changed/);});
test('wrong native/static/fast ABI flags reject before compiling or mutating',()=>{for(const flags of [0,0x108,0x80100,0x100108]){const f=fixture({flags});assert.throws(()=>f.run(7),/instance JNI/);assert(!f.events.includes('atomic-publish'));assert(!f.events.includes('compile'));}});
test('observed native 0x200101 flags are accepted without misreading NterpInvoke as CriticalNative',()=>{const f=fixture({flags:0x200101});assert.equal(f.run(7).inspect().ready,true);});
test('registered target needs executable trusted system module, not ART resolver/application code',()=>{for(const options of [{pathName:'/data/app/example.so'},{ownerName:'libart.so'},{jniExecutable:false}]){const f=fixture(options);assert.throws(()=>f.run(7),/system module/);assert(!f.events.includes('atomic-publish'));}});
test('foreign replacement pointer refuses installation',()=>{const f=fixture({slotOwned:false});assert.throws(()=>f.run(7),/does not belong/);assert(!f.events.includes('compile'));});
test('only a distinct private ArtMethod clone is eligible',()=>{const f=fixture({clonePrivate:false});assert.throws(()=>f.run(7),/private ArtMethod clone/);assert(!f.events.includes('compile'));});
test('publication ownership failure still resumes and leaves callback unchanged',()=>{const f=fixture({publishOwned:false});assert.throws(()=>f.run(7),/ownership changed/);assert.strictEqual(f.slots.get(0x200000+24),f.impl);assert.equal(f.events.at(-1),'resume');});
test('compiler and harmless execution self-test failures leave Java callback slot unchanged',()=>{for(const options of [{compileFail:true},{probeCounterFail:true},{probePublishFail:true}]){const f=fixture(options);assert.throws(()=>f.run(7),/compiler|self-test failed/);assert.strictEqual(f.slots.get(0x200000+24),f.impl);assert(!f.events.includes('atomic-publish'));}});
test('own cloaked CModule range does not falsely reject successfully executed native self-tests',()=>{const f=fixture({entryCloaked:true}),gate=f.run(7);assert.equal(gate.inspect().ready,true);assert.equal(f.events.filter(e=>e==='probe-publish').length,2);assert(!f.events.includes('range:4194304'),'own CModule does not depend on filtered range enumeration');assert(f.events.lastIndexOf('probe-publish')<f.events.indexOf('suspend'));});
test('invalid transaction numbers reject before allocation',()=>{for(const code of [-1,0x80000000,1.5,'7']){const f=fixture();assert.throws(()=>f.run(code),/jint/);assert.equal(f.events.length,0);}});
test('guarded API validates exact boot class and signature before setter',()=>{
  let installed=0;
  const context={INSTANCE_METHOD:3,android:{installNativeTransactionCodeFilter(){installed++;return {mode:'native_code_filter'};}},vm:{}};
  vm.createContext(context);vm.runInContext(verify+descriptors+'\nthis.descriptors=filterDescriptors;',context);
  function method({name='transactNative',holderName='android.os.BinderProxy',type=3,ret='boolean',args=['int','android.os.Parcel','android.os.Parcel','int'],loaderName='java.lang.BootClassLoader'}={}){
    const holder={$n:holderName,class:{getName:()=>holderName,getClassLoader:()=>loaderName===null?null:{getClass:()=>({getName:()=>loaderName})}}};
    const m={_p:[name,holder,type,{}, {className:ret},args.map(className=>({className}))],_r:{_m:{}}};
    Object.defineProperty(m,'implementation',{get(){return this.installedReplacement;},set(value){this.handler=value;this.installedReplacement=function nativeCallbackOwner(){};this._r={_m:{}};this.writes=(this.writes||0)+1;}});Object.defineProperties(m,context.descriptors);return m;
  }
  const fn=()=>true,m=method();assert.equal(m.setNativeCodeFilter(fn,7).mode,'native_code_filter');assert.strictEqual(m.handler,fn);assert.notStrictEqual(m.implementation,fn,'upstream getter returns the NativeCallback owner');assert.equal(m.writes,1);assert.equal(installed,1);
  for(const options of [{name:'transact'},{holderName:'example.FakeBinder'},{type:2},{type:2,args:['int','int'],ret:'int'},{ret:'int'},{args:['int']},{loaderName:'dalvik.system.PathClassLoader'}]){const m=method(options);assert.throws(()=>m.setNativeCodeFilter(fn,7),/ABI|boot class/);assert.equal(m.writes,undefined);}
  context.android.installNativeTransactionCodeFilter=()=>{throw Error('fast capability absent');};const fallback=method(),g=fallback.setNativeCodeFilter(fn,7);assert.equal(g.mode,'java_replacement');assert.strictEqual(fallback.handler,fn);assert.notStrictEqual(fallback.implementation,fn);assert.equal(g.inspect().ready,true);fallback.implementation=()=>false;assert.equal(g.inspect().ready,false,'fallback checks captured installed callback ownership');
});
test('existing setter restoration marks gate retired without freeing C or callback',()=>{
  const raw=files['lib/android.js'];assert(raw.includes('if (this._nativeTransactionGate !== undefined) this._nativeTransactionGate.active = false;'));
  const f=fixture(),g=f.run(7);f.mangler._nativeTransactionGate.active=false;assert.equal(g.inspect().ready,false);assert(!f.disposed());assert.strictEqual(f.mangler._nativeTransactionGate.implementation,f.impl);
});
test('actual C source preserves arguments/return/pending exception and keeps ordinary traffic out of guarded callback',()=>{
  const f=fixture();f.run(7);const source=f.source();
  assert(source.includes('extern volatile gint ordinary_count;'));
  assert(source.includes('extern volatile gint matched_count;'));
  assert(!/\bstatic\s/.test(source),'CModule writable globals must live in external Memory.alloc symbols');
  assert(f.mangler._nativeTransactionGate.counters,'counter memory is strongly retained with active/in-flight gate');
  const dir=path.resolve(root,'..','native-filter-c-model');fs.mkdirSync(dir,{recursive:true});
  fs.writeFileSync(path.join(dir,'glib.h'),'#include <stdint.h>\ntypedef void* gpointer; typedef int gint; typedef unsigned int guint; typedef unsigned char guint8; typedef intptr_t gssize; typedef uintptr_t guintptr; static inline gint g_atomic_int_add(volatile gint *p,gint n){return __atomic_fetch_add(p,n,__ATOMIC_SEQ_CST);} static inline gssize g_atomic_pointer_add(volatile void *p,gssize n){return (gssize)__atomic_fetch_add((volatile uintptr_t*)p,(uintptr_t)n,__ATOMIC_SEQ_CST);}\n');
  const harness=`
#include <stdint.h>
gint selected_code=7;
volatile gint ordinary_count;
volatile gint matched_count;
typedef struct { int pending; int original; int guarded; } State;
guint8 original_transact(gpointer e,gpointer s,gint c,gpointer d,gpointer r,gint f){State *x=e;x->original++;if(s!=(void*)1||r!=(void*)3||f!=19||c==7)return 2;if(!d){x->pending=41;return 0;}return 1;}
guint8 guarded_transact(gpointer e,gpointer s,gint c,gpointer d,gpointer r,gint f){State *x=e;x->guarded++;if(c!=7)return 2;x->pending=99;return 0;}
int main(void){State x={0};int i;gpointer slot=(gpointer)0x1000;for(i=0;i<10000;i++)if(transact_filter(&x,(void*)1,8,(void*)2,(void*)3,19)!=1)return 10;if(x.original!=10000||x.guarded!=0||read_ordinary()!=10000||read_matched()!=0)return 11;if(transact_filter(&x,(void*)1,8,0,(void*)3,19)!=0||x.pending!=41)return 12;if(transact_filter(&x,(void*)1,7,(void*)2,(void*)3,19)!=0||x.pending!=99||x.guarded!=1)return 13;if(read_ordinary()!=10001||read_matched()!=1)return 14;if(publish_filter(&slot,(gpointer)0x2000,(gpointer)0x5000)!=0||slot!=(gpointer)0x1000)return 15;if(publish_filter(&slot,(gpointer)0x1000,(gpointer)0x5000)!=1||slot!=(gpointer)0x5000)return 16;return 0;}
`;
  const c=path.join(dir,'dispatch.c'),exe=path.join(dir,process.platform==='win32'?'dispatch.exe':'dispatch');fs.writeFileSync(c,source+harness);
  const compiler=spawnSync(process.env.CC||'clang',['-std=c11','-O2','-I',dir,c,'-o',exe],{encoding:'utf8'});assert.equal(compiler.status,0,compiler.stderr||compiler.error?.message);
  const run=spawnSync(exe,[],{encoding:'utf8'});assert.equal(run.status,0,'compiled C dispatcher '+run.status+' '+run.stderr);
});
console.log(JSON.stringify({passed:true,tests:checks,preparedHashes:recipe.files.map(f=>({path:f.path,sha256:f.afterSha256})),actualCDispatcherExecuted:true,ARTExecuted:false,deviceOperations:false}));
