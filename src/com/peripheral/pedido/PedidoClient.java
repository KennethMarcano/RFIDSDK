package com.peripheral.pedido;

public interface PedidoClient {

    Pedido fetchPedido(String numeroPedido) throws PedidoException;
}
