import assert from 'node:assert/strict';
import {rebootLabels, labelForState, isManualRestartText, createRebootLabelController, lifecycleImplementations, installRebootLabels} from '../src/reboot-labels.js';

// Presentation-only fixtures: no Android updater, marker mutation, slot API or
// reboot is executed. The native listener/result/arguments remain the oracle.
let passed = 0;
const test = (name, fn) => { fn(); passed++; console.log('PASS ' + name); };
function fixture({reply = {ok:true,state:0}, views, resources = [], warnThrows = false} = {}) {
  let time = 1000, counter = 0, stateReply = reply;
  const timers = new Map(), warnings = [], requests = [], writes = [], weak = [];
  const button = (text, extra={}) => ({text,visible:true,enabled:true,clickable:true,listener:true,...extra});
  const native = views || [button('立即重启'),button('下载'),button('稍后'),button('Restart now',{visible:false})];
  const owner = {views:native}, other = {views:[]};
  const read = view => ({...view});
  const later = (fn, delay) => { const id=++counter;timers.set(id,{fn,at:time+delay});return id; };
  const p={state(){requests.push('STATE');return typeof stateReply==='function'?stateReply():stateReply;},
    warn(message,error){warnings.push({message,error});if(warnThrows)throw new Error('logger absent');},
    weak(value){const ref={value,get(){return this.value;}};weak.push(ref);return ref;},same:(a,b)=>a===b,
    main:fn=>fn(),background:fn=>later(fn,0),later,cancel:id=>timers.delete(id),now:()=>time,
    read,write(view,text){writes.push({view,text});if(view.failWrite)throw new Error('native setter failed');view.text=text;},
    scan:activity=>activity.views.map(view=>({...read(view),view,manual:isManualRestartText(view.text,resources)})),
    intervalMs:2500,cacheMs:750};
  const labels=createRebootLabelController(p);
  function tick(ms=0){time+=ms;for(;;){const entry=[...timers].find(([,task])=>task.at<=time);if(!entry)break;timers.delete(entry[0]);entry[1].fn();}}
  return {labels,owner,other,native,button,p,timers,warnings,requests,writes,weak,tick,setReply:value=>{stateReply=value;}};
}

test('only exact proven one is labelled Reboot now',()=>{
  assert.equal(labelForState({ok:true,state:1}),rebootLabels.reboot);
  for(const reply of [null,{}, {ok:true,state:0},{ok:true,state:''},{ok:true,state:'1'},{ok:false,state:1},{state:1}])
    assert.equal(labelForState(reply),rebootLabels.continue);
});
test('two APK native resource/localized texts and strict unrelated controls',()=>{
  for(const text of ['立即重启','Restart now','REBOOT NOW','立即重新啟動'])assert.equal(isManualRestartText(text),true);
  assert.equal(isManualRestartText('Reiniciar ahora',['Reiniciar ahora']),true);
  for(const text of ['下载','安装','继续流程','Restart','Restart (10s)','Install and restart','Restart now?','night restart'])
    assert.equal(isManualRestartText(text),false);
});
test('all original restart text variants retain trimming case and whitespace compatibility',()=>{
  for(const text of ['立即重启','立即重新启动','立即重新啟動','立即重新開機','restart now','reboot now',
    ' 立即重新启动 ','\u00a0立即重新開機\u00a0','RESTART NOW','ReBoOt NoW','restart   now','reboot\tnow','restart\nnow'])
    assert.equal(isManualRestartText(text),true,JSON.stringify(text));
  for(const text of ['继续流程','立即重启设备','立即重启?','download and reboot now','重启','稍后重启'])
    assert.equal(isManualRestartText(text),false,text);
});
test('foreground zero then resume one, unrelated and hidden buttons untouched',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();
  assert.equal(f.native[0].text,'继续流程');assert.equal(f.native[1].text,'下载');assert.equal(f.native[2].text,'稍后');
  assert.equal(f.native[3].text,'Restart now');assert.deepEqual(f.requests,['STATE']);
  f.labels.pause(f.owner);assert.equal(f.native[0].text,'立即重启');assert.equal(f.timers.size,0);
  f.setReply({ok:true,state:1});f.labels.resume(f.owner);f.tick();assert.equal(f.native[0].text,'立即重启');
  assert.deepEqual(f.requests,['STATE','STATE']);
});
test('no matching control means no broker traffic',()=>{
  const f=fixture({views:[{text:'安装',visible:true,enabled:true,clickable:true,listener:true}]});
  f.labels.resume(f.owner);f.tick(0);f.tick(2500);assert.deepEqual(f.requests,[]);assert.equal(f.writes.length,0);
});
test('manual text requires visibility, enabled click and a native listener',()=>{
  for(const extra of [{visible:false},{enabled:false},{clickable:false},{listener:false}]){
    const f=fixture({views:[{text:'立即重启',visible:true,enabled:true,clickable:true,listener:true,...extra}]});
    f.labels.resume(f.owner);f.tick();assert.equal(f.requests.length,0);assert.equal(f.writes.length,0);
  }
});
test('native state transition on a reused view retires the presentation',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();f.native[0].text='安装';f.labels.refresh(false);f.tick();
  assert.equal(f.native[0].text,'安装');assert.equal(f.labels.inspect().tracked,0);assert.equal(f.requests.length,1);
});
test('real click observes original native text, original arguments/result once',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();let calls=0;const args=['manul',17];
  const result=f.labels.aroundClick(f.native[0],()=>{calls++;assert.equal(f.native[0].text,'立即重启');assert.deepEqual(args,['manul',17]);return 'native-result';});
  assert.equal(result,'native-result');assert.equal(calls,1);assert.equal(f.native[0].text,'继续流程');assert.deepEqual(f.requests,['STATE']);
});
test('native exception and nested click propagate without consuming another token',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();const failure=new Error('native failure');let calls=0;
  assert.throws(()=>f.labels.aroundClick(f.native[0],()=>f.labels.aroundOriginal(()=>{calls++;assert.equal(f.native[0].text,'立即重启');throw failure;})),error=>error===failure);
  assert.equal(calls,1);assert.equal(f.native[0].text,'继续流程');assert.deepEqual(f.requests,['STATE']);
});
test('unrelated click and native text replaced immediately before click stay native',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();let calls=0;
  f.labels.aroundClick(f.native[1],()=>{calls++;assert.equal(f.native[1].text,'下载');});
  f.native[0].text='安装';f.labels.aroundClick(f.native[0],()=>{calls++;assert.equal(f.native[0].text,'安装');});
  assert.equal(calls,2);assert.equal(f.native[0].text,'安装');
});
test('pause cancels queued read and background timer; another activity pause is ignored',()=>{
  const f=fixture();f.labels.resume(f.owner);f.labels.pause(f.other);assert.equal(f.labels.inspect().active,true);
  f.labels.pause(f.owner);f.tick(10000);assert.deepEqual(f.requests,[]);assert.equal(f.timers.size,0);assert.equal(f.native[0].text,'立即重启');
});
test('stale reply from previous resume cannot install old granted label',()=>{
  const f=fixture();f.setReply(()=>{f.labels.pause(f.owner);return {ok:true,state:1};});f.labels.resume(f.owner);f.tick();
  assert.equal(f.native[0].text,'立即重启');assert.equal(f.labels.inspect().active,false);assert.equal(f.timers.size,0);
  f.setReply({ok:true,state:0});f.labels.resume(f.owner);f.tick();assert.equal(f.native[0].text,'继续流程');
});
test('broker absence, malformed state and logger failure only affect label/warning',()=>{
  const f=fixture({warnThrows:true,reply:()=>{throw new Error('socket absent');}});f.labels.resume(f.owner);f.tick();
  assert.equal(f.native[0].text,'继续流程');assert.equal(f.warnings.length,1);
  f.setReply({ok:true,state:'1'});f.tick(2500);assert.equal(f.native[0].text,'继续流程');
  f.setReply({ok:true,state:1});f.tick(2500);assert.equal(f.native[0].text,'立即重启');
});
test('weak collected views are retired, disposal restores and ends monitoring',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();
  for(const ref of f.weak)if(ref.value===f.native[0])ref.value=null;
  f.owner.views=[];f.labels.refresh(false);f.tick();assert.equal(f.labels.inspect().tracked,0);
  f.labels.dispose();f.labels.dispose();f.tick(10000);assert.equal(f.labels.inspect().stopped,true);assert.equal(f.timers.size,0);
});
test('optional write failure does not interrupt native listener',()=>{
  const f=fixture({warnThrows:true});f.native[0].failWrite=true;f.labels.resume(f.owner);f.tick();let calls=0;
  assert.equal(f.labels.aroundClick(f.native[0],()=>{calls++;return 42;}),42);assert.equal(calls,1);assert.ok(f.warnings.length>0);
});
test('view identity failure still executes the original callback exactly once',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();f.p.same=()=>{throw new Error('dead Java wrapper');};let calls=0;
  assert.equal(f.labels.aroundClick(f.native[0],()=>{calls++;return 43;}),43);assert.equal(calls,1);assert.ok(f.warnings.length>0);
});
test('refresh scheduler failure preserves native return and original exception',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();f.p.main=()=>{throw new Error('UI scheduler absent');};let calls=0;
  assert.equal(f.labels.aroundClick(f.native[0],()=>{calls++;return 44;}),44);
  const original=new Error('native callback');assert.throws(()=>f.labels.aroundOriginal(()=>{calls++;throw original;}),error=>error===original);
  assert.equal(calls,2);assert.ok(f.warnings.length>0);
});
test('visible marker changes and native asynchronous text writes are reconciled without lifecycle changes',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();assert.equal(f.native[0].text,'继续流程');
  f.native[0].text='立即重启';f.labels.refresh(false);f.tick();assert.equal(f.native[0].text,'继续流程');
  f.setReply({ok:true,state:1});f.tick(2500);assert.equal(f.native[0].text,'立即重启');
  f.setReply({ok:true,state:0});f.tick(2500);assert.equal(f.native[0].text,'继续流程');
  assert.deepEqual(f.requests,['STATE','STATE','STATE']);
  assert.equal(f.native[1].text,'下载');assert.equal(f.native[2].text,'稍后');
  f.labels.dispose();const requests=f.requests.length;f.tick(100000);assert.equal(f.requests.length,requests);assert.equal(f.timers.size,0);
});
test('returning from hnubl forces fresh state even within the label cache lifetime',()=>{
  const f=fixture({reply:{ok:true,state:1}});f.labels.resume(f.owner);f.tick();assert.equal(f.native[0].text,'立即重启');
  f.labels.pause(f.owner);f.setReply({ok:true,state:0});f.labels.resume(f.owner);f.tick();assert.equal(f.native[0].text,'继续流程');
  f.labels.pause(f.owner);f.setReply({ok:true,state:1});f.labels.resume(f.owner);f.tick();assert.equal(f.native[0].text,'立即重启');
  assert.deepEqual(f.requests,['STATE','STATE','STATE']);f.labels.dispose();
});
test('reused download/install views remain native and can later become a restart candidate again',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();assert.equal(f.native[0].text,'继续流程');
  f.native[0].text='下载';f.labels.refresh(false);f.tick();assert.equal(f.native[0].text,'下载');
  f.native[0].text='安装';f.labels.refresh(false);f.tick();assert.equal(f.native[0].text,'安装');
  f.native[0].text='立即重启';f.labels.refresh(false);f.tick();assert.equal(f.native[0].text,'继续流程');
  assert.equal(f.labels.inspect().tracked,1);assert.ok(f.requests.every(command=>command==='STATE'));f.labels.dispose();
});
test('a foreground view created after resume is found and an empty page sends no state requests',()=>{
  const f=fixture({views:[]});f.labels.resume(f.owner);f.tick();assert.equal(f.requests.length,0);
  f.owner.views.push(f.button('立即重启'));f.labels.refresh(false);f.tick();assert.equal(f.owner.views[0].text,'继续流程');
  f.owner.views=[];f.labels.refresh(false);f.tick();const requests=f.requests.length;f.tick(2500);assert.equal(f.requests.length,requests);
  f.labels.dispose();assert.equal(f.timers.size,0);
});
test('parent action and child label preserve original callback arguments return and exception once',()=>{
  const f=fixture();const parent={clickable:true,listener:true,enabled:true};const label=f.native[0];
  const nativeRead=f.p.read;f.p.read=view=>({...nativeRead(view),...(view===label?{clickable:parent.clickable,listener:parent.listener,
    enabled:parent.enabled,actionView:parent}:{})});
  f.p.scan=owner=>owner.views.map(view=>({...f.p.read(view),view,manual:isManualRestartText(view.text)}));
  f.labels.resume(f.owner);f.tick();assert.equal(label.text,'继续流程');let calls=0;const args=['manul',17];
  const result=f.labels.aroundClick(parent,()=>{calls++;assert.equal(label.text,'立即重启');assert.deepEqual(args,['manul',17]);return 73;});
  assert.equal(result,73);assert.equal(calls,1);assert.equal(label.text,'继续流程');
  const failure=new Error('original parent listener');
  assert.throws(()=>f.labels.aroundClick(parent,()=>{calls++;assert.equal(label.text,'立即重启');throw failure;}),error=>error===failure);
  assert.equal(calls,2);assert.deepEqual(f.requests,['STATE']);
  parent.enabled=false;f.labels.refresh(false);f.tick();assert.equal(label.text,'立即重启');f.labels.dispose();
});
test('queued resume followed by pause cannot resurrect a stopped foreground screen',()=>{
  const f=fixture(),ui=[];f.p.main=fn=>ui.push(fn);
  const flush=()=>{while(ui.length)ui.shift()();};
  f.labels.resume(f.owner);f.labels.pause(f.owner);flush();f.tick();flush();
  assert.equal(f.labels.inspect().active,false);assert.deepEqual(f.requests,[]);assert.equal(f.native[0].text,'立即重启');
  assert.equal(f.timers.size,0);f.labels.dispose();flush();
});
test('queued old resume and pause cannot replace the later resumed screen',()=>{
  const f=fixture(),ui=[];f.p.main=fn=>ui.push(fn);f.other.views=[f.button('Restart now')];
  const flush=()=>{while(ui.length)ui.shift()();};
  f.labels.resume(f.owner);f.labels.pause(f.owner);f.labels.resume(f.other);flush();f.tick();flush();
  assert.equal(f.labels.inspect().active,true);assert.equal(f.native[0].text,'立即重启');assert.equal(f.other.views[0].text,'继续流程');
  assert.deepEqual(f.requests,['STATE']);f.labels.dispose();flush();assert.equal(f.timers.size,0);
});
test('a reply and queued UI render from an earlier screen cannot overwrite the new screen',()=>{
  const f=fixture(),ui=[];f.p.main=fn=>ui.push(fn);f.other.views=[f.button('Restart now')];
  const flush=()=>{while(ui.length)ui.shift()();};
  f.setReply(()=>{f.labels.pause(f.owner);f.labels.resume(f.other);f.setReply({ok:true,state:0});return {ok:true,state:1};});
  f.labels.resume(f.owner);flush();f.tick();flush();f.tick();flush();
  assert.equal(f.native[0].text,'立即重启');assert.equal(f.other.views[0].text,'继续流程');assert.equal(f.labels.inspect().active,true);
  assert.deepEqual(f.requests,['STATE','STATE']);f.labels.dispose();flush();assert.equal(f.timers.size,0);
});
test('a transient resume scheduler failure does not permanently reserve the pending owner',()=>{
  const f=fixture();f.p.main=()=>{throw new Error('temporary resume scheduler failure');};
  assert.doesNotThrow(()=>f.labels.resume(f.owner));assert.equal(f.labels.inspect().active,false);assert.equal(f.requests.length,0);
  f.p.main=fn=>fn();f.labels.resume(f.owner);f.tick();assert.equal(f.native[0].text,'继续流程');
  assert.deepEqual(f.requests,['STATE']);assert.ok(f.warnings.length>0);f.labels.dispose();assert.equal(f.timers.size,0);
});
test('stable state polling performs no UI scan read or write and changed state touches only tracked labels',()=>{
  const f=fixture();let scans=0,reads=0;const scan=f.p.scan,read=f.p.read;
  f.p.scan=owner=>{scans++;return scan(owner);};f.p.read=view=>{reads++;return read(view);};
  f.labels.resume(f.owner);f.tick();const baseline={scans,reads,writes:f.writes.length,requests:f.requests.length};
  for(let i=0;i<3;i++)f.tick(2500);
  assert.equal(f.requests.length,baseline.requests+3);assert.equal(scans,baseline.scans);
  assert.equal(reads,baseline.reads);assert.equal(f.writes.length,baseline.writes);
  f.setReply({ok:true,state:1});f.tick(2500);assert.equal(f.native[0].text,'立即重启');assert.equal(scans,baseline.scans);
  assert.ok(reads>baseline.reads);assert.equal(f.writes.length,baseline.writes+1);
  assert.equal(f.native[1].text,'下载');assert.equal(f.native[2].text,'稍后');f.labels.dispose();
});

