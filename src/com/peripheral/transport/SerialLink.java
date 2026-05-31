package com.peripheral.transport;

import com.fazecast.jSerialComm.SerialPort;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.SerialConnectionConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SerialLink {

    public interface LineListener {
        void onLine(String line);
    }

    private SerialPort port;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private LineListener lineListener;
    private final BlockingQueue<String> lineQueue = new LinkedBlockingQueue<>();
    private final StringBuilder lineBuffer = new StringBuilder();

    public void open(SerialConnectionConfig config) throws PeripheralException {
        close();
        if (config == null || config.getPortName() == null || config.getPortName().trim().isEmpty()) {
            throw new PeripheralException("Porta serial não informada");
        }
        port = SerialPort.getCommPort(config.getPortName().trim());
        port.setComPortParameters(
                config.getBaudRate(),
                config.getDataBits(),
                config.getJSerialCommStopBits(),
                config.getParity().getJSerialCommValue()
        );
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);
        if (!port.openPort()) {
            throw new PeripheralException("Não foi possível abrir a porta " + config.getPortName());
        }
        running.set(true);
        readerThread = new Thread(this::readLoop, "SerialLink-" + config.getPortName());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public void close() {
        running.set(false);
        if (readerThread != null) {
            try {
                readerThread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readerThread = null;
        }
        if (port != null) {
            try {
                if (port.isOpen()) {
                    port.closePort();
                }
            } catch (Throwable ignored) {
            }
            port = null;
        }
        lineQueue.clear();
        synchronized (lineBuffer) {
            lineBuffer.setLength(0);
        }
    }

    public boolean isOpen() {
        return port != null && port.isOpen();
    }

    public void setLineListener(LineListener listener) {
        this.lineListener = listener;
    }

    public String pollLine(long timeoutMs) throws InterruptedException {
        return lineQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void readLoop() {
        byte[] buf = new byte[256];
        try {
            InputStream in = port.getInputStream();
            while (running.get() && port != null && port.isOpen()) {
                int n = in.read(buf);
                if (n <= 0) {
                    continue;
                }
                appendBytes(buf, n);
            }
        } catch (IOException e) {
            if (running.get()) {
                dispatchLine("[erro serial: " + e.getMessage() + "]");
            }
        }
    }

    private void appendBytes(byte[] buf, int n) {
        synchronized (lineBuffer) {
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xFF);
                if (c == '\n' || c == '\r') {
                    flushLine();
                } else {
                    lineBuffer.append(c);
                }
            }
        }
    }

    private void flushLine() {
        String line;
        synchronized (lineBuffer) {
            line = lineBuffer.toString().trim();
            lineBuffer.setLength(0);
        }
        if (line.isEmpty()) {
            return;
        }
        dispatchLine(line);
    }

    private void dispatchLine(String line) {
        lineQueue.offer(line);
        LineListener l = lineListener;
        if (l != null) {
            l.onLine(line);
        }
    }

    public void writeBytes(byte[] data) throws PeripheralException {
        if (!isOpen()) {
            throw new PeripheralException("Porta serial não está aberta");
        }
        try {
            port.getOutputStream().write(data);
            port.getOutputStream().flush();
        } catch (IOException e) {
            throw new PeripheralException("Erro ao escrever na serial: " + e.getMessage(), e);
        }
    }

    public void writeLine(String text) throws PeripheralException {
        writeBytes((text + "\r\n").getBytes(StandardCharsets.US_ASCII));
    }
}
