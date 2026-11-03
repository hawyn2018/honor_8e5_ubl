package net.hawyn.hnubl.module;

import android.net.LocalSocket;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Streaming framing; output is never truncated or accumulated into one buffer. */
final class RootWire {
    static final byte[] REQUEST_MAGIC = "HNUBLRT1".getBytes(StandardCharsets.US_ASCII);
    static final byte[] AUTH_MAGIC = "HNUBLRA1".getBytes(StandardCharsets.US_ASCII);
    static final byte[] RESPONSE_MAGIC = "HNUBLRS1".getBytes(StandardCharsets.US_ASCII);
    static final int STDOUT = 1, STDERR = 2, EXIT = 3, ERROR = 4, STARTED = 16;

    private RootWire() {}

    static void serve(LocalSocket socket) throws IOException {
        Writer writer = new Writer(new DataOutputStream(socket.getOutputStream()));
        final String command;
        try { command = readCommand(socket.getInputStream()); }
        catch (IOException failure) { writer.error("Command framing or encoding rejected"); return; }
        final Process child;
        try {
            child = new ProcessBuilder("/system/bin/sh", "-c", command).start();
        } catch (IOException failure) {
            writer.error("Shell process creation failed");
            return;
        }
        // This protocol transports commands and output, not an interactive stdin.
        try { child.getOutputStream().close(); } catch (IOException ignored) { }
        AtomicBoolean pipeFailure = new AtomicBoolean();
        // A disconnected caller never causes another execution. Continue draining
        // pipes after transport failure so an already started write can finish.
        writer.frame(STARTED, new byte[0], 0, 0);
        Thread output = pump(child.getInputStream(), writer, STDOUT, pipeFailure);
        Thread error = pump(child.getErrorStream(), writer, STDERR, pipeFailure);
        boolean interrupted = false;
        int result;
        for (;;) {
            try { result = child.waitFor(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        interrupted |= join(output);
        interrupted |= join(error);
        if (pipeFailure.get()) {
            byte[] message = "[hnubl-module] Child output pipe failed; do not replay the command\n"
                    .getBytes(StandardCharsets.UTF_8);
            writer.frame(STDERR, message, 0, message.length);
            if (result == 0) result = 125;
        }
        writer.exit(result);
        if (interrupted) Thread.currentThread().interrupt();
    }

    static String readCommand(InputStream stream) throws IOException {
        DataInputStream input = new DataInputStream(stream);
        byte[] magic = new byte[REQUEST_MAGIC.length];
        input.readFully(magic);
        if (!Arrays.equals(magic, REQUEST_MAGIC)) throw new IOException("Protocol version rejected");
        long size = input.readInt() & 0xffffffffL;
        if (size > Integer.MAX_VALUE) throw new IOException("Command exceeds the Java byte-array range");
        byte[] commandBytes = new byte[(int) size];
        input.readFully(commandBytes);
        final String command;
        try {
            command = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(commandBytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IOException("Command encoding rejected", failure);
        }
        if (command.indexOf('\0') >= 0) {
            throw new IOException("NUL in command");
        }
        return command;
    }

    private static Thread pump(final InputStream input, final Writer writer, final int type,
                               final AtomicBoolean pipeFailure) {
        Thread thread = new Thread(() -> {
            try (InputStream stream = input) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (count > 0) writer.frame(type, buffer, 0, count);
                }
            } catch (IOException ignored) {
                pipeFailure.set(true);
            }
        }, "hnubl-stream");
        thread.start();
        return thread;
    }

    private static boolean join(Thread thread) {
        boolean interrupted = false;
        for (;;) {
            try { thread.join(); return interrupted; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
    }

    private static final class Writer {
        private final DataOutputStream output;
        private boolean failed;
        private boolean header;

        Writer(DataOutputStream output) { this.output = output; }

        synchronized void frame(int type, byte[] bytes, int offset, int count) {
            if (failed) return;
            try {
                if (!header) { output.write(RESPONSE_MAGIC); header = true; }
                output.writeByte(type);
                output.writeInt(count);
                output.write(bytes, offset, count);
                output.flush();
            } catch (IOException failure) { failed = true; }
        }

        void error(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            frame(ERROR, bytes, 0, bytes.length);
            exit(126);
        }

        void exit(int value) {
            byte[] bytes = new byte[] { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
            frame(EXIT, bytes, 0, bytes.length);
        }
    }
}
