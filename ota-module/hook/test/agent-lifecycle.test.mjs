import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import * as policy from '../src/policy.js';
import * as versions from '../src/version-policy.js';
import {createRebootContext} from '../src/reboot-context.js';
import {installCacheVersionQuery} from '../src/cache-query.js';

// Exercise the actual agent's binding/closure/lifecycle code with a Java ABI
// fixture. No ART, broker socket, updater, payload, reboot or slot API is run.
const source = fs.readFileSync(new URL('../src/agent.js', import.meta.url), 'utf8').replace(/^import .*;\r?\n/gm, '');
const hook = (role, name, parameters=[], returns='void', extra={}) => ({role,class_name:'Fixture',method_name:name,
  parameter_types:parameters,return_type:returns,...extra});
const critical = () => [hook('reboot_terminal','reboot',['java.lang.String'],'void',{mode_arg:0}),hook('reboot_context','reason',['java.lang.String'],'void',{mode_arg:0}),hook('reboot_gate','frontier',['java.lang.String'],'void',{mode_arg:0,manual_values:['manual'],automatic_values:['auto']}),
  hook('countdown','countdown'),hook('payload_start','apply',['java.lang.String']),hook('payload_complete','complete',['int'])];

function fixture({hooks=critical(), failedMethods=[], replies=[{ok:true,state:0,decision:'DENY'}],logFails=false,
  queued=false,state={ok:true,state:1},lifecycle=false,lifecycleBindFails=false,engineReady=false,engineStart=true,
  traceFails=false,noLooper=false,postFails=false,threadFailAt=0,deoptFails=false}={}) {
  const events=[], timers=[], requests=[], calls=[], logs=[], traces=[], workers=[], main=[], stages=[]; const methods=new Map();
  let lane='ui',rebootJob=false,manualAction=null,deopts=0,identity=0,references=0,starts=0,stops=0,liveRetains=0,threadStarts=0;
  const run=(where,body)=>{const previous=lane;lane=where;try{return body();}finally{lane=previous;}};
  const enqueue=(list,where,body)=>{if(queued)list.push(()=>run(where,body));else run(where,body);};
  const looper={id:'original-looper'};
  function method(name) {
    if (!methods.has(name)) methods.set(name,{call(receiver,...args){calls.push({name,args});stages.push({native:name,lane,receiver});return name==='version'?'native-result':undefined;}});
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
  const engineSpec={class_name:'EngineFixture',factory:{method_name:'get',parameter_types:[],return_type:'EngineFixture',is_static:true},
    start:{method_name:'open',parameter_types:[],return_type:'boolean',is_static:false},stop:{method_name:'close',parameter_types:[],return_type:'void',is_static:false}};
  const vendor={id:'native-vendor-instance'},engine={
    get:{overload:()=>({call:()=>vendor})},
    open:{overload:()=>({call(instance){assert.equal(instance.id,vendor.id);assert.equal(lane,'worker');starts++;
      if(engineStart instanceof Error)throw engineStart;if(engineStart){references++;engineReady=true;}return engineStart;}})},
    close:{overload:()=>({call(instance){assert.equal(instance.id,vendor.id);assert.equal(lane,'worker');stops++;references--;assert(references>=0);}})}
  };
  const Java={deoptimizeEverything(){deopts++;if(deoptFails)throw new Error('deopt unavailable');},perform:fn=>fn(),scheduleOnMainThread:fn=>enqueue(main,'ui',fn),array:(_type,values)=>values,classFactory:{},cast:()=>{throw new Error('version getter failed');},
    retain(value){if(value==null)return null;liveRetains++;let disposed=false;const copy=Object.create(value);copy.$dispose=()=>{assert(!disposed,'one retained-wrapper release');disposed=true;liveRetains--;};return copy;},
    registerClass(spec){assert.equal(spec.implements.length,1);assert.equal(spec.implements[0].name,'Runnable');
      assert.equal(spec.fields.hnublJob,'java.lang.String');return {$new(){return {hnublJob:{value:null},run(){spec.methods.run.call(this);},$dispose(){}};}};},
    use(name){
      if(name==='android.app.ActivityThread')return {currentApplication:()=>app};
      if(name==='java.io.FileInputStream')return {$new:()=>({})};
      if(name==='java.io.InputStreamReader')return {$new:()=>reader};
      if(name==='java.lang.Thread')return {currentThread:()=>({getId:()=>lane==='ui'?7:41}),$new(runnable,label){assert.equal(label,'hnubl-ota-prepare');return {
        start(){if(++threadStarts===threadFailAt)throw new Error('Thread scheduling unavailable');enqueue(workers,'worker',()=>{const previous=rebootJob;rebootJob=true;try{return runnable.run();}finally{rebootJob=previous;}});},$dispose(){}};}};
      if(name==='java.lang.Runnable')return {name:'Runnable'};
      if(name==='android.os.Looper')return {myLooper:()=>noLooper||lane==='worker'?null:looper};
      if(name==='android.os.Handler')return {$new(value){assert.equal(value.id,looper.id);return {post:{overload:()=>({call(_handler,runnable){
        if(postFails)return false;enqueue(main,'ui',()=>runnable.run());return true;}})},$dispose(){}};}};
      if(name==='java.lang.IllegalStateException')return {$new:message=>new Error(message)};
      if(name==='java.lang.ref.WeakReference')return {$new(value){return {get:()=>({$isSameObject:other=>value._identity===other._identity}),$dispose(){}};}};
      if(name==='java.lang.System')return {identityHashCode(value){if(!value._identity)value._identity=++identity;return value._identity;}};
      if(name==='RunFixture')return {$init:{overload:()=>method('ctor')},run:{overload:()=>method('runContinuation')}};
      if(name==='EngineFixture'){if(lifecycleBindFails)throw new Error('optional original lifecycle ABI missing');return engine;}
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
  const context=vm.createContext({...policy,...versions,createRebootContext,Java,installCacheVersionQuery,installRebootLabels:options=>{manualAction=options.manualAction;return {aroundOriginal:fn=>fn()};},rpc:{exports:{}},send:e=>events.push(JSON.parse(JSON.stringify(e))),
    setTimeout:fn=>{timers.push(()=>run('worker',fn));return timers.length;},clearTimeout(){},console});
  vm.runInContext(source,context);
  context.brokerRequest=command=>{
    stages.push({command,lane});
    if(command.startsWith('TRACE ')){traces.push(command);if(traceFails)throw new Error('trace unavailable');return {ok:true};}
    requests.push(command);
    if(command==='STATE'&&rebootJob){const result=typeof state==='function'?state():state;if(result instanceof Error)throw result;return result;}
    const next=replies.length>1?replies.shift():replies[0];if(next instanceof Error)throw next;return next;};
  vm.runInContext('marker = command => brokerRequest(command)',context);
  const mapping={schema:1,package:'com.hihonor.ouc',discovery_complete:true,apk_sha256:'fixture',hooks,reboot_continuations:[{class_name:'RunFixture',constructors:[{parameter_types:['android.content.Context','java.lang.String']}],callback:{method_name:'run',parameter_types:[],return_type:'void',is_static:false}}],...(lifecycle?{engine_lifecycle:engineSpec}:{})};
  const init=parameters=>context.rpc.exports.init('late',{mapping,...parameters});
  return {context,events,timers,requests,calls,logs,methods,traces,workers,main,stages,init,inspect:()=>context.rpc.exports.inspect(),
    flush(){while(workers.length||main.length){if(workers.length)workers.shift()();else main.shift()();}},
    get deopts(){return deopts;},
    continuation(reason='ota'){const receiver={_identity:++identity};method('ctor').implementation.call(receiver,app,reason);enqueue(workers,'worker',()=>method('runContinuation').implementation.call(receiver));return receiver;},
    run(receiver){enqueue(workers,'worker',()=>method('runContinuation').implementation.call(receiver));},
    raw:(name,...args)=>method(name).implementation.call({},...args),
    get starts(){return starts;},get stops(){return stops;},get references(){return references;},get retained(){return liveRetains;},
    invoke:(name,...args)=>{const body=()=>method(name).implementation.call({},...args);return name==='reboot'&&manualAction?manualAction(body):body();}};
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
  f.invoke('reboot','manual');assert.deepEqual(f.requests,['STATE','STATE','CONSUME']);assert.equal(f.calls.filter(c=>c.name==='reboot').length,0);
}
{
  const f=fixture();await f.init();f.invoke('apply','payload');f.invoke('complete',0);
  assert.deepEqual(f.requests,['STATE','BEGIN','RESET']);
  assert.equal(f.calls.filter(c=>c.name==='apply').length,1);assert.equal(f.calls.filter(c=>c.name==='complete').length,1);
  assert.equal(f.inspect().cycle_reset_pending,false);
}

{
  const f=fixture({replies:[{ok:true,state:0,decision:'DENY'},{ok:true,state:0,decision:'ALLOW'}]});await f.init();
  f.context.send=()=>{throw new Error('injector detached');};
  f.invoke('reboot','manual');assert.equal(f.calls.filter(c=>c.name==='reboot').length,1);assert.deepEqual(f.requests,['STATE','STATE','CONSUME']);
  // A completed original path removes its thread allowance. A second click must consume again.
  f.invoke('reboot','manual');assert.equal(f.requests.filter(c=>c==='CONSUME').length,2);
}
{
  const f=fixture({replies:[{ok:true,state:0,decision:'DENY'},new Error('reset unavailable')]});await f.init();
  assert.throws(()=>f.invoke('apply','payload'),/clearing the previous OTA permission failed/);assert.equal(f.inspect().cycle_reset_pending,true);assert.equal(f.calls.filter(c=>c.name==='apply').length,0);
  f.invoke('reboot','manual');assert.equal(f.requests.includes('CONSUME'),true);assert.equal(f.calls.filter(c=>c.name==='reboot').length,0);
  assert.deepEqual(f.requests,['STATE','BEGIN','STATE','CONSUME']);
  assert.ok(f.events.some(e=>e.event==='warning'&&e.phase==='cycle_reset'));assert.equal(f.events.some(e=>e.event==='error'),false);
}
{
  const f=fixture({replies:[{ok:true,state:0,decision:'DENY'},new Error('old reset unavailable'),
    {ok:true,state:0,decision:'ALLOW'},{ok:true,state:0,decision:'DENY'}]});await f.init();
  assert.throws(()=>f.invoke('apply','payload'),/clearing the previous OTA permission failed/);assert.equal(f.inspect().cycle_reset_pending,true);
  // hnubl subsequently wrote 1. The real CONSUME permits the original manual
  // path once; an old process-local reset error must not overwrite that token.
  f.context.send=()=>{throw new Error('eternalized host absent');};
  f.invoke('reboot','manual');assert.equal(f.calls.filter(c=>c.name==='reboot').length,1);
  assert.equal(f.inspect().cycle_reset_pending,false);assert.deepEqual(f.requests,['STATE','BEGIN','STATE','CONSUME']);
  assert.ok(f.logs.some(e=>JSON.parse(e.message).event==='original_reboot_entry_allowed'));
  assert.ok(f.logs.some(e=>JSON.parse(e.message).event==='reboot_authorization_result'&&JSON.parse(e.message).decision==='ALLOW'));
  f.invoke('reboot','manual');assert.equal(f.calls.filter(c=>c.name==='reboot').length,1);
  assert.deepEqual(f.requests,['STATE','BEGIN','STATE','CONSUME','STATE','CONSUME']);
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
    const install=f.context.installRebootLabels;f.context.installRebootLabels=options=>{install(options);return {aroundOriginal(fn){if(failAfter)fn();throw new Error('optional label failure');}};};
    await f.init();f.invoke('reboot','manual');
    assert.equal(f.calls.filter(c=>c.name==='reboot').length,1);
    assert.ok(f.events.some(e=>e.event==='warning'&&e.phase==='reboot_labels'));
  }
  const f=fixture({replies:[{ok:true,state:0,decision:'DENY'},{ok:true,state:0,decision:'ALLOW'}]});await f.init();
  let originalFailures=0;
  f.methods.get('reboot').call=()=>{originalFailures++;throw new Error('native handler failure');};
  assert.doesNotThrow(()=>f.invoke('reboot','manual'));
  assert.equal(originalFailures,1);assert(f.events.some(e=>e.event==='original_reboot_entry_failed'));
  assert(f.traces.some(e=>e.startsWith('TRACE original_failed ')));assert.equal(f.retained,0);
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

const grantReplies=()=>[{ok:true,state:0,decision:'DENY'},{ok:true,authorized:true,state:0,decision:'ALLOW'}];
let lifecycleCases=0;
async function lifecycleCase(name,body){await body();lifecycleCases++;console.log('PASS '+name);}
function noUiProtocol(f){for(const s of f.stages.filter(x=>x.command&&x.command!=='STATE'))assert.equal(s.lane,'worker',s.command+' ran on UI');
  assert.equal(f.requests.some(x=>x==='STATUS'||x==='PREPARE'),false,'phase/preflight commands must not participate in file permission');}

await lifecycleCase('manual terminal returns before queued work and deduplicates repeated clicks',async()=>{
  const f=fixture({queued:true,replies:grantReplies()});await f.init();
  f.invoke('reboot','manual');f.invoke('reboot','manual');assert.equal(f.calls.length,0);assert.equal(f.workers.length,1);
  assert.equal(f.inspect().reboot_preparing,true);f.flush();
  assert.deepEqual(f.requests,['STATE','STATE','CONSUME']);assert.equal(f.calls.filter(x=>x.name==='reboot').length,1);
  assert.equal(f.inspect().reboot_preparing,false);assert.equal(f.retained,0);noUiProtocol(f);
});

await lifecycleCase('marker zero redirects without starting engine or spending permission',async()=>{
  const f=fixture({queued:true,lifecycle:true,state:{ok:true,state:0},replies:grantReplies()});await f.init();
  f.invoke('reboot','manual');f.flush();assert.deepEqual(f.requests,['STATE','STATE']);
  assert.equal(f.starts,0);assert.equal(f.stops,0);assert.equal(f.calls.length,0);assert.equal(f.retained,0);noUiProtocol(f);
});

await lifecycleCase('original lifecycle is best effort preparation before one file CONSUME',async()=>{
  const f=fixture({queued:true,lifecycle:true,replies:grantReplies()});await f.init();f.invoke('reboot','manual');f.flush();
  assert.deepEqual(f.requests,['STATE','STATE','CONSUME']);assert.equal(f.starts,1);assert.equal(f.stops,1);assert.equal(f.references,0);
  assert.equal(f.calls.filter(x=>x.name==='reboot').length,1);assert.equal(f.stages.find(x=>x.native==='reboot').lane,'ui');
  assert.deepEqual(f.traces.map(x=>x.split(' ')[1]),['terminal_enter','status','open_start','open_ok','consume_allow','original_started','lease_released']);
  for(const line of f.traces)assert(/^TRACE [a-z_]+ [A-Za-z0-9_-]{1,24}$/.test(line)&&line.length<64);
  assert.equal(f.retained,0);noUiProtocol(f);
});

await lifecycleCase('an existing engine reference can be paired without changing file-only permission',async()=>{
  const f=fixture({queued:true,lifecycle:true,engineReady:true,replies:grantReplies()});await f.init();f.invoke('reboot','manual');f.flush();
  assert.equal(f.starts,1);assert.equal(f.stops,1);assert.equal(f.references,0);assert.equal(f.calls.length,1);noUiProtocol(f);
});

await lifecycleCase('missing optional lifecycle still consumes a valid file and preserves the original flow',async()=>{
  const f=fixture({queued:true,replies:grantReplies()});await f.init();f.invoke('reboot','manual');f.flush();
  assert.deepEqual(f.requests,['STATE','STATE','CONSUME']);assert.equal(f.inspect().critical_ready,true);
  assert.equal(f.calls.filter(x=>x.name==='reboot').length,1);assert.equal(f.retained,0);noUiProtocol(f);
});

await lifecycleCase('native start false or exception never denies a file grant or causes unmatched close',async()=>{
  for(const engineStart of [false,new Error('native opener failed')]){
    const f=fixture({queued:true,lifecycle:true,engineStart,replies:grantReplies()});await f.init();f.invoke('reboot','manual');f.flush();
    assert.equal(f.starts,1);assert.equal(f.stops,0);assert.equal(f.references,0);assert.equal(f.retained,0);
    assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);assert.equal(f.calls.length,1);noUiProtocol(f);
  }
});

await lifecycleCase('real pending action failure is reported as already authorized and does not reboot',async()=>{
  const f=fixture({queued:true,lifecycle:true,replies:[{ok:true,state:0},{ok:false,authorized:true,state:0,decision:'EXECUTION_FAILED',error:'typed service unavailable'}]});
  await f.init();f.invoke('reboot','manual');f.flush();assert.equal(f.calls.length,0);
  assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);assert.equal(f.starts,1);assert.equal(f.stops,1);assert.equal(f.retained,0);
  assert.equal(f.inspect().broker_ready,true);assert.equal(f.inspect().hook_ready,true);
  assert(f.events.some(x=>x.event==='reboot_execution_failed'));assert(f.traces.some(x=>x.startsWith('TRACE original_failed ')));
  assert.equal(f.traces.some(x=>x.startsWith('TRACE consume_allow ')),false);
  assert.equal(f.traces.some(x=>x.startsWith('TRACE consume_deny ')),false);
  assert.equal(f.events.some(x=>x.event==='reboot_authorization_result'&&x.authorized===true),true);noUiProtocol(f);
});

await lifecycleCase('old phase boot source cycle metadata never adds a file permission condition',async()=>{
  for(const phase of ['NONE','ARMED','DEFERRED','DONE','FAILED','COMMITTING','COMMITTED']){
    const f=fixture({queued:true,state:{ok:true,state:1,phase,ready:false,requires_engine:true,boot_id:'old',source_slot:1,cycle_id:'other'},replies:grantReplies()});
    await f.init();f.invoke('reboot','manual');f.flush();assert.equal(f.calls.length,1);assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);noUiProtocol(f);
  }
});

