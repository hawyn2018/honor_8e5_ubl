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
        LegacyOtaFallback safe=new LegacyOtaFallback(true,"native optional unavailable");
        check(new org.json.JSONObject(safe.request("BEGIN",marker)).getBoolean("ok")&&calls.equals(java.util.List.of("RESET")),"optional bootstrap maps BEGIN to original RESET");
        check(new org.json.JSONObject(safe.request("CONSUME",marker)).getString("decision").equals("ALLOW"),"confirmed no record preserves original gate");
        int before=calls.size();
        LegacyOtaFallback unknown=new LegacyOtaFallback(false,"pending record unreadable");
        check(new org.json.JSONObject(unknown.request("CONSUME",marker)).getString("decision").equals("DENY")&&calls.size()==before,"unknown durable record never falls through to consume");
        check(new org.json.JSONObject(unknown.request("RESET",marker)).getBoolean("ok"),"reset still invalidates token while native bootstrap pending");
        System.out.println("TRANSPORT_CONTRACT_CHECKS=" + checks);
    }
}
