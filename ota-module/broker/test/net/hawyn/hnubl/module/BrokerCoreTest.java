package net.hawyn.hnubl.module;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Host-side protocol/value checks. Does not create sockets or run commands. */
public final class BrokerCoreTest {
    private static int checks;
    private interface Checked { void run() throws IOException; }

    public static void main(String[] args) throws Exception {
        require(MarkerValue.strict(null) == 0, "missing");
        require(MarkerValue.strict(new byte[0]) == 0, "empty");
        require(MarkerValue.strict(new byte[] { '1' }) == 1, "exact one");
        for (String value : new String[] { "0", "01", "1\n", "1\r\n", " 1", "1 ", "true", "\uFEFF1", "1\0" }) {
            require(MarkerValue.strict(value.getBytes(StandardCharsets.UTF_8)) == 0, "invalid marker");
        }
        for (String command : new String[] { "", "id -u", "printf '引号\\n'", "a\nb", "dd if=/one of=/two bs=4096" }) {
            require(command.equals(RootWire.readCommand(new ByteArrayInputStream(request(command.getBytes(StandardCharsets.UTF_8))))),
                    "exact UTF-8 command roundtrip");
        }
        byte[] valid = request("id -u".getBytes(StandardCharsets.UTF_8));
        for (int size : new int[] { 0, 4, 8, 11, valid.length - 1 }) {
            byte[] truncated = Arrays.copyOf(valid, size);
            fails(() -> RootWire.readCommand(new ByteArrayInputStream(truncated)));
        }
        byte[] wrongMagic = valid.clone();
        wrongMagic[0] = '!';
        fails(() -> RootWire.readCommand(new ByteArrayInputStream(wrongMagic)));
        byte[] wrongLength = Arrays.copyOf(valid, 12);
        Arrays.fill(wrongLength, 8, 12, (byte) 0xff);
        fails(() -> RootWire.readCommand(new ByteArrayInputStream(wrongLength)));
        fails(() -> RootWire.readCommand(new ByteArrayInputStream(request(new byte[] { (byte) 0xc3, 0x28 }))));
        fails(() -> RootWire.readCommand(new ByteArrayInputStream(request(new byte[] { 'i', 0, 'd' }))));
        for (String command : new String[] { "STATE", "CONSUME", "RESET", "BEGIN" }) {
            require(command.equals(RootBroker.readOtaCommand(new ByteArrayInputStream(
                    (command + "\n").getBytes(StandardCharsets.US_ASCII)))), "OTA command");
        }
        for (String invalid : new String[] { "", "STATE", "STATE\r\n", "state\n", "EXEC\n", "CONSUMEX\n", " RESET\n", "STATE\0\n" }) {
            fails(() -> RootBroker.readOtaCommand(new ByteArrayInputStream(invalid.getBytes(StandardCharsets.US_ASCII))));
        }
        require(RootWire.AUTH_MAGIC.length == 8 && RootWire.REQUEST_MAGIC.length == 8 && RootWire.RESPONSE_MAGIC.length == 8,
                "wire version lengths");
        System.out.println("BROKER_CORE_CHECKS=" + checks);
        System.out.println("BROKER_CORE_RESULT=success");
    }

    private static byte[] request(byte[] bytes) throws IOException {
        ByteArrayOutputStream memory = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(memory);
        out.write(RootWire.REQUEST_MAGIC);
        out.writeInt(bytes.length);
        out.write(bytes);
        return memory.toByteArray();
    }

    private static void fails(Checked action) throws IOException {
        try { action.run(); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError("Rejected input was accepted");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
