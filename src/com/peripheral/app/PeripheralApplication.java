package com.peripheral.app;

import com.peripheral.camera.CameraMicroserviceLifecycle;
import com.rfid.util.MercuryTransportBootstrap;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PeripheralApplication {

    public static void main(String[] args) {
        MercuryTransportBootstrap.installIfLinux();
        configureMercuryNativeLibrary();
        // USB-serial reset desativado: afetava o touch USB da tela 7".
        startCameraServiceAsync();
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        WorkflowUiTheme.install();
        javax.swing.SwingUtilities.invokeLater(MainFrame::new);
    }

    private static void startCameraServiceAsync() {
        Thread starter = new Thread(() -> {
            CameraMicroserviceLifecycle lifecycle = CameraMicroserviceLifecycle.getInstance();
            boolean ok = lifecycle.start();
            if (ok) {
                boolean modelReady = lifecycle.getClient().checkReady();
                System.out.println(modelReady
                        ? "[Câmera] Serviço + modelo IMX500 prontos (fallback IA)."
                        : "[Câmera] Serviço online — modelo IA ainda não confirmado.");
            } else {
                String err = lifecycle.getLastStartupError();
                System.out.println("[Câmera] Falha ao iniciar serviço"
                        + (err != null && !err.isEmpty() ? ": " + err : "."));
            }
        }, "camera-service-starter");
        starter.setDaemon(true);
        starter.start();
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
