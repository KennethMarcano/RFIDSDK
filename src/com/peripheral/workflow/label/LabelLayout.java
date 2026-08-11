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
}
