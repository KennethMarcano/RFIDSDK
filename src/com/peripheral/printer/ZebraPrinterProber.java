package com.peripheral.printer;

import com.fazecast.jSerialComm.SerialPort;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.PortProbeResult;
import com.peripheral.core.SerialConnectionConfig;
import com.peripheral.core.SerialPortProber;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ZebraPrinterProber implements SerialPortProber {

    @Override
    public PortProbeResult probe(SerialConnectionConfig config, long timeoutMs) {
        if (config == null || config.getPortName() == null || config.getPortName().trim().isEmpty()) {
            return PortProbeResult.openFailed("Porta da impressora não informada", "");
        }
        String portName = config.getPortName().trim();
        try {
            if (ZebraPrinterPeripheral.isRawLinuxLp(portName)) {
                return probeLinuxLp(portName);
            }
            return probeSerial(config, portName);
        } catch (Exception e) {
            return PortProbeResult.openFailed(
                    "Falha ao testar a impressora: "
                            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                    portName);
        }
    }

    static void probeOrThrow(SerialConnectionConfig config) throws PeripheralException {
        PortProbeResult result = new ZebraPrinterProber().probe(config, 3000);
        if (result.isBlocking()) {
            throw new PeripheralException(result.getMessage());
        }
    }

    private static PortProbeResult probeLinuxLp(String portName) {
        Path path = Paths.get(portName);
        if (!Files.exists(path)) {
            return PortProbeResult.openFailed("Dispositivo não encontrado: " + portName, "");
        }
        if (!Files.isWritable(path)) {
            return PortProbeResult.openFailed(
                    "Sem permissão de escrita em " + portName + ". Inclua o usuário no grupo lp.",
                    portName);
        }
        return PortProbeResult.match("Impressora USB detectada em " + portName + ".", portName);
    }

    private static PortProbeResult probeSerial(SerialConnectionConfig config, String portName) {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setComPortParameters(
                config.getBaudRate(),
                config.getDataBits(),
                config.getJSerialCommStopBits(),
                config.getParity().getJSerialCommValue());
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 400, 0);
        if (!port.openPort()) {
            return PortProbeResult.openFailed("Não foi possível abrir " + portName, portName);
        }
        try {
            // ~HS pede status; USB RAW muitas vezes não responde — abrir já valida a porta.
            byte[] ping = "~HS\r\n".getBytes(StandardCharsets.US_ASCII);
            try (OutputStream out = port.getOutputStream()) {
                out.write(ping);
                out.flush();
            }
            byte[] buf = new byte[128];
            int n = port.getInputStream().read(buf);
            String detail = n > 0 ? new String(buf, 0, n, StandardCharsets.US_ASCII).trim() : "";
            if (detail.toUpperCase().contains("ZD") || detail.contains("PRINTER") || n > 0) {
                return PortProbeResult.match("Zebra respondeu em " + portName + ".", detail);
            }
            return PortProbeResult.match(
                    "Porta USB abriu (" + portName + "). A ZD230 em modo RAW pode não devolver status.",
                    portName);
        } catch (Exception e) {
            return PortProbeResult.match(
                    "Porta USB abriu (" + portName + "). Sem leitura de status — comum em USB RAW.",
                    portName);
        } finally {
            port.closePort();
        }
    }
}
