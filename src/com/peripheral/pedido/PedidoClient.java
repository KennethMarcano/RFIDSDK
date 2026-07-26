package com.peripheral.pedido;

import java.util.List;

public interface PedidoClient {

    Pedido fetchPedido(String numeroPedido) throws PedidoException;

    /** Carrega todos os pedidos disponíveis (mock / fila). */
    default List<Pedido> fetchAllPedidos() throws PedidoException {
        throw new PedidoException("Carregar todos os pedidos não é suportado neste cliente.");
    }
}
