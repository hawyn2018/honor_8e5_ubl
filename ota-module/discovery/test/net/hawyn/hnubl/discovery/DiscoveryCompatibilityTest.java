package net.hawyn.hnubl.discovery;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.rewriter.*;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Offline, unsigned fixtures only. Never installs or executes the rewritten APK. */
public final class DiscoveryCompatibilityTest {
    private static final Map<String,String> typeNames=new LinkedHashMap<>();
    private static final Map<String,String> methodNames=new LinkedHashMap<>();
    private static final List<String> inputDexOrder=new ArrayList<>();
    private static final Map<String,Integer> originalReferences=new TreeMap<>();
    private static final Map<String,Integer> rewrittenReferences=new TreeMap<>();
    private static boolean classOrderChanged;
    private static final Set<String> protocolNames=new HashSet<>(Arrays.asList(
        "applyPayload","applyMultiPayloads","applyUpdateZip","startUpdateZip",
        "handleMessage","run","call","get","equals","hashCode","toString","compareTo","close"));

    public static void main(String[] args)throws Exception {
        require(args.length==2,"APK NEW_OUTPUT_DIRECTORY");
        File original=new File(args[0]).getCanonicalFile();Path out=Paths.get(args[1]).toAbsolutePath();
        require(original.isFile(),"Input APK missing");require(!Files.exists(out),"Output directory must be new");
        Files.createDirectories(out);String initialHash=sha256(original);
        Map<String,Object> report=new LinkedHashMap<>();
        try {
            String before=OucDiscovery.discover(original);
            write(out.resolve("original-mapping.json"),before);
            JsonObject baseline=JsonParser.parseString(before).getAsJsonObject();
            require(baseline.get("discovery_complete").getAsBoolean(),"Original discovery incomplete");
            verifyNativeNoUpdate(original,baseline);
            VersionCacheDiscoveryTest.verify(original,baseline);
            deriveRenames(original,baseline);
            require(typeNames.size()>0,"No application classes eligible for rename");
            require(methodNames.size()>0,"No non-protocol methods eligible for rename");
            System.gc();
            File mutated=out.resolve("TEST-ONLY-renamed-reordered-UNSIGNED.apk").toFile();
            mutate(original,mutated,out);
            System.gc();
            String after=OucDiscovery.discover(mutated);
            write(out.resolve("mutated-mapping.json"),after);
            JsonObject changed=JsonParser.parseString(after).getAsJsonObject();
            require(changed.get("discovery_complete").getAsBoolean(),"Mutated discovery incomplete");
            verifyNativeNoUpdate(mutated,changed);
            VersionCacheDiscoveryTest.verify(mutated,changed);
            require(roleCounts(baseline).equals(roleCounts(changed)),"Role coverage/count changed");
            verifyRenamedHooks(baseline,changed);
            require(originalReferences.equals(rewrittenReferences),"An invoke reference was lost or not rewritten");
            require(sha256(original).equals(initialHash),"Original APK changed");
            require(!sha256(mutated).equals(initialHash),"Mutation did not change fixture");
            List<String> reversed=new ArrayList<>(inputDexOrder);Collections.reverse(reversed);
            require(zipDexOrder(mutated).equals(reversed),"DEX ZIP entry order was not reversed");
            require(classOrderChanged,"Type renaming did not change canonical DEX class order");
            report.put("passed",true);report.put("inputApk",original.getPath());report.put("inputSha256",initialHash);
            report.put("fixture",mutated.getPath());report.put("fixtureSha256",sha256(mutated));
            report.put("classesRenamed",typeNames);report.put("methodsRenamed",methodNames);
            report.put("originalRoles",roleCounts(baseline));report.put("mutatedRoles",roleCounts(changed));
            report.put("originalDexZipOrder",inputDexOrder);report.put("mutatedDexZipOrder",zipDexOrder(mutated));
            report.put("classEnumerationReversedBeforeDexSerialization",true);
            report.put("canonicalClassOrderChangedByRenaming",classOrderChanged);
            report.put("invokeReferencesPerRenamedMethod",originalReferences);
            report.put("rewrittenInvokeReferencesPerRenamedMethod",rewrittenReferences);
            report.put("allInvokeReferencesPreserved",true);report.put("originalApkUnchanged",true);
            report.put("protocolMethodNamesPreserved",true);report.put("deviceOperations",false);
            report.put("nativeNoUpdateCallbackAndHandlerEdgesVerified",true);
            report.put("nativeCacheListCountUriEvidenceVerified",true);
            report.put("limitations",Arrays.asList("Not a real older updater APK", "No runtime execution or ART-hook verification", "No control-flow/instruction-body mutation", "Unsigned fixture must not be installed", "Valid DEX tables remain canonically sorted as required by DEX"));
            write(out.resolve("report.json"),OucDiscovery.json(report));
            System.out.println(OucDiscovery.json(report));
        } catch(Throwable error) {
            report.put("passed",false);report.put("error",error.toString());
            write(out.resolve("report.json"),OucDiscovery.json(report));throw error;
        }
    }

