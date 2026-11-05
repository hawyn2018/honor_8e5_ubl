import assert from 'node:assert/strict';
import {createProcessMethodCache,methodIdentity} from '../src/method-resolution-cache.js';
let passed=0;
function test(name,run){run();passed++;console.log('PASS '+name);}
function fixture() {
  const counts={names:0,parameters:0,returns:0,classes:0,declared:0,overloads:0,handles:0};
  const specs=Array.from({length:120},(_,i)=>({class_name:'Fixture',method_name:'m'+i,parameter_types:['int'],return_type:'void'}));
  const reflect=spec=>({getName(){counts.names++;return spec.method_name;},getParameterTypes(){counts.parameters++;return spec.parameter_types.map(name=>({getName:()=>name}));},
    getReturnType(){counts.returns++;return {getName:()=>spec.return_type};},getModifiers:()=>0});
  const reflected=specs.map(reflect),type={class:{getDeclaredMethods(){counts.declared++;return reflected;},getDeclaredConstructors:()=>[]}};
  for(const spec of specs) type[spec.method_name]={overload(...args){counts.overloads++;assert.deepEqual(args,spec.parameter_types);return {
    returnType:{className:spec.return_type},get handle(){counts.handles++;return {isNull:()=>false,toString:()=>spec.method_name};}};}};
  const Java={use(name){if(name==='java.lang.reflect.Proxy')return {isProxyClass:()=>false};assert.equal(name,'Fixture');counts.classes++;return type;}};
  return {counts,specs,type,Java,reflect,reflected};
}
test('large class names and signatures are decoded once and binding reuses process handles',()=>{
  const f=fixture(),cache=createProcessMethodCache(f.Java);
  for(const spec of f.specs)cache.resolve(spec,{validateBody:true,requireHandle:true});
  const initial={...f.counts};
  for(let cycle=0;cycle<10;cycle++)for(const spec of f.specs)cache.resolve(spec,{validateBody:true,requireHandle:true});
  assert.deepEqual(f.counts,initial);
  assert.equal(initial.declared,1);assert.equal(initial.names,120);assert.equal(initial.parameters,120);assert.equal(initial.handles,120);
  assert.equal(cache.inspect().method_hits,1200);assert.equal(cache.inspect().scope,'process-classloader');
});
test('an unselected overload return type stays unresolved',()=>{
  const f=fixture(),selected=f.specs[0];
  f.reflected.push({...f.reflect({...selected,parameter_types:['java.lang.String']}),getReturnType(){throw Error('missing optional result class');}});
  assert.doesNotThrow(()=>createProcessMethodCache(f.Java).resolve(selected,{validateBody:true,requireHandle:true}));
});
test('independent classloader cache resolves fresh runtime addresses',()=>{
  const f=fixture(),a=createProcessMethodCache(f.Java),b=createProcessMethodCache(f.Java);
  const left=a.resolve(f.specs[0],{requireHandle:true}),right=b.resolve(f.specs[0],{requireHandle:true});
  assert.notStrictEqual(left,right);assert.notStrictEqual(left.handle,right.handle);assert.equal(f.counts.overloads,2);
});
test('binding-first lookups still validate the original body exactly once',()=>{
  const f=fixture(),cache=createProcessMethodCache(f.Java),spec=f.specs[0];
  const first=cache.resolve(spec);assert.equal(f.counts.declared,0);
  assert.strictEqual(cache.resolve(spec,{validateBody:true,requireHandle:true}),first);assert.equal(f.counts.declared,1);
  cache.resolve(spec,{validateBody:true,requireHandle:true});assert.equal(f.counts.declared,1);
});
test('ambiguous original signatures reject instead of reusing a guessed method',()=>{
  const f=fixture();f.reflected.push(f.reflect(f.specs[0]));
  assert.throws(()=>createProcessMethodCache(f.Java).resolve(f.specs[0],{validateBody:true}),/ambiguous/);
});
test('native or abstract bodies remain excluded before mutation',()=>{
  for(const flags of [0x100,0x400]){const f=fixture();f.reflected[0].getModifiers=()=>flags;
    assert.throws(()=>createProcessMethodCache(f.Java).resolve(f.specs[0],{validateBody:true}),/native\/abstract\/unresolved/);}
});
test('constructors and array descriptors keep their exact identity',()=>{
  assert.equal(methodIdentity({class_name:'nested.Type',method_name:'<init>',parameter_types:['[Ljava.lang.String;','[[I'],return_type:'void'}),'Lnested/Type;-><init>([Ljava/lang/String;[[I)V');
  const f=fixture(),spec={class_name:'Fixture',method_name:'<init>',parameter_types:[],return_type:'void'};
  f.type.class.getDeclaredConstructors=()=>[f.reflect(spec)];f.type.$init={overload:()=>({returnType:{className:'void'},handle:{isNull:()=>false,toString:()=>'<init>'}})};
  assert.doesNotThrow(()=>createProcessMethodCache(f.Java).resolve(spec,{validateBody:true,requireHandle:true}));
});
console.log(JSON.stringify({passed:true,tests:passed,deviceOperations:false,persistentPointers:false}));