const reflectedType = name => ({getName:()=>name,isAssignableFrom:other=>name===String(other.getName()) ||
  (name==='java.lang.Object' && !['void','boolean','char','byte','short','int','long','float','double'].includes(String(other.getName())))});
const reflectedMethod = (name,parameters=[],returns='void',modifiers=1) => ({getName:()=>name,getModifiers:()=>modifiers,
  getParameterTypes:()=>parameters.map(reflectedType),getReturnType:()=>reflectedType(returns)});
function lifecycleContract() {
  const activity='android.app.Activity',bundle='android.os.Bundle';
  const declared=[reflectedMethod('onActivityCreated',[activity,bundle]),reflectedMethod('onActivityStarted',[activity]),
    reflectedMethod('onActivityResumed',[activity]),reflectedMethod('onActivityPaused',[activity]),reflectedMethod('onActivityStopped',[activity]),
    reflectedMethod('onActivitySaveInstanceState',[activity,bundle]),reflectedMethod('onActivityDestroyed',[activity])];
  // Supplied current-device ABI fixture, plus a future overload and method.
  // Production discovers these names at runtime and has no such list.
  const defaults=['PostStarted','PostStopped','PreStarted','PrePaused','PreDestroyed','PostResumed',
    'PostCreated','PreCreated','PreResumed','PreSaveInstanceState','PreStopped','PostSaveInstanceState','PostPaused','PostDestroyed']
    .map(suffix=>reflectedMethod('onActivity'+suffix,/Created|SaveInstanceState/.test(suffix)?[activity,bundle]:[activity]));
  defaults.push(reflectedMethod('onActivityConfigurationChanged',[activity,'android.content.res.Configuration']),
    reflectedMethod('onActivityResumed',[activity,'int']),reflectedMethod('futurePlatformCallback',['[Landroid.app.Activity;','long'],'boolean'));
  return {getMethods:()=>[...declared,...defaults],getDeclaredMethods:()=>declared};
}
test('runtime interface methods include all inherited/default callbacks with exact signatures',()=>{
  const contract=lifecycleContract(),calls=[],warnings=[];
  const methods=lifecycleImplementations(contract,{resume:value=>calls.push(['resume',value]),pause:value=>calls.push(['pause',value])},(...args)=>warnings.push(args));
  for(const expected of contract.getMethods()){
    const name=expected.getName(),args=expected.getParameterTypes().map(type=>type.getName());
    const matches=methods[name].filter(method=>JSON.stringify(method.argumentTypes)===JSON.stringify(args));
    assert.equal(matches.length,1,name+' exact overload');assert.equal(matches[0].returnType,expected.getReturnType().getName());
  }
  const owner={};methods.onActivityResumed.find(m=>m.argumentTypes.length===1).implementation(owner);
  methods.onActivityResumed.find(m=>m.argumentTypes.length===2).implementation(owner,1);
  methods.onActivityPostResumed[0].implementation(owner);methods.onActivityPaused[0].implementation(owner);methods.onActivityDestroyed[0].implementation(owner);
  assert.deepEqual(calls,[['resume',owner],['resume',owner],['pause',owner],['pause',owner]]);assert.deepEqual(warnings,[]);
  assert.equal(methods.futurePlatformCallback[0].implementation([],0),false);
});
test('new return types, arrays, covariant bridges and non-instance interface helpers',()=>{
  const members=['void','boolean','char','byte','short','int','long','float','double','java.lang.String','[Ljava.lang.String;']
    .map((type,index)=>reflectedMethod('future'+index,['[I','java.lang.String'],type));
  members.push(reflectedMethod('staticHelper',[],'void',9),reflectedMethod('privateHelper',[],'void',2),
    reflectedMethod('bridge',[],'java.lang.Object'),reflectedMethod('bridge',[],'java.lang.String'));
  const methods=lifecycleImplementations({getMethods:()=>members,getDeclaredMethods:()=>members},{},()=>{});
  assert.equal(methods.staticHelper,undefined);assert.equal(methods.privateHelper,undefined);
  const expected=[undefined,false,'\u0000',0,0,0,0,0,0,null,null];
  for(let i=0;i<expected.length;i++)assert.equal(methods['future'+i][0].implementation([],''),expected[i]);
  assert.equal(methods.bridge.length,1);assert.equal(methods.bridge[0].returnType,'java.lang.String');
});
function androidFixture({choose='throws',roots='inspector',parentClick=false,dialog=false,focused=true,decorContext=false,queuedMain=false,observer='available',
  resourceName='para_restart_now',resourceText='Restart now',nativeText='Restart now',bodyCount=0,identity='unique',layoutDelivery='sync',textWatchers='available',nativeCallbackGetter=false}={}) {
  const timers=new Map(),callbacks=[],properties=new Map(),requests=[],warnings=[];let timer=0;
  let reply={ok:true,state:0},chooseCalls=0,time=1000,scheduleFailure=false,textFailure=null,addFailure=null;
  const observers=new Map(),weakRefs=[];let callbackDepth=0,maxCallbackDepth=0,nativeDepth=0,maxNativeDepth=0,layoutEvents=0,observerAdds=0,observerRemoves=0;
  const facades=new WeakMap();const unwrap=value=>value!=null&&typeof value==='object'&&facades.has(value)?facades.get(value):value;
  const bridge={textReads:0,bodyTextReads:0,parentReads:0,bodyParentReads:0,walks:0,resourceIds:0,resourceStrings:0,resourceConfigs:0,identity:0,casts:0,main:0};
  let locale='en-US',configurationRevision=0,nativeRebootText=nativeText;
  function objectFacade(value) {
    if(value==null)return null;
    const result={equals(other){return value===unwrap(other);},getClass(){return value.getClass?value.getClass():{getName:()=> 'java.lang.Object'};}};
    facades.set(result,value);return result;
  }
  function dispatch(root,kind,value) {
    const target=observers.get(root);if(!target)return;
    for(const listener of [...target[kind]]) {
      callbackDepth++;maxCallbackDepth=Math.max(maxCallbackDepth,callbackDepth);
      try {assert.ok(callbackDepth<25,'view-tree observer synchronously recursed');
        if(kind==='layouts'){layoutEvents++;listener.onGlobalLayout();}else if(kind==='scroll')listener.onScrollChanged();else listener.onWindowFocusChanged(value);
      }finally{callbackDepth--;}
    }
  }
  const nativeLayoutFrames=new Set();
  function nativeLayout(root) {
    if(layoutDelivery==='none')return;
    if(layoutDelivery!=='async'){dispatch(root,'layouts');return;}
    // Android may deliver a coalesced layout pass after setText has returned.
    // This is a platform event, not a callback executed inside the write guard.
    if(nativeLayoutFrames.has(root))return;nativeLayoutFrames.add(root);
    setTimeout(()=>{nativeLayoutFrames.delete(root);dispatch(root,'layouts');},16);
  }
  const watchedTexts=new Map();let watcherAdds=0,watcherRemoves=0,watcherCallbacks=0;
  function addTextWatcher(view,listener) {
    if(textWatchers==='throws')throw new Error('addTextChangedListener unavailable');
    if(!watchedTexts.has(view))watchedTexts.set(view,new Set());watchedTexts.get(view).add(listener);watcherAdds++;
  }
  function removeTextWatcher(view,listener){if(watchedTexts.has(view))watchedTexts.get(view).delete(listener);watcherRemoves++;}
  function dispatchText(view,name,args) {
    for(const listener of [...(watchedTexts.get(view)||[])]) {
      callbackDepth++;maxCallbackDepth=Math.max(maxCallbackDepth,callbackDepth);watcherCallbacks++;
      try{assert.ok(callbackDepth<25,'TextWatcher synchronously recursed');listener[name](...args);}finally{callbackDepth--;}
    }
  }
  function newObserver() {
      const target={kind:'observer',layouts:new Set(),focus:new Set(),scroll:new Set(),alive:true,equals(other){return this===unwrap(other);}};
      const live=()=>{if(!target.alive)throw new Error('This ViewTreeObserver is not alive');};
      Object.assign(target,{isAlive:()=>target.alive,
        addOnGlobalLayoutListener(listener){live();observerAdds++;target.layouts.add(listener);},
        removeOnGlobalLayoutListener(listener){live();observerRemoves++;target.layouts.delete(listener);},
        removeGlobalOnLayoutListener(listener){live();observerRemoves++;target.layouts.delete(listener);},
        addOnWindowFocusChangeListener(listener){live();observerAdds++;target.focus.add(listener);},
        removeOnWindowFocusChangeListener(listener){live();observerRemoves++;target.focus.delete(listener);},
        addOnScrollChangedListener(listener){live();observerAdds++;target.scroll.add(listener);},
        removeOnScrollChangedListener(listener){live();observerRemoves++;target.scroll.delete(listener);}});
      return target;
  }
  function observerFor(root) {
    if(observer==='throws')throw new Error('getViewTreeObserver temporarily unavailable');
    if(!observers.has(root))observers.set(root,newObserver());
    return observers.get(root);
  }
  function nativeBody(fn) {nativeDepth++;maxNativeDepth=Math.max(maxNativeDepth,nativeDepth);try{return fn();}finally{nativeDepth--;}}
  const originalTimers={setTimeout:globalThis.setTimeout,clearTimeout:globalThis.clearTimeout,now:Date.now};
  globalThis.setTimeout=(fn,delay=0)=>{const id=++timer;timers.set(id,{fn,at:time+delay,delay});return id;};globalThis.clearTimeout=id=>timers.delete(id);
  Date.now=()=>time;
  const method=(fn,args=[])=>{
    const target={call:fn,argumentTypes:args.map(className=>({className})),returnType:{className:'void'}};
    let replacement=null;
    Object.defineProperty(target,'implementation',{configurable:true,get:()=>replacement,set(handler){
      // Frida exposes a generated native callback, not the assigned JS function.
      replacement=handler==null?null:nativeCallbackGetter?function(...values){return handler.apply(this,values);}:handler;
    }});
    return target;
  };let nativeCalls=0;
  const perform=method(view=>{nativeCalls++;assert.equal((view.label||view).text,nativeRebootText);return true;});
  const call=method(view=>{nativeCalls++;assert.equal((view.label||view).text,nativeRebootText);return false;});
  const text={kind:'text',text:nativeText,equals(other){return this===other;},getText(){bridge.textReads++;if(this.body)bridge.bodyTextReads++;return this.text;},isShown:()=>true,
    isEnabled:()=>true,isClickable:()=>!parentClick,hasOnClickListeners:()=>!parentClick};
  const download={...text,text:'Install',isClickable:()=>true,hasOnClickListeners:()=>true};
  const action={kind:'group',label:text,equals(other){return this===other;},isShown:()=>true,isEnabled:()=>true,isClickable:()=>true,
    hasOnClickListeners:()=>true,getChildCount:()=>1,getChildAt:()=>text};
  const body=Array.from({length:bodyCount},(_,i)=>({...text,body:true,text:'Release notes paragraph '+i,isClickable:()=>false,hasOnClickListeners:()=>false}));
  const children=[parentClick?action:text,download,...body];
  const group={kind:'group',_focused:focused,equals(other){return this===other;},getChildCount:()=>{bridge.walks++;return children.length;},getChildAt:i=>children[i],
    isShown:()=>true,isAttachedToWindow:()=>true,hasWindowFocus(){return this._focused;},isEnabled:()=>true,isClickable:()=>false,hasOnClickListeners:()=>false,
    getVisibility:()=>0,getWindowVisibility:()=>0};
  const token={equals(other){return this===other;}};
  const configuration={toString:()=>locale+';revision='+configurationRevision,getLocales:()=>({toLanguageTags:()=>locale,toString:()=>locale})};
  const resources={kind:'resources',equals(other){return this===unwrap(other);},getIdentifier(name){bridge.resourceIds++;return name===resourceName?99:0;},
    getString(){bridge.resourceStrings++;return resourceText;},getConfiguration(){bridge.resourceConfigs++;return configuration;}};
  const activity={kind:'activity',equals(other){return this===other;},getResources:()=>resources,
    getPackageName:()=> 'com.hihonor.ouc',getWindow:()=>({getDecorView:()=>group}),hasWindowFocus:()=>focused,isFinishing:()=>false,isDestroyed:()=>false};
  const wrapper={getBaseContext:()=>decorContext?null:activity,getPackageName:()=> 'com.hihonor.ouc',getResources:()=>activity.getResources()};
  for(const view of [text,download,action,group,...body]) {
    view.getContext=()=>dialog||decorContext?wrapper:activity;view.getApplicationWindowToken=()=>token;view.getWindowToken=()=>token;
    view.getResources=()=>activity.getResources();view.getRootView=()=>group;
    if(view.kind==='text') {
      view.addTextChangedListener=function(listener){return addTextWatcher(this,listener);};
      view.removeTextChangedListener=function(listener){return removeTextWatcher(this,listener);};
      view.addTextChangedListener.overload=()=>addWatcherMethod;
      view.removeTextChangedListener.overload=()=>removeWatcherMethod;
    }
  }
  group.getViewTreeObserver=function(){return observerFor(this);};
  text.getParent=()=>{bridge.parentReads++;return parentClick?action:group;};
  action.getParent=()=>{bridge.parentReads++;return group;};download.getParent=()=>{bridge.parentReads++;return group;};group.getParent=()=>null;
  for(const view of body)view.getParent=()=>{bridge.parentReads++;bridge.bodyParentReads++;return group;};
  const rootViews=[group],rootList={size:()=>rootViews.length,get:i=>rootViews[i],toArray:()=>rootViews};
  const app={getPackageName:()=> 'com.hihonor.ouc',registerActivityLifecycleCallbacks:value=>callbacks.push(value),
    unregisterActivityLifecycleCallbacks:value=>callbacks.splice(callbacks.indexOf(value),1)};
  let textWrites=0,addCalls=0;
  let focusCalls=0;const focusChanged=method((view,value)=>nativeBody(()=>{focusCalls++;view._focused=value;dispatch(view.getRootView(),'focus',value);}),['boolean']);
  const textSetter=method((view,value)=>nativeBody(()=>{textWrites++;if(textFailure)throw textFailure;
    const before=String(view.text);dispatchText(view,'beforeTextChanged',[before,0,before.length,String(value).length]);
    view.text=value;dispatchText(view,'onTextChanged',[String(value),0,before.length,String(value).length]);
    dispatchText(view,'afterTextChanged',[{toString:()=>String(view.text)}]);nativeLayout(view.getRootView());}),['java.lang.CharSequence']);
  const addView=method((view,child)=>nativeBody(()=>{addCalls++;if(addFailure)throw addFailure;children.push(child);nativeLayout(view.getRootView());}),['android.view.View']);
  const addWatcherMethod=method((view,listener)=>addTextWatcher(view,listener),['android.text.TextWatcher']);
  const removeWatcherMethod=method((view,listener)=>removeTextWatcher(view,listener),['android.text.TextWatcher']);
  const types={
    'android.app.Application$ActivityLifecycleCallbacks':{class:lifecycleContract()},'android.app.Activity':{class:{isInstance:value=>unwrap(value)?.kind==='activity'}},
    'android.content.ContextWrapper':{class:{isInstance:value=>typeof unwrap(value)?.getBaseContext==='function'}},
    'java.lang.ref.WeakReference':{$new:value=>{const ref={value:unwrap(value),get(){return objectFacade(this.value);},equals(other){return this===unwrap(other);}};weakRefs.push(ref);return ref;}},'java.lang.String':{$new:value=>value},
    'java.lang.System':{getProperties:()=>({get:key=>properties.get(key)??null,put:(key,value)=>properties.set(key,value),remove:key=>properties.delete(key)})},
    'android.widget.TextView':{class:{isInstance:view=>unwrap(view)?.kind==='text'},setText:{overload:()=>textSetter,overloads:[textSetter]},
      addTextChangedListener:{overload:()=>addWatcherMethod},removeTextChangedListener:{overload:()=>removeWatcherMethod}},
    'android.view.View':{class:{isInstance:value=>unwrap(value)?.kind==='text'||unwrap(value)?.kind==='group'},performClick:{overload:()=>perform},callOnClick:{overload:()=>call},
      onWindowFocusChanged:{overload:()=>focusChanged}},
    'android.view.ViewGroup':{class:{isInstance:view=>unwrap(view)?.kind==='group'},addView:{overload:()=>addView,overloads:[addView]}},
    'android.view.ViewTreeObserver':{class:{isInstance:value=>unwrap(value)?.kind==='observer'}},
    'android.content.res.Resources':{class:{isInstance:value=>unwrap(value)?.kind==='resources'}},
  };
  const contract=members=>({getMethods:()=>members,getDeclaredMethods:()=>members});
  if(observer!=='missing'&&observer!=='focus-only')types['android.view.ViewTreeObserver$OnGlobalLayoutListener']={class:contract([reflectedMethod('onGlobalLayout')])};
  if(observer!=='missing'&&observer!=='layout-only')types['android.view.ViewTreeObserver$OnWindowFocusChangeListener']={class:contract([reflectedMethod('onWindowFocusChanged',['boolean'])])};
  if(observer!=='missing')types['android.view.ViewTreeObserver$OnScrollChangedListener']={class:contract([reflectedMethod('onScrollChanged')])};
  if(textWatchers!=='missing')types['android.text.TextWatcher']={class:contract([
    reflectedMethod('beforeTextChanged',['java.lang.CharSequence','int','int','int']),
    reflectedMethod('onTextChanged',['java.lang.CharSequence','int','int','int']),reflectedMethod('afterTextChanged',['android.text.Editable'])])};
  const identities=new Map();let nextIdentity=0;
  if(identity!=='missing')types['java.lang.System'].identityHashCode=value=>{bridge.identity++;if(identity==='throws')throw new Error('identity hash unavailable');
    if(identity==='collision')return 7;value=unwrap(value);if(!identities.has(value))identities.set(value,++nextIdentity);return identities.get(value);};
  if(roots==='inspector')types['android.view.inspector.WindowInspector']={getGlobalWindowViews:{overload:()=>method(()=>rootList)}};
  if(roots==='manager')types['android.view.WindowManagerGlobal']={getInstance:{overload:()=>method(()=>({}))},getWindowViews:{overload:()=>method(()=>rootList)}};
  if(roots==='throws')types['android.view.inspector.WindowInspector']={getGlobalWindowViews:{overload:()=>method(()=>{throw new Error('window inspector unavailable');})}};
  const Java={use:name=>{assert.ok(types[name],'unknown Android ABI '+name);return types[name];},cast:value=>{bridge.casts++;return unwrap(value);},array:(_type,value)=>value,
    perform:fn=>fn(),scheduleOnMainThread:fn=>{bridge.main++;if(scheduleFailure)throw new Error('optional main scheduler unavailable');return queuedMain?setTimeout(fn,0):fn();},
    registerClass(spec){
      for(const implemented of spec.implements||[])for(const expected of implemented.class.getMethods()){
        const actual=spec.methods[expected.getName()];const choices=Array.isArray(actual)?actual:[actual];
        assert.ok(choices.some(method=>method && method.returnType===expected.getReturnType().getName() &&
          JSON.stringify(method.argumentTypes)===JSON.stringify(expected.getParameterTypes().map(type=>type.getName()))),
        'Frida missing interface implementation '+expected.getName());
      }
      return {$new:()=>{const value={equals(other){return this===other;}};
      for(const [name,type] of Object.entries(spec.fields||{}))value[name]={value:type==='boolean'?false:null};
      for(const [name,definition] of Object.entries(spec.methods)){
        const choices=Array.isArray(definition)?definition:[definition];
        value[name]=function(...args){const method=choices.find(choice=>choice.argumentTypes.length===args.length);
          assert.ok(method,'Unexpected callback overload '+name);return method.implementation.apply(this,args);};
      }
      value.getClass=()=>({getMethod:()=>({invoke:()=>value.hnublLabelsStop()})});return value;}};}};
  if(choose!=='missing')Java.choose=(_name,visitor)=>{chooseCalls++;if(choose==='throws')throw new Error("Cannot read properties of null (reading 'iterateOverInstancesOfClass')");visitor.onMatch(activity);visitor.onComplete();};
  function install(){return installRebootLabels({Java,app,state:(...args)=>{assert.equal(args.length,0);assert.equal(nativeDepth,0,'STATE called inside a native setter');
    assert.equal(callbackDepth,0,'STATE called synchronously inside an observer');requests.push('STATE');if(typeof reply==='function')return reply();return reply;},warn:(...args)=>warnings.push(args)});}
  function tick(ms=0){time+=ms;let count=0;for(;;){const entry=[...timers].find(([,task])=>task.at<=time);if(!entry)break;
    assert.ok(++count<2000,'observer refresh queue did not converge');timers.delete(entry[0]);entry[1].fn();}}
  return {install,activity,text,download,action,group,rootViews,perform,call,callbacks,timers,requests,warnings,tick,nativeCalls:()=>nativeCalls,
    setReply:value=>{reply=value;},chooseCalls:()=>chooseCalls,textWrites:()=>textWrites,addCalls:()=>addCalls,
    step(ms=0){time+=ms;const entry=[...timers].find(([,task])=>task.at<=time);if(!entry)return false;timers.delete(entry[0]);entry[1].fn();return true;},
    setSchedulerFailure:value=>{scheduleFailure=value;},setTextFailure:value=>{textFailure=value;},setAddFailure:value=>{addFailure=value;},properties,
    setText(view,value){return textSetter.implementation?textSetter.implementation.call(view,value):textSetter.call(view,value);},
    addView(view,child){return addView.implementation?addView.implementation.call(view,child):addView.call(view,child);},
    focus(view,value){return focusChanged.implementation?focusChanged.implementation.call(view,value):focusChanged.call(view,value);},focusCalls:()=>focusCalls,
    emitLayout(root=group){dispatch(root,'layouts');},observerFor,
    replaceObserver(root=group,{migrate=true,killOld=true}={}){
      const old=observerFor(root),next=newObserver();
      if(migrate)for(const kind of ['layouts','focus','scroll']){for(const listener of old[kind])next[kind].add(listener);old[kind].clear();}
      old.alive=!killOld;observers.set(root,next);return {old,next};
    },
    emitScroll(root=group){dispatch(root,'scroll');},bridge,body,
    scrollListenerCount(){return [...observers.values()].reduce((sum,value)=>sum+value.scroll.size,0);},
    watcherCount(view=null){return view==null?[...watchedTexts.values()].reduce((sum,value)=>sum+value.size,0):(watchedTexts.get(view)?.size||0);},
    watcherAdds:()=>watcherAdds,watcherRemoves:()=>watcherRemoves,watcherCallbacks:()=>watcherCallbacks,
    setResources({name=resourceName,text=resourceText,nextLocale=locale}={}){resourceName=name;resourceText=text;nativeRebootText=text;locale=nextLocale;configurationRevision++;},
    listenerCounts(){return [...observers.values()].reduce((sum,value)=>({layouts:sum.layouts+value.layouts.size,focus:sum.focus+value.focus.size}),{layouts:0,focus:0});},
    observerAdds:()=>observerAdds,observerRemoves:()=>observerRemoves,maxCallbackDepth:()=>maxCallbackDepth,maxNativeDepth:()=>maxNativeDepth,layoutEvents:()=>layoutEvents,
    nativeImplementations(){return {setText:textSetter.implementation,addView:addView.implementation,focus:focusChanged.implementation};},
    collectWeak(value){for(const ref of weakRefs)if(ref.value===value)ref.value=null;},
    close(){globalThis.setTimeout=originalTimers.setTimeout;globalThis.clearTimeout=originalTimers.clearTimeout;Date.now=originalTimers.now;}};
}
test('Android adapter resource scan/lifecycle/click restores native dispatch and only STATE',()=>{
  const f=androidFixture();try{
    const labels=f.install();f.tick();assert.equal(f.text.text,'继续流程');assert.equal(f.download.text,'Install');
    assert.equal(f.perform.implementation.call(f.text),true);assert.equal(f.call.implementation.call(f.text),false);
    assert.equal(f.nativeCalls(),2);assert.deepEqual(f.requests,['STATE']);
    f.callbacks[0].onActivityPaused(f.activity);assert.equal(f.text.text,'Restart now');assert.equal(f.timers.size,0);
    f.callbacks[0].onActivityResumed(f.activity);f.tick();assert.equal(f.text.text,'继续流程');
    labels.dispose();assert.equal(f.text.text,'Restart now');assert.equal(f.callbacks.length,0);assert.equal(f.perform.implementation,null);
  }finally{f.close();}
});
test('reinjection retires previous observer/timer and retains the native restart label',()=>{
  const f=androidFixture();try{
    const old=f.install();f.tick();assert.equal(f.text.text,'继续流程');const next=f.install();f.tick();
    assert.equal(old.inspect().stopped,true);assert.equal(f.callbacks.length,1);assert.equal(f.text.text,'继续流程');
    next.dispose();assert.equal(f.text.text,'Restart now');assert.equal(f.callbacks.length,0);assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('missing Java.choose still attaches to the already visible updater window',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',decorContext:true});try{
    const labels=f.install();f.tick();assert.equal(f.callbacks.length,1);assert.equal(labels.inspect().stopped,false);
    assert.equal(f.text.text,'继续流程');assert.deepEqual(f.requests,['STATE']);assert.equal(f.chooseCalls(),0);
    labels.dispose();assert.equal(f.callbacks.length,0);assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('a throwing heap walker is never called and cannot dispose lifecycle or click hooks',()=>{
  const f=androidFixture({choose:'throws',roots:'inspector'});try{
    const labels=f.install();f.tick();assert.equal(f.chooseCalls(),0);assert.equal(labels.inspect().stopped,false);
    assert.equal(f.callbacks.length,1);assert.equal(f.text.text,'继续流程');
    assert.equal(f.perform.implementation.call(f.text),true);assert.equal(f.nativeCalls(),1);
    assert.ok(f.requests.every(command=>command==='STATE'));labels.dispose();
  }finally{f.close();}
});
test('optional global root failure keeps lifecycle callbacks live for a later resumed screen',()=>{
  const f=androidFixture({choose:'throws',roots:'throws'});try{
    const labels=f.install();f.tick();assert.equal(labels.inspect().stopped,false);assert.equal(f.callbacks.length,1);
    assert.notEqual(f.perform.implementation,null);assert.ok(f.warnings.length>0);assert.equal(f.chooseCalls(),0);
    f.callbacks[0].onActivityResumed(f.activity);f.tick();assert.equal(f.text.text,'继续流程');
    labels.dispose();assert.equal(f.callbacks.length,0);assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('a DecorContext without an Activity still supplies a visible dialog window label',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',dialog:true,decorContext:true});try{
    const labels=f.install();f.tick();assert.equal(f.text.text,'继续流程');assert.equal(labels.inspect().stopped,false);
    f.setReply({ok:true,state:1});f.tick(2500);assert.equal(f.text.text,'立即重启');
    f.setReply(()=>{throw new Error('STATE endpoint unavailable');});f.tick(2500);assert.equal(f.text.text,'继续流程');
    assert.ok(f.requests.every(command=>command==='STATE'));labels.dispose();assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('Android parent click with child text restores only its label and dispatches native once',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',parentClick:true});try{
    const labels=f.install();f.tick();assert.equal(f.text.text,'继续流程');assert.equal(f.download.text,'Install');
    assert.equal(f.perform.implementation.call(f.action),true);assert.equal(f.call.implementation.call(f.action),false);
    assert.equal(f.nativeCalls(),2);assert.equal(f.text.text,'继续流程');assert.equal(f.download.text,'Install');
    assert.deepEqual(f.requests,['STATE']);labels.dispose();assert.equal(f.text.text,'Restart now');
  }finally{f.close();}
});
test('an unclickable parent without a native listener never becomes a reboot action',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',parentClick:true});try{
    f.action.isClickable=()=>false;f.action.hasOnClickListeners=()=>false;
    const labels=f.install();f.tick();assert.equal(f.text.text,'Restart now');assert.equal(f.requests.length,0);
    assert.equal(f.download.text,'Install');labels.dispose();assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('native layout notifications defer and coalesce refresh without replacing the setter',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',queuedMain:true});try{
    const labels=f.install();f.tick();assert.equal(f.text.text,'继续流程');const requests=f.requests.length;
    assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});
    f.setText(f.text,'Restart now');f.setText(f.text,'Restart now');
    assert.equal(f.requests.length,requests);assert.equal(f.text.text,'Restart now');
    f.tick(250);assert.equal(f.text.text,'继续流程');assert.equal(f.download.text,'Install');
    assert.ok(f.requests.length<=requests+1);assert.ok(f.requests.every(command=>command==='STATE'));
    f.setText(f.download,'Download');f.tick(250);assert.equal(f.download.text,'Download');
    assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});
    labels.dispose();f.tick();assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('a newly added native label is discovered asynchronously without changing download controls',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',queuedMain:true});try{
    const labels=f.install();f.tick();const added={...f.text,text:'Restart now'},requests=f.requests.length;
    f.addView(f.group,added);assert.equal(f.addCalls(),1);assert.equal(added.text,'Restart now');assert.equal(f.requests.length,requests);
    f.tick(250);assert.equal(added.text,'继续流程');assert.equal(f.text.text,'继续流程');assert.equal(f.download.text,'Install');
    f.setReply({ok:true,state:1});f.tick(2500);assert.equal(added.text,'立即重启');assert.equal(f.text.text,'立即重启');
    labels.dispose();f.tick();assert.equal(added.text,'Restart now');assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('post-resume native text rebinding is followed by a fresh marker label',()=>{
  const f=androidFixture({choose:'missing',roots:'manager'});try{
    const labels=f.install();f.tick();f.callbacks[0].onActivityPaused(f.activity);
    f.setReply({ok:true,state:1});f.callbacks[0].onActivityResumed(f.activity);
    f.setText(f.text,'Restart now');f.callbacks[0].onActivityPostResumed(f.activity);f.tick();assert.equal(f.text.text,'立即重启');
    f.callbacks[0].onActivityPaused(f.activity);f.setReply({ok:true,state:0});
    f.callbacks[0].onActivityResumed(f.activity);f.setText(f.text,'Restart now');f.callbacks[0].onActivityPostResumed(f.activity);f.tick();
    assert.equal(f.text.text,'继续流程');assert.equal(f.download.text,'Install');labels.dispose();assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('optional layout scheduling failure preserves native addView and permits later observer refresh',()=>{
  const f=androidFixture({choose:'missing',roots:'manager'});try{
    const labels=f.install();f.tick();const requests=f.requests.length;
    f.setSchedulerFailure(true);assert.doesNotThrow(()=>assert.equal(f.addView(f.group,{...f.download,text:'Download'}),undefined));
    assert.equal(f.addCalls(),1);assert.equal(f.requests.length,requests);f.tick(250);assert.ok(f.warnings.length>0);
    f.setSchedulerFailure(false);f.setText(f.text,'Restart now');f.tick(250);assert.equal(f.text.text,'继续流程');
    labels.dispose();f.tick();assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('queued refresh callback failure is diagnostic and does not leave refresh permanently queued',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',queuedMain:true});try{
    const labels=f.install();f.tick();f.setText(f.text,'Restart now');f.setSchedulerFailure(true);
    assert.doesNotThrow(()=>f.tick(250));assert.ok(f.warnings.length>0);
    f.setSchedulerFailure(false);f.setText(f.text,'Restart now');f.tick(250);assert.equal(f.text.text,'继续流程');
    labels.dispose();f.tick();assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('dispose during a scheduler failure still removes lifecycle registry and all optional hooks',()=>{
  const f=androidFixture({choose:'missing',roots:'manager'});try{
    const labels=f.install();f.tick();f.setSchedulerFailure(true);assert.doesNotThrow(()=>labels.dispose());
    assert.equal(f.callbacks.length,0);assert.equal(f.properties.size,0);assert.equal(f.perform.implementation,null);assert.equal(f.call.implementation,null);
    assert.equal(labels.inspect().stopped,true);const requests=f.requests.length;
    f.setSchedulerFailure(false);f.tick(10000);assert.equal(f.requests.length,requests);assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('native text and addView exceptions keep their exact identity and execute only once',()=>{
  const f=androidFixture({choose:'missing',roots:'manager'});try{
    const labels=f.install();f.tick();const requests=f.requests.length,textWrites=f.textWrites();
    const textFailure=new Error('native TextView error');f.setTextFailure(textFailure);
    assert.throws(()=>f.setText(f.text,'Restart now'),error=>error===textFailure);assert.equal(f.textWrites(),textWrites+1);
    f.setTextFailure(null);const addFailure=new Error('native addView error');f.setAddFailure(addFailure);
    assert.throws(()=>f.addView(f.group,f.download),error=>error===addFailure);assert.equal(f.addCalls(),1);
    assert.equal(f.requests.length,requests);f.setAddFailure(null);labels.dispose();f.tick();
  }finally{f.close();}
});
test('disabled parent action does not override a child restart text or poll the marker',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',parentClick:true});try{
    f.action.isEnabled=()=>false;const labels=f.install();f.tick();
    assert.equal(f.text.text,'Restart now');assert.equal(f.requests.length,0);assert.equal(f.download.text,'Install');labels.dispose();
  }finally{f.close();}
});
test('separate visible dialog and activity roots share read-only state and retire cleanly',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',decorContext:true});try{
    const dialogText={...f.text,text:'Restart now'};
    const dialogRoot={...f.group,getChildCount:()=>1,getChildAt:()=>dialogText};
    dialogRoot.getRootView=()=>dialogRoot;dialogText.getParent=()=>dialogRoot;dialogText.getRootView=()=>dialogRoot;
    f.group.hasWindowFocus=()=>false;f.rootViews.push(dialogRoot);
    const labels=f.install();f.tick();assert.equal(f.text.text,'继续流程');assert.equal(dialogText.text,'继续流程');
    assert.equal(f.requests.length,1);f.setReply({ok:true,state:1});f.tick(2500);
    assert.equal(f.text.text,'立即重启');assert.equal(dialogText.text,'立即重启');
    f.callbacks[0].onActivityPaused(f.activity);assert.equal(f.text.text,'Restart now');assert.equal(dialogText.text,'Restart now');
    const requests=f.requests.length;f.tick(10000);assert.equal(f.requests.length,requests);assert.equal(f.timers.size,0);
    labels.dispose();
  }finally{f.close();}
});
test('a root that gains focus after installation starts labels and loses all background reads on focus loss',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',focused:false,decorContext:true});try{
    const labels=f.install();f.tick();assert.equal(labels.inspect().active,false);assert.equal(f.requests.length,0);
    assert.equal(f.focus(f.group,true),undefined);f.tick(250);assert.equal(f.text.text,'继续流程');assert.equal(f.focusCalls(),1);
    const requests=f.requests.length;f.focus(f.group,false);f.tick(10000);
    assert.equal(labels.inspect().active,false);assert.equal(f.requests.length,requests);assert.equal(f.timers.size,0);
    f.setReply({ok:true,state:1});f.focus(f.group,true);f.tick(250);assert.equal(f.text.text,'立即重启');assert.equal(f.focusCalls(),3);
    assert.ok(f.requests.every(command=>command==='STATE'));labels.dispose();
  }finally{f.close();}
});
test('another activity pause stops a mismatched dialog owner only after all updater roots lose focus',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',decorContext:true});try{
    const labels=f.install();f.tick();const otherToken={equals(other){return this===other;}};
    const otherRoot={...f.group,getApplicationWindowToken:()=>otherToken};otherRoot.getRootView=()=>otherRoot;
    const otherActivity={...f.activity,getWindow:()=>({getDecorView:()=>otherRoot})};
    f.callbacks[0].onActivityPaused(otherActivity);assert.equal(labels.inspect().active,true);assert.equal(f.text.text,'继续流程');
    f.group._focused=false;f.callbacks[0].onActivityPaused(otherActivity);const requests=f.requests.length;f.tick(10000);
    assert.equal(labels.inspect().active,false);assert.equal(f.requests.length,requests);assert.equal(f.timers.size,0);
    labels.dispose();
  }finally{f.close();}
});
test('standard observers register once and never replace native text tree or focus methods',()=>{
  const f=androidFixture({choose:'missing',roots:'manager'});try{
    assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});
    const labels=f.install();f.tick();const counts=f.listenerCounts();
    assert.ok(counts.layouts>0);assert.ok(counts.focus>0);assert.ok(f.observerAdds()>0);
    assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});
    f.setText(f.text,'Restart now');f.tick(250);assert.equal(f.text.text,'继续流程');
    labels.dispose();f.tick();assert.deepEqual(f.listenerCounts(),{layouts:0,focus:0});assert.ok(f.observerRemoves()>0);
    assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});
  }finally{f.close();}
});
test('synchronous native layout storms stay deferred bounded and cannot recurse through self writes',()=>{
  const f=androidFixture({choose:'missing',roots:'manager'});try{
    const labels=f.install();f.tick();const requests=f.requests.length,writes=f.textWrites(),timers=f.timers.size;
    for(let i=0;i<100;i++)f.setText(f.text,'Restart now');
    assert.equal(f.requests.length,requests);assert.equal(f.text.text,'Restart now');
    assert.ok(f.timers.size<=timers+1,'observer events must coalesce one queued refresh');
    f.tick(250);assert.equal(f.text.text,'继续流程');assert.equal(f.download.text,'Install');
    assert.ok(f.textWrites()<=writes+102,'a self write must not keep producing label writes');
    assert.equal(f.maxCallbackDepth(),1);assert.equal(f.maxNativeDepth(),1);assert.ok(f.layoutEvents()>=100);
    assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});
    assert.ok(f.requests.length<=requests+1);labels.dispose();f.tick();assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('unavailable observer interfaces preserve lifecycle refresh and original clicks',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',observer:'missing'});try{
    const labels=f.install();f.tick();assert.equal(labels.inspect().stopped,false);assert.equal(f.callbacks.length,1);
    assert.equal(f.text.text,'继续流程');assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});
    f.setText(f.text,'Restart now');f.callbacks[0].onActivityPostResumed(f.activity);f.tick();assert.equal(f.text.text,'继续流程');
    assert.equal(f.perform.implementation.call(f.text),true);assert.equal(f.nativeCalls(),1);
    assert.ok(f.requests.every(command=>command==='STATE'));labels.dispose();f.tick();assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('a failing ViewTreeObserver provider never removes critical lifecycle or click observation',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',observer:'throws'});try{
    const labels=f.install();f.tick();assert.equal(labels.inspect().stopped,false);assert.equal(f.callbacks.length,1);
    assert.equal(f.text.text,'继续流程');assert.ok(f.warnings.length>0);
    f.callbacks[0].onActivityPaused(f.activity);f.setReply({ok:true,state:1});f.callbacks[0].onActivityResumed(f.activity);f.tick();
    assert.equal(f.text.text,'立即重启');assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});
    labels.dispose();f.tick();assert.equal(f.callbacks.length,0);assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('global layout remains functional when the optional focus observer interface is absent',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',observer:'layout-only'});try{
    const labels=f.install();f.tick();assert.equal(f.text.text,'继续流程');assert.ok(f.listenerCounts().layouts>0);
    f.setText(f.text,'Restart now');f.tick(250);assert.equal(f.text.text,'继续流程');
    f.callbacks[0].onActivityPaused(f.activity);f.setReply({ok:true,state:1});f.callbacks[0].onActivityResumed(f.activity);f.tick();
    assert.equal(f.text.text,'立即重启');labels.dispose();f.tick();assert.deepEqual(f.listenerCounts(),{layouts:0,focus:0});
  }finally{f.close();}
});
test('the focus observer alone can reacquire a window when the optional layout interface is absent',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',observer:'focus-only',focused:false});try{
    const labels=f.install();f.tick();assert.equal(f.listenerCounts().layouts,0);assert.ok(f.listenerCounts().focus>0);
    assert.equal(labels.inspect().active,false);f.focus(f.group,true);f.tick(250);assert.equal(f.text.text,'继续流程');
    f.setText(f.text,'Restart now');f.callbacks[0].onActivityPostResumed(f.activity);f.tick();assert.equal(f.text.text,'继续流程');
    assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});
    labels.dispose();f.tick();assert.deepEqual(f.listenerCounts(),{layouts:0,focus:0});
  }finally{f.close();}
});
test('lifecycle pause detaches all observers even when the UI restore scheduler fails',()=>{
  const f=androidFixture({choose:'missing',roots:'manager'});try{
    const labels=f.install();f.tick();assert.ok(f.listenerCounts().layouts>0);
    f.setSchedulerFailure(true);assert.doesNotThrow(()=>f.callbacks[0].onActivityPaused(f.activity));
    assert.equal(labels.inspect().active,false);assert.deepEqual(f.listenerCounts(),{layouts:0,focus:0});
    const requests=f.requests.length;f.setSchedulerFailure(false);f.tick(10000);assert.equal(f.requests.length,requests);
    f.setReply({ok:true,state:1});f.callbacks[0].onActivityResumed(f.activity);f.tick();assert.equal(f.text.text,'立即重启');
    labels.dispose();f.tick();assert.deepEqual(f.listenerCounts(),{layouts:0,focus:0});assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('a queued native layout after disposal does not read state or rewrite a retired window',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',queuedMain:true});try{
    const labels=f.install();f.tick();f.setText(f.text,'Restart now');
    labels.dispose();f.tick();const requests=f.requests.length,writes=f.textWrites();
    f.emitLayout();f.tick(10000);assert.equal(f.requests.length,requests);assert.equal(f.textWrites(),writes);
    assert.deepEqual(f.listenerCounts(),{layouts:0,focus:0});assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('weak root retirement and disposal remove listeners without reviving the collected owner',()=>{
  const f=androidFixture({choose:'missing',roots:'manager'});try{
    const labels=f.install();f.tick();const requests=f.requests.length;
    f.rootViews.length=0;f.collectWeak(f.group);f.emitLayout();f.tick(10000);
    assert.equal(f.requests.length,requests);labels.dispose();f.tick();
    assert.deepEqual(f.listenerCounts(),{layouts:0,focus:0});assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('reinjection replaces standard listeners instead of stacking native observers or replacements',()=>{
  const f=androidFixture({choose:'missing',roots:'manager'});try{
    const old=f.install();f.tick();const initial=f.listenerCounts();const next=f.install();f.tick();
    assert.equal(old.inspect().stopped,true);assert.equal(f.callbacks.length,1);assert.deepEqual(f.listenerCounts(),initial);
    assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});
    f.setText(f.text,'Restart now');f.tick(250);assert.equal(f.text.text,'继续流程');
    next.dispose();f.tick();assert.deepEqual(f.listenerCounts(),{layouts:0,focus:0});assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('each legacy semantic resource alias recognizes a non-regex localized restart label',()=>{
  for(const [resourceName,localized] of [['para_restart_now','再起動する'],['restart_now','Nu opnieuw starten'],
    ['reboot_now','Jetzt neu starten'],['restart_immediately','Reiniciar ahora'],['reboot_immediately','Riavvia adesso']]) {
    assert.equal(isManualRestartText(localized),false);
    const f=androidFixture({choose:'missing',roots:'manager',resourceName,resourceText:localized,nativeText:localized});try{
      const labels=f.install();f.tick();assert.equal(f.text.text,'继续流程',resourceName);
      assert.equal(f.perform.implementation.call(f.text),true);assert.equal(f.nativeCalls(),1);
      f.setReply({ok:true,state:1});f.tick(2500);assert.equal(f.text.text,'立即重启');
      labels.dispose();f.tick();assert.equal(f.text.text,localized);assert.equal(f.scrollListenerCount(),0);
    }finally{f.close();}
  }
});
test('resource cache avoids repeated lookup and invalidates on locale or configuration changes in the same Resources object',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',resourceName:'reboot_now',resourceText:'Reiniciar ahora',nativeText:'Reiniciar ahora'});try{
    const labels=f.install();f.tick();assert.equal(f.text.text,'继续流程');const identifiers=f.bridge.resourceIds;
    for(let i=0;i<3;i++){f.emitLayout();f.tick(250);assert.equal(f.text.text,'继续流程');}
    assert.equal(f.bridge.resourceIds,identifiers,'unchanged resource configuration must reuse its labels');
    f.setResources({text:'Jetzt neu starten',nextLocale:'de-DE'});f.setText(f.text,'Jetzt neu starten');f.tick(250);
    assert.equal(f.text.text,'继续流程');assert.ok(f.bridge.resourceIds>identifiers);
    const afterLocale=f.bridge.resourceIds;f.setResources({text:'Erneut starten',nextLocale:'de-DE'});f.setText(f.text,'Erneut starten');f.tick(250);
    assert.equal(f.text.text,'继续流程');assert.ok(f.bridge.resourceIds>afterLocale,'configuration change without locale change must invalidate');
    assert.equal(f.perform.implementation.call(f.text),true);assert.equal(f.nativeCalls(),1);
    labels.dispose();f.tick();assert.equal(f.text.text,'Erneut starten');
  }finally{f.close();}
});
test('large unrelated release notes never walk click ancestors and stable polls perform no UI bridge operations',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',bodyCount:800,parentClick:true,identity:'unique'});try{
    const labels=f.install();f.tick();assert.equal(f.text.text,'继续流程');assert.equal(f.bridge.bodyParentReads,0);
    assert.ok(f.bridge.bodyTextReads>=800);assert.ok(f.bridge.bodyTextReads<=1600,'initial discovery must not repeatedly traverse the same tree');
    const bridge={...f.bridge},requests=f.requests.length,writes=f.textWrites();
    for(let i=0;i<3;i++)f.tick(2500);
    assert.equal(f.requests.length,requests+3);assert.deepEqual(f.bridge,bridge);assert.equal(f.textWrites(),writes);
    f.setReply({ok:true,state:1});f.tick(2500);assert.equal(f.text.text,'立即重启');assert.equal(f.bridge.walks,bridge.walks);
    assert.equal(f.bridge.bodyTextReads,bridge.bodyTextReads);assert.equal(f.bridge.bodyParentReads,0);assert.equal(f.textWrites(),writes+1);
    assert.equal(f.download.text,'Install');assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});labels.dispose();f.tick();
  }finally{f.close();}
});
test('identity hash collisions unavailable hashes and fallback preserve candidate and parent click identity',()=>{
  for(const identity of ['unique','collision','missing','throws']) {
    const f=androidFixture({choose:'missing',roots:'manager',identity,bodyCount:30,parentClick:true});try{
      const labels=f.install();f.tick();assert.equal(f.text.text,'继续流程',identity);assert.equal(f.bridge.bodyParentReads,0,identity);
      assert.equal(f.download.text,'Install');assert.equal(f.perform.implementation.call(f.action),true);assert.equal(f.nativeCalls(),1);
      labels.dispose();f.tick();assert.deepEqual(f.listenerCounts(),{layouts:0,focus:0});assert.equal(f.scrollListenerCount(),0);
    }finally{f.close();}
  }
});
test('scroll and layout events perform zero scans until one trailing quiet discovery',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',bodyCount:300});try{
    const labels=f.install();f.tick();assert.ok(f.scrollListenerCount()>0);const walks=f.bridge.walks,bodyReads=f.bridge.bodyTextReads;
    for(let i=0;i<10;i++){f.emitScroll();f.emitLayout();if(i===4)labels.refresh(true);f.tick(50);}
    f.setText(f.text,'Restart now');f.emitScroll();f.emitLayout();
    assert.equal(f.bridge.walks,walks);assert.equal(f.bridge.bodyTextReads,bodyReads);assert.equal(f.text.text,'Restart now');
    f.tick(199);assert.equal(f.bridge.walks,walks);f.tick(1);
    assert.equal(f.bridge.walks,walks+1);assert.equal(f.text.text,'继续流程');assert.equal(f.bridge.bodyTextReads,bodyReads+300);
    f.tick(200);assert.equal(f.bridge.walks,walks+1,'no periodic discovery should follow an unchanged tree');
    assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});labels.dispose();f.tick();assert.equal(f.scrollListenerCount(),0);
  }finally{f.close();}
});
test('applied continue labels survive repeated optimized discovery and native install download reuse',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',parentClick:true,bodyCount:50});try{
    const labels=f.install();f.tick();assert.equal(f.text.text,'继续流程');
    for(let i=0;i<3;i++){f.emitLayout();f.tick(250);assert.equal(f.text.text,'继续流程');assert.equal(labels.inspect().tracked,1);}
    f.setText(f.text,'Install');f.tick(250);assert.equal(f.text.text,'Install');assert.equal(labels.inspect().tracked,0);
    f.setText(f.text,'Download');f.tick(250);assert.equal(f.text.text,'Download');
    f.setText(f.text,'Restart now');f.tick(250);assert.equal(f.text.text,'继续流程');assert.equal(labels.inspect().tracked,1);
    assert.equal(f.perform.implementation.call(f.action),true);assert.equal(f.nativeCalls(),1);assert.equal(f.bridge.bodyParentReads,0);
    labels.dispose();f.tick();
  }finally{f.close();}
});
test('disposal during active scroll cancels trailing discovery and removes every observer',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',bodyCount:100});try{
    const labels=f.install();f.tick();f.emitScroll();f.emitLayout();f.tick(100);labels.dispose();f.tick();
    const bridge={...f.bridge},requests=f.requests.length,writes=f.textWrites();f.emitScroll();f.emitLayout();f.tick(10000);
    assert.deepEqual(f.bridge,bridge);assert.equal(f.requests.length,requests);assert.equal(f.textWrites(),writes);
    assert.deepEqual(f.listenerCounts(),{layouts:0,focus:0});assert.equal(f.scrollListenerCount(),0);assert.equal(f.callbacks.length,0);assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('long scrolling defers every changed state render and publishes only the newest state at rest',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',bodyCount:100});try{
    const labels=f.install();f.tick();const before={...f.bridge},writes=f.textWrites(),requests=f.requests.length;
    for(let i=0;i<90;i++) {
      if(i===0||i===60)f.setReply({ok:true,state:1});if(i===30)f.setReply({ok:true,state:0});
      f.emitScroll();if(i%5===0)f.emitLayout();f.tick(100);
      assert.equal(f.bridge.walks,before.walks);assert.equal(f.bridge.textReads,before.textReads);assert.equal(f.textWrites(),writes);
      assert.equal(f.text.text,'继续流程');
    }
    assert.ok(f.requests.length>=requests+3,'long scroll must include real state polls');
    f.emitScroll();f.tick(199);assert.equal(f.bridge.walks,before.walks);f.tick(1);
    assert.equal(f.bridge.walks,before.walks+1);assert.equal(f.text.text,'立即重启');assert.equal(f.textWrites(),writes+1);
    assert.equal(f.bridge.bodyParentReads,0);labels.dispose();f.tick();assert.equal(f.scrollListenerCount(),0);
  }finally{f.close();}
});
test('a new scroll invalidates a quiet timer already queued on the main thread',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',queuedMain:true,bodyCount:50});try{
    const labels=f.install();f.tick();const walks=f.bridge.walks;
    f.emitLayout();assert.equal(f.step(200),true,'quiet JS task should enqueue its UI work');
    f.emitScroll();f.tick();assert.equal(f.bridge.walks,walks,'old queued UI work must not scan during new scroll');
    f.tick(199);assert.equal(f.bridge.walks,walks);f.tick(1);assert.equal(f.bridge.walks,walks+1);
    assert.equal(f.text.text,'继续流程');labels.dispose();f.tick();assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('pure scrolling with unchanged state never creates discovery or UI work even after scrolling stops',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',bodyCount:300});try{
    const labels=f.install();f.tick();const before={...f.bridge},requests=f.requests.length,writes=f.textWrites();
    for(let i=0;i<90;i++){f.emitScroll();f.tick(100);assert.deepEqual(f.bridge,before);assert.equal(f.textWrites(),writes);}
    assert.ok(f.requests.length>=requests+3);f.emitScroll();f.tick(1000);
    assert.deepEqual(f.bridge,before);assert.equal(f.textWrites(),writes);assert.equal(f.text.text,'继续流程');
    labels.dispose();f.tick();assert.equal(f.scrollListenerCount(),0);
  }finally{f.close();}
});
test('state changes during pure scroll defer only the latest tracked render and never discover the tree',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',bodyCount:300,parentClick:true});try{
    const labels=f.install();f.tick();const before={...f.bridge},requests=f.requests.length,writes=f.textWrites();
    for(let i=0;i<90;i++) {
      if(i===0||i===60)f.setReply({ok:true,state:1});if(i===30)f.setReply({ok:true,state:0});
      f.emitScroll();f.tick(100);assert.deepEqual(f.bridge,before);assert.equal(f.textWrites(),writes);
    }
    assert.ok(f.requests.length>=requests+3);f.emitScroll();f.tick(199);assert.deepEqual(f.bridge,before);f.tick(1);
    assert.equal(f.bridge.walks,before.walks);assert.equal(f.bridge.bodyTextReads,before.bodyTextReads);
    assert.equal(f.bridge.resourceIds,before.resourceIds);assert.equal(f.textWrites(),writes+1);assert.equal(f.text.text,'立即重启');
    assert.equal(f.download.text,'Install');labels.dispose();f.tick();assert.equal(f.scrollListenerCount(),0);
  }finally{f.close();}
});
test('a state write followed by a real asynchronous self layout performs bounded discovery and then becomes idle',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',bodyCount:100,layoutDelivery:'async'});try{
    const labels=f.install();f.tick();f.tick(16);f.tick(200);assert.equal(f.text.text,'继续流程');
    for(const state of [1,0]) {
      const walks=f.bridge.walks,writes=f.textWrites();f.setReply({ok:true,state});f.tick(2500);
      assert.equal(f.text.text,state===1?'立即重启':'继续流程');assert.equal(f.bridge.walks,walks);assert.equal(f.textWrites(),writes+1);
      f.tick(16);assert.equal(f.bridge.walks,walks);f.tick(199);assert.equal(f.bridge.walks,walks);f.tick(1);
      assert.equal(f.bridge.walks,walks+1,'one real layout pass may legitimately trigger one merged discovery');
      const bridge={...f.bridge},settledWrites=f.textWrites();f.tick(2500);f.tick(2500);
      assert.deepEqual(f.bridge,bridge);assert.equal(f.textWrites(),settledWrites);
    }
    assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});labels.dispose();f.tick(1000);
    assert.equal(f.scrollListenerCount(),0);assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('an asynchronous self layout coalesced with a new localized sibling still discovers the real candidate',()=>{
  const localized='Reiniciar ahora';
  const f=androidFixture({choose:'missing',roots:'manager',resourceName:'reboot_now',resourceText:localized,nativeText:localized,
    bodyCount:100,layoutDelivery:'async'});try{
    const labels=f.install();f.tick();f.tick(16);f.tick(200);const walks=f.bridge.walks;
    f.setReply({ok:true,state:1});f.tick(2500);assert.equal(f.text.text,'立即重启');
    const sibling={...f.text,text:localized},events=f.layoutEvents();assert.equal(isManualRestartText(localized),false);
    f.addView(f.group,sibling);assert.equal(sibling.text,localized);assert.equal(f.bridge.walks,walks);
    f.tick(16);assert.equal(f.layoutEvents(),events+1,'self write and native child insertion share one asynchronous layout pass');
    f.tick(200);assert.equal(f.bridge.walks,walks+1);assert.equal(labels.inspect().tracked,2);
    assert.equal(sibling.text,'立即重启');assert.equal(f.text.text,'立即重启');
    // Rendering the newly discovered sibling may itself enqueue one more real
    // layout. It must converge without another write or repeated discovery.
    const writes=f.textWrites();f.tick(16);f.tick(200);assert.equal(f.bridge.walks,walks+2);assert.equal(f.textWrites(),writes);
    const bridge={...f.bridge};f.tick(2500);f.tick(2500);assert.deepEqual(f.bridge,bridge);assert.equal(f.textWrites(),writes);
    assert.equal(f.perform.implementation.call(sibling),true);assert.equal(f.nativeCalls(),1);
    labels.dispose();f.tick(1000);assert.equal(f.scrollListenerCount(),0);assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('idle fallback discovers an untracked fixed-size restart without layout and stops discovery once correct',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',layoutDelivery:'none',bodyCount:100});try{
    f.text.text='Install';const labels=f.install();f.tick();assert.equal(labels.inspect().tracked,0);assert.equal(f.watcherCount(),0);
    const walks=f.bridge.walks;f.setText(f.text,'Restart now');assert.equal(f.bridge.walks,walks);assert.equal(f.layoutEvents(),0);
    f.tick(2500);f.tick(250);assert.equal(f.text.text,'继续流程');assert.equal(labels.inspect().tracked,1);assert.equal(f.watcherCount(f.text),1);
    const bridge={...f.bridge},writes=f.textWrites();for(let i=0;i<3;i++)f.tick(2500);
    assert.deepEqual(f.bridge,bridge);assert.equal(f.textWrites(),writes);assert.equal(f.bridge.walks,walks+1);
    labels.dispose();f.tick();assert.equal(f.watcherCount(),0);assert.equal(f.timers.size,0);
  }finally{f.close();}
});
test('a tracked localized fixed-size label is corrected through its watcher without tree discovery',()=>{
  const localized='Reiniciar ahora';
  const f=androidFixture({choose:'missing',roots:'manager',layoutDelivery:'none',parentClick:true,bodyCount:100,
    resourceName:'restart_immediately',resourceText:localized,nativeText:localized});try{
    const labels=f.install();f.tick();assert.equal(f.text.text,'继续流程');assert.equal(f.watcherCount(),1);
    assert.ok(f.body.every(view=>f.watcherCount(view)===0));const walks=f.bridge.walks,bodyReads=f.bridge.bodyTextReads,requests=f.requests.length;
    f.setText(f.text,localized);assert.equal(f.text.text,localized);assert.equal(f.requests.length,requests);assert.equal(f.layoutEvents(),0);
    f.tick(250);assert.equal(f.text.text,'继续流程');assert.equal(f.bridge.walks,walks);assert.equal(f.bridge.bodyTextReads,bodyReads);
    const bridge={...f.bridge},writes=f.textWrites();f.tick(2500);f.tick(2500);assert.deepEqual(f.bridge,bridge);assert.equal(f.textWrites(),writes);
    assert.equal(f.watcherCount(),1);assert.equal(f.perform.implementation.call(f.action),true);assert.equal(f.nativeCalls(),1);
    assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});labels.dispose();f.tick();assert.equal(f.watcherCount(),0);
  }finally{f.close();}
});
test('same-text callbacks and the module own watcher callbacks never enqueue repeated UI work',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',layoutDelivery:'none',bodyCount:100});try{
    const labels=f.install();f.tick();assert.equal(f.watcherCount(),1);const bridge={...f.bridge},writes=labels.inspect().writeCount;
    const callbacks=f.watcherCallbacks(),timers=f.timers.size;
    for(let i=0;i<100;i++)f.setText(f.text,'继续流程');
    assert.equal(f.watcherCallbacks(),callbacks+300,'all three real TextWatcher callbacks must be delivered');
    assert.deepEqual(f.bridge,bridge);assert.equal(f.timers.size,timers);f.tick(250);assert.deepEqual(f.bridge,bridge);assert.equal(labels.inspect().writeCount,writes);
    f.setText(f.text,'Restart now');f.tick(250);assert.equal(f.text.text,'继续流程');assert.equal(f.maxCallbackDepth(),1);assert.equal(f.maxNativeDepth(),1);
    const settled={...f.bridge},nativeWrites=f.textWrites();f.tick(1000);assert.deepEqual(f.bridge,settled);assert.equal(f.textWrites(),nativeWrites);
    labels.dispose();f.tick();assert.equal(f.watcherCount(),0);
  }finally{f.close();}
});
test('known candidate watcher preserves native install download text and recognizes restart again without layout',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',layoutDelivery:'none',bodyCount:100,parentClick:true});try{
    const labels=f.install();f.tick();const walks=f.bridge.walks;assert.equal(f.watcherCount(),1);
    f.setText(f.text,'Install');f.tick(250);assert.equal(f.text.text,'Install');assert.equal(labels.inspect().tracked,0);assert.equal(f.watcherCount(),1);
    const bridge={...f.bridge},callbacks=f.watcherCallbacks();for(let i=0;i<10;i++)f.setText(f.text,'Install');f.tick(250);
    assert.equal(f.watcherCallbacks(),callbacks+30);assert.deepEqual(f.bridge,bridge);
    f.setText(f.text,'Download');f.tick(250);assert.equal(f.text.text,'Download');assert.equal(labels.inspect().tracked,0);
    f.setText(f.text,'Restart now');f.tick(250);assert.equal(f.text.text,'继续流程');assert.equal(labels.inspect().tracked,1);
    assert.equal(f.bridge.walks,walks);assert.equal(f.watcherCount(),1);assert.equal(f.download.text,'Install');
    assert.equal(f.perform.implementation.call(f.action),true);assert.equal(f.nativeCalls(),1);labels.dispose();f.tick();assert.equal(f.watcherCount(),0);
  }finally{f.close();}
});
test('untracked fallback and local text correction both wait until scrolling stops',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',layoutDelivery:'none',bodyCount:100});try{
    f.text.text='Install';const labels=f.install();f.tick();const walks=f.bridge.walks;f.setText(f.text,'Restart now');
    for(let i=0;i<30;i++){f.emitScroll();f.tick(100);assert.equal(f.bridge.walks,walks);assert.equal(labels.inspect().tracked,0);}
    f.emitScroll();f.tick(199);assert.equal(f.bridge.walks,walks);f.tick(1);assert.equal(f.text.text,'继续流程');assert.equal(f.bridge.walks,walks+1);
    const correctWalks=f.bridge.walks;f.emitScroll();f.setText(f.text,'Restart now');const writes=f.textWrites();
    for(let i=0;i<10;i++){f.emitScroll();f.tick(100);assert.equal(f.text.text,'Restart now');assert.equal(f.textWrites(),writes);assert.equal(f.bridge.walks,correctWalks);}
    f.emitScroll();f.tick(199);assert.equal(f.text.text,'Restart now');f.tick(1);assert.equal(f.text.text,'继续流程');assert.equal(f.bridge.walks,correctWalks);
    labels.dispose();f.tick();assert.equal(f.watcherCount(),0);assert.equal(f.scrollListenerCount(),0);
  }finally{f.close();}
});
test('target-only typed text watchers are removed on pause disposal and reinjection',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',layoutDelivery:'none',bodyCount:100});try{
    const old=f.install();f.tick();assert.equal(f.watcherCount(),1);assert.equal(f.watcherCount(f.download),0);assert.ok(f.body.every(view=>f.watcherCount(view)===0));
    f.callbacks[0].onActivityPaused(f.activity);assert.equal(f.watcherCount(),0);
    f.callbacks[0].onActivityResumed(f.activity);f.tick();assert.equal(f.watcherCount(),1);
    const next=f.install();f.tick();assert.equal(old.inspect().stopped,true);assert.equal(f.watcherCount(),1);assert.equal(f.callbacks.length,1);
    f.setText(f.text,'Restart now');next.dispose();f.tick(10000);assert.equal(f.watcherCount(),0);assert.equal(f.callbacks.length,0);assert.equal(f.scrollListenerCount(),0);
    assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});assert.ok(f.watcherRemoves()>0);assert.equal(f.timers.size,0);
  }finally{f.close();}
});
function watcherFailureFallback(mode) {
  const f=androidFixture({choose:'missing',roots:'manager',layoutDelivery:'none',bodyCount:100,textWatchers:mode});try{
    const labels=f.install();f.tick();assert.equal(f.text.text,'继续流程');assert.equal(f.watcherCount(),0);assert.ok(f.warnings.length>0);
    const walks=f.bridge.walks,bodyReads=f.bridge.bodyTextReads;f.setText(f.text,'Restart now');assert.equal(f.layoutEvents(),0);
    f.tick(2500);f.tick(250);assert.equal(f.text.text,'继续流程');assert.equal(labels.inspect().tracked,1);
    for(let i=0;i<3;i++){f.tick(2500);f.tick(250);}
    assert.equal(f.bridge.walks,walks,'a known candidate with failed watcher must use local checks rather than whole-tree discovery');
    assert.equal(f.bridge.bodyTextReads,bodyReads);assert.equal(f.bridge.bodyParentReads,0);
    assert.equal(f.perform.implementation.call(f.text),true);assert.equal(f.nativeCalls(),1);assert.equal(f.callbacks.length,1);
    assert.deepEqual(f.nativeImplementations(),{setText:null,addView:null,focus:null});labels.dispose();f.tick();assert.equal(f.watcherCount(),0);assert.equal(f.timers.size,0);
  }finally{f.close();}
}
test('a missing TextWatcher interface falls back to local known-candidate checks without scanning body text',()=>{
  watcherFailureFallback('missing');
});
test('per-view watcher registration failure keeps local fixed-size correction and native click behavior',()=>{
  watcherFailureFallback('throws');
});

