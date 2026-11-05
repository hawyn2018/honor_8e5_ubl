// Runtime addresses belong to this agent and its fixed application class loader.
// Only DEX identities are persisted by the root supervisor; never serialize JNI
// handles or ArtMethod pointers, which change across processes and ART updates.
const primitives = {void:'V',boolean:'Z',byte:'B',char:'C',short:'S',int:'I',long:'J',float:'F',double:'D'};
const dexType = name => primitives[name] || (name.startsWith('[') ? name.replace(/\./g, '/') : 'L' + name.replace(/\./g, '/') + ';');
export const methodIdentity = item => dexType(item.class_name) + '->' + item.method_name + '(' + item.parameter_types.map(dexType).join('') + ')' + dexType(item.return_type);

export function createProcessMethodCache(Java) {
  const classes = new Map(), methods = new Map();
  const stats = {class_hits:0,class_misses:0,method_hits:0,method_misses:0,reflection_classes:0,reflection_signatures:0};
  function use(name) {
    if (classes.has(name)) { stats.class_hits++; return classes.get(name).type; }
    const type = Java.use(name); stats.class_misses++;
    classes.set(name, {type, names:null, signatures:new Map(), constructors:null, proxy:null});
    return type;
  }
  function body(spec) {
    const type = use(spec.class_name), entry = classes.get(spec.class_name);
    if (entry.proxy === null) entry.proxy = Java.use('java.lang.reflect.Proxy').isProxyClass(type.class);
    if (entry.proxy) throw new Error('Selective proxy method is not a Java body');
    const constructor = spec.method_name === '<init>';
    let candidates;
    if (constructor) {
      if (entry.constructors === null) entry.constructors = Array.from(type.class.getDeclaredConstructors());
      candidates = entry.constructors;
    } else {
      if (entry.names === null) {
        entry.names = new Map(); stats.reflection_classes++;
        for (const method of type.class.getDeclaredMethods()) {
          const name = String(method.getName()), group = entry.names.get(name) || [];
          group.push(method); entry.names.set(name, group);
        }
      }
      candidates = entry.names.get(spec.method_name) || [];
    }
    // Decode only the selected name group. Unrelated optional method types are
    // not newly resolved, preserving compatibility with the previous lookup.
    if (!entry.signatures.has(spec.method_name)) {
      const parameters = new Map();
      for (const method of candidates) {
        const key = JSON.stringify(Array.from(method.getParameterTypes(), type => String(type.getName())));
        const matches = parameters.get(key) || [];
        matches.push(method); parameters.set(key, matches); stats.reflection_signatures++;
      }
      entry.signatures.set(spec.method_name, {parameters,results:new Map()});
    }
    const group = entry.signatures.get(spec.method_name), parameterKey = JSON.stringify(spec.parameter_types);
    if (!group.results.has(parameterKey)) {
      const results = new Map();
      for (const method of group.parameters.get(parameterKey) || []) {
        const result = constructor ? 'void' : String(method.getReturnType().getName());
        const matches = results.get(result) || [];
        matches.push(method); results.set(result, matches);
      }
      group.results.set(parameterKey, results);
    }
    const matches = group.results.get(parameterKey).get(spec.return_type) || [];
    if (matches.length > 1) throw new Error('Selective reflective method is ambiguous');
    if (matches.length !== 1 || (Number(matches[0].getModifiers()) & (0x100 | 0x400)) !== 0)
      throw new Error('Selective native/abstract/unresolved method: ' + methodIdentity(spec));
  }
  function resolve(spec, {validateBody=false,requireHandle=false} = {}) {
    const key = methodIdentity(spec);
    let value = methods.get(key);
    if (value === undefined) {
      const type = use(spec.class_name), member = type[spec.method_name === '<init>' ? '$init' : spec.method_name];
      if (member == null || typeof member.overload !== 'function') throw new Error('Runtime method is absent: ' + key);
      const method = member.overload(...spec.parameter_types);
      if (method.returnType.className !== spec.return_type) throw new Error('Runtime method result ABI differs: ' + key);
      value = {type,method,handle:null,validated:false}; methods.set(key,value); stats.method_misses++;
    } else stats.method_hits++;
    if (validateBody && !value.validated) { body(spec); value.validated = true; }
    if (requireHandle && value.handle === null) {
      const handle = value.method.handle;
      if (handle == null || handle.isNull()) throw new Error('Selective method ABI/handle unavailable: ' + key);
      value.handle = handle;
    }
    return value;
  }
  return {use,resolve,inspect:() => ({...stats,classes:classes.size,methods:methods.size,scope:'process-classloader'})};
}
