package net.hawyn.hnubl.discovery;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** DEX semantics, descriptors and invoke edges; no vendor class/version/address table. */
public final class OucDiscovery {
    /** Persist only location-independent DEX descriptors; runtime handles are resolved in each new process. */
    public static final String CACHE_POLICY = "ouc-semantic-dex-identities-v3";
    private static final String STRING = "Ljava/lang/String;";
    private static final class MethodInfo {
        final Method method;
        final String key;
        final Set<String> strings = new LinkedHashSet<>();
        final Set<String> calls = new LinkedHashSet<>();
        final Set<String> asynchronousCalls = new LinkedHashSet<>();
        final Map<String, Set<String>> literalArguments = new LinkedHashMap<>();
        final ArrayList<Instruction> instructions = new ArrayList<>();
        final Set<String> constructed = new HashSet<>();
        boolean submitsRunnable;
        boolean conditional;
        MethodInfo(Method m) { method=m; key=key(m); }
        boolean signature(String returns, String... args) {
            return method.getReturnType().equals(returns) && types(method).equals(Arrays.asList(args));
        }
        boolean contains(String fragment) {
            String target=fragment.toLowerCase(Locale.ROOT);
            for(String s:strings) if(s.toLowerCase(Locale.ROOT).contains(target)) return true;
            return false;
        }
    }

    public static String discover(File apk) throws Exception {
        Map<String,MethodInfo> methods=new LinkedHashMap<>();
        Map<String,ClassDef> classes=new LinkedHashMap<>();
        MultiDexContainer<? extends DexFile> container=DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        for(String name:container.getDexEntryNames()) {
            MultiDexContainer.DexEntry<? extends DexFile> entry=container.getEntry(name);
            if(entry==null) continue;
            for(ClassDef c:entry.getDexFile().getClasses()) {
                classes.put(c.getType(),c);
                for(Method m:c.getMethods()) if(m.getImplementation()!=null) {
                    MethodInfo info=new MethodInfo(m);
                    for(Instruction ins:m.getImplementation().getInstructions()) info.instructions.add(ins);
                    inspect(info);
                    // Retain semantic facts, not every decoded instruction in the APK.
                    // Otherwise large updater libraries inflate the long-lived graph.
                    info.instructions.clear();info.instructions.trimToSize();
                    methods.put(info.key,info);
                }
            }
        }

        // A constructed Runnable subsequently submitted by a method belongs to its
        // asynchronous call graph. The invoke descriptor (Runnable), not its class
        // name, establishes the queue boundary.
        for(MethodInfo m:methods.values()) {
            if(m.submitsRunnable) for(String owner:m.constructed) {
                ClassDef c=classes.get(owner);
                if(c!=null && isSubclass(owner,"Ljava/lang/Runnable;",classes,new HashSet<String>())) {
                    String run=owner+"->run()V";
                    if(methods.containsKey(run)) { m.calls.add(run);m.asynchronousCalls.add(run); }
                }
            }
        }

        Set<String> sinks=new HashSet<>();
        for(MethodInfo m:methods.values()) for(String call:m.calls) {
            if(call.startsWith("Landroid/os/PowerManager;->reboot(") ||
               call.startsWith("Landroid/os/RecoverySystem;->rebootWipe")) sinks.add(m.key);
        }
        Set<String> reachesReboot=reverseClosure(methods,sinks);
        Map<String,Set<String>> manual=new LinkedHashMap<>(), automatic=new LinkedHashMap<>();
        for(MethodInfo m:methods.values()) for(Map.Entry<String,Set<String>> e:m.literalArguments.entrySet()) {
            MethodInfo callee=methods.get(e.getKey());
            if(callee==null || !callee.signature("V",STRING) || !reachesReboot.contains(callee.key)) continue;
            for(String value:e.getValue()) {
                if(isManual(value)) manual.computeIfAbsent(callee.key,k->new LinkedHashSet<>()).add(value);
                if(isAutomatic(value)) automatic.computeIfAbsent(callee.key,k->new LinkedHashSet<>()).add(value);
            }
        }
        Set<String> gates=new LinkedHashSet<>(manual.keySet());
        // A shared route does not dominate an independent automatic or manual
        // entry. Keep every literal-reason frontier proved to reach real reboot.
        // Synchronous nested entries use the agent's same-thread allowance.
        gates.addAll(automatic.keySet());
        List<String> asynchronousFrontiers=new ArrayList<>();
        for(String outer:gates) for(String inner:gates)
            if(!outer.equals(inner) && asynchronouslyReachable(outer,inner,methods))
                asynchronousFrontiers.add(outer+" => "+inner);

        List<Map<String,Object>> hooks=new ArrayList<>();
        Set<String> hookIds=new HashSet<>();
        Map<String,Integer> versionStates=versionStates(classes);
        Map<String,Map<String,Object>> structuralCountdowns=discoverCountdownMessages(methods,classes,automatic.keySet());
        for(MethodInfo m:methods.values()) {
            if(m.signature(STRING) && m.contains("getPackageTypeForChecking") && m.strings.contains("recovery"))
                add(hooks,hookIds,m,"full_package","request package-type log + String() + recovery literal");
            // The input is still the server response here. Replacing its status
            // feeds the client's existing not-available branch and original
            // Handler callback, before either legacy XML or BL parsing starts.
            if(m.signature("V","I",STRING) && m.strings.contains("versionPackageTypes") &&
               m.strings.contains("status") && m.contains("dealCheckNewVersionComplete status is not available") &&
               !versionStates.isEmpty()) {
                Map<String,Object> current=currentVersionSource(m,methods,classes);
                MethodInfo callback=null;
                for(String call:m.calls) {
                    MethodInfo candidate=methods.get(call);
                    if(candidate!=null && candidate.method.getDefiningClass().equals(m.method.getDefiningClass()) &&
                       candidate.signature("V") && candidate.contains("sendCheckResult") &&
                       callsNamed(candidate,"obtainMessage") && callsNamed(candidate,"sendMessage")) callback=candidate;
                }
                if(!current.isEmpty() && callback!=null) {
                    Map<String,Object> h=add(hooks,hookIds,m,"version_response",
                        "status + versionPackageTypes JSON response entry + native unavailable branch + original Handler result callback");
                    if(h!=null) {
                        h.put("response_arg",1);h.put("version_states",versionStates);h.put("current_version_source",current);
                        h.put("native_no_update_callback",key(callback.method));
                    }
                }
            }
            if(m.method.getReturnType().equals("Z") && m.contains("ota_disable_automatic_update") && settingsGetter(m,"Global"))
                setting(hooks,hookIds,m,"disable_auto","Global","ota_disable_automatic_update",1);
            if(m.method.getReturnType().equals("Z") && m.contains("hn_system_night_upgrade_switch") && settingsGetter(m,"Secure"))
                setting(hooks,hookIds,m,"night_switch","Secure","hn_system_night_upgrade_switch",0);
            if(m.method.getReturnType().equals("Z") && m.contains("hn_system_update_auto_download") && settingsGetter(m,"Global"))
                setting(hooks,hookIds,m,"auto_download","Global","hn_system_update_auto_download",0);

            boolean countdownLog=false;
            for(String s:m.strings) {
                String n=s.toLowerCase(Locale.ROOT).replace("_","").replace(" ","");
                if(n.contains("auto") && (n.contains("reboot") || n.contains("install")) && n.contains("countdown")) countdownLog=true;
            }
            if(countdownLog && types(m.method).isEmpty() &&
               (m.method.getReturnType().equals("Z") || m.method.getReturnType().equals("V")))
                add(hooks,hookIds,m,"countdown","automatic reboot/install countdown log + no-argument entry");
            else if(structuralCountdowns.containsKey(m.key)) {
                Map<String,Object> h=add(hooks,hookIds,m,"countdown",
                    "real Message.obtain Handler field + matching what branch + delayed resend and proved automatic reboot path");
                if(h!=null)h.putAll(structuralCountdowns.get(m.key));
            }

            if(gates.contains(m.key)) {
                Map<String,Object> h=add(hooks,hookIds,m,"reboot_gate","manual/automatic literal reason + exact String signature + verified reboot graph; independent entrances retained");
                if(h!=null) {
                    h.put("mode_arg",0);
                    h.put("manual_values",new ArrayList<>(manual.getOrDefault(m.key,Collections.<String>emptySet())));
                    h.put("automatic_values",new ArrayList<>(automatic.getOrDefault(m.key,Collections.<String>emptySet())));
                }
            }

            // These are Android/vendor binder protocol method names, not obfuscated
            // implementation identifiers. Abstract interface declarations are excluded.
            boolean payloadProtocol=m.method.getReturnType().equals("V")&&hasBinderTransaction(m)&&
                isSubclass(m.method.getDefiningClass(),"Landroid/os/IUpdateEngine;",classes,new HashSet<String>())&&
                ((m.method.getName().equals("applyPayload")&&types(m.method).equals(Arrays.asList(STRING,"J","J","[Ljava/lang/String;")))||
                 (m.method.getName().equals("applyMultiPayloads")&&types(m.method).equals(Arrays.asList("[Ljava/lang/String;","[J","[J","[Ljava/lang/String;")))||
                 (m.method.getName().equals("applyUpdateZip")&&types(m.method).isEmpty())||
                 (m.method.getName().equals("startUpdateZip")&&types(m.method).equals(Collections.singletonList(STRING))));
            if(payloadProtocol) {
                List<String> callers=payloadCallers(m,methods,classes);
                if(!callers.isEmpty()) {
                    Map<String,Object> h=add(hooks,hookIds,m,"payload_start",
                        "typed UpdateEngine Binder protocol implementation + actual non-protocol caller invokes the same interface ABI");
                    if(h!=null){h.put("payload_callers",callers);h.put("protocol_descriptor",m.key.substring(m.key.indexOf("->")+2));}
                }
            }
            if(m.method.getName().equals("onPayloadApplicationComplete") && m.signature("V","I") &&
               (m.contains("installVirtualAB") || m.contains("startUpdateZip")) && !m.contains("cleanup"))
                add(hooks,hookIds,m,"payload_complete","payload completion int protocol + install log; cleanup callbacks excluded");

            // Older clients can use CountDownTimer instead of a Handler. Only timers
            // whose finish path reaches an automatic-reason gate are selected.
            if(m.method.getName().equals("onFinish") && m.signature("V") &&
               isSubclass(m.method.getDefiningClass(),"Landroid/os/CountDownTimer;",classes,new HashSet<String>()) &&
               reachesAny(m.key,automatic.keySet(),methods) && singleActionTimer(m))
                add(hooks,hookIds,m,"countdown_timer","CountDownTimer subtype + finish path to automatic reboot gate");
        }

        discoverReasonBridges(hooks,hookIds,methods,gates,reachesReboot,manual,automatic);
        List<Map<String,Object>> rebootContinuations=discoverTerminalReboot(hooks,hookIds,methods,classes);
        discoverVersionCache(hooks,hookIds,methods,classes);
        Map<String,Object> engineLifecycle=discoverEngineLifecycle(methods,classes);
        Set<String> roles=new HashSet<>();for(Map<String,Object> h:hooks) roles.add((String)h.get("role"));
        List<String> missing=new ArrayList<>();
        if(!roles.contains("reboot_gate")) missing.add("reboot_gate");
        if(!roles.contains("reboot_terminal")) missing.add("reboot_terminal");
        if(rebootContinuations.isEmpty()) missing.add("reboot_continuation");
        if(manual.isEmpty()) missing.add("manual_reboot_frontier");
        if(automatic.isEmpty()) missing.add("automatic_reboot_frontier");
        if(!roles.contains("countdown") && !roles.contains("countdown_timer")) missing.add("countdown");
        if(!roles.contains("payload_start")) missing.add("payload_start");
        // No asynchronous permission propagation or entry-dominance proof has
        // been established for such a pair. Do not silently hook both and then
        // consume again after the outer call's thread allowance has ended.
        if(!asynchronousFrontiers.isEmpty()) missing.add("asynchronous_reboot_frontier");
        List<String> optionalMissing=new ArrayList<>();
        for(String role:Arrays.asList("full_package","disable_auto","night_switch","auto_download","version_response","version_cache_query","payload_complete"))
            if(!roles.contains(role)) optionalMissing.add(role);
        if(engineLifecycle.isEmpty()) optionalMissing.add("engine_lifecycle");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("schema",1);result.put("package","com.hihonor.ouc");
        result.put("apk_sha256",sha256(apk));result.put("discovery_complete",missing.isEmpty());
        result.put("missing",missing);result.put("hooks",hooks);
        result.put("critical_missing",missing);result.put("optional_missing",optionalMissing);
        result.put("critical_roles",Arrays.asList("manual_reboot_gate","automatic_reboot_gate","reboot_terminal","reboot_continuation","countdown/countdown_timer","payload_start"));
        result.put("reboot_continuations",rebootContinuations);
        result.put("deoptimization_plan",criticalDispatchPlan(methods,classes,hooks,rebootContinuations));
        result.put("engine_lifecycle",engineLifecycle);
        result.put("unproved_asynchronous_frontiers",asynchronousFrontiers);
        result.put("method_count",methods.size());
        result.put("policy","semantic anchors + DEX descriptors + literal argument flow + invoke graph; no model/version/address table");
        return json(result);
    }

