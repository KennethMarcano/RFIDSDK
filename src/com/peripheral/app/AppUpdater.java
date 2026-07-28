package com.peripheral.app;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Atualização simples no dispositivo: git pull/reset + build + reinício via script.
 */
public final class AppUpdater {

    private AppUpdater() {
    }

    public static Path projectRoot() {
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    public static Path updateScript() {
        return projectRoot().resolve("scripts").resolve("atualizar-e-reiniciar.sh");
    }

    /**
     * Roda o script em background. Em sucesso agenda {@code System.exit(0)}
     * (o script já programou o {@code ./iniciar.sh}).
     */
    public static void runUpdateAsync(Window owner, Consumer<String> log) {
        Path script = updateScript();
        if (!Files.isRegularFile(script)) {
            JOptionPane.showMessageDialog(owner,
                    "Script não encontrado:\n" + script
                            + "\n\nRode a app a partir da pasta do projeto (./iniciar.sh).",
                    "Atualizar", JOptionPane.ERROR_MESSAGE);
            return;
        }

        WorkflowUiTheme.showBusy(owner, "Atualizando aplicativo...");
        Thread t = new Thread(() -> {
            StringBuilder output = new StringBuilder();
            int code = -1;
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "bash", script.toAbsolutePath().toString());
                pb.directory(projectRoot().toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                        if (log != null) {
                            log.accept(line);
                        }
                        final String busyText = truncateBusy(line);
                        SwingUtilities.invokeLater(() ->
                                WorkflowUiTheme.showBusy(owner, busyText));
                    }
                }
                if (!p.waitFor(10, TimeUnit.MINUTES)) {
                    p.destroyForcibly();
                    throw new IllegalStateException("Timeout ao atualizar (mais de 10 min).");
                }
                code = p.exitValue();
            } catch (Exception e) {
                output.append("ERRO: ").append(e.getMessage()).append('\n');
                if (log != null) {
                    log.accept("Atualização falhou: " + e.getMessage());
                }
            }

            final int exitCode = code;
            final String text = output.toString().trim();
            SwingUtilities.invokeLater(() -> {
                WorkflowUiTheme.hideBusy(owner);
                if (exitCode == 0) {
                    JOptionPane.showMessageDialog(owner,
                            "Atualização concluída.\nA aplicação vai reiniciar agora.",
                            "Atualizar", JOptionPane.INFORMATION_MESSAGE);
                    // O script já agendou ./iniciar.sh — encerra este processo.
                    System.exit(0);
                } else {
                    String msg = text.isEmpty()
                            ? "Falha na atualização (código " + exitCode + ")."
                            : text;
                    if (msg.length() > 1200) {
                        msg = msg.substring(msg.length() - 1200);
                    }
                    JOptionPane.showMessageDialog(owner, msg, "Atualizar",
                            JOptionPane.ERROR_MESSAGE);
                }
            });
        }, "app-updater");
        t.setDaemon(true);
        t.start();
    }

    private static String truncateBusy(String line) {
        if (line == null || line.isEmpty()) {
            return "Atualizando aplicativo...";
        }
        String s = line.trim();
        if (s.length() > 60) {
            s = s.substring(0, 57) + "...";
        }
        return s;
    }
}