await lifecycleCase('best effort TRACE failure never changes the real permission or original callback',async()=>{
  const f=fixture({queued:true,lifecycle:true,traceFails:true,replies:grantReplies()});await f.init();f.invoke('reboot','manual');f.flush();
  assert.equal(f.calls.filter(x=>x.name==='reboot').length,1);assert.equal(f.starts,1);assert.equal(f.stops,1);assert.equal(f.retained,0);noUiProtocol(f);
});

await lifecycleCase('non-Looper terminal callers retain non-UI execution and consume exactly once',async()=>{
  const f=fixture({queued:true,noLooper:true,replies:grantReplies()});await f.init();f.invoke('reboot','manual');f.flush();
  assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);assert.equal(f.calls.filter(x=>x.name==='reboot').length,1);
  assert.equal(f.stages.find(x=>x.native==='reboot').lane,'worker');assert.equal(f.retained,0);noUiProtocol(f);
});

await lifecycleCase('original callback failure is reported once and still balances the native lease',async()=>{
  const f=fixture({queued:true,lifecycle:true,replies:grantReplies()});await f.init();let called=0;
  f.methods.get('reboot').call=()=>{called++;throw new Error('original failure');};f.invoke('reboot','manual');f.flush();
  assert.equal(called,1);assert.equal(f.starts,1);assert.equal(f.stops,1);assert.equal(f.references,0);assert.equal(f.retained,0);
  assert(f.traces.some(x=>x.startsWith('TRACE original_failed ')));assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);noUiProtocol(f);
});

