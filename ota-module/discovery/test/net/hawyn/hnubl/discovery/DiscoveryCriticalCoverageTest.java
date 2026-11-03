package net.hawyn.hnubl.discovery;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
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

/** Actual APK DEX fixtures only. Never installs a fixture or calls update APIs. */
public final class DiscoveryCriticalCoverageTest {
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    private static JsonObject inspect(File apk)throws Exception { return JsonParser.parseString(OucDiscovery.discover(apk)).getAsJsonObject(); }
    private static Set<String> roles(JsonObject map) {
        Set<String> out=new TreeSet<>();for(JsonElement item:map.getAsJsonArray("hooks"))out.add(item.getAsJsonObject().get("role").getAsString());return out;
    }
    private static String rewrite(String value,boolean critical) {
        if(critical && Set.of("manual","manul","manually").contains(value.toLowerCase(Locale.ROOT)))return "fixture_nonmanual_reason";
        if(!critical && (value.contains("getPackageTypeForChecking")||value.equals("versionPackageTypes")||
            List.of("ota_disable_automatic_update","hn_system_night_upgrade_switch","hn_system_update_auto_download").stream().anyMatch(value::contains)))
            return "fixture_optional_anchor_removed_"+Integer.toHexString(value.hashCode());
        return value;
    }
    private static File mutate(File apk,Path out,boolean critical)throws Exception {
        Files.createDirectory(out);Map<String,Path> changed=new LinkedHashMap<>();
        var container=DexFileFactory.loadDexContainer(apk,null);
        DexRewriter rewriter=new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters all) {
                final Rewriter<Instruction> normal=super.getInstructionRewriter(all);
                return instruction -> {
                    if(instruction instanceof ReferenceInstruction && ((ReferenceInstruction)instruction).getReference() instanceof StringReference) {
                        String before=((StringReference)((ReferenceInstruction)instruction).getReference()).getString();String after=rewrite(before,critical);
                        if(!before.equals(after)) {
                            int register=((OneRegisterInstruction)instruction).getRegisterA();var ref=new ImmutableStringReference(after);
                            if(instruction.getOpcode().name().equals("CONST_STRING"))return new ImmutableInstruction21c(instruction.getOpcode(),register,ref);
                            if(instruction.getOpcode().name().equals("CONST_STRING_JUMBO"))return new ImmutableInstruction31c(instruction.getOpcode(),register,ref);
                            throw new AssertionError("Unexpected string instruction format");
                        }
                    }
                    return normal.rewrite(instruction);
                };
            }
        });
        for(String name:container.getDexEntryNames()) {
            Path file=out.resolve(name);DexPool.writeTo(file.toString(),rewriter.getDexFileRewriter().rewrite(container.getEntry(name).getDexFile()));changed.put(name,file);
        }
        File output=out.resolve("TEST-ONLY-UNSIGNED.apk").toFile();
        try(ZipFile source=new ZipFile(apk);ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(output))) {
            var entries=source.entries();while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();String name=entry.getName(),upper=name.toUpperCase(Locale.ROOT);
                if(entry.isDirectory()||upper.startsWith("META-INF/"))continue;
                zip.putNextEntry(new ZipEntry(name));
                if(changed.containsKey(name))Files.copy(changed.get(name),zip);
                else try(InputStream input=source.getInputStream(entry)){input.transferTo(zip);}
                zip.closeEntry();
            }
        }
        return output;
    }
    private static String hash(File file)throws Exception {
        var md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)md.update(buffer,0,n);}
        return HexFormat.of().formatHex(md.digest());
    }
    public static void main(String[] args)throws Exception {
        check(args.length==2,"APK NEW_OUTPUT_DIRECTORY");File original=new File(args[0]).getCanonicalFile();Path out=Path.of(args[1]);
        check(!Files.exists(out),"Outputs must be exclusive");Files.createDirectories(out);String before=hash(original);
        JsonObject baseline=inspect(original);check(baseline.get("discovery_complete").getAsBoolean(),"Actual input lacks critical coverage");
        JsonObject optional=inspect(mutate(original,out.resolve("without-optional"),false));
        check(optional.get("discovery_complete").getAsBoolean(),"Absent optional anchors prevented critical discovery");
        check(optional.getAsJsonArray("critical_missing").isEmpty(),"Optional loss reported as critical");
        for(String role:List.of("full_package","disable_auto","night_switch","auto_download","version_response"))
            check(!roles(optional).contains(role),"Optional mutation did not remove actual role: "+role);
        check(optional.getAsJsonArray("optional_missing").size()>=5,"Missing optional diagnostics absent");
        JsonObject critical=inspect(mutate(original,out.resolve("without-manual-reboot-frontier"),true));
        check(!critical.get("discovery_complete").getAsBoolean(),"Missing manual frontier falsely complete");
        check(roles(critical).contains("reboot_gate"),"Removing manual evidence must retain the independently proved automatic gate");
        check(critical.getAsJsonArray("critical_missing").asList().stream().anyMatch(v->v.getAsString().equals("manual_reboot_frontier")),"Missing manual proof was not identified");
        check(hash(original).equals(before),"Original APK changed");
        JsonObject report=new JsonObject();report.addProperty("passed",true);report.addProperty("inputSha256",before);
        report.add("original",baseline);report.add("optionalAnchorsAbsent",optional);report.add("manualFrontierAbsent",critical);
        report.addProperty("fixtureOnly",true);report.addProperty("deviceOperations",false);
        Files.writeString(out.resolve("report.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("Critical/optional DEX mutation checks passed: "+out);
    }
}