    private static boolean callsNamed(MethodInfo m,String name) {
        for(String call:m.calls)if(call.contains("->"+name+"("))return true;return false;
    }

    private static void discoverReasonBridges(List<Map<String,Object>> hooks,Set<String> ids,
            Map<String,MethodInfo> methods,Set<String> gates,Set<String> reachesReboot,Map<String,Set<String>> originalManual,Map<String,Set<String>> originalAutomatic) {
        Set<String> seen=new HashSet<>(gates);Deque<String> pending=new ArrayDeque<>(gates);
        Map<String,Set<String>> manual=new HashMap<>(),automatic=new HashMap<>();Map<String,Map<String,Object>> bridges=new HashMap<>();
        for(String gate:gates){manual.put(gate,new LinkedHashSet<>(originalManual.getOrDefault(gate,Collections.emptySet())));automatic.put(gate,new LinkedHashSet<>(originalAutomatic.getOrDefault(gate,Collections.emptySet())));}
        while(!pending.isEmpty()) {
            MethodInfo sender=code(methods.get(pending.removeFirst()));
            int parameter=sender.method.getImplementation().getRegisterCount()-1;
            List<List<Integer>> parents=predecessors(sender.instructions);
            for(int i=0;i<sender.instructions.size();i++) {
                Instruction ins=sender.instructions.get(i);if(!(ins instanceof ReferenceInstruction)||!(((ReferenceInstruction)ins).getReference() instanceof MethodReference))continue;
                String call=key((MethodReference)((ReferenceInstruction)ins).getReference());MethodInfo target=methods.get(call);
                if(target==null||!target.signature("V",STRING)||!reachesReboot.contains(call))continue;
                int argument=argumentRegister(ins,ins.getOpcode().name().startsWith("INVOKE_STATIC")?0:1);
                if(!fromParameter(sender.instructions,parents,i,argument,parameter))continue;
                Set<String> m=manual.computeIfAbsent(call,k->new LinkedHashSet<>()),a=automatic.computeIfAbsent(call,k->new LinkedHashSet<>());
                boolean changed=m.addAll(manual.getOrDefault(sender.key,Collections.emptySet()));changed=a.addAll(automatic.getOrDefault(sender.key,Collections.emptySet()))||changed;
                if(seen.add(call)){changed=true;Map<String,Object> h=add(hooks,ids,target,"reboot_context",
                    "original reboot reason parameter forwarded from proved reboot frontier; restores asynchronous callback reason without consuming permission");
                    if(h!=null){h.put("mode_arg",0);h.put("forwarded_from",sender.key);bridges.put(call,h);}
                }
                Map<String,Object> h=bridges.get(call);if(h!=null){h.put("manual_values",new ArrayList<>(m));h.put("automatic_values",new ArrayList<>(a));}
                if(changed)pending.add(call);
            }
        }
    }
    private static boolean fromParameter(List<Instruction> code,List<List<Integer>> parents,int at,int register,int parameter) {
        if(register<0)return false;Set<String> seen=new HashSet<>();Deque<int[]> todo=new ArrayDeque<>();todo.add(new int[]{at,register});boolean reached=false;
        while(!todo.isEmpty()) {
            int[] node=todo.removeFirst();if(!seen.add(node[0]+":"+node[1]))continue;
            if(parents.get(node[0]).isEmpty()){if(node[1]!=parameter)return false;reached=true;continue;}
            for(int prior:parents.get(node[0])) {
                Instruction ins=code.get(prior);
                if(ins.getOpcode().setsRegister()&&ins instanceof OneRegisterInstruction&&((OneRegisterInstruction)ins).getRegisterA()==node[1]) {
                    if(ins.getOpcode().name().startsWith("MOVE_OBJECT")&&ins instanceof TwoRegisterInstruction)todo.add(new int[]{prior,((TwoRegisterInstruction)ins).getRegisterB()});
                    else return false;
                }else todo.add(new int[]{prior,node[1]});
            }
        }
        return reached;
    }
    private static List<Map<String,Object>> discoverTerminalReboot(List<Map<String,Object>> hooks,Set<String> ids,
            Map<String,MethodInfo> methods,Map<String,ClassDef> classes) {
        final String terminal="Landroid/os/PowerManager;->reboot(Ljava/lang/String;)V";List<String> callers=new ArrayList<>();
        List<Map<String,Object>> continuations=new ArrayList<>();
        for(MethodInfo method:methods.values())if(method.calls.contains(terminal)) {
            callers.add(method.key);
            if(!method.method.getName().equals("run")||!method.signature("V")||
                    !isSubclass(method.method.getDefiningClass(),"Ljava/lang/Runnable;",classes,new HashSet<String>()))continue;
            List<Map<String,Object>> constructors=new ArrayList<>();String owner=method.method.getDefiningClass();
            for(MethodInfo ctor:methods.values())if(ctor.method.getDefiningClass().equals(owner)&&ctor.method.getName().equals("<init>")) {
                Map<String,Object> shape=new LinkedHashMap<>();List<String> params=new ArrayList<>();for(String type:types(ctor.method))params.add(javaType(type));shape.put("parameter_types",params);constructors.add(shape);
            }
            if(constructors.isEmpty())continue;
            Map<String,Object> item=new LinkedHashMap<>();item.put("class_name",javaType(owner));item.put("constructors",constructors);item.put("callback",methodShape(method));
            item.put("evidence",Arrays.asList("actual Runnable.run invokes framework reboot directly","actual constructors capture only process-local origin, never a consumed Root grant"));continuations.add(item);
        }
        if(!callers.isEmpty()&&ids.add("reboot_terminal|"+terminal)) {
            Map<String,Object> hook=new LinkedHashMap<>();hook.put("role","reboot_terminal");hook.put("class_name","android.os.PowerManager");hook.put("method_name","reboot");
            hook.put("parameter_types",Collections.singletonList("java.lang.String"));hook.put("return_type","void");hook.put("is_static",false);hook.put("mode_arg",0);
            hook.put("evidence",Arrays.asList("actual app invoke references to framework PowerManager.reboot(String)","last process-local reboot boundary; automatic and unknown origins cannot spend file permission"));hook.put("actual_callers",callers);hooks.add(hook);
        }
        return continuations;
    }

