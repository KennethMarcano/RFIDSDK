package com.peripheral.pedido;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PedidoItem {

    private final String codigoProduto;
    private final String nome;
    private final int quantidadeEsperada;
    private final double pesoUnitarioKg;

    public PedidoItem(String codigoProduto, String nome, int quantidadeEsperada, double pesoUnitarioKg) {
        this.codigoProduto = codigoProduto;
        this.nome = nome;
        this.quantidadeEsperada = quantidadeEsperada;
        this.pesoUnitarioKg = pesoUnitarioKg;
    }

    public String getCodigoProduto() {
        return codigoProduto;
    }

    public String getNome() {
        return nome;
    }

    public int getQuantidadeEsperada() {
        return quantidadeEsperada;
    }

    public double getPesoUnitarioKg() {
        return pesoUnitarioKg;
    }

    public double getPesoTotalEsperadoKg() {
        return pesoUnitarioKg * quantidadeEsperada;
    }
}
