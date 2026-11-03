package net.hawyn.hnubl.module;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class PlatformTextTest {
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]);Files.createDirectories(root);
        Path ascii=root.resolve("proc-status"),utf8=root.resolve("message");
        Files.write(ascii,"Uid:\t0\t0\t0\t0\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(utf8,"提权与更新\n".getBytes(StandardCharsets.UTF_8));
        if(!PlatformText.read(ascii,StandardCharsets.US_ASCII).equals("Uid:\t0\t0\t0\t0\n"))throw new AssertionError("ASCII proc data changed");
        if(!PlatformText.read(utf8,StandardCharsets.UTF_8).equals("提权与更新\n"))throw new AssertionError("UTF8 text changed");
        System.out.println("PLATFORM_TEXT_CHECKS=2");
    }
}
