package com.peripheral.workflow.label;

import com.peripheral.scale.ScaleWeightFormat;

public final class LabelLayout {

    public static final float LABEL_WIDTH_MM = 100f;
    public static final float LABEL_HEIGHT_MM = 50f;
    public static final int ZPL_DPI = 203;
    public static final float PDF_WEIGHT_FONT_SIZE = 32f;

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
}
