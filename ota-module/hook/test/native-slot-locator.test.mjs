import assert from 'node:assert/strict';
import {locateNativeSlots,inside,SLOT_SYMBOLS} from '../src/native-slot-locator.js';
class Pointer {constructor(value){this.value=BigInt(value);}add(n){return new Pointer(this.value+BigInt(n));}toString(){return '0x'+this.value.toString(16);}}
const ptr=n=>new Pointer(n);
let count=0;
function test(name,body){body();count++;console.log('PASS '+name);}
function fixture(delta=0n){
  const symbols=Object.entries(SLOT_SYMBOLS).map(([role,name],i)=>({name,type:'function',address:ptr(0x1000n+delta+BigInt(i)*0x100n),size:16,role}));
  const instructions=new Map();
  const module={base:ptr(0x1000n+delta),size:0x1000,enumerateSymbols:()=>symbols};
  const parse=address=>instructions.get(String(address))||{mnemonic:'stp',opStr:'x29, x30, [sp, #-0x20]!',size:4};
  const locate=()=>locateNativeSlots(module,parse,()=>true);
  return {symbols,instructions,module,parse,locate};
}
test('function names and sizes work after relocation with no specimen offsets',()=>{
  for(const delta of [0n,0x7000000000n,0x12345000n]){const f=fixture(delta),r=f.locate();assert.equal(String(r.finish.address),String(f.symbols[1].address));
    assert.equal(inside(r.complete.address.add(15),r.complete),true);assert.equal(inside(r.complete.address.add(16),r.complete),false);}
});
test('same-address aliases merge while zero-sized aliases cannot create bodies',()=>{
  const f=fixture();f.symbols.push({...f.symbols[0],name:f.symbols[0].name+'.cfi'});
  f.symbols.push({...f.symbols[1],name:f.symbols[1].name+'.cfi',size:0});assert.ok(f.locate().complete);
});
test('complete CFI entry thunk resolves only to a sized same-signature body',()=>{
  const f=fixture(),body=f.symbols[0];body.name+='.cfi';const entry={...body,name:SLOT_SYMBOLS.complete,address:ptr(0x1a00),size:8};
  f.symbols.push(entry);f.instructions.set(String(entry.address),{mnemonic:'bti',opStr:'c',size:4});
  f.instructions.set(String(entry.address.add(4)),{mnemonic:'b',opStr:'#'+String(body.address),size:4});
  assert.equal(String(f.locate().complete.address),String(body.address));
});
test('unknown thunk target, multiple bodies or invalid size disable optional locator',()=>{
  for(const mode of ['target','ambiguous','zero','conflict']){const f=fixture();
    if(mode==='target'){f.symbols[0].size=4;f.instructions.set(String(f.symbols[0].address),{mnemonic:'b',opStr:'#0x1f00',size:4});}
    if(mode==='ambiguous')f.symbols.push({...f.symbols[0],name:f.symbols[0].name+'.cfi',address:ptr(0x1f00)});
    if(mode==='zero')f.symbols[0].size=0;
    if(mode==='conflict')f.symbols.push({...f.symbols[0],name:f.symbols[0].name+'.cfi',size:20});
    assert.throws(f.locate,undefined,mode);
  }
});
test('similar methods, stubs and thunks with another ABI are not selected',()=>{
  const f=fixture();f.symbols.push({...f.symbols[2],name:'_ZN7android8snapshot10DeviceInfo17SetActiveBootSlotEj',address:ptr(0x1e00)});
  assert.equal(f.locate().setActive.name,SLOT_SYMBOLS.setActive);
  f.symbols[2].name=f.symbols[2].name.replace(/Ej$/,'Ei');assert.throws(f.locate,/unavailable/);
});
console.log(count+' native locator host tests passed; no module loaded or native method invoked');
