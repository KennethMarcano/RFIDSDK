package com.peripheral.pedido;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PedidoVolume {
    private final int indice;
    private final List<PedidoItem> itens;

    public PedidoVolume(int indice, List<PedidoItem> itens) {
        this.indice = indice;
        this.itens = itens != null
                ? Collections.unmodifiableList(new ArrayList<>(itens))
                : Collections.emptyList();
    }

    public int getIndice() {
        return indice;
    }

    public List<PedidoItem> getItens() {
        return itens;
    }

    public double getPesoEsperadoKg() {
        double total = 0;
        for (PedidoItem item : itens) {
            total += item.getPesoTotalEsperadoKg();
        }
        return total;
    }

    public String formatSeriaisParaSimulacao() {
        StringBuilder sb = new StringBuilder();
        for (PedidoItem item : itens) {
            for (PedidoSerial serial : item.getSeriais()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(serial.getSerial());
            }
        }
        return sb.toString();
    }

    public int getTotalSeriais() {
        int total = 0;
        for (PedidoItem item : itens) {
            total += item.getSeriais().size();
        }
        return total;
    }
}
