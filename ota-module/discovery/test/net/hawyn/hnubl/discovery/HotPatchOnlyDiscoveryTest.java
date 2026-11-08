package net.hawyn.hnubl.discovery;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.MethodRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Locate the real vendor observer and reject a hot-and-cold predicate. Offline only. */
public final class HotPatchOnlyDiscoveryTest {
    private static void check(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);
    }

    private static JsonObject discover(File input) throws Exception {
        return JsonParser.parseString(OucDiscovery.discover(input)).getAsJsonObject();
    }

    private static JsonObject verify(JsonObject mapping) {
        JsonObject observer=mapping.getAsJsonObject("hotpatch_only");
        check(!observer.isEmpty(),"No native hot-only observer");
        check(observer.get("is_static").getAsBoolean() &&
              observer.get("return_type").getAsString().equals("boolean") &&
              observer.getAsJsonArray("parameter_types").isEmpty(),"Wrong observer ABI");
        JsonObject mask=observer.getAsJsonObject("patch_mask_test");
        check(mask.get("is_static").getAsBoolean() &&
              mask.get("return_type").getAsString().equals("boolean") &&
              mask.getAsJsonArray("parameter_types").size()==1 &&
              mask.getAsJsonArray("parameter_types").get(0).getAsString().equals("int") &&
              mask.get("class_name").equals(observer.get("class_name")),"Wrong native mask ABI");
        return mask;
    }

    private static File hotAndColdFixture(File input,JsonObject mask,Path out) throws Exception {
        String owner="L"+mask.get("class_name").getAsString().replace('.','/')+";";
        String name=mask.get("method_name").getAsString();
        int[] edits={0};
        DexRewriter rewriter=new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Method> getMethodRewriter(Rewriters all) {
                return new MethodRewriter(all) {
                    @Override public Method rewrite(Method method) {
                        return new RewrittenMethod(method) {
                            @Override public MethodImplementation getImplementation() {
                                MethodImplementation body=super.getImplementation();
                                if(body==null || !method.getDefiningClass().equals(owner) || !method.getName().equals(name) ||
                                   !method.getParameterTypes().equals(List.of("I")) || !method.getReturnType().equals("Z")) return body;
                                List<Instruction> code=new ArrayList<>();
                                for(Instruction instruction:body.getInstructions()) {
                                    if(instruction.getOpcode()==Opcode.IF_NEZ) {
                                        code.add(new ImmutableInstruction21t(Opcode.IF_EQZ,
                                            ((OneRegisterInstruction)instruction).getRegisterA(),((OffsetInstruction)instruction).getCodeOffset()));
                                        edits[0]++;
                                    } else code.add(instruction);
                                }
                                return new ImmutableMethodImplementation(body.getRegisterCount(),code,body.getTryBlocks(),body.getDebugItems());
                            }
                        };
                    }
                };
            }
        });
        File result=out.resolve("TEST-ONLY-UNSIGNED.apk").toFile();
        var container=DexFileFactory.loadDexContainer(input,null);
        try(ZipOutputStream output=new ZipOutputStream(Files.newOutputStream(result.toPath()))) {
            for(String entry:container.getDexEntryNames()) {
                Path dex=out.resolve(entry);
                DexPool.writeTo(dex.toString(),rewriter.getDexFileRewriter().rewrite(container.getEntry(entry).getDexFile()));
                output.putNextEntry(new ZipEntry(entry));
                Files.copy(dex,output);
                output.closeEntry();
            }
        }
        check(edits[0]>0,"No real cold-mask branch changed");
        return result;
    }

    public static void main(String[] args) throws Exception {
        check(args.length>=2,"NEW_OUTPUT_DIRECTORY APK [APK ...]");
        Path out=Files.createDirectory(Path.of(args[0]));
        JsonObject first=null;
        boolean ready=false;
        for(int i=1;i<args.length;i++) {
            JsonObject mapping=discover(new File(args[i]));
            JsonObject mask=verify(mapping);
            if(first==null) {
                first=mask;
                ready=mapping.get("discovery_complete").getAsBoolean();
            }
            System.out.println("Found native hot-only observer: "+mapping.get("hotpatch_only"));
        }
        File input=new File(args[1]);
        JsonObject mutated=discover(hotAndColdFixture(input,first,out));
        check(mutated.getAsJsonObject("hotpatch_only").isEmpty(),"Cold subtype became eligible");
        check(mutated.get("discovery_complete").getAsBoolean()==ready,
            "Optional proof changed critical readiness");
        System.out.println("Hot-only discovery and mixed-patch mutation passed");
    }
}
