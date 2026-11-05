package net.hawyn.hnubl.discovery;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.google.gson.*;
import java.io.File;
import java.util.*;
import java.io.ByteArrayInputStream;
import java.util.zip.GZIPInputStream;

/** Actual APK source/descriptor/closure test; never loads an Android class. */
public final class SelectiveDeoptimizationPlanTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static String key(MethodReference m){StringBuilder s=new StringBuilder(m.getDefiningClass()).append("->").append(m.getName()).append('(');for(CharSequence p:m.getParameterTypes())s.append(p);return s.append(')').append(m.getReturnType()).toString();}
    private static String type(String t){switch(t){case "void":return "V";case "boolean":return "Z";case "byte":return "B";case "char":return "C";case "short":return "S";case "int":return "I";case "long":return "J";case "float":return "F";case "double":return "D";default:return t.startsWith("[")?t.replace('.','/'):"L"+t.replace('.','/')+";";}}
    private static String shape(String owner,JsonObject m){StringBuilder s=new StringBuilder(type(owner)).append("->").append(m.get("method_name").getAsString()).append('(');for(JsonElement p:m.getAsJsonArray("parameter_types"))s.append(type(p.getAsString()));return s.append(')').append(type(m.get("return_type").getAsString())).toString();}
    public static void main(String[] args)throws Exception {
        File apk=new File(args[0]);JsonObject discovered=JsonParser.parseString(OucDiscovery.discover(apk)).getAsJsonObject();
        check(discovered.get("discovery_complete").getAsBoolean(),"original critical feature discovery remains complete");
        JsonObject plan=discovered.getAsJsonObject("deoptimization_plan");
        check(plan.get("policy").getAsString().equals("critical-dispatch-native-barrier-v1"),"explicit plan policy");
        check(plan.get("requires_native_barrier").getAsBoolean()&&!plan.get("full_inline_caller_proof").getAsBoolean(),"small dispatch plan never claims full CHA protection");
        Map<String,Method> definitions=new HashMap<>();Map<String,Set<String>> calls=new HashMap<>();
        MultiDexContainer<? extends DexFile> container=DexFileFactory.loadDexContainer(apk,Opcodes.getDefault());
        for(String entry:container.getDexEntryNames())for(ClassDef c:container.getEntry(entry).getDexFile().getClasses())for(Method m:c.getMethods())if(m.getImplementation()!=null){
            String caller=key(m);definitions.put(caller,m);Set<String> refs=new HashSet<>();calls.put(caller,refs);
            for(Instruction ins:m.getImplementation().getInstructions())if(ins.getOpcode().name().startsWith("INVOKE_")&&ins instanceof ReferenceInstruction){Object ref=((ReferenceInstruction)ins).getReference();if(ref instanceof MethodReference)refs.add(key((MethodReference)ref));}
        }
        JsonArray specifications;
        if(plan.has("methods"))specifications=plan.getAsJsonArray("methods");
        else {
            check("gzip-base64-json-v1".equals(plan.get("encoding").getAsString()),"recognized compressed encoding");
            try(GZIPInputStream gzip=new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(plan.get("methods_gzip_base64").getAsString())))){
                specifications=JsonParser.parseString(new String(gzip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
            }
        }
        Set<String> selected=new HashSet<>();
        for(JsonElement value:specifications){JsonObject m=value.getAsJsonObject();String name=m.get("dex_method").getAsString();
            check(selected.add(name),"unique method "+name);check(name.equals(shape(m.get("class_name").getAsString(),m)),"exact overload descriptor "+name);
            if(definitions.containsKey(name))check((definitions.get(name).getAccessFlags()&(0x100|0x400))==0,"selected app method has Java code "+name);
        }
        check(selected.size()==plan.get("method_count").getAsInt(),"method count is exact");
        for(JsonElement root:plan.getAsJsonArray("roots"))check(selected.contains(root.getAsString()),"all plan roots selected");
        Set<String> roles=Set.of("reboot_gate","reboot_terminal","reboot_context","countdown","countdown_timer","payload_start","full_package","version_response","version_cache_query","payload_complete");
        for(JsonElement value:discovered.getAsJsonArray("hooks")){JsonObject h=value.getAsJsonObject();if(!roles.contains(h.get("role").getAsString()))continue;
            check(selected.contains(shape(h.get("class_name").getAsString(),h)),"every critical hook selected");
            if(h.has("payload_callers"))for(JsonElement caller:h.getAsJsonArray("payload_callers"))check(selected.contains(caller.getAsString()),"typed interface payload caller selected");
        }
        for(Map.Entry<String,Set<String>> caller:calls.entrySet())for(String callee:caller.getValue())if(definitions.containsKey(callee)&&selected.contains(callee))
            check(selected.contains(caller.getKey()),"exact packaged reverse invoke closure: "+caller.getKey()+" => "+callee);
        check(discovered.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<128*1024,"mapping fits existing Android single argument ABI");
        System.out.println("SELECTIVE_PLAN_CHECKS="+checks+" METHODS="+selected.size()+" APK="+discovered.get("apk_sha256").getAsString());
    }
}
