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

/** DEX semantics, descriptors and invoke edges; no vendor class/version/address table. */
public final class OucDiscovery {
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
            if(m.method.getName().equals("applyPayload") && m.method.getReturnType().equals("V") && hasBinderTransaction(m) &&
               types(m.method).equals(Arrays.asList(STRING,"J","J","[Ljava/lang/String;")))
                add(hooks,hookIds,m,"payload_start","UpdateEngine applyPayload protocol descriptor");
            if(m.method.getReturnType().equals("V") && hasBinderTransaction(m) &&
               ((m.method.getName().equals("applyMultiPayloads") && types(m.method).equals(Arrays.asList("[Ljava/lang/String;","[J","[J","[Ljava/lang/String;"))) ||
                (m.method.getName().equals("applyUpdateZip") && types(m.method).isEmpty()) ||
                (m.method.getName().equals("startUpdateZip") && types(m.method).equals(Collections.singletonList(STRING)))) &&
               isSubclass(m.method.getDefiningClass(),"Landroid/os/IUpdateEngine;",classes,new HashSet<String>()))
                add(hooks,hookIds,m,"payload_start","UpdateEngine binder payload/ZIP application protocol descriptor");
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

        discoverVersionCache(hooks,hookIds,methods,classes);
        Set<String> roles=new HashSet<>();for(Map<String,Object> h:hooks) roles.add((String)h.get("role"));
        List<String> missing=new ArrayList<>();
        if(!roles.contains("reboot_gate")) missing.add("reboot_gate");
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
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("schema",1);result.put("package","com.hihonor.ouc");
        result.put("apk_sha256",sha256(apk));result.put("discovery_complete",missing.isEmpty());
        result.put("missing",missing);result.put("hooks",hooks);
        result.put("critical_missing",missing);result.put("optional_missing",optionalMissing);
        result.put("critical_roles",Arrays.asList("manual_reboot_gate","automatic_reboot_gate","countdown/countdown_timer","payload_start"));
        result.put("unproved_asynchronous_frontiers",asynchronousFrontiers);
        result.put("method_count",methods.size());
        result.put("policy","semantic anchors + DEX descriptors + literal argument flow + invoke graph; no model/version/address table");
        return json(result);
    }

    private static boolean callsNamed(MethodInfo m,String name) {
        for(String call:m.calls)if(call.contains("->"+name+"("))return true;return false;
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
