package com.peripheral.pedido;

public class PedidoSerial {

    private final String serial;
    private final String epc;

    public PedidoSerial(String serial, String epc) {
        this.serial = serial != null ? serial : "";
        this.epc = (epc == null || epc.isEmpty()) ? this.serial : epc;
    }

    /** Serial e EPC são o mesmo identificador. */
    public static PedidoSerial of(String serial) {
        return new PedidoSerial(serial, serial);
    }

    public String getSerial() {
        return serial;
    }

    public String getEpc() {
        return epc;
    }
}
