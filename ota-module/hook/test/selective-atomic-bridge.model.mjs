// Separate bridge-pipeline test: node test/selective-atomic-bridge.model.mjs PREPARED/lib/android.js
// No ART/JNI/device calls. Execute the actual prepared request and STW functions
// with strict native-state/lock/ordering ports, including failure cleanup.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {createHash} from 'node:crypto';

const path=process.argv[2];
if(!path)throw new Error('Supply lib/android.js from apply_bridge_backport.prepare output');
let raw=fs.readFileSync(path,'utf8');
const recipe=JSON.parse(fs.readFileSync(new URL('../patches/selective-deoptimization-atomic-policy.json',import.meta.url),'utf8'));
const inputSha256=createHash('sha256').update(raw).digest('hex');
const quickLayer=JSON.parse(fs.readFileSync(new URL('../patches/art-quick-code-publication-policy.json',import.meta.url),'utf8')).files.find(item=>item.path==='lib/android.js');
if(inputSha256===quickLayer.afterSha256){
  for(const operation of [...quickLayer.operations].reverse()){assert.equal(raw.split(operation.new).length-1,1);raw=raw.replace(operation.new,operation.old);}
  assert.equal(createHash('sha256').update(raw).digest('hex'),quickLayer.beforeSha256);
}
const nativeLayer=JSON.parse(fs.readFileSync(new URL('../patches/native-transaction-filter-policy.json',import.meta.url),'utf8')).files.find(item=>item.path==='lib/android.js');
if(createHash('sha256').update(raw).digest('hex')===nativeLayer.afterSha256){
  for(const operation of [...nativeLayer.operations].reverse()){
    assert.equal(raw.split(operation.new).length-1,1,'reverse exact native-filter context');
    raw=raw.replace(operation.new,operation.old);
  }
  assert.equal(createHash('sha256').update(raw).digest('hex'),nativeLayer.beforeSha256,'restore batch layer before historical atomic checks');
}
if(inputSha256!==recipe.afterSha256){
  // A later independent layer adds only the bounded batch API. Verify its exact
  // final identity, reverse its explicit operations, and prove the historical
  // atomic layer byte-for-byte before running the unchanged old-route model.
  const batch=JSON.parse(fs.readFileSync(new URL('../patches/selective-deoptimization-batch-policy.json',import.meta.url),'utf8'));
  const entry=batch.files.find(item=>item.path==='lib/android.js');
  assert.equal(createHash('sha256').update(raw).digest('hex'),entry.afterSha256,'only the recorded batch layer may follow the atomic layer');
  assert.equal(entry.beforeSha256,recipe.afterSha256,'batch begins at the historical atomic hash');
  for(const operation of [...entry.operations].reverse()){
    assert.equal(raw.split(operation.new).length-1,1,'reverse context matches once');
    raw=raw.replace(operation.new,operation.old);
  }
}
assert.equal(createHash('sha256').update(raw).digest('hex'),recipe.afterSha256,'test binds the exact prepared bridge');
const part=(start,end)=>{const a=raw.indexOf(start),b=raw.indexOf(end,a);assert(a>=0&&b>a);return raw.slice(a,b).replace(/^export /gm,'');};
const source=part('export function deoptimizeMethod (','export function deoptimizeEverything (')+'\n'+
  part('function requestDeoptimization (','\nclass JdwpSession')+'\n'+
  part('export function withAllArtThreadsSuspended (','\nclass ArtClassVisitor');