await lifecycleCase('rejected captured Looper never replays consumed permission or a native body',async()=>{
  const f=fixture({queued:true,lifecycle:true,postFails:true,replies:grantReplies()});await f.init();f.invoke('reboot','manual');f.flush();
  assert.equal(f.calls.filter(x=>x.name==='reboot').length,0);assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);
  assert.equal(f.starts,1);assert.equal(f.stops,1);assert.equal(f.retained,0);assert.equal(f.references,0);noUiProtocol(f);
});

await lifecycleCase('worker scheduling failure releases retained Java wrappers without a broker request',async()=>{
  const f=fixture({queued:true,threadFailAt:1,replies:grantReplies()});await f.init();f.invoke('reboot','manual');f.flush();
  assert.deepEqual(f.requests,['STATE']);assert.equal(f.retained,0);assert.equal(f.inspect().reboot_preparing,false);
});

await lifecycleCase('lease cleanup scheduling failure has a separate non-UI fallback without replay',async()=>{
  const f=fixture({queued:true,lifecycle:true,threadFailAt:2,replies:grantReplies()});await f.init();f.invoke('reboot','manual');f.flush();
  assert.equal(f.stops,0);assert.equal(f.timers.length,1);f.timers.shift()();assert.equal(f.stops,1);assert.equal(f.references,0);assert.equal(f.retained,0);
  assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);assert.equal(f.calls.filter(x=>x.name==='reboot').length,1);noUiProtocol(f);
});