    /** A packaged, unused Binder Proxy is not evidence that new payload starts
     * pass through it. Require a real invoke of this exact protocol signature
     * outside its interface implementation/Stub hierarchy. This proves a call
     * site, not a whole UI-to-payload asynchronous reachability graph. */
    private static List<String> payloadCallers(MethodInfo payload,Map<String,MethodInfo> methods,Map<String,ClassDef> classes) {
        List<String> callers=new ArrayList<>();String suffix=payload.key.substring(payload.key.indexOf("->"));
        for(MethodInfo caller:methods.values()) {
            if(isSubclass(caller.method.getDefiningClass(),"Landroid/os/IUpdateEngine;",classes,new HashSet<String>()))continue;
            for(String call:caller.calls)if(call.endsWith(suffix)) {
                String owner=call.substring(0,call.indexOf("->"));
                if(isSubclass(owner,"Landroid/os/IUpdateEngine;",classes,new HashSet<String>())){callers.add(caller.key);break;}
            }
        }
        return callers;
    }

    /** A log-free fallback for an actual countdown message sender. The message
     * discriminator and concrete Handler field come from DEX operands. A matching
     * handleMessage branch must both reschedule a message and reach a proved
     * automatic reboot frontier; unrelated timers/handlers do not qualify. */
    private static Map<String,Map<String,Object>> discoverCountdownMessages(Map<String,MethodInfo> methods,
            Map<String,ClassDef> classes,Set<String> automatic) {
        Map<String,Map<String,Object>> found=new LinkedHashMap<>();
        Set<String> automaticProducers=new HashSet<>();
        for(MethodInfo method:methods.values())for(Map.Entry<String,Set<String>> literal:method.literalArguments.entrySet())
            if(automatic.contains(literal.getKey()))for(String value:literal.getValue())if(isAutomatic(value))automaticProducers.add(method.key);
        final String obtain="Landroid/os/Message;->obtain(Landroid/os/Handler;ILjava/lang/Object;)Landroid/os/Message;";
        for(MethodInfo original:methods.values()) {
            if(!types(original.method).isEmpty()||!(original.method.getReturnType().equals("V")||original.method.getReturnType().equals("Z"))||
                    !original.calls.contains(obtain)||!original.calls.contains("Landroid/os/Message;->sendToTarget()V"))continue;
            MethodInfo sender=code(original);Set<Integer> boundaries=blockStarts(sender);
            for(int i=0;i<sender.instructions.size();i++) {
                Instruction ins=sender.instructions.get(i);
                if(!(ins instanceof ReferenceInstruction)||!(((ReferenceInstruction)ins).getReference() instanceof MethodReference)||
                        !key((MethodReference)((ReferenceInstruction)ins).getReference()).equals(obtain))continue;
                FieldReference field=localObjectField(sender.instructions,i,argumentRegister(ins,0),boundaries);
                Integer what=constantInteger(sender.instructions,i,argumentRegister(ins,1),boundaries);
                if(field==null||what==null||!messageSentToTarget(sender.instructions,i)||!isSubclass(field.getType(),"Landroid/os/Handler;",classes,new HashSet<String>()))continue;
                MethodInfo callback=methods.get(field.getType()+"->handleMessage(Landroid/os/Message;)V");
                if(callback==null)continue;
                Set<String> calls=messageBranchCalls(code(callback),what);boolean reboot=false,repeat=false;
                for(String call:calls) {
                    if(reachesAny(call,automaticProducers,methods))reboot=true;
                    Set<String> seen=new HashSet<>();Deque<String> todo=new ArrayDeque<>();todo.add(call);
                    while(!todo.isEmpty()) {String next=todo.removeFirst();if(!seen.add(next))continue;
                        if(next.equals("Landroid/os/Handler;->sendMessageDelayed(Landroid/os/Message;J)Z")){repeat=true;break;}
                        MethodInfo target=methods.get(next);if(target!=null)todo.addAll(target.calls);
                    }
                }
                if(!reboot||!repeat)continue;
                Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("handler_callback",callback.key);
                evidence.put("message_what",what);evidence.put("message_handler_field",field.getDefiningClass()+"->"+field.getName()+":"+field.getType());
                evidence.put("matched_message_calls",new ArrayList<>(calls));found.put(sender.key,evidence);
            }
        }
        return found;
    }
    private static boolean messageSentToTarget(List<Instruction> code,int obtainAt) {
        if(obtainAt+1>=code.size()||!code.get(obtainAt+1).getOpcode().name().equals("MOVE_RESULT_OBJECT"))return false;
        Set<Integer> aliases=new HashSet<>();aliases.add(((OneRegisterInstruction)code.get(obtainAt+1)).getRegisterA());
        for(int i=obtainAt+2;i<code.size()&&!aliases.isEmpty();i++) {
            Instruction ins=code.get(i);String op=ins.getOpcode().name();
            if(op.startsWith("IF_")||op.startsWith("GOTO")||op.endsWith("SWITCH")||op.startsWith("RETURN")||op.equals("THROW"))return false;
            if(ins instanceof ReferenceInstruction&&((ReferenceInstruction)ins).getReference() instanceof MethodReference&&
                    key((MethodReference)((ReferenceInstruction)ins).getReference()).equals("Landroid/os/Message;->sendToTarget()V")&&
                    aliases.contains(argumentRegister(ins,0)))return true;
            if(ins.getOpcode().setsRegister()&&ins instanceof OneRegisterInstruction) {
                int dest=((OneRegisterInstruction)ins).getRegisterA();boolean copy=op.startsWith("MOVE_OBJECT")&&ins instanceof TwoRegisterInstruction&&aliases.contains(((TwoRegisterInstruction)ins).getRegisterB());
                aliases.remove(dest);if(copy)aliases.add(dest);
            }
        }
        return false;
    }
    private static FieldReference localObjectField(List<Instruction> code,int at,int register,Set<Integer> boundaries) {
        if(register<0)return null;
        for(int i=at-1;i>=0;i--) {
            Instruction ins=code.get(i);String op=ins.getOpcode().name();
            if(op.startsWith("GOTO")||op.startsWith("IF_")||op.endsWith("SWITCH")||op.startsWith("RETURN")||op.equals("THROW"))return null;
            if(ins.getOpcode().setsRegister()&&ins instanceof OneRegisterInstruction&&((OneRegisterInstruction)ins).getRegisterA()==register) {
                if(op.equals("IGET_OBJECT")&&ins instanceof ReferenceInstruction&&((ReferenceInstruction)ins).getReference() instanceof FieldReference)
                    return (FieldReference)((ReferenceInstruction)ins).getReference();
                if(op.startsWith("MOVE_OBJECT")&&ins instanceof TwoRegisterInstruction){register=((TwoRegisterInstruction)ins).getRegisterB();continue;}
                return null;
            }
            if(boundaries.contains(i))return null;
        }
        return null;
    }
    private static Set<String> messageBranchCalls(MethodInfo callback,int what) {
        List<Instruction> code=callback.instructions;Map<Integer,Integer> offsets=new HashMap<>();int[] pcs=new int[code.size()];int pc=0;
        for(int i=0;i<code.size();i++){pcs[i]=pc;offsets.put(pc,i);pc+=code.get(i).getCodeUnits();}
        List<Map<Integer,Object>> inputs=new ArrayList<>();for(int i=0;i<code.size();i++)inputs.add(null);
        Map<Integer,Object> entry=new HashMap<>();entry.put(callback.method.getImplementation().getRegisterCount()-1,"message");
        inputs.set(0,entry);Deque<Integer> todo=new ArrayDeque<>();todo.add(0);Set<String> calls=new LinkedHashSet<>();boolean readsWhat=false,selectsWhat=false;
        while(!todo.isEmpty()) {
            int at=todo.removeFirst();Instruction ins=code.get(at);String op=ins.getOpcode().name();Map<Integer,Object> values=new HashMap<>(inputs.get(at));
            if(ins instanceof ReferenceInstruction&&((ReferenceInstruction)ins).getReference() instanceof MethodReference)
                calls.add(key((MethodReference)((ReferenceInstruction)ins).getReference()));
            if(ins.getOpcode().setsRegister()&&ins instanceof OneRegisterInstruction) {
                int dest=((OneRegisterInstruction)ins).getRegisterA();Object value=null;
                if(op.startsWith("CONST")&&ins instanceof NarrowLiteralInstruction)value=((NarrowLiteralInstruction)ins).getNarrowLiteral();
                else if(op.startsWith("MOVE")&&ins instanceof TwoRegisterInstruction)value=values.get(((TwoRegisterInstruction)ins).getRegisterB());
                else if(op.equals("IGET")&&ins instanceof TwoRegisterInstruction&&ins instanceof ReferenceInstruction) {
                    Reference ref=((ReferenceInstruction)ins).getReference();
                    if(ref instanceof FieldReference) {FieldReference f=(FieldReference)ref;
                        if(f.getDefiningClass().equals("Landroid/os/Message;")&&f.getName().equals("what")&&f.getType().equals("I")&&
                                "message".equals(values.get(((TwoRegisterInstruction)ins).getRegisterB()))){value=Long.valueOf(what);readsWhat=true;}
                    }
                }
                values.remove(dest);if(value!=null)values.put(dest,value);
                if(ins.getOpcode().setsWideRegister())values.remove(dest+1);
            }
            List<Integer> next=new ArrayList<>();Integer target=ins instanceof OffsetInstruction?offsets.get(pcs[at]+((OffsetInstruction)ins).getCodeOffset()):null;
            if(op.startsWith("IF_")) {
                Integer left=integer(values.get(((OneRegisterInstruction)ins).getRegisterA()));Integer right=op.endsWith("Z")?0:integer(values.get(((TwoRegisterInstruction)ins).getRegisterB()));Boolean branch=null;
                if(left!=null&&right!=null) {
                    Object leftOrigin=values.get(((OneRegisterInstruction)ins).getRegisterA());
                    Object rightOrigin=op.endsWith("Z")?Integer.valueOf(0):values.get(((TwoRegisterInstruction)ins).getRegisterB());
                    if((leftOrigin instanceof Long&&rightOrigin instanceof Integer)||(rightOrigin instanceof Long&&leftOrigin instanceof Integer))selectsWhat=true;
                    String condition=op.substring(3).replace("Z","");branch=switch(condition){case "EQ"->left.intValue()==right.intValue();case "NE"->left.intValue()!=right.intValue();case "LT"->left<right;case "GE"->left>=right;case "GT"->left>right;case "LE"->left<=right;default->null;};}
                if(branch!=Boolean.FALSE&&target!=null)next.add(target);if(branch!=Boolean.TRUE&&at+1<code.size())next.add(at+1);
            } else if(op.endsWith("SWITCH")&&target!=null&&code.get(target) instanceof SwitchPayload) {
                Object selector=values.get(((OneRegisterInstruction)ins).getRegisterA());Integer selected=integer(selector);boolean matched=false;
                if(selector instanceof Long)selectsWhat=true;
                for(SwitchElement element:((SwitchPayload)code.get(target)).getSwitchElements())if(selected==null||selected==element.getKey()) {Integer to=offsets.get(pcs[at]+element.getOffset());if(to!=null)next.add(to);matched=true;}
                if((selected==null||!matched)&&at+1<code.size())next.add(at+1);
            } else if(op.startsWith("GOTO")){if(target!=null)next.add(target);}
            else if(!op.startsWith("RETURN")&&!op.equals("THROW")&&!(ins instanceof PayloadInstruction)&&at+1<code.size())next.add(at+1);
            for(int to:next) {
                Map<Integer,Object> old=inputs.get(to),merged=new HashMap<>(values);
                if(old!=null){merged.keySet().retainAll(old.keySet());merged.entrySet().removeIf(e->!Objects.equals(e.getValue(),old.get(e.getKey())));}
                if(old==null||!old.equals(merged)){inputs.set(to,merged);todo.add(to);}
            }
        }
        return readsWhat&&selectsWhat?calls:Collections.emptySet();
    }
    private static Integer integer(Object value){return value instanceof Integer||value instanceof Long?((Number)value).intValue():null;}

