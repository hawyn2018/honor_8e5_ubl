import assert from 'node:assert/strict';
import {installCacheVersionQuery} from '../src/cache-query.js';

// Actual adapter + actual pure policy, mocked Java/Cursor ABI only. No Android
// DB, update service, storage, marker, network or device operation is executed.
const currentVersion = 'DEVICE 11.0.0.170(SP2C00E170R1P1)';
const columns = {id:'_id',state:'fw_state',progress:'fw_update_progress',metadata:'fw_metadata',
  version:'fw_version',package_type:'fw_package_type'};
const types = ['android.net.Uri','[Ljava.lang.String;','java.lang.String','[Ljava.lang.String;','java.lang.String'];
const metadata = target => JSON.stringify({blVersionInfo:{targetBlVersionNumber:target}});
const row = (id='7', extra={}) => ({_id:id,fw_state:0,fw_update_progress:0,fw_metadata:metadata(currentVersion),...extra});
const uri = value => ({toString:()=>value});
const cacheUri = 'content://fixture.native.firmware/cache';
const makeHook = () => ({class_name:'FixtureAccessor',method_name:'query',parameter_types:[...types],
  return_type:'android.database.Cursor',is_static:false,receiver_class:'FixtureCacheReceiver',columns:{...columns},
  uri_source:{class_name:'FixtureUris',field_name:'CACHE',field_type:'android.net.Uri'},
  current_version_source:{fallback_supplier:{class_name:'FixtureVersion',method_name:'current'}},pending_states:[0]});

function fixture(options={}) {
  const calls=[],writes=[],warnings=[],events=[],cursors=[],lookups=[],binding=[];
  const hook=makeHook();if(options.mutateHook)options.mutateHook(hook);
  const target=uri(cacheUri),receiver={isCacheReceiver:true},stats={};
  let force=options.force??false,version=options.version??currentVersion,threadId=1;
  const projection=Object.freeze(['fw_version','fw_metadata']);const selectionArgs=Object.freeze(['0','2']);
  const args=[target,projection,'fw_state=? OR fw_package_type=?',selectionArgs,'modified DESC'];
  const nativeCursor={kind:'native',position:3,closeCount:0,close(){this.closeCount++;},getCount:()=>31};
  function cursor() {
    const rows=typeof options.rows==='function'?options.rows():options.rows??[row()];
    const names=options.cursorColumns??Object.values(columns);let position=-1;
    const value=index=>{if(options.readError)throw options.readError;return rows[position][names[index]];};
    const result={kind:'metadata',closeCount:0,readNames:[],getColumnIndex:name=>names.indexOf(name),
      moveToFirst(){if(options.moveError)throw options.moveError;position=0;return rows.length>0;},
      moveToNext(){position++;return position<rows.length;},
      isNull(index){return value(index)==null;},getString(index){this.readNames.push(names[index]);const v=value(index);return v==null?null:String(v);},
      // Matches Android SQLiteCursor: getInt on SQL NULL is zero. State is
      // separately guarded by isNull; progress follows the native row mapper.
      getInt(index){this.readNames.push(names[index]);const v=value(index);return v==null?0:Number(v);},
      close(){this.closeCount++;if(options.closeError)throw options.closeError;}};
    cursors.push(result);return result;
  }
  const isSnapshot=values=>values[1]===null&&values[2]===null&&values[3]===null&&values[4]===null;
  const overload={implementation:null,call(self,...values){
    const snapshot=isSnapshot(values);const entry={receiver:self,args:values,snapshot};calls.push(entry);
    if(options.onNative)options.onNative(entry,api);
    if(snapshot){if(options.snapshotError)throw options.snapshotError;return options.nullSnapshot?null:cursor();}
    if(options.nativeError)throw options.nativeError;
    if(options.filteredError && String(values[2]).includes(' NOT IN '))throw options.filteredError;
    return nativeCursor;
  }};
  const field={setAccessible:value=>binding.push(['accessible',value]),get(self){assert.equal(self,null);return options.nullUri?null:target;}};
  const supplier={overload(...parameters){assert.deepEqual(parameters,[]);return {call(self){assert.equal(self,supplierOwner);
    if(options.supplierError)throw options.supplierError;return typeof version==='function'?version():version;}};}};
  const supplierOwner={current:supplier};
  const owner={query:{overload(...parameters){assert.deepEqual(parameters,types);binding.push(['overload',parameters]);return overload;}}};
  for(const name of ['insert','update','delete','bulkInsert','execSQL','replace'])owner[name]=(...values)=>{writes.push({name,values});throw new Error('Unexpected DB mutation '+name);};
  const known={FixtureAccessor:owner,FixtureCacheReceiver:{class:{isInstance:value=>{
    if(options.receiverError)throw options.receiverError;return value!=null&&value.isCacheReceiver===true;}}},
    FixtureUris:{class:{getDeclaredField(name){assert.equal(name,'CACHE');if(options.fieldError)throw options.fieldError;return field;}}},
    FixtureVersion:supplierOwner,'java.lang.Thread':{currentThread(){if(options.threadError)throw options.threadError;return {getId:()=>threadId};}}};
  const Java={use(name){lookups.push(name);if(options.missingType===name||!known[name])throw new Error('Missing Java ABI '+name);return known[name];}};
  const api={hook,args,receiver,target,projection,selectionArgs,nativeCursor,calls,writes,warnings,events,cursors,stats,lookups,overload,options,
    install(){return installCacheVersionQuery({Java,hook,forceFullPackage(){if(options.forceError)throw options.forceError;return typeof force==='function'?force():force;},
      warn(...values){warnings.push(values);if(options.warnError)throw options.warnError;},
      telemetry(value){events.push(value);if(options.telemetryError)throw options.telemetryError;},stats});},
    invoke(values=args,self=receiver){return overload.implementation.apply(self,values);},
    setForce:value=>{force=value;},setVersion:value=>{version=value;},setThread:value=>{threadId=value;},
    snapshots:()=>calls.filter(call=>call.snapshot),nativeCalls:()=>calls.filter(call=>!call.snapshot)};
  if(options.install!==false)api.handle=api.install();return api;
}

