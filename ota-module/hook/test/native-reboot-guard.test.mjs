import assert from 'node:assert/strict';
import {createNativeRebootGuardPolicy, installNativeRebootGuard, installNativeRebootGuardFixture} from '../src/native-reboot-guard.js';
let passed=0;
function test(name,body){body();passed++;console.log('PASS '+name);}
function fixture(options={}) {
  const calls=[],events=[],warnings=[],replies=[];let disposed=0,retained=0,descriptorReads=0,identityReads=0,scopeReads=0,scope=false;
  const type=name=>({getName:()=>name});
  const method=(name,parameters,result,modifiers=1)=>({getName:()=>name,getParameterTypes:()=>parameters.map(type),getReturnType:()=>type(result),getModifiers:()=>modifiers});
  const field=(name,fieldType,modifiers,value)=>({getName:()=>name,getType:()=>type(fieldType),getModifiers:()=>modifiers,setAccessible(value){assert.equal(value,true);},get:()=>value,getInt:()=>value});
  const binder={__binder:true,id:1,getInterfaceDescriptor(){descriptorReads++;return options.descriptor??'android.os.IPowerManager';},
    $isSameObject(other){identityReads++;if(options.identityError)throw Error('identity');return other?.id===1;}};
  const service=options.inheritedAsBinder?{inheritedInterfaceService:true}:{asBinder:()=>binder};
  let declaredInterfaceCalls=0;
  const signature=['int','android.os.Parcel','android.os.Parcel','int'];
  const native={returnType:{className:options.bridgeReturn||'boolean'},argumentTypes:signature.map(className=>({className})),
    call(receiver,...args){calls.push({receiver,args});if(options.nativeError)throw options.nativeError;return options.nativeResult??true;}};
  let replacement=options.existing||null;
  Object.defineProperty(native,'implementation',{configurable:true,get:()=>replacement,set(handler){
    // Real bridge getter is the generated NativeCallback; it is never the exact
    // JS handler supplied to the setter. Model that ownership contract.
    replacement=handler===null?null:function(...args){return handler.apply(this,args);};
  }});
  const binderClass={...type('android.os.BinderProxy'),getDeclaredMethods:()=>[method('transactNative',options.signature||signature,'boolean',options.notNative?1:257)],isInstance:value=>value?.__binder===true};
  const powerClass={...type('android.os.PowerManager'),getDeclaredFields:()=>[
    field('notService','java.lang.String',1,'unused'),
    ...Array.from({length:options.ambiguousService?2:1},(_,i)=>field('oemField'+i,'android.os.IPowerManager',1,service))]};
  const stubClass={...type('android.os.IPowerManager$Stub'),getDeclaredFields:()=>options.missingTransaction?[]:[field('TRANSACTION_reboot','int',9,options.code??41)]};
  const Stub={class:stubClass};if(!options.oldStub)Stub.getDefaultTransactionName=()=>options.transactionName??'reboot';
  const classes={
    'android.os.BinderProxy':{class:binderClass,transactNative:{overload(...args){assert.deepEqual(args,signature);return native;}}},
    'java.lang.reflect.Modifier':{isNative:value=>(value&256)!==0,isStatic:value=>(value&8)!==0},
    'android.content.Context':{POWER_SERVICE:{value:'runtime-power-service'}},
    'android.os.PowerManager':{class:powerClass},
    'android.os.IPowerManager':{class:{...type('android.os.IPowerManager'),getDeclaredMethods:()=>[method('reboot',options.rebootSignature||['boolean','java.lang.String','boolean'],options.rebootReturn||'void')]}},
    'android.os.IInterface':{asBinder:{overload(...args){assert.deepEqual(args,[]);return {argumentTypes:[],returnType:{className:options.asBinderReturn||'android.os.IBinder'},
      call(receiver){assert.equal(receiver,service);declaredInterfaceCalls++;return binder;}};}}},
    'android.os.IPowerManager$Stub':Stub,
    'android.os.IBinder':{FIRST_CALL_TRANSACTION:{value:1},LAST_CALL_TRANSACTION:{value:0xffffff},INTERFACE_TRANSACTION:{value:0x5f4e5446}},
    'java.lang.SecurityException':{$new(message){const error=Error(message);error.javaClass='java.lang.SecurityException';return error;}},
  };
  const Java={use(name){assert.ok(classes[name],'unexpected Java class '+name);return classes[name];},cast:value=>value,
    retain(value){retained++;return {...value,$dispose(){disposed++;}};}};
  const app={getSystemService:{overload(name){assert.equal(name,'java.lang.String');return {call(owner,key){assert.equal(owner,app);assert.equal(key,'runtime-power-service');return options.noManager?null:{};}};}}};
  const config={Java,app,allowed(){scopeReads++;const permit=scope;scope=false;return permit;},blocked:detail=>events.push(detail),warn:(...args)=>warnings.push(args)};
  function reply({broken=false}={}) {const log=[];const value={data:[91,92],position:9,
    setDataSize(size){log.push(['size',size]);if(broken)throw Error('reply');this.data=[];},
    setDataPosition(position){log.push(['position',position]);this.position=position;},
    writeNoException(){log.push(['noException']);this.data.push(0);},log};replies.push(value);return value;}
  const data=new Proxy({ownedByCaller:true},{get(target,key){if(key==='ownedByCaller')return true;throw Error('request Parcel inspected: '+String(key));}});
  return {Java,app,binder,native,config,calls,events,warnings,data,reply,permit:()=>{scope=true;},
    counts:()=>({disposed,retained,descriptorReads,identityReads,scopeReads,declaredInterfaceCalls}),
    install:()=>installNativeRebootGuard(config),invoke(receiver=binder,code=options.code??41,output=reply(),flags=0){return native.implementation.call(receiver,code,data,output,flags);}};
}
const isSecurity=error=>error.javaClass==='java.lang.SecurityException';
test('runtime interface type and transaction semantics bind an OEM-renamed field without fixed code',()=>{
  for(const code of [7,41,99,138]){const f=fixture({code}),guard=f.install();assert.equal(guard.ready,true);assert.equal(guard.inspect().code,code);assert.equal(f.counts().descriptorReads,1);guard.dispose();assert.equal(f.counts().disposed,1);}
});
test('inherited IInterface.asBinder is resolved from its declaring contract when the service facade omits it',()=>{
  const f=fixture({inheritedAsBinder:true}),guard=f.install();
  assert.equal(guard.ready,true);assert.equal(f.counts().declaredInterfaceCalls,1);assert.equal(f.counts().descriptorReads,1);
  assert.equal(f.invoke(),true);assert.equal(f.calls.length,0);f.permit();assert.equal(f.invoke(),true);assert.equal(f.calls.length,1);
  guard.dispose();assert.equal(f.counts().disposed,1);
});
test('old generated stubs without a transaction-name helper retain typed field semantics',()=>{
  const f=fixture({oldStub:true}),guard=f.install();assert.equal(guard.ready,true);guard.dispose();
});
test('ordinary transaction codes preserve receiver arguments return and do zero identity or scope work',()=>{
  for(const result of [true,false]){const f=fixture({nativeResult:result}),guard=f.install(),reply=f.reply();
    assert.equal(f.invoke(f.binder,10,reply,19),result);assert.deepEqual(f.calls[0],{receiver:f.binder,args:[10,f.data,reply,19]});
    assert.equal(f.counts().identityReads,0);assert.equal(f.counts().scopeReads,0);assert.equal(f.events.length,0);assert.deepEqual(reply.log,[]);assert.equal(f.counts().descriptorReads,1);guard.dispose();}
});
test('ordinary original exceptions propagate with exact identity and no denial reply',()=>{
  const error=Error('original'),f=fixture({nativeError:error}),guard=f.install(),reply=f.reply();
  assert.throws(()=>f.invoke(f.binder,10,reply),value=>value===error);assert.deepEqual(reply.log,[]);guard.dispose();
});
test('the same transaction number on another Binder stays native without consuming the original scope',()=>{
  const f=fixture(),guard=f.install(),other={__binder:true,id:2},reply=f.reply();f.permit();
  assert.equal(f.invoke(other,41,reply),true);assert.equal(f.calls[0].receiver,other);assert.equal(f.counts().scopeReads,0);assert.deepEqual(reply.log,[]);
  assert.equal(f.invoke(),true);assert.equal(f.calls.length,2);assert.equal(f.counts().scopeReads,1);guard.dispose();
});
test('unpermitted reboot sends zero IPC and returns a valid local void reply instead of false/defaultImpl',()=>{
  const f=fixture(),guard=f.install(),reply=f.reply();assert.equal(f.invoke(f.binder,41,reply),true);assert.equal(f.calls.length,0);
  assert.deepEqual(reply.data,[0]);assert.equal(reply.position,0);assert.deepEqual(reply.log,[['size',0],['position',0],['noException'],['position',0]]);
  assert.equal(f.events[0].reason,'no_synchronous_authorized_original');assert.equal(guard.inspect().blocked,1);assert.equal(f.counts().descriptorReads,1);guard.dispose();
});
test('one synchronous permission is forwarded once and a later call is independently blocked',()=>{
  const f=fixture(),guard=f.install();f.permit();assert.equal(f.invoke(),true);assert.equal(f.calls.length,1);assert.equal(f.events.length,0);
  assert.equal(f.invoke(),true);assert.equal(f.calls.length,1);assert.equal(f.events.length,1);assert.equal(guard.inspect().allowed,1);guard.dispose();
});
test('allowed native false result and native exception remain the actual original outcome',()=>{
  const f=fixture({nativeResult:false}),guard=f.install(),reply=f.reply();f.permit();assert.equal(f.invoke(f.binder,41,reply),false);assert.deepEqual(reply.log,[]);guard.dispose();
  const error=Error('allowed original'),g=fixture({nativeError:error}),other=g.install();g.permit();assert.throws(()=>g.invoke(),value=>value===error);other.dispose();
});
test('null and one-way denied calls throw locally and never return false or send a transaction',()=>{
  for(const flags of [0,1,17]){const f=fixture(),guard=f.install();assert.throws(()=>f.invoke(f.binder,41,flags===0?null:f.reply(),flags),isSecurity);assert.equal(f.calls.length,0);guard.dispose();}
});
test('a reply failure throws locally and never falls through to the original transaction',()=>{
  const f=fixture(),guard=f.install();assert.throws(()=>f.invoke(f.binder,41,f.reply({broken:true})),isSecurity);assert.equal(f.calls.length,0);assert.ok(f.warnings.length);guard.dispose();
});
test('scope and diagnostic callback exceptions fail closed without an IPC retry',()=>{
  const f=fixture();f.config.allowed=()=>{throw Error('scope');};f.config.blocked=()=>{throw Error('trace');};f.config.warn=()=>{throw Error('warning');};
  const guard=f.install();assert.equal(guard.ready,true);assert.equal(f.invoke(),true);assert.equal(f.calls.length,0);guard.dispose();
});
test('identity comparison failure blocks a candidate code and reports its own reason',()=>{
  const f=fixture({identityError:true}),guard=f.install();assert.equal(f.invoke(),true);assert.equal(f.calls.length,0);assert.equal(f.events[0].reason,'binder_identity_error');guard.dispose();
});
test('truthy scope values are not boolean proof',()=>{
  for(const value of [1,'true',{},null]){let forwarded=0,denied=0;const guard=createNativeRebootGuardPolicy({code:1,sameBinder:()=>true,allowed:()=>value,blocked:()=>{},deny:()=>{denied++;return true;}});
    assert.equal(guard.dispatch({},[1,{},{}],()=>{forwarded++;}),true);assert.equal(forwarded,0);assert.equal(denied,1);}
});
test('ABI metadata failure leaves the native method untouched and requires legacy protection',()=>{
  for(const options of [{notNative:true},{asBinderReturn:'java.lang.Object'},{bridgeReturn:'int'},{signature:['int']},{descriptor:'other.Interface'},{ambiguousService:true},{missingTransaction:true},{transactionName:'shutdown'},{rebootReturn:'boolean'},{rebootSignature:['java.lang.String']},{code:0},{code:0x1000000},{noManager:true}]){
    const f=fixture(options),guard=f.install();assert.equal(guard.ready,false,JSON.stringify(options));assert.equal(f.native.implementation,null);assert.ok(guard.inspect().error);assert.ok(f.warnings.length);guard.dispose();assert.equal(f.calls.length,0);}
});
test('an existing native replacement is retained rather than bypassed or stacked',()=>{
  const existing=()=>false,f=fixture({existing}),guard=f.install();assert.equal(guard.ready,false);assert.equal(f.native.implementation,existing);guard.dispose();assert.equal(f.native.implementation,existing);
});