test('same active root resume refreshes STATE without rediscovering the view tree',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();const before=f.labels.inspect();
  const writes=f.writes.length;f.setReply({ok:true,state:1});
  for(let i=0;i<8;i++)f.labels.resume(f.owner);
  f.tick();const after=f.labels.inspect();
  assert.equal(after.discoveryCount,before.discoveryCount);assert.equal(after.sameRootResumeCount,before.sameRootResumeCount+8);
  assert.equal(after.stateReadCount,before.stateReadCount+1,'pending forced marker requests coalesce');
  assert.equal(f.native[0].text,'立即重启');assert.equal(f.writes.length,writes+1);f.labels.dispose();
});

test('pending root resume still coalesces and a genuinely new root performs discovery',()=>{
  const f=fixture(),main=[];f.p.main=fn=>main.push(fn);
  f.labels.resume(f.owner);f.labels.resume(f.owner);f.labels.resume(f.owner);
  while(main.length)main.shift()();f.tick();while(main.length)main.shift()();
  const before=f.labels.inspect();assert.equal(before.discoveryCount,1);assert.equal(before.pendingResumeCount,2);
  f.other.views.push(f.button('Restart now'));f.labels.resume(f.other);
  while(main.length)main.shift()();f.tick();while(main.length)main.shift()();
  assert.equal(f.labels.inspect().discoveryCount,before.discoveryCount+1);assert.equal(f.other.views[0].text,'继续流程');
  assert.equal(f.native[0].text,'立即重启','leaving the old root restores its native presentation');
  f.labels.dispose();while(main.length)main.shift()();
});

