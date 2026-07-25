package com.peripheral.scale;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser do protocolo Digitron DGN (T.1), string de 8 bytes + CR.
 * <p>
 * Exemplos do manual:
 * <ul>
 *   <li>{@code D000.980} — peso estável</li>
 *   <li>{@code E000.050} — líquido estável com tara de hardware</li>
 *   <li>{@code L000.001} — peso negativo (abaixo de zero)</li>
 *   <li>{@code M000.200} — valor da tara (sem o objeto de tara na plataforma)</li>
 * </ul>
 * Erro clássico: tratar {@code L}/{@code H}/{@code I} como peso positivo e
 * {@code M} como carga — isso faz o fluxo mostrar gramas a mais com a balança vazia.
 */
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
        double magnitude;
        try {
            magnitude = Double.parseDouble(integerPart + "." + decimalPart);
        } catch (NumberFormatException e) {
            return ParseResult.unparsed(trimmed);
        }

        double weightKg = toSignedWeightKg(statusChar, magnitude);
        boolean stable = isStableStatus(statusChar);
        boolean tareReferenceOnly = statusChar == 'M';

        String display = (stable ? "Estável: " : "Instável: ")
                + ScaleWeightFormat.formatGramsWithUnit(weightKg);
        if (tareReferenceOnly) {
            display = "Tara (sem recipiente): " + ScaleWeightFormat.formatGramsWithUnit(magnitude);
        }
        return new ParseResult(trimmed, weightKg, stable, display, true, tareReferenceOnly);
    }

    /**
     * Converte o valor ASCII do protocolo no peso real em kg.
     * Status negativos e referência de tara não devem virar carga positiva.
     */
    static double toSignedWeightKg(char statusChar, double magnitude) {
        if (magnitude < 0) {
            magnitude = 0;
        }
        switch (statusChar) {
            case 'H': // negativo em movimento
            case 'I': // negativo estável com tara
            case 'L': // negativo
                return -magnitude;
            case 'M':
                // Manual: "tara sem o peso da tara na plataforma" — o número é a tara,
                // não há produto. Peso líquido efetivo ≈ 0 (não +tara).
                return 0;
            default:
                return magnitude;
        }
    }

    static boolean isStableStatus(char statusChar) {
        switch (statusChar) {
            case 'D': // peso estável
            case 'E': // líquido estável com tara
            case 'F': // zero
            case 'G': // zero com tara
            case 'I': // negativo estável com tara
            case 'L': // negativo estável
                return true;
            default:
                // A B C H M @ — em movimento / referência de tara
                return false;
        }
    }

    public static final class ParseResult {
        private final String raw;
        private final double weightKg;
        private final boolean stable;
        private final String displayText;
        private final boolean parsed;
        private final boolean tareReferenceOnly;

        private ParseResult(String raw, double weightKg, boolean stable, String displayText,
                            boolean parsed, boolean tareReferenceOnly) {
            this.raw = raw;
            this.weightKg = weightKg;
            this.stable = stable;
            this.displayText = displayText;
            this.parsed = parsed;
            this.tareReferenceOnly = tareReferenceOnly;
        }

        static ParseResult unparsed(String raw) {
            return new ParseResult(raw != null ? raw : "", 0, false,
                    raw != null ? raw : "", false, false);
        }

        ParseResult(String raw, double weightKg, boolean stable, String displayText) {
            this(raw, weightKg, stable, displayText, true, false);
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

        /** True quando a linha é status M (valor de tara, sem carga). */
        public boolean isTareReferenceOnly() {
            return tareReferenceOnly;
        }
    }
}
