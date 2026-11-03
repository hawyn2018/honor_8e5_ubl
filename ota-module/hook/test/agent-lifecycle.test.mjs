import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as policy from '../src/policy.js';
import * as versions from '../src/version-policy.js';
import {installCacheVersionQuery} from '../src/cache-query.js';

// Exercise the actual agent's binding/closure/lifecycle code with a Java ABI
// fixture. No ART, broker socket, updater, payload, reboot or slot API is run.
const source = fs.readFileSync(new URL('../src/agent.js', import.meta.url), 'utf8').replace(/^import .*;\r?\n/gm, '');
const hook = (role, name, parameters=[], returns='void', extra={}) => ({role,class_name:'Fixture',method_name:name,
  parameter_types:parameters,return_type:returns,...extra});
const critical = () => [hook('reboot_gate','reboot',['java.lang.String'],'void',{mode_arg:0,manual_values:['manual'],automatic_values:['auto']}),
  hook('countdown','countdown'),hook('payload_start','apply',['java.lang.String']),hook('payload_complete','complete',['int'])];

function fixture({hooks=critical(), failedMethods=[], replies=[{ok:true,state:0,decision:'DENY'}],logFails=false}={}) {
  const events=[], timers=[], requests=[], calls=[], logs=[]; const methods=new Map();
  function method(name) {
    if (!methods.has(name)) methods.set(name,{call(receiver,...args){calls.push({name,args});return name==='version'?'native-result':undefined;}});
    return methods.get(name);
  }
  const app={getPackageName:()=> 'com.hihonor.ouc',getClassLoader:()=>({}),
    getFilesDir:()=>({getAbsolutePath:()=>'/data/fixture/com.hihonor.ouc/files'}),startActivity:()=>{}};
  const reader={read:{overload:()=>({call(_receiver,chars){const text='com.hihonor.ouc\0';for(let i=0;i<text.length;i++)chars[i]=text[i];return text.length;}})},close(){}};
  const types={};
  for(const h of hooks) {
    if(!types[h.class_name])types[h.class_name]={};
    types[h.class_name][h.method_name]={overload:()=>{if(failedMethods.includes(h.method_name))throw new Error('missing optional ABI');return method(h.method_name);}};
  }
  const Java={perform:fn=>fn(),scheduleOnMainThread:fn=>fn(),array:(_type,values)=>values,classFactory:{},cast:()=>{throw new Error('version getter failed');},
    use(name){
      if(name==='android.app.ActivityThread')return {currentApplication:()=>app};
      if(name==='java.io.FileInputStream')return {$new:()=>({})};
      if(name==='java.io.InputStreamReader')return {$new:()=>reader};
      if(name==='java.lang.Thread')return {currentThread:()=>({getId:()=>7})};
      if(name==='android.util.Log'){
        const method={overload:()=>({call(_type,tag,message){if(logFails)throw new Error('logger unavailable');logs.push({tag,message});}})};
        return {w:method,i:method};
      }
      if(name==='FieldOwner')return {class:{getDeclaredField:()=>({setAccessible(){},get:()=>({})})}};
      if(name==='Info')return {};
      if(name==='android.content.Intent')return {$new:()=>({setClassName(){},putExtra:{overload:()=>({call(){}})},addFlags(){}})};
      if(types[name])return types[name];
      throw new Error('unavailable '+name);
    }};
  const context=vm.createContext({...policy,...versions,Java,installCacheVersionQuery,installRebootLabels:()=>({aroundOriginal:fn=>fn()}),rpc:{exports:{}},send:e=>events.push(JSON.parse(JSON.stringify(e))),
    setTimeout:fn=>{timers.push(fn);return timers.length;},clearTimeout(){},console});
  vm.runInContext(source,context);
  context.brokerRequest=command=>{requests.push(command);const next=replies.length>1?replies.shift():replies[0];if(next instanceof Error)throw next;return next;};
  vm.runInContext('marker = command => brokerRequest(command)',context);
  const mapping={schema:1,package:'com.hihonor.ouc',discovery_complete:true,apk_sha256:'fixture',hooks};
  const init=parameters=>context.rpc.exports.init('late',{mapping,...parameters});
  return {context,events,timers,requests,calls,logs,methods,init,inspect:()=>context.rpc.exports.inspect(),invoke:(name,...args)=>method(name).implementation.call({},...args)};
}

