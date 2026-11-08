import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRebootLabelController, installRebootLabels} from '../src/reboot-labels.js';

let passed = 0;
function test(name, body) { body(); passed++; console.log('PASS ' + name); }
function fixture() {
  let time = 1000, sequence = 0, reply = {ok:true,state:0};
  const tasks = new Map(), ui = [], events = [], errors = [], writes = [], warnings = [];
  const view = {text:'立即重启'}, owner = {view};
  let queuedMain = false, reads = 0, scans = 0;
  const later = (fn, delay) => { const id = ++sequence; tasks.set(id,{fn,at:time+delay}); return id; };
  const labels = createRebootLabelController({
    weak:value=>({get:()=>value}),same:(a,b)=>a===b,now:()=>time,later,cancel:id=>tasks.delete(id),
    main:fn=>queuedMain ? ui.push(fn) : fn(),background:fn=>later(fn,0),
    scan:root=>{scans++;return [{view:root.view,text:root.view.text,manual:true,visible:true,clickable:true,listener:true}];},
    read:value=>({text:value.text,visible:true,clickable:true,listener:true}),
    write:(value,text)=>{writes.push(text);value.text=text;},
    state:()=>{reads++;return typeof reply==='function'?reply():reply;},
    onState:value=>events.push(value),onStateError:error=>errors.push(error),warn:(...args)=>warnings.push(args),
  });
  const tick = (ms=0) => { time+=ms; let calls=0; for (;;) {
    const entry=[...tasks].find(([,item])=>item.at<=time); if (!entry) return;
    assert.ok(++calls<100,'STATE requests failed to coalesce');tasks.delete(entry[0]);entry[1].fn();
  }};
  return {labels,view,owner,events,errors,writes,warnings,tick,setReply:value=>{reply=value;},
    counts:()=>({reads,scans}),queueMain:()=>{queuedMain=true;},flush:()=>{while(ui.length)ui.shift()();},
    pending:()=>tasks.size};
}

test('state-only return refresh bypasses the cache without discovering the tree',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();const before=f.labels.inspect();
  f.setReply({ok:true,state:1});f.labels.refreshState(true);f.tick();
  assert.equal(f.view.text,'立即重启');assert.equal(f.counts().reads,2);
  assert.equal(f.labels.inspect().discoveryCount,before.discoveryCount);
  assert.deepEqual(f.events,[0,1]);f.labels.dispose();
});
test('pure hotpatch restores native text without fabricating a marker value',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();
  assert.equal(f.view.text,'继续流程');
  f.setReply({native:true});f.labels.refreshState(true);f.tick();
  assert.equal(f.view.text,'立即重启');assert.deepEqual(f.events,[0]);
  f.setReply({ok:true,state:0});f.labels.refreshState(true);f.tick();
  assert.equal(f.view.text,'继续流程');f.labels.dispose();
});
test('native click return preserves result once and reads the consumed file without a tree scan',()=>{
  const f=fixture();f.setReply({ok:true,state:1});f.labels.resume(f.owner);f.tick();
  const before=f.counts();let calls=0;
  assert.equal(f.labels.aroundClick(f.view,()=>{calls++;f.setReply({ok:true,state:0});return 31;}),31);
  f.tick();assert.equal(calls,1);assert.equal(f.view.text,'继续流程');
  assert.equal(f.counts().scans,before.scans);assert.equal(f.counts().reads,before.reads+1);f.labels.dispose();
});
test('force events during one old read coalesce and its stale response never changes presentation',()=>{
  const f=fixture();f.setReply({ok:true,state:1});f.labels.resume(f.owner);f.tick();const before=f.labels.inspect();
  let old=true;f.setReply(()=>{if(old){old=false;f.labels.refreshState(true);f.labels.refreshState(true);return {ok:true,state:0};}return {ok:true,state:1};});
  f.labels.refreshState(true);f.tick();
  assert.equal(f.view.text,'立即重启');assert.equal(f.counts().reads,3);
  assert.equal(f.labels.inspect().renderCount,before.renderCount);assert.deepEqual(f.events,[1]);f.labels.dispose();
});
test('multiple force events before a worker starts use one actual latest file read',()=>{
  const f=fixture();f.labels.resume(f.owner);f.labels.refreshState(true);f.labels.refreshState(true);
  f.setReply({ok:true,state:1});f.tick();assert.equal(f.counts().reads,1);assert.equal(f.view.text,'立即重启');f.labels.dispose();
});
test('a diagnostic callback yielding to a newer force cannot publish its older response',()=>{
  const f=fixture();f.setReply({ok:true,state:1});f.labels.resume(f.owner);f.tick();const before=f.labels.inspect();
  f.events.push=function(value){Array.prototype.push.call(this,value);if(value===0){f.setReply({ok:true,state:1});f.labels.refreshState(true);}};
  f.setReply({ok:true,state:0});f.labels.refreshState(true);f.tick();
  assert.equal(f.view.text,'立即重启');assert.equal(f.labels.inspect().writeCount,before.writeCount);
  assert.equal(f.counts().reads,3);f.labels.dispose();
});
test('a UI render queued before a newer forced read cannot publish the older state',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();f.queueMain();const before=f.writes.length;
  f.setReply({ok:true,state:1});f.labels.refreshState(true);f.tick();
  f.setReply({ok:true,state:0});f.labels.refreshState(true);f.flush();
  assert.equal(f.view.text,'继续流程');assert.equal(f.writes.length,before);
  f.tick();f.flush();assert.equal(f.view.text,'继续流程');assert.equal(f.writes.includes('立即重启'),false);f.labels.dispose();f.flush();
});
test('old screen read completion cannot clear a new screen queued file read',()=>{
  const f=fixture();const other={view:{text:'立即重启'}};
  f.setReply(()=>{f.labels.pause(f.owner);f.setReply({ok:true,state:0});f.labels.resume(other);return {ok:true,state:1};});
  f.labels.resume(f.owner);f.tick();assert.equal(other.view.text,'继续流程');assert.equal(f.counts().reads,2);
  assert.deepEqual(f.events,[0]);f.labels.dispose();
});
test('state diagnostics record only successful value changes and one event per consecutive read failure',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();f.labels.refreshState(true);f.tick();
  f.setReply(()=>{throw new Error('STATE transport absent');});f.labels.refreshState(true);f.tick();f.labels.refreshState(true);f.tick();
  assert.deepEqual(f.events,[0]);assert.equal(f.errors.length,1);assert.equal(f.view.text,'继续流程');
  f.setReply({ok:true,state:1});f.labels.refreshState(true);f.tick();assert.deepEqual(f.events,[0,1]);
  f.setReply({ok:false,state:1});f.labels.refreshState(true);f.tick();assert.equal(f.errors.length,2);
  assert.equal(f.view.text,'继续流程');assert.deepEqual(f.events,[0,1]);f.labels.dispose();
});
test('stable polling after a forced refresh still adds no UI discovery render or writes',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();f.labels.refreshState(true);f.tick();const before=f.labels.inspect();
  for(let i=0;i<3;i++)f.tick(2500);const after=f.labels.inspect();
  for(const key of ['discoveryCount','candidateReadCount','renderCount','writeCount'])assert.equal(after[key],before[key],key);
  assert.equal(after.stateReadCount,before.stateReadCount+3);f.labels.dispose();
});

