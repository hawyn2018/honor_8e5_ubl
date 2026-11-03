package net.hawyn.hnubl.module;

/** No whitespace, newline, BOM, trailing bytes or number coercion is accepted. */
final class MarkerValue {
    private MarkerValue() {}
    static int strict(byte[] bytes) {
        return bytes != null && bytes.length == 1 && bytes[0] == (byte) '1' ? 1 : 0;
    }
}
