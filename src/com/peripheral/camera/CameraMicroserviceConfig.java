package com.peripheral.camera;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class CameraMicroserviceConfig {

    public static final int DEFAULT_PORT = 8765;
    public static final int HEALTH_TIMEOUT_MS = 3000;
    public static final int CAPTURE_TIMEOUT_MS = 30000;
    public static final int ANALYZE_TIMEOUT_MS = 60000;
    public static final int STARTUP_WAIT_MS = 15000;

    private final String host;
    private final int port;
    private final Path serviceDirectory;
    private final String pythonCommand;
    private final boolean stubMode;

    public CameraMicroserviceConfig(String host, int port, Path serviceDirectory,
                                    String pythonCommand, boolean stubMode) {
        this.host = host != null && !host.isEmpty() ? host : "127.0.0.1";
        this.port = port > 0 ? port : DEFAULT_PORT;
        this.serviceDirectory = serviceDirectory;
        this.pythonCommand = pythonCommand != null && !pythonCommand.isEmpty()
                ? pythonCommand : "python3";
        this.stubMode = stubMode;
    }

    public static CameraMicroserviceConfig fromSystemProperties() {
        String host = System.getProperty("rfidsdk.camera.host", "127.0.0.1");
        int port = parseInt(System.getProperty("rfidsdk.camera.port"), DEFAULT_PORT);
        String python = System.getProperty("rfidsdk.camera.python", detectPython());
        boolean stub = !"false".equalsIgnoreCase(System.getProperty("rfidsdk.camera.stub", "true"));
        Path dir = findServiceDirectory();
        return new CameraMicroserviceConfig(host, port, dir, python, stub);
    }

    private static String detectPython() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "python" : "python3";
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static Path findServiceDirectory() {
        Path cwd = Paths.get(System.getProperty("user.dir", "."));
        Path candidate = cwd.resolve("camera-service");
        if (candidate.toFile().isDirectory()) {
            return candidate;
        }
        Path parent = cwd.getParent();
        if (parent != null) {
            candidate = parent.resolve("camera-service");
            if (candidate.toFile().isDirectory()) {
                return candidate;
            }
        }
        return cwd.resolve("camera-service");
    }

    public String getBaseUrl() {
        return "http://" + host + ":" + port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public Path getServiceDirectory() {
        return serviceDirectory;
    }

    public String getPythonCommand() {
        return pythonCommand;
    }

    public boolean isStubMode() {
        return stubMode;
    }

    public File getMainScript() {
        return serviceDirectory.resolve("main.py").toFile();
    }
}
