package com.peripheral.pedido;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class MockPedidoClient implements PedidoClient {

    private final Path jsonPath;

    public MockPedidoClient() {
        this(resolveJsonPath());
    }

    public MockPedidoClient(Path jsonPath) {
        this.jsonPath = jsonPath;
    }

    @Override
    public Pedido fetchPedido(String numeroPedido) throws PedidoException {
        if (numeroPedido == null || numeroPedido.trim().isEmpty()) {
            throw new PedidoException("Informe o número do pedido.");
        }
        String json = loadJson();
        String numero = numeroPedido.trim();
        List<PedidoVolume> volumes = parseVolumes(json, numero);
        if (volumes.isEmpty()) {
            throw new PedidoException("Pedido não encontrado: " + numero);
        }
        return new Pedido(numero, volumes);
    }

    private String loadJson() throws PedidoException {
        if (jsonPath != null && Files.isRegularFile(jsonPath)) {
            try {
                return new String(Files.readAllBytes(jsonPath), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new PedidoException("Erro ao ler pedidos mock: " + e.getMessage(), e);
            }
        }
        try (InputStream in = MockPedidoClient.class.getResourceAsStream("/resources/pedidos-mock.json")) {
            if (in != null) {
                return readStream(in);
            }
        } catch (IOException e) {
            throw new PedidoException("Erro ao ler pedidos mock do classpath: " + e.getMessage(), e);
        }
        throw new PedidoException("Arquivo pedidos-mock.json não encontrado.");
    }

    private static Path resolveJsonPath() {
        Path[] candidates = new Path[]{
                Paths.get("src/resources/pedidos-mock.json"),
                Paths.get("resources/pedidos-mock.json"),
                Paths.get("out/resources/pedidos-mock.json")
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath();
            }
        }
        return null;
    }

    private static String readStream(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    static List<PedidoVolume> parseVolumes(String json, String numero) {
        List<PedidoVolume> volumes = new ArrayList<>();
        String pedidoBlock = extractPedidoBlock(json, numero);
        if (pedidoBlock == null) {
            return volumes;
        }
        int searchFrom = 0;
        while (true) {
            int volIdx = pedidoBlock.indexOf("\"indice\"", searchFrom);
            if (volIdx < 0) {
                break;
            }
            int indice = parseIntAfterKey(pedidoBlock, volIdx);
            int itemsStart = pedidoBlock.indexOf("\"itens\"", volIdx);
            if (itemsStart < 0) {
                break;
            }
            int arrayStart = pedidoBlock.indexOf('[', itemsStart);
            int arrayEnd = findMatchingBracket(pedidoBlock, arrayStart);
            if (arrayStart < 0 || arrayEnd < 0) {
                break;
            }
            String itemsJson = pedidoBlock.substring(arrayStart, arrayEnd + 1);
            volumes.add(new PedidoVolume(indice, parseItems(itemsJson)));
            searchFrom = arrayEnd + 1;
        }
        return volumes;
    }

    private static String extractPedidoBlock(String json, String numero) {
        String key = "\"numero\":\"" + numero + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            key = "\"numero\": \"" + numero + "\"";
            idx = json.indexOf(key);
        }
        if (idx < 0) {
            return null;
        }
        int objStart = json.lastIndexOf('{', idx);
        int objEnd = findMatchingBrace(json, objStart);
        if (objStart < 0 || objEnd < 0) {
            return null;
        }
        return json.substring(objStart, objEnd + 1);
    }

    private static List<PedidoItem> parseItems(String arrayJson) {
        List<PedidoItem> items = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            int codeIdx = arrayJson.indexOf("\"codigoProduto\"", searchFrom);
            if (codeIdx < 0) {
                break;
            }
            int nextCodeIdx = arrayJson.indexOf("\"codigoProduto\"", codeIdx + 10);
            int itemEnd = nextCodeIdx >= 0 ? nextCodeIdx : arrayJson.length();
            String itemBlock = arrayJson.substring(codeIdx, itemEnd);

            String code = parseStringAfterKey(itemBlock, 0);
            int nameIdx = itemBlock.indexOf("\"nome\"");
            String name = nameIdx >= 0 ? parseStringAfterKey(itemBlock, nameIdx) : code;
            int weightIdx = itemBlock.indexOf("\"pesoUnitarioKg\"");
            double weight = weightIdx >= 0 ? parseDoubleAfterKey(itemBlock, weightIdx) : 0;

            List<PedidoSerial> seriais = parseSeriais(itemBlock);
            if (seriais.isEmpty()) {
                int qtyIdx = itemBlock.indexOf("\"quantidadeEsperada\"");
                int qty = qtyIdx >= 0 ? parseIntAfterKey(itemBlock, qtyIdx) : 1;
                items.add(new PedidoItem(code, name, qty, weight));
            } else {
                items.add(new PedidoItem(code, name, weight, seriais));
            }
            searchFrom = codeIdx + 10;
        }
        return items;
    }

    private static List<PedidoSerial> parseSeriais(String itemBlock) {
        List<PedidoSerial> seriais = new ArrayList<>();
        int seriaisIdx = itemBlock.indexOf("\"seriais\"");
        if (seriaisIdx < 0) {
            return seriais;
        }
        int arrayStart = itemBlock.indexOf('[', seriaisIdx);
        int arrayEnd = findMatchingBracket(itemBlock, arrayStart);
        if (arrayStart < 0 || arrayEnd < 0) {
            return seriais;
        }
        String seriaisJson = itemBlock.substring(arrayStart, arrayEnd + 1);

        if (!seriaisJson.contains("{")) {
            int from = 0;
            while (true) {
                int q = seriaisJson.indexOf('"', from);
                if (q < 0) {
                    break;
                }
                int q2 = seriaisJson.indexOf('"', q + 1);
                if (q2 < 0) {
                    break;
                }
                String value = seriaisJson.substring(q + 1, q2);
                if (!value.isEmpty()) {
                    seriais.add(PedidoSerial.of(value));
                }
                from = q2 + 1;
            }
            return seriais;
        }

        int searchFrom = 0;
        while (true) {
            int serialIdx = seriaisJson.indexOf("\"serial\"", searchFrom);
            if (serialIdx < 0) {
                break;
            }
            String serial = parseStringAfterKey(seriaisJson, serialIdx);
            if (!serial.isEmpty()) {
                int epcIdx = seriaisJson.indexOf("\"epc\"", serialIdx);
                String epc = epcIdx >= 0 ? parseStringAfterKey(seriaisJson, epcIdx) : serial;
                seriais.add(new PedidoSerial(serial, epc));
            }
            searchFrom = serialIdx + 8;
        }
        return seriais;
    }

    private static int findMatchingBrace(String text, int start) {
        if (start < 0 || start >= text.length() || text.charAt(start) != '{') {
            return -1;
        }
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findMatchingBracket(String text, int start) {
        if (start < 0 || start >= text.length() || text.charAt(start) != '[') {
            return -1;
        }
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String parseStringAfterKey(String json, int keyIndex) {
        int colon = json.indexOf(':', keyIndex);
        if (colon < 0) {
            return "";
        }
        int quoteStart = json.indexOf('"', colon + 1);
        if (quoteStart < 0) {
            return "";
        }
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) {
            return "";
        }
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static int parseIntAfterKey(String json, int keyIndex) {
        int colon = json.indexOf(':', keyIndex);
        if (colon < 0) {
            return 0;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '-') {
                sb.append(c);
                i++;
            } else {
                break;
            }
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDoubleAfterKey(String json, int keyIndex) {
        int colon = json.indexOf(':', keyIndex);
        if (colon < 0) {
            return 0;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '-') {
                sb.append(c);
                i++;
            } else {
                break;
            }
        }
        try {
            return Double.parseDouble(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
