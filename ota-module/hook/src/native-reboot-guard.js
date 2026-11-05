// A process-local native Binder terminal. It contains no marker/slot API:
// allowed() is a one-shot take owned by the current synchronous original call.
export function createNativeRebootGuardPolicy({code, sameBinder, allowed, blocked, deny, transaction = 'reboot', warn = () => {}}) {
  const stats = {ordinary: 0, otherBinder: 0, allowed: 0, blocked: 0, comparisonErrors: 0};
  const warning = (message, error) => { try { warn(message, error); } catch (_) {} };
  return {
    dispatch(receiver, args, original) {
      // No Java identity, Parcel, descriptor, broker or callback work here.
      if (Number(args[0]) !== code) { stats.ordinary++; return original(); }
      let matches;
      try { matches = sameBinder(receiver) === true; }
      catch (error) {
        stats.comparisonErrors++; stats.blocked++;
        warning('Native reboot binder identity comparison failed', error);
        try { blocked({reason: 'binder_identity_error', transaction, code}); }
        catch (failure) { warning('Reporting the native reboot block failed', failure); }
        return deny(args[2], args[3]);
      }
      if (!matches) { stats.otherBinder++; return original(); }
      let permit = false;
      try { permit = allowed() === true; }
      catch (error) { warning('Taking the synchronous original reboot scope failed', error); }
      if (permit) { stats.allowed++; return original(); }
      stats.blocked++;
      try { blocked({reason: 'no_synchronous_authorized_original', transaction, code}); }
      catch (error) { warning('Reporting the native reboot block failed', error); }
      return deny(args[2], args[3]);
    },
    inspect: () => ({...stats}),
  };
}

function need(value, message) { if (!value) throw new Error(message); }
const parameterNames = method => Array.from(method.getParameterTypes(), value => String(value.getName()));
const sameParameters = (actual, expected) => actual.length === expected.length && actual.every((value, index) => value === expected[index]);

function verifyNativeAbi(Java) {
  const type = Java.use('android.os.BinderProxy'), Modifier = Java.use('java.lang.reflect.Modifier');
  const parameters = ['int', 'android.os.Parcel', 'android.os.Parcel', 'int'];
  const reflected = Array.from(type.class.getDeclaredMethods()).filter(method => String(method.getName()) === 'transactNative' &&
    sameParameters(parameterNames(method), parameters) && String(method.getReturnType().getName()) === 'boolean');
  need(reflected.length === 1 && Modifier.isNative(reflected[0].getModifiers()) === true &&
    Modifier.isStatic(reflected[0].getModifiers()) === false, 'BinderProxy native transaction ABI is not proved');
  const method = type.transactNative.overload(...parameters);
  need(method.returnType.className === 'boolean' && sameParameters(method.argumentTypes.map(value => value.className), parameters), 'BinderProxy bridge ABI differs from reflection');
  return {type, method};
}

