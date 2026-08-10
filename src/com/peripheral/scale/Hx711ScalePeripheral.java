package com.peripheral.scale;

import com.peripheral.core.DeviceModelEntry;
import com.peripheral.core.PeripheralDataEvent;
import com.peripheral.core.PeripheralDataListener;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.core.SerialConnectionConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Balança própria via chip HX711 (GPIO BCM DT={@link Hx711GpioPins#DT_BCM},
 * SCK={@link Hx711GpioPins#SCK_BCM}).
 * <p>
 * Lê peso através de {@code scripts/hx711_reader.py} (lgpio ou RPi.GPIO).
 * Calibração: {@code -Dhx711.refUnit=<divisor>} e opcional {@code -Dhx711.offset=<raw>}.
 */
public class Hx711ScalePeripheral implements ReadablePeripheral {

    private final DeviceModelEntry model;
    private SerialConnectionConfig serialConfig;
    private Process readerProcess;
    private Thread stdoutThread;
    private Thread stderrThread;
    private PeripheralDataListener dataListener;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean continuousReading = new AtomicBoolean(false);
    private volatile String lastError;

    public Hx711ScalePeripheral(DeviceModelEntry model, SerialConnectionConfig serialConfig) {
        this.model = model;
        this.serialConfig = serialConfig != null
                ? serialConfig.copy()
                : SerialConnectionConfig.hx711GpioDefault();
        if (this.serialConfig.getPortName() == null || this.serialConfig.getPortName().trim().isEmpty()) {
            this.serialConfig.setPortName(Hx711GpioPins.LOGICAL_PORT);
        }
    }

    @Override
    public DeviceModelEntry getModel() {
        return model;
    }

    @Override
    public void connect(SerialConnectionConfig config) throws PeripheralException {
        disconnect();
        if (config != null) {
            serialConfig = config.copy();
        }
        if (serialConfig.getPortName() == null || serialConfig.getPortName().trim().isEmpty()) {
            serialConfig.setPortName(Hx711GpioPins.LOGICAL_PORT);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) {
            throw new PeripheralException("HX711 GPIO só está disponível em Linux (Raspberry Pi)");
        }
        Path script = resolveReaderScript();
        if (script == null || !Files.isRegularFile(script)) {
            throw new PeripheralException(
                    "Script HX711 não encontrado (scripts/hx711_reader.py). "
                            + "Execute a partir da raiz do projeto.");
        }
        List<String> cmd = buildCommand(script);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            readerProcess = pb.start();
            connected.set(true);
            lastError = null;
            startStderrDrain();
            startStdoutDrain();
            // Aguarda um instante: se o processo morrer já, falha a conexão
            Thread.sleep(250);
            if (!readerProcess.isAlive()) {
                int code = readerProcess.exitValue();
                connected.set(false);
                destroyProcessQuietly();
                String detail = lastError != null ? lastError : ("exit=" + code);
                throw new PeripheralException("Falha ao iniciar leitor HX711: " + detail);
            }
        } catch (PeripheralException e) {
            throw e;
        } catch (Exception e) {
            connected.set(false);
            destroyProcessQuietly();
            throw new PeripheralException("Erro ao conectar HX711: " + e.getMessage());
        }
    }

    @Override
    public void disconnect() {
        stopContinuousReading();
        connected.set(false);
        destroyProcessQuietly();
        dataListener = null;
    }

    @Override
    public boolean isConnected() {
        return connected.get() && readerProcess != null && readerProcess.isAlive();
    }

    @Override
    public String getDeviceInfo() {
        if (!isConnected()) {
            return "-";
        }
        return "Propio | " + Hx711GpioPins.describeWiring();
    }

    @Override
    public void startContinuousReading(PeripheralDataListener listener) throws PeripheralException {
        ensureConnected();
        dataListener = listener;
        continuousReading.set(true);
        startStdoutDrain();
        notifyReadingState(listener, true);
    }

    @Override
    public void stopContinuousReading() {
        if (!continuousReading.getAndSet(false)) {
            return;
        }
        notifyReadingState(dataListener, false);
    }

    @Override
    public void readOnce(int timeoutMs, PeripheralDataListener listener) throws PeripheralException {
        ensureConnected();
        if (continuousReading.get()) {
            throw new PeripheralException("Pare a leitura contínua antes da leitura manual");
        }
        notifyReadingState(listener, true);
        long deadline = System.currentTimeMillis() + Math.max(200, timeoutMs);
        Thread once = new Thread(() -> {
            try {
                while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted()) {
                    PeripheralDataListener previous = dataListener;
                    dataListener = listener;
                    Thread.sleep(50);
                    dataListener = previous;
                    // Uma amostra já terá sido despachada pelo drain se chegou linha W
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                notifyReadingState(listener, false);
            }
        }, "hx711-read-once");
        once.setDaemon(true);
        once.start();
    }

    @Override
    public boolean isReading() {
        return continuousReading.get();
    }

    private void startStdoutDrain() {
        if (stdoutThread != null && stdoutThread.isAlive()) {
            return;
        }
        if (readerProcess == null) {
            return;
        }
        stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(readerProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (connected.get() && !Thread.currentThread().isInterrupted()) {
                    line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    if (continuousReading.get()) {
                        dispatchLine(line, dataListener);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (continuousReading.getAndSet(false)) {
                    notifyReadingState(dataListener, false);
                }
            }
        }, "hx711-stdout");
        stdoutThread.setDaemon(true);
        stdoutThread.start();
    }

    private void startStderrDrain() {
        if (stderrThread != null && stderrThread.isAlive()) {
            return;
        }
        stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(readerProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("ERR")) {
                        lastError = line.substring(3).trim();
                    }
                }
            } catch (Exception ignored) {
            }
        }, "hx711-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    private void dispatchLine(String line, PeripheralDataListener listener) {
        if (listener == null || line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("W ")) {
            return;
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length < 3) {
            return;
        }
        try {
            double kg = Double.parseDouble(parts[1]);
            boolean stable = "1".equals(parts[2]);
            String raw = trimmed;
            PeripheralDataEvent event = PeripheralDataEvent.builder(model)
                    .rawPayload(raw)
                    .weight(String.format(Locale.US, "%.3f", kg))
                    .unit("kg")
                    .stable(stable)
                    .displayText(ScaleWeightFormat.formatGramsWithUnit(kg)
                            + (stable ? " (estável)" : ""))
                    .build();
            listener.onData(event);
        } catch (NumberFormatException ignored) {
        }
    }

    private List<String> buildCommand(Path script) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolvePython());
        cmd.add(script.toAbsolutePath().toString());
        cmd.add("--dout");
        cmd.add(String.valueOf(Hx711GpioPins.DT_BCM));
        cmd.add("--sck");
        cmd.add(String.valueOf(Hx711GpioPins.SCK_BCM));
        String refUnit = System.getProperty("hx711.refUnit", "1");
        cmd.add("--ref-unit");
        cmd.add(refUnit);
        String offset = System.getProperty("hx711.offset");
        if (offset != null && !offset.trim().isEmpty()) {
            cmd.add("--offset");
            cmd.add(offset.trim());
        }
        return cmd;
    }

    private static String resolvePython() {
        String override = System.getProperty("hx711.python");
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        return "python3";
    }

    static Path resolveReaderScript() {
        String override = System.getProperty("hx711.script");
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override.trim());
        }
        String[] candidates = {
                "scripts/hx711_reader.py",
                "../scripts/hx711_reader.py",
                "RFIDSDK/scripts/hx711_reader.py"
        };
        for (String candidate : candidates) {
            Path path = Paths.get(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        // user.dir
        Path fromUserDir = Paths.get(System.getProperty("user.dir", "."), "scripts", "hx711_reader.py");
        if (Files.isRegularFile(fromUserDir)) {
            return fromUserDir.toAbsolutePath().normalize();
        }
        return null;
    }

    private void destroyProcessQuietly() {
        connected.set(false);
        if (stdoutThread != null && stdoutThread.isAlive()) {
            stdoutThread.interrupt();
            stdoutThread = null;
        }
        if (stderrThread != null && stderrThread.isAlive()) {
            stderrThread.interrupt();
            stderrThread = null;
        }
        if (readerProcess != null) {
            readerProcess.destroy();
            try {
                if (!readerProcess.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    readerProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                readerProcess.destroyForcibly();
            }
            readerProcess = null;
        }
    }

    private void notifyReadingState(PeripheralDataListener listener, boolean reading) {
        if (listener != null) {
            listener.onReadingStateChanged(reading);
        }
    }

    private void ensureConnected() throws PeripheralException {
        if (!isConnected()) {
            throw new PeripheralException("Balança HX711 não conectada");
        }
    }
}
