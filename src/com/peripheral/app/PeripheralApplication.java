package com.peripheral.app;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PeripheralApplication {

    public static void main(String[] args) {
        configureMercuryNativeLibrary();
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        WorkflowUiTheme.install();
        javax.swing.SwingUtilities.invokeLater(MainFrame::new);
    }

    private static void configureMercuryNativeLibrary() {
        String existing = System.getProperty("java.library.path", "");
        Path sdkMercury = findSdkMercuryDir();
        if (sdkMercury == null) {
            return;
        }
        String path = sdkMercury.toAbsolutePath().toString();
        if (!existing.contains(path)) {
            String updated = existing.isEmpty() ? path : path + File.pathSeparator + existing;
            System.setProperty("java.library.path", updated);
        }
    }

    private static Path findSdkMercuryDir() {
        Path cwd = Paths.get(System.getProperty("user.dir", "."));
        Path candidate = cwd.resolve("SDKMERCURY");
        if (candidate.toFile().isDirectory()) {
            return candidate;
        }
        Path parent = cwd.getParent();
        if (parent != null) {
            candidate = parent.resolve("SDKMERCURY");
            if (candidate.toFile().isDirectory()) {
                return candidate;
            }
        }
        return null;
    }
}
