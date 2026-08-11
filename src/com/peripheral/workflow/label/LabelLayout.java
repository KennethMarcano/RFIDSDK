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
}
