// Execute exact prepared staging/publication/restoration functions with strict
// memory/ART ports. Instruction writer ports model documented instruction/literal
// fixups; this host test does not claim to execute Gum writers or ART itself.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createHash} from 'node:crypto';
const root=process.argv[2]; assert(root,'Supply prepared bridge directory');
const recipe=JSON.parse(fs.readFileSync(new URL('../patches/art-quick-code-publication-policy.json',import.meta.url),'utf8'));
const entry=recipe.files[0],raw=fs.readFileSync(path.join(root,entry.path),'utf8');
const hash=s=>createHash('sha256').update(s).digest('hex');assert.equal(hash(raw),entry.afterSha256);
let reversed=raw;
for(const operation of [...entry.operations].reverse()){
  assert.equal(reversed.split(operation.new).length-1,1,'every reverse context matches once');
  reversed=reversed.replace(operation.new,operation.old);
}
assert.equal(hash(reversed),entry.beforeSha256,'new layer restores exact previous native/batch bridge');
const part=(start,end)=>{const a=raw.indexOf(start),b=raw.indexOf(end,a);assert(a>=0&&b>a,start);return raw.slice(a,b).replace(/^export /gm,'');};
const source=part('function retireArtQuickCodeInterceptor (','\nfunction isArtQuickEntrypoint (')+'\n'+
  part('export function withAllArtThreadsSuspended (','\nclass ArtClassVisitor')+'\n'+
  part('class InlineHook {','\nfunction instrumentGetOatQuickMethodHeaderInlinedCopyArm (');
