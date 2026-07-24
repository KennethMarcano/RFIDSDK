package com.peripheral.scale;

import java.util.Locale;

/**
 * Formatação do peso para os displays: sempre em gramas com largura fixa de 5 dígitos,
 * de modo que a leitura não mude de tamanho conforme o valor (capacidade máxima 10 kg = 10000 g).
 */
public final class ScaleWeightFormat {

    public static final double MAX_KG = 10.0;
    public static final int MAX_GRAMS = 10_000;
    public static final int DIGITS = 5;
    public static final String UNIT = "g";
    /** Placeholder com a mesma largura da leitura (5 dígitos). */
    public static final String PLACEHOLDER = "-----";

    private ScaleWeightFormat() {
    }

    public static int toGrams(double kg) {
        return (int) Math.round(kg * 1000.0);
    }

    public static double toKg(int grams) {
        return grams / 1000.0;
    }

    public static boolean isOverload(double kg) {
        return toGrams(kg) > MAX_GRAMS;
    }

    /** Ex.: 3.125 kg -> "03125"; 10 kg -> "10000". Negativos usam sinal e 4 dígitos. */
    public static String formatGrams(double kg) {
        int grams = toGrams(kg);
        if (grams < 0) {
            int abs = Math.min(-grams, 9_999);
            return String.format(Locale.US, "-%04d", abs);
        }
        if (grams > 99_999) {
            grams = 99_999;
        }
        return String.format(Locale.US, "%0" + DIGITS + "d", grams);
    }

    /** Ex.: "03125 g". */
    public static String formatGramsWithUnit(double kg) {
        return formatGrams(kg) + " " + UNIT;
    }

    /**
     * Gramas sem zeros à esquerda, para impressos onde a largura fixa não importa.
     * Ex.: 3.125 kg -> "3125 g".
     */
    public static String formatGramsPlain(double kg) {
        return toGrams(kg) + " " + UNIT;
    }

    /**
     * Converte o texto de peso do evento (em kg) para gramas formatados.
     *
     * @return {@link #PLACEHOLDER} quando o valor não é numérico
     */
    public static String formatGramsFromKgText(String kgText) {
        Double kg = parseKg(kgText);
        return kg != null ? formatGrams(kg) : PLACEHOLDER;
    }

    public static Double parseKg(String kgText) {
        if (kgText == null) {
            return null;
        }
        String normalized = kgText.trim().replace(',', '.');
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