{
  const f=fixture({hooks:[...critical(),hook('full_package','optional',[],'java.lang.String')],failedMethods:['optional']});
  const state=await f.init();
  assert.equal(state.hook_ready,true);assert.equal(state.critical_ready,true);assert.equal(state.broker_ready,true);
  assert.equal(f.events.filter(e=>e.event==='error').length,0);assert.ok(f.events.some(e=>e.event==='warning'&&e.phase==='optional_hook'));
  assert.deepEqual(f.requests,['STATE']);
}
{
  const f=fixture({replies:[new Error('ENOENT'),{ok:true,state:0,decision:'DENY'}]});
  const pending=f.init();assert.strictEqual(f.init(),pending);
  assert.equal(f.inspect().critical_ready,true);assert.equal(f.inspect().broker_ready,false);assert.equal(f.inspect().hook_ready,false);
  assert.equal(f.events.filter(e=>e.event==='error').length,0);assert.equal(f.events.filter(e=>e.event==='ready').length,0);
  assert.equal(f.events.filter(e=>e.event==='critical_ready').length,1);assert.equal(f.timers.length,1);
  f.invoke('apply','native-payload');assert.equal(f.calls.filter(c=>c.name==='apply').length,1);
  f.timers.shift()();assert.equal((await pending).hook_ready,true);assert.equal(f.events.filter(e=>e.event==='ready').length,1);
}
{
  const f=fixture({failedMethods:['reboot']});await assert.rejects(f.init(),/required discovered entry/);
  assert.equal(f.inspect().critical_ready,false);assert.ok(f.events.some(e=>e.event==='error'&&e.fatal===true));assert.deepEqual(f.requests,[]);
}
{
  const version=hook('version_response','version',['int','java.lang.String'],'void',{response_arg:1,version_states:{available:0,unavailable:1},
    native_no_update_callback:'native',current_version_source:{field_owner:'FieldOwner',field_name:'info',field_type:'Info',getter:'joint'}});
  const f=fixture({hooks:[...critical(),version]});await f.init();const raw='{"status":0,"result":{}}';
  assert.equal(f.invoke('version',0,raw),'native-result');
  assert.deepEqual(f.calls.filter(c=>c.name==='version'),[{name:'version',args:[0,raw]}]);
  assert.ok(f.events.some(e=>e.event==='warning'&&e.phase==='version_response'));assert.equal(f.events.some(e=>e.event==='error'),false);
  assert.ok(f.logs.some(e=>e.tag==='hnubl-ota'&&JSON.parse(e.message).phase==='version_response'));
  const noLog=fixture({hooks:[...critical(),version],logFails:true});await noLog.init();
  assert.equal(noLog.invoke('version',0,raw),'native-result');assert.equal(noLog.calls.filter(c=>c.name==='version').length,1);
  assert.equal(noLog.events.some(e=>e.event==='error'),false);
  // An eternalized process can have no live send receiver. logcat is enough;
  // neither telemetry nor warning transport may interrupt the native callback.
  f.context.send=()=>{throw new Error('injector detached');};
  assert.equal(f.invoke('version',0,raw),'native-result');assert.equal(f.calls.filter(c=>c.name==='version').length,2);
}
{
  const f=fixture();await f.init();f.invoke('complete',0);assert.deepEqual(f.requests,['STATE']);
  f.invoke('countdown');assert.equal(f.calls.filter(c=>c.name==='countdown').length,0);
  f.invoke('reboot','auto');assert.deepEqual(f.requests,['STATE']);assert.equal(f.calls.filter(c=>c.name==='reboot').length,0);
  f.invoke('reboot','manual');assert.deepEqual(f.requests,['STATE','CONSUME']);assert.equal(f.calls.filter(c=>c.name==='reboot').length,0);
}
{
  const f=fixture();await f.init();f.invoke('apply','payload');f.invoke('complete',0);
  assert.deepEqual(f.requests,['STATE','BEGIN','RESET']);
  assert.equal(f.calls.filter(c=>c.name==='apply').length,1);assert.equal(f.calls.filter(c=>c.name==='complete').length,1);
  assert.equal(f.inspect().cycle_reset_pending,false);
}
{
  const inner=hook('reboot_gate','inner',['java.lang.String'],'void',{mode_arg:0,manual_values:['manual'],automatic_values:['auto']});
  const f=fixture({hooks:[...critical(),inner],replies:[{ok:true,state:0,decision:'DENY'},{ok:true,state:0,decision:'ALLOW'}]});await f.init();
  const outer=f.methods.get('reboot'),nativeOuter=outer.call;
  outer.call=(receiver,...args)=>{nativeOuter(receiver,...args);f.invoke('inner','manual');};
  f.invoke('reboot','manual');assert.equal(f.requests.filter(c=>c==='CONSUME').length,1);
  assert.equal(f.calls.filter(c=>c.name==='reboot').length,1);assert.equal(f.calls.filter(c=>c.name==='inner').length,1);
  f.invoke('inner','manual');assert.equal(f.requests.filter(c=>c==='CONSUME').length,2);
}
{
  const f=fixture({replies:[{ok:true,state:0,decision:'DENY'},{ok:true,state:0,decision:'ALLOW'}]});await f.init();
  f.context.send=()=>{throw new Error('injector detached');};
  f.invoke('reboot','manual');assert.equal(f.calls.filter(c=>c.name==='reboot').length,1);assert.deepEqual(f.requests,['STATE','CONSUME']);
  // A completed original path removes its thread allowance. A second click must consume again.
  f.invoke('reboot','manual');assert.equal(f.requests.filter(c=>c==='CONSUME').length,2);
}
{
  const f=fixture({replies:[{ok:true,state:0,decision:'DENY'},new Error('reset unavailable')]});await f.init();
  f.invoke('apply','payload');assert.equal(f.inspect().cycle_reset_pending,true);assert.equal(f.calls.filter(c=>c.name==='apply').length,1);
  f.invoke('reboot','manual');assert.equal(f.requests.includes('CONSUME'),true);assert.equal(f.calls.filter(c=>c.name==='reboot').length,0);
  assert.deepEqual(f.requests,['STATE','BEGIN','CONSUME']);
  assert.ok(f.events.some(e=>e.event==='warning'&&e.phase==='cycle_reset'));assert.equal(f.events.some(e=>e.event==='error'),false);
}
{
  const f=fixture({replies:[{ok:true,state:0,decision:'DENY'},new Error('old reset unavailable'),
    {ok:true,state:0,decision:'ALLOW'},{ok:true,state:0,decision:'DENY'}]});await f.init();
  f.invoke('apply','payload');assert.equal(f.inspect().cycle_reset_pending,true);
  // hnubl subsequently wrote 1. The real CONSUME permits the original manual
  // path once; an old process-local reset error must not overwrite that token.
  f.context.send=()=>{throw new Error('eternalized host absent');};
  f.invoke('reboot','manual');assert.equal(f.calls.filter(c=>c.name==='reboot').length,1);
  assert.equal(f.inspect().cycle_reset_pending,false);assert.deepEqual(f.requests,['STATE','BEGIN','CONSUME']);
  assert.ok(f.logs.some(e=>JSON.parse(e.message).event==='original_reboot_entry_allowed'));
  assert.ok(f.logs.some(e=>JSON.parse(e.message).event==='reboot_authorization_result'&&JSON.parse(e.message).decision==='ALLOW'));
  f.invoke('reboot','manual');assert.equal(f.calls.filter(c=>c.name==='reboot').length,1);
  assert.deepEqual(f.requests,['STATE','BEGIN','CONSUME','CONSUME']);
  assert.ok(f.logs.some(e=>JSON.parse(e.message).event==='redirect_to_hnubl'));
}
{
  const f=fixture();await assert.rejects(f.init({ota_socket:'/unrelated/socket'}),/endpoint does not match/);
  assert.equal(f.requests.length,0);assert.equal(f.inspect().hook_ready,false);
}
{
  const f=fixture();await f.init();f.context.send=()=>{throw new Error('eternalized host absent');};
  const response={ok:true,state:0,decision:'ALLOW',warnings:['No native deferred activation was recorded; using the updater original slot flow']};
  assert.strictEqual(f.context.reportBrokerWarnings(response),response);assert.equal(policy.consumeAllows(response),true);
  assert.ok(f.logs.some(e=>JSON.parse(e.message).phase==='native_slot'));
  assert.equal(f.events.some(e=>e.event==='error'),false);assert.equal(f.inspect().hook_ready,true);
}
{
  for (const failAfter of [false,true]) {
    const f=fixture({replies:[{ok:true,state:0,decision:'DENY'},{ok:true,state:0,decision:'ALLOW'}]});
    f.context.installRebootLabels=()=>({aroundOriginal(fn){if(failAfter)fn();throw new Error('optional label failure');}});
    await f.init();f.invoke('reboot','manual');
    assert.equal(f.calls.filter(c=>c.name==='reboot').length,1);
    assert.ok(f.events.some(e=>e.event==='warning'&&e.phase==='reboot_labels'));
  }
  const f=fixture({replies:[{ok:true,state:0,decision:'DENY'},{ok:true,state:0,decision:'ALLOW'}]});await f.init();
  f.methods.get('reboot').call=()=>{throw new Error('native handler failure');};
  assert.throws(()=>f.invoke('reboot','manual'),/native handler failure/);
}
console.log('Agent optional warnings, pending STATE, critical lifecycle, native callbacks and closed reboot gate checks passed (host Java fixtures only)');

// Cache visibility is optional; a future updater with a different binding must
// retain the existing update/reboot protection rather than exit its process.
{
  const f=fixture({hooks:[...critical(),hook('version_cache_query','cache',[],'android.database.Cursor')]});
  const state=await f.init();
  assert.equal(state.hook_ready,true);
  assert.ok(f.events.some(e=>e.event==='warning'&&e.phase==='optional_hook'));
  assert.equal(f.events.filter(e=>e.event==='error').length,0);
}
{
  const f=fixture();await f.init();
  f.context.telemetry({event:'version_filter',decision:'latest',reason:'same_version'});
  f.context.telemetry({event:'cache_version_filter',decision:'latest',hidden_count:3});
  assert.ok(f.logs.some(e=>JSON.parse(e.message).event==='version_filter'));
  assert.ok(f.logs.some(e=>JSON.parse(e.message).event==='cache_version_filter'));
  const g=fixture({logFails:true});await g.init();
  assert.doesNotThrow(()=>g.context.telemetry({event:'cache_version_filter',decision:'preserve'}));
}