test('Android resumed and post-resumed callbacks reuse the already discovered native root',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',layoutDelivery:'none',bodyCount:800});try{
    const labels=f.install();f.tick();const before=labels.inspect(),bodyReads=f.bridge.bodyTextReads,walks=f.bridge.walks;
    f.setReply({ok:true,state:1});
    f.callbacks[0].onActivityResumed(f.activity);f.callbacks[0].onActivityPostResumed(f.activity);f.tick();
    assert.equal(labels.inspect().sameRootResumeCount,before.sameRootResumeCount+2);
    assert.equal(labels.inspect().discoveryCount,before.discoveryCount);assert.equal(f.bridge.walks,walks);
    assert.equal(f.bridge.bodyTextReads,bodyReads);assert.equal(f.text.text,'立即重启');
    labels.dispose();f.tick();
  }finally{f.close();}
});

test('disposal restores actual NativeCallback getters instead of comparing them to JS handlers',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',nativeCallbackGetter:true});try{
    const labels=f.install();f.tick();assert.equal(typeof f.perform.implementation,'function');assert.equal(typeof f.call.implementation,'function');
    assert.equal(f.perform.implementation.call(f.text),true);assert.equal(f.nativeCalls(),1);
    labels.dispose();f.tick();assert.equal(f.perform.implementation,null);assert.equal(f.call.implementation,null);
    assert.equal(f.callbacks.length,0);assert.equal(f.watcherCount(),0);
  }finally{f.close();}
});

