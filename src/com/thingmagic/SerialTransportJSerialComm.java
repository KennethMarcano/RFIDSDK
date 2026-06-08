package com.thingmagic;

import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

/**
 * Transporte serial Mercury via jSerialComm (Java puro).
 * Usado no Linux para evitar falha ao carregar {@link SerialTransportNative}.
 */
public class SerialTransportJSerialComm implements SerialTransport {

    private final String deviceName;
    private SerialPort serialPort;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean opened;
    private int baudRate = 115200;

    public SerialTransportJSerialComm(String deviceName) {
        this.deviceName = resolveCommPortName(deviceName);
    }

    @Override
    public void open() throws ReaderException {
        serialPort = SerialPort.getCommPort(deviceName);
        if (!serialPort.openPort()) {
            throw new ReaderCommException("Couldn't open device: " + deviceName);
        }
        serialPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        applyBaudRate();
        applyWriteTimeout(1000);
        tryEnableUsbSignals();
        try {
            inputStream = serialPort.getInputStream();
            outputStream = serialPort.getOutputStream();
            purgeInputQuietly();
        } catch (Exception e) {
            serialPort.closePort();
            serialPort = null;
            throw new ReaderCommException("Couldn't open device: " + e.getMessage());
        }
        opened = true;
    }

    @Override
    public void sendBytes(int length, byte[] message, int offset, int timeoutMs) throws ReaderException {
        if (!opened) {
            return;
        }
        try {
            applyWriteTimeout(Math.max(100, timeoutMs));
            outputStream.write(message, offset, length);
            outputStream.flush();
        } catch (IOException e) {
            throw new ReaderCommException("Serial error");
        }
    }

    @Override
    public byte[] receiveBytes(int length, byte[] messageSpace, int offset, int timeoutMillis)
            throws ReaderException {
        if (!opened) {
            return messageSpace;
        }
        if (messageSpace == null) {
            messageSpace = new byte[length + offset];
        }
        int totalRead = 0;
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMillis);
        try {
            while (totalRead < length) {
                int remainingMs = (int) Math.max(1, deadline - System.currentTimeMillis());
                applyReadTimeout(remainingMs);
                int read = inputStream.read(messageSpace, offset + totalRead, length - totalRead);
                if (read < 0) {
                    throw new ReaderCommException("Serial error from receiveBytes: EOF");
                }
                if (read == 0) {
                    if (System.currentTimeMillis() >= deadline) {
                        throw new ReaderCommException("Serial error from receiveBytes: timeout");
                    }
                    continue;
                }
                totalRead += read;
            }
        } catch (ReaderException e) {
            throw e;
        } catch (IOException e) {
            throw new ReaderCommException("Serial error from receiveBytes");
        }
        return messageSpace;
    }

    @Override
    public int getBaudRate() {
        return baudRate;
    }

    @Override
    public void setBaudRate(int rate) throws ReaderException {
        baudRate = rate;
        if (opened && serialPort != null) {
            applyBaudRate();
        }
    }

    @Override
    public void flush() throws ReaderException {
        if (!opened || outputStream == null) {
            return;
        }
        try {
            outputStream.flush();
        } catch (IOException e) {
            throw new ReaderCommException("Serial error");
        }
    }

    @Override
    public void shutdown() throws ReaderException {
        if (!opened) {
            return;
        }
        opened = false;
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException ignored) {
            }
            outputStream = null;
        }
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
            inputStream = null;
        }
        if (serialPort != null) {
            serialPort.closePort();
            serialPort = null;
        }
    }

    private void applyBaudRate() {
        serialPort.setComPortParameters(
                baudRate,
                8,
                SerialPort.ONE_STOP_BIT,
                SerialPort.NO_PARITY);
    }

    private void applyReadTimeout(int timeoutMs) {
        serialPort.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                Math.max(1, timeoutMs),
                0);
    }

    private void applyWriteTimeout(int timeoutMs) {
        serialPort.setComPortTimeouts(
                SerialPort.TIMEOUT_WRITE_BLOCKING,
                Math.max(1, timeoutMs),
                Math.max(1, timeoutMs));
    }

    private void tryEnableUsbSignals() {
        try {
            serialPort.setDTR();
            serialPort.setRTS();
        } catch (Throwable ignored) {
        }
    }

    private void purgeInputQuietly() {
        if (inputStream == null) {
            return;
        }
        try {
            applyReadTimeout(50);
            byte[] scratch = new byte[256];
            while (true) {
                int read = inputStream.read(scratch);
                if (read <= 0) {
                    break;
                }
            }
        } catch (IOException ignored) {
        }
    }

    static String resolveCommPortName(String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return candidate;
        }
        String port = candidate.trim();
        SerialPort direct = SerialPort.getCommPort(port);
        if (direct != null && !direct.getSystemPortName().isEmpty()) {
            return direct.getSystemPortName();
        }
        String alt = port.startsWith("/dev/") ? port.substring(5) : "/dev/" + port;
        SerialPort altPort = SerialPort.getCommPort(alt);
        if (altPort != null && !altPort.getSystemPortName().isEmpty()) {
            return altPort.getSystemPortName();
        }
        for (SerialPort available : SerialPort.getCommPorts()) {
            String system = available.getSystemPortName();
            if (port.equals(system) || alt.equals(system)) {
                return system;
            }
        }
        return port;
    }

    public static class Factory implements ReaderFactory {

        @Override
        public SerialReader createReader(String uriString) throws ReaderException {
            String readerUri;
            try {
                readerUri = new URI(uriString).getPath();
            } catch (Exception ex) {
                throw new ReaderException("URI inválida: " + uriString);
            }
            return new SerialReader(readerUri, new SerialTransportJSerialComm(readerUri));
        }
    }
}
