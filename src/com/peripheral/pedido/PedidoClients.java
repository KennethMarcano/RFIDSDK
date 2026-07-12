package com.peripheral.pedido;

public final class PedidoClients {

    private PedidoClients() {
    }

    public static PedidoClient createDefault() {
        if (useMock()) {
            return new MockPedidoClient();
        }
        return new EshipPedidoClient();
    }

    public static boolean useMock() {
        String prop = System.getProperty("rfidsdk.pedido.mock");
        if (prop != null) {
            return !"false".equalsIgnoreCase(prop.trim());
        }
        return true;
    }
}
