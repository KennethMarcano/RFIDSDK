package com.peripheral.pedido;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PedidoItem {

    private final String codigoProduto;
    private final String nome;
    private final double pesoUnitarioKg;
    private final List<PedidoSerial> seriais;
    private final int quantidadeFallback;

    public PedidoItem(String codigoProduto, String nome, int quantidadeEsperada, double pesoUnitarioKg) {
        this(codigoProduto, nome, pesoUnitarioKg, Collections.emptyList(), quantidadeEsperada);
    }

    public PedidoItem(String codigoProduto, String nome, double pesoUnitarioKg, List<PedidoSerial> seriais) {
        this(codigoProduto, nome, pesoUnitarioKg, seriais, seriais != null ? seriais.size() : 0);
    }

    private PedidoItem(String codigoProduto, String nome, double pesoUnitarioKg,
                       List<PedidoSerial> seriais, int quantidadeFallback) {
        this.codigoProduto = codigoProduto;
        this.nome = nome;
        this.pesoUnitarioKg = pesoUnitarioKg;
        this.seriais = seriais != null
                ? Collections.unmodifiableList(new ArrayList<>(seriais))
                : Collections.emptyList();
        this.quantidadeFallback = quantidadeFallback;
    }

    public String getCodigoProduto() {
        return codigoProduto;
    }

    public String getNome() {
        return nome;
    }

    public int getQuantidadeEsperada() {
        return seriais.isEmpty() ? quantidadeFallback : seriais.size();
    }

    public double getPesoUnitarioKg() {
        return pesoUnitarioKg;
    }

    public List<PedidoSerial> getSeriais() {
        return seriais;
    }

    public boolean hasSeriais() {
        return !seriais.isEmpty();
    }

    public double getPesoTotalEsperadoKg() {
        return pesoUnitarioKg * getQuantidadeEsperada();
    }
}
