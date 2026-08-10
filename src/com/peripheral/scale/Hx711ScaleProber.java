package com.peripheral.scale;

import com.peripheral.core.PortProbeResult;
import com.peripheral.core.SerialConnectionConfig;
import com.peripheral.core.SerialPortProber;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Verifica se o HX711 responde nos pinos BCM DT/SCK configurados.
 */
public class Hx711ScaleProber implements SerialPortProber {

    @Override
    public PortProbeResult probe(SerialConnectionConfig config, long timeoutMs) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) {
            return PortProbeResult.openFailed(
                    "HX711 GPIO só funciona em Linux (Raspberry Pi).",
                    Hx711GpioPins.describeWiring());
        }
        Path script = Hx711ScalePeripheral.resolveReaderScript();
        if (script == null || !Files.isRegularFile(script)) {
            return PortProbeResult.openFailed(
                    "Script scripts/hx711_reader.py não encontrado.",
                    "Execute a aplicação a partir da raiz do repositório RFIDSDK.");
        }
        List<String> cmd = new ArrayList<>();
        String python = System.getProperty("hx711.python", "python3");
        cmd.add(python);
        cmd.add(script.toAbsolutePath().toString());
        cmd.add("--dout");
        cmd.add(String.valueOf(Hx711GpioPins.DT_BCM));
        cmd.add("--sck");
        cmd.add(String.valueOf(Hx711GpioPins.SCK_BCM));
        cmd.add("--ref-unit");
        cmd.add(System.getProperty("hx711.refUnit", Hx711GpioPins.DEFAULT_REF_UNIT));
        // Probe rápido: offset fixo evita tara longa; janela curta mas consistente
        cmd.add("--offset");
        cmd.add("0");
        cmd.add("--samples");
        cmd.add("8");
        cmd.add("--interval-ms");
        cmd.add("40");
        cmd.add("--ema");
        cmd.add("0.35");
        cmd.add("--deadband-raw");
        cmd.add("0");
        cmd.add("--quantize-g");
        cmd.add("0");

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            // Garante que o script ache libs/cwd mesmo se a JVM tiver outro user.dir
            Path scriptDir = script.toAbsolutePath().getParent();
            if (scriptDir != null && scriptDir.getParent() != null) {
                pb.directory(scriptDir.getParent().toFile());
            }
            process = pb.start();
            long waitMs = Math.max(8000, timeoutMs);
            long deadline = System.currentTimeMillis() + waitMs;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String last = null;
                while (System.currentTimeMillis() < deadline) {
                    if (reader.ready()) {
                        String line = reader.readLine();
                        if (line == null) {
                            break;
                        }
                        last = line.trim();
                        if (last.startsWith("W ")) {
                            return PortProbeResult.match(
                                    "HX711 detectado (" + Hx711GpioPins.describeWiring() + ").",
                                    "Amostra: " + last);
                        }
                        if (last.startsWith("ERR")) {
                            return PortProbeResult.openFailed(
                                    "Falha no HX711: " + last.substring(3).trim(),
                                    "Confira fiação DT=BCM" + Hx711GpioPins.DT_BCM
                                            + " / SCK=BCM" + Hx711GpioPins.SCK_BCM
                                            + " e se lgpio ou RPi.GPIO está instalado.");
                        }
                    } else {
                        Thread.sleep(40);
                    }
                    if (!process.isAlive()) {
                        break;
                    }
                }
                if (last != null && last.startsWith("ERR")) {
                    return PortProbeResult.openFailed(
                            "Falha no HX711: " + last.substring(3).trim(),
                            Hx711GpioPins.describeWiring());
                }
                return PortProbeResult.noResponse(
                        "Nenhuma leitura do HX711 em " + waitMs + " ms.",
                        "Verifique alimentação do módulo, DOUT em BCM"
                                + Hx711GpioPins.DT_BCM + " e SCK em BCM"
                                + Hx711GpioPins.SCK_BCM
                                + ".\nSe o comando python3 scripts/hx711_reader.py funciona, "
                                + "confirme que a app foi iniciada na raiz do projeto.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PortProbeResult.noResponse("Teste interrompido.", "");
        } catch (Exception e) {
            return PortProbeResult.openFailed(
                    "Não foi possível iniciar o leitor HX711: " + e.getMessage(),
                    "python3 + scripts/hx711_reader.py | " + Hx711GpioPins.describeWiring());
        } finally {
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }
}
