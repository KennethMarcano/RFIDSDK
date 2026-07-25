package com.peripheral.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * No Linux (Raspberry Pi), módulos USB-serial ligados antes do boot às vezes não
 * enumeram até um re-plug. Este utilitário faz o equivalente por software:
 * desautoriza e reautoriza apenas adapters USB-UART conhecidos (não mexe em
 * touch, teclado, mouse ou storage).
 */
public final class LinuxUsbSerialReset {

    /** VIDs típicos de conversor USB-serial (RFID / balança). */
    private static final int[] SERIAL_VIDS = {
            0x0403, // FTDI
            0x10C4, // Silicon Labs CP210x
            0x1A86, // QinHeng CH340/CH341
            0x067B, // Prolific
            0x04D8, // Microchip
            0x2341, // Arduino
            0x2A03, // Arduino.org
            0x26AC, // ThingMagic / OEM
            0x0FE6,
            0x0557,
            0x9710,
            0x06CD,
            0x04B4,
            0x1D50,
            0x1B4F
    };

    private static final Path USB_DEVICES = Paths.get("/sys/bus/usb/devices");
    private static final long TOGGLE_PAUSE_MS = 700L;
    private static final long SETTLE_MS = 1500L;

    private LinuxUsbSerialReset() {
    }

    public static boolean isSupported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("linux") && Files.isDirectory(USB_DEVICES);
    }

    /**
     * Reset seletivo + espera de reenumeração. Seguro chamar no boot;
     * se não houver permissão ou não for Linux, retorna resultado sem lançar.
     * Ordem: sysfs direto → script bash (com sudo -n se disponível).
     */
    public static Result resetPreferredSerialAdapters() {
        if (!isSupported()) {
            return Result.skipped("USB reset só é suportado no Linux (/sys/bus/usb).");
        }
        Result sysfs = resetViaSysfs();
        if (sysfs.getResetCount() > 0) {
            return sysfs;
        }
        Result script = resetViaShellScript();
        if (script.isAttempted() && script.getResetCount() > 0) {
            return script;
        }
        if (sysfs.hasErrors() || !sysfs.getSummary().isEmpty()) {
            List<String> merged = new ArrayList<>(sysfs.getErrors());
            if (script.hasErrors()) {
                merged.addAll(script.getErrors());
            } else if (script.getSummary() != null && !script.getSummary().isEmpty()
                    && script.isAttempted()) {
                merged.add(script.getSummary());
            }
            String summary = sysfs.getResetCount() == 0 && !merged.isEmpty()
                    ? "Reset USB sem permissão. Use ./iniciar.sh (recomendado) ou configure sudo/udev."
                    : sysfs.getSummary();
            return new Result(true, 0, sysfs.getResetDevices(), merged, summary);
        }
        return script.isAttempted() ? script : sysfs;
    }

    private static Result resetViaSysfs() {
        List<String> resetDevices = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            Set<Path> targets = findSerialUsbDeviceDirs();
            if (targets.isEmpty()) {
                return Result.ok(0, resetDevices,
                        "Nenhum conversor USB-serial conhecido encontrado no sysfs.");
            }
            for (Path deviceDir : targets) {
                String name = deviceDir.getFileName().toString();
                try {
                    if (toggleAuthorized(deviceDir)) {
                        resetDevices.add(name + " (" + describeDevice(deviceDir) + ")");
                    } else {
                        errors.add(name + ": sem permissão para escrever authorized.");
                    }
                } catch (Exception e) {
                    errors.add(name + ": " + e.getMessage());
                }
            }
            if (!resetDevices.isEmpty()) {
                sleepQuietly(SETTLE_MS);
            }
            return new Result(true, resetDevices.size(), resetDevices, errors,
                    resetDevices.isEmpty()
                            ? "Reset USB via sysfs não aplicado."
                            : "Reset USB aplicado em " + resetDevices.size() + " dispositivo(s).");
        } catch (Exception e) {
            return Result.failed("Falha no reset USB: " + e.getMessage());
        }
    }

    private static Result resetViaShellScript() {
        Path script = findResetScript();
        if (script == null) {
            return Result.skipped("Script usb-serial-reset.sh não encontrado.");
        }
        try {
            List<String> cmd = new ArrayList<>();
            if (canSudoNonInteractive()) {
                cmd.add("sudo");
                cmd.add("-n");
            }
            cmd.add("bash");
            cmd.add(script.toAbsolutePath().toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Result.failed("Timeout ao executar usb-serial-reset.sh");
            }
            int code = process.exitValue();
            int count = countResetInOutput(output);
            List<String> devices = new ArrayList<>();
            if (count > 0) {
                devices.add("via script (" + count + ")");
                sleepQuietly(SETTLE_MS);
            }
            List<String> errors = new ArrayList<>();
            if (code != 0) {
                errors.add("exit=" + code);
            }
            if (output.toLowerCase(Locale.ROOT).contains("sem permissão")
                    || output.toLowerCase(Locale.ROOT).contains("permission")) {
                errors.add(output);
            }
            String summary = output.isEmpty()
                    ? (count > 0 ? "Reset USB via script OK." : "Script USB sem dispositivos.")
                    : output.replace('\n', ' ');
            return new Result(true, count, devices, errors, summary);
        } catch (Exception e) {
            return Result.failed("Falha ao executar script USB: " + e.getMessage());
        }
    }

    private static Path findResetScript() {
        Path cwd = Paths.get(System.getProperty("user.dir", "."));
        Path[] candidates = {
                cwd.resolve("scripts/usb-serial-reset.sh"),
                cwd.resolve("usb-serial-reset.sh")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean canSudoNonInteractive() {
        try {
            Process p = new ProcessBuilder("sudo", "-n", "true").start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int countResetInOutput(String output) {
        if (output == null || output.isEmpty()) {
            return 0;
        }
        // "usb-serial-reset: N resetado(s)"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s+resetado")
                .matcher(output);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        int lines = 0;
        for (String line : output.split("\\R")) {
            if (line.startsWith("Reset USB-serial:")) {
                lines++;
            }
        }
        return lines;
    }

    private static Set<Path> findSerialUsbDeviceDirs() throws IOException {
        Set<Path> result = new LinkedHashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(USB_DEVICES)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                // Ignora interfaces (ex.: 1-1.2:1.0); só nós de dispositivo (1-1.2).
                if (name.contains(":")) {
                    continue;
                }
                Path vendorFile = entry.resolve("idVendor");
                Path authFile = entry.resolve("authorized");
                if (!Files.isRegularFile(vendorFile) || !Files.isRegularFile(authFile)) {
                    continue;
                }
                int vid = parseHexId(readTrimmed(vendorFile));
                if (isSerialVid(vid)) {
                    result.add(entry);
                }
            }
        }
        return result;
    }

    private static boolean toggleAuthorized(Path deviceDir) throws IOException, InterruptedException {
        Path authFile = deviceDir.resolve("authorized");
        if (!Files.isWritable(authFile)) {
            // Tenta via processo com permissão elevada só se o script wrapper já tiver sudo.
            return false;
        }
        Files.write(authFile, "0\n".getBytes(StandardCharsets.US_ASCII));
        sleepQuietly(TOGGLE_PAUSE_MS);
        Files.write(authFile, "1\n".getBytes(StandardCharsets.US_ASCII));
        return true;
    }

    private static String describeDevice(Path deviceDir) {
        try {
            String vid = readTrimmed(deviceDir.resolve("idVendor"));
            String pid = readTrimmed(deviceDir.resolve("idProduct"));
            Path product = deviceDir.resolve("product");
            String productName = Files.isRegularFile(product) ? readTrimmed(product) : "";
            if (!productName.isEmpty()) {
                return vid + ":" + pid + " " + productName;
            }
            return vid + ":" + pid;
        } catch (IOException e) {
            return "?";
        }
    }

    private static boolean isSerialVid(int vendorId) {
        if (vendorId <= 0) {
            return false;
        }
        for (int vid : SERIAL_VIDS) {
            if (vid == vendorId) {
                return true;
            }
        }
        return false;
    }

    private static int parseHexId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim(), 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readTrimmed(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    private static void sleepQuietly(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class Result {
        private final boolean attempted;
        private final int resetCount;
        private final List<String> resetDevices;
        private final List<String> errors;
        private final String summary;

        private Result(boolean attempted, int resetCount, List<String> resetDevices,
                       List<String> errors, String summary) {
            this.attempted = attempted;
            this.resetCount = resetCount;
            this.resetDevices = resetDevices != null ? resetDevices : new ArrayList<>();
            this.errors = errors != null ? errors : new ArrayList<>();
            this.summary = summary != null ? summary : "";
        }

        public static Result skipped(String summary) {
            return new Result(false, 0, new ArrayList<>(), new ArrayList<>(), summary);
        }

        public static Result failed(String summary) {
            return new Result(true, 0, new ArrayList<>(), new ArrayList<>(), summary);
        }

        public static Result ok(int count, List<String> devices, String summary) {
            return new Result(true, count, devices, new ArrayList<>(), summary);
        }

        public boolean isAttempted() {
            return attempted;
        }

        public int getResetCount() {
            return resetCount;
        }

        public List<String> getResetDevices() {
            return resetDevices;
        }

        public List<String> getErrors() {
            return errors;
        }

        public String getSummary() {
            return summary;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(summary);
            if (!resetDevices.isEmpty()) {
                sb.append(" [").append(String.join(", ", resetDevices)).append("]");
            }
            if (!errors.isEmpty()) {
                sb.append(" avisos=").append(errors.size());
            }
            return sb.toString();
        }
    }
}