// Reuse the frozen, strict Android/WeakReference/observer ABI fixture instead of
// maintaining another partial Android model or changing the original 79 cases.
const legacy=fs.readFileSync(new URL('./reboot-labels.test.mjs',import.meta.url),'utf8');
const reflect=legacy.slice(legacy.indexOf('const reflectedType ='),legacy.indexOf("test('runtime interface methods"));
const android=legacy.slice(legacy.indexOf('function androidFixture('),legacy.indexOf("test('Android adapter resource"));
assert.ok(reflect.startsWith('const reflectedType =')&&android.startsWith('function androidFixture('));
const androidFixture=new Function('assert','installRebootLabels',reflect+android+'\nreturn androidFixture;')(assert,installRebootLabels);

test('same already-active window focus return reads file one inside cache without a full discovery',()=>{
  const f=androidFixture({layoutDelivery:'none',decorContext:true});try{
    const labels=f.install();f.tick();const before=labels.inspect();assert.equal(f.text.text,'继续流程');
    f.setReply({ok:true,state:1});f.focus(f.group,true);f.tick();
    assert.equal(f.text.text,'立即重启');assert.equal(labels.inspect().discoveryCount,before.discoveryCount);
    assert.equal(labels.inspect().stateReadCount,before.stateReadCount+1);labels.dispose();
  }finally{f.close();}
});
test('focus-return forced state respects active scrolling and does not manufacture discovery',()=>{
  const f=androidFixture({layoutDelivery:'none'});try{
    const labels=f.install();f.tick();const before=labels.inspect();f.emitScroll();
    f.setReply({ok:true,state:1});f.focus(f.group,true);f.tick(199);
    assert.equal(f.text.text,'继续流程');assert.equal(labels.inspect().discoveryCount,before.discoveryCount);
    f.tick(1);assert.equal(f.text.text,'立即重启');assert.equal(labels.inspect().discoveryCount,before.discoveryCount);labels.dispose();
  }finally{f.close();}
});
console.log(JSON.stringify({passed:true,tests:passed,deviceOperations:false,markerMutations:false,slotOperations:false,rebootOperations:false}));