let checks=0;
function test(name,fn){fn();checks++;console.log('PASS '+name);}
function fixture({level=36,enable=false,enabled=false,fail='',missing='',instrumentationNull=false}={}){
  let state='native',stopped=false;const events=[];
  const enabledAddress={readU8(){events.push('enabled-read');return enabled?1:0;}};
  const instrumentation={add(offset){assert(!stopped,'spec/address resolution must precede suspension');assert.equal(offset,17);events.push('address');return enabledAddress;}};
  const api={artInstrumentation:instrumentationNull?null:instrumentation,artThreadList:{name:'threads'},
    'art::ThreadList::SuspendAll'(list,cause,long){assert.equal(state,'native','never suspend while holding Runnable/shared mutator');assert(!stopped);assert.equal(long,0);events.push('suspend');if(fail==='suspend')throw Error('suspend failure');stopped=true;},
    'art::ThreadList::ResumeAll'(){assert(stopped);events.push('resume');stopped=false;},
    'art::Instrumentation::Deoptimize'(value,method){assert(stopped,'all mutators must be stopped');assert.equal(state,'native');assert.strictEqual(value,instrumentation);assert.equal(method,'decoded-method');events.push('deopt');if(fail==='deopt')throw Error('native selective failure');},
    'art::Instrumentation::DeoptimizeEverything'(){assert.equal(state,'runnable');assert(!stopped);events.push('global');},
    isJdwpStarted:()=>true,isDebuggerActive:()=>true,
    'art::Dbg::RequestDeoptimization'(){assert.equal(state,'runnable');assert(!stopped);events.push('request');},
    'art::Dbg::ManageDeoptimization'(){assert.equal(state,'runnable');events.push('manage');}
  };
  if(enable)api['art::Instrumentation::EnableDeoptimization']=()=>{assert(stopped);events.push('enable');if(fail==='enable')throw Error('enable failure');};
  if(missing)delete api[missing];
  const memory={writeU32(){return memory;},add(){return memory;},writePointer(){return memory;}};
  const context={kSelectiveDeoptimization:2,kFullDeoptimization:3,pointerSize:8,jdwpSessions:[],getAndroidApiLevel:()=>level,
    getApi(){assert(!stopped,'API lookup precedes suspension');events.push('api');return api;},
    getArtInstrumentationSpec(){assert(!stopped,'no lazy metadata/JNI during STW');events.push('spec');return {offset:{deoptimizationEnabled:17}};},
    unwrapMethodId(method){assert.equal(state,'native');assert(!stopped);assert.equal(method,'opaque-method');events.push('decode');return 'decoded-method';},
    withRunnableArtThread(_vm,_env,body){assert(!stopped);events.push('runnable-enter');state='runnable';try{return body();}finally{state='native';events.push('runnable-exit');}},
    Memory:{alloc:()=>memory,allocUtf8String:s=>{assert(!stopped);return s;}},startJdwp(){throw Error('fixture does not start JDWP');}};
  vm.createContext(context);vm.runInContext(source,context);
  return {events,run:()=>context.deoptimizeMethod({}, {}, 'opaque-method'),global:()=>context.requestDeoptimization({}, {},3),state:()=>({state,stopped})};
}
test('modern selective decoder and API resolve precede native suspend/deopt/resume',()=>{
  const f=fixture();f.run();assert.deepEqual(f.events,['decode','api','api','suspend','deopt','resume']);assert.deepEqual(f.state(),{state:'native',stopped:false});
});
test('optional enable metadata is resolved before STW and enabled only inside it',()=>{
  const f=fixture({enable:true});f.run();assert.deepEqual(f.events,['decode','api','spec','address','api','suspend','enabled-read','enable','deopt','resume']);
});
test('already enabled runtime never repeats EnableDeoptimization',()=>{
  const f=fixture({enable:true,enabled:true});f.run();assert(!f.events.includes('enable'));assert(f.events.includes('deopt'));
});
test('selective exception always resumes mutators before propagating',()=>{
  const f=fixture({fail:'deopt'});assert.throws(f.run,/native selective failure/);assert.equal(f.events.at(-1),'resume');assert.equal(f.state().stopped,false);
});
test('enable exception always resumes and never mutates a method',()=>{
  const f=fixture({enable:true,fail:'enable'});assert.throws(f.run,/enable failure/);assert.equal(f.events.at(-1),'resume');assert(!f.events.includes('deopt'));
});
test('missing instrumentation rejects before any suspension',()=>{
  const f=fixture({instrumentationNull:true});assert.throws(f.run,/Instrumentation unavailable/);assert(!f.events.includes('suspend'));
});
test('missing either STW capability rejects before native mutation',()=>{
  for(const missing of ['art::ThreadList::SuspendAll','art::ThreadList::ResumeAll']){const f=fixture({missing});assert.throws(f.run,/exclusive suspension API unavailable/);assert(!f.events.includes('suspend'));assert(!f.events.includes('deopt'));}
});
test('SuspendAll failure does not pretend a successful acquisition or call Deoptimize',()=>{
  const f=fixture({fail:'suspend'});assert.throws(f.run,/suspend failure/);assert(!f.events.includes('deopt'));assert(!f.events.includes('resume'));assert.equal(f.state().stopped,false);
});
test('legacy Dbg route remains Runnable and does not acquire nested exclusive STW',()=>{
  const f=fixture({level:29});f.run();assert(f.events.includes('request'));assert(f.events.includes('manage'));assert(!f.events.includes('suspend'));assert.deepEqual(f.state(),{state:'native',stopped:false});
});
test('existing modern global route retains its original transition contract',()=>{
  const f=fixture();f.global();assert(f.events.includes('global'));assert(!f.events.includes('suspend'));assert(!f.events.includes('deopt'));assert.deepEqual(f.state(),{state:'native',stopped:false});
});
test('unsupported legacy API rejects without touching native state',()=>{
  const f=fixture({level:23});assert.throws(f.run,/only available on Android/);assert.deepEqual(f.state(),{state:'native',stopped:false});assert(!f.events.includes('suspend'));
});
console.log(JSON.stringify({passed:true,tests:checks,inputPreparedSha256:inputSha256,preparedSha256:recipe.afterSha256,nativeCallsModeled:true,ARTExecuted:false,deviceOperations:false}));
