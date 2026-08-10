package com.peripheral.scale;

/**
 * Mapeamento HX711 na Raspberry Pi (numeração BCM).
 * <pre>
 * GPIO 5  ────────  DT  (DOUT)
 * GPIO 6  ────────  SCK
 * </pre>
 */
public final class Hx711GpioPins {

    public static final int DT_BCM = 5;
    public static final int SCK_BCM = 6;
    public static final String CHIP = "gpiochip0";
    public static final String LOGICAL_PORT = "GPIO-HX711";

    /**
     * Divisor de calibração: kg = (raw - tara) / REF_UNIT.
     * Pode ser sobrescrito com {@code -Dhx711.refUnit=...}.
     */
    public static final String DEFAULT_REF_UNIT = "198025";

    private Hx711GpioPins() {
    }

    public static String describeWiring() {
        return "HX711 | DT=BCM" + DT_BCM + " SCK=BCM" + SCK_BCM + " (" + CHIP + ")";
    }
}
