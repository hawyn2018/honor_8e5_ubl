package net.hawyn.hnubl.module;

import java.io.IOException;
import org.json.JSONObject;
import org.json.JSONArray;

/** Optional native support may be absent; an unknown durable plan is never ignored. */
final class LegacyOtaFallback {
    private final boolean noPendingRecord;
    private final String warning;
    LegacyOtaFallback(boolean noPendingRecord,String warning){this.noPendingRecord=noPendingRecord;this.warning=warning;}
    String request(String command,DeferredSlotStore.Marker marker)throws IOException{
        try{
            if("STATE".equals(command))return marker.request(command);
            if("CONSUME".equals(command)&&!noPendingRecord)return new JSONObject().put("ok",false).put("state",0).put("decision","DENY")
                .put("error","Deferred slot record requires a valid current boot/source identity").put("warnings",new JSONArray().put(warning)).toString();
            if(!"BEGIN".equals(command)&&!"RESET".equals(command)&&!"CONSUME".equals(command))throw new IOException("OTA command rejected");
            String response=marker.request("BEGIN".equals(command)?"RESET":command);
            return new JSONObject(response).put("warnings",new JSONArray().put(warning)).toString();
        }catch(org.json.JSONException invalid){throw new IOException("Invalid marker fallback response",invalid);}
    }
}
