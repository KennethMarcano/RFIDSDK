package com.peripheral.pedido;

public class EshipPedidoClient implements PedidoClient {

    @Override
    public Pedido fetchPedido(String numeroPedido) throws PedidoException {
        throw new PedidoException(
                "Consulta eShip ainda não implementada. Ative o modo mock (rfidsdk.pedido.mock=true).");
    }
}
