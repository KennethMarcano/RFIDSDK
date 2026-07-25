package com.peripheral.core;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executa I/O de periféricos com timeout para evitar travar a UI ou o fluxo
 * quando o dispositivo (ex.: ThingMagic) para de responder no cabo USB/serial.
 */
public final class PeripheralSafeIo {

    public static final long DEFAULT_TIMEOUT_MS = 2500L;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "peripheral-safe-io-" + THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private PeripheralSafeIo() {
    }

    public static boolean runWithTimeout(Runnable action, long timeoutMs) {
        if (action == null) {
            return true;
        }
        long ms = Math.max(200L, timeoutMs);
        Future<?> future = EXECUTOR.submit(() -> {
            try {
                action.run();
            } catch (Throwable ignored) {
            }
        });
        try {
            future.get(ms, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            future.cancel(true);
            return false;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    public static void stopReading(ReadablePeripheral device) {
        stopReading(device, DEFAULT_TIMEOUT_MS);
    }

    public static void stopReading(ReadablePeripheral device, long timeoutMs) {
        if (device == null) {
            return;
        }
        runWithTimeout(device::stopContinuousReading, timeoutMs);
    }

    public static void disconnect(ReadablePeripheral device) {
        disconnect(device, DEFAULT_TIMEOUT_MS);
    }

    public static void disconnect(ReadablePeripheral device, long timeoutMs) {
        if (device == null) {
            return;
        }
        runWithTimeout(device::disconnect, timeoutMs);
    }

    public static boolean looksLikeConnectionLoss(Throwable error) {
        if (error == null) {
            return false;
        }
        return looksLikeConnectionLoss(error.getMessage());
    }

    public static boolean looksLikeConnectionLoss(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String m = message.toLowerCase();
        return m.contains("timeout")
                || m.contains("timed out")
                || m.contains("connection lost")
                || (m.contains("conex") && m.contains("perd"))
                || m.contains("broken pipe")
                || m.contains("broken serial")
                || m.contains("communication error")
                || m.contains("not connected")
                || m.contains("não conectado")
                || m.contains("nao conectado")
                || m.contains("device was reset")
                || m.contains("i/o error")
                || m.contains("ioexception")
                || m.contains("native serial")
                || m.contains("port is closed")
                || m.contains("no such port");
    }
}
