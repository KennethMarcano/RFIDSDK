package com.rfid.app;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Ponto de entrada da aplicação RFID multi-SDK.
 * <p>
 * Para Mercury no Windows, é necessário JNI em SDKMERCURY. Este main configura
 * {@code java.library.path} automaticamente quando a pasta SDKMERCURY existe no projeto.
 */
public class RfidApplication {

    public static void main(String[] args) {
        configureMercuryNativeLibrary();
        javax.swing.SwingUtilities.invokeLater(RfidMainFrame::new);
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
