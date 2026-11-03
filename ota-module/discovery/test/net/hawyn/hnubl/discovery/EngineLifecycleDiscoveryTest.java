package net.hawyn.hnubl.discovery;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.rewriter.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Real DEX mutations, never installed or executed. */
public final class EngineLifecycleDiscoveryTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String owner(JsonObject map){return "L"+map.get("class_name").getAsString().replace('.','/')+";";}
    private static String key(MethodReference method){StringBuilder out=new StringBuilder(method.getDefiningClass()).append("->").append(method.getName()).append('(');for(CharSequence arg:method.getParameterTypes())out.append(arg);return out.append(')').append(method.getReturnType()).toString();}
    private static Set<String> strings(Method method){Set<String> out=new HashSet<>();for(Instruction ins:method.getImplementation().getInstructions())if(ins instanceof ReferenceInstruction&&((ReferenceInstruction)ins).getReference() instanceof StringReference)out.add(((StringReference)((ReferenceInstruction)ins).getReference()).getString());return out;}
    static void verify(File apk,JsonObject mapping)throws Exception{
        JsonObject engine=mapping.getAsJsonObject("engine_lifecycle");check(engine!=null&&!engine.isEmpty(),"No semantic engine lifecycle");
        String owner=owner(engine);Map<String,Method> methods=new HashMap<>();var container=DexFileFactory.loadDexContainer(apk,null);
        for(String entry:container.getDexEntryNames())for(ClassDef type:container.getEntry(entry).getDexFile().getClasses())if(type.getType().equals(owner))for(Method method:type.getMethods())methods.put(method.getName(),method);
        for(String role:List.of("factory","start","stop")){
            JsonObject shape=engine.getAsJsonObject(role);Method method=methods.get(shape.get("method_name").getAsString());
            check(method!=null&&method.getImplementation()!=null,"Real lifecycle method absent: "+role);
            check(method.getParameterTypes().isEmpty(),"Lifecycle needs unknown arguments");
            check(((method.getAccessFlags()&8)!=0)==shape.get("is_static").getAsBoolean(),"Static/instance ABI differs");
        }
        Method start=methods.get(engine.getAsJsonObject("start").get("method_name").getAsString()),stop=methods.get(engine.getAsJsonObject("stop").get("method_name").getAsString());
        check(start.getReturnType().equals("Z")&&stop.getReturnType().equals("V"),"Open/close ABI differs");
        boolean service=false,typed=false;
        for(Instruction instruction:start.getImplementation().getInstructions())if(instruction instanceof ReferenceInstruction&&((ReferenceInstruction)instruction).getReference() instanceof MethodReference){
            String reference=key((MethodReference)((ReferenceInstruction)instruction).getReference());
            service|=reference.contains("->getService(Ljava/lang/String;)Landroid/os/IBinder;");
            typed|=reference.contains("->asInterface(Landroid/os/IBinder;)Landroid/os/IUpdateEngine;");
        }
        check(service&&typed,"Start does not acquire the real typed service");
        for(String field:List.of("control_property","counter_property")){
            String property=engine.get(field).getAsString();check(strings(start).contains(property)&&strings(stop).contains(property),"Paired property identity absent: "+field);
        }
    }
    private static File mutate(File original,Path out,JsonObject engine,String mode)throws Exception{
        Files.createDirectory(out);String target=owner(engine)+"->"+engine.getAsJsonObject(mode.equals("start-value")?"start":"stop").get("method_name").getAsString()+"()"+(mode.equals("start-value")?"Z":"V");
        String control=engine.get("control_property").getAsString(),counter=engine.get("counter_property").getAsString();int[] edits={0};
        DexRewriter rewriter=new DexRewriter(new RewriterModule(){
            @Override public Rewriter<Method> getMethodRewriter(Rewriters all){return new MethodRewriter(all){
                @Override public Method rewrite(Method method){return new RewrittenMethod(method){
                    @Override public MethodImplementation getImplementation(){
                        MethodImplementation body=super.getImplementation();if(body==null||(!mode.equals("property-rename")&&!key(method).equals(target)))return body;
                        List<Instruction> code=new ArrayList<>();
                        for(Instruction instruction:body.getInstructions()){
                            if(instruction instanceof ReferenceInstruction&&((ReferenceInstruction)instruction).getReference() instanceof StringReference){
                                String old=((StringReference)((ReferenceInstruction)instruction).getReference()).getString(),changed=old;
                                if(mode.equals("property-rename")){if(old.equals(control))changed="fixture.engine.control";if(old.equals(counter))changed="fixture.engine.references";}
                                else if(mode.equals("control")&&old.equals(control))changed="fixture.unpaired.control";
                                else if(mode.equals("counter")&&old.equals(counter))changed="fixture.unpaired.counter";
                                else if(mode.equals("start-value")&&old.equals("true"))changed="fixture_not_a_start_value";
                                if(!old.equals(changed)){
                                    int register=((OneRegisterInstruction)instruction).getRegisterA();var value=new ImmutableStringReference(changed);
                                    code.add(instruction.getOpcode().name().equals("CONST_STRING")?new ImmutableInstruction21c(instruction.getOpcode(),register,value):new ImmutableInstruction31c(instruction.getOpcode(),register,value));edits[0]++;continue;
                                }
                            }code.add(instruction);
                        }
                        return new ImmutableMethodImplementation(body.getRegisterCount(),code,body.getTryBlocks(),body.getDebugItems());
                    }
                };}
            };}
        });
        var container=DexFileFactory.loadDexContainer(original,null);Map<String,Path> dex=new LinkedHashMap<>();
        for(String entry:container.getDexEntryNames()){Path file=out.resolve(entry);DexPool.writeTo(file.toString(),rewriter.getDexFileRewriter().rewrite(container.getEntry(entry).getDexFile()));dex.put(entry,file);}
        check(edits[0]>0,"No real lifecycle evidence changed: "+mode);File result=out.resolve("TEST-ONLY-UNSIGNED.apk").toFile();
        try(ZipFile input=new ZipFile(original);ZipOutputStream output=new ZipOutputStream(new FileOutputStream(result))){
            var entries=input.entries();while(entries.hasMoreElements()){ZipEntry entry=entries.nextElement();String name=entry.getName();if(entry.isDirectory()||name.toUpperCase(Locale.ROOT).startsWith("META-INF/"))continue;
                output.putNextEntry(new ZipEntry(name));if(dex.containsKey(name))Files.copy(dex.get(name),output);else try(InputStream stream=input.getInputStream(entry)){stream.transferTo(output);}output.closeEntry();}
        }
        return result;
    }
    private static String hash(File file)throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(InputStream stream=new FileInputStream(file)){byte[] bytes=new byte[65536];int n;while((n=stream.read(bytes))!=-1)digest.update(bytes,0,n);}return HexFormat.of().formatHex(digest.digest());}
    public static void main(String[] args)throws Exception{
        check(args.length==2,"APK NEW_OUTPUT_DIRECTORY");File apk=new File(args[0]);Path output=Path.of(args[1]);check(!Files.exists(output),"Output must be exclusive");Files.createDirectories(output);
        String before=hash(apk);JsonObject original=JsonParser.parseString(OucDiscovery.discover(apk)).getAsJsonObject();verify(apk,original);
        Map<String,Object> report=new LinkedHashMap<>();report.put("inputSha256",before);report.put("original",original.get("engine_lifecycle"));
        for(String mode:List.of("control","counter","start-value","property-rename")){
            File fixture=mutate(apk,output.resolve(mode),original.getAsJsonObject("engine_lifecycle"),mode);
            JsonObject mapping=JsonParser.parseString(OucDiscovery.discover(fixture)).getAsJsonObject();check(mapping.get("discovery_complete").getAsBoolean(),"Optional lifecycle loss disabled critical protection: "+mode);
            if(mode.equals("property-rename")){verify(fixture,mapping);check(mapping.getAsJsonObject("engine_lifecycle").get("control_property").getAsString().equals("fixture.engine.control"),"Control property was hardcoded");}
            else check(mapping.getAsJsonObject("engine_lifecycle").isEmpty(),"Unproved paired lifecycle accepted: "+mode);
            report.put(mode,mapping.get("engine_lifecycle"));
        }
        check(hash(apk).equals(before),"Input APK changed");report.put("passed",true);report.put("deviceOperations",false);report.put("fixturesOnly",true);
        Files.writeString(output.resolve("report.json"),OucDiscovery.json(report));System.out.println("Engine lifecycle real DEX checks passed: "+output);
    }
}
