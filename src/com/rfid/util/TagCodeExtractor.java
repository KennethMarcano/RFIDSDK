package com.rfid.util;

import java.nio.charset.StandardCharsets;

public final class TagCodeExtractor {

    private TagCodeExtractor() {
    }

    public static String fromEpcHex(String epcHex) {
        String epcHexClean = safeString(epcHex).replaceAll("[^0-9A-Fa-f]", "");
        if (epcHexClean.length() < 2) {
            return "0";
        }
        try {
            int hexLen = epcHexClean.length();
            if ((hexLen & 1) == 1) {
                hexLen -= 1;
            }
            byte[] buf = new byte[hexLen / 2];
            for (int i = 0; i < hexLen; i += 2) {
                buf[i / 2] = (byte) Integer.parseInt(epcHexClean.substring(i, i + 2), 16);
            }
            String ascii = new String(buf, StandardCharsets.US_ASCII);
            String digitsOnly = ascii.replaceAll("[^0-9]", "");
            return digitsOnly.length() > 0 ? digitsOnly : "0";
        } catch (Exception e) {
            return "0";
        }
    }

    private static String safeString(String s) {
        return s != null ? s : "";
    }
}
