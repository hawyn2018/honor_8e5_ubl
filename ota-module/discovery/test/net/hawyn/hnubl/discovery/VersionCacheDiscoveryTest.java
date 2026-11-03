package net.hawyn.hnubl.discovery;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.rewriter.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Real DEX boundary mutations; fixture APKs are unsigned and never executed. */
public final class VersionCacheDiscoveryTest {
    private static void check(boolean b,String text){if(!b)throw new AssertionError(text);}
    private static String key(MethodReference m){StringBuilder s=new StringBuilder(m.getDefiningClass()).append("->").append(m.getName()).append('(');for(CharSequence t:m.getParameterTypes())s.append(t);return s.append(')').append(m.getReturnType()).toString();}
    private static JsonObject inspect(File f)throws Exception{return JsonParser.parseString(OucDiscovery.discover(f)).getAsJsonObject();}
    private static JsonObject cache(JsonObject mapping){JsonObject found=null;for(JsonElement e:mapping.getAsJsonArray("hooks"))if(e.getAsJsonObject().get("role").getAsString().equals("version_cache_query")){check(found==null,"Ambiguous cache query");found=e.getAsJsonObject();}return found;}
    private static Set<String> calls(Method m){Set<String> out=new HashSet<>();for(Instruction i:m.getImplementation().getInstructions())if(i instanceof ReferenceInstruction&&((ReferenceInstruction)i).getReference() instanceof MethodReference)out.add(key((MethodReference)((ReferenceInstruction)i).getReference()));return out;}
    private static String hash(File file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}
    static void verify(File apk,JsonObject mapping)throws Exception {
        JsonObject h=cache(mapping);check(h!=null,"Actual firmware cache role missing");
        check(mapping.getAsJsonArray("critical_missing").isEmpty(),"Critical discovery regressed");
        var container=DexFileFactory.loadDexContainer(apk,null);Map<String,Method> methods=new HashMap<>();Map<String,ClassDef> classes=new HashMap<>();
        for(String entry:container.getDexEntryNames())for(ClassDef c:container.getEntry(entry).getDexFile().getClasses()){classes.put(c.getType(),c);for(Method m:c.getMethods())methods.put(key(m),m);}
        String query="L"+h.get("class_name").getAsString().replace('.','/')+";->"+h.get("method_name").getAsString()+h.get("descriptor").getAsString();
        check(calls(methods.get(query)).contains("Landroid/content/ContentResolver;->query"+h.get("descriptor").getAsString()),"Query does not invoke the native read API");
        Method list=methods.get(h.get("list_method").getAsString()),count=methods.get(h.get("count_method").getAsString());
        check(calls(list).contains(query)&&calls(count).contains(query),"List/count do not share this exact Cursor source");
        check(calls(count).contains("Landroid/database/Cursor;->getCount()I"),"Count is not derived from the shared Cursor");
        check(calls(list).contains(h.get("row_mapper").getAsString()),"List does not invoke the row decoder");
        JsonObject uri=h.getAsJsonObject("uri_source");ClassDef owner=classes.get("L"+uri.get("class_name").getAsString().replace('.','/')+";");
        boolean field=false;for(Field f:owner.getFields())if(f.getName().equals(uri.get("field_name").getAsString())&&f.getType().equals("Landroid/net/Uri;")&&(f.getAccessFlags()&8)!=0)field=true;
        check(field,"URI source is not an actual static Uri field");
        check(h.getAsJsonObject("columns").has("progress"),"Actual row progress column was not proved");
        check(h.getAsJsonArray("pending_states").size()==1&&h.getAsJsonArray("pending_states").get(0).getAsInt()==0,"Native initial-state proof missing");
        check(h.getAsJsonArray("pending_state_evidence").size()>0,"Missing state-write witnesses");
    }
    private static File mutate(File apk,Path out,JsonObject h,String mode)throws Exception {
        Files.createDirectory(out);Set<String> targets=new HashSet<>();
        if(mode.equals("count"))targets.add(h.get("count_method").getAsString());
        else if(mode.equals("uri"))targets.add(h.get("uri_method").getAsString());
        else if(mode.equals("state"))for(JsonElement e:h.getAsJsonArray("pending_state_evidence"))targets.add(e.getAsString());
        else targets.add(h.get("row_mapper").getAsString());
        int[] edits={0};DexRewriter rewriter=new DexRewriter(new RewriterModule(){
            @Override public Rewriter<Method> getMethodRewriter(Rewriters all){return new MethodRewriter(all){
                @Override public Method rewrite(Method method){return new RewrittenMethod(method){
                    @Override public MethodImplementation getImplementation(){
                        MethodImplementation original=super.getImplementation();if(original==null||!targets.contains(key(method)))return original;
                        List<Instruction> code=new ArrayList<>();
                        for(Instruction i:original.getInstructions()){
                            if(mode.equals("uri")&&i.getOpcode()==Opcode.SGET_OBJECT){code.add(new ImmutableInstruction21s(Opcode.CONST_16,((OneRegisterInstruction)i).getRegisterA(),0));edits[0]++;continue;}
                            if(i instanceof ReferenceInstruction){Reference ref=((ReferenceInstruction)i).getReference();
                                if(mode.equals("count")&&ref instanceof MethodReference&&key((MethodReference)ref).equals("Landroid/database/Cursor;->getCount()I")){
                                    MethodReference r=(MethodReference)ref;var changed=new ImmutableMethodReference(r.getDefiningClass(),"getPosition",r.getParameterTypes(),r.getReturnType());
                                    if(i instanceof FiveRegisterInstruction){FiveRegisterInstruction f=(FiveRegisterInstruction)i;code.add(new ImmutableInstruction35c(i.getOpcode(),f.getRegisterCount(),f.getRegisterC(),f.getRegisterD(),f.getRegisterE(),f.getRegisterF(),f.getRegisterG(),changed));}
                                    else {RegisterRangeInstruction f=(RegisterRangeInstruction)i;code.add(new ImmutableInstruction3rc(i.getOpcode(),f.getStartRegister(),f.getRegisterCount(),changed));}edits[0]++;continue;
                                }
                                String before=ref instanceof StringReference?((StringReference)ref).getString():null;
                                String sought=mode.equals("progress")?"fw_update_progress":"fw_state";
                                if(!mode.equals("count")&&!mode.equals("uri")&&sought.equals(before)){
                                    var changed=new ImmutableStringReference("fixture_unproved_"+sought);int register=((OneRegisterInstruction)i).getRegisterA();
                                    code.add(i.getOpcode()==Opcode.CONST_STRING?new ImmutableInstruction21c(i.getOpcode(),register,changed):new ImmutableInstruction31c(i.getOpcode(),register,changed));edits[0]++;continue;
                                }
                            }code.add(i);
                        }
                        return new ImmutableMethodImplementation(original.getRegisterCount(),code,original.getTryBlocks(),original.getDebugItems());
                    }
                };}
            };}
        });
        var container=DexFileFactory.loadDexContainer(apk,null);Map<String,Path> dex=new LinkedHashMap<>();
        for(String name:container.getDexEntryNames()){Path f=out.resolve(name);DexPool.writeTo(f.toString(),rewriter.getDexFileRewriter().rewrite(container.getEntry(name).getDexFile()));dex.put(name,f);}
        check(edits[0]>0,"Mutation did not change the discovered evidence: "+mode);
        File result=out.resolve("TEST-ONLY-UNSIGNED.apk").toFile();
        try(ZipFile source=new ZipFile(apk);ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(result))){
            var entries=source.entries();while(entries.hasMoreElements()){ZipEntry e=entries.nextElement();if(e.isDirectory()||e.getName().toUpperCase(Locale.ROOT).startsWith("META-INF/"))continue;
                zip.putNextEntry(new ZipEntry(e.getName()));if(dex.containsKey(e.getName()))Files.copy(dex.get(e.getName()),zip);else try(InputStream in=source.getInputStream(e)){in.transferTo(zip);}zip.closeEntry();}
        }return result;
    }
    public static void main(String[] args)throws Exception {
        check(args.length==2,"APK NEW_OUTPUT_DIRECTORY");File apk=new File(args[0]).getCanonicalFile();Path out=Path.of(args[1]);check(!Files.exists(out),"Output already exists");Files.createDirectories(out);
        String before=hash(apk);JsonObject baseline=inspect(apk);verify(apk,baseline);JsonObject h=cache(baseline),report=new JsonObject();
        for(String mode:List.of("count","mapper","uri","state","progress")){
            JsonObject changed=inspect(mutate(apk,out.resolve(mode),h,mode));JsonObject target=cache(changed);
            check(changed.getAsJsonArray("critical_missing").equals(baseline.getAsJsonArray("critical_missing")),"Optional evidence changed critical readiness");
            if(List.of("count","mapper","uri").contains(mode)){check(target==null,"Incomplete cache proof accepted: "+mode);check(changed.getAsJsonArray("optional_missing").asList().stream().anyMatch(v->v.getAsString().equals("version_cache_query")),"Missing optional diagnostic");}
            else if(mode.equals("state")){check(target!=null&&!target.has("pending_states"),"Missing initial-state witness was guessed");}
            else check(target!=null&&!target.getAsJsonObject("columns").has("progress"),"Missing progress column was guessed");
            report.addProperty(mode,true);System.gc();
        }
        check(hash(apk).equals(before),"Original APK changed");report.addProperty("passed",true);report.addProperty("inputSha256",before);report.addProperty("cases",6);report.addProperty("deviceOperations",false);report.addProperty("fixturesUnsignedNeverExecuted",true);
        Files.writeString(out.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println("Version cache discovery: 6 cases passed: "+out);
    }
}