    private static void deriveRenames(File apk,JsonObject baseline)throws Exception {
        MultiDexContainer<? extends DexFile> container=DexFileFactory.loadDexContainer(apk,null);
        Map<String,ClassDef> classes=new HashMap<>();Map<String,Method> methods=new HashMap<>();
        for(String entry:container.getDexEntryNames())for(ClassDef c:container.getEntry(entry).getDexFile().getClasses()) {
            classes.put(c.getType(),c);for(Method m:c.getMethods())methods.put(key(m),m);
        }
        int nextClass=0,nextMethod=0;
        for(JsonElement element:baseline.getAsJsonArray("hooks")) {
            JsonObject h=element.getAsJsonObject();String name=h.get("class_name").getAsString();
            // Targets are actual definitions from this APK. A vendor application
            // can retain com.* packages while obfuscating its class/method leaf;
            // excluding every com.* target would leave that real family untested.
            // Android/Java protocol types retain their identity.
            if(name.startsWith("android.")||name.startsWith("java.")||name.startsWith("javax."))continue;
            String owner=descriptor(name);
            if(!typeNames.containsKey(owner))typeNames.put(owner,"Lhnubl/compat/mutation/Target"+(++nextClass)+";");
            String methodKey=hookKey(h);Method m=methods.get(methodKey);
            require(m!=null,"Discovered target method not in APK: "+methodKey);
            String method=m.getName();
            boolean protocol=method.startsWith("<")||method.startsWith("on")||protocolNames.contains(method);
            if(!protocol && !isOverride(m,classes) && !methodNames.containsKey(methodKey))
                methodNames.put(methodKey,"probe_"+(++nextMethod));
        }
        for(JsonElement element:baseline.getAsJsonArray("hooks")) {
            JsonObject hook=element.getAsJsonObject();if(!hook.get("role").getAsString().equals("version_response"))continue;
            String callback=hook.get("native_no_update_callback").getAsString();
            require(methods.containsKey(callback),"Native no-update callback missing");
            if(!methodNames.containsKey(callback))methodNames.put(callback,"probe_"+(++nextMethod));
        }
        Set<String> renamed=methodNames.keySet();
        for(ClassDef c:classes.values())for(Method m:c.getMethods())if(m.getImplementation()!=null)
            for(Instruction i:m.getImplementation().getInstructions())if(i instanceof ReferenceInstruction) {
                Reference r=((ReferenceInstruction)i).getReference();
                if(r instanceof MethodReference && renamed.contains(key((MethodReference)r)))increment(originalReferences,key((MethodReference)r));
            }
        // All later phases re-open one DEX at a time instead of retaining this index.
    }