    /** Recover the vendor's own reference-counted engine client. Only public
     * Binder/property API shapes are semantic anchors; owner/member names and
     * property keys are outputs learned from this APK, never adaptation tables. */
    private static Map<String,Object> discoverEngineLifecycle(Map<String,MethodInfo> methods,Map<String,ClassDef> classes) {
        final String engine="Landroid/os/IUpdateEngine;";
        List<Map<String,Object>> matches=new ArrayList<>();
        Set<String> binders=new HashSet<>();
        for(MethodInfo method:methods.values())for(String call:method.calls)
            if(call.startsWith(engine+"->bind(")&&call.endsWith(")Z"))binders.add(method.key);
        for(MethodInfo start:methods.values()) {
            if(!start.signature("Z")||(start.method.getAccessFlags()&8)!=0||!start.conditional)continue;
            boolean service=false,typed=false;
            for(String call:start.calls) {
                if(call.contains("->getService("+STRING+")Landroid/os/IBinder;"))service=true;
                if(call.contains("->asInterface(Landroid/os/IBinder;)"+engine))typed=true;
            }
            if(!service||!typed)continue;
            String owner=start.method.getDefiningClass();
            boolean bound=false;
            Set<String> reachesStart=reverseClosure(methods,Collections.singleton(start.key));
            for(String caller:reachesStart)if(reachesAny(caller,binders,methods)){bound=true;break;}
            if(!bound)continue;
            List<MethodInfo> writers=new ArrayList<>();
            for(String call:start.calls) {
                MethodInfo writer=methods.get(call);
                if(writer==null||!writer.signature("V",STRING,STRING))continue;
                Set<String> setters=new HashSet<>(),getters=new HashSet<>();
                for(String api:writer.calls) {
                    if(api.contains("->set("+STRING+STRING+")V"))setters.add(api.substring(0,api.indexOf("->")));
                    if(api.contains("->get("+STRING+STRING+")"+STRING))getters.add(api.substring(0,api.indexOf("->")));
                }
                setters.retainAll(getters);if(!setters.isEmpty())writers.add(writer);
            }
            for(MethodInfo writer:writers) {
                Map<String,Set<String>> started=propertyWrites(start,writer.key);
                for(Map.Entry<String,Set<String>> control:started.entrySet()) {
                    if(!control.getValue().containsAll(Arrays.asList("true","false")))continue;
                    List<MethodInfo> stops=new ArrayList<>();String counter=null;
                    for(MethodInfo stop:methods.values()) {
                        if(!stop.method.getDefiningClass().equals(owner)||!stop.signature("V")||
                                (stop.method.getAccessFlags()&8)!=0||!stop.conditional)continue;
                        Map<String,Set<String>> stopped=propertyWrites(stop,writer.key);
                        if(!stopped.getOrDefault(control.getKey(),Collections.emptySet()).contains("false"))continue;
                        String shared=null;
                        for(Map.Entry<String,Set<String>> entry:start.literalArguments.entrySet()) {
                            MethodInfo reader=methods.get(entry.getKey());
                            if(reader==null||!reader.signature("I",STRING)||!stop.literalArguments.containsKey(entry.getKey()))continue;
                            Set<String> common=new HashSet<>(entry.getValue());common.retainAll(stop.literalArguments.get(entry.getKey()));common.remove(control.getKey());
                            if(common.size()==1) {
                                String property=common.iterator().next();
                                if(started.containsKey(property)&&stopped.containsKey(property))shared=property;
                            }
                        }
                        if(shared!=null){stops.add(stop);counter=shared;}
                    }
                    if(stops.size()!=1)continue;
                    List<MethodInfo> factories=new ArrayList<>();
                    for(MethodInfo factory:methods.values())if(factory.method.getDefiningClass().equals(owner)&&factory.signature(owner)&&
                            (factory.method.getAccessFlags()&8)!=0&&factory.constructed.contains(owner)&&factory.conditional) {
                        boolean field=false;
                        for(Field candidate:classes.get(owner).getFields())if(candidate.getType().equals(owner)&&(candidate.getAccessFlags()&8)!=0)field=true;
                        if(field)factories.add(factory);
                    }
                    if(factories.size()!=1)continue;
                    Map<String,Object> result=new LinkedHashMap<>();result.put("class_name",javaType(owner));
                    result.put("factory",methodShape(factories.get(0)));result.put("start",methodShape(start));result.put("stop",methodShape(stops.get(0)));
                    result.put("control_property",control.getKey());result.put("counter_property",counter);
                    result.put("evidence",Arrays.asList("typed UpdateEngine ServiceManager acquisition + real bind caller graph",
                        "same property writer true/false arguments + shared native reference counter reader",
                        "conditional paired normal close + unique singleton constructor and static self field"));
                    matches.add(result);
                }
            }
        }
        return matches.size()==1?matches.get(0):Collections.emptyMap();
    }