await lifecycleCase('a new payload notification is not a second permission condition after an actual file grant',async()=>{
  const f=fixture({queued:true,lifecycle:true,replies:grantReplies()});await f.init();f.invoke('reboot','manual');
  f.workers.shift()();assert.equal(f.main.length,1);assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);
  f.invoke('apply','new-payload');f.flush();assert.equal(f.calls.filter(x=>x.name==='apply').length,1);
  assert.equal(f.calls.filter(x=>x.name==='reboot').length,1);assert.equal(f.starts,1);assert.equal(f.stops,1);assert.equal(f.retained,0);
  assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);
});

await lifecycleCase('STATE transport failure does not invent a file value or spend permission',async()=>{
  const f=fixture({queued:true,lifecycle:true,state:new Error('file reader unavailable'),replies:grantReplies()});await f.init();f.invoke('reboot','manual');f.flush();
  assert.equal(f.starts,0);assert.equal(f.calls.length,0);assert.equal(f.requests.includes('CONSUME'),false);assert.equal(f.retained,0);noUiProtocol(f);
});

await lifecycleCase('an atomic file denial after the preliminary read never invokes the original reboot',async()=>{
  const f=fixture({queued:true,lifecycle:true,replies:[{ok:true,state:0},{ok:true,authorized:false,state:0,decision:'DENY'}]});await f.init();f.invoke('reboot','manual');f.flush();
  assert.equal(f.starts,1);assert.equal(f.stops,1);assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);assert.equal(f.calls.length,0);assert.equal(f.retained,0);noUiProtocol(f);
});