function runtimeBinding(Java, app) {
  const Context = Java.use('android.content.Context'), PowerManager = Java.use('android.os.PowerManager');
  const powerInterface = Java.use('android.os.IPowerManager'), Stub = Java.use('android.os.IPowerManager$Stub');
  const Modifier = Java.use('java.lang.reflect.Modifier');
  const descriptor = String(powerInterface.class.getName());
  need(descriptor === 'android.os.IPowerManager', 'Power interface identity differs from the framework API');
  const serviceName = String(Context.POWER_SERVICE.value);
  const manager = Java.cast(app.getSystemService.overload('java.lang.String').call(app, serviceName), PowerManager);
  need(manager != null, 'Runtime PowerManager service is absent');
  // Locate the actual interface field by its framework type, not an OEM field
  // name or version table. Ambiguous layouts keep the legacy protection mode.
  const fields = Array.from(PowerManager.class.getDeclaredFields()).filter(field =>
    String(field.getType().getName()) === descriptor && Modifier.isStatic(field.getModifiers()) === false);
  need(fields.length === 1, 'Runtime PowerManager interface field is ambiguous');
  fields[0].setAccessible(true);
  const service = Java.cast(fields[0].get(manager), powerInterface);
  need(service != null, 'PowerManager has no runtime Binder service');
  // asBinder is declared by IInterface. Some JNI reflection models do not
  // expose inherited interface methods on an IPowerManager wrapper. Resolve
  // its declaring contract explicitly instead of depending on that facade.
  const IInterface = Java.use('android.os.IInterface');
  const asBinder = IInterface.asBinder.overload();
  need(asBinder.argumentTypes.length === 0 && asBinder.returnType.className === 'android.os.IBinder', 'Runtime IInterface.asBinder ABI is not proved');
  const binder = asBinder.call(Java.cast(service, IInterface));
  need(binder != null && String(binder.getInterfaceDescriptor()) === descriptor, 'Runtime power Binder descriptor is not proved');
  const reboot = Array.from(powerInterface.class.getDeclaredMethods()).filter(method => String(method.getName()) === 'reboot' &&
    String(method.getReturnType().getName()) === 'void' && sameParameters(parameterNames(method), ['boolean', 'java.lang.String', 'boolean']));
  need(reboot.length === 1, 'Runtime power reboot is not the supported synchronous void ABI');
  const transactionFields = Array.from(Stub.class.getDeclaredFields()).filter(field => String(field.getName()) === 'TRANSACTION_reboot' &&
    String(field.getType().getName()) === 'int' && Modifier.isStatic(field.getModifiers()) === true);
  need(transactionFields.length === 1, 'Runtime reboot transaction field is ambiguous');
  transactionFields[0].setAccessible(true);
  const code = Number(transactionFields[0].getInt(null));
  const IBinder = Java.use('android.os.IBinder');
  need(Number.isInteger(code) && code >= Number(IBinder.FIRST_CALL_TRANSACTION.value) && code <= Number(IBinder.LAST_CALL_TRANSACTION.value), 'Runtime reboot transaction code is outside the Binder call range');
  // Older generated AIDL stubs lack this optional name method. The exact
  // typed TRANSACTION_reboot field and interface method still bind semantics.
  if (typeof Stub.getDefaultTransactionName === 'function')
    need(String(Stub.getDefaultTransactionName(code)) === 'reboot', 'Runtime transaction name disagrees with reboot');
  return {binder, code, descriptor, mode: 'runtime-power-reboot', transaction: 'reboot'};
}