test('disposal preserves a newer owners NativeCallback replacement',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',nativeCallbackGetter:true});try{
    const labels=f.install();f.tick();const newerHandler=()=> 'new-owner';f.perform.implementation=newerHandler;
    const newerCallback=f.perform.implementation;assert.notEqual(newerCallback,newerHandler);
    labels.dispose();f.tick();assert.equal(f.perform.implementation,newerCallback);assert.equal(f.perform.implementation(),'new-owner');
    assert.equal(f.call.implementation,null);f.perform.implementation=null;
  }finally{f.close();}
});

test('reinjection with native callback getters removes the old observers and retains one fresh click wrapper',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',nativeCallbackGetter:true});try{
    const old=f.install();f.tick();const first=f.perform.implementation;const next=f.install();f.tick();
    assert.equal(old.inspect().stopped,true);assert.notEqual(f.perform.implementation,first);assert.equal(f.callbacks.length,1);
    assert.equal(f.perform.implementation.call(f.text),true);assert.equal(f.nativeCalls(),1);
    next.dispose();f.tick();assert.equal(f.perform.implementation,null);assert.equal(f.call.implementation,null);
    assert.equal(f.watcherCount(),0);assert.equal(f.scrollListenerCount(),0);assert.equal(f.timers.size,0);
  }finally{f.close();}
});

