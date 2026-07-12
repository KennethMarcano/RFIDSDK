package com.peripheral.camera;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CameraMicroserviceClient {

    private final CameraMicroserviceConfig config;
    private volatile boolean available;

    public CameraMicroserviceClient(CameraMicroserviceConfig config) {
        this.config = config;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public boolean checkHealth() {
        try {
            String body = get("/health", CameraMicroserviceConfig.HEALTH_TIMEOUT_MS);
            available = body != null && body.contains("\"ok\"");
            return available;
        } catch (Exception e) {
            available = false;
            return false;
        }
    }

    public CameraStatus getStatus() throws CameraServiceException {
        String body = get("/camera/status", CameraMicroserviceConfig.HEALTH_TIMEOUT_MS);
        return parseStatus(body);
    }

    public String recalibrate() throws CameraServiceException {
        String body = post("/camera/recalibrate", "{}", CameraMicroserviceConfig.CAPTURE_TIMEOUT_MS);
        return extractJsonString(body, "message");
    }

    public String capture(String outputPath) throws CameraServiceException {
        String json = "{\"output_path\":\"" + escapeJson(outputPath) + "\"}";
        String body = post("/camera/capture", json, CameraMicroserviceConfig.CAPTURE_TIMEOUT_MS);
        if (!body.contains("\"success\":true") && !body.contains("\"success\": true")) {
            throw new CameraServiceException(extractJsonString(body, "message"));
        }
        return extractJsonString(body, "path");
    }

    public AnalysisResult analyze(String imagePath, List<ExpectedProductPayload> products)
            throws CameraServiceException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"image_path\":\"").append(escapeJson(imagePath)).append("\",\"expected_products\":[");
        for (int i = 0; i < products.size(); i++) {
            ExpectedProductPayload p = products.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"code\":\"").append(escapeJson(p.code)).append("\",")
                    .append("\"name\":\"").append(escapeJson(p.name)).append("\",")
                    .append("\"quantity\":").append(p.quantity).append(',')
                    .append("\"order_number\":\"").append(escapeJson(p.orderNumber)).append("\",")
                    .append("\"volume_index\":").append(p.volumeIndex).append('}');
        }
        sb.append("]}");
        String body = post("/analysis/analyze", sb.toString(), CameraMicroserviceConfig.ANALYZE_TIMEOUT_MS);
        return parseAnalysis(body);
    }

    private String get(String path, int timeoutMs) throws CameraServiceException {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(path, "GET", timeoutMs);
            int code = conn.getResponseCode();
            String body = readStream(code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                throw new CameraServiceException("HTTP " + code + ": " + body);
            }
            return body;
        } catch (IOException e) {
            throw new CameraServiceException("Falha ao contactar serviço de câmera: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String post(String path, String json, int timeoutMs) throws CameraServiceException {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(path, "POST", timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            String body = readStream(code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                throw new CameraServiceException("HTTP " + code + ": " + body);
            }
            return body;
        } catch (IOException e) {
            throw new CameraServiceException("Falha ao contactar serviço de câmera: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(String path, String method, int timeoutMs) throws IOException {
        URL url = new URL(config.getBaseUrl() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        return conn;
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static CameraStatus parseStatus(String body) {
        return new CameraStatus(
                body.contains("\"ready\":true") || body.contains("\"ready\": true"),
                body.contains("\"model_loaded\":true") || body.contains("\"model_loaded\": true"),
                extractJsonString(body, "last_error"));
    }

    private static AnalysisResult parseAnalysis(String body) {
        boolean success = body.contains("\"success\":true") || body.contains("\"success\": true");
        String message = extractJsonString(body, "message");
        List<String> missing = extractMissingProducts(body);
        return new AnalysisResult(success, message, missing);
    }

    private static List<String> extractMissingProducts(String body) {
        List<String> result = new ArrayList<>();
        int idx = body.indexOf("\"missing_products\"");
        if (idx < 0) {
            return result;
        }
        int searchFrom = idx;
        while (true) {
            int nameIdx = body.indexOf("\"name\"", searchFrom);
            if (nameIdx < 0) {
                break;
            }
            String name = extractJsonString(body.substring(nameIdx), "name");
            if (name != null && !name.isEmpty()) {
                result.add(name);
            }
            searchFrom = nameIdx + 6;
            if (searchFrom >= body.length()) {
                break;
            }
        }
        return result;
    }

    static String extractJsonString(String body, String key) {
        if (body == null) {
            return "";
        }
        String pattern = "\"" + key + "\":\"";
        int start = body.indexOf(pattern);
        if (start < 0) {
            pattern = "\"" + key + "\": \"";
            start = body.indexOf(pattern);
        }
        if (start < 0) {
            return "";
        }
        start += pattern.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(i + 1);
                if (next == 'n') {
                    sb.append('\n');
                } else if (next == 't') {
                    sb.append('\t');
                } else {
                    sb.append(next);
                }
                i++;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public static final class ExpectedProductPayload {
        public final String code;
        public final String name;
        public final int quantity;
        public final String orderNumber;
        public final int volumeIndex;

        public ExpectedProductPayload(String code, String name, int quantity,
                                      String orderNumber, int volumeIndex) {
            this.code = code;
            this.name = name;
            this.quantity = quantity;
            this.orderNumber = orderNumber;
            this.volumeIndex = volumeIndex;
        }
    }

    public static final class CameraStatus {
        private final boolean ready;
        private final boolean modelLoaded;
        private final String lastError;

        public CameraStatus(boolean ready, boolean modelLoaded, String lastError) {
            this.ready = ready;
            this.modelLoaded = modelLoaded;
            this.lastError = lastError;
        }

        public boolean isReady() {
            return ready;
        }

        public boolean isModelLoaded() {
            return modelLoaded;
        }

        public String getLastError() {
            return lastError;
        }
    }

    public static final class AnalysisResult {
        private final boolean success;
        private final String message;
        private final List<String> missingProducts;

        public AnalysisResult(boolean success, String message, List<String> missingProducts) {
            this.success = success;
            this.message = message;
            this.missingProducts = missingProducts != null ? missingProducts : new ArrayList<>();
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public List<String> getMissingProducts() {
            return missingProducts;
        }
    }
}