await lifecycleCase('execution failure waits for a fresh user grant and click rather than replaying a consumed action',async()=>{
  const f=fixture({queued:true,lifecycle:true,replies:[{ok:true,state:0},{ok:false,authorized:true,state:0,decision:'EXECUTION_FAILED',error:'invoke failed'},
    {ok:true,authorized:true,state:0,decision:'ALLOW'}]});await f.init();f.invoke('reboot','manual');f.flush();
  assert.equal(f.calls.length,0);assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);assert.equal(f.workers.length,0);assert.equal(f.timers.length,0);
  // The fixture supplies a new actual value1 only when the user explicitly retries.
  f.invoke('reboot','manual');f.flush();assert.equal(f.requests.filter(x=>x==='CONSUME').length,2);assert.equal(f.calls.length,1);
  assert.equal(f.starts,2);assert.equal(f.stops,2);assert.equal(f.references,0);assert.equal(f.retained,0);noUiProtocol(f);
});

await lifecycleCase('unavailable lifecycle binding remains optional with no pending native action',async()=>{
  const f=fixture({queued:true,lifecycle:true,lifecycleBindFails:true,replies:grantReplies()});await f.init();f.invoke('reboot','manual');f.flush();
  assert.equal(f.inspect().critical_ready,true);assert.equal(f.inspect().engine_lifecycle_ready,false);assert.equal(f.calls.length,1);
  assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);assert(f.events.some(x=>x.event==='warning'&&x.phase==='engine_lifecycle'));noUiProtocol(f);
});