    private static Map<String,Object> methodShape(MethodInfo method) {
        Map<String,Object> value=new LinkedHashMap<>();value.put("method_name",method.method.getName());
        List<String> args=new ArrayList<>();for(String type:types(method.method))args.add(javaType(type));
        value.put("parameter_types",args);value.put("return_type",javaType(method.method.getReturnType()));
        value.put("is_static",(method.method.getAccessFlags()&8)!=0);return value;
    }

    private static Map<String,Set<String>> propertyWrites(MethodInfo original,String writer) {
        MethodInfo method=code(original);Map<String,Set<String>> values=new LinkedHashMap<>();
        List<List<Integer>> predecessors=predecessors(method.instructions);
        for(int i=0;i<method.instructions.size();i++) {
            Instruction instruction=method.instructions.get(i);
            if(!(instruction instanceof ReferenceInstruction)||!(((ReferenceInstruction)instruction).getReference() instanceof MethodReference)||
                    !key((MethodReference)((ReferenceInstruction)instruction).getReference()).equals(writer))continue;
            int first=instruction.getOpcode().name().startsWith("INVOKE_STATIC")?0:1;
            String property=reachingString(method.instructions,predecessors,i,argumentRegister(instruction,first));
            String setting=reachingString(method.instructions,predecessors,i,argumentRegister(instruction,first+1));
            if(property!=null) {
                Set<String> settings=values.computeIfAbsent(property,key->new LinkedHashSet<>());
                if(setting!=null)settings.add(setting);
            }
        }
        return values;
    }

    private static List<List<Integer>> predecessors(List<Instruction> code) {
        List<List<Integer>> result=new ArrayList<>();Map<Integer,Integer> indices=new HashMap<>();int offset=0;
        int[] offsets=new int[code.size()];
        for(int i=0;i<code.size();i++){result.add(new ArrayList<>());offsets[i]=offset;indices.put(offset,i);offset+=code.get(i).getCodeUnits();}
        for(int i=0;i<code.size();i++) {
            Instruction instruction=code.get(i);String op=instruction.getOpcode().name();
            if(!op.startsWith("GOTO")&&!op.startsWith("RETURN")&&!op.equals("THROW")&&!(instruction instanceof PayloadInstruction)&&i+1<code.size())result.get(i+1).add(i);
            if(instruction instanceof OffsetInstruction) {
                Integer target=indices.get(offsets[i]+((OffsetInstruction)instruction).getCodeOffset());
                if(target!=null) {
                    if(op.endsWith("SWITCH")&&code.get(target) instanceof SwitchPayload)for(SwitchElement entry:((SwitchPayload)code.get(target)).getSwitchElements()) {
                        Integer branch=indices.get(offsets[i]+entry.getOffset());if(branch!=null)result.get(branch).add(i);
                    }
                    else if(op.startsWith("IF_")||op.startsWith("GOTO"))result.get(target).add(i);
                }
            }
        }
        return result;
    }

    private static String reachingString(List<Instruction> code,List<List<Integer>> predecessors,int at,int register) {
        if(register<0)return null;
        Set<String> strings=new LinkedHashSet<>(),seen=new HashSet<>();Deque<int[]> todo=new ArrayDeque<>();todo.add(new int[]{at,register});
        while(!todo.isEmpty()) {
            int[] next=todo.removeFirst();if(!seen.add(next[0]+":"+next[1]))continue;
            if(predecessors.get(next[0]).isEmpty())return null;
            for(int prior:predecessors.get(next[0])) {
                Instruction instruction=code.get(prior);String op=instruction.getOpcode().name();
                if(instruction.getOpcode().setsRegister()&&instruction instanceof OneRegisterInstruction&&((OneRegisterInstruction)instruction).getRegisterA()==next[1]) {
                    if((op.equals("CONST_STRING")||op.equals("CONST_STRING_JUMBO"))&&instruction instanceof ReferenceInstruction)
                        strings.add(((StringReference)((ReferenceInstruction)instruction).getReference()).getString());
                    else if(op.startsWith("MOVE_OBJECT")&&instruction instanceof TwoRegisterInstruction)
                        todo.add(new int[]{prior,((TwoRegisterInstruction)instruction).getRegisterB()});
                    else return null;
                } else todo.add(new int[]{prior,next[1]});
            }
        }
        return strings.size()==1?strings.iterator().next():null;
    }

