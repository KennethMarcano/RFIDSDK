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

/**
 * Atualização no dispositivo: verifica o remoto e, se houver mudanças,
 * aplica, recompila e fecha a app para o script reiniciar.
 */
public final class AppUpdater {

    private static final int EXIT_UP_TO_DATE = 2;

    private AppUpdater() {
    }

    public static Path projectRoot() {
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    public static Path updateScript() {
        return projectRoot().resolve("scripts").resolve("atualizar-e-reiniciar.sh");
    }

    public static void runUpdateAsync(Window owner) {
        Path script = updateScript();
        if (!Files.isRegularFile(script)) {
            JOptionPane.showMessageDialog(owner,
                    "Não foi possível atualizar.",
                    "Atualizar", JOptionPane.ERROR_MESSAGE);
            return;
        }

        WorkflowUiTheme.showBusy(owner, "Atualizando...");
        Thread t = new Thread(() -> {
            try {
                if (!hasRemoteChanges()) {
                    SwingUtilities.invokeLater(() -> {
                        WorkflowUiTheme.hideBusy(owner);
                        JOptionPane.showMessageDialog(owner,
                                "O aplicativo já está na última versão.",
                                "Atualizar", JOptionPane.INFORMATION_MESSAGE);
                    });
                    return;
                }

                SwingUtilities.invokeLater(() ->
                        WorkflowUiTheme.showBusy(owner, "O aplicativo será atualizado..."));

                int code = runUpdateScript(script);
                SwingUtilities.invokeLater(() -> {
                    WorkflowUiTheme.hideBusy(owner);
                    if (code == 0) {
                        System.exit(0);
                    } else if (code == EXIT_UP_TO_DATE) {
                        JOptionPane.showMessageDialog(owner,
                                "O aplicativo já está na última versão.",
                                "Atualizar", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(owner,
                                "Não foi possível atualizar.",
                                "Atualizar", JOptionPane.ERROR_MESSAGE);
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    WorkflowUiTheme.hideBusy(owner);
                    JOptionPane.showMessageDialog(owner,
                            "Não foi possível atualizar.",
                            "Atualizar", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "app-updater");
        t.setDaemon(true);
        t.start();
    }

    private static boolean hasRemoteChanges() throws Exception {
        Path root = projectRoot();
        runGit(root, 60, "fetch", "--prune", "origin");
        String branch = runGit(root, 15, "rev-parse", "--abbrev-ref", "HEAD").trim();
        if (branch.isEmpty() || "HEAD".equals(branch)) {
            return true;
        }
        String local = runGit(root, 15, "rev-parse", "HEAD").trim();
        String remote = runGit(root, 15, "rev-parse", "origin/" + branch).trim();
        return !local.equals(remote);
    }

    private static int runUpdateScript(Path script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", script.toAbsolutePath().toString());
        pb.directory(projectRoot().toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                // Não mostra git fetch / compile na UI.
            }
        }
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    private static String runGit(Path root, int timeoutSec, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(root.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
            }
        }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("git timeout");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git failed");
        }
        return out.toString();
    }
}