function sameArguments(actual,original) {
  assert.equal(actual.length,original.length);
  for(let i=0;i<original.length;i++)assert.strictEqual(actual[i],original[i],'argument identity '+i);
}
function originalOnly(f,values=f.args,self=f.receiver) {
  assert.strictEqual(f.invoke(values,self),f.nativeCursor);assert.equal(f.calls.length,1);
  sameArguments(f.calls[0].args,values);assert.strictEqual(f.calls[0].receiver,self);assert.equal(f.cursors.length,0);
}
function filtered(f,ids,values=f.args) {
  assert.strictEqual(f.invoke(values),f.nativeCursor);assert.equal(f.snapshots().length,1);assert.equal(f.nativeCalls().length,1);
  const scan=f.snapshots()[0],native=f.nativeCalls()[0];sameArguments(scan.args,[values[0],null,null,null,null]);
  for(const index of [0,1,3,4])assert.strictEqual(native.args[index],values[index]);
  const selection=String(native.args[2]),match=/"_id"\s+NOT\s+IN\s*\(([^)]*)\)/i.exec(selection);
  assert.ok(match,'Numeric native row exclusion');assert.deepEqual(match[1].split(',').map(value=>value.trim()),ids);
  if(values[2]!=null&&String(values[2]).trim()!=='')assert.ok(selection.startsWith('('+String(values[2])+') AND ('),'original OR precedence retained');
  assert.equal(f.cursors[0].closeCount,1);assert.equal(f.nativeCursor.closeCount,0);assert.equal(f.nativeCursor.position,3);
  assert.equal(f.writes.length,0);return native;
}
function preserved(f) {
  assert.strictEqual(f.invoke(),f.nativeCursor);assert.equal(f.snapshots().length,1);assert.equal(f.nativeCalls().length,1);
  sameArguments(f.nativeCalls()[0].args,f.args);assert.equal(f.cursors[0].closeCount,1);assert.equal(f.writes.length,0);
}
let passed=0;const failed=[];
function test(name,run){try{run();passed++;console.log('PASS '+name);}catch(error){failed.push({name,error:String(error.stack||error)});console.error('FAIL '+name+'\n'+error.stack);}}