    /** One native Cursor source serves both the firmware list and its count.
     * Column names are the persisted protocol, not obfuscated class identities.
     * Keeping the real query/selection contract avoids editing the database or
     * manufacturing a replacement Cursor with different navigation semantics. */
    private static void discoverVersionCache(List<Map<String,Object>> hooks,Set<String> ids,
            Map<String,MethodInfo> methods,Map<String,ClassDef> classes) {
        final String cursor="Landroid/database/Cursor;",uri="Landroid/net/Uri;",strings="[Ljava/lang/String;";
        final String querySignature="("+uri+strings+STRING+strings+STRING+")"+cursor;
        Set<String> emitted=new HashSet<>();
        for(MethodInfo mapper:methods.values()) {
            if(!types(mapper.method).equals(Collections.singletonList(cursor)) ||
               !mapper.strings.containsAll(Arrays.asList("_id","fw_state","fw_version","extends2","fw_package_type")) ||
               !(callsNamed(mapper,"getColumnIndexOrThrow")||callsNamed(mapper,"getColumnIndex")) ||
               !callsNamed(mapper,"getString") || !callsNamed(mapper,"getInt"))continue;
            String owner=mapper.method.getDefiningClass();
            for(MethodInfo list:methods.values()) {
                if(!list.method.getDefiningClass().equals(owner)||!list.signature("Ljava/util/List;")||
                   !list.calls.contains(mapper.key)||!callsNamed(list,"moveToNext"))continue;
                for(String call:list.calls) {
                    MethodInfo query=methods.get(call);
                    if(query==null||!query.key.substring(query.key.indexOf('(')).equals(querySignature)||
                       !query.calls.contains("Landroid/content/ContentResolver;->query"+querySignature))continue;
                    MethodInfo count=null;
                    for(MethodInfo candidate:methods.values())
                        if(candidate.signature("I",STRING,strings)&&candidate.calls.contains(query.key)&&
                           candidate.calls.contains(cursor+"->getCount()I")&&
                           isSubclass(owner,candidate.method.getDefiningClass(),classes,new HashSet<String>())) {
                            if(count!=null){count=null;break;}count=candidate;
                        }
                    if(count==null)continue;
                    Map<String,Object> source=null;String uriMethod=null;
                    for(MethodInfo candidate:methods.values())if(candidate.method.getDefiningClass().equals(owner)&&candidate.signature(uri)) {
                        Map<String,Object> found=returnedStaticUri(candidate,classes);
                        if(found==null)continue;
                        if(source!=null){source=null;break;}source=found;uriMethod=candidate.key;
                    }
                    if(source==null||!emitted.add(query.key+"|"+owner))continue;
                    Map<String,Object> current=currentVersionSource(list,methods,classes);
                    if(!current.containsKey("fallback_supplier"))continue;
                    Map<String,Object> h=add(hooks,ids,query,"version_cache_query",
                        "firmware Cursor column mapper + full list and Cursor.getCount share native ContentResolver.query + operator static Uri");
                    if(h==null)continue;
                    h.put("descriptor",querySignature);h.put("receiver_class",javaType(owner));h.put("uri_source",source);
                    h.put("uri_arg",0);h.put("projection_arg",1);h.put("selection_arg",2);h.put("selection_args_arg",3);h.put("order_arg",4);
                    Map<String,Object> columns=new LinkedHashMap<>();
                    columns.put("id","_id");columns.put("state","fw_state");columns.put("version","fw_version");
                    columns.put("metadata","extends2");columns.put("package_type","fw_package_type");
                    if(mapper.strings.contains("fw_update_progress"))columns.put("progress","fw_update_progress");
                    h.put("columns",columns);h.put("current_version_source",current);
                    h.put("row_mapper",mapper.key);h.put("list_method",list.key);h.put("count_method",count.key);h.put("uri_method",uriMethod);
                    List<String> pending=new ArrayList<>();
                    for(MethodInfo writer:methods.values())if(writer.strings.containsAll(Arrays.asList("fw_version","fw_package_type","fw_state"))&&
                            writesInitialFirmwareState(writer))pending.add(writer.key);
                    if(!pending.isEmpty()){h.put("pending_states",Collections.singletonList(0));h.put("pending_state_evidence",pending);}
                }
            }
        }
    }
    private static MethodInfo code(MethodInfo original) {
        MethodInfo copy=new MethodInfo(original.method);
        for(Instruction instruction:original.method.getImplementation().getInstructions())copy.instructions.add(instruction);
        return copy;
    }
    private static Map<String,Object> returnedStaticUri(MethodInfo method,Map<String,ClassDef> classes) {
        if(method.conditional)return null;
        Map<Integer,FieldReference> values=new HashMap<>();FieldReference result=null;
        for(Instruction instruction:method.method.getImplementation().getInstructions()) {
            String op=instruction.getOpcode().name();
            if(op.equals("RETURN_OBJECT")&&instruction instanceof OneRegisterInstruction) {
                FieldReference field=values.get(((OneRegisterInstruction)instruction).getRegisterA());
                if(field==null||(result!=null&&!result.equals(field)))return null;result=field;
            } else if(instruction.getOpcode().setsRegister()&&instruction instanceof OneRegisterInstruction) {
                int register=((OneRegisterInstruction)instruction).getRegisterA();FieldReference value=null;
                if(op.equals("SGET_OBJECT")&&instruction instanceof ReferenceInstruction&&
                        ((ReferenceInstruction)instruction).getReference() instanceof FieldReference) {
                    FieldReference f=(FieldReference)((ReferenceInstruction)instruction).getReference();
                    if(f.getType().equals("Landroid/net/Uri;"))value=f;
                } else if(op.startsWith("MOVE_OBJECT")&&instruction instanceof TwoRegisterInstruction)
                    value=values.get(((TwoRegisterInstruction)instruction).getRegisterB());
                values.remove(register);if(value!=null)values.put(register,value);
            }
        }
        if(result==null)return null;
        ClassDef c=classes.get(result.getDefiningClass());boolean declared=false;
        if(c!=null)for(Field field:c.getFields())if(field.getName().equals(result.getName())&&
                field.getType().equals(result.getType())&&(field.getAccessFlags()&8)!=0)declared=true;
        if(!declared)return null;
        Map<String,Object> out=new LinkedHashMap<>();out.put("class_name",javaType(result.getDefiningClass()));
        out.put("field_name",result.getName());out.put("field_type",javaType(result.getType()));return out;
    }
    private static boolean writesInitialFirmwareState(MethodInfo original) {
        MethodInfo method=code(original);Set<Integer> boundaries=blockStarts(method);
        for(int i=0;i<method.instructions.size();i++) {
            Instruction ins=method.instructions.get(i);
            if(!(ins instanceof ReferenceInstruction)||!(((ReferenceInstruction)ins).getReference() instanceof MethodReference))continue;
            MethodReference call=(MethodReference)((ReferenceInstruction)ins).getReference();
            if(!key(call).equals("Landroid/content/ContentValues;->put(Ljava/lang/String;Ljava/lang/Integer;)V"))continue;
            String column=constantString(method.instructions,i,argumentRegister(ins,1),boundaries);
            Integer value=constantInteger(method.instructions,i,argumentRegister(ins,2),boundaries);
            if("fw_state".equals(column)&&Integer.valueOf(0).equals(value))return true;
        }
        return false;
    }
    private static Integer constantInteger(List<Instruction> code,int at,int register,Set<Integer> boundaries) {
        if(register<0||boundaries.contains(at))return null;
        for(int i=at-1;i>=0;i--) {
            Instruction ins=code.get(i);String op=ins.getOpcode().name();
            if(op.startsWith("GOTO")||op.startsWith("IF_")||op.endsWith("SWITCH")||op.startsWith("RETURN")||op.equals("THROW"))return null;
            if(ins.getOpcode().setsRegister()&&ins instanceof OneRegisterInstruction&&((OneRegisterInstruction)ins).getRegisterA()==register) {
                if(op.startsWith("CONST")&&ins instanceof NarrowLiteralInstruction)return ((NarrowLiteralInstruction)ins).getNarrowLiteral();
                if(op.equals("MOVE_RESULT_OBJECT")&&i>0&&code.get(i-1) instanceof ReferenceInstruction) {
                    Instruction previous=code.get(i-1);Reference ref=((ReferenceInstruction)previous).getReference();
                    if(ref instanceof MethodReference&&key((MethodReference)ref).equals("Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;"))
                        return constantInteger(code,i-1,argumentRegister(previous,0),boundaries);
                }
                if(op.startsWith("MOVE")&&ins instanceof TwoRegisterInstruction){register=((TwoRegisterInstruction)ins).getRegisterB();continue;}
                return null;
            }
            if(boundaries.contains(i))return null;
        }
        return null;
    }
    private static Map<String,Integer> versionStates(Map<String,ClassDef> classes) {
        // These are protocol constants, read from this APK, not numeric values
        // copied from one updater release. Reject conflicting definitions.
        Map<String,Integer> found=new LinkedHashMap<>();
        for(ClassDef c:classes.values()) {
            Map<String,Integer> values=new LinkedHashMap<>();
            for(Field f:c.getFields())if(f.getType().equals("I") && f.getInitialValue() instanceof IntEncodedValue)
                values.put(f.getName(),((IntEncodedValue)f.getInitialValue()).getValue());
            if(!values.containsKey("STATUS_NEW_VERSION_AVAILABLE") || !values.containsKey("STATUS_NEW_VERSION_NOT_AVAILABLE"))continue;
            Map<String,Integer> next=new LinkedHashMap<>();
            next.put("available",values.get("STATUS_NEW_VERSION_AVAILABLE"));
            next.put("unavailable",values.get("STATUS_NEW_VERSION_NOT_AVAILABLE"));
            if(values.containsKey("STATUS_NEW_VERSION_AND_REQUEST_AGAIN"))next.put("request_again",values.get("STATUS_NEW_VERSION_AND_REQUEST_AGAIN"));
            if(!found.isEmpty() && !found.equals(next))return Collections.emptyMap();
            found=next;
        }
        return found;
    }
    private static Map<String,Object> currentVersionSource(MethodInfo entry,Map<String,MethodInfo> methods,Map<String,ClassDef> classes) {
        Map<String,Object> out=new LinkedHashMap<>();
        Set<String> visited=new HashSet<>();String owner=entry.method.getDefiningClass();
        while(owner!=null && visited.add(owner)) {
            ClassDef c=classes.get(owner);if(c==null)break;
            for(Field f:c.getFields()) {
                MethodInfo getter=methods.get(f.getType()+"->getDeviceJointVersion()"+STRING);
                if(getter!=null) {
                    out.put("field_owner",javaType(owner));out.put("field_name",f.getName());
                    out.put("field_type",javaType(f.getType()));out.put("getter",getter.method.getName());break;
                }
            }
            if(out.containsKey("getter"))break;owner=c.getSuperclass();
        }
        // Complete firmware assembly is also semantically discoverable when an
        // older client has no request object's joint-version getter.
        for(MethodInfo method:methods.values())if(method.signature(STRING) &&
           (method.method.getAccessFlags()&8)!=0 && method.contains("getFirmwareVersion()")) {
            Map<String,Object> supplier=new LinkedHashMap<>();
            supplier.put("class_name",javaType(method.method.getDefiningClass()));supplier.put("method_name",method.method.getName());
            out.put("fallback_supplier",supplier);break;
        }
        return out;
    }

