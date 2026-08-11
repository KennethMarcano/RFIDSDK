package com.peripheral.workflow.label;

import com.peripheral.pedido.PedidoItem;
import com.peripheral.pedido.PedidoVolume;
import com.peripheral.scale.ScaleWeightFormat;
import com.peripheral.workflow.WorkflowContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LabelContent {

    private final String orderNumber;
    private final int volumeIndex;
    private final List<Line> lines;
    private final double measuredWeightKg;
    private final String qrPayload;

    public LabelContent(String orderNumber, int volumeIndex, List<Line> lines,
                        double measuredWeightKg, String qrPayload) {
        this.orderNumber = orderNumber != null ? orderNumber : "";
        this.volumeIndex = volumeIndex;
        this.lines = lines != null
                ? Collections.unmodifiableList(new ArrayList<>(lines))
                : Collections.emptyList();
        this.measuredWeightKg = measuredWeightKg;
        this.qrPayload = qrPayload != null ? qrPayload : "";
    }

    public static LabelContent from(WorkflowContext context) {
        String order = context != null && context.getNumeroPedido() != null
                ? context.getNumeroPedido().trim() : "";
        int volume = context != null ? context.getVolumeIndex() : 1;
        double weight = context != null ? context.getWeightKg() : 0;
        PedidoVolume pedidoVolume = context != null ? context.getCurrentVolume() : null;

        List<Line> lines = new ArrayList<>();
        if (pedidoVolume != null) {
            for (PedidoItem item : pedidoVolume.getItens()) {
                int qty = Math.max(1, item.getQuantidadeEsperada());
                lines.add(new Line(
                        item.getCodigoProduto(),
                        item.getNome(),
                        qty,
                        item.getPesoUnitarioKg(),
                        item.getPesoTotalEsperadoKg()));
            }
        }

        StringBuilder qr = new StringBuilder();
        qr.append("Pedido: ").append(order.isEmpty() ? "-" : order).append('\n');
        qr.append("Volume: ").append(volume).append('\n');
        qr.append('\n');
        for (Line line : lines) {
            qr.append(line.codigo).append('\n');
            qr.append("Qtd: ").append(line.quantidade).append('\n');
            qr.append("Peso: ").append(ScaleWeightFormat.formatGramsPlain(line.pesoLinhaKg)).append('\n');
            qr.append('\n');
        }
        qr.append("Peso conferido: ").append(ScaleWeightFormat.formatGramsPlain(weight));
        return new LabelContent(order, volume, lines, weight, qr.toString());
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public int getVolumeIndex() {
        return volumeIndex;
    }

    public List<Line> getLines() {
        return lines;
    }

    public double getMeasuredWeightKg() {
        return measuredWeightKg;
    }

    public String getQrPayload() {
        return qrPayload;
    }

    public static final class Line {
        public final String codigo;
        public final String nome;
        public final int quantidade;
        public final double pesoUnitarioKg;
        public final double pesoLinhaKg;

        public Line(String codigo, String nome, int quantidade,
                    double pesoUnitarioKg, double pesoLinhaKg) {
            this.codigo = codigo != null ? codigo : "";
            this.nome = nome != null ? nome : "";
            this.quantidade = quantidade;
            this.pesoUnitarioKg = pesoUnitarioKg;
            this.pesoLinhaKg = pesoLinhaKg;
        }
    }
}