test('optional native code filtering retains the exact Java policy and counts native ordinary traffic',()=>{
  const f=fixture();let code,handler,active=true;
  f.native.installNativeTransactionCodeFilter=value=>{code=value;handler=f.native.implementation;
    return {mode:'native_code_filter',inspect:()=>({ready:active,native_fastpass:30000,native_matched:2})};};
  const guard=f.install();assert.equal(code,41);assert.strictEqual(f.native.implementation,handler);
  assert.equal(guard.inspect().native_filter_ready,true);assert.equal(guard.inspect().ordinary,30000);
  assert.equal(f.invoke(),true);assert.equal(f.calls.length,0);f.permit();assert.equal(f.invoke(),true);assert.equal(f.calls.length,1);
  guard.dispose();active=false;assert.equal(f.native.implementation,null);assert.equal(guard.inspect().native_filter_ready,false);
});

test('native filter preparation failure leaves a fully working Java guard',()=>{
  const f=fixture();f.native.installNativeTransactionCodeFilter=()=>{throw Error('optional C capability unavailable');};
  const guard=f.install();assert.equal(guard.ready,true);assert.equal(guard.inspect().native_filter_ready,false);
  assert.match(guard.inspect().native_filter_error,/C capability/);assert.equal(f.invoke(),true);assert.equal(f.calls.length,0);
  f.permit();assert.equal(f.invoke(),true);assert.equal(f.calls.length,1);guard.dispose();assert.equal(f.native.implementation,null);
});
test('disposal restores only this replacement releases the owned binder and is idempotent',()=>{
  const f=fixture(),guard=f.install();assert.equal(guard.ready,true);guard.dispose();guard.dispose();assert.equal(guard.ready,false);assert.equal(f.native.implementation,null);assert.equal(f.counts().disposed,1);
  const g=fixture(),other=g.install(),newOwner=()=>false;g.native.implementation=newOwner;const ownedCallback=g.native.implementation;
  assert.notStrictEqual(ownedCallback,newOwner);other.dispose();assert.equal(g.native.implementation,ownedCallback);assert.equal(g.counts().disposed,1);
});
test('a failed restoration retains the installed endpoint identity until a successful retry',()=>{
  const f=fixture(),guard=f.install();let value=f.native.implementation,fail=true;
  Object.defineProperty(f.native,'implementation',{get:()=>value,set(next){if(fail)throw Error('restore');value=next;},configurable:true});
  assert.equal(guard.dispose(),false);assert.equal(guard.ready,true);assert.equal(f.counts().disposed,0);
  assert.equal(f.invoke(),true);assert.equal(f.calls.length,0);
  fail=false;assert.equal(guard.dispose(),true);assert.equal(guard.ready,false);assert.equal(value,null);assert.equal(f.counts().disposed,1);
});
test('read-only warmed-caller fixture accepts only runtime INTERFACE_TRANSACTION',()=>{
  const f=fixture(),guard=installNativeRebootGuardFixture({...f.config,binder:f.binder,targetCode:0x5f4e5446});assert.equal(guard.ready,true);assert.equal(guard.inspect().mode,'descriptor-fixture');
  assert.equal(f.invoke(f.binder,0x5f4e5446),true);assert.equal(f.calls.length,0);f.permit();assert.equal(f.invoke(f.binder,0x5f4e5446),true);assert.equal(f.calls.length,1);guard.dispose();
  for(const targetCode of [1,41,0x5f4e5445]){const g=fixture(),bad=installNativeRebootGuardFixture({...g.config,binder:g.binder,targetCode});assert.equal(bad.ready,false);assert.equal(g.native.implementation,null);}
});
console.log(JSON.stringify({passed:true,tests:passed,deviceOperations:false,binderTransactions:false,markerOperations:false,slotOperations:false,rebootOperations:false}));
