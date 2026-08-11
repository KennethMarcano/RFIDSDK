package com.peripheral.printer;

import com.fazecast.jSerialComm.SerialPort;
import com.peripheral.core.DeviceModelEntry;
import com.peripheral.core.PeripheralDataListener;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.core.SerialConnectionConfig;
import com.peripheral.workflow.label.LabelLayout;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ZebraPrinterPeripheral implements ReadablePeripheral, LabelPrinter {

    private final DeviceModelEntry model;
    private SerialConnectionConfig serialConfig;
    private volatile boolean connected;
    private float labelWidthMm = LabelLayout.DEFAULT_WIDTH_MM;
    private float labelHeightMm = LabelLayout.DEFAULT_HEIGHT_MM;

    public ZebraPrinterPeripheral(DeviceModelEntry model, SerialConnectionConfig serialConfig) {
        this.model = model;
        this.serialConfig = serialConfig != null ? serialConfig.copy() : SerialConnectionConfig.printerDefault();
    }

    @Override
    public DeviceModelEntry getModel() {
        return model;
    }

    @Override
    public void connect(SerialConnectionConfig config) throws PeripheralException {
        if (config != null) {
            this.serialConfig = config.copy();
        }
        String port = serialConfig.getPortName();
        if (port == null || port.trim().isEmpty()) {
            throw new PeripheralException("Selecione a porta USB da impressora");
        }
        ZebraPrinterProber.probeOrThrow(serialConfig);
        connected = true;
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String getDeviceInfo() {
        String port = serialConfig != null ? serialConfig.getPortName() : "";
        return (model != null ? model.getDisplayLabel() : "Zebra")
                + " @ " + port
                + " · " + Math.round(labelWidthMm) + "×" + Math.round(labelHeightMm) + " mm";
    }

    @Override
    public void startContinuousReading(PeripheralDataListener listener) {
        // Impressora não gera leituras.
    }

    @Override
    public void stopContinuousReading() {
    }

    @Override
    public void readOnce(int timeoutMs, PeripheralDataListener listener) throws PeripheralException {
        throw new PeripheralException("A impressora Zebra não faz leitura de dados.");
    }

    @Override
    public boolean isReading() {
        return false;
    }

    @Override
    public void printZpl(String zpl) throws PeripheralException {
        if (!connected) {
            throw new PeripheralException("Impressora não conectada");
        }
        if (zpl == null || zpl.trim().isEmpty()) {
            throw new PeripheralException("Comando ZPL vazio");
        }
        String port = serialConfig.getPortName();
        byte[] payload = zpl.getBytes(StandardCharsets.US_ASCII);
        try {
            if (isRawLinuxLp(port)) {
                writeRawFile(port, payload);
            } else {
                writeSerial(port, payload);
            }
        } catch (PeripheralException e) {
            throw e;
        } catch (Exception e) {
            throw new PeripheralException("Falha ao enviar ZPL: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        }
    }

    @Override
    public float getLabelWidthMm() {
        return labelWidthMm;
    }

    @Override
    public float getLabelHeightMm() {
        return labelHeightMm;
    }

    @Override
    public void setLabelSizeMm(float widthMm, float heightMm) {
        this.labelWidthMm = Math.max(20f, widthMm);
        this.labelHeightMm = Math.max(20f, heightMm);
    }

    public SerialConnectionConfig getSerialConfig() {
        return serialConfig != null ? serialConfig.copy() : SerialConnectionConfig.printerDefault();
    }

    static boolean isRawLinuxLp(String port) {
        if (port == null) {
            return false;
        }
        String p = port.trim();
        return p.startsWith("/dev/usb/lp") || p.startsWith("/dev/lp");
    }

    private static void writeRawFile(String path, byte[] payload) throws IOException, PeripheralException {
        Path file = Paths.get(path);
        if (!Files.exists(file)) {
            throw new PeripheralException("Dispositivo não encontrado: " + path);
        }
        try (OutputStream out = new FileOutputStream(file.toFile())) {
            out.write(payload);
            out.flush();
        }
    }

    private void writeSerial(String portName, byte[] payload) throws PeripheralException, IOException {
        SerialPort port = SerialPort.getCommPort(portName);
        port.setComPortParameters(
                serialConfig.getBaudRate(),
                serialConfig.getDataBits(),
                serialConfig.getJSerialCommStopBits(),
                serialConfig.getParity() != null
                        ? serialConfig.getParity().getJSerialCommValue()
                        : com.fazecast.jSerialComm.SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 8000, 0);
        if (!port.openPort()) {
            throw new PeripheralException("Não foi possível abrir a impressora: " + portName);
        }
        try {
            try (OutputStream out = port.getOutputStream()) {
                out.write(payload);
                out.flush();
            }
        } finally {
            port.closePort();
        }
    }
}