function install(Java, bind, {allowed = () => false, blocked = () => {}, warn = () => {}}) {
  let active = false, target = null, method = null, implementation = null, installedReplacement = null, prior = null, binding = null, policy = null, errorText = '';
  let nativeFilter = null, nativeFilterError = '';
  const warning = (message, error) => { try { warn(message, error); } catch (_) {} };
  function dispose() {
    if (!active && target == null) return true;
    // Frida's getter returns the generated NativeCallback, not the JS handler
    // assigned to the setter. Capture that exact owned replacement so a real
    // restoration happens, without removing a subsequent owner's callback.
    try { if (method && installedReplacement !== null && method.implementation === installedReplacement) method.implementation = prior; }
    catch (error) {
      // Retain endpoint identity while our callback might still be installed.
      // Releasing it here would silently turn the remaining guard into pass-through.
      warning('Restoring the native Binder transaction failed; endpoint retained for retry', error);
      return false;
    }
    active = false;
    if (target != null) { try { target.$dispose(); } catch (_) {} target = null; }
    return true;
  }
  try {
    need(typeof allowed === 'function' && typeof blocked === 'function', 'Native reboot scope callbacks are absent');
    const abi = verifyNativeAbi(Java); method = abi.method;
    need(method.implementation == null, 'Native Binder transaction already has a replacement');
    prior = method.implementation;
    binding = bind();
    need(abi.type.class.isInstance(binding.binder) === true, 'Runtime power endpoint is not a BinderProxy');
    target = Java.retain(binding.binder);
    need(target != null && typeof target.$isSameObject === 'function', 'Native Binder endpoint identity API is absent');
    const SecurityException = Java.use('java.lang.SecurityException');
    const deny = (reply, flags) => {
      // AIDL reboot is void/synchronous. Preserve its caller's normal return
      // shape while sending no transaction. A false return could activate an
      // older generated proxy's default implementation fallback.
      if (reply != null && (Number(flags) & 1) === 0) try {
        reply.setDataSize(0); reply.setDataPosition(0); reply.writeNoException(); reply.setDataPosition(0);
        return true;
      } catch (error) { warning('Writing a local denied-reboot reply failed', error); }
      throw SecurityException.$new('hnubl: reboot requires this synchronous original call to have completed its OTA permission action');
    };
    policy = createNativeRebootGuardPolicy({code: binding.code, transaction: binding.transaction,
      sameBinder: receiver => target != null && target.$isSameObject(receiver), allowed, blocked, deny, warn: warning});
    implementation = function (...args) { return policy.dispatch(this, args, () => method.call(this, ...args)); };
    try { method.implementation = implementation; }
    finally { installedReplacement = method.implementation; }
    active = true;
    // Ordinary Binder transactions can remain entirely in the registered native
    // JNI implementation. Matching codes still enter this exact Java callback,
    // including its Binder identity, one-shot scope and Java exception handling.
    // The optional bridge API commits only after all ABI/entry checks succeed.
    if (typeof method.installNativeTransactionCodeFilter === 'function') try {
      nativeFilter = method.installNativeTransactionCodeFilter(binding.code);
      need(nativeFilter != null && nativeFilter.mode === 'native_code_filter' && nativeFilter.inspect().ready === true,
        'Native transaction fast filter did not become ready');
    } catch (error) {
      nativeFilter = null; nativeFilterError = String(error);
      // Retire the old private clone even if optional publication failed after
      // preparation. The original setter restores it and creates one complete
      // Java replacement; retained native gates are never freed in flight.
      try { method.implementation = implementation; }
      finally { installedReplacement = method.implementation; }
      warning('Native transaction fast filter unavailable; keeping the existing Java guard', error);
    }
  } catch (error) {
    errorText = String(error); dispose(); warning('Native reboot boundary unavailable; legacy protection is required', error);
  }
  return {get ready() { return active; }, dispose,
    inspect() {
      const counts = policy ? policy.inspect() : {};
      let fast = {};
      if (nativeFilter !== null) try { fast = nativeFilter.inspect(); } catch (_) {}
      return {ready: active, mode: binding && binding.mode || 'unavailable', transaction: binding && binding.transaction || 'reboot',
        code: binding && binding.code, descriptor: binding && binding.descriptor, error: errorText, ...counts,
        ordinary:(counts.ordinary || 0)+(Number(fast.native_fastpass)||0),
        native_filter_ready:active && nativeFilter !== null && fast.ready === true,
        native_filter_error:nativeFilterError,native_filter:fast};
    }};
}

export function installNativeRebootGuard(options) {
  const {Java, app} = options;
  return install(Java, () => runtimeBinding(Java, app), options);
}

// Separate, read-only descriptor fixture entry. Production has no transaction
// number override. Even a missed fixture hook only requests a descriptor.
export function installNativeRebootGuardFixture(options) {
  const {Java, binder, targetCode} = options;
  return install(Java, () => {
    const code = Number(Java.use('android.os.IBinder').INTERFACE_TRANSACTION.value);
    need(Number(targetCode) === code, 'Fixture accepts only the framework descriptor query transaction');
    need(binder != null, 'Fixture Binder is absent');
    return {binder, code, descriptor: 'descriptor-query-fixture', mode: 'descriptor-fixture', transaction: 'descriptor-query'};
  }, options);
}
