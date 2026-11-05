package net.hawyn.hnubl.module;

import java.io.IOException;

/** Host protocol/path checks; Linux mount namespaces and AF_UNIX need device tests. */
public final class TransportContractTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void rejects(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("Invalid transport input accepted");
    }
    public static void main(String[] args) throws Exception {
        for (String path : new String[] { "/data/user_de/0/com.hihonor.ouc", "/data/user/10/com.hihonor.ouc", "/data/data/com.hihonor.ouc" }) {
            check(ApplicationStorage.otaSocket(path).equals(path + "/files/hnubl-module/ota.sock"), "PM CE/DE/user selection is retained");
        }
        for (String path : new String[] { "data/user_de/0/app", "/tmp/app", "/data/../system", "/data/user//0/app", "/data/user/./0/app", "/data/user/0/app/", "/data/user/0/ap\0p" }) rejects(() -> ApplicationStorage.otaSocket(path));
        MarkerStateProof.require("{\"ok\":true,\"state\":0,\"decision\":\"DENY\"}"); checks++;
        MarkerStateProof.require("{\"ok\":true,\"state\":1,\"decision\":\"ALLOW\"}"); checks++;
        for (String invalid : new String[] { "", "{}", "{\"ok\":false,\"state\":0}", "{\"ok\":\"true\",\"state\":0}", "{\"ok\":true,\"state\":\"0\"}", "{\"ok\":true,\"state\":0.5}", "{\"ok\":true,\"state\":2}", "{\"ok\":true,\"state\":1,\"decision\":\"DENY\"}" }) rejects(() -> MarkerStateProof.require(invalid));
        java.util.List<String> calls=new java.util.ArrayList<>();
        DeferredSlotStore.Marker marker=command->{calls.add(command);return "{\"ok\":true,\"state\":0,\"decision\":\""+(command.equals("CONSUME")?"ALLOW":"DENY")+"\"}";};
        LegacyOtaFallback safe=new LegacyOtaFallback("native optional unavailable",(s,t)->true);
        check(new org.json.JSONObject(safe.request("BEGIN",marker)).getBoolean("ok")&&calls.equals(java.util.List.of("RESET")),"optional bootstrap maps BEGIN to original RESET");
        check(new org.json.JSONObject(safe.request("CONSUME",marker)).getString("decision").equals("ALLOW"),"confirmed no record preserves original gate");
        int before=calls.size();
        LegacyOtaFallback unknown=new LegacyOtaFallback("pending record unreadable");
        org.json.JSONObject fallbackGrant=new org.json.JSONObject(unknown.request("CONSUME",marker));
        check(fallbackGrant.getBoolean("authorized")&&fallbackGrant.getString("decision").equals("EXECUTION_FAILED")&&calls.size()==before+1,"missing official execution capability is reported after the file grant");
        check(new org.json.JSONObject(unknown.request("RESET",marker)).getBoolean("ok"),"reset still invalidates token while native bootstrap pending");
        DeferredSlotStoreTest.Marker real=new DeferredSlotStoreTest.Marker(new java.util.ArrayList<>());int[] actions={0};
        LegacyOtaFallback noRegistry=new LegacyOtaFallback("local registry unavailable",(s,t)->{actions[0]++;return true;});
        for(String command:new String[]{"BEGIN","RESET"}) {
            real.token=1;check(new org.json.JSONObject(noRegistry.request(command,real)).getInt("state")==0&&real.token==0&&actions[0]==0,"fallback invalidates old file grant without requiring the registry "+command);
            real.token=1;check(new org.json.JSONObject(DeferredSlotStore.unregistered(null).ota(command,real,(s,t)->{throw new AssertionError("reset executed action");})).getInt("state")==0&&real.token==0,"direct unregistered reset is also permission safe "+command);
        }
        real.token=1;org.json.JSONObject executed=new org.json.JSONObject(noRegistry.request("CONSUME",real));
        check(executed.getBoolean("authorized")&&executed.getString("decision").equals("ALLOW")&&actions[0]==1&&real.token==0,"fallback consumes once and executes the official action exactly once");
        for(String command:new String[]{"STATUS","PREPARE"})rejects(()->RootBroker.readOtaCommand(new java.io.ByteArrayInputStream((command+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        for(String stage:new String[]{"status","open_start","open_ok","open_failed","prepare_ready","prepare_wait","consume_allow","consume_deny","original_started","original_returned","original_failed","lease_released","frontier_pass","terminal_enter","terminal_blocked","label_state_zero","label_state_one","label_read_error"}){
            String frame="TRACE "+stage+" abc_123-XYZ";
            check(RootBroker.readOtaCommand(new java.io.ByteArrayInputStream((frame+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII))).equals(frame),"fixed trace stage accepted "+stage);
            check(OtaClientTrace.parse(frame).attempt.equals("abc_123-XYZ"),"trace attempt correlation retained");
        }
        for(String invalid:new String[]{"TRACE","TRACE arbitrary abc","TRACE open_ok ","TRACE open_ok a b","TRACE  open_ok abc","TRACE open_ok a/b","TRACE open_ok " + "x".repeat(25),"trace open_ok abc","TRACE open_ok a\tb","TRACE open_ok abc\nCONSUME"})
            rejects(()->OtaClientTrace.parse(invalid));
        rejects(()->RootBroker.readOtaCommand(new java.io.ByteArrayInputStream(("TRACE "+"x".repeat(80)+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        System.out.println("TRANSPORT_CONTRACT_CHECKS=" + checks);
    }
}