await lifecycleCase('optional action diagnostic fields are not an extra condition on a successful file consume',async()=>{
  const f=fixture({queued:true,replies:grantReplies()});await f.init();f.invoke('reboot','manual');f.flush();
  assert.equal(f.calls.length,1);assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);assert.equal(f.retained,0);noUiProtocol(f);
});

console.log(lifecycleCases+' asynchronous marker-permission lifecycle cases passed; Java ABI is a fixture, no service or update ran');

await lifecycleCase('upstream manual frontier reads only STATE and preserves original preparation until the terminal',async()=>{
 const f=fixture({queued:true,lifecycle:true,replies:grantReplies()});await f.init();f.raw('frontier','manual');f.flush();
 assert.deepEqual(f.requests,['STATE','STATE']);assert.equal(f.starts,0);assert.equal(f.calls.filter(x=>x.name==='frontier').length,1);assert.equal(f.inspect().stats.allowed,0);
});
await lifecycleCase('upstream zero redirects before the original preparation or terminal',async()=>{
 const f=fixture({queued:true,state:{ok:true,state:0},replies:grantReplies()});await f.init();f.raw('frontier','manual');f.flush();assert.equal(f.calls.length,0);assert.equal(f.requests.includes('CONSUME'),false);
});
await lifecycleCase('original reason bridge and captured Runnable reach the terminal with one consumption',async()=>{
 const f=fixture({queued:true,lifecycle:true,replies:grantReplies()});await f.init();
 const native=f.methods.get('frontier').call;f.methods.get('frontier').call=(receiver,...args)=>{native(receiver,...args);f.raw('reason',args[0]);};
 f.methods.get('reason').call=()=>f.continuation();f.methods.get('runContinuation').call=()=>f.raw('reboot','ota');
 f.raw('frontier','manual');f.flush();assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);assert.equal(f.calls.filter(x=>x.name==='reboot').length,1);assert.equal(f.starts,1);assert.equal(f.stops,1);
 assert.equal(f.deopts,1);assert.equal(f.inspect().deoptimized,true);assert.equal(f.inspect().terminal_ready,true);assert.equal(f.inspect().continuation_ready,true);
});
await lifecycleCase('replayed associated callback restores its original reason without another frontier consume',async()=>{
 const f=fixture({queued:true,replies:grantReplies()});await f.init();f.methods.get('reason').call=()=>f.continuation();f.methods.get('runContinuation').call=()=>f.raw('reboot','ota');
 f.raw('reason','manul');f.flush();assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);assert.equal(f.calls.filter(x=>x.name==='reboot').length,1);
});
await lifecycleCase('automatic and unknown actual exits never spend a pending one',async()=>{
 for(const kind of ['auto','unrecognized']){const f=fixture({queued:true,replies:grantReplies()});await f.init();f.methods.get('reason').call=()=>f.continuation();f.methods.get('runContinuation').call=()=>f.raw('reboot','ota');f.raw('reason',kind);f.flush();assert.equal(f.requests.includes('CONSUME'),false);assert.equal(f.calls.filter(x=>x.name==='reboot').length,0);}
});
await lifecycleCase('failed process deoptimization never fabricates protected readiness',async()=>{
 const f=fixture({deoptFails:true});await assert.rejects(f.init(),/deopt unavailable/);assert.equal(f.inspect().critical_ready,false);assert.equal(f.events.some(e=>e.event==='ready'),false);assert.equal(f.deopts,1);
});
console.log('Terminal and original preparation integration cases passed');

