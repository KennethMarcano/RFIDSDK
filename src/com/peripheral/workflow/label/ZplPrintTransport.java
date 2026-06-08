package com.peripheral.workflow.label;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ZplPrintTransport {

    public static final String PRINTER_PORT_PROPERTY = "rfidsdk.label.printer.port";
    private static final int DEFAULT_BAUD = 9600;

    public Path send(String zpl, Path sessionDirectory, int labelIndex) throws IOException {
        if (zpl == null || zpl.isEmpty()) {
            throw new IOException("Comando ZPL vazio");
        }

        Path zplFile = saveZplCopy(zpl, sessionDirectory, labelIndex);
        String portName = System.getProperty(PRINTER_PORT_PROPERTY, "").trim();
        if (portName.isEmpty()) {
            return zplFile;
        }

        SerialPort port = SerialPort.getCommPort(portName);
        port.setComPortParameters(DEFAULT_BAUD, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 5000, 0);

        if (!port.openPort()) {
            throw new IOException("Não foi possível abrir a porta da impressora: " + portName
                    + " (ZPL salvo em " + zplFile + ")");
        }

        try {
            byte[] payload = zpl.getBytes(StandardCharsets.US_ASCII);
            try (OutputStream out = port.getOutputStream()) {
                out.write(payload);
                out.flush();
            }
        } finally {
            port.closePort();
        }
        return zplFile;
    }

    private Path saveZplCopy(String zpl, Path sessionDirectory, int labelIndex) throws IOException {
        if (sessionDirectory == null) {
            throw new IOException("Diretório da sessão não definido");
        }
        Files.createDirectories(sessionDirectory);
        Path zplFile = sessionDirectory.resolve(String.format("label_%03d.zpl", Math.max(1, labelIndex)));
        Files.write(zplFile, zpl.getBytes(StandardCharsets.US_ASCII));
        return zplFile.toAbsolutePath();
    }
}
