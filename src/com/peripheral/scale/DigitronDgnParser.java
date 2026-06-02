package com.peripheral.scale;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DigitronDgnParser {

    private static final Pattern DGN_VALUE = Pattern.compile("^[A-Z@]([0-9]+)\\.([0-9]+)$");

    private DigitronDgnParser() {
    }

    public static ParseResult parse(String rawLine) {
        if (rawLine == null) {
            return ParseResult.unparsed("");
        }
        String trimmed = rawLine.trim();
        if (trimmed.endsWith("#")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (!DigitronScaleProber.isDigitronLine(trimmed)) {
            return ParseResult.unparsed(trimmed);
        }
        char statusChar = trimmed.charAt(0);
        Matcher matcher = DGN_VALUE.matcher(trimmed);
        if (!matcher.matches()) {
            return ParseResult.unparsed(trimmed);
        }
        String integerPart = matcher.group(1);
        String decimalPart = matcher.group(2);
        double value;
        try {
            value = Double.parseDouble(integerPart + "." + decimalPart);
        } catch (NumberFormatException e) {
            return ParseResult.unparsed(trimmed);
        }
        boolean stable = statusChar == 'D';
        String display = (stable ? "Estável: " : "Instável: ") + formatWeight(value) + " kg";
        return new ParseResult(trimmed, value, stable, display);
    }

    private static String formatWeight(double value) {
        if (value == Math.floor(value)) {
            return String.format("%.0f", value);
        }
        return String.format("%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    public static final class ParseResult {
        private final String raw;
        private final double weightKg;
        private final boolean stable;
        private final String displayText;
        private final boolean parsed;

        private ParseResult(String raw, double weightKg, boolean stable, String displayText, boolean parsed) {
            this.raw = raw;
            this.weightKg = weightKg;
            this.stable = stable;
            this.displayText = displayText;
            this.parsed = parsed;
        }

        static ParseResult unparsed(String raw) {
            return new ParseResult(raw != null ? raw : "", 0, false, raw != null ? raw : "", false);
        }

        ParseResult(String raw, double weightKg, boolean stable, String displayText) {
            this(raw, weightKg, stable, displayText, true);
        }

        public String getRaw() {
            return raw;
        }

        public double getWeightKg() {
            return weightKg;
        }

        public boolean isStable() {
            return stable;
        }

        public String getDisplayText() {
            return displayText;
        }

        public boolean isParsed() {
            return parsed;
        }
    }
}
