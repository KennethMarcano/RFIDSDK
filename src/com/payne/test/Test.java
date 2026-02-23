package com.payne.test;

import com.fazecast.jSerialComm.SerialPort;
import com.payne.reader.Reader;
import com.payne.reader.base.BaseInventory;
import com.payne.reader.base.Consumer;
import com.payne.reader.bean.config.*;
import com.payne.reader.bean.receive.*;
import com.payne.reader.bean.send.*;
import com.payne.reader.communication.ConnectHandle;
import com.payne.reader.process.ReaderImpl;
import com.payne.reader.util.ArrayUtils;
import com.payne.reader.util.LLLog;

import javax.swing.*;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Test {
    /* Verbose console logs */
    private static final boolean VERBOSE = false;

    /*Config params area-----------------------------↓↓↓*/
    public static final String COM_PORT = "ttyUSB0";
    /* SINGLE_CHANNEL FOUR_CHANNELS EIGHT_CHANNELS SIXTEEN_CHANNELS */
    private static AntennaCount antCount = AntennaCount.SINGLE_CHANNEL;
    private static Session session = Session.S0;
    private static Target target = Target.A;
    private static boolean enablePhase = false;
    private static byte power = 33;/* Rang: (1 , 33) */
    private static int[] workAntIdArr = {0, 9};/* work AntId: (0 , 15) */

    private static boolean fastSwitchAnt = false;/* Fast switch antId mode */
    private static byte repeat = 1;   /*Cycle count for each single ant */
    private static int delayMs = 0;   /*Delay seconds for end of one round */
    private static int loopCount = 1;/*Perform times -1：always run */

    /*↑↑↑-----------------------------Config params area end*/
    //
    /*-------------------------------------------------------------------------------------------------*/

    /* HTTP/REST Config ↓↓↓ */
    private static final String ENDPOINT_URL = "https://fulle.eship.com.br/v3/?api&funcao=webServicePostApontamento";/* configure aqui */
    private static final String ENDPOINT_URL_MOVIMENTACAO = "https://fulle.eship.com.br/v3/?api&funcao=webServicePostMovimentacaoObrigatoria";
    private static final String ENDPOINT_URL_EQUIPAMENTO = "https://fulle.eship.com.br/v3/?api&funcao=webServiceGetEquipamento";
    private static final String ENDPOINT_URL_ESTRUTURA_LOCAL = "https://fulle.eship.com.br/v3/?api&funcao=webServiceGetEstruturaLocal";
    private static final String API_TOKEN = "P";/* configure aqui (ex.: "eyJ...")
    /* HTTP/REST Config ↑↑↑ */

    /* GPIO sysfs (Raspberry Pi) ↓↓↓
     * IMPORTANTE: "GPIO29" em Pi4J/WiringPi corresponde ao BCM 21.
     * Portanto, no sysfs usaremos gpio21.
     */
    private static final int GPIO_PIN_BCM = 21; /* mapeado de GPIO29 (WiringPi) -> BCM21 */
    private static final Path SYSFS_EXPORT = Paths.get("/sys/class/gpio/export");
    private static final Path SYSFS_UNEXPORT = Paths.get("/sys/class/gpio/unexport");
    private static final Path SYSFS_GPIO_DIR = Paths.get("/sys/class/gpio/gpio" + GPIO_PIN_BCM);
    private static final Path SYSFS_GPIO_DIRECTION = SYSFS_GPIO_DIR.resolve("direction");
    private static final Path SYSFS_GPIO_VALUE = SYSFS_GPIO_DIR.resolve("value");
    /* GPIO sysfs ↑↑↑ */

    /* UI notifier (Linux) */
    private interface UiNotifier {
        void onConnectStatus(boolean connected);
        void onReadingStatus(boolean reading);
        void onTagDetected(String code);
        void onApiResult(boolean success, String code, String message);
    }
    private static volatile UiNotifier uiNotifier;
    private static volatile JTextField tfIdRecebimento;
    private static volatile JTextField tfCodigoVolumeRecebimento;
    private static volatile JTextField tfCodigoProduto;
    
    /* Campos para Movimentacao Obrigatoria */
    private static volatile JTextField tfCodArmazem;
    private static volatile JComboBox<EquipamentoItem> cbEquipamento;
    private static volatile JComboBox<EstruturaLocalItem> cbDestinoLocal;
    private static volatile JTextField tfTipoMovimento;
    private static volatile JTextField tfNivelOperacao;
    private static volatile JTextField tfApiToken;
    private static volatile JLabel lbStatusEquipamento;
    private static volatile JLabel lbStatusDestinoLocal;
    private static volatile JButton btnAtualizarAlternativas;

    /* Enfileiramento para evitar trabalho pesado no callback do leitor */
    private static final int HTTP_QUEUE_CAPACITY = 256;
    private static final java.util.concurrent.BlockingQueue<String> httpQueue =
            new java.util.concurrent.ArrayBlockingQueue<>(HTTP_QUEUE_CAPACITY);
    private static volatile boolean httpWorkerStarted = false;

    private static String getUiIdRecebimento() {
        if (tfIdRecebimento != null) {
            return safeString(tfIdRecebimento.getText()).trim();
        }
        String v = System.getProperty("idRecebimento");
        if (v == null || v.trim().isEmpty()) v = System.getenv("ID_RECEBIMENTO");
        return v == null ? "" : v.trim();
    }

    private static String getUiCodigoVolumeRecebimento() {
        if (tfCodigoVolumeRecebimento != null) {
            return safeString(tfCodigoVolumeRecebimento.getText()).trim();
        }
        String v = System.getProperty("codigoVolumeRecebimento");
        if (v == null || v.trim().isEmpty()) v = System.getenv("CODIGO_VOLUME_RECEBIMENTO");
        return v == null ? "" : v.trim();
    }

    private static String getUiCodigoProduto() {
        if (tfCodigoProduto != null) {
            return safeString(tfCodigoProduto.getText()).trim();
        }
        String v = System.getProperty("codigoProduto");
        if (v == null || v.trim().isEmpty()) v = System.getenv("CODIGO_PRODUTO");
        return v == null ? "" : v.trim();
    }

    private static String getUiCodArmazem() {
        if (tfCodArmazem != null) {
            return safeString(tfCodArmazem.getText()).trim();
        }
        String v = System.getProperty("codArmazem");
        if (v == null || v.trim().isEmpty()) v = System.getenv("COD_ARMAZEM");
        return v == null ? "" : v.trim();
    }

    private static String getUiEquipamentoId() {
        if (cbEquipamento != null && cbEquipamento.getSelectedItem() != null) {
            EquipamentoItem item = (EquipamentoItem) cbEquipamento.getSelectedItem();
            return String.valueOf(item.getId());
        }
        String v = System.getProperty("equipamentoId");
        if (v == null || v.trim().isEmpty()) v = System.getenv("EQUIPAMENTO_ID");
        return v == null ? "" : v.trim();
    }

    private static String getUiDestinoLocalId() {
        if (cbDestinoLocal != null && cbDestinoLocal.getSelectedItem() != null) {
            EstruturaLocalItem item = (EstruturaLocalItem) cbDestinoLocal.getSelectedItem();
            return String.valueOf(item.getId());
        }
        String v = System.getProperty("destinoLocalId");
        if (v == null || v.trim().isEmpty()) v = System.getenv("DESTINO_LOCAL_ID");
        return v == null ? "" : v.trim();
    }

    private static String getUiTipoMovimento() {
        if (tfTipoMovimento != null) {
            String val = safeString(tfTipoMovimento.getText()).trim();
            return val.isEmpty() ? "0" : val;
        }
        String v = System.getProperty("tipoMovimento");
        if (v == null || v.trim().isEmpty()) v = System.getenv("TIPO_MOVIMENTO");
        return v == null ? "0" : v.trim();
    }

    private static String getUiNivelOperacao() {
        if (tfNivelOperacao != null) {
            String val = safeString(tfNivelOperacao.getText()).trim();
            return val.isEmpty() ? "1" : val;
        }
        String v = System.getProperty("nivelOperacao");
        if (v == null || v.trim().isEmpty()) v = System.getenv("NIVEL_OPERACAO");
        return v == null ? "1" : v.trim();
    }

    private static String getUiApiToken() {
        if (tfApiToken != null) {
            return safeString(tfApiToken.getText()).trim();
        }
        String v = System.getProperty("apiToken");
        if (v == null || v.trim().isEmpty()) v = System.getenv("API_TOKEN");
        // Se não encontrar no input nem nas propriedades, usa a constante como fallback
        return v == null ? (API_TOKEN != null ? API_TOKEN.trim() : "") : v.trim();
    }

    private static InventoryParam param = new InventoryParam();
    private static Consumer<Failure> failureConsumer = new Consumer<Failure>() {
        @Override
        public void accept(Failure failure) throws Exception {
            String codeName = Failure.getNameForResultCode(failure.getErrorCode());
            System.err.println("failure:" + codeName);
        }
    };

    /* ---------------------- HTTP helpers ---------------------- */
    private static Map<String, String> buildDefaultHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        String apiToken = getUiApiToken();
        if (apiToken != null && apiToken.trim().length() > 0) {
            headers.put("Api", apiToken.trim());
        }
        return headers;
    }

    private static String buildApontamentoJsonBody(String serialNumber, String idRecebimento, String codigoVolumeRecebimento, String codigoProduto) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{")
                .append("\"idRecebimento\":\"").append(escapeJson(safeString(idRecebimento))).append("\",")
                .append("\"codigoVolumeRecebimento\":\"").append(escapeJson(safeString(codigoVolumeRecebimento))).append("\",")
                .append("\"serialNumber\":\"").append(escapeJson(safeString(serialNumber))).append("\"");
        String cp = safeString(codigoProduto).trim();
        if (cp.length() > 0) {
            sb.append(",").append("\"codigoProduto\":\"").append(escapeJson(cp)).append("\"");
        } else {
            sb.append(",").append("\"codigoProduto\":").append("null");
        }
        sb
                .append("}");
        return sb.toString();
    }

    private static String buildMovimentacaoJsonBody(String codArmazem, String equipamentoId, String destinoLocalId, 
                                                     String sequenciamento, String codigoIdentificador, 
                                                     String tipoMovimento, String nivelOperacao) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{")
                .append("\"codArmazem\":\"").append(escapeJson(safeString(codArmazem))).append("\",")
                .append("\"equipamentoId\":\"").append(escapeJson(safeString(equipamentoId))).append("\",")
                .append("\"destinoLocalId\":\"").append(escapeJson(safeString(destinoLocalId))).append("\",")
                .append("\"sequenciamento\":\"").append(escapeJson(safeString(sequenciamento))).append("\",")
                .append("\"codigoIdentificador\":\"").append(escapeJson(safeString(codigoIdentificador))).append("\",")
                .append("\"tipoMovimento\":\"").append(escapeJson(safeString(tipoMovimento))).append("\",")
                .append("\"nivelOperacao\":\"").append(escapeJson(safeString(nivelOperacao))).append("\"")
                .append("}");
        return sb.toString();
    }

    private static String extractCodeFromTag(InventoryTag tag) {
        String epcHex = safeString(tag != null ? tag.getEpc() : "");
        String epcHexClean = epcHex.replaceAll("[^0-9A-Fa-f]", "");
        String epcDecimal;
        if (epcHexClean.length() >= 2) {
            try {
                int hexLen = epcHexClean.length();
                if ((hexLen & 1) == 1) {
                    hexLen -= 1;
                }
                byte[] buf = new byte[hexLen / 2];
                for (int i = 0; i < hexLen; i += 2) {
                    buf[i / 2] = (byte) Integer.parseInt(epcHexClean.substring(i, i + 2), 16);
                }
                String ascii = new String(buf, StandardCharsets.US_ASCII);
                String digitsOnly = ascii.replaceAll("[^0-9]", "");
                epcDecimal = digitsOnly.length() > 0 ? digitsOnly : "0";
            } catch (Exception e) {
                epcDecimal = "0";
            }
        } else {
            epcDecimal = "0";
        }
        return epcDecimal;
    }

    /* ---------------------- GPIO sysfs helpers ---------------------- */
    private static void gpioInitIfLinux() {
        String os = safeString(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return;
        try {
            if (!Files.exists(SYSFS_GPIO_DIR)) {
                Files.write(SYSFS_EXPORT, String.valueOf(GPIO_PIN_BCM).getBytes(StandardCharsets.US_ASCII),
                        StandardOpenOption.WRITE);
                /* aguarda o kernel criar a pasta */
                for (int i = 0; i < 10 && !Files.exists(SYSFS_GPIO_DIR); i++) {
                    try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                }
            }
            if (Files.exists(SYSFS_GPIO_DIRECTION)) {
                Files.write(SYSFS_GPIO_DIRECTION, "out".getBytes(StandardCharsets.US_ASCII), StandardOpenOption.WRITE);
            }
            if (Files.exists(SYSFS_GPIO_VALUE)) {
                Files.write(SYSFS_GPIO_VALUE, "0".getBytes(StandardCharsets.US_ASCII), StandardOpenOption.WRITE);
            }
        } catch (Exception e) {
            System.err.println("GPIO sysfs init error: " + e.getMessage());
        }
    }

    private static void gpioSetHighPulse() {
        String os = safeString(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return;
        try {
            if (!Files.exists(SYSFS_GPIO_VALUE)) return;
            Files.write(SYSFS_GPIO_VALUE, "1".getBytes(StandardCharsets.US_ASCII), StandardOpenOption.WRITE);
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                try {
                    Files.write(SYSFS_GPIO_VALUE, "0".getBytes(StandardCharsets.US_ASCII), StandardOpenOption.WRITE);
                } catch (Exception ignored) {
                }
            }, "gpio29-success-pulse").start();
        } catch (Exception e) {
            System.err.println("GPIO sysfs write error: " + e.getMessage());
        }
    }
    /* -------------------- GPIO sysfs helpers (end) -------------------- */

    /* ---------------------- Linux serial helpers ---------------------- */
    private static String resolveLinuxPortByScan() {
        try {
            SerialPort[] ports = SerialPort.getCommPorts();
            String candidate = null;
            for (SerialPort p : ports) {
                String name = safeString(p.getSystemPortName());
                if (name.contains("ttyUSB") || name.contains("ttyACM") || name.contains("ttyAMA")) {
                    candidate = name;
                    break;
                }
                if (candidate == null) candidate = name;
            }
            return candidate != null && candidate.length() > 0 ? candidate : "ttyUSB0";
        } catch (Throwable ignored) {
            return "ttyUSB0";
        }
    }

    private static String resolveLinuxPortFromPropsEnv() {
        String[] props = {"rfid.port", "serial.port"};
        for (String key : props) {
            String v = System.getProperty(key);
            if (v != null && v.trim().length() > 0) return v.trim();
        }
        String[] envs = {"RFID_PORT", "SERIAL_PORT"};
        for (String key : envs) {
            String v = System.getenv(key);
            if (v != null && v.trim().length() > 0) return v.trim();
        }
        return null;
    }
    /* -------------------- Linux serial helpers (end) -------------------- */

    /* ---------------------- API body validation helpers ---------------------- */
    private static boolean bodyHasNonEmptyErrors(String body) {
        if (body == null || body.trim().isEmpty()) return false;
        
        // Procurar pelo campo "erros"
        int key = body.indexOf("\"erros\"");
        if (key < 0) {
            // Se não encontrar "erros", assumir que não há erros (pode ser formato diferente)
            return false;
        }
        
        // Procurar pelo valor após "erros":
        int colon = body.indexOf(':', key);
        if (colon < 0) return false;
        
        // Pular espaços após os dois pontos
        int valueStart = colon + 1;
        while (valueStart < body.length() && Character.isWhitespace(body.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart >= body.length()) return false;
        
        // Verificar se é null (sem erros)
        String remaining = body.substring(valueStart);
        if (remaining.startsWith("null")) {
            return false; // null significa sem erros - sucesso
        }
        
        // Verificar se é um array vazio [] (sem erros)
        if (remaining.startsWith("[]")) {
            return false; // array vazio significa sem erros - sucesso
        }
        
        // Verificar se é um array com conteúdo
        if (remaining.startsWith("[")) {
            int rb = body.indexOf(']', valueStart);
            if (rb < 0) return false;
            String inside = body.substring(valueStart + 1, rb).trim();
            // Se o array tem conteúdo, há erros
            return inside.length() > 0;
        }
        
        // Se chegou aqui e não é null nem array vazio, pode haver erro
        // Mas por padrão, se não conseguimos identificar, assumir que não há erros
        return false;
    }

    private static String extractFirstErrorMessage(String body) {
        if (body == null) return null;
        try {
            Pattern p = Pattern.compile("\"mensagem\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
            Matcher m = p.matcher(body);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String extractFirstErrorCode(String body) {
        if (body == null) return null;
        try {
            Pattern p = Pattern.compile("\"codigo\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
            Matcher m = p.matcher(body);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {
        }
        return null;
    }
    /* ------------------ API body validation helpers (end) ------------------ */

    /* ---------------------- Classes auxiliares para selects ---------------------- */
    private static class EquipamentoItem {
        private final int id;
        private final String descricao;
        private final String codArmazem;

        public EquipamentoItem(int id, String descricao, String codArmazem) {
            this.id = id;
            this.descricao = descricao;
            this.codArmazem = codArmazem;
        }

        public int getId() {
            return id;
        }

        public String getDescricao() {
            return descricao;
        }

        public String getCodArmazem() {
            return codArmazem;
        }

        @Override
        public String toString() {
            return descricao;
        }
    }

    private static class EstruturaLocalItem {
        private final int id;
        private final String descricao;
        private final String codArmazem;
        private final int tipo;

        public EstruturaLocalItem(int id, String descricao, String codArmazem, int tipo) {
            this.id = id;
            this.descricao = descricao;
            this.codArmazem = codArmazem;
            this.tipo = tipo;
        }

        public int getId() {
            return id;
        }

        public String getDescricao() {
            return descricao;
        }

        public String getCodArmazem() {
            return codArmazem;
        }

        public int getTipo() {
            return tipo;
        }

        @Override
        public String toString() {
            return descricao;
        }
    }
    /* -------------------- Classes auxiliares (end) -------------------- */

    /* ---------------------- Métodos GET para buscar dados ---------------------- */
    private static String getJson(String urlStr, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (java.io.InputStream is = conn.getInputStream()) {
                    if (is != null) {
                        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            StringBuilder rsb = new StringBuilder();
                            while ((line = br.readLine()) != null) {
                                rsb.append(line).append('\n');
                            }
                            return rsb.toString().trim();
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("GET error: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static java.util.List<EquipamentoItem> fetchEquipamentos() {
        java.util.List<EquipamentoItem> lista = new ArrayList<>();
        System.out.println("========================================");
        System.out.println("Consultando equipamentos...");
        System.out.println("URL: " + ENDPOINT_URL_EQUIPAMENTO);
        try {
            Map<String, String> headers = buildDefaultHeaders();
            String responseBody = getJson(ENDPOINT_URL_EQUIPAMENTO, headers);
            System.out.println("Resposta da API (Equipamentos):");
            System.out.println(responseBody != null ? responseBody : "(null)");
            
            if (responseBody != null && !responseBody.isEmpty()) {
                // Verificar se há erros na resposta
                if (bodyHasNonEmptyErrors(responseBody)) {
                    String errorMsg = extractFirstErrorMessage(responseBody);
                    String errorCode = extractFirstErrorCode(responseBody);
                    System.err.println("ERRO na consulta de equipamentos: " + errorMsg + (errorCode != null ? " (" + errorCode + ")" : ""));
                    if (lbStatusEquipamento != null) {
                        SwingUtilities.invokeLater(() -> {
                            lbStatusEquipamento.setText("ERRO: " + (errorMsg != null ? errorMsg : "Erro desconhecido"));
                            lbStatusEquipamento.setForeground(Color.RED);
                        });
                    }
                    return lista;
                }
                
                // Parse JSON response
                // Estrutura: {"erros":null,"corpo":{"body":{"dadosPaginacao":{...},"dados":[...]}}}
                // Procurar pelo array "dados" de forma mais flexível
                Pattern dadosPattern = Pattern.compile("\"dados\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
                Matcher dadosMatcher = dadosPattern.matcher(responseBody);
                if (dadosMatcher.find()) {
                    String dadosStr = dadosMatcher.group(1);
                    // Extrair cada objeto do array - padrão mais flexível
                    Pattern itemPattern = Pattern.compile("\\{\\s*\"id\"\\s*:\\s*(\\d+)\\s*,\\s*\"codArmazem\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"descricao\"\\s*:\\s*\"([^\"]*)\"\\s*\\}", Pattern.DOTALL);
                    Matcher itemMatcher = itemPattern.matcher(dadosStr);
                    while (itemMatcher.find()) {
                        try {
                            int id = Integer.parseInt(itemMatcher.group(1).trim());
                            String codArmazem = itemMatcher.group(2).trim();
                            String descricao = itemMatcher.group(3).trim();
                            lista.add(new EquipamentoItem(id, descricao, codArmazem));
                        } catch (Exception e) {
                            System.err.println("Erro ao processar item de equipamento: " + e.getMessage());
                        }
                    }
                }
                
                System.out.println("Equipamentos encontrados: " + lista.size());
                if (lbStatusEquipamento != null) {
                    SwingUtilities.invokeLater(() -> {
                        lbStatusEquipamento.setText("OK (" + lista.size() + " itens)");
                        lbStatusEquipamento.setForeground(Color.GREEN);
                    });
                }
            } else {
                System.err.println("Resposta vazia ou nula da API de equipamentos");
                if (lbStatusEquipamento != null) {
                    SwingUtilities.invokeLater(() -> {
                        lbStatusEquipamento.setText("ERRO: Resposta vazia");
                        lbStatusEquipamento.setForeground(Color.RED);
                    });
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching equipamentos: " + e.getMessage());
            e.printStackTrace();
            if (lbStatusEquipamento != null) {
                SwingUtilities.invokeLater(() -> {
                    lbStatusEquipamento.setText("ERRO: " + e.getMessage());
                    lbStatusEquipamento.setForeground(Color.RED);
                });
            }
        }
        System.out.println("========================================");
        return lista;
    }

    private static java.util.List<EstruturaLocalItem> fetchEstruturasLocais() {
        java.util.List<EstruturaLocalItem> lista = new ArrayList<>();
        System.out.println("========================================");
        System.out.println("Consultando estruturas locais...");
        System.out.println("URL: " + ENDPOINT_URL_ESTRUTURA_LOCAL);
        try {
            Map<String, String> headers = buildDefaultHeaders();
            String responseBody = getJson(ENDPOINT_URL_ESTRUTURA_LOCAL, headers);
            System.out.println("Resposta da API (Estruturas Locais):");
            System.out.println(responseBody != null ? responseBody : "(null)");
            
            if (responseBody != null && !responseBody.isEmpty()) {
                // Verificar se há erros na resposta
                if (bodyHasNonEmptyErrors(responseBody)) {
                    String errorMsg = extractFirstErrorMessage(responseBody);
                    String errorCode = extractFirstErrorCode(responseBody);
                    System.err.println("ERRO na consulta de estruturas locais: " + errorMsg + (errorCode != null ? " (" + errorCode + ")" : ""));
                    if (lbStatusDestinoLocal != null) {
                        SwingUtilities.invokeLater(() -> {
                            lbStatusDestinoLocal.setText("ERRO: " + (errorMsg != null ? errorMsg : "Erro desconhecido"));
                            lbStatusDestinoLocal.setForeground(Color.RED);
                        });
                    }
                    return lista;
                }
                
                // Parse JSON response
                // Procurar pelo array "dados" de forma mais flexível
                Pattern dadosPattern = Pattern.compile("\"dados\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
                Matcher dadosMatcher = dadosPattern.matcher(responseBody);
                if (dadosMatcher.find()) {
                    String dadosStr = dadosMatcher.group(1);
                    // Extrair cada objeto do array - padrão mais flexível
                    Pattern itemPattern = Pattern.compile("\\{\\s*\"id\"\\s*:\\s*(\\d+)\\s*,\\s*\"codArmazem\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"descricao\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"tipo\"\\s*:\\s*(\\d+)\\s*\\}", Pattern.DOTALL);
                    Matcher itemMatcher = itemPattern.matcher(dadosStr);
                    while (itemMatcher.find()) {
                        try {
                            int id = Integer.parseInt(itemMatcher.group(1).trim());
                            String codArmazem = itemMatcher.group(2).trim();
                            String descricao = itemMatcher.group(3).trim();
                            int tipo = Integer.parseInt(itemMatcher.group(4).trim());
                            lista.add(new EstruturaLocalItem(id, descricao, codArmazem, tipo));
                        } catch (Exception e) {
                            System.err.println("Erro ao processar item de estrutura local: " + e.getMessage());
                        }
                    }
                }
                
                System.out.println("Estruturas locais encontradas: " + lista.size());
                if (lbStatusDestinoLocal != null) {
                    SwingUtilities.invokeLater(() -> {
                        lbStatusDestinoLocal.setText("OK (" + lista.size() + " itens)");
                        lbStatusDestinoLocal.setForeground(Color.GREEN);
                    });
                }
            } else {
                System.err.println("Resposta vazia ou nula da API de estruturas locais");
                if (lbStatusDestinoLocal != null) {
                    SwingUtilities.invokeLater(() -> {
                        lbStatusDestinoLocal.setText("ERRO: Resposta vazia");
                        lbStatusDestinoLocal.setForeground(Color.RED);
                    });
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching estruturas locais: " + e.getMessage());
            e.printStackTrace();
            if (lbStatusDestinoLocal != null) {
                SwingUtilities.invokeLater(() -> {
                    lbStatusDestinoLocal.setText("ERRO: " + e.getMessage());
                    lbStatusDestinoLocal.setForeground(Color.RED);
                });
            }
        }
        System.out.println("========================================");
        return lista;
    }
    /* -------------------- Métodos GET (end) -------------------- */

    private static void postJson(String urlStr, String jsonBody, Map<String, String> headers, String codeForUi) {
        String currentUrl = urlStr;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(currentUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);/* preservar POST em 307/308 */
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
                System.out.println("jsonBody: " + jsonBody);
                byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
                int code = conn.getResponseCode();
                if (code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && loc.length() > 0) {
                        System.out.println("HTTP redirect " + code + " -> " + loc);
                        currentUrl = loc;
                        continue;/* tenta novamente no novo Location com POST */
                    }
                }
                /* Ler e imprimir corpo da resposta (sucesso ou erro) */
                String responseBody = "";
                try (java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()) {
                    if (is != null) {
                        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            StringBuilder rsb = new StringBuilder();
                            while ((line = br.readLine()) != null) {
                                rsb.append(line).append('\n');
                            }
                            responseBody = rsb.toString().trim();
                        }
                    }
                } catch (Exception ignored) {
                }
                // System.out.println("HTTP status: " + code);
                // System.out.println("HTTP response: " + responseBody);
                boolean httpOk = (code >= 200 && code < 300);
                boolean bodyErr = bodyHasNonEmptyErrors(responseBody);
                boolean success = httpOk && !bodyErr;

                String msg;
                if (bodyErr) {
                    String em = extractFirstErrorMessage(responseBody);
                    String ec = extractFirstErrorCode(responseBody);
                    msg = (em != null ? em : "Erro(s) no corpo") + (ec != null ? " (" + ec + ")" : "");
                } else {
                    msg = "HTTP " + code;
                }

                if (!success && !httpOk) {
                    System.err.println("HTTP post failed: " + code);
                }

                if (success) {
                    gpioSetHighPulse(); /* pulso em sucesso */
                    if (uiNotifier != null) {
                        UiNotifier n = uiNotifier;
                        SwingUtilities.invokeLater(() -> n.onApiResult(true, codeForUi, msg));
                    }
                    System.out.println("OK tag=" + codeForUi + " " + msg);
                } else {
                    if (uiNotifier != null) {
                        UiNotifier n = uiNotifier;
                        SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, msg));
                    }
                    System.err.println("ERROR tag=" + codeForUi + " " + msg);
                }
                break;/* sucesso ou erro não-redirecionado: sai do loop */
            } catch (java.net.SocketTimeoutException e) {
                System.err.println("HTTP post timeout: " + e.getMessage());
                System.err.println("NOTA: Timeout HTTP não afeta a conexão com a antena");
                if (uiNotifier != null) {
                    UiNotifier n = uiNotifier;
                    SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, "Timeout na conexão HTTP"));
                }
                // Timeout HTTP não deve afetar a conexão com a antena
                break;
            } catch (java.net.ConnectException e) {
                System.err.println("HTTP post connection error: " + e.getMessage());
                System.err.println("NOTA: Erro de conexão HTTP não afeta a conexão com a antena");
                if (uiNotifier != null) {
                    UiNotifier n = uiNotifier;
                    SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, "Erro de conexão HTTP"));
                }
                // Erro de conexão HTTP não deve afetar a conexão com a antena
                break;
            } catch (Exception e) {
                System.err.println("HTTP post error: " + e.getMessage());
                System.err.println("NOTA: Erro HTTP não afeta a conexão com a antena");
                e.printStackTrace();
                if (uiNotifier != null) {
                    UiNotifier n = uiNotifier;
                    SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, e.getMessage()));
                }
                // Erros HTTP não devem afetar a conexão com a antena
                break;
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static void sendTag(InventoryTag tag, String codeForUi) {
        if (ENDPOINT_URL == null || ENDPOINT_URL.trim().isEmpty()) {
            return;
        }
        String idRecebimento = getUiIdRecebimento();
        String codigoVolume = getUiCodigoVolumeRecebimento();
        if (idRecebimento == null || idRecebimento.trim().isEmpty()
                || codigoVolume == null || codigoVolume.trim().isEmpty()) {
            String msg = "Preencha Identificador Recebimento e Codigo Volume Recebimento";
            System.err.println("ERROR tag=" + codeForUi + " " + msg);
            if (uiNotifier != null) {
                UiNotifier n = uiNotifier;
                SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, msg));
            }
            return;
        }
        String codigoProduto = getUiCodigoProduto();
        final String body = buildApontamentoJsonBody(codeForUi, idRecebimento, codigoVolume, codigoProduto);
        final Map<String, String> headers = buildDefaultHeaders();
        new Thread(() -> postJson(ENDPOINT_URL, body, headers, codeForUi), "rfid-http-post").start();
    }

    /* Enfileirar envio para não bloquear callbacks do leitor */
    private static void enqueueSend(String codeForUi) {
        System.out.println("========================================");
        System.out.println("Tag detectada: " + codeForUi);
        String codArmazem = getUiCodArmazem();
        String equipamentoId = getUiEquipamentoId();
        String destinoLocalId = getUiDestinoLocalId();
        System.out.println("codArmazem: " + codArmazem);
        System.out.println("equipamentoId: " + equipamentoId);
        System.out.println("destinoLocalId: " + destinoLocalId);
        
        if (codArmazem == null || codArmazem.trim().isEmpty()
                || equipamentoId == null || equipamentoId.trim().isEmpty()
                || destinoLocalId == null || destinoLocalId.trim().isEmpty()) {
            String msg = "Preencha Código Armazém, Equipamento e Destino Local";
            System.err.println("ERROR tag=" + codeForUi + " " + msg);
            if (uiNotifier != null) {
                UiNotifier n = uiNotifier;
                SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, msg));
            }
            return;
        }
        boolean offered = httpQueue.offer(codeForUi);
        if (!offered) {
            String msg = "Fila cheia. Descartando leitura.";
            System.err.println("ERROR tag=" + codeForUi + " " + msg);
            if (uiNotifier != null) {
                UiNotifier n = uiNotifier;
                SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, msg));
            }
        } else {
            System.out.println("Tag adicionada à fila para envio");
        }
        System.out.println("========================================");
    }

    private static synchronized void startHttpWorkerIfNeeded() {
        if (httpWorkerStarted) return;
        httpWorkerStarted = true;
        System.out.println("HTTP Worker iniciado - aguardando tags para envio via webServicePostMovimentacaoObrigatoria");
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    String code = httpQueue.take();
                    System.out.println("========================================");
                    System.out.println("Processando tag da fila: " + code);
                    String codArmazem = getUiCodArmazem();
                    String equipamentoId = getUiEquipamentoId();
                    String destinoLocalId = getUiDestinoLocalId();
                    if (codArmazem == null || codArmazem.trim().isEmpty()
                            || equipamentoId == null || equipamentoId.trim().isEmpty()
                            || destinoLocalId == null || destinoLocalId.trim().isEmpty()) {
                        String msg = "Campos obrigatórios não preenchidos (codArmazem, equipamentoId, destinoLocalId)";
                        System.err.println("ERROR: " + msg);
                        if (uiNotifier != null) {
                            UiNotifier n = uiNotifier;
                            SwingUtilities.invokeLater(() -> n.onApiResult(false, code, msg));
                        }
                        continue;
                    }
                    String tipoMovimento = getUiTipoMovimento();
                    String nivelOperacao = getUiNivelOperacao();
                    // sequenciamento: timestamp da data de envio
                    String sequenciamento = String.valueOf(System.currentTimeMillis());
                    System.out.println("Enviando para: " + ENDPOINT_URL_MOVIMENTACAO);
                    System.out.println("codArmazem: " + codArmazem);
                    System.out.println("equipamentoId: " + equipamentoId);
                    System.out.println("destinoLocalId: " + destinoLocalId);
                    System.out.println("sequenciamento: " + sequenciamento);
                    System.out.println("codigoIdentificador: " + code);
                    System.out.println("tipoMovimento: " + tipoMovimento);
                    System.out.println("nivelOperacao: " + nivelOperacao);
                    final String body = buildMovimentacaoJsonBody(codArmazem, equipamentoId, destinoLocalId, 
                                                                  sequenciamento, code, tipoMovimento, nivelOperacao);
                    final Map<String, String> headers = buildDefaultHeaders();
                    postJson(ENDPOINT_URL_MOVIMENTACAO, body, headers, code);
                    System.out.println("========================================");
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable th) {
                    System.err.println("HTTP worker error: " + th.getMessage());
                    th.printStackTrace();
                }
            }
        }, "rfid-http-worker");
        t.setDaemon(true);
        t.start();
    }

    private static String safeString(String s) {
        return s == null ? "" : s;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
    /* -------------------- HTTP helpers (end) -------------------- */

    public static void main(String[] args) {/*You can run main to list serialport */
        try {
            loopCount = Integer.parseInt(args[0]);
        } catch (Exception ignored) {
        }
        System.out.println("-------------------------------------------------" + Arrays.toString(args));
        SerialPort[] commPorts = SerialPort.getCommPorts();
        if (VERBOSE) {
            for (SerialPort commPort : commPorts) {
                System.out.println("CommPort:" + commPort.getDescriptivePortName() + "-->" + commPort.getSystemPortName());
            }
        }

        String osName = System.getProperty("os.name").toLowerCase();
        System.out.println("OS:" + osName
                + "\n-------------------------------------------------");
//        LLLog.setLogLs(new LLLog.OnLogL() {
//            @Override
//            public void onLogI(String s) {
//                System.out.println("i.s:" + s);
//            }
//
//            @Override
//            public void onLogW(String s) {
//                System.out.println("w.s:" + s);
//            }
//
//            @Override
//            public void onLogE(String s) {
//                System.err.println("e.s:" + s);
//            }
//        });
        if (osName.contains("win")) {
            param.setAntennaCount(antCount);
            param.setSession(session);
            param.setTarget(target);
            param.setRepeat(repeat);
            param.setDelayMs(delayMs * 1000);
            param.setLoopCount(loopCount);

            param.clearCustomSessionIds();
            for (int antId : workAntIdArr) {
                param.addCustomSessionId(antId);
            }

            new WinDemo();
        } else {
            if (commPorts.length <= 0) {
                System.err.println("no com port!");
                return;
            }
            initLinuxUI();
            gpioInitIfLinux();
            startHttpWorkerIfNeeded();
            LinuxDemo demo = new LinuxDemo();
            boolean connect = demo.connect();
            if (connect) {
                demo.startInventory();
            }
        }
    }

    /* ---------------------- Simple Linux UI (Swing) ---------------------- */
    private static void initLinuxUI() {
        String os = safeString(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return;
        SwingUtilities.invokeLater(() -> {
            JFrame jf = new JFrame("RFID Linux Status - Movimentação Obrigatória");
            jf.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            jf.setAlwaysOnTop(true);
            jf.setSize(800, 600);
            jf.setLayout(new GridLayout(15, 1));

            JLabel lbConn = new JLabel("Conexão: -");
            JLabel lbRead = new JLabel("Leitura: -");
            JLabel lbTag = new JLabel("Última tag: -");
            JLabel lbApi = new JLabel("API: -");

            // Campo API Token (Autorização)
            JPanel pApiToken = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JLabel lbApiToken = new JLabel("Autorização (API Token):");
            JTextField tfApiTokenField = new JTextField(30);
            if (API_TOKEN != null && !API_TOKEN.trim().isEmpty() && !API_TOKEN.equals("P")) {
                tfApiTokenField.setText(API_TOKEN);
            }
            pApiToken.add(lbApiToken);
            pApiToken.add(tfApiTokenField);

            // Campo codArmazem
            JPanel pCodArmazem = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JLabel lbCodArmazem = new JLabel("Código Armazém:");
            JTextField tfCodArmazemField = new JTextField(22);
            pCodArmazem.add(lbCodArmazem);
            pCodArmazem.add(tfCodArmazemField);

            // Select Equipamento
            JPanel pEquipamento = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JLabel lbEquipamento = new JLabel("Equipamento:");
            JComboBox<EquipamentoItem> cbEquipamentoField = new JComboBox<>();
            JLabel lbStatusEquipamentoField = new JLabel("(aguardando...)");
            lbStatusEquipamentoField.setForeground(Color.GRAY);
            pEquipamento.add(lbEquipamento);
            pEquipamento.add(cbEquipamentoField);
            pEquipamento.add(lbStatusEquipamentoField);

            // Select Destino Local
            JPanel pDestinoLocal = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JLabel lbDestinoLocal = new JLabel("Destino Local:");
            JComboBox<EstruturaLocalItem> cbDestinoLocalField = new JComboBox<>();
            JLabel lbStatusDestinoLocalField = new JLabel("(aguardando...)");
            lbStatusDestinoLocalField.setForeground(Color.GRAY);
            pDestinoLocal.add(lbDestinoLocal);
            pDestinoLocal.add(cbDestinoLocalField);
            pDestinoLocal.add(lbStatusDestinoLocalField);

            // Botão para atualizar alternativas
            JPanel pBtnAtualizar = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JButton btnAtualizarAlternativasField = new JButton("Atualizar Alternativas");
            pBtnAtualizar.add(btnAtualizarAlternativasField);

            // Campo tipoMovimento
            JPanel pTipoMovimento = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JLabel lbTipoMovimento = new JLabel("Tipo Movimento:");
            JTextField tfTipoMovimentoField = new JTextField(22);
            tfTipoMovimentoField.setText("0");
            pTipoMovimento.add(lbTipoMovimento);
            pTipoMovimento.add(tfTipoMovimentoField);

            // Campo nivelOperacao
            JPanel pNivelOperacao = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JLabel lbNivelOperacao = new JLabel("Nível Operação:");
            JTextField tfNivelOperacaoField = new JTextField(22);
            tfNivelOperacaoField.setText("1");
            pNivelOperacao.add(lbNivelOperacao);
            pNivelOperacao.add(tfNivelOperacaoField);

            jf.add(lbConn);
            jf.add(lbRead);
            jf.add(pApiToken);
            jf.add(pCodArmazem);
            jf.add(pEquipamento);
            jf.add(pDestinoLocal);
            jf.add(pBtnAtualizar);
            jf.add(pTipoMovimento);
            jf.add(pNivelOperacao);
            jf.add(lbTag);
            jf.add(lbApi);
            jf.setLocationRelativeTo(null);
            jf.setVisible(true);

            tfApiToken = tfApiTokenField;
            tfCodArmazem = tfCodArmazemField;
            cbEquipamento = cbEquipamentoField;
            cbDestinoLocal = cbDestinoLocalField;
            tfTipoMovimento = tfTipoMovimentoField;
            tfNivelOperacao = tfNivelOperacaoField;
            lbStatusEquipamento = lbStatusEquipamentoField;
            lbStatusDestinoLocal = lbStatusDestinoLocalField;
            btnAtualizarAlternativas = btnAtualizarAlternativasField;

            // Método para carregar equipamentos
            Runnable loadEquipamentos = () -> {
                SwingUtilities.invokeLater(() -> {
                    lbStatusEquipamentoField.setText("Carregando...");
                    lbStatusEquipamentoField.setForeground(Color.BLUE);
                });
                try {
                    java.util.List<EquipamentoItem> equipamentos = fetchEquipamentos();
                    SwingUtilities.invokeLater(() -> {
                        cbEquipamentoField.removeAllItems();
                        for (EquipamentoItem item : equipamentos) {
                            cbEquipamentoField.addItem(item);
                        }
                        if (equipamentos.isEmpty()) {
                            System.err.println("Nenhum equipamento encontrado");
                        }
                    });
                } catch (Exception e) {
                    System.err.println("Erro ao carregar equipamentos: " + e.getMessage());
                    SwingUtilities.invokeLater(() -> {
                        lbStatusEquipamentoField.setText("ERRO: " + e.getMessage());
                        lbStatusEquipamentoField.setForeground(Color.RED);
                    });
                }
            };

            // Método para carregar estruturas locais
            Runnable loadEstruturas = () -> {
                SwingUtilities.invokeLater(() -> {
                    lbStatusDestinoLocalField.setText("Carregando...");
                    lbStatusDestinoLocalField.setForeground(Color.BLUE);
                });
                try {
                    java.util.List<EstruturaLocalItem> estruturas = fetchEstruturasLocais();
                    SwingUtilities.invokeLater(() -> {
                        cbDestinoLocalField.removeAllItems();
                        for (EstruturaLocalItem item : estruturas) {
                            cbDestinoLocalField.addItem(item);
                        }
                        if (estruturas.isEmpty()) {
                            System.err.println("Nenhuma estrutura local encontrada");
                        }
                    });
                } catch (Exception e) {
                    System.err.println("Erro ao carregar estruturas locais: " + e.getMessage());
                    SwingUtilities.invokeLater(() -> {
                        lbStatusDestinoLocalField.setText("ERRO: " + e.getMessage());
                        lbStatusDestinoLocalField.setForeground(Color.RED);
                    });
                }
            };

            // Método para atualizar todas as alternativas
            Runnable atualizarAlternativas = () -> {
                btnAtualizarAlternativasField.setEnabled(false);
                btnAtualizarAlternativasField.setText("Atualizando...");
                new Thread(() -> {
                    loadEquipamentos.run();
                    loadEstruturas.run();
                    SwingUtilities.invokeLater(() -> {
                        btnAtualizarAlternativasField.setEnabled(true);
                        btnAtualizarAlternativasField.setText("Atualizar Alternativas");
                    });
                }, "atualizar-alternativas").start();
            };

            // Adicionar listener ao botão
            btnAtualizarAlternativasField.addActionListener(e -> atualizarAlternativas.run());

            // Carregar dados dos selects em thread separada ao iniciar
            new Thread(() -> {
                loadEquipamentos.run();
                loadEstruturas.run();
            }, "load-inicial").start();

            uiNotifier = new UiNotifier() {
                @Override
                public void onConnectStatus(boolean connected) {
                    lbConn.setText("Conexão: " + (connected ? "Conectado" : "Desconectado"));
                }

                @Override
                public void onReadingStatus(boolean reading) {
                    lbRead.setText("Leitura: " + (reading ? "Lendo..." : "Parado"));
                }

                @Override
                public void onTagDetected(String code) {
                    lbTag.setText("Última tag: " + code);
                    lbApi.setText("API: aguardando resposta...");
                }

                @Override
                public void onApiResult(boolean success, String code, String message) {
                    lbApi.setText("API (" + code + "): " + (success ? "OK" : "ERRO") + " - " + message);
                }
            };

            jf.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    System.exit(0);
                }
            });
        });
    }
    /* -------------------- Simple Linux UI (end) -------------------- */

    private static class WinDemo {
        //<editor-fold desc="No need edit area">
        private final String connectStr = "Connect";
        private final String disconnectStr = "Disconnect";
        private final String countStr = "Total:";
        private final String startStr = "Start";
        private final String stopStr = "Stop";
        private final String showListStr = "ShowResult";

        //        private JComboBox<String> jcb = new JComboBox<>();
        private JLabel lbVer = new JLabel("-");
        private JLabel lbCount = new JLabel(countStr);
        private JButton btnConnect = new JButton(connectStr);
        //        private JCheckBox jCbFastSwitchAnt = new JCheckBox("FastSwitchAnt");
        private JButton btnStart = new JButton(startStr);
        private JButton btnShowList = new JButton(showListStr);

        private SimpleDateFormat mSdf = getSafeDateFormat("yyyy/MM/dd HH:mm:ss.SSS");
        private LinkedHashMap<String, InventoryTagBean> mTagMap = new LinkedHashMap<>();
//        private String mLastItem = "1";

        private Reader mReader;
        private volatile int mSaveId;
        private volatile long mCmdStartTime;
        //        private ItemListener mL = new ItemListener() {
//
//            @Override
//            public void itemStateChanged(ItemEvent e) {
//                String item = (String) e.getItem();
//                if (item.equals(mLastItem)) {
//                    mLastItem = item;
//                    System.out.println("->" + mLastItem);
//                }
//            }
//        };

        private ActionListener al = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                switch (e.getActionCommand()) {
                    case connectStr: {
                        connect();
                    }
                    break;
                    case disconnectStr: {
                        disconnect();
                    }
                    break;
                    case startStr: {
                        startInventory();
                    }
                    break;
                    case stopStr: {
                        stopInventory();/*stop*/
                    }
                    break;
                    case showListStr: {
                        if (mTagMap.size() > 0) {
                            Set<String> keySet = mTagMap.keySet();
                            for (String key : keySet) {
                                System.out.println("------" + mTagMap.get(key));
                            }
                            System.out.println("------Show List OK !");
                        } else {
                            System.out.println("------List: 0");
                        }
                    }
                    break;
                }
            }
        };

        //<editor-fold desc="createWinUI">
        private void createWinUI() {
//            jcb.addItemListener(mL);
//            jcb.addItem("1");
//            jcb.addItem("4");
//            jcb.addItem("8");
//            jcb.addItem("16");

            btnConnect.addActionListener(al);
            btnStart.addActionListener(al);
            btnShowList.addActionListener(al);

            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();

            JFrame jf = new JFrame("ReadLabelDemo");
            jf.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            jf.setAlwaysOnTop(true);
            jf.setSize(512, 128);
            jf.setLayout(new FlowLayout());
//            jf.add(jcb);
            jf.add(lbVer);
            jf.add(btnConnect);
            jf.add(btnStart);
            jf.add(lbCount);
            jf.add(btnShowList);
            jf.setLocation((int) (screenSize.width / 2.5), screenSize.height / 3);
            jf.setVisible(true);
            jf.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    disconnect();
                    System.exit(0);
                }
            });
        }
        //</editor-fold>

        public WinDemo() {
            createWinUI();
        }
        //</editor-fold>

        private boolean connect() {
            ConnectHandle handle = new JSerialPortHandle(COM_PORT, 115200);/*78*/
//            ConnectHandle handle = new NetworkHandle("192.168.0.178", 4001);/*34*/
            mReader = ReaderImpl.create(antCount);
            boolean linkSuccess = mReader.connect(handle);
            if (!linkSuccess) {
                System.err.println(LLLog.getStackTrace(2) + "\nReader connect fail...");
                return false;
            }
//            mReader.setReconnectByTimeoutTimes(3);/*默认止3，每出现3次超时，重连一次。0 表示不重连*/
            setInventoryConfig();

//            mReader.setCommandStatusCallback(new Consumer<CmdStatus>() {
//                @Override
//                public void accept(CmdStatus cmdStatus) throws Exception {
//                    String cmdName = Cmd.getNameForCmd(cmdStatus.getCmd());
//                    String codeName = ResultCode.getNameForResultCode(cmdStatus.getStatus());
//                    System.out.println("CommandStatus: " + cmdName + " is " + codeName);
//                }
//            });
            mReader.addOriginalDataReceivedCallback(new Consumer<byte[]>() {
                @Override
                public void accept(byte[] bytes) throws Exception {
                    /* no op*/
                }

                @Override
                public void onUnknownArr(byte[] bytes) throws Exception {
                    System.err.println("---onUnknownArr:" + ArrayUtils.bytesToHexString(bytes, 0, bytes.length));
                }
            });
            mReader.setOriginalDataCallback(new Consumer<byte[]>() {
                @Override
                public void accept(byte[] onSend) throws Exception {
                    if (VERBOSE) {
                        String hexString = ArrayUtils.bytesToHexString(onSend, 0, onSend.length);
                        System.out.println("---reader send :" + hexString);
                    }
//                endMs = System.currentTimeMillis();
                }
            }, new Consumer<byte[]>() {
                @Override
                public void accept(byte[] onReceive) throws Exception {
                    if (VERBOSE) {
                        String hexString = ArrayUtils.bytesToHexString(onReceive, 0, onReceive.length);
                        System.out.println("===reader recv:" + hexString);
                    }
//                long l = System.currentTimeMillis() - endMs;
//                System.out.println(formatSeconds((int) (l / 1000.0)));
                }
            });
            mReader.getFirmwareVersion(new Consumer<Version>() {
                @Override
                public void accept(Version version) throws Exception {
                    String s = version.getChipType() + " V" + version.getVersion();
                    lbVer.setText(s);
                    btnConnect.setText(disconnectStr);
                }
            }, failureConsumer);

            mReader.setOutputPowerUniformly(power, new Consumer<Success>() {
                @Override
                public void accept(Success success) throws Exception {
                    lbVer.setText(lbVer.getText() + ", power:" + power);
                }
            }, failureConsumer);
            return true;
        }

        private void disconnect() {
            System.out.println("disconnect!-----------------------------------------");

            btnConnect.setText(connectStr);
            btnStart.setText(startStr);
            btnStart.setSelected(false);

            if (mReader != null) {
                mReader.disconnect();
            }
        }

        private BaseInventory getInventory() {
            BaseInventory inventory = null;
            if (fastSwitchAnt) {
                switch (antCount) {
                    case SINGLE_CHANNEL:
                        inventory = new FastSwitchSingleAntennaInventory.Builder()
                                .session(session)
                                .target(target)
                                .enablePhase(enablePhase)
                                .repeat(repeat)
                                .build();
                        break;
                    case FOUR_CHANNELS:
                        inventory = new FastSwitchFourAntennaInventory.Builder()
                                .antennaA(FourAntenna.ANT_A).stayA((byte) 1)
                                .antennaB(FourAntenna.ANT_B).stayB((byte) 1)
                                .antennaC(FourAntenna.ANT_C).stayC((byte) 1)
                                .antennaD(FourAntenna.ANT_D).stayD((byte) 1)
                                .session(session)
                                .target(target)
                                .enablePhase(enablePhase)
                                .repeat(repeat)
                                .build();
                        break;
                    case EIGHT_CHANNELS:
                        inventory = new FastSwitchEightAntennaInventory.Builder()
                                .antennaA(EightAntenna.ANT_A).stayA((byte) 1)
                                .antennaB(EightAntenna.ANT_B).stayB((byte) 1)
                                .antennaC(EightAntenna.ANT_C).stayC((byte) 1)
                                .antennaD(EightAntenna.ANT_D).stayD((byte) 1)
                                .antennaE(EightAntenna.ANT_E).stayE((byte) 1)
                                .antennaF(EightAntenna.ANT_F).stayF((byte) 1)
                                .antennaG(EightAntenna.ANT_G).stayG((byte) 1)
                                .antennaH(EightAntenna.ANT_H).stayH((byte) 1)
                                .session(session)
                                .target(target)
                                .enablePhase(enablePhase)
                                .repeat(repeat)
                                .build();
                        break;
                    case SIXTEEN_CHANNELS:
                        inventory = new FastSwitchSixteenAntennaInventory.Builder()
                                .antennaA(EightAntenna.ANT_A).stayA((byte) 1)
                                .antennaB(EightAntenna.ANT_B).stayB((byte) 1)
                                .antennaC(EightAntenna.ANT_C).stayC((byte) 1)
                                .antennaD(EightAntenna.ANT_D).stayD((byte) 1)
                                .antennaE(EightAntenna.ANT_E).stayE((byte) 1)
                                .antennaF(EightAntenna.ANT_F).stayF((byte) 1)
                                .antennaG(EightAntenna.ANT_G).stayG((byte) 1)
                                .antennaH(EightAntenna.ANT_H).stayH((byte) 1)
                                .antennaI(HighEightAntenna.ANT_I).stayI((byte) 1)
                                .antennaJ(HighEightAntenna.ANT_J).stayJ((byte) 1)
                                .antennaK(HighEightAntenna.ANT_K).stayK((byte) 1)
                                .antennaL(HighEightAntenna.ANT_L).stayL((byte) 1)
                                .antennaM(HighEightAntenna.ANT_M).stayM((byte) 1)
                                .antennaN(HighEightAntenna.ANT_N).stayN((byte) 1)
                                .antennaO(HighEightAntenna.ANT_O).stayO((byte) 1)
                                .antennaP(HighEightAntenna.ANT_P).stayP((byte) 1)
                                .session(session)
                                .target(target)
                                .enablePhase(enablePhase)
                                .repeat(repeat)
                                .build();
                        break;
                }
            } else {
                inventory = new CustomSessionTargetInventory.Builder()
                        .session(session)
                        .target(target)
                        .enablePhase(enablePhase)
                        .repeat(repeat)
                        .build();
            }
            return inventory;
        }

        private void setInventoryConfig() {
            Consumer<InventoryTag> inventoryTagSuccess = tag -> onRecv(tag);
            Consumer<InventoryTagEnd> inventoryTagEndSuccess = tagEnd -> onEnd(tagEnd);
            Consumer<InventoryFailure> inventoryFailure = failure -> onFailure(failure);

            BaseInventory inventory = getInventory();
            InventoryConfig inventoryConfig = new InventoryConfig.Builder()
                    .setInventoryParam(param)
                    .setInventory(inventory)
                    .setOnInventoryTagSuccess(inventoryTagSuccess)
                    .setOnInventoryTagEndSuccess(inventoryTagEndSuccess)
                    .setOnFailure(inventoryFailure)
                    .build();
            mReader.setInventoryConfig(inventoryConfig);
        }

        private void onRecv(InventoryTag tag) {
            String tagEpc = tag.getEpc();
            InventoryTagBean bean = mTagMap.get(tagEpc);
            if (bean != null) {
                bean.addTimes();
//                System.out.println("exist tag: " + tagBean);
            } else {
                InventoryTagBean tagBean = new InventoryTagBean(tag, mTagMap.size());
                String epc = tagBean.getEpc();
                mTagMap.put(epc, tagBean);
//                System.out.println("accept tag: " + tagBean);
            }
            lbCount.setText(countStr + mTagMap.size());
            /* Enviar tag via HTTP (Windows) */
            sendTag(tag, extractCodeFromTag(tag));
        }

        private void onEnd(InventoryTagEnd tagEnd) {
            endLog(tagEnd);
            boolean isGroupEnd = isGroupEnd(tagEnd);

            boolean finished = tagEnd.isFinished();
            if (finished) {
                stopInventory();
                return;
            }

            if (fastSwitchAnt) {
                return;
            }

            doNextAnt(true);/*onEnd*/
        }

        private void endLog(InventoryTagEnd inventoryTagEnd) {
            String antMsg = "] AntGroup:[" + inventoryTagEnd.getAntennaGroupId();
            if (!fastSwitchAnt) {
                antMsg = "] AntId:[" + inventoryTagEnd.getCurrentAnt();
            }
            int size = mTagMap.size();
            StringBuilder sb = new StringBuilder(LLLog.getStackTrace(3))
                    .append("\nTime:[").append(mSdf.format(new Date())).append("]")
                    .append("ID:[").append(mSaveId++).append(antMsg).append("]")
                    .append("Read Count:[").append(inventoryTagEnd.getTotalRead()).append("]")
                    .append("Read Rate:[").append(inventoryTagEnd.getReadRate()).append("]")
                    .append("Spend:[").append(System.currentTimeMillis() - mCmdStartTime).append("ms]")
                    .append("Real Total:[").append(size).append("]").append("\n");

            String msg = sb.toString();
            System.out.println(msg + "-----------------------------------------------------------------\n");
        }

        private void onFailure(InventoryFailure failure) {
            int antId = failure.getAntId();
            String cmdStr = Cmd.getNameForCmd(failure.getCmd());
            String resultCodeStr = ResultCode.getNameForResultCode(failure.getErrorCode());

            System.err.println("OnFailure: AntId(" + antId + ") " + cmdStr + "-->" + resultCodeStr);
            if (fastSwitchAnt) {
                return;
            }
            doNextAnt(true);/*onFailure*/
        }

        private boolean isGroupEnd(InventoryTagEnd inventoryTagEnd) {
            boolean isGroupEnd;
            if (fastSwitchAnt) {
                if (mReader.getAntennaCount() == AntennaCount.SIXTEEN_CHANNELS) {
                    int antennaGroupId = inventoryTagEnd.getAntennaGroupId();
                    isGroupEnd = antennaGroupId == 1;
                } else {
                    isGroupEnd = true;
                }
            } else {
                isGroupEnd = param.isLastAnt();
            }
            return isGroupEnd;
        }

        private void startInventory() {
            if (mReader == null) {
                System.out.println("mReader is null ...");
                return;
            }
            if (btnStart.isSelected()) {
                return;
            }
            btnStart.setSelected(true);
            btnStart.setText(stopStr);
            mTagMap.clear();
            mSaveId = 1;

            doNextAnt(false);/*startInventory*/
        }

        private void stopInventory() {
            if (!btnStart.isSelected()) {
                return;
            }
            System.out.println("stopInventory!");

            btnStart.setText(startStr);
            btnStart.setSelected(false);

            if (mReader != null) {
                mReader.stopInventory();
            }
        }

        private void doNextAnt(boolean nextAnt) {
            if (startStr.equals(btnStart.getText())) {
                System.out.println("stopped...");
                return;
            }

            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs * 1000);
                } catch (InterruptedException ignored) {
                }
            }

            mCmdStartTime = System.currentTimeMillis();

            int workAntId = param.getAntennaId(nextAnt);
            System.out.println("setWorkAntenna:" + workAntId);
            mReader.setWorkAntenna(workAntId, successAntConsumer, failureAntConsumer);
        }

        /* 设置盘存天线---------------------------------------- */
        Consumer<Success> successAntConsumer = success -> mReader.startInventory();
        Consumer<Failure> failureAntConsumer = failure -> doNextAnt(true);

        public String formatSeconds(int totalSeconds) {
            if (totalSeconds < 1) {
                return "00:01";
            }

            int hours = totalSeconds / 3600;

            int rem = totalSeconds % 3600;
            int minutes = rem / 60;
            int seconds = rem % 60;
            if (hours <= 0) {
                return String.format("%02d.%02d", minutes, seconds);
            }
            return String.format("%02d.%2d.%02d", hours, minutes, seconds);
        }
    }

    //<editor-fold desc="LinuxDemo">
    private static class LinuxDemo {
        private Reader mReader;
        private String mPortName = "ttyUSB0";

        public LinuxDemo() {
            String dir = System.getProperty("user.dir");
            System.out.println("Current Dir:" + dir);

            File file = new File("", "CFG.txt");
            try (BufferedReader br = new BufferedReader(new FileReader(file));) {
                String str = br.readLine();
                if (str != null && str.length() > 0) {
                    mPortName = str;
                }
                System.out.println("str=" + str);
            } catch (Exception e) {
                System.err.println(e.getMessage());
            }

            /* overrides via props/env or scan if necessário */
            String fromPropsEnv = resolveLinuxPortFromPropsEnv();
            if (fromPropsEnv != null && fromPropsEnv.trim().length() > 0) {
                mPortName = fromPropsEnv.trim();
            } else if (mPortName == null || mPortName.trim().isEmpty()) {
                mPortName = resolveLinuxPortByScan();
            }
            System.out.println("Using serial port: " + mPortName);
        }

        private boolean connect() {
//            NetworkHandle handle = new NetworkHandle("169.254.252.120", 4001);/*34*/
            ConnectHandle handle = new JSerialPortHandle(mPortName, 115200);/*78*/

            mReader = ReaderImpl.create(antCount);
            boolean linkSuccess = mReader.connect(handle);
            if (!linkSuccess) {
                System.err.println(LLLog.getStackTrace(2) + "\nReader connect fail...");
                if (uiNotifier != null) {
                    UiNotifier n = uiNotifier;
                    SwingUtilities.invokeLater(() -> n.onConnectStatus(false));
                }
                return false;
            }
            /* Reconnect automático em timeouts - aumentar para 10 tentativas */
            try {
                mReader.setReconnectByTimeoutTimes(10);
                System.out.println("Reconexão automática configurada: 10 tentativas por timeout");
            } catch (Throwable e) {
                System.err.println("Erro ao configurar reconexão automática: " + e.getMessage());
            }
            if (uiNotifier != null) {
                UiNotifier n = uiNotifier;
                SwingUtilities.invokeLater(() -> n.onConnectStatus(true));
            }
            inventoryParamConfig();

            mReader.addOriginalDataReceivedCallback(new Consumer<byte[]>() {
                @Override
                public void accept(byte[] bytes) throws Exception {
                    /* no op*/
                }

                @Override
                public void onUnknownArr(byte[] bytes) throws Exception {
                    if (VERBOSE) {
                        System.err.println("---onUnknownArr:" + ArrayUtils.bytesToHexString(bytes, 0, bytes.length));
                    }
                }
            });
            mReader.setOriginalDataCallback(new Consumer<byte[]>() {
                @Override
                public void accept(byte[] onSend) throws Exception {
                    if (VERBOSE) {
                        String hexString = ArrayUtils.bytesToHexString(onSend, 0, onSend.length);
                        System.out.println("---reader send :" + hexString);
                    }
//                endMs = System.currentTimeMillis();
                }
            }, new Consumer<byte[]>() {
                @Override
                public void accept(byte[] onReceive) throws Exception {
                    if (VERBOSE) {
                        System.out.println("===reader recv:" + ArrayUtils.bytesToHexString(onReceive, 0, onReceive.length));
                    }
//                long l = System.currentTimeMillis() - endMs;
//                System.out.println(formatSeconds((int) (l / 1000.0)));
                }
            });
            mReader.getFirmwareVersion(new Consumer<Version>() {
                @Override
                public void accept(Version version) throws Exception {
                    String s = version.getChipType() + " V" + version.getVersion();
                    System.out.println(s);
                }
            }, failureConsumer);

            mReader.setOutputPowerUniformly(power, new Consumer<Success>() {
                @Override
                public void accept(Success success) throws Exception {
                    System.out.println("power:" + power);
                }
            }, failureConsumer);

            return true;
        }

        //<editor-fold desc="inventoryParamConfig">
        private void inventoryParamConfig() {
            BaseInventory inventory = null;
            if (fastSwitchAnt) {
                switch (antCount) {
                    case SINGLE_CHANNEL:
                        inventory = new FastSwitchSingleAntennaInventory.Builder()
                                .session(session)
                                .target(target)
                                .enablePhase(enablePhase)
                                .repeat(repeat)
                                .build();
                        break;
                    case FOUR_CHANNELS:
                        inventory = new FastSwitchFourAntennaInventory.Builder()
                                .antennaA(FourAntenna.ANT_A).stayA((byte) 1)
                                .antennaB(FourAntenna.ANT_B).stayB((byte) 1)
                                .antennaC(FourAntenna.ANT_C).stayC((byte) 1)
                                .antennaD(FourAntenna.ANT_D).stayD((byte) 1)
                                .session(session)
                                .target(target)
                                .enablePhase(enablePhase)
                                .repeat(repeat)
                                .build();
                        break;
                    case EIGHT_CHANNELS:
                        inventory = new FastSwitchEightAntennaInventory.Builder()
                                .antennaA(EightAntenna.ANT_A).stayA((byte) 1)
                                .antennaB(EightAntenna.ANT_B).stayB((byte) 1)
                                .antennaC(EightAntenna.ANT_C).stayC((byte) 1)
                                .antennaD(EightAntenna.ANT_D).stayD((byte) 1)
                                .antennaE(EightAntenna.ANT_E).stayE((byte) 1)
                                .antennaF(EightAntenna.ANT_F).stayF((byte) 1)
                                .antennaG(EightAntenna.ANT_G).stayG((byte) 1)
                                .antennaH(EightAntenna.ANT_H).stayH((byte) 1)
                                .session(session)
                                .target(target)
                                .enablePhase(enablePhase)
                                .repeat(repeat)
                                .build();
                        break;
                    case SIXTEEN_CHANNELS:
                        inventory = new FastSwitchSixteenAntennaInventory.Builder()
                                .antennaA(EightAntenna.ANT_A).stayA((byte) 1)
                                .antennaB(EightAntenna.ANT_B).stayB((byte) 1)
                                .antennaC(EightAntenna.ANT_C).stayC((byte) 1)
                                .antennaD(EightAntenna.ANT_D).stayD((byte) 1)
                                .antennaE(EightAntenna.ANT_E).stayE((byte) 1)
                                .antennaF(EightAntenna.ANT_F).stayF((byte) 1)
                                .antennaG(EightAntenna.ANT_G).stayG((byte) 1)
                                .antennaH(EightAntenna.ANT_H).stayH((byte) 1)
                                .antennaI(HighEightAntenna.ANT_I).stayI((byte) 1)
                                .antennaJ(HighEightAntenna.ANT_J).stayJ((byte) 1)
                                .antennaK(HighEightAntenna.ANT_K).stayK((byte) 1)
                                .antennaL(HighEightAntenna.ANT_L).stayL((byte) 1)
                                .antennaM(HighEightAntenna.ANT_M).stayM((byte) 1)
                                .antennaN(HighEightAntenna.ANT_N).stayN((byte) 1)
                                .antennaO(HighEightAntenna.ANT_O).stayO((byte) 1)
                                .antennaP(HighEightAntenna.ANT_P).stayP((byte) 1)
                                .session(session)
                                .target(target)
                                .enablePhase(enablePhase)
                                .repeat(repeat)
                                .build();
                        break;
                }
            } else {
                inventory = new CustomSessionTargetInventory.Builder()
                        .session(session)
                        .target(target)
                        .enablePhase(enablePhase)
                        .repeat(repeat)
                        .build();
            }

            InventoryConfig config = new InventoryConfig.Builder()
                    .setInventory(inventory)
                    .setOnInventoryTagSuccess(new Consumer<InventoryTag>() {
                        @Override
                        public void accept(InventoryTag tag) throws Exception {
                            /* Atualiza UI com a tag detectada e envia para API */
                            String code = extractCodeFromTag(tag);
                            if (uiNotifier != null) {
                                UiNotifier n = uiNotifier;
                                SwingUtilities.invokeLater(() -> n.onTagDetected(code));
                            }
                            /* Enfileirar para evitar bloquear callback do leitor */
                            enqueueSend(code);
                        }
                    }).setOnInventoryTagEndSuccess(new Consumer<InventoryTagEnd>() {
                        @Override
                        public void accept(InventoryTagEnd tagEnd) throws Exception {
                            if (VERBOSE) {
                                System.out.println("tag: " + tagEnd.toString());
                            }

                            if (fastSwitchAnt) {
                                return;
                            }

                            doNextAnt(true);/*onEnd*/
                        }
                    }).setOnFailure(new Consumer<InventoryFailure>() {
                        @Override
                        public void accept(InventoryFailure failure) throws Exception {
                            byte cmd = failure.getCmd();
                            int antId = failure.getAntId();
                            byte errorCode = failure.getErrorCode();

                            String cmdStr = Cmd.getNameForCmd(cmd);
                            String resultCodeStr = ResultCode.getNameForResultCode(errorCode);
                            System.err.println("OnFailure: AntId(" + antId + ") " + cmdStr + "-->" + resultCodeStr);
                            
                            // Se for timeout, tentar reconectar
                            if (resultCodeStr != null && (resultCodeStr.contains("TIMEOUT") || resultCodeStr.contains("Timeout"))) {
                                System.err.println("Timeout detectado - tentando reconectar...");
                                // A reconexão automática deve ser tratada pelo setReconnectByTimeoutTimes
                                // Mas podemos forçar uma verificação
                                try {
                                    if (mReader != null) {
                                        // Não desconectar, deixar a reconexão automática funcionar
                                        System.out.println("Aguardando reconexão automática...");
                                    }
                                } catch (Exception e) {
                                    System.err.println("Erro ao tentar reconectar: " + e.getMessage());
                                }
                            }
                        }
                    }).build();
            mReader.setInventoryConfig(config);
        }

        //</editor-fold>

        private void startInventory() {
            if (mReader == null) {
                System.out.println("mReader is null ...");
                return;
            }

            if (uiNotifier != null) {
                UiNotifier n = uiNotifier;
                SwingUtilities.invokeLater(() -> n.onReadingStatus(true));
            }

            doNextAnt(false);/*startInventory*/
        }

        private void doNextAnt(boolean nextAnt) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs * 1000);
                } catch (InterruptedException ignored) {
                }
            }

            int workAntId = param.getAntennaId(nextAnt);
            System.out.println("setWorkAntenna:" + workAntId);
            mReader.setWorkAntenna(workAntId, successAntConsumer, failureAntConsumer);
        }

        Consumer<Success> successAntConsumer = success -> mReader.startInventory();
        Consumer<Failure> failureAntConsumer = failure -> doNextAnt(true);

        private void stopInventory() {
            System.out.println("stopInventory!");
            if (mReader != null) {
                mReader.stopInventory();
            }
        }
    }
    //</editor-fold>

    //<editor-fold desc="ThreadLocal SimpleDateFormat">
    private static final ThreadLocal<Map<String, SimpleDateFormat>> SDF_THREAD_LOCAL = new ThreadLocal<Map<String, SimpleDateFormat>>() {
        @Override
        protected Map<String, SimpleDateFormat> initialValue() {
            return new HashMap<>();
        }
    };

    public static SimpleDateFormat getSafeDateFormat(String pattern) {
        Map<String, SimpleDateFormat> sdfMap = SDF_THREAD_LOCAL.get();
        //No Inspection ConstantConditions
        SimpleDateFormat simpleDateFormat = sdfMap.get(pattern);
        if (simpleDateFormat == null) {
            simpleDateFormat = new SimpleDateFormat(pattern);
            sdfMap.put(pattern, simpleDateFormat);
        }
        return simpleDateFormat;
    }
    //        LLLog.setLogLs(new LLLog.OnLogL() {
//            @Override
//            public void onLogI(String s) {
//                System.out.println("onLogI->" + s);
//            }
//
//            @Override
//            public void onLogW(String s) {
//                System.out.println("onLogW->" + s);
//            }
//
//            @Override
//            public void onLogE(String s) {
//                System.err.println("onLogE->" + s);
//            }
//        });
    //</editor-fold>
}