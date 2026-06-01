package com.peripheral.rfid;

import com.peripheral.core.PortProbeResult;
import com.peripheral.core.SerialConnectionConfig;
import com.peripheral.core.SerialPortProber;
import com.rfid.core.RfidException;
import com.rfid.core.RfidReaderConfig;
import com.rfid.impl.PayneRfidReader;

public class PayneRfidProber implements SerialPortProber {

    @Override
    public PortProbeResult probe(SerialConnectionConfig config, long timeoutMs) {
        if (config == null || config.getPortName() == null || config.getPortName().trim().isEmpty()) {
            return PortProbeResult.openFailed("Porta serial não informada", "");
        }
        String port = config.getPortName().trim();
        PayneRfidReader reader = new PayneRfidReader(new RfidReaderConfig()
                .setBaudRate(config.getBaudRate())
                .setDefaultPowerPercent(50));
        try {
            reader.connect(port);
            String info = reader.getReaderInfo();
            if (info == null || info.trim().isEmpty()) {
                info = "Payne UHF";
            }
            return PortProbeResult.match(
                    "Leitor RFID Payne detectado na porta " + port + ".",
                    info);
        } catch (RfidException e) {
            return classifyRfidFailure(port, e.getMessage(), true);
        } finally {
            reader.disconnect();
        }
    }

    static PortProbeResult classifyRfidFailure(String port, String rawMessage, boolean payne) {
        String msg = rawMessage != null ? rawMessage : "Falha desconhecida";
        String lower = msg.toLowerCase();
        if (lower.contains("busy") || lower.contains("acesso") || lower.contains("in use")) {
            return PortProbeResult.busy(
                    "Porta " + port + " em uso por outro programa.",
                    "Feche outros apps que usem esta COM antes de testar.");
        }
        if (lower.contains("connect") || lower.contains("conectar") || lower.contains("timeout")
                || lower.contains("falha") || lower.contains("fail")) {
            String sdk = payne ? "Payne (115200 bps)" : "Mercury";
            return PortProbeResult.noResponse(
                    "Nenhum leitor RFID " + sdk + " respondeu na porta " + port + ".",
                    msg + "\n\nEsta porta pode ser a balança ou outro dispositivo serial."
                            + "\nLeitores RFID usam baud 115200 e protocolo proprietário.");
        }
        return PortProbeResult.wrongDevice(
                "Resposta inesperada na porta " + port + ".",
                msg + "\nProvavelmente não é um leitor RFID " + (payne ? "Payne" : "Mercury") + ".");
    }

    public static String formatConnectError(String port, String rawMessage, boolean payne) {
        PortProbeResult result = classifyRfidFailure(port, rawMessage, payne);
        if (result.getDetail().isEmpty()) {
            return result.getMessage();
        }
        return result.getMessage() + "\n" + result.getDetail();
    }
}