test('dead observers migrated into the current live root remove only this modules listener identities',()=>{
  const f=androidFixture({choose:'missing',roots:'manager'});try{
    const labels=f.install();f.tick();const old=f.observerFor(f.group);
    const unrelated={layout:{},focus:{},scroll:{}};
    old.layouts.add(unrelated.layout);old.focus.add(unrelated.focus);old.scroll.add(unrelated.scroll);
    const {next}=f.replaceObserver();const removals=f.observerRemoves();
    assert.equal(old.isAlive(),false);assert.equal(next.layouts.size,2);
    labels.dispose();f.tick();
    assert.deepEqual([...next.layouts],[unrelated.layout]);assert.deepEqual([...next.focus],[unrelated.focus]);assert.deepEqual([...next.scroll],[unrelated.scroll]);
    assert.equal(f.observerRemoves(),removals+3);assert.ok(!f.warnings.some(args=>String(args).includes('not alive')));
    assert.equal(f.callbacks.length,0);assert.equal(f.timers.size,0);
  }finally{f.close();}
});

test('observer retirement cleans both still-live old and current observers and deduplicates identical roots',()=>{
  const f=androidFixture({choose:'missing',roots:'manager'});try{
    const labels=f.install();f.tick();const {old,next}=f.replaceObserver(f.group,{migrate:false,killOld:false});
    const unrelated={};next.layouts.add(unrelated);const removals=f.observerRemoves();
    labels.dispose();f.tick();assert.equal(old.layouts.size,0);assert.equal(old.focus.size,0);assert.equal(old.scroll.size,0);
    assert.deepEqual([...next.layouts],[unrelated]);assert.equal(f.observerRemoves(),removals+6);
  }finally{f.close();}
  const same=androidFixture({choose:'missing',roots:'manager'});try{
    const labels=same.install();same.tick();const removals=same.observerRemoves();labels.dispose();same.tick();
    assert.equal(same.observerRemoves(),removals+3,'same native observer is removed once per own listener');
  }finally{same.close();}
});

