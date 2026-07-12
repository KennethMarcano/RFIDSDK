package com.peripheral.pedido;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Pedido {

    private final String numero;
    private final List<PedidoVolume> volumes;

    public Pedido(String numero, List<PedidoVolume> volumes) {
        this.numero = numero;
        this.volumes = volumes != null
                ? Collections.unmodifiableList(new ArrayList<>(volumes))
                : Collections.emptyList();
    }

    public String getNumero() {
        return numero;
    }

    public List<PedidoVolume> getVolumes() {
        return volumes;
    }

    public int getVolumeCount() {
        return volumes.size();
    }

    public PedidoVolume getVolume(int index) {
        if (index < 0 || index >= volumes.size()) {
            return null;
        }
        return volumes.get(index);
    }
}
