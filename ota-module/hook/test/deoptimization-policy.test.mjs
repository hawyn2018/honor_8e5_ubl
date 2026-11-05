import assert from 'node:assert/strict';
import {gzipSync, gunzipSync} from 'node:zlib';
import {deoptimizationPolicy, applyCriticalDeoptimization, createNativeRebootScope, decodeDeoptimizationMethods} from '../src/deoptimization-policy.js';
let passed=0;
function test(name,body){body();passed++;console.log('PASS '+name);}
function fixture(){
  const calls=[],warnings=[],method={returnType:{className:'void'},handle:{isNull:()=>false,toString:()=> 'method-1'}};
  const item={class_name:'Fixture',method_name:'reboot',parameter_types:['java.lang.String'],return_type:'void',is_static:false,dex_method:'LFixture;->reboot(Ljava/lang/String;)V'};
  const mapping={hooks:[{...item,role:'reboot_terminal'}],deoptimization_plan:{policy:deoptimizationPolicy,requires_native_barrier:true,full_inline_caller_proof:false,methods:[item],method_count:1,roots:[item.dex_method]}};
  const reflection={getName:()=> 'reboot',getModifiers:()=>0,getParameterTypes:()=>[{getName:()=> 'java.lang.String'}],getReturnType:()=>({getName:()=> 'void'})};
  const type={class:{getDeclaredMethods:()=>[reflection],getDeclaredConstructors:()=>[]},reboot:{overload:()=>method}};
  const Java={use:name=>name==='java.lang.reflect.Proxy'?{isProxyClass:()=>false}:type,deoptimizeMethod:h=>calls.push(h),deoptimizeEverything:()=>calls.push('global')};
  return {calls,warnings,item,mapping,method,reflection,type,Java,run:()=>applyCriticalDeoptimization({Java,mapping,nativeReady:true,warn:(...a)=>warnings.push(a),now:()=>1})};
}
test('verified native barrier and exact plan select only described methods',()=>{
  const f=fixture(),r=f.run();assert.equal(r.strategy,'selective_native_barrier');assert.equal(r.full_inline_caller_proof,false);
  assert.equal(r.method_count,1);assert.equal(r.native_barrier,true);assert.deepEqual(f.calls,[f.method.handle]);assert.equal(f.warnings.length,0);
});
test('legacy mapping explicitly falls back instead of claiming selective coverage',()=>{
  const f=fixture();delete f.mapping.deoptimization_plan;const r=f.run();assert.equal(r.strategy,'global_fallback');assert.match(r.fallback_reason,/missing/);assert.deepEqual(f.calls,['global']);assert.equal(f.warnings.length,1);
});
test('native capability absence preserves existing full coverage',()=>{
  const f=fixture(),r=applyCriticalDeoptimization({Java:f.Java,mapping:f.mapping,nativeReady:false,warn(){}});
  assert.equal(r.strategy,'global_fallback');assert.equal(r.native_barrier,false);assert.deepEqual(f.calls,['global']);
});
test('a missing root resolves no selective mutation and logs global fallback',()=>{
  const f=fixture();f.mapping.deoptimization_plan.roots.push('LOther;->reboot()V');assert.equal(f.run().strategy,'global_fallback');assert.deepEqual(f.calls,['global']);
});
test('all methods resolve before the first ART mutation',()=>{
  const f=fixture();f.mapping.deoptimization_plan.methods.push({...f.item,dex_method:'LOther;->reboot(Ljava/lang/String;)V',class_name:'Other'});f.mapping.deoptimization_plan.method_count++;
  const use=f.Java.use;f.Java.use=name=>{if(name==='Other')throw Error('missing ABI');return use(name);};
  assert.equal(f.run().strategy,'global_fallback');assert.deepEqual(f.calls,['global']);
});
test('plan metadata cannot omit a required hook by deleting it from its own roots',()=>{
  const f=fixture();f.mapping.hooks.push({...f.item,role:'payload_start',method_name:'apply',parameter_types:[]});
  assert.equal(f.run().strategy,'global_fallback');assert.deepEqual(f.calls,['global']);
});
test('duplicate handles deoptimize once, while duplicate descriptors reject',()=>{
  const f=fixture();f.mapping.deoptimization_plan.methods.push({...f.item,class_name:'Alias',dex_method:'LAlias;->reboot(Ljava/lang/String;)V'});f.mapping.deoptimization_plan.method_count++;
  assert.equal(f.run().method_count,1);assert.equal(f.calls.length,1);
  const g=fixture();g.mapping.deoptimization_plan.methods.push({...g.item});g.mapping.deoptimization_plan.method_count++;
  assert.equal(g.run().strategy,'global_fallback');assert.deepEqual(g.calls,['global']);
});
test('selective failure has explicit partial count and restores old full coverage',()=>{
  const f=fixture();f.Java.deoptimizeMethod=()=>{throw Error('ART rejected selective');};const r=f.run();
  assert.equal(r.strategy,'global_fallback');assert.match(r.fallback_reason,/ART rejected/);assert.deepEqual(f.calls,['global']);
});
test('native abstract or dynamic-proxy runtime methods fall back before the ART selective call',()=>{
  for(const flags of [0x100,0x400]){const f=fixture();f.reflection.getModifiers=()=>flags;assert.equal(f.run().strategy,'global_fallback');assert.deepEqual(f.calls,['global']);}
  const f=fixture(),use=f.Java.use;f.Java.use=name=>name==='java.lang.reflect.Proxy'?{isProxyClass:()=>true}:use(name);
  assert.equal(f.run().strategy,'global_fallback');assert.deepEqual(f.calls,['global']);
});
test('UTF8 gzip methods decode once and close the owned reader; plain fixture plans stay compatible',()=>{
  const values=[{label:'中文 UTF8',method_name:'m'}],text=JSON.stringify(values),encoded=gzipSync(Buffer.from(text,'utf8')).toString('base64');let closes=0,decodes=0;
  const Java={use(name){
    if(name==='android.util.Base64')return {decode:{overload:()=>({call(_type,s,flags){decodes++;assert.equal(flags,0);return Buffer.from(s,'base64');}})}};
    if(name==='java.io.ByteArrayInputStream')return {$new:b=>b};
    if(name==='java.util.zip.GZIPInputStream')return {$new:b=>({text:gunzipSync(b).toString('utf8'),close:()=>closes++})};
    if(name==='java.io.InputStreamReader')return {$new:(s,charset)=>{assert.equal(charset,'UTF-8');return s;}};
    if(name==='java.io.BufferedReader')return {$new:s=>{let done=false;return {readLine(){if(done)return null;done=true;return s.text;},close:()=>closes++};}};
    throw Error(name);
  }};
  assert.deepEqual(decodeDeoptimizationMethods(Java,{encoding:'gzip-base64-json-v1',methods_gzip_base64:encoded}),values);assert.equal(decodes,1);assert.equal(closes,1);
  assert.strictEqual(decodeDeoptimizationMethods({}, {methods:values}),values);
});
test('unavailable full fallback rejects initialization',()=>{
  const f=fixture();delete f.mapping.deoptimization_plan;delete f.Java.deoptimizeEverything;assert.throws(()=>f.run(),/fallback deoptimization unavailable/);
});
test('a prepared batch is used once and preserves the complete method plan',()=>{
  const f=fixture();let input;
  f.Java.deoptimizeMethods=handles=>{input=handles;return {count:handles.length,batches:1};};
  const r=f.run();assert.equal(r.strategy,'selective_native_barrier');assert.deepEqual(input,[f.method.handle]);
  assert.equal(r.batch_count,1);assert.deepEqual(f.calls,[]);assert.equal(r.method_count,1);
});
test('a failed batch explicitly falls back without claiming completed selective coverage',()=>{
  const f=fixture();f.Java.deoptimizeMethods=()=>{throw Error('native batch failed');};const r=f.run();
  assert.equal(r.strategy,'global_fallback');assert.match(r.fallback_reason,/native batch failed/);assert.deepEqual(f.calls,['global']);
});
test('cached mapping mismatch may request clean rediscovery before any mutation',()=>{
  const f=fixture();f.mapping.deoptimization_plan.roots.push('LMissing;->method()V');let reason;
  assert.throws(()=>applyCriticalDeoptimization({Java:f.Java,mapping:f.mapping,nativeReady:true,warn(){},onMappingInvalid(value){reason=value;throw Error('rediscovery requested');}}),/rediscovery requested/);
  assert.match(reason,/omits a critical root/);assert.deepEqual(f.calls,[]);
});
test('ART capability or mutation failures do not invalidate a correct location cache',()=>{
  for(const phase of ['capability','mutation']){const f=fixture();let invalid=0;
    if(phase==='capability')delete f.Java.deoptimizeMethod;else f.Java.deoptimizeMethod=()=>{throw Error('ART failure');};
    const r=applyCriticalDeoptimization({Java:f.Java,mapping:f.mapping,nativeReady:true,warn(){},onMappingInvalid(){invalid++;}});
    assert.equal(r.strategy,'global_fallback');assert.equal(invalid,0);}
});
test('one exact synchronous native transaction only; other threads and later calls see no scope',()=>{
  let thread=1;const s=createNativeRebootScope(()=>thread);assert.equal(s.take(),false);
  s.run(()=>{thread=2;assert.equal(s.take(),false);thread=1;assert.equal(s.take(),true);assert.equal(s.take(),false);});
  assert.equal(s.take(),false);
});
test('native scope closes on exception and nested calls restore only their caller',()=>{
  const s=createNativeRebootScope(()=>1),e=Error('original');assert.throws(()=>s.run(()=>{throw e;}),x=>x===e);assert.equal(s.take(),false);
  s.run(()=>{s.run(()=>{assert.equal(s.take(),true);});assert.equal(s.take(),true);});assert.equal(s.take(),false);
});
console.log(JSON.stringify({passed:true,tests:passed,deviceOperations:false,ARTExecuted:false}));