    private static boolean settingsGetter(MethodInfo m,String space) {
        for(String c:m.calls) if(c.startsWith("Landroid/provider/Settings$"+space+";->getInt(") || c.startsWith("Landroid/provider/Settings$"+space+";->getIntForUser(")) return true;
        return false;
    }
    private static boolean hasBinderTransaction(MethodInfo m) {
        for(String c:m.calls)if(c.startsWith("Landroid/os/IBinder;->transact("))return true;
        return false;
    }
    private static void setting(List<Map<String,Object>> out,Set<String> ids,MethodInfo m,String role,String namespace,String key,int value) {
        Map<String,Object> h=add(out,ids,m,role,"literal preference key + Settings."+namespace+".getInt read; preserve caller predicate/data flow");
        if(h!=null){h.put("setting_namespace",namespace);h.put("setting_key",key);h.put("setting_value",value);}
    }
    private static boolean singleActionTimer(MethodInfo m) {
        // A shared timer may also drive unrelated waiting or download branches.
        // Do not replace its entire behavior merely because one branch reboots.
        return !m.conditional;
    }
    private static boolean isManual(String s) {
        return s.equalsIgnoreCase("manul") || s.equalsIgnoreCase("manual") || s.equalsIgnoreCase("manually");
    }
    private static boolean isAutomatic(String s) {
        return s.trim().toLowerCase(Locale.ROOT).matches("^(auto|automatic|night|scheduled?|countdown).*");
    }
    private static Map<String,Object> add(List<Map<String,Object>> out,Set<String> ids,MethodInfo m,String role,String evidence) {
        if(!ids.add(role+":"+m.key)) return null;
        Map<String,Object> v=new LinkedHashMap<>();v.put("role",role);
        v.put("class_name",javaType(m.method.getDefiningClass()));v.put("method_name",m.method.getName());
        List<String> args=new ArrayList<>();for(String t:types(m.method)) args.add(javaType(t));
        v.put("parameter_types",args);v.put("return_type",javaType(m.method.getReturnType()));
        v.put("is_static",(m.method.getAccessFlags() & 8)!=0);
        v.put("evidence",Collections.singletonList(evidence));out.add(v);return v;
    }
    private static void inspect(MethodInfo m) {
        Set<Integer> boundaries=blockStarts(m);
        for(int i=0;i<m.instructions.size();i++) {
            Instruction ins=m.instructions.get(i);
            String opcode=ins.getOpcode().name();
            if(opcode.startsWith("IF_") || opcode.endsWith("SWITCH"))m.conditional=true;
            if(!(ins instanceof ReferenceInstruction)) continue;
            Reference ref=((ReferenceInstruction)ins).getReference();
            if(ref instanceof StringReference) m.strings.add(((StringReference)ref).getString());
            if(ref instanceof MethodReference) {
                MethodReference call=(MethodReference)ref;String key=key(call);m.calls.add(key);
                if(call.getName().equals("<init>"))m.constructed.add(call.getDefiningClass());
                if(types(call).contains("Ljava/lang/Runnable;"))m.submitsRunnable=true;
                if(types(call).equals(Collections.singletonList(STRING))) {
                    int register=argumentRegister(ins,ins.getOpcode().name().startsWith("INVOKE_STATIC")?0:1);
                    String value=constantString(m.instructions,i,register,boundaries);
                    if(value!=null) m.literalArguments.computeIfAbsent(key,k->new LinkedHashSet<>()).add(value);
                }
            }
        }
    }
    private static int argumentRegister(Instruction ins,int index) {
        if(ins instanceof FiveRegisterInstruction) {
            FiveRegisterInstruction f=(FiveRegisterInstruction)ins;
            if(index>=f.getRegisterCount()) return -1;
            return new int[]{f.getRegisterC(),f.getRegisterD(),f.getRegisterE(),f.getRegisterF(),f.getRegisterG()}[index];
        }
        if(ins instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction r=(RegisterRangeInstruction)ins;
            return index<r.getRegisterCount()?r.getStartRegister()+index:-1;
        }
        return -1;
    }
    private static Set<Integer> blockStarts(MethodInfo m) {
        Map<Integer,Integer> index=new HashMap<>();int address=0;
        int[] offsets=new int[m.instructions.size()];
        for(int i=0;i<m.instructions.size();i++){offsets[i]=address;index.put(address,i);address+=m.instructions.get(i).getCodeUnits();}
        Set<Integer> starts=new HashSet<>();
        for(int i=0;i<m.instructions.size();i++) {
            Instruction ins=m.instructions.get(i);String op=ins.getOpcode().name();
            if(ins instanceof OffsetInstruction) {
                int target=offsets[i]+((OffsetInstruction)ins).getCodeOffset();
                Integer targetIndex=index.get(target);
                if(targetIndex!=null && (op.startsWith("GOTO")||op.startsWith("IF_")))starts.add(targetIndex);
                if(targetIndex!=null && op.endsWith("SWITCH") && m.instructions.get(targetIndex) instanceof SwitchPayload)
                    for(SwitchElement e:((SwitchPayload)m.instructions.get(targetIndex)).getSwitchElements()) {
                        Integer jump=index.get(offsets[i]+e.getOffset());if(jump!=null)starts.add(jump);
                    }
            }
        }
        for(TryBlock<? extends ExceptionHandler> t:m.method.getImplementation().getTryBlocks())
            for(ExceptionHandler e:t.getExceptionHandlers()){Integer jump=index.get(e.getHandlerCodeAddress());if(jump!=null)starts.add(jump);}
        return starts;
    }
    private static String constantString(List<Instruction> code,int at,int register,Set<Integer> boundaries) {
        if(register<0 || boundaries.contains(at)) return null;
        for(int i=at-1;i>=0;i--) {
            Instruction ins=code.get(i);String op=ins.getOpcode().name();
            // Stay within a straight-line definition region; never infer a literal
            // through an unproved branch or merge.
            if(op.startsWith("GOTO") || op.startsWith("IF_") || op.endsWith("SWITCH") || op.startsWith("RETURN") || op.equals("THROW")) return null;
            if(ins.getOpcode().setsRegister() && ins instanceof OneRegisterInstruction && ((OneRegisterInstruction)ins).getRegisterA()==register) {
                if(op.equals("CONST_STRING") || op.equals("CONST_STRING_JUMBO"))
                    return ((StringReference)((ReferenceInstruction)ins).getReference()).getString();
                if(op.startsWith("MOVE_OBJECT") && ins instanceof TwoRegisterInstruction) {
                    register=((TwoRegisterInstruction)ins).getRegisterB();continue;
                }
                return null;
            }
            if(boundaries.contains(i))return null;
        }
        return null;
    }
    private static Set<String> reverseClosure(Map<String,MethodInfo> methods,Set<String> roots) {
        Map<String,Set<String>> reverse=new HashMap<>();
        for(MethodInfo m:methods.values()) for(String callee:m.calls) reverse.computeIfAbsent(callee,k->new HashSet<>()).add(m.key);
        Set<String> found=new HashSet<>(roots);Deque<String> q=new ArrayDeque<>(roots);
        while(!q.isEmpty()) for(String caller:reverse.getOrDefault(q.removeFirst(),Collections.<String>emptySet())) if(found.add(caller)) q.addLast(caller);
        return found;
    }

