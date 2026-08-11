package com.peripheral.workflow.label;

import com.peripheral.scale.ScaleWeightFormat;

public final class LabelLayout {

    /** Etiqueta física informada: 100 × 80 mm. */
    public static final float DEFAULT_WIDTH_MM = 100f;
    public static final float DEFAULT_HEIGHT_MM = 80f;
    public static final int ZPL_DPI = 203;

    private LabelLayout() {
    }

    public static String formatWeight(double weightKg) {
        return ScaleWeightFormat.formatGramsPlain(weightKg);
    }

    public static float mmToPoints(float mm) {
        return mm * 72f / 25.4f;
    }

    public static int mmToDots(float mm, int dpi) {
        return Math.round(mm * dpi / 25.4f);
    }

    public static String zplEscape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("^", " ").replace("~", " ").replace("\\", " ");
    }

    /**
     * Mantém quebras de linha no QR via ^FH (_0A = LF) para o celular exibir
     * cada campo em uma linha ao escanear.
     */
    public static String zplQrPayload(String payload) {
        String escaped = zplEscape(payload).replace("_", " ");
        StringBuilder out = new StringBuilder(escaped.length() + 16);
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '\r') {
                if (i + 1 < escaped.length() && escaped.charAt(i + 1) == '\n') {
                    i++;
                }
                out.append("_0A");
            } else if (c == '\n') {
                out.append("_0A");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static int orderFontDots(String orderNumber) {
        int len = orderNumber != null ? orderNumber.length() : 0;
        if (len <= 6) {
            return 72;
        }
        if (len <= 10) {
            return 54;
        }
        return 40;
    }

    /** Capacidade aproximada em bytes (modo byte, ECC M) por versão QR. */
    private static final int[] QR_BYTE_CAP_M = {
            0, 14, 26, 42, 62, 84, 106, 122, 152, 180, 214
    };

    public static int qrVersionForBytes(int byteLen) {
        int n = Math.max(0, byteLen);
        for (int version = 1; version < QR_BYTE_CAP_M.length; version++) {
            if (n <= QR_BYTE_CAP_M[version]) {
                return version;
            }
        }
        return QR_BYTE_CAP_M.length - 1;
    }

    /**
     * Tamanho impresso do QR Zebra (^BQ modelo 2): módulos + quiet zone de 4
     * de cada lado, vezes a magnificação.
     */
    public static int zplQrPrintedDots(int payloadBytes, int magnification) {
        int version = qrVersionForBytes(payloadBytes);
        int modules = 21 + 4 * (version - 1);
        int mag = Math.max(1, magnification);
        return (modules + 8) * mag;
    }
}
