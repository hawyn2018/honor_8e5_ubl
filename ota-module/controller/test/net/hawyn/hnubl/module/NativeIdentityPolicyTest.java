package net.hawyn.hnubl.module;

import java.io.IOException;

public final class NativeIdentityPolicyTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private interface Action{void run()throws Exception;}
    private static void rejects(Action action)throws Exception{try{action.run();}catch(IOException expected){checks++;return;}throw new AssertionError("Invalid identity accepted");}
    public static void main(String[] args)throws Exception{
        check(BootIdentity.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","_a").sourceSlot==0,"slot A");
        check(BootIdentity.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","b").sourceSlot==1,"slot B");
        rejects(()->BootIdentity.parse("bad","_a"));rejects(()->BootIdentity.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","_c"));
        StringBuilder stat=new StringBuilder("42 (update (engine) name) S");for(int i=1;i<19;i++)stat.append(' ').append(i);stat.append(" 456789");
        check(NativeEngineIdentity.startTime(stat.toString())==456789,"stat comm parentheses/spaces do not shift start time");
        rejects(()->NativeEngineIdentity.startTime("42 no-parentheses"));rejects(()->NativeEngineIdentity.startTime("42 (name) S 1"));
        check(NativeEngineIdentity.effectiveUid("Name:\tmain\nUid:\t0\t1000\t0\t0\n")==1000,"effective UID is checked rather than inode owner");
        rejects(()->NativeEngineIdentity.effectiveUid("Uid: bad bad\n"));rejects(()->NativeEngineIdentity.effectiveUid("Name: other\n"));
        check(NativePolicyTypes.type("u:r:update_engine:s0\0").equals("update_engine"),"actual daemon type");
        check(NativePolicyTypes.type("u:object_r:update_engine_data_file:s0:c123,c456").equals("update_engine_data_file"),"actual socket type ignores categories");
        String rules=NativePolicyTypes.rules("update_engine","ksu","update_engine_data_file");
        check(rules.equals("allow update_engine ksu unix_stream_socket connectto\nallow update_engine update_engine_data_file sock_file { getattr write }\n"),"only narrow observed-type rules are emitted");
        rejects(()->NativePolicyTypes.type("u:r:bad\nallow:s0"));rejects(()->NativePolicyTypes.rules("*","ksu","type"));rejects(()->NativePolicyTypes.rules("update_engine","ksu;","type"));
        System.out.println("NATIVE_IDENTITY_POLICY_CHECKS="+checks);
    }
}