test('binds exact instance Cursor ABI, static URI field and complete-version supplier',()=>{
  const f=fixture();assert.equal(typeof f.overload.implementation,'function');assert.deepEqual(f.calls,[]);assert.ok(f.handle);
  assert.ok(f.lookups.includes('FixtureCacheReceiver'));assert.ok(f.lookups.includes('FixtureVersion'));
});
test('same/older cache adds only numeric ID selection and returns the actual native Cursor',()=>{
  const rows=[row('7'),row('9007199254740993123',{fw_metadata:metadata('11.0.0.169(SP99)')})];const before=JSON.stringify(rows);
  const f=fixture({rows});filtered(f,['7','9007199254740993123']);assert.equal(JSON.stringify(rows),before);
  assert.equal(f.stats.cacheLatest,1);assert.equal(f.events[0].event,'cache_version_filter');
});
test('NULL/blank caller selection changes only selection, never projection/binds/order',()=>{
  for(const selection of [null,'','  ']){const f=fixture();const values=[...f.args];values[2]=selection;filtered(f,['7'],values);}
});
test('native count projection is retained; caller reads its real Cursor result',()=>{
  const f=fixture();const projection=Object.freeze(['count(*) AS size']);const values=[...f.args];values[1]=projection;
  filtered(f,['7'],values);assert.equal(f.nativeCursor.getCount(),31);assert.strictEqual(f.nativeCalls()[0].args[1],projection);
});
test('real cache NULL progress uses original Cursor.getInt(NULL)=0 semantics',()=>{
  const f=fixture({rows:[row('1',{fw_update_progress:null}),row('2',{fw_update_progress:null}),row('3',{fw_update_progress:null})]});
  filtered(f,['1','2','3']);assert.equal(f.cursors[0].readNames.filter(name=>name===columns.progress).length,3);
});
test('NULL state stays unknown even when the SQLite integer accessor would return zero',()=>{
  preserved(fixture({rows:[row('1',{fw_state:null,fw_update_progress:null})]}));
});
test('force-full is checked live and directly calls the original query without metadata I/O',()=>{
  const f=fixture({force:true});originalOnly(f);assert.equal(f.stats.cacheLatest,undefined);
  f.calls.length=0;f.setForce(false);filtered(f,['7']);
});
test('wrong URI, null URI and different receiver pass through all argument identities',()=>{
  for(const values of [uri('content://fixture.other/cache'),null]){const f=fixture();const args=[...f.args];args[0]=values;originalOnly(f,args);}
  const f=fixture();originalOnly(f,f.args,{isCacheReceiver:false});
});
test('URI toString or receiver/thread classification error warns and retains original query once',()=>{
  for(const options of [{receiverError:new Error('receiver')},{threadError:new Error('thread')}]){
    const f=fixture(options);originalOnly(f);assert.equal(f.warnings.length,1);
  }
  const f=fixture();const args=[...f.args];args[0]={toString(){throw new Error('URI');}};originalOnly(f,args);assert.equal(f.warnings.length,1);
});
test('unknown/active/progress/newer/qualifier-changed rows preserve the whole native group',()=>{
  for(const extra of [{fw_state:2},{fw_update_progress:1},{fw_metadata:'{bad'},
    {fw_metadata:metadata('11.0.0.171(SP2C00E170R1P1)')},{fw_metadata:metadata('11.0.0.170(SP3C00E170R1P1)')},
    {fw_metadata:JSON.stringify({version:'11.0.0.169',components:[{name:'base-only'}]})}])
    preserved(fixture({rows:[row('1'),row('2',extra)]}));
});
test('empty cache, duplicate or unsafe IDs, null metadata are preserved without SQL interpolation',()=>{
  for(const rows of [[],[row('1'),row('1')],[row('1) OR 1=1 --')],[row(null)],[row('1',{fw_metadata:null})]])preserved(fixture({rows}));
});
test('unparseable or throwing complete-version supplier falls back before metadata access',()=>{
  for(const options of [{version:'unknown'},{supplierError:new Error('supplier')}]){
    const f=fixture(options);originalOnly(f);assert.equal(f.warnings.length,1);
  }
});
test('metadata query failure or no Cursor preserves original query, and closes no invented Cursor',()=>{
  for(const options of [{snapshotError:new Error('snapshot')},{nullSnapshot:true}]){
    const f=fixture(options);assert.strictEqual(f.invoke(),f.nativeCursor);assert.equal(f.snapshots().length,1);assert.equal(f.nativeCalls().length,1);
    assert.equal(f.cursors.length,0);sameArguments(f.nativeCalls()[0].args,f.args);assert.equal(f.warnings.length,1);
  }
});
test('missing column, move failure and read failure each close the metadata Cursor exactly once',()=>{
  for(const options of [{cursorColumns:['_id','fw_state','fw_metadata']},{moveError:new Error('move')},{readError:new Error('read')}]){
    const f=fixture(options);preserved(f);assert.equal(f.warnings.length,1);
  }
});
test('metadata close failure is not retried and never closes caller Cursor',()=>{
  const f=fixture({closeError:new Error('close')});preserved(f);assert.equal(f.warnings.length,1);assert.equal(f.nativeCursor.closeCount,0);
});
test('force setting failure is optional and invokes original query once',()=>{
  const f=fixture({forceError:new Error('optional setting')});originalOnly(f);assert.equal(f.warnings.length,1);
});
test('warning/telemetry failures do not replace the native Cursor',()=>{
  filtered(fixture({telemetryError:new Error('detached telemetry')}),['7']);
  const f=fixture({supplierError:new Error('optional supplier'),warnError:new Error('logger')});originalOnly(f);
});
test('same-thread recursive query bypasses a second metadata scan and retains its native arguments',()=>{
  let nested=false;const nestedArgs=[];const f=fixture({onNative(entry,api){
    if(entry.snapshot&&!nested){nested=true;const values=[...api.args];values[2]='native_nested=?';nestedArgs.push(values);assert.strictEqual(api.invoke(values),api.nativeCursor);}
  }});
  assert.strictEqual(f.invoke(),f.nativeCursor);assert.equal(f.snapshots().length,1);assert.equal(f.nativeCalls().length,2);
  sameArguments(f.nativeCalls()[0].args,nestedArgs[0]);assert.equal(f.cursors[0].closeCount,1);assert.equal(f.stats.cacheLatest,1);
});
test('different threads have independent guards; guard clears after each operation',()=>{
  let nested=false;const f=fixture({onNative(entry,api){
    if(entry.snapshot&&!nested){nested=true;api.setThread(2);assert.strictEqual(api.invoke(),api.nativeCursor);api.setThread(1);}
  }});
  assert.strictEqual(f.invoke(),f.nativeCursor);assert.equal(f.snapshots().length,2);assert.equal(f.nativeCalls().length,2);assert.equal(f.stats.cacheLatest,2);
  for(const cursor of f.cursors)assert.equal(cursor.closeCount,1);
  f.options.onNative=null;assert.strictEqual(f.invoke(),f.nativeCursor);assert.equal(f.snapshots().length,3);assert.equal(f.stats.cacheLatest,3);
});
test('guard is released after optional metadata failure so the next query can be filtered',()=>{
  const f=fixture({readError:new Error('first read')});preserved(f);f.options.readError=null;f.calls.length=0;
  assert.strictEqual(f.invoke(),f.nativeCursor);assert.equal(f.snapshots().length,1);assert.ok(f.nativeCalls()[0].args[2].includes(' NOT IN '));
});
test('direct native-query exception propagates unchanged once on force/URI/preserved paths',()=>{
  for(const setup of ['force','uri','preserve']){
    const error=new Error('native database failure');const f=fixture({nativeError:error,force:setup==='force',rows:setup==='preserve'?[row('7',{fw_state:3})]:[row()]});
    const args=[...f.args];if(setup==='uri')args[0]=uri('content://other');assert.throws(()=>f.invoke(args),actual=>actual===error);
    assert.equal(f.nativeCalls().length,1);sameArguments(f.nativeCalls()[0].args,args);
  }
});
test('filtered SQL failure falls back once to the unmodified native query',()=>{
  const f=fixture({filteredError:new Error('optional selection rejected')});assert.strictEqual(f.invoke(),f.nativeCursor);
  assert.equal(f.snapshots().length,1);assert.equal(f.nativeCalls().length,2);sameArguments(f.nativeCalls()[1].args,f.args);
  assert.equal(f.cursors[0].closeCount,1);assert.equal(f.warnings.length,1);assert.equal(f.stats.cacheLatest??0,0);
});
test('original fallback exception propagates once and releases the per-thread guard',()=>{
  const native=new Error('native fallback failed');const f=fixture({supplierError:new Error('optional supplier'),nativeError:native});
  assert.throws(()=>f.invoke(),error=>error===native);assert.equal(f.nativeCalls().length,1);assert.equal(f.snapshots().length,0);
  f.options.supplierError=null;f.options.nativeError=null;f.calls.length=0;filtered(f,['7']);
});
test('unsupported descriptors, unbound columns/states/URI/supplier fail before installing a hook',()=>{
  const changes=[h=>h.return_type='java.lang.Object',h=>h.is_static=true,h=>h.parameter_types.push('int'),
    h=>h.parameter_types[0]='java.lang.String',h=>delete h.columns.metadata,h=>h.columns.id='_id;DELETE',
    h=>h.pending_states=[],h=>h.pending_states=['0'],h=>delete h.uri_source,h=>h.uri_source.field_type='java.lang.String',
    h=>delete h.current_version_source];
  for(const mutateHook of changes){const f=fixture({install:false,mutateHook});assert.throws(()=>f.install());assert.equal(f.overload.implementation,null);assert.equal(f.calls.length,0);}
  for(const options of [{missingType:'FixtureAccessor'},{missingType:'FixtureCacheReceiver'},{missingType:'FixtureVersion'},
    {fieldError:new Error('field missing')},{nullUri:true}]){
    const f=fixture({...options,install:false});assert.throws(()=>f.install());assert.equal(f.overload.implementation,null);assert.equal(f.calls.length,0);
  }
});
test('all successful filtering operations use query only and keep stored metadata unmodified',()=>{
  const rows=[row('5'),row('6')];const before=JSON.stringify(rows);const f=fixture({rows});filtered(f,['5','6']);
  assert.deepEqual(f.writes,[]);assert.equal(JSON.stringify(rows),before);assert.equal(f.calls.every(call=>call.receiver===f.receiver),true);
});

console.log(JSON.stringify({passed:failed.length===0,tests:passed+failed.length,successful:passed,failures:failed.length,
  deviceOperations:false,databaseWrites:false,networkOperations:false}));
if(failed.length)process.exitCode=1;
