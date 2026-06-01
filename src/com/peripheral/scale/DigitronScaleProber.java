package com.peripheral.scale;

import com.peripheral.core.PortProbeResult;
import com.peripheral.core.SerialConnectionConfig;
import com.peripheral.core.SerialPortProber;
import com.peripheral.transport.SerialLink;

import java.util.regex.Pattern;

public class DigitronScaleProber implements SerialPortProber {

    private static final Pattern DGN_LINE = Pattern.compile("^[A-Z@][0-9]+\\.[0-9]+$");

    @Override
    public PortProbeResult probe(SerialConnectionConfig config, long timeoutMs) {
        if (config == null || config.getPortName() == null || config.getPortName().trim().isEmpty()) {
            return PortProbeResult.openFailed("Porta serial não informada", "");
        }
        SerialLink link = new SerialLink();
        String lastLine = null;
        try {
            link.open(config);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (msg.toLowerCase().contains("busy") || msg.toLowerCase().contains("acesso")) {
                return PortProbeResult.busy(
                        "Porta " + config.getPortName() + " em uso por outro programa.",
                        "Feche o monitor serial ou outro app que use esta COM.");
            }
            return PortProbeResult.openFailed(
                    "Não foi possível abrir " + config.getPortName() + ": " + msg,
                    "Verifique se o cabo USB está conectado e se a porta existe.");
        }
        try {
            long deadline = System.currentTimeMillis() + Math.max(500, timeoutMs);
            while (System.currentTimeMillis() < deadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                String line = link.pollLine(Math.min(remaining, 250));
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                lastLine = line.trim();
                if (isDigitronLine(lastLine)) {
                    return PortProbeResult.match(
                            "Balança Digitron detectada na porta " + config.getPortName() + ".",
                            "Exemplo recebido: " + lastLine);
                }
                return PortProbeResult.wrongDevice(
                        "Recebeu dados, mas não no formato Digitron DGN.",
                        "Linha recebida: " + lastLine
                                + "\nEsperado formato como D000.980 (protocolo T.1 + DGN, 9600 8N1)."
                                + "\nEsta porta pode ser o módulo RFID ou outro dispositivo.");
            }
            if (lastLine != null) {
                return PortProbeResult.wrongDevice(
                        "Dados recebidos não correspondem ao protocolo Digitron.",
                        "Última linha: " + lastLine);
            }
            return PortProbeResult.noResponse(
                    "Nenhum peso recebido na porta " + config.getPortName() + ".",
                    "Confirme protocolo T.1 + DGN na balança, baud " + config.getBaudRate()
                            + ", cabo RS232 e se escolheu o adaptador USB-serial correto.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PortProbeResult.noResponse("Teste interrompido.", "");
        } finally {
            link.close();
        }
    }

    static boolean isDigitronLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.endsWith("#")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return DGN_LINE.matcher(trimmed).matches();
    }
}
