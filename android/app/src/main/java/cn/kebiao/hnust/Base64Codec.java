package cn.kebiao.hnust;

/**
 * 极简 Base64（RFC 4648），避免依赖 android.util.Base64，
 * 让网络层能在普通 JVM 上直接跑测试。
 */
final class Base64Codec {

    private static final char[] MAP =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final int[] REV = new int[128];

    static {
        for (int i = 0; i < REV.length; i++) REV[i] = -1;
        for (int i = 0; i < MAP.length; i++) REV[MAP[i]] = i;
        REV['='] = -2;
    }

    private Base64Codec() {
    }

    static String encode(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(MAP[(n >>> 18) & 63]).append(MAP[(n >>> 12) & 63])
                    .append(MAP[(n >>> 6) & 63]).append(MAP[n & 63]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xFF) << 16;
            sb.append(MAP[(n >>> 18) & 63]).append(MAP[(n >>> 12) & 63]).append("==");
        } else if (rest == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(MAP[(n >>> 18) & 63]).append(MAP[(n >>> 12) & 63])
                    .append(MAP[(n >>> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    static byte[] decode(String text) {
        if (text == null) return new byte[0];
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 128 && REV[c] != -1) clean.append(c);
        }
        int len = clean.length();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(len * 3 / 4 + 3);
        int buffer = 0, bits = 0;
        for (int i = 0; i < len; i++) {
            int v = REV[clean.charAt(i)];
            if (v < 0) continue;
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >>> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
