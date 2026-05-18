package com.rfid.util;

public final class MercuryUriBuilder {

    private MercuryUriBuilder() {
    }

    /**
     * Converte nome de porta do jSerialComm para URI Mercury (tmr:///COM4 ou tmr:///dev/ttyUSB0).
     */
    public static String fromPortName(String portName) {
        if (portName == null || portName.trim().isEmpty()) {
            throw new IllegalArgumentException("Porta serial inválida");
        }
        String port = portName.trim();
        if (port.startsWith("/")) {
            return "tmr://" + port;
        }
        if (port.toUpperCase().startsWith("COM")) {
            return "tmr:///" + port;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "tmr:///" + port;
        }
        if (port.startsWith("dev/")) {
            return "tmr:///" + port;
        }
        return "tmr:///dev/" + port;
    }
}
