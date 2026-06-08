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
        this.deviceName = deviceName;
    }

    @Override
    public void open() throws ReaderException {
        serialPort = SerialPort.getCommPort(deviceName);
        if (!serialPort.openPort()) {
            throw new ReaderCommException("Couldn't open device: " + deviceName);
        }
        applyBaudRate();
        try {
            inputStream = serialPort.getInputStream();
            outputStream = serialPort.getOutputStream();
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
        long start = System.currentTimeMillis();
        try {
            while (totalRead < length) {
                int available = inputStream.available();
                if (available > 0) {
                    int toRead = Math.min(available, length - totalRead);
                    int read = inputStream.read(messageSpace, offset + totalRead, toRead);
                    if (read > 0) {
                        totalRead += read;
                    }
                } else {
                    if (System.currentTimeMillis() - start >= timeoutMillis) {
                        throw new ReaderCommException("Serial error from receiveBytes: timeout");
                    }
                    Thread.sleep(10);
                }
            }
        } catch (ReaderException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReaderCommException("Serial error");
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
