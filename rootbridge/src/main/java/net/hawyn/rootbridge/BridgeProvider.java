package net.hawyn.rootbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class BridgeProvider extends ContentProvider {
    private BridgeEngine engine;

    @Override public boolean onCreate() {
        engine = BridgeEngine.get(getContext());
        return true;
    }

    private void checkCaller() {
        int uid = Binder.getCallingUid();
        if (uid != 0 && uid != 2000) throw new SecurityException("ADB shell or root UID required");
        if (!engine.isEnabled()) throw new SecurityException("Bridge is disabled in the application");
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        checkCaller();
        Bundle result = new Bundle();
        if ("submit".equals(method)) {
            if (arg == null || arg.length() > 45000) throw new IllegalArgumentException("Missing or oversized command");
            byte[] bytes;
            try { bytes = Base64.decode(arg, Base64.DEFAULT); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Command must be Base64 UTF-8", e); }
            if (bytes.length > 32768) throw new IllegalArgumentException("Command exceeds 32 KiB");
            String command;
            try {
                command = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) { throw new IllegalArgumentException("Invalid UTF-8", e); }
            result.putString("json", engine.submit(command).toString());
        } else if ("status".equals(method)) {
            result.putString("json", engine.status(arg).toString());
        } else throw new IllegalArgumentException("Supported methods: submit, status");
        return result;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        checkCaller();
        if (!"r".equals(mode)) throw new SecurityException("Read-only output access");
        if (!"content".equals(uri.getScheme()) || !"net.hawyn.rootbridge".equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null) throw new FileNotFoundException("Invalid URI");
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 3 || !"jobs".equals(parts.get(0))
                || !("stdout".equals(parts.get(2)) || "stderr".equals(parts.get(2)))) {
            throw new FileNotFoundException("Expected /jobs/<UUID>/stdout or stderr");
        }
        try { return ParcelFileDescriptor.open(engine.output(parts.get(1), parts.get(2)), ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (IllegalArgumentException e) { throw new FileNotFoundException(e.getMessage()); }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { checkCaller(); throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { checkCaller(); throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri u, ContentValues v) { checkCaller(); throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { checkCaller(); throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { checkCaller(); throw new UnsupportedOperationException(); }
}
