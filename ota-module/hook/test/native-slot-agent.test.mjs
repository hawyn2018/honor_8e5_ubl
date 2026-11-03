import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import {inside,locateNativeSlots,SLOT_SYMBOLS} from '../src/native-slot-locator.js';
import {createSlotDeferral} from '../src/native-slot-policy.js';

const source=fs.readFileSync(new URL('../src/native-slot-agent.js',import.meta.url),'utf8').replace(/^import .*;\r?\n/gm,'');
class Pointer {constructor(n){this.n=BigInt(n);}add(n){return new Pointer(this.n+BigInt(n));}toString(){return '0x'+this.n.toString(16);}toInt32(){return Number(BigInt.asIntN(32,this.n));}}
const ptr=n=>new Pointer(n);
let count=0;
function test(name,body){body();count++;console.log('PASS '+name);}
function fixture({missing=false,wait=false,wireChange,failOriginal=false,failAfterReplace=false}={}) {
  const events=[],calls=[],original=[],attached=new Map(),detached=[],reverted=[];
  const symbols=Object.entries(SLOT_SYMBOLS).map(([role,name],i)=>({role,name,type:'function',address:ptr(0x1000+i*0x100),size:16}));
  const fact=Object.fromEntries(symbols.map(s=>[s.role,s]));if(missing)symbols.pop();
  const params={activation_id:'act',boot_id:'boot',engine_pid:42,engine_start:'99',engine_path:'/system/bin/update_engine',source_slot:0,endpoint:'/data/fixture/native.sock'};
  const module={path:params.engine_path,base:ptr(0x1000),size:0x1000,enumerateSymbols:()=>symbols};
  const context=vm.createContext({inside,locateNativeSlots,createSlotDeferral,rpc:{exports:{}},send:e=>events.push(e),console,
    File:{readAllText:path=>path==='/proc/self/stat'?'42 (update_engine) S '+Array(18).fill('0').join(' ')+' 99':'boot\n'},
    Process:{id:42,arch:'arm64',pointerSize:8,mainModule:module,getCurrentThreadId:()=>7,findRangeByAddress:()=>({protection:'r-x'})},
    Instruction:{parse:()=>({mnemonic:'stp',opStr:'x29, x30, [sp, #-0x20]!',size:4})},
    NativeCallback:function(fn){return fn;},NativeFunction:function(){if(failOriginal)throw new Error('trampoline constructor');return (...args)=>{original.push(args);return 1;};},
    Interceptor:{attach(address,handler){attached.set(String(address),handler);return {detach(){detached.push(String(address));}};},
      replace(address,callback){this.replacement=callback;},replaceFast(){throw new Error('fast replacement has no invocation-context contract');},
      flush(){if(failAfterReplace&&this.replacement&&reverted.length===0)throw new Error('post-replace flush');},revert(address){reverted.push(String(address));}}
  });
  vm.runInContext(source,context);
  context.mockWire=(event,fields)=>{
    calls.push({event,fields});
    const reply=event==='HELLO'?(wait?{ok:false,decision:'WAIT_BEGIN'}:{ok:true,decision:'READY',source_slot:0,cycle_id:'cycle'}):
      {ok:true,decision:event==='DEFER'?'DEFER':'ACK',durable:true,cycle_id:fields.cycle_id,target_slot:fields.target_slot};
    return wireChange?wireChange(event,fields,reply):reply;
  };
  vm.runInContext('libcWire = () => mockWire',context);
  const init=extra=>context.rpc.exports.init('late',{...params,...extra});
  const enterComplete=(code=0)=>{const h=attached.get(String(fact.complete.address)),c={threadId:7};h.onEnter.call(c,[ptr(0),ptr(code)]);return ()=>h.onLeave.call(c);};
  const finish=(success=1,parent=true)=>{const h=attached.get(String(fact.finish.address)),c={threadId:7,returnAddress:parent?fact.complete.address.add(8):ptr(0x1f00)};h.onEnter.call(c);h.onLeave.call(c,ptr(success));};
  const set=(slot=1,parent=true)=>context.Interceptor.replacement.call({returnAddress:parent?fact.complete.address.add(12):ptr(0x1f00)},ptr(0xa000),slot);
  const terminate=(code=0)=>{const h=attached.get(String(fact.terminate.address)),c={};h.onEnter.call(c,[ptr(0),ptr(code)]);h.onLeave.call(c);};
  return {context,events,calls,original,attached,detached,reverted,init,enterComplete,finish,set,terminate};
}
test('actual native agent defers only real successful parent then reports original completion',()=>{
  const f=fixture();assert.equal(f.init().native_ready,true);f.calls.length=0;
  const leave=f.enterComplete();f.finish();assert.equal(f.set(),1);assert.equal(f.original.length,0);
  leave();f.terminate();assert.deepEqual(f.calls.map(c=>c.event),['HELLO','DEFER','DONE']);
  assert.equal(f.calls.at(-1).fields.code,0);
});
test('normal SetActive calls, failed Finish and unrelated caller run original exactly once',()=>{
  const f=fixture();f.init();f.set();const leave=f.enterComplete();f.finish(0);f.set();f.finish(1,false);f.set();f.finish();f.set(1,false);leave();
  assert.equal(f.original.length,4);assert.equal(f.calls.some(c=>c.event==='DEFER'),false);
});
test('WAIT_BEGIN at initialization preserves installed future-cycle hooks',()=>{
  const f=fixture({wait:true});assert.equal(f.init().native_ready,true);const leave=f.enterComplete();f.finish();f.set();leave();
  assert.equal(f.original.length,1);assert.equal(f.calls.some(c=>c.event==='DEFER'),false);
});
test('durability transport rejection preserves original while native failure reports FAIL',()=>{
  const failed=fixture({wireChange:(event,_fields,reply)=>event==='DEFER'?{...reply,durable:false}:reply});failed.init();
  const leave=failed.enterComplete();failed.finish();failed.set();leave();assert.equal(failed.original.length,1);
  const active=fixture();active.init();const end=active.enterComplete();active.finish();active.set();end();active.terminate(52);
  assert.equal(active.calls.at(-1).event,'FAIL');assert.equal(active.calls.at(-1).fields.code,52);
});
test('missing signatures and process identity mismatch install no replacement',()=>{
  const absent=fixture({missing:true});assert.equal(absent.init().native_ready,false);assert.equal(absent.attached.size,0);
  const stale=fixture();assert.equal(stale.init({engine_start:'100'}).native_ready,false);assert.equal(stale.attached.size,0);
});
test('initialization failure after replacement reverts original and detaches all partial listeners',()=>{
  const f=fixture({failAfterReplace:true});assert.equal(f.init().native_ready,false);
  assert.equal(f.reverted.length,1);assert.equal(f.detached.length,3);
});
console.log(count+' actual native agent lifecycle host tests passed; Frida/HAL/broker are fixtures only');
