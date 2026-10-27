import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import dev.honor.bootforge.core.EfispImagePatcher;
import net.hawyn.ablefisp.api.AblEfisp;
import net.hawyn.ablefisp.text.TextEntry;
public class BuiltinSmoke {
 public static void main(String[] args) throws Exception {
  File input = new File(args[0]);
  File directory = new File(args[1]); directory.mkdirs();
  int failures = 0;
  for (boolean fake : new boolean[]{false,true}) {
   String mode = fake ? "fakebl" : "freedom";
   try {
    File output = new File(directory,mode+"-efisp.img");
    EfispImagePatcher.Result r = EfispImagePatcher.INSTANCE.patch(input,output,fake);
    String label = ""; int warnings = 0;
    for (TextEntry e : AblEfisp.scanText(Files.readAllBytes(output.toPath())).getEntries()) {
     if (e.getId().equals("fastboot-unlocked")) label=e.getText();
     if (e.getText().contains("has been unlocked")) warnings++;
    }
    System.out.println("SUCCESS mode="+mode+" bytes="+r.getBytes()+" sha256="+r.getSha256()+" sourceSha256="+r.getSourceSha256()+" patches="+r.getPatchCount()+" textChanges="+r.getTextChanges()+" warningEntries="+warnings+" labelBase64="+Base64.getEncoder().encodeToString(label.getBytes("UTF-8")));
   } catch(Exception e) {
    failures++;
    System.out.println("FAILED mode="+mode+" errorBase64="+Base64.getEncoder().encodeToString((e.getClass().getSimpleName()+": "+e.getMessage()).getBytes("UTF-8")));
    e.printStackTrace(System.err);
   }
  }
  if(failures>0)System.exit(1);
 }
}
