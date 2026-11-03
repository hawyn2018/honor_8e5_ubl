package net.hawyn.hnubl.discovery;

import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Synthetic DEX invoke graphs; never installed, verified by ART or executed. */
public final class DiscoveryRebootFrontierTest {
    private static final String OWNER="Lfixture/RebootPolicy;",TASK="Lfixture/QueuedTask;",STRING="Ljava/lang/String;";
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static ImmutableInstruction10x ret(){return new ImmutableInstruction10x(Opcode.RETURN_VOID);}
    private static ImmutableInstruction21c text(int register,String value){return new ImmutableInstruction21c(Opcode.CONST_STRING,register,new ImmutableStringReference(value));}
    private static ImmutableInstruction35c invoke(Opcode opcode,String owner,String name,List<String> args,String result,int... registers){
        int[] r=new int[5];System.arraycopy(registers,0,r,0,registers.length);
        return new ImmutableInstruction35c(opcode,registers.length,r[0],r[1],r[2],r[3],r[4],new ImmutableMethodReference(owner,name,args,result));
    }
    private static ImmutableMethod method(String owner,String name,int flags,List<String> args,int registers,ImmutableInstruction... code){
        List<ImmutableMethodParameter> parameters=new ArrayList<>();for(String type:args)parameters.add(new ImmutableMethodParameter(type,Set.of(),null));
        return new ImmutableMethod(owner,name,parameters,"V",flags,Set.of(),Set.of(),new ImmutableMethodImplementation(registers,List.of(code),List.of(),List.of()));
    }
    private static ImmutableMethod gate(String name){return method(OWNER,name,9,List.of(STRING),2,
        invoke(Opcode.INVOKE_STATIC,OWNER,"sink",List.of(STRING),"V",1),ret());}
    private static ImmutableMethod caller(String name,String gate,String reason){return method(OWNER,name,9,List.of(),1,text(0,reason),
        invoke(Opcode.INVOKE_STATIC,OWNER,gate,List.of(STRING),"V",0),ret());}
    private static ImmutableClassDef type(String owner,List<String> interfaces,List<ImmutableMethod> methods){return new ImmutableClassDef(owner,1,"Ljava/lang/Object;",interfaces,null,Set.of(),List.of(),methods);}
    private static File fixture(Path out,String kind)throws Exception{
        List<ImmutableMethod> methods=new ArrayList<>();List<ClassDef> classes=new ArrayList<>();
        methods.add(method(OWNER,"sink",9,List.of(STRING),2,new ImmutableInstruction11n(Opcode.CONST_4,0,0),
            invoke(Opcode.INVOKE_VIRTUAL,"Landroid/os/PowerManager;","reboot",List.of(STRING),"V",0,1),ret()));
        methods.add(method(OWNER,"countdown",9,List.of(),1,text(0,"automatic install countdown"),ret()));
        methods.add(method(OWNER,"decoy",9,List.of(STRING),2,text(0,"reboot log only"),ret()));
        methods.add(caller("decoyCaller","decoy","auto"));
        if(kind.equals("independent")){
            methods.add(gate("shared"));methods.add(gate("autoOnly"));methods.add(gate("manualOnly"));
            methods.add(caller("manualCaller","shared","manual"));methods.add(caller("autoCaller","shared","auto"));
            methods.add(caller("independentAutoCaller","autoOnly","night_policy"));methods.add(caller("independentManualCaller","manualOnly","manual"));
        }else{
            methods.add(gate("inner"));methods.add(caller("manualCaller","outer","manual"));methods.add(caller("autoCaller","outer","auto"));
            if(kind.equals("synchronous"))methods.add(method(OWNER,"outer",9,List.of(STRING),2,text(0,"manual"),
                invoke(Opcode.INVOKE_STATIC,OWNER,"inner",List.of(STRING),"V",0),ret()));
            else{
                methods.add(method(OWNER,"outer",9,List.of(STRING),3,
                    new ImmutableInstruction21c(Opcode.NEW_INSTANCE,0,new ImmutableTypeReference(TASK)),
                    invoke(Opcode.INVOKE_DIRECT,TASK,"<init>",List.of(),"V",0),new ImmutableInstruction11n(Opcode.CONST_4,1,0),
                    invoke(Opcode.INVOKE_INTERFACE,"Ljava/util/concurrent/Executor;","execute",List.of("Ljava/lang/Runnable;"),"V",1,0),ret()));
                classes.add(type(TASK,List.of("Ljava/lang/Runnable;"),List.of(
                    method(TASK,"<init>",65537,List.of(),1,invoke(Opcode.INVOKE_DIRECT,"Ljava/lang/Object;","<init>",List.of(),"V",0),ret()),
                    method(TASK,"run",1,List.of(),2,text(0,"manual"),invoke(Opcode.INVOKE_STATIC,OWNER,"inner",List.of(STRING),"V",0),ret()))));
            }
        }
        classes.add(type(OWNER,List.of(),methods));
        classes.add(type("Landroid/os/IUpdateEngine$Stub$Proxy;",List.of("Landroid/os/IUpdateEngine;"),List.of(
            method("Landroid/os/IUpdateEngine$Stub$Proxy;","applyPayload",1,List.of(STRING,"J","J","[Ljava/lang/String;"),8,
                new ImmutableInstruction11n(Opcode.CONST_4,0,0),new ImmutableInstruction11n(Opcode.CONST_4,1,0),
                invoke(Opcode.INVOKE_INTERFACE,"Landroid/os/IBinder;","transact",List.of("I","Landroid/os/Parcel;","Landroid/os/Parcel;","I"),"Z",0,1,0,0,1),ret()))));
        Path dex=out.resolve(kind+".dex");DexPool.writeTo(dex.toString(),new ImmutableDexFile(Opcodes.getDefault(),classes));
        File apk=out.resolve("TEST-ONLY-"+kind+"-UNSIGNED.apk").toFile();
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(apk))){zip.putNextEntry(new ZipEntry("classes.dex"));Files.copy(dex,zip);zip.closeEntry();}
        return apk;
    }
    private static Set<String> gates(JsonObject mapping){Set<String> out=new TreeSet<>();for(JsonElement element:mapping.getAsJsonArray("hooks")){
        JsonObject hook=element.getAsJsonObject();if(hook.get("role").getAsString().equals("reboot_gate"))out.add(hook.get("method_name").getAsString());}return out;}
    public static void main(String[] args)throws Exception{
        check(args.length==1,"NEW_OUTPUT_DIRECTORY");Path out=Path.of(args[0]);check(!Files.exists(out),"Exclusive outputs required");Files.createDirectories(out);
        JsonObject report=new JsonObject();
        for(String kind:List.of("independent","synchronous","asynchronous")){
            JsonObject map=JsonParser.parseString(OucDiscovery.discover(fixture(out,kind))).getAsJsonObject();Set<String> actual=gates(map);
            if(kind.equals("independent")){
                check(actual.equals(Set.of("shared","autoOnly","manualOnly")),"A shared gate suppressed an independent manual/automatic entry: "+actual);
                check(map.get("discovery_complete").getAsBoolean(),"Independent proven entries should be complete");
            }else if(kind.equals("synchronous")){
                check(actual.equals(Set.of("outer","inner")),"Synchronous entry coverage lost");
                check(map.get("discovery_complete").getAsBoolean(),"Same-thread nested allowance should be supported");
            }else{
                check(!map.get("discovery_complete").getAsBoolean(),"Unproved asynchronous permission propagation falsely complete");
                check(map.getAsJsonArray("unproved_asynchronous_frontiers").size()>0,"Missing asynchronous ambiguity evidence");
            }
            check(!actual.contains("decoy"),"A reboot string without a real reboot invoke was hooked");report.add(kind,map);
        }
        report.addProperty("passed",true);report.addProperty("fixtureOnly",true);report.addProperty("deviceOperations",false);
        Files.writeString(out.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("Independent, synchronous and unproved-asynchronous reboot frontier tests passed: "+out);
    }
}