    /** Normal dispatch preservation. The native Binder barrier, not this small
     * exact-reference closure, is the final defense against old inlined callers. */
    private static Map<String,Object> criticalDispatchPlan(Map<String,MethodInfo> methods,Map<String,ClassDef> classes,
            List<Map<String,Object>> hooks,List<Map<String,Object>> continuations) {
        Set<String> roles=new HashSet<>(Arrays.asList("reboot_gate","reboot_terminal","reboot_context","countdown","countdown_timer","payload_start",
            "full_package","version_response","version_cache_query","payload_complete"));
        Set<String> protectedRoots=new TreeSet<>(), versionCacheRoots=new TreeSet<>();
        Map<String,Map<String,Object>> external=new TreeMap<>();
        for(Map<String,Object> hook:hooks)if(roles.contains(hook.get("role"))) {
            String name=shapeKey((String)hook.get("class_name"),hook);
            ("version_cache_query".equals(hook.get("role")) ? versionCacheRoots : protectedRoots).add(name);
            if(!methods.containsKey(name))external.put(name,new LinkedHashMap<>(hook));
            for(String field:Arrays.asList("actual_callers","payload_callers")) {
                Object callers=hook.get(field);if(callers instanceof Collection)for(Object caller:(Collection<?>)callers)
                    if(caller instanceof String && methods.containsKey(caller))protectedRoots.add((String)caller);
            }
            if("countdown_timer".equals(hook.get("role")))addFramework(external,protectedRoots,"android.os.CountDownTimer","start","android.os.CountDownTimer",false);
        }
        for(Map<String,Object> item:continuations) {
            String owner="L"+((String)item.get("class_name")).replace('.','/')+";";
            for(MethodInfo m:methods.values())if(owner.equals(m.method.getDefiningClass()) &&
                    (m.method.getName().equals("<init>")||(m.method.getName().equals("run")&&m.signature("V"))))protectedRoots.add(m.key);
        }
        Set<String> roots=new TreeSet<>(protectedRoots);
        roots.addAll(versionCacheRoots);
        // payload_callers already proves the interface/Proxy protocol aliases.
        // Generic Runnable/Listener CHA expansion is intentionally not claimed
        // here: native IPC independently protects those old inline paths.
        Set<String> selected=new TreeSet<>(reverseClosure(methods,roots));
        if(!continuations.isEmpty())addFramework(external,selected,"java.lang.Thread","run","void",false);
        for(String name:new ArrayList<>(selected)) {
            MethodInfo m=methods.get(name);if(m==null)continue;
            if(m.method.getName().equals("handleMessage")&&m.signature("V","Landroid/os/Message;")&&
                    isSubclass(m.method.getDefiningClass(),"Landroid/os/Handler;",classes,new HashSet<String>()))
                addFramework(external,selected,"android.os.Handler","dispatchMessage","void",false,"android.os.Message");
            if(m.method.getName().equals("onClick")&&m.signature("V","Landroid/view/View;")&&
                    isSubclass(m.method.getDefiningClass(),"Landroid/view/View$OnClickListener;",classes,new HashSet<String>())) {
                addFramework(external,selected,"android.view.View","performClick","boolean",false);
                addFramework(external,selected,"android.view.View","callOnClick","boolean",false);
            }
        }
        List<Map<String,Object>> entries=new ArrayList<>();
        List<String> versionCacheInitializers=new ArrayList<>();
        Set<String> protectedCallers=null;
        for(String name:selected) {
            MethodInfo m=methods.get(name);Map<String,Object> entry;
            // Reflection cannot bind a class initializer. Keep the full graph
            // and every root. Only omit its own ART mutation if its exact
            // caller closure reaches the read-only version cache alone.
            if(m!=null && !versionCacheRoots.isEmpty() && !roots.contains(name) &&
                    "<clinit>".equals(m.method.getName()) && m.signature("V") &&
                    (m.method.getAccessFlags()&0x8)!=0) {
                if(protectedCallers==null)protectedCallers=reverseClosure(methods,protectedRoots);
                if(!protectedCallers.contains(name)) {
                    versionCacheInitializers.add(name);
                    continue;
                }
            }
            if(m!=null) {entry=methodShape(m);entry.put("class_name",javaType(m.method.getDefiningClass()));}
            else {Map<String,Object> shape=external.get(name);if(shape==null)continue;entry=new LinkedHashMap<>(shape);}
            entry.put("dex_method",name);entries.add(entry);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("policy","critical-dispatch-native-barrier-v1");
        result.put("requires_native_barrier",true);result.put("full_inline_caller_proof",false);
        result.put("roots",new ArrayList<>(roots));result.put("method_count",entries.size());
        if(!versionCacheInitializers.isEmpty())result.put("version_cache_initializer_callers",versionCacheInitializers);
        try {
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(GZIPOutputStream gzip=new GZIPOutputStream(buffer)){gzip.write(json(entries).getBytes(StandardCharsets.UTF_8));}
            result.put("encoding","gzip-base64-json-v1");result.put("methods_gzip_base64",Base64.getEncoder().encodeToString(buffer.toByteArray()));
        } catch(IOException impossible){throw new IllegalStateException("In-memory dispatch-plan encoding failed",impossible);}
        result.put("evidence","critical roots + actual typed payload/terminal callers + exact app reverse references + real callback dispatch boundaries; native barrier independently blocks any ungranted inlined reboot");
        return result;
    }

    private static String shapeKey(String owner,Map<String,Object> shape) {
        StringBuilder s=new StringBuilder(dexType(owner)).append("->").append(shape.get("method_name")).append('(');
        for(Object t:(Collection<?>)shape.get("parameter_types"))s.append(dexType((String)t));
        return s.append(')').append(dexType((String)shape.get("return_type"))).toString();
    }
    private static String dexType(String type) {
        switch(type){case "void":return "V";case "boolean":return "Z";case "byte":return "B";case "char":return "C";case "short":return "S";case "int":return "I";case "long":return "J";case "float":return "F";case "double":return "D";default:break;}
        return type.startsWith("[")?type.replace('.','/'):"L"+type.replace('.','/')+";";
    }
    private static void addFramework(Map<String,Map<String,Object>> entries,Set<String> names,String owner,String method,String result,boolean isStatic,String...args) {
        Map<String,Object> shape=new LinkedHashMap<>();shape.put("class_name",owner);shape.put("method_name",method);
        shape.put("parameter_types",Arrays.asList(args));shape.put("return_type",result);shape.put("is_static",isStatic);
        String name=shapeKey(owner,shape);entries.put(name,shape);names.add(name);
    }
    private static boolean reachesAny(String from,Collection<String> targets,Map<String,MethodInfo> methods) {
        for(String to:targets) if(reachable(from,to,methods)) return true;return false;
    }
    private static boolean reachable(String from,String target,Map<String,MethodInfo> methods) {
        Set<String> seen=new HashSet<>();Deque<String> q=new ArrayDeque<>();q.add(from);
        while(!q.isEmpty()) {
            String n=q.removeFirst();if(!seen.add(n))continue;if(n.equals(target))return true;
            MethodInfo m=methods.get(n);if(m!=null) for(String c:m.calls) if(methods.containsKey(c))q.addLast(c);
        }
        return false;
    }
    private static boolean asynchronouslyReachable(String from,String target,Map<String,MethodInfo> methods) {
        Set<String> seen=new HashSet<>();Deque<String> nodes=new ArrayDeque<>();Deque<Boolean> asynchronous=new ArrayDeque<>();
        nodes.add(from);asynchronous.add(false);
        while(!nodes.isEmpty()) {
            String name=nodes.removeFirst();boolean crossed=asynchronous.removeFirst();
            if(!seen.add((crossed?"async:":"sync:")+name))continue;
            if(crossed && name.equals(target))return true;
            MethodInfo method=methods.get(name);if(method==null)continue;
            for(String next:method.calls)if(methods.containsKey(next)) {
                nodes.addLast(next);asynchronous.addLast(crossed||method.asynchronousCalls.contains(next));
            }
        }
        return false;
    }
    private static boolean isSubclass(String type,String target,Map<String,ClassDef> classes,Set<String> seen) {
        if(type==null)return false;if(type.equals(target))return true;if(!seen.add(type))return false;
        ClassDef c=classes.get(type);if(c==null)return false;
        if(isSubclass(c.getSuperclass(),target,classes,seen))return true;
        for(String i:c.getInterfaces())if(isSubclass(i,target,classes,seen))return true;return false;
    }
    private static List<String> types(MethodReference m) {
        List<String> out=new ArrayList<>();for(CharSequence t:m.getParameterTypes())out.add(t.toString());return out;
    }
    private static String key(MethodReference m) {
        StringBuilder s=new StringBuilder(m.getDefiningClass()).append("->").append(m.getName()).append('(');
        for(CharSequence t:m.getParameterTypes())s.append(t);return s.append(')').append(m.getReturnType()).toString();
    }
    private static String javaType(String d) {
        switch(d) {case "V":return "void";case "Z":return "boolean";case "I":return "int";case "J":return "long";case "F":return "float";case "D":return "double";case "B":return "byte";case "S":return "short";case "C":return "char";default:break;}
        if(d.startsWith("["))return d.replace('/','.');return d.substring(1,d.length()-1).replace('/','.');
    }
    private static String sha256(File f)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];
        try(InputStream in=new FileInputStream(f)){int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder s=new StringBuilder();for(byte v:digest.digest())s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();
    }
    public static String json(Object value) {
        if(value==null)return "null";if(value instanceof Boolean || value instanceof Number)return value.toString();
        if(value instanceof Map) {StringJoiner j=new StringJoiner(",","{","}");for(Map.Entry<?,?>e:((Map<?,?>)value).entrySet())j.add(json(e.getKey().toString())+":"+json(e.getValue()));return j.toString();}
        if(value instanceof Collection){StringJoiner j=new StringJoiner(",","[","]");for(Object v:(Collection<?>)value)j.add(json(v));return j.toString();}
        StringBuilder s=new StringBuilder("\"");for(char c:value.toString().toCharArray())switch(c){case '"':s.append("\\\"");break;case '\\':s.append("\\\\");break;case '\n':s.append("\\n");break;case '\r':s.append("\\r");break;case '\t':s.append("\\t");break;default:if(c<32)s.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else s.append(c);}return s.append('"').toString();
    }
    public static void main(String[] args)throws Exception {
        if(args.length<1 || args.length>2)throw new IllegalArgumentException("APK [OUTPUT_JSON]");
        String result=discover(new File(args[0]));
        if(args.length==2)try(OutputStream out=new FileOutputStream(args[1])){out.write(result.getBytes(StandardCharsets.UTF_8));}
        else System.out.println(result);
    }
}