    private static boolean isOverride(Method m,Map<String,ClassDef> classes) {
        if((m.getAccessFlags()&(8|2))!=0)return false; // static/private do not override
        ClassDef c=classes.get(m.getDefiningClass());if(c==null)return true;
        Set<String> visited=new HashSet<>();Deque<String> q=new ArrayDeque<>();
        if(c.getSuperclass()!=null)q.add(c.getSuperclass());q.addAll(c.getInterfaces());
        while(!q.isEmpty()) {
            String owner=q.removeFirst();if(!visited.add(owner)||owner.equals("Ljava/lang/Object;"))continue;
            ClassDef ancestor=classes.get(owner);
            if(ancestor==null)return true; // Unknown external contract: retain the method name.
            for(Method inherited:ancestor.getMethods())
                if(signature(inherited).equals(signature(m)))return true;
            if(ancestor.getSuperclass()!=null)q.add(ancestor.getSuperclass());q.addAll(ancestor.getInterfaces());
        }
        return false;
    }

    private static void mutate(File original,File output,Path directory)throws Exception {
        inputDexOrder.addAll(zipDexOrder(original));
        MultiDexContainer<? extends DexFile> container=DexFileFactory.loadDexContainer(original,null);
        RewriterModule module=new RewriterModule() {
            @Override public Rewriter<String> getTypeRewriter(Rewriters rewriters) {return DiscoveryCompatibilityTest::rewriteType;}
            @Override public Rewriter<MethodReference> getMethodReferenceRewriter(Rewriters rewriters) {
                return new MethodReferenceRewriter(rewriters) {
                    @Override public MethodReference rewrite(final MethodReference value) {
                        return new RewrittenMethodReference(value) {
                            @Override public String getName(){return methodNames.getOrDefault(key(value),value.getName());}
                        };
                    }
                };
            }
        };
        DexRewriter rewriter=new DexRewriter(module);
        Map<String,Path> changedDex=new LinkedHashMap<>();
        for(String name:inputDexOrder) {
            DexFile input=container.getEntry(name).getDexFile();
            List<ClassDef> ordered=new ArrayList<>(input.getClasses());Collections.reverse(ordered);
            final Set<ClassDef> reversed=new LinkedHashSet<>(ordered);
            DexFile reverseView=new DexFile(){public Set<? extends ClassDef> getClasses(){return reversed;}public com.android.tools.smali.dexlib2.Opcodes getOpcodes(){return input.getOpcodes();}};
            Path file=directory.resolve("rewritten-"+name);
            DexPool.writeTo(file.toString(),rewriter.getDexFileRewriter().rewrite(reverseView));
            changedDex.put(name,file);
            List<String> oldOrder=new ArrayList<>();for(ClassDef c:input.getClasses())oldOrder.add(rewriteType(c.getType()));
            DexFile newDex=DexFileFactory.loadDexFile(file.toFile(),null);
            List<String> newOrder=new ArrayList<>();for(ClassDef c:newDex.getClasses())newOrder.add(c.getType());
            if(!newOrder.equals(oldOrder))classOrderChanged=true;
            scanRewrittenReferences(newDex);
            System.gc();
        }
        try(ZipFile zip=new ZipFile(original);ZipOutputStream out=new ZipOutputStream(new FileOutputStream(output))) {
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry e=entries.nextElement();String name=e.getName();
                if(e.isDirectory()||changedDex.containsKey(name)||isApkSignature(name))continue;
                out.putNextEntry(new ZipEntry(name));try(InputStream in=zip.getInputStream(e)){copy(in,out);}out.closeEntry();
            }
            List<String> reversedOrder=new ArrayList<>(inputDexOrder);Collections.reverse(reversedOrder);
            for(String name:reversedOrder){out.putNextEntry(new ZipEntry(name));try(InputStream in=Files.newInputStream(changedDex.get(name))){copy(in,out);}out.closeEntry();}
        }
    }

    private static void scanRewrittenReferences(DexFile dex) {
        Map<String,String> reversedMethods=new HashMap<>();
        for(String old:methodNames.keySet())reversedMethods.put(rewriteMethodKey(old),old);
        for(ClassDef c:dex.getClasses())for(Method m:c.getMethods())if(m.getImplementation()!=null)
            for(Instruction i:m.getImplementation().getInstructions())if(i instanceof ReferenceInstruction) {
                Reference r=((ReferenceInstruction)i).getReference();if(!(r instanceof MethodReference))continue;
                String reference=key((MethodReference)r);
                require(!methodNames.containsKey(reference),"Stale original invoke reference: "+reference);
                String original=reversedMethods.get(reference);if(original!=null)increment(rewrittenReferences,original);
            }
    }
    private static void verifyRenamedHooks(JsonObject before,JsonObject after) {
        Set<String> actual=new HashSet<>();for(JsonElement e:after.getAsJsonArray("hooks")){JsonObject h=e.getAsJsonObject();actual.add(h.get("role").getAsString()+":"+hookKey(h));}
        for(JsonElement e:before.getAsJsonArray("hooks")) {
            JsonObject h=e.getAsJsonObject();String key=hookKey(h);
            require(actual.contains(h.get("role").getAsString()+":"+rewriteMethodKey(key)),"Renamed target not rediscovered: "+key);
            if(h.get("role").getAsString().equals("version_response")) {
                String expected=rewriteMethodKey(h.get("native_no_update_callback").getAsString());boolean found=false;
                for(JsonElement item:after.getAsJsonArray("hooks")) {
                    JsonObject candidate=item.getAsJsonObject();
                    if(candidate.get("role").getAsString().equals("version_response") &&
                       candidate.get("native_no_update_callback").getAsString().equals(expected))found=true;
                }
                require(found,"Renamed native no-update callback not rediscovered");
            }
        }
    }
    private static void verifyNativeNoUpdate(File apk,JsonObject mapping)throws Exception {
        Map<String,Method> methods=new HashMap<>();
        MultiDexContainer<? extends DexFile> container=DexFileFactory.loadDexContainer(apk,null);
        for(String entry:container.getDexEntryNames())for(ClassDef c:container.getEntry(entry).getDexFile().getClasses())
            for(Method m:c.getMethods())methods.put(key(m),m);
        int verified=0;
        for(JsonElement item:mapping.getAsJsonArray("hooks")) {
            JsonObject hook=item.getAsJsonObject();if(!hook.get("role").getAsString().equals("version_response"))continue;
            String callbackKey=hook.get("native_no_update_callback").getAsString();
            Method entry=methods.get(hookKey(hook)),callback=methods.get(callbackKey);
            require(entry!=null && callback!=null,"Response/native callback definition missing");
            boolean entryCallsCallback=false,obtains=false,sends=false,constructsFalseCapableResult=false;
            for(Instruction i:entry.getImplementation().getInstructions())if(i instanceof ReferenceInstruction) {
                Reference ref=((ReferenceInstruction)i).getReference();
                if(ref instanceof MethodReference && key((MethodReference)ref).equals(callbackKey))entryCallsCallback=true;
            }
            for(Instruction i:callback.getImplementation().getInstructions())if(i instanceof ReferenceInstruction) {
                Reference ref=((ReferenceInstruction)i).getReference();if(!(ref instanceof MethodReference))continue;
                MethodReference call=(MethodReference)ref;
                if(call.getName().equals("obtainMessage"))obtains=true;
                if(call.getName().equals("sendMessage"))sends=true;
                if(call.getName().equals("<init>") && call.getParameterTypes().size()==3 &&
                   call.getParameterTypes().get(0).equals("I") && call.getParameterTypes().get(1).equals("Z"))constructsFalseCapableResult=true;
            }
            require(entryCallsCallback && obtains && sends && constructsFalseCapableResult,
                "No-update must call the native CheckResult/Handler path, not a display-only replacement");
            JsonObject states=hook.getAsJsonObject("version_states");
            require(states.get("available").getAsInt()!=states.get("unavailable").getAsInt(),"Protocol states overlap");
            require(hook.getAsJsonObject("current_version_source").size()>0,"No actual firmware source");verified++;
        }
        require(verified>0,"Version response hook missing");
    }
    private static Map<String,Integer> roleCounts(JsonObject mapping) {
        Map<String,Integer> roles=new TreeMap<>();for(JsonElement e:mapping.getAsJsonArray("hooks"))increment(roles,e.getAsJsonObject().get("role").getAsString());return roles;
    }
    private static List<String> zipDexOrder(File apk)throws IOException {
        List<String> names=new ArrayList<>();try(ZipFile z=new ZipFile(apk)){Enumeration<? extends ZipEntry> e=z.entries();while(e.hasMoreElements()){String n=e.nextElement().getName();if(n.matches("classes(?:[0-9]+)?\\.dex"))names.add(n);}}return names;
    }
    private static boolean isApkSignature(String name) {String upper=name.toUpperCase(Locale.ROOT);return upper.startsWith("META-INF/")&&(upper.endsWith(".RSA")||upper.endsWith(".DSA")||upper.endsWith(".EC")||upper.endsWith(".SF")||upper.equals("META-INF/MANIFEST.MF"));}
    private static String rewriteType(String value) {int arrays=0;while(arrays<value.length()&&value.charAt(arrays)=='[')arrays++;return value.substring(0,arrays)+typeNames.getOrDefault(value.substring(arrays),value.substring(arrays));}
    private static String rewriteMethodKey(String old) {
        int split=old.indexOf("->"),open=old.indexOf('(',split),close=old.indexOf(')',open);
        String owner=rewriteType(old.substring(0,split));String name=methodNames.getOrDefault(old,old.substring(split+2,open));
        String parameters=old.substring(open+1,close);StringBuilder rewritten=new StringBuilder();
        int at=0;while(at<parameters.length()){int end=at;while(parameters.charAt(end)=='[')end++;if(parameters.charAt(end)=='L')end=parameters.indexOf(';',end)+1;else end++;rewritten.append(rewriteType(parameters.substring(at,end)));at=end;}
        return owner+"->"+name+"("+rewritten+")"+rewriteType(old.substring(close+1));
    }
    private static String hookKey(JsonObject h) {StringBuilder s=new StringBuilder(descriptor(h.get("class_name").getAsString())).append("->").append(h.get("method_name").getAsString()).append('(');for(JsonElement p:h.getAsJsonArray("parameter_types"))s.append(descriptor(p.getAsString()));return s.append(')').append(descriptor(h.get("return_type").getAsString())).toString();}
    private static String descriptor(String type) {
        switch(type){case "void":return "V";case "boolean":return "Z";case "int":return "I";case "long":return "J";case "float":return "F";case "double":return "D";case "short":return "S";case "byte":return "B";case "char":return "C";default:break;}
        if(type.startsWith("["))return type.replace('.','/');return "L"+type.replace('.','/')+";";
    }
    private static String signature(MethodReference m){StringBuilder s=new StringBuilder(m.getName()).append('(');for(CharSequence p:m.getParameterTypes())s.append(p);return s.append(')').append(m.getReturnType()).toString();}
    private static String key(MethodReference m){return m.getDefiningClass()+"->"+signature(m);}
    private static void increment(Map<String,Integer> map,String k){map.put(k,map.getOrDefault(k,0)+1);}
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    private static void write(Path path,String text)throws IOException{Files.write(path,(text+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static String sha256(File file)throws Exception {MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[]b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}StringBuilder h=new StringBuilder();for(byte b:md.digest())h.append(String.format(Locale.ROOT,"%02x",b&255));return h.toString();}
}