const inlineFunction=(text,arch)=>{
  const start=text.indexOf('function instrumentGetOatQuickMethodHeaderInlinedCopy'+arch+' (');
  const end=text.indexOf(arch==='Arm'?'\nfunction instrumentGetOatQuickMethodHeaderInlinedCopyArm64 (':'\nexport function makeMethodMangler (',start);
  assert(start>=0&&end>start);return text.slice(start,end);
};
const inlineSuffix=arch=>{
  const body=inlineFunction(raw,arch);const suffix=body.slice(body.lastIndexOf('  const hook = new InlineHook'));
  assert(suffix.includes('publishArtQuickCodePatch(address,'));
  return 'function installInline'+arch+'(address,size,trampoline,scratchReg){let redirectCapacity=size;\n'+suffix.trimEnd().slice(0,-1)+'\nreturn hook;\n}';
};
const inlineSource=inlineSuffix('Arm')+'\n'+inlineSuffix('Arm64');
let checks=0;function test(name,body){body();checks++;console.log('PASS '+name);}
function fixture({arch='arm64',redirect=16,relocated=16,failPatch='',missing='',writerSize=null}={}){
  const events=[],live=Buffer.alloc(64,0xaa),retired=[];let stopped=false,alloc=0x8000,patches=0,freed=0;
  const array=b=>Uint8Array.from(b).buffer;
  class Pointer{
    constructor(value,buffer=live){this.value=value;this.buffer=buffer;}
    and(n){return new Pointer(this.value&(typeof n==='object'?n.value:n),this.buffer);}
    or(n){return new Pointer(this.value|n,this.buffer);}
    readByteArray(n){assert(!stopped,'all byte snapshots precede suspension');events.push('snapshot');return array(this.buffer.subarray(0,n));}
    writeByteArray(bytes){assert(stopped,'only suspended patch callback writes live code');assert.strictEqual(this.buffer,live);events.push('copy');Buffer.from(bytes).copy(live);}
  }
  const target=new Pointer(arch==='arm'?0x1001:0x1000),trampoline=new Pointer(0x12345678,Buffer.alloc(64));
  class BaseWriter{
    constructor(code,options){assert(!stopped);assert.notStrictEqual(code.buffer,live,'writer never targets a published entry');assert.equal(options.pc.value,arch==='arm'?0x1000:0x1000);this.code=code;this.offset=0;this.literal=null;events.push('writer');}
    u32(value){assert(!stopped);this.code.buffer.writeUInt32LE(value>>>0,this.offset);this.offset+=4;}
    flush(){assert(!stopped);if(this.literal!==null){assert.equal(this.offset,8);this.code.buffer.writeBigUInt64LE(BigInt(this.literal),8);this.code.buffer.writeUInt32LE(0x58000040|this.literalReg,0);this.offset=16;}if(writerSize!==null)this.offset=writerSize;events.push('writer-flush');}
  }
  class Arm64Writer extends BaseWriter{
    putLdrRegAddress(reg,dest){assert(/^x\d+$/.test(reg));this.literalReg=Number(reg.slice(1));this.u32(0x58000000|this.literalReg);this.literal=dest.value;}
    putAdrpRegAddress(reg,dest){assert.equal(reg,'x16');this.u32(0x90000010);}
    putBrReg(reg){assert(/^x\d+$/.test(reg));this.u32(0xd61f0000|(Number(reg.slice(1))<<5));}
  }
  class X86Writer extends BaseWriter{putJmpAddress(){this.code.buffer.set([0xe9,1,2,3,4],0);this.offset=5;}}
  class ThumbWriter extends BaseWriter{putLdrRegAddress(reg,dest){assert.equal(reg,'pc');assert.equal(dest.value&1,1);this.u32(0xf000f8df);this.u32(dest.value);}}
  const api={artThreadList:{},'art::ThreadList::SuspendAll'(){assert(!stopped);events.push('suspend');stopped=true;},'art::ThreadList::ResumeAll'(){assert(stopped);events.push('resume');stopped=false;}};
  if(missing)delete api[missing];
  const context={Process:{arch},pointerSize:8,THUMB_BIT_REMOVAL_MASK:{value:~1},retiredArtQuickCodeInterceptors:retired,inlineHooks:[],retiredInlineHooks:[],
    getApi(){assert(!stopped,'resolve APIs before suspension');events.push('api');return api;},
    Memory:{alloc(n){assert(!stopped);events.push('allocate');return new Pointer(alloc+=0x100,Buffer.alloc(n));},allocUtf8String(s){assert(!stopped);events.push('cause');return s;},dup(p,n){assert(!stopped);return new Pointer(alloc+=0x100,Buffer.from(p.buffer.subarray(0,n)));},patchCode(p,n,apply){assert(stopped);assert.strictEqual(p.buffer,live);patches++;events.push('patch:'+patches);if(patches===1&&failPatch==='before')throw Error('publish failure');if(patches===1&&failPatch==='partial'){live[0]=0xee;throw Error('publish failure');}apply(new Pointer(p.value,live));if(patches===1&&failPatch==='after')throw Error('publish failure');events.push('icache');}},
    Arm64Writer,X86Writer,ThumbWriter,trampolineAllocator:{freeSlice(){freed++;}}
  };
  vm.createContext(context);vm.runInContext(source+'\n'+inlineSource+'\nthis.Interceptor=ArtQuickCodeInterceptor;this.publish=publishArtQuickCodePatch;',context);
  const interceptor=new context.Interceptor(target);interceptor._allocateTrampoline=function(){this.trampoline=trampoline;this.redirectSize=redirect;return {};};
  context.artQuickCodeReplacementTrampolineWriters={[arch]:()=>relocated};
  return {context,events,live,retired,interceptor,target,trampoline,installInline:reg=>context[arch==='arm'?'installInlineArm':'installInlineArm64'](new Pointer(0x1000),arch==='arm'?8:16,trampoline,reg),activate:()=>interceptor.activate({}),restore:()=>interceptor.deactivate(),state:()=>({stopped,patches,freed})};
}
test('ARM64 literal placeholder never reaches live entry and finalized LDR#8/BR bytes publish under STW',()=>{
  const f=fixture();f.activate();assert.equal(f.live.readUInt32LE(0),0x58000050);assert.equal(f.live.readUInt32LE(4),0xd61f0200);assert.equal(f.live.readBigUInt64LE(8),0x12345678n);assert(f.events.indexOf('writer-flush')<f.events.indexOf('suspend'));assert(f.events.indexOf('copy')>f.events.indexOf('suspend'));assert(f.events.indexOf('icache')<f.events.indexOf('resume'));assert.equal(f.interceptor.publishedPrologue.byteLength,16);assert.equal(f.state().stopped,false);
});
test('ARM64 ADRP near form copies exactly eight bytes and preserves following original instructions',()=>{
  const f=fixture({redirect:8,relocated:8});f.activate();assert.equal(f.interceptor.publishedPrologue.byteLength,8);assert.equal(f.live.readUInt32LE(0),0x90000010);assert(f.live.subarray(8).every(x=>x===0xaa));
});
test('x64 and ia32 near branches do not copy uninitialized staging tails',()=>{
  for(const arch of ['x64','ia32']){const f=fixture({arch,redirect:5,relocated:5});f.activate();assert.equal(f.interceptor.publishedPrologue.byteLength,5);assert(f.live.subarray(5).every(x=>x===0xaa));}
});
test('ARM Thumb uses true untagged target PC and preserves the trampoline Thumb bit',()=>{
  const f=fixture({arch:'arm',redirect:8,relocated:8});f.activate();assert.equal(f.live.readUInt32LE(4),0x12345679);assert.equal(f.interceptor.publishedPrologue.byteLength,8);
});
test('alignment padding is allowed up to actual completed relocation, independent of redirect estimate',()=>{
  const f=fixture({redirect:8,relocated:12,writerSize:12});f.activate();assert.equal(f.interceptor.publishedPrologue.byteLength,12);
});
test('a writer exceeding completed relocation rejects before live publication and retains owner',()=>{
  const f=fixture({relocated:16,writerSize:20});assert.throws(f.activate,/completed relocation/);assert(f.live.every(x=>x===0xaa));assert(!f.events.includes('suspend'));assert.equal(f.retired[0],f.interceptor);assert.equal(f.state().freed,0);
});
test('all publication error stages restore original bytes within same STW before ResumeAll',()=>{
  for(const failPatch of ['before','partial','after']){const f=fixture({failPatch});assert.throws(f.activate,/publish failure/);assert(f.live.every(x=>x===0xaa));assert.equal(f.state().patches,2);assert.equal(f.events.at(-1),'resume');assert.equal(f.state().stopped,false);assert.equal(f.retired[0],f.interceptor);assert.equal(f.state().freed,0);}
});
test('missing either exclusive capability rejects before touching executable bytes',()=>{
  for(const missing of ['art::ThreadList::SuspendAll','art::ThreadList::ResumeAll']){const f=fixture({missing});assert.throws(f.activate,/exclusive suspension/);assert(f.live.every(x=>x===0xaa));assert.equal(f.state().patches,0);}
});
test('restore publishes original full relocated prefix while preserving in-flight trampoline lifetime',()=>{
  const f=fixture();f.activate();f.events.length=0;f.restore();assert(f.live.every(x=>x===0xaa));assert.equal(f.retired[0],f.interceptor);assert.equal(f.state().freed,0);assert(f.events.indexOf('snapshot')<f.events.indexOf('suspend'));assert(f.events.indexOf('icache')<f.events.indexOf('resume'));
});
test('restore ownership mismatch skips publication, never overwrites later owner and retains old trampoline',()=>{
  const f=fixture();f.activate();f.live[0]=0x77;f.events.length=0;f.restore();assert.equal(f.live[0],0x77);assert.equal(f.interceptor.restoreSkipped,true);assert(!f.events.includes('suspend'));assert.equal(f.state().freed,0);assert.equal(f.retired[0],f.interceptor);
});
test('retired owner is pinned only once across repeated disposal',()=>{
  const f=fixture();f.activate();f.restore();f.restore();assert.equal(f.retired.length,1);assert.equal(f.state().freed,0);
});
test('shared instrumentation bootstrap failure is sticky rather than false-ready in same VM',()=>{
  const body=part('function ensureArtKnowsHowToHandleMethodInstrumentation (','function instrumentArtQuickEntrypoints (');
  let calls=0;const failure=Error('second shared entry failed'),context={instrumentArtQuickEntrypoints(){calls++;throw failure;},instrumentArtMethodInvocationFromInterpreter(){throw Error('must not continue');},instrumentArtGarbageCollection(){throw Error('must not continue');},instrumentArtFixupStaticTrampolines(){throw Error('must not continue');}};
  vm.createContext(context);vm.runInContext('let taughtArtAboutMethodInstrumentation=false,artMethodInstrumentationFailure=null;\n'+body+'\nthis.run=ensureArtKnowsHowToHandleMethodInstrumentation;',context);assert.throws(()=>context.run({}),e=>e===failure);assert.throws(()=>context.run({}),e=>e===failure);assert.equal(calls,1);assert.equal(vm.runInContext('taughtArtAboutMethodInstrumentation',context),false);
});
test('successful shared bootstrap remains initialized once without repeating patches',()=>{
  const body=part('function ensureArtKnowsHowToHandleMethodInstrumentation (','function instrumentArtQuickEntrypoints (');let calls=0;const context={instrumentArtQuickEntrypoints(){calls++;},instrumentArtMethodInvocationFromInterpreter(){calls++;},instrumentArtGarbageCollection(){calls++;},instrumentArtFixupStaticTrampolines(){calls++;}};
  vm.createContext(context);vm.runInContext('let taughtArtAboutMethodInstrumentation=false,artMethodInstrumentationFailure=null;\n'+body+'\nthis.run=ensureArtKnowsHowToHandleMethodInstrumentation;',context);context.run({});context.run({});assert.equal(calls,4);
});
test('all three formerly live raw patch sites use the common staged publisher; original relocation and discovery remain exact',()=>{
  for(const arch of ['Arm','Arm64']){
    const before=inlineFunction(reversed,arch),after=inlineFunction(raw,arch);
    assert.equal(before.slice(0,before.indexOf('  inlineHooks.push(new InlineHook')),after.slice(0,after.indexOf('  const hook = new InlineHook')),'private trampoline/scanning/relocation is unchanged');
    assert.equal((after.match(/Memory\.patchCode\(/g)||[]).length,1,'only unpublished trampoline is raw');
    assert(after.includes('Memory.patchCode(trampoline, 256,'));
    const suffix=after.slice(after.indexOf('  const hook = new InlineHook'));
    assert(suffix.indexOf('writer.flush()')<suffix.indexOf('publishArtQuickCodePatch('));
    assert(!suffix.includes('Memory.patchCode'));
  }
  const revert=part('class InlineHook {','\nfunction instrumentGetOatQuickMethodHeaderInlinedCopyArm (');
  assert(!revert.includes('Memory.patchCode'));assert(revert.includes('publishArtQuickCodePatch(this.address, this.originalCode)'));
});
test('inline ARM64 dynamically selected x8 literal is finalized before publication and never exposes LDR#0/BR',()=>{
  const f=fixture(),hook=f.installInline('x8');assert.equal(f.live.readUInt32LE(0),0x58000048);assert.equal(f.live.readUInt32LE(4),0xd61f0100);assert.equal(f.live.readBigUInt64LE(8),0x12345678n);assert.equal(hook.publishedCode.byteLength,16);assert(f.events.indexOf('writer-flush')<f.events.indexOf('suspend'));assert.equal(f.context.inlineHooks[0],hook);assert.equal(f.state().stopped,false);
});
test('inline ARM Thumb target publication and revert preserve trampoline Thumb bit and pin retired page',()=>{
  const f=fixture({arch:'arm'}),hook=f.installInline();assert.equal(f.live.readUInt32LE(4),0x12345679);assert.equal(hook.publishedCode.byteLength,8);hook.revert();assert(f.live.every(x=>x===0xaa));assert.equal(f.context.retiredInlineHooks[0],hook);assert.equal(f.state().freed,0);assert.equal(f.state().stopped,false);
});
test('inline redirect publication errors rollback in the same exclusive suspension and preserve owner',()=>{
  for(const arch of ['arm','arm64'])for(const failPatch of ['before','partial','after']){const f=fixture({arch,failPatch});assert.throws(()=>f.installInline('x8'),/publish failure/);assert(f.live.every(x=>x===0xaa));assert.equal(f.state().patches,2);assert.equal(f.events.at(-1),'resume');assert.equal(f.context.inlineHooks.length,1);assert.equal(f.state().freed,0);assert.equal(f.state().stopped,false);}
});
test('inline revert does not overwrite a later owner, and retains original executable page on restore failure',()=>{
  const f=fixture(),hook=f.installInline('x8');f.live[0]=0x77;f.events.length=0;hook.revert();assert.equal(f.live[0],0x77);assert.equal(hook.restoreSkipped,true);assert(!f.events.includes('suspend'));assert.equal(f.context.retiredInlineHooks[0],hook);
  const g=fixture();const h=g.installInline('x8');g.context.publishArtQuickCodePatch=()=>{throw Error('restore failure');};assert.throws(()=>h.revert(),/restore failure/);assert.equal(g.context.retiredInlineHooks[0],h);assert.equal(g.state().freed,0);
});
test('replacement-method bootstrap preserves original failure instead of claiming partially instrumented inline targets ready',()=>{
  const body=part('function ensureArtKnowsHowToHandleReplacementMethods (','\nconst artGetOatQuickMethodHeaderInlinedCopyHandler');
  const failure=Error('second inline target failed');let calls=0;const context={maybeInstrumentGetOatQuickMethodHeaderInlineCopies(){calls++;throw failure;}};
  vm.createContext(context);vm.runInContext('let taughtArtAboutReplacementMethods=false,artReplacementMethodInstrumentationFailure=null;\n'+body+'\nthis.run=ensureArtKnowsHowToHandleReplacementMethods;',context);assert.throws(()=>context.run({}),e=>e===failure);assert.throws(()=>context.run({}),e=>e===failure);assert.equal(calls,1);assert.equal(vm.runInContext('taughtArtAboutReplacementMethods',context),false);
});
test('replacement bootstrap preserves legitimate empty-header return and instruments a successful runtime only once',()=>{
  const body=part('function ensureArtKnowsHowToHandleReplacementMethods (','\nconst artGetOatQuickMethodHeaderInlinedCopyHandler');let calls=0;const context={maybeInstrumentGetOatQuickMethodHeaderInlineCopies(){calls++;return false;},artController:{getOatQuickMethodHeaderImpl:null}};
  vm.createContext(context);vm.runInContext('let taughtArtAboutReplacementMethods=false,artReplacementMethodInstrumentationFailure=null;\n'+body+'\nthis.run=ensureArtKnowsHowToHandleReplacementMethods;',context);context.run({});context.run({});assert.equal(calls,1);assert.equal(vm.runInContext('artReplacementMethodInstrumentationFailure',context),null);assert.equal(vm.runInContext('taughtArtAboutReplacementMethods',context),true);
});
console.log(JSON.stringify({passed:true,tests:checks,preparedAndroidSha256:entry.afterSha256,reversedBaseSha256:entry.beforeSha256,actualPreparedSourceExecuted:true,writerSemanticsModeled:true,GumWriterExecuted:false,ARTExecuted:false,deviceOperations:false}));
