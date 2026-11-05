import assert from 'node:assert/strict';
import fs from 'node:fs';
import {startCooperativeScan} from '../src/cooperative-scan.js';
import {createRebootLabelController, installRebootLabels, isManualRestartText, readRestartCandidateText} from '../src/reboot-labels.js';
let passed=0;
function test(name,fn){fn();passed++;console.log('PASS '+name);}
function pumpFixture({length=10000,cost=0,fail=false,defer=false}={}) {
  let time=0,reads=0,closed=0,finished=0,errors=0,defers=0,current=true,id=0;
  const tasks=new Map(),chunks=[];
  const pump=startCooperativeScan({
    iterator:{next(){if(fail)throw Error('iterator failed');time+=cost;return reads<length?{value:reads++,done:false}:{done:true};},return(){closed++;return {done:true};}},
    now:()=>time,current:()=>current,beforeSlice:()=>!defer,deferred:()=>defers++,
    schedule:(fn,delay)=>{assert.equal(delay,16);tasks.set(++id,fn);return id;},cancel:id=>tasks.delete(id),
    chunk:(values,steps)=>chunks.push({values,steps}),done:()=>finished++,error:()=>errors++,
  });
  return {pump,tasks,chunks,counts:()=>({reads,closed,finished,errors,defers}),
    stale:()=>{current=false;},step(){const entry=tasks.entries().next().value;if(entry){tasks.delete(entry[0]);entry[1]();return true;}return false;}};
}
test('wide discovery yields at the operation cap and processes every value once',()=>{
  const f=pumpFixture();f.pump.run();assert.equal(f.counts().reads,2048);assert.equal(f.tasks.size,1);
  while(f.step());assert.equal(f.counts().finished,1);assert.equal(f.counts().closed,1);
  assert.ok(f.chunks.every(chunk=>chunk.steps<=2048));
  assert.deepEqual(f.chunks.flatMap(chunk=>chunk.values),Array.from({length:10000},(_,i)=>i));
});
test('elapsed bridge cost yields at four milliseconds rather than traversing the tree',()=>{
  const f=pumpFixture({length:25,cost:1});f.pump.run();assert.equal(f.counts().reads,4);
  while(f.step());assert.ok(f.chunks.every(chunk=>chunk.steps<=4));assert.equal(f.counts().finished,1);
});
test('cancel releases cursor and queued continuation without later publication',()=>{
  const f=pumpFixture();f.pump.run();f.pump.cancel();f.pump.cancel();assert.equal(f.tasks.size,0);
  assert.equal(f.counts().closed,1);assert.equal(f.chunks.length,1);assert.equal(f.counts().finished,0);
});
test('a continuation already queued on UI rechecks the screen generation',()=>{
  const f=pumpFixture();f.pump.run();f.stale();f.step();assert.equal(f.counts().reads,2048);
  assert.equal(f.counts().closed,1);assert.equal(f.tasks.size,0);
});
test('scroll deferral closes the cursor before another bridge read',()=>{
  const f=pumpFixture({defer:true});f.pump.run();assert.deepEqual(f.counts(),{reads:0,closed:1,finished:0,errors:0,defers:1});
});
test('iterator failure closes once and reports once without a retry loop',()=>{
  const f=pumpFixture({fail:true});f.pump.run();assert.equal(f.counts().errors,1);assert.equal(f.counts().closed,1);assert.equal(f.tasks.size,0);
});
function sequence(text,{unsupported=false}={}) {
  let chars=0,conversions=0;
  return {length(){if(unsupported)throw Error('optional API');return text.length;},charAt(i){chars++;return text[i];},
    toString(){conversions++;return text;},counts:()=>({chars,conversions})};
}
test('long unrelated notes use a bounded prefix and avoid full string conversion',()=>{
  for(const text of ['Release notes: '+ 'a'.repeat(100000),'更新内容'+ 'a'.repeat(100000),'Install '+ 'a'.repeat(100000)]) {
    const value=sequence(text);assert.equal(readRestartCandidateText(value),null);assert.equal(value.counts().conversions,0);assert.ok(value.counts().chars<=16);
  }
});
test('long native restart texts preserve all prior trimming Unicode and whitespace matches',()=>{
  const whites=[' ','\t','\n','\u00a0','\u2003','\ufeff'];
  const native=['立即重启','立即重新启动','立即重新啟動','立即重新開機','Restart now','REBOOT NOW','reſtart now'];
  for(const prefix of whites)for(const text of native) {
    const full=prefix+text+' '.repeat(300),value=sequence(full);
    assert.equal(isManualRestartText(full),true);assert.equal(readRestartCandidateText(value),full);
  }
  const unusual=' '.repeat(300)+'restart\t\nnow';assert.equal(readRestartCandidateText(sequence(unusual)),unusual);
});
test('every localized resource alias remains an exact match even when its full value is long',()=>{
  for(const text of ['Redémarrer maintenant','Jetzt neu starten','今すぐ再起動','🚀'.repeat(160),'R'.repeat(300)]) {
    const full=text+' '.repeat(300);assert.equal(readRestartCandidateText(sequence(full),[text]),full);
  }
});
test('tracked and unsupported CharSequences retain the original full text path',()=>{
  const text='Install '+ 'x'.repeat(500);let value=sequence(text);assert.equal(readRestartCandidateText(value,[],true),text);assert.equal(value.counts().chars,0);
  value=sequence(text,{unsupported:true});assert.equal(readRestartCandidateText(value),text);assert.equal(value.counts().conversions,1);
  assert.equal(readRestartCandidateText(text),text);
});
test('numeric UTF16 charAt bridges preserve native and localized restart matches',()=>{
  for(const text of ['Restart now','REBOOT NOW','reſtart now','立即重新開機','🚀'.repeat(160)]) {
    const full=text+' '.repeat(300),value=sequence(full);value.charAt=i=>full.charCodeAt(i);
    assert.equal(readRestartCandidateText(value,[text]),full);
  }
  const full='Release notes '+ 'x'.repeat(500),value=sequence(full);value.charAt=i=>full.charCodeAt(i);
  assert.equal(readRestartCandidateText(value),null);assert.equal(value.counts().conversions,0);
});
test('unknown or malformed charAt representations use the full compatible text path',()=>{
  for(const cell of [null,undefined,{},65536,-1,1.5,'ab','']) {
    const full='Restart now'+' '.repeat(300),value=sequence(full);value.charAt=()=>cell;
    assert.equal(readRestartCandidateText(value),full);assert.equal(value.counts().conversions,1);
  }
});
// Use the same strict Android ABI fixture as the original compatibility suite.
const legacy=fs.readFileSync(new URL('./reboot-labels.test.mjs',import.meta.url),'utf8');
const reflect=legacy.slice(legacy.indexOf('const reflectedType ='),legacy.indexOf("test('runtime interface methods"));
const androidSource=legacy.slice(legacy.indexOf('function androidFixture('),legacy.indexOf("test('Android adapter resource"));
assert.equal(androidSource.split('close(){globalThis.setTimeout').length,2);
const android=androidSource.replace('close(){globalThis.setTimeout','weakRefCount:()=>weakRefs.length,close(){globalThis.setTimeout');
const androidFixture=new Function('assert','installRebootLabels',reflect+android+'\nreturn androidFixture;')(assert,installRebootLabels);
function finish(f,labels){for(let i=0;i<100&&labels.inspect().discoverySlices<100;i++){const before=f.bridge.bodyTextReads;f.tick(16);if(f.bridge.bodyTextReads===6000&&before===6000)return;}assert.fail('large scan did not converge');}
test('six thousand native text views are split across UI frames with all candidates retained',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',bodyCount:6000,layoutDelivery:'none'});try{
    const labels=f.install();f.tick();assert.ok(f.bridge.bodyTextReads<6000);assert.ok(f.bridge.bodyTextReads>0);
    const first=f.bridge.bodyTextReads;f.tick(15);assert.equal(f.bridge.bodyTextReads,first);f.tick(1);assert.ok(f.bridge.bodyTextReads>first);
    finish(f,labels);assert.equal(f.text.text,'继续流程');assert.equal(labels.inspect().tracked,1);assert.equal(f.bridge.bodyParentReads,0);
    const before={...f.bridge};for(let i=0;i<3;i++)f.tick(2500);assert.deepEqual(f.bridge,before);labels.dispose();f.tick();
  }finally{f.close();}
});
test('disposing a partially scanned large screen cancels cursor and every later bridge read',()=>{
  const f=androidFixture({bodyCount:6000,layoutDelivery:'none'});try{
    const labels=f.install();f.tick();assert.ok(f.bridge.bodyTextReads<6000);labels.dispose();f.tick();const before={...f.bridge};
    f.tick(10000);assert.deepEqual(f.bridge,before);assert.equal(f.timers.size,0);assert.equal(f.watcherCount(),0);assert.equal(f.scrollListenerCount(),0);
  }finally{f.close();}
});
test('pause prevents an unfinished discovery from reading or modifying the old Activity',()=>{
  const f=androidFixture({bodyCount:6000,layoutDelivery:'none'});try{
    const labels=f.install();f.tick();f.callbacks[0].onActivityPaused(f.activity);f.tick();const before={...f.bridge};
    f.tick(10000);assert.deepEqual(f.bridge,before);assert.equal(labels.inspect().active,false);assert.equal(f.timers.size,0);labels.dispose();f.tick();
  }finally{f.close();}
});
test('layout storms during a sliced scan coalesce one follow-up and eventually become idle',()=>{
  const f=androidFixture({bodyCount:6000,layoutDelivery:'none'});try{
    const labels=f.install();f.tick();for(let i=0;i<30;i++)f.emitLayout();
    for(let i=0;i<60;i++)f.tick(16);assert.equal(f.text.text,'继续流程');
    assert.ok(labels.inspect().discoveryCount<=3);const before={...f.bridge};f.tick(2500);assert.deepEqual(f.bridge,before);labels.dispose();f.tick();
  }finally{f.close();}
});
test('tree discovery allocates one Java weak reference per additional view and none on stable polls',()=>{
  let fixedReferences=null;
  for(const count of [0,100,1000,6000]) {
    const f=androidFixture({choose:'missing',roots:'manager',bodyCount:count,layoutDelivery:'none'});try{
      const labels=f.install();f.tick();
      for(let i=0;i<100&&f.bridge.bodyTextReads<count;i++)f.tick(16);
      f.tick(16);assert.equal(f.bridge.bodyTextReads,count);assert.equal(labels.inspect().tracked,1);
      const allocated=f.weakRefCount();
      if(fixedReferences===null)fixedReferences=allocated;
      assert.equal(allocated,fixedReferences+count,'visited must reuse each cursor WeakReference rather than allocate another Java object');
      const bridge={...f.bridge};for(let i=0;i<3;i++)f.tick(2500);
      assert.equal(f.weakRefCount(),allocated);assert.deepEqual(f.bridge,bridge);
      labels.dispose();f.tick();assert.equal(f.timers.size,0);assert.equal(f.watcherCount(),0);assert.equal(f.scrollListenerCount(),0);
    }finally{f.close();}
  }
});
console.log(JSON.stringify({passed:true,tests:passed,deviceOperations:false,markerMutations:false,slotOperations:false,rebootOperations:false}));