test('a collected root and unrecoverable dead observer deactivate callbacks and clear their weak roots',()=>{
  const f=androidFixture({choose:'missing',roots:'manager'});try{
    const labels=f.install();f.tick();const old=f.observerFor(f.group),listeners=[...old.layouts,...old.focus,...old.scroll];
    old.alive=false;f.collectWeak(f.group);const removals=f.observerRemoves();labels.dispose();f.tick();
    assert.equal(f.observerRemoves(),removals,'no illegal removal call is made on a known dead observer');
    for(const listener of listeners){assert.equal(listener.hnublActive.value,false);assert.equal(listener.hnublRoot.value,null);}
    assert.ok(!f.warnings.some(args=>String(args).includes('not alive')));assert.equal(f.callbacks.length,0);assert.equal(f.timers.size,0);
  }finally{f.close();}
});

test('same-root reacquisition removes migrated old callbacks before installing the current live observers',()=>{
  const f=androidFixture({choose:'missing',roots:'manager'});try{
    const labels=f.install();f.tick();const {next}=f.replaceObserver();const before=labels.inspect();
    f.callbacks[0].onActivityResumed(f.activity);f.tick();
    assert.equal(next.layouts.size,1);assert.equal(next.focus.size,1);assert.equal(next.scroll.size,1);
    assert.equal(labels.inspect().discoveryCount,before.discoveryCount);assert.equal(labels.inspect().sameRootResumeCount,before.sameRootResumeCount+1);
    labels.dispose();f.tick();assert.deepEqual(f.listenerCounts(),{layouts:0,focus:0});assert.equal(f.scrollListenerCount(),0);
    assert.ok(!f.warnings.some(args=>String(args).includes('not alive')));
  }finally{f.close();}
});

