import assert from 'node:assert/strict';
import {createSlotDeferral} from '../src/native-slot-policy.js';

let count = 0;
function test(name, body) { body(); count++; console.log('PASS ' + name); }
function fixture(change) {
  const calls = [], warnings = [];
  const wire = (event, fields) => {
    calls.push({event, fields});
    const original = event === 'HELLO' ? {ok:true,decision:'READY',cycle_id:'cycle-1',source_slot:0} :
      {ok:true,decision:event==='DEFER'?'DEFER':'ACK',durable:true,cycle_id:fields.cycle_id,target_slot:fields.target_slot};
    return change ? change(event, fields, original) : original;
  };
  return {calls,warnings,policy:createSlotDeferral({wire,warn:(...x)=>warnings.push(x),sourceSlot:0})};
}
function armed(f, thread=7) { f.policy.enter(thread,0);f.policy.finish(thread,true,true); }
test('only successful FinishUpdate with an active Complete parent can defer',()=>{
  for (const mode of ['none','incoming-error','finish-false','finish-other-parent','wrong-parent','other-thread','source-slot','invalid-slot']) {
    const f=fixture();
    if(mode!=='none') f.policy.enter(7,mode==='incoming-error'?1:0);
    f.policy.finish(7,mode!=='finish-other-parent',mode!=='finish-false');
    assert.equal(f.policy.defer(mode==='other-thread'?8:7,mode!=='wrong-parent',mode==='source-slot'?0:mode==='invalid-slot'?2:1),false,mode);
    assert.equal(f.calls.length,0,mode);
  }
});
test('durable ack delays once, without changing original Finish or completion codes',()=>{
  const f=fixture();armed(f);assert.equal(f.policy.defer(7,true,1),true);
  assert.deepEqual(f.calls.map(c=>c.event),['HELLO','DEFER']);
  assert.equal(f.policy.defer(7,true,1),true);assert.equal(f.calls.length,4);
  const pending=f.policy.snapshot();f.policy.leave(7);f.policy.terminated(pending,0);
  assert.deepEqual(f.calls.at(-1),{event:'DONE',fields:{cycle_id:'cycle-1',target_slot:1,code:0}});
  assert.equal(f.policy.snapshot(),null);
});
test('no durable ack, stale cycle, wrong target and unarmed broker preserve original call',()=>{
  for(const mode of ['durable','cycle','target','error','wait','source','throw']) {
    const f=fixture((event,fields,reply)=>{
      if(mode==='throw')throw new Error('offline');
      if(event==='HELLO'&&mode==='wait')return {ok:false,decision:'WAIT_BEGIN'};
      if(event==='HELLO'&&mode==='source')return {...reply,source_slot:1};
      if(event==='DEFER')return {...reply,...({durable:{durable:false},cycle:{cycle_id:'old'},target:{target_slot:0},error:{ok:false}}[mode]||{})};
      return reply;
    });armed(f);assert.equal(f.policy.defer(7,true,1),false,mode);
    assert.equal(f.policy.snapshot(),null);assert.equal(f.warnings.length,1);
  }
});
test('nonzero completion including UpdatedButNotActive is FAIL, never DONE',()=>{
  for(const code of [1,52,-1]) {const f=fixture();armed(f);f.policy.defer(7,true,1);f.policy.terminated(f.policy.snapshot(),code);
    assert.equal(f.calls.at(-1).event,'FAIL');assert.equal(f.calls.at(-1).fields.code,code);}
});
test('late callback from another cycle cannot complete the current plan',()=>{
  const f=fixture();armed(f);f.policy.defer(7,true,1);
  f.policy.terminated({cycle_id:'stale',target_slot:1},0);
  assert.equal(f.calls.length,2);assert.equal(f.policy.snapshot().cycle_id,'cycle-1');
});
test('nested and thread-specific contexts do not borrow a successful parent Finish',()=>{
  const f=fixture();armed(f);f.policy.enter(7,0);assert.equal(f.policy.defer(7,true,1),false);
  f.policy.leave(7);assert.equal(f.policy.defer(8,true,1),false);assert.equal(f.policy.defer(7,true,1),true);
});
test('completion notification errors do not alter already returned native completion',()=>{
  const f=fixture((event,_fields,reply)=>{if(event==='DONE')throw new Error('closed');return reply;});
  armed(f);f.policy.defer(7,true,1);assert.doesNotThrow(()=>f.policy.terminated(f.policy.snapshot(),0));
  assert.equal(f.warnings.length,1);assert.equal(f.policy.snapshot(),null);
});
test('warnings and DEFER transport failures never replay native partition writes',()=>{
  let original=0;const p=createSlotDeferral({sourceSlot:0,wire(){throw new Error('lost');},warn(){throw new Error('logger');}});
  p.enter(7,0);p.finish(7,true,true);if(!p.defer(7,true,1))original++;
  assert.equal(original,1);
});
test('a second Complete in the same cycle also defers through the idempotent root transaction',()=>{
  const f=fixture();armed(f);assert.equal(f.policy.defer(7,true,1),true);f.policy.leave(7);
  armed(f);assert.equal(f.policy.defer(7,true,1),true);
  assert.deepEqual(f.calls.map(c=>c.event),['HELLO','DEFER','HELLO','DEFER']);
  assert.equal(f.warnings.length,0);
});
test('cooperative broker reentry on another native thread never falls through to HAL',()=>{
  let policy,reentered=false,nestedResult,nestedProof;
  const f=fixture((event,_fields,reply)=>{
    if(event==='DEFER'&&!reentered){reentered=true;policy.enter(8,0);policy.finish(8,true,true);
      nestedResult=policy.defer(8,true,1);nestedProof=policy.snapshot(8);policy.leave(8);}
    return reply;
  });policy=f.policy;armed(f);assert.equal(policy.defer(7,true,1),true);assert.equal(nestedResult,true);
  assert.equal(f.calls.filter(c=>c.event==='DEFER').length,2);assert.equal(f.warnings.length,0);
  const outerProof=policy.snapshot(7);policy.terminated(nestedProof,0);
  assert.equal(policy.snapshot().local_id,outerProof.local_id);policy.terminated(outerProof,0);assert.equal(policy.snapshot(),null);
});
test('context-local repetition refreshes its cycle rather than blindly reusing old proof',()=>{
  let cycle='one';const f=fixture((event,_fields,reply)=>event==='HELLO'?{...reply,cycle_id:cycle}:reply);
  armed(f);assert.equal(f.policy.defer(7,true,1),true);const previous=f.policy.snapshot();cycle='two';
  assert.equal(f.policy.defer(7,true,1),true);const current=f.policy.snapshot();assert.equal(current.cycle_id,'two');
  f.policy.terminated(previous,0);assert.equal(f.policy.snapshot().local_id,current.local_id);
});
test('DONE or FAIL RPC reentry preserves the new cycle and its own completion proof',()=>{
  for(const code of [0,52]){let cycle='one',policy;
    const f=fixture((event,_fields,reply)=>{
      if(event==='HELLO')return {...reply,cycle_id:cycle};
      if(event===(code===0?'DONE':'FAIL')){cycle='two';policy.enter(8,0);policy.finish(8,true,true);assert.equal(policy.defer(8,true,1),true);policy.leave(8);}
      return reply;
    });policy=f.policy;armed(f);policy.defer(7,true,1);const previous=policy.snapshot();policy.leave(7);policy.terminated(previous,code);
    assert.equal(policy.snapshot().cycle_id,'two');assert.equal(policy.snapshot(8).cycle_id,'two');
  }
});
console.log(count+' native slot policy host tests passed; no updater, HAL, partition or Binder call ran');
