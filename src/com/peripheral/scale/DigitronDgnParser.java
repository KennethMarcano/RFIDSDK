package com.peripheral.scale;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser do protocolo Digitron DGN (T.1), string de 8 bytes + CR.
 * <p>
 * Manual (Tabela 5):
 * <ul>
 *   <li>{@code D000.980} — bruto estável (carga)</li>
 *   <li>{@code E000.050} — líquido estável com tara de hardware</li>
 *   <li>{@code F000.000} / {@code G000.000} — zero (sem / com tara)</li>
 *   <li>{@code M000.200} — referência de tara SEM o recipiente na plataforma
 *       (o número é a tara, NÃO há produto; tratar como 0 g de carga)</li>
 *   <li>{@code L000.001} — negativo estável</li>
 * </ul>
 * Erro clássico: tratar {@code M}/{@code O} como carga positiva — a UI mostra
 * ~100–200 g com a balança vazia (valor da tara de hardware).
 */
public final class DigitronDgnParser {

    private static final Pattern DGN_VALUE = Pattern.compile("^[A-Z@]([0-9]+)\\.([0-9]+)$");

    private DigitronDgnParser() {
    }

    public static ParseResult parse(String rawLine) {
        if (rawLine == null) {
            return ParseResult.unparsed("");
        }
        String trimmed = sanitize(rawLine);
        if (trimmed.isEmpty()) {
            return ParseResult.unparsed("");
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
        if (magnitude < 0) {
            magnitude = 0;
        }

        double weightKg = toProductWeightKg(statusChar, magnitude);
        boolean stable = isStableStatus(statusChar);
        boolean noLoad = isNoLoadStatus(statusChar);

        String display;
        if (noLoad && (statusChar == 'M' || statusChar == 'O')) {
            display = "Tara HW (sem recipiente): "
                    + ScaleWeightFormat.formatGramsWithUnit(magnitude)
                    + " → carga 0 g";
        } else if (noLoad) {
            display = "Zero: " + ScaleWeightFormat.formatGramsWithUnit(0);
        } else {
            display = (stable ? "Estável: " : "Instável: ")
                    + ScaleWeightFormat.formatGramsWithUnit(weightKg);
        }
        return new ParseResult(trimmed, weightKg, stable, display, true, noLoad && (statusChar == 'M' || statusChar == 'O'));
    }

    /**
     * Remove lixo de framing (STX/ETX, NULs, espaços) mantendo o frame DGN.
     */
    static String sanitize(String rawLine) {
        StringBuilder sb = new StringBuilder(rawLine.length());
        for (int i = 0; i < rawLine.length(); i++) {
            char c = rawLine.charAt(i);
            if (c >= 32 && c < 127) {
                sb.append(c);
            }
        }
        String trimmed = sb.toString().trim();
        if (trimmed.endsWith("#")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    /**
     * Peso de produto/carga em kg para o checkout.
     * Status de zero / referência de tara NÃO são carga.
     */
    static double toProductWeightKg(char statusChar, double magnitude) {
        if (isNoLoadStatus(statusChar)) {
            return 0;
        }
        switch (statusChar) {
            case 'H': // negativo em movimento
            case 'I': // negativo estável com tara
            case 'L': // negativo estável
            case 'T': // negativo em movimento com tara
                // Abaixo de zero: para checkout trata como 0 (sem produto).
                return 0;
            case 'P': // sobrecarga com tara
            case 'Q': // sobrecarga sem tara
            case 'X': // subcarga
            case 'Y': // subcarga com tara
                return magnitude;
            case '@': // bruto em movimento
            case 'A': // líquido em movimento
            case 'D': // bruto estável
            case 'E': // líquido estável
            default:
                return magnitude;
        }
    }

    /**
     * Estados em que não há produto na plataforma (ou só referência de tara).
     */
    static boolean isNoLoadStatus(char statusChar) {
        switch (statusChar) {
            case 'B': // zero em movimento
            case 'C': // zero em movimento com tara
            case 'F': // zero estável
            case 'G': // zero com tara
            case 'M': // tara sem recipiente na plataforma (manual Tabela 5)
            case 'O': // valor da tara com sinal negativo
                return true;
            default:
                return false;
        }
    }

    static boolean isStableStatus(char statusChar) {
        switch (statusChar) {
            case 'D':
            case 'E':
            case 'F':
            case 'G':
            case 'I':
            case 'L':
            case 'M': // referência de tara estável o suficiente para UI
            case 'O':
                return true;
            default:
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

        /** True quando a linha é status M/O (valor de tara, sem carga). */
        public boolean isTareReferenceOnly() {
            return tareReferenceOnly;
        }
    }
}
