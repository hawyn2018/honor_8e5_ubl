package net.hawyn.hnubl.discovery;
import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.rewriter.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.google.gson.*;
import java.io.*;import java.nio.file.*;import java.security.*;import java.util.*;import java.util.zip.*;
/** Actual multi-DEX mutation, never installed or executed. */
public final class CountdownStructuralDiscoveryTest {
 private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 private static String key(MethodReference m){StringBuilder s=new StringBuilder(m.getDefiningClass()).append("->").append(m.getName()).append('(');for(CharSequence p:m.getParameterTypes())s.append(p);return s.append(')').append(m.getReturnType()).toString();}
 private static String hash(File file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}
 private static File mutate(File input,Path output,String mode,Map<String,Integer> senders)throws Exception{
  Files.createDirectories(output);int[] edits={0};
  DexRewriter rewriter=new DexRewriter(new RewriterModule(){
   @Override public Rewriter<Method> getMethodRewriter(Rewriters all){return new MethodRewriter(all){@Override public Method rewrite(Method method){return new RewrittenMethod(method){@Override public MethodImplementation getImplementation(){
    MethodImplementation body=super.getImplementation();if(body==null||!mode.equals("wrong-message-code")||!senders.containsKey(key(method)))return body;
    List<Instruction> changed=new ArrayList<>();int old=senders.get(key(method));for(Instruction ins:body.getInstructions()){
     if(ins instanceof NarrowLiteralInstruction&&((NarrowLiteralInstruction)ins).getNarrowLiteral()==old&&ins.getOpcode()==Opcode.CONST_16){changed.add(new ImmutableInstruction21s(ins.getOpcode(),((OneRegisterInstruction)ins).getRegisterA(),old+7919));edits[0]++;}else changed.add(ins);
    }return new ImmutableMethodImplementation(body.getRegisterCount(),changed,body.getTryBlocks(),body.getDebugItems());
   }};}};}
   @Override public Rewriter<MethodImplementation> getMethodImplementationRewriter(Rewriters r){return new MethodImplementationRewriter(r){@Override public MethodImplementation rewrite(MethodImplementation body){
   List<Instruction> code=new ArrayList<>();for(Instruction ins:body.getInstructions()){
    if(ins instanceof ReferenceInstruction){Reference ref=((ReferenceInstruction)ins).getReference();
     if(ref instanceof StringReference&&((StringReference)ref).getString().toLowerCase(Locale.ROOT).contains("countdown")){
      var text=new ImmutableStringReference("本地化诊断文本");int reg=((OneRegisterInstruction)ins).getRegisterA();code.add(ins.getOpcode()==Opcode.CONST_STRING?new ImmutableInstruction21c(ins.getOpcode(),reg,text):new ImmutableInstruction31c(ins.getOpcode(),reg,text));edits[0]++;continue;
     }
     if(mode.equals("what-field")&&ref instanceof FieldReference){FieldReference f=(FieldReference)ref;
      if(f.getDefiningClass().equals("Landroid/os/Message;")&&f.getName().equals("what")&&f.getType().equals("I")){
       TwoRegisterInstruction t=(TwoRegisterInstruction)ins;code.add(new ImmutableInstruction22c(ins.getOpcode(),t.getRegisterA(),t.getRegisterB(),new ImmutableFieldReference(f.getDefiningClass(),"fixture_other_code",f.getType())));edits[0]++;continue;
      }
     }
     if(mode.equals("no-delayed-message")&&ref instanceof MethodReference){MethodReference m=(MethodReference)ref;
      if(key(m).equals("Landroid/os/Handler;->sendMessageDelayed(Landroid/os/Message;J)Z")){
       var changed=new ImmutableMethodReference(m.getDefiningClass(),"fixture_not_a_delayed_send",m.getParameterTypes(),m.getReturnType());
       if(ins instanceof RegisterRangeInstruction){RegisterRangeInstruction t=(RegisterRangeInstruction)ins;code.add(new ImmutableInstruction3rc(ins.getOpcode(),t.getStartRegister(),t.getRegisterCount(),changed));}
       else {FiveRegisterInstruction t=(FiveRegisterInstruction)ins;code.add(new ImmutableInstruction35c(ins.getOpcode(),t.getRegisterCount(),t.getRegisterC(),t.getRegisterD(),t.getRegisterE(),t.getRegisterF(),t.getRegisterG(),changed));}edits[0]++;continue;
      }
     }
    }
    code.add(ins);
   }return new ImmutableMethodImplementation(body.getRegisterCount(),code,body.getTryBlocks(),body.getDebugItems());
  }};}});
  var dex=DexFileFactory.loadDexContainer(input,null);Map<String,Path> replacements=new LinkedHashMap<>();for(String name:dex.getDexEntryNames()){Path file=output.resolve(name);DexPool.writeTo(file.toString(),rewriter.getDexFileRewriter().rewrite(dex.getEntry(name).getDexFile()));replacements.put(name,file);}
  check(edits[0]>0,"No actual DEX operands changed");File result=output.resolve("TEST-ONLY-UNSIGNED.apk").toFile();
  try(ZipFile zip=new ZipFile(input);ZipOutputStream out=new ZipOutputStream(new FileOutputStream(result))){var entries=zip.entries();while(entries.hasMoreElements()){ZipEntry entry=entries.nextElement();String name=entry.getName();if(entry.isDirectory()||name.toUpperCase(Locale.ROOT).startsWith("META-INF/"))continue;out.putNextEntry(new ZipEntry(name));if(replacements.containsKey(name))Files.copy(replacements.get(name),out);else try(InputStream stream=zip.getInputStream(entry)){stream.transferTo(out);}out.closeEntry();}}
  return result;
 }
 private static JsonObject inspect(File apk)throws Exception{return JsonParser.parseString(OucDiscovery.discover(apk)).getAsJsonObject();}
 public static void main(String[] args)throws Exception{
  check(args.length>=2,"APK NEW_OUTPUT_DIRECTORY [negative]");File input=new File(args[0]);Path out=Path.of(args[1]);check(!Files.exists(out),"Exclusive output required");Files.createDirectories(out);String before=hash(input);
  File localized=mutate(input,out.resolve("localized-countdown-logs"),"localized",Map.of());JsonObject result=inspect(localized);check(result.get("discovery_complete").getAsBoolean(),"Localizing countdown diagnostics lost true functional coverage: "+result);
  JsonArray structural=new JsonArray();for(JsonElement e:result.getAsJsonArray("hooks")){JsonObject h=e.getAsJsonObject();if(h.get("role").getAsString().equals("countdown")){check(h.has("handler_callback")&&h.has("message_what")&&h.has("message_handler_field"),"Unproved log-free countdown hook");structural.add(h);}}
  check(!structural.isEmpty(),"No matching message-to-automatic-reboot countdown found");JsonObject report=new JsonObject();report.addProperty("inputSha256",before);report.add("localizedHooks",structural);
  Map<String,Integer> senders=new LinkedHashMap<>();for(JsonElement e:structural){JsonObject h=e.getAsJsonObject();senders.put("L"+h.get("class_name").getAsString().replace('.','/')+";->"+h.get("method_name").getAsString()+"()V",h.get("message_what").getAsInt());}
  if(args.length>2)for(String mode:List.of("what-field","no-delayed-message","wrong-message-code")){
   JsonObject broken=inspect(mutate(input,out.resolve(mode),mode,senders));check(!broken.get("discovery_complete").getAsBoolean(),"Broken critical countdown falsely declared ready: "+mode);check(broken.getAsJsonArray("critical_missing").asList().stream().anyMatch(v->v.getAsString().equals("countdown")),"Wrong critical loss classification");report.add(mode,broken.get("critical_missing"));
  }
  check(before.equals(hash(input)),"Original APK changed");report.addProperty("passed",true);report.addProperty("deviceOperations",false);report.addProperty("actualDexMutation",true);Files.writeString(out.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println("Countdown structural DEX tests passed: "+out);
 }
}