test('PostResumed discovers an untracked Install control that became restart without layout or a text watcher',()=>{
  const f=androidFixture({choose:'missing',roots:'manager',nativeText:'Install',layoutDelivery:'none',bodyCount:100});try{
    const labels=f.install();f.tick();const before=labels.inspect();assert.equal(before.tracked,0);assert.equal(f.watcherCount(),0);
    f.setText(f.text,'Restart now');assert.equal(f.layoutEvents(),0);f.callbacks[0].onActivityPostResumed(f.activity);f.tick();
    assert.equal(labels.inspect().discoveryCount,before.discoveryCount+1);assert.equal(labels.inspect().tracked,1);
    assert.equal(f.text.text,'继续流程');assert.equal(f.watcherCount(),1);labels.dispose();f.tick();
  }finally{f.close();}
});

test('same-root resume with a collected candidate discovers its replacement rather than taking the stable fast path',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();const before=f.labels.inspect();
  for(const ref of f.weak)if(ref.value===f.native[0])ref.value=null;
  const replacement=f.button('Restart now');f.owner.views=[replacement];f.labels.resume(f.owner);f.tick();
  assert.equal(f.labels.inspect().discoveryCount,before.discoveryCount+1);assert.equal(f.labels.inspect().tracked,1);
  assert.equal(replacement.text,'继续流程');f.labels.dispose();
});

test('same-root local validation retiring every remembered candidate falls back to one discovery of a new native control',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();const before=f.labels.inspect();
  f.native[0].text='Install';const replacement=f.button('Restart now');f.owner.views=[replacement];
  f.labels.resume(f.owner);f.tick();assert.equal(f.labels.inspect().discoveryCount,before.discoveryCount+1);
  assert.equal(replacement.text,'继续流程');assert.equal(f.native[0].text,'Install');f.labels.dispose();
});

for(const [name,change] of [['hidden',{visible:false}],['disabled',{enabled:false}],['no native listener',{listener:false}],['not clickable',{clickable:false}]])
test('same-root '+name+' old candidate falls back once to find a replacement without layout',()=>{
  const f=fixture();f.labels.resume(f.owner);f.tick();const before=f.labels.inspect();
  Object.assign(f.native[0],change);const replacement=f.button('Restart now');f.owner.views=[replacement];
  f.labels.resume(f.owner);f.tick();
  assert.equal(f.labels.inspect().discoveryCount,before.discoveryCount+1);
  assert.equal(replacement.text,'继续流程');assert.equal(f.labels.inspect().tracked,1);
  assert.equal(f.native[0].text,'立即重启','ineligible old controls keep native presentation');
  const stable=f.labels.inspect().discoveryCount;f.tick(2500);assert.equal(f.labels.inspect().discoveryCount,stable);
  f.labels.dispose();
});
console.log(JSON.stringify({passed:true,tests:passed,deviceOperations:false,markerMutations:false,slotOperations:false,rebootOperations:false}));