await lifecycleCase('the same captured Runnable cannot reuse manual intent on a later invocation',async()=>{
 const f=fixture({queued:true,replies:[{ok:true,state:0},{ok:true,state:0,decision:'ALLOW'},{ok:true,state:0,decision:'ALLOW'}]});await f.init();let task;
 f.methods.get('reason').call=()=>{task=f.continuation();};f.methods.get('runContinuation').call=()=>f.raw('reboot','ota');f.raw('reason','manul');f.flush();
 assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);f.run(task);f.flush();assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);assert.equal(f.calls.filter(x=>x.name==='reboot').length,1);
});
await lifecycleCase('the bridge accepts a non-default reason only when carried by discovered metadata',async()=>{
 const hooks=critical();hooks.find(x=>x.role==='reboot_context').manual_values=['vendor_explicit_resume'];const f=fixture({queued:true,hooks,replies:grantReplies()});await f.init();
 f.methods.get('reason').call=()=>f.continuation();f.methods.get('runContinuation').call=()=>f.raw('reboot','ota');f.raw('reason','vendor_explicit_resume');f.flush();assert.equal(f.requests.filter(x=>x==='CONSUME').length,1);assert.equal(f.calls.filter(x=>x.name==='reboot').length,1);
});

await lifecycleCase('a failed new BEGIN never starts payload and a real recovered reset permits a fresh retry',async()=>{
 const f=fixture({replies:[{ok:true,state:0},new Error('RESET unavailable'),{ok:true,state:0}]});await f.init();assert.throws(()=>f.invoke('apply','payload'),/clearing the previous OTA permission failed/);
 assert.equal(f.calls.filter(x=>x.name==='apply').length,0);assert.equal(f.inspect().stats.reset,0);assert.equal(f.requests.includes('CONSUME'),false);
 f.invoke('complete',0);assert.equal(f.requests.filter(x=>x==='RESET').length,0);f.invoke('apply','payload');assert.equal(f.calls.filter(x=>x.name==='apply').length,1);assert.equal(f.inspect().stats.reset,1);
});
