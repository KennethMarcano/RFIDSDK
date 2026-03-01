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

// Pi4J v2 imports
import com.pi4j.Pi4J;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;

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
    private static final String ENDPOINT_URL_CONSULTAR_MOVIMENTACAO = "https://fulle.eship.com.br/v3/?api&funcao=webServicePostMovimentacao";
    private static final String API_TOKEN = "P";/* configure aqui (ex.: "eyJ...")
    /* HTTP/REST Config ↑↑↑ */

    /* GPIO Pi4J v2 (Raspberry Pi) ↓↓↓ */
    private static final int GPIO_PIN_BCM = 21; /* GPIO21 (BCM numbering) */
    private static DigitalOutput gpioOutput = null;
    private static Pi4J pi4j = null;
    /* GPIO Pi4J v2 ↑↑↑ */

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
    private static volatile JTextField tfPower;
    private static volatile JLabel lbStatusEquipamento;
    private static volatile JLabel lbStatusDestinoLocal;
    private static volatile JButton btnAtualizarAlternativas;
    
    /* Referência estática para LinuxDemo para poder atualizar power */
    private static volatile LinuxDemo linuxDemoInstance = null;

    /* Enfileiramento para evitar trabalho pesado no callback do leitor */
    private static final int HTTP_QUEUE_CAPACITY = 256;
    private static final java.util.concurrent.BlockingQueue<String> httpQueue =
            new java.util.concurrent.ArrayBlockingQueue<>(HTTP_QUEUE_CAPACITY);
    private static volatile boolean httpWorkerStarted = false;
    
    /* Tempo mínimo em segundos entre envios HTTP para a mesma tag */
    private static final int TEMPO_MINIMO_ENTRE_ENVIOS_SEGUNDOS = 5; /* configure aqui */
    
    /* Variável para armazenar a última tag lida e evitar duplicados */
    private static volatile String ultimaTagLida = null;
    
    /* Variável para armazenar a última tag que teve sucesso na API */
    private static volatile String ultimaTagComSucesso = null;
    
    /* Flag para indicar se a última tag teve sucesso */
    private static volatile boolean ultimaTagTeveSucesso = false;
    
    /* Classe para representar histórico de envio HTTP com timestamp da última leitura */
    private static class HistoricoEnvioHttp {
        private final String tag;
        private long timestampUltimaLeitura; // Timestamp da última leitura da tag
        private long timestampUltimoEnvio;   // Timestamp do último envio HTTP
        
        public HistoricoEnvioHttp(String tag, long timestampLeitura) {
            this.tag = tag;
            this.timestampUltimaLeitura = timestampLeitura;
            this.timestampUltimoEnvio = timestampLeitura;
        }
        
        public String getTag() { return tag; }
        public long getTimestampUltimaLeitura() { return timestampUltimaLeitura; }
        public long getTimestampUltimoEnvio() { return timestampUltimoEnvio; }
        
        public void atualizarTimestampLeitura(long timestamp) {
            this.timestampUltimaLeitura = timestamp;
        }
        
        public void atualizarTimestampEnvio(long timestamp) {
            this.timestampUltimoEnvio = timestamp;
            this.timestampUltimaLeitura = timestamp;
        }
    }
    
    /* Map thread-safe para armazenar histórico de envios HTTP por tag */
    private static final java.util.Map<String, HistoricoEnvioHttp> historicoEnvioHttp = 
        java.util.Collections.synchronizedMap(new java.util.HashMap<>());
    
    /* Classe para representar uma entrada no histórico de tags */
    private static class TagHistorico {
        private final String tag;
        private final long timestamp;
        private final boolean sucesso;
        private final String mensagem;
        
        public TagHistorico(String tag, boolean sucesso, String mensagem) {
            this.tag = tag;
            this.timestamp = System.currentTimeMillis();
            this.sucesso = sucesso;
            this.mensagem = mensagem;
        }
        
        public String getTag() { return tag; }
        public long getTimestamp() { return timestamp; }
        public boolean isSucesso() { return sucesso; }
        public String getMensagem() { return mensagem; }
        
        @Override
        public String toString() {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            return String.format("[%s] Tag: %s | %s | %s", 
                sdf.format(new java.util.Date(timestamp)), 
                tag, 
                sucesso ? "SUCESSO" : "ERRO", 
                mensagem);
        }
    }
    
    /* Lista thread-safe para armazenar histórico de tags */
    private static final java.util.List<TagHistorico> historicoTags = 
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    
    /* Método para obter uma cópia do histórico de tags (thread-safe) */
    public static java.util.List<TagHistorico> getHistoricoTags() {
        synchronized (historicoTags) {
            return new java.util.ArrayList<>(historicoTags);
        }
    }
    
    /* Método para obter o tamanho do histórico */
    public static int getHistoricoTagsSize() {
        return historicoTags.size();
    }

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
    
    /* Função para construir JSON body para webServicePostMovimentacao */
    private static String buildConsultarMovimentacaoJsonBody(String codigoIdentificador) {
        // Adicionar "(02)" no início do código identificador
        String codigoComPrefixo = "(02)" + safeString(codigoIdentificador);
        StringBuilder sb = new StringBuilder(256);
        sb.append("{")
                .append("\"codArmazem\":\"").append("03").append("\",")
                .append("\"equipamentoId\":\"").append("326").append("\",")
                .append("\"codigoIdentificador\":\"").append(escapeJson(codigoComPrefixo)).append("\",")
                .append("\"parametros\":\"").append("{}").append("\",")
                .append("\"idStatus\":").append("null")
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
    
    /* Função para carregar variáveis do arquivo .env */
    private static java.util.Map<String, String> loadEnvFile() {
        java.util.Map<String, String> envMap = new java.util.HashMap<>();
        try {
            java.io.File envFile = new java.io.File(".env");
            if (!envFile.exists()) {
                // Tentar na raiz do projeto
                envFile = new java.io.File("src/.env");
            }
            if (!envFile.exists()) {
                return envMap; // Retorna mapa vazio se arquivo não existir
            }
            
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(envFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    // Ignorar linhas vazias e comentários
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    // Formato: KEY=VALUE ou KEY="VALUE"
                    int eqIndex = line.indexOf('=');
                    if (eqIndex > 0) {
                        String key = line.substring(0, eqIndex).trim();
                        String value = line.substring(eqIndex + 1).trim();
                        // Remover aspas se existirem
                        if ((value.startsWith("\"") && value.endsWith("\"")) || 
                            (value.startsWith("'") && value.endsWith("'"))) {
                            value = value.substring(1, value.length() - 1);
                        }
                        envMap.put(key, value);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Erro ao ler arquivo .env: " + e.getMessage());
        }
        return envMap;
    }
    
    /* Função para atualizar power no módulo se estiver conectado */
    private static void updatePowerIfConnected() {
        if (linuxDemoInstance != null && linuxDemoInstance.mReader != null) {
            try {
                // Usar Consumer de sucesso e falha
                Consumer<Success> successConsumer = success -> {
                    System.out.println("Power atualizado com sucesso: " + power);
                };
                Consumer<Failure> failureConsumer = failure -> {
                    String codeName = Failure.getNameForResultCode(failure.getErrorCode());
                    System.err.println("Erro ao atualizar power: " + codeName + " (código: " + failure.getErrorCode() + ")");
                };
                linuxDemoInstance.mReader.setOutputPowerUniformly(power, successConsumer, failureConsumer);
            } catch (Exception e) {
                System.err.println("Erro ao atualizar power: " + e.getMessage());
            }
        } else {
            System.out.println("Módulo não conectado - power será aplicado na próxima conexão");
        }
    }

    /* ---------------------- GPIO Pi4J v2 helpers ---------------------- */
    private static void gpioInitIfLinux() {
        String os = safeString(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return;
        try {
            // Inicializar Pi4J
            pi4j = Pi4J.newAutoContext();
            
            // Criar configuração para GPIO21 como saída digital
            var config = DigitalOutput.newConfigBuilder(pi4j)
                    .id("GPIO" + GPIO_PIN_BCM)
                    .name("Tag Detection LED")
                    .address(GPIO_PIN_BCM)
                    .shutdown(DigitalState.LOW)
                    .initial(DigitalState.LOW)
                    .provider("pigpio-digital-output");
            
            // Criar o output digital
            gpioOutput = pi4j.dio().create(config);
            System.out.println("GPIO" + GPIO_PIN_BCM + " inicializado como saída (Pi4J v2)");
        } catch (Exception e) {
            System.err.println("GPIO Pi4J v2 init error: " + e.getMessage());
            e.printStackTrace();
            gpioOutput = null;
            pi4j = null;
        }
    }
    
    /* Piscar LED quando tag é detectada */
    private static void gpioBlinkOnTagDetected() {
        if (gpioOutput == null) return;
        try {
            // Ativar o LED
            gpioOutput.high();
            // Desativar após 100ms em thread separada
            new Thread(() -> {
                try {
                    Thread.sleep(100);
                    if (gpioOutput != null) {
                        gpioOutput.low();
                    }
                } catch (InterruptedException ignored) {
                } catch (Exception e) {
                    System.err.println("Erro ao desativar GPIO: " + e.getMessage());
                }
            }, "gpio-tag-blink").start();
        } catch (Exception e) {
            System.err.println("GPIO blink error: " + e.getMessage());
        }
    }
    
    /* Alternar estado do GPIO (para teste) */
    private static void gpioToggle() {
        if (gpioOutput == null) {
            System.err.println("GPIO não inicializado");
            return;
        }
        try {
            DigitalState currentState = gpioOutput.state();
            if (currentState == DigitalState.HIGH) {
                gpioOutput.low();
                System.out.println("GPIO" + GPIO_PIN_BCM + " desativado");
            } else {
                gpioOutput.high();
                System.out.println("GPIO" + GPIO_PIN_BCM + " ativado");
            }
        } catch (Exception e) {
            System.err.println("GPIO toggle error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /* Limpar recursos GPIO */
    private static void gpioShutdown() {
        try {
            if (gpioOutput != null) {
                gpioOutput.low();
                gpioOutput.shutdown(DigitalState.LOW);
                gpioOutput = null;
            }
            if (pi4j != null) {
                pi4j.shutdown();
                pi4j = null;
            }
        } catch (Exception e) {
            System.err.println("GPIO shutdown error: " + e.getMessage());
        }
    }
    /* -------------------- GPIO Pi4J v2 helpers (end) -------------------- */

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
        
        // Procurar pelo campo "erros" (plural) - estrutura: "erros": [...]
        int keyErros = body.indexOf("\"erros\"");
        
        // Se não encontrar "erros", verificar se existe "erro" (singular)
        if (keyErros < 0) {
            int keyErro = body.indexOf("\"erro\"");
            if (keyErro < 0) {
                // Se não encontrar "erro" nem "erros", assumir que não há erros (sucesso)
                return false;
            }
            // Se encontrou "erro" (singular), verificar se tem conteúdo
            int colon = body.indexOf(':', keyErro);
            if (colon < 0) return false;
            
            int valueStart = colon + 1;
            while (valueStart < body.length() && Character.isWhitespace(body.charAt(valueStart))) {
                valueStart++;
            }
            if (valueStart >= body.length()) return false;
            
            String remaining = body.substring(valueStart);
            if (remaining.startsWith("null")) {
                return false; // null significa sem erros
            }
            // Se é um objeto ou string não vazia, há erro
            return !remaining.startsWith("[]") && remaining.trim().length() > 0;
        }
        
        // Encontrou "erros", verificar o valor
        int colon = body.indexOf(':', keyErros);
        if (colon < 0) return false;
        
        // Pular espaços após os dois pontos
        int valueStart = colon + 1;
        while (valueStart < body.length() && Character.isWhitespace(body.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart >= body.length()) return false;
        
        // Verificar o valor após "erros:"
        String remaining = body.substring(valueStart);
        
        // Se é null, não há erros
        if (remaining.startsWith("null")) {
            return false;
        }
        
        // Se é um array vazio [], não há erros
        if (remaining.startsWith("[]")) {
            return false;
        }
        
        // Se é um array com conteúdo, verificar se tem pelo menos um objeto com "erro"
        if (remaining.startsWith("[")) {
            // Procurar pelo fechamento do array
            int bracketEnd = body.indexOf(']', valueStart);
            if (bracketEnd < 0) return false;
            
            // Extrair o conteúdo do array
            String arrayContent = body.substring(valueStart + 1, bracketEnd).trim();
            
            // Se o array está vazio (apenas espaços), não há erros
            if (arrayContent.isEmpty()) {
                return false;
            }
            
            // Verificar se há pelo menos um objeto com "erro" dentro do array
            // Estrutura esperada: [{"erro": {"mensagem": "...", "codigo": "..."}}]
            // Procurar por "erro" dentro do conteúdo do array
            int erroKey = arrayContent.indexOf("\"erro\"");
            if (erroKey >= 0) {
                // Encontrou "erro" dentro do array, verificar se tem conteúdo
                int erroColon = arrayContent.indexOf(':', erroKey);
                if (erroColon >= 0) {
                    int erroValueStart = erroColon + 1;
                    while (erroValueStart < arrayContent.length() && Character.isWhitespace(arrayContent.charAt(erroValueStart))) {
                        erroValueStart++;
                    }
                    if (erroValueStart < arrayContent.length()) {
                        String erroValue = arrayContent.substring(erroValueStart).trim();
                        // Se o valor de "erro" não é null e tem conteúdo, há erro
                        if (!erroValue.startsWith("null") && erroValue.length() > 0) {
                            return true; // Há erro
                        }
                    }
                }
            }
            
            // Se o array tem conteúdo mas não encontrou "erro" estruturado,
            // verificar se há qualquer conteúdo não vazio
            return arrayContent.length() > 0;
        }
        
        // Por padrão, se não conseguimos identificar, assumir que não há erros
        return false;
    }

    private static String extractFirstErrorMessage(String body) {
        if (body == null) return null;
        try {
            Pattern p = Pattern.compile("\"mensagem\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
            Matcher m = p.matcher(body);
            if (m.find()) {
                String mensagem = m.group(1);
                // Tratar sequências Unicode escapadas (ex: \u00e1 para á)
                mensagem = decodeUnicodeEscapes(mensagem);
                // Tratar outras sequências de escape JSON
                mensagem = unescapeJsonString(mensagem);
                return mensagem;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
    
    /* Decodifica sequências Unicode escapadas (ex: \u00e1 -> á) */
    private static String decodeUnicodeEscapes(String str) {
        if (str == null) return null;
        try {
            Pattern unicodePattern = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
            Matcher matcher = unicodePattern.matcher(str);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String hex = matcher.group(1);
                int codePoint = Integer.parseInt(hex, 16);
                matcher.appendReplacement(sb, new String(Character.toChars(codePoint)));
            }
            matcher.appendTail(sb);
            return sb.toString();
        } catch (Exception e) {
            return str; // Retornar original se houver erro
        }
    }
    
    /* Remove escapes JSON comuns */
    private static String unescapeJsonString(String str) {
        if (str == null) return null;
        return str.replace("\\\"", "\"")
                  .replace("\\\\", "\\")
                  .replace("\\/", "/")
                  .replace("\\b", "\b")
                  .replace("\\f", "\f")
                  .replace("\\n", "\n")
                  .replace("\\r", "\r")
                  .replace("\\t", "\t");
    }

    /* Verifica se o campo "dados" (array) está vazio no JSON */
    private static boolean isDadosArrayEmpty(String body) {
        if (body == null || body.trim().isEmpty()) return true;
        
        // Procurar pelo campo "dados"
        int key = body.indexOf("\"dados\"");
        if (key < 0) {
            // Se não encontrar "dados", considerar como vazio (erro)
            return true;
        }
        
        // Procurar pelo valor após "dados":
        int colon = body.indexOf(':', key);
        if (colon < 0) return true;
        
        // Pular espaços após os dois pontos
        int valueStart = colon + 1;
        while (valueStart < body.length() && Character.isWhitespace(body.charAt(valueStart))) {
            valueStart++;
        }
        
        if (valueStart >= body.length()) return true;
        
        // Verificar se é um array vazio []
        String remaining = body.substring(valueStart);
        if (remaining.startsWith("[]")) {
            return true; // Array vazio
        }
        
        // Verificar se é null
        if (remaining.startsWith("null")) {
            return true; // null também é considerado vazio
        }
        
        // Se começa com [, verificar se tem conteúdo
        if (remaining.startsWith("[")) {
            // Procurar pelo fechamento do array
            int bracketEnd = remaining.indexOf(']', 1);
            if (bracketEnd > 1) {
                // Verificar se há conteúdo entre os colchetes (ignorando espaços)
                String content = remaining.substring(1, bracketEnd).trim();
                return content.isEmpty();
            }
        }
        
        return false; // Array não está vazio
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
                        // Garantir que a leitura seja feita com UTF-8
                        // Ler todos os bytes primeiro para garantir codificação correta
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        // Converter bytes para string usando UTF-8
                        return new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
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
                System.out.println("========================================");
                System.out.println("REQUEST:");
                System.out.println("URL: " + currentUrl);
                System.out.println("Method: POST");
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
                        // Garantir que a leitura seja feita com UTF-8
                        // Ler todos os bytes primeiro para garantir codificação correta
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        // Converter bytes para string usando UTF-8
                        responseBody = new String(baos.toByteArray(), StandardCharsets.UTF_8).trim();
                    }
                } catch (Exception ignored) {
                }
                System.out.println("RESPONSE:");
                System.out.println("HTTP status: " + code);
                System.out.println("Response body: " + responseBody);
                System.out.println("========================================");
                boolean httpOk = (code >= 200 && code < 300);
                boolean bodyErr = bodyHasNonEmptyErrors(responseBody);
                // Considerar sucesso se HTTP OK e não houver erros no corpo
                boolean success = httpOk && !bodyErr;

                String msg;
                if (bodyErr) {
                    String em = extractFirstErrorMessage(responseBody);
                    String ec = extractFirstErrorCode(responseBody);
                    msg = (em != null ? em : "Erro retornado pela API") + (ec != null ? " (" + ec + ")" : "");
                } else {
                    msg = "Sucesso";
                }

                if (!success && !httpOk) {
                    System.err.println("HTTP post failed: " + code);
                }

                // Atualizar timestamp do último envio no histórico de envio HTTP
                long timestampEnvioConcluido = System.currentTimeMillis();
                synchronized (historicoEnvioHttp) {
                    HistoricoEnvioHttp historico = historicoEnvioHttp.get(codeForUi);
                    if (historico != null) {
                        historico.atualizarTimestampEnvio(timestampEnvioConcluido);
                    }
                }
                
                if (success) {
                    // GPIO será ativado quando tag for detectada, não aqui
                    // Marcar que a última tag teve sucesso
                    ultimaTagComSucesso = codeForUi;
                    ultimaTagTeveSucesso = true;
                    
                    // Sempre criar novo registro no histórico quando diferença de tempo for maior que a constante
                    // (só chega aqui se deveEnviar = true, ou seja, diferença >= TEMPO_MINIMO_ENTRE_ENVIOS_SEGUNDOS)
                    historicoTags.add(new TagHistorico(codeForUi, true, msg));
                    System.out.println("Tag adicionada ao histórico (SUCESSO): " + codeForUi);
                    
                    if (uiNotifier != null) {
                        UiNotifier n = uiNotifier;
                        SwingUtilities.invokeLater(() -> n.onApiResult(true, codeForUi, msg));
                    }
                    System.out.println("OK tag=" + codeForUi + " " + msg);
                } else {
                    // Em caso de erro, limpar flag de sucesso para permitir reenvio
                    if (ultimaTagComSucesso != null && ultimaTagComSucesso.equals(codeForUi)) {
                        ultimaTagTeveSucesso = false;
                        ultimaTagComSucesso = null;
                    }
                    
                    // Sempre criar novo registro no histórico quando diferença de tempo for maior que a constante
                    // (só chega aqui se deveEnviar = true, ou seja, diferença >= TEMPO_MINIMO_ENTRE_ENVIOS_SEGUNDOS)
                    historicoTags.add(new TagHistorico(codeForUi, false, msg));
                    System.out.println("Tag adicionada ao histórico (ERRO): " + codeForUi);
                    
                    if (uiNotifier != null) {
                        UiNotifier n = uiNotifier;
                        SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, msg));
                    }
                    System.err.println("ERROR tag=" + codeForUi + " " + msg);
                }
                break;/* sucesso ou erro não-redirecionado: sai do loop */
            } catch (java.net.SocketTimeoutException e) {
                // Atualizar timestamp do último envio no histórico (mesmo em caso de erro)
                long timestampEnvioConcluido = System.currentTimeMillis();
                synchronized (historicoEnvioHttp) {
                    HistoricoEnvioHttp historico = historicoEnvioHttp.get(codeForUi);
                    if (historico != null) {
                        historico.atualizarTimestampEnvio(timestampEnvioConcluido);
                    }
                }
                
                // Em caso de erro, limpar flag de sucesso para permitir reenvio
                if (ultimaTagComSucesso != null && ultimaTagComSucesso.equals(codeForUi)) {
                    ultimaTagTeveSucesso = false;
                    ultimaTagComSucesso = null;
                }
                
                // Adicionar ao histórico sempre que houver erro (todas as tentativas)
                String errorMsg = "Timeout na conexão HTTP: " + e.getMessage();
                historicoTags.add(new TagHistorico(codeForUi, false, errorMsg));
                System.out.println("Tag adicionada ao histórico (ERRO - Timeout): " + codeForUi);
                
                System.err.println("HTTP post timeout: " + e.getMessage());
                System.err.println("NOTA: Timeout HTTP não afeta a conexão com a antena");
                if (uiNotifier != null) {
                    UiNotifier n = uiNotifier;
                    SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, "Timeout na conexão HTTP"));
                }
                // Timeout HTTP não deve afetar a conexão com a antena
                break;
            } catch (java.net.ConnectException e) {
                // Atualizar timestamp do último envio no histórico (mesmo em caso de erro)
                long timestampEnvioConcluido = System.currentTimeMillis();
                synchronized (historicoEnvioHttp) {
                    HistoricoEnvioHttp historico = historicoEnvioHttp.get(codeForUi);
                    if (historico != null) {
                        historico.atualizarTimestampEnvio(timestampEnvioConcluido);
                    }
                }
                
                // Em caso de erro, limpar flag de sucesso para permitir reenvio
                if (ultimaTagComSucesso != null && ultimaTagComSucesso.equals(codeForUi)) {
                    ultimaTagTeveSucesso = false;
                    ultimaTagComSucesso = null;
                }
                
                // Adicionar ao histórico sempre que houver erro (todas as tentativas)
                String errorMsg = "Erro de conexão HTTP: " + e.getMessage();
                historicoTags.add(new TagHistorico(codeForUi, false, errorMsg));
                System.out.println("Tag adicionada ao histórico (ERRO - Conexão): " + codeForUi);
                
                System.err.println("HTTP post connection error: " + e.getMessage());
                System.err.println("NOTA: Erro de conexão HTTP não afeta a conexão com a antena");
                if (uiNotifier != null) {
                    UiNotifier n = uiNotifier;
                    SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, "Erro de conexão HTTP"));
                }
                // Erro de conexão HTTP não deve afetar a conexão com a antena
                break;
            } catch (Exception e) {
                // Atualizar timestamp do último envio no histórico (mesmo em caso de erro)
                long timestampEnvioConcluido = System.currentTimeMillis();
                synchronized (historicoEnvioHttp) {
                    HistoricoEnvioHttp historico = historicoEnvioHttp.get(codeForUi);
                    if (historico != null) {
                        historico.atualizarTimestampEnvio(timestampEnvioConcluido);
                    }
                }
                
                // Em caso de erro, limpar flag de sucesso para permitir reenvio
                if (ultimaTagComSucesso != null && ultimaTagComSucesso.equals(codeForUi)) {
                    ultimaTagTeveSucesso = false;
                    ultimaTagComSucesso = null;
                }
                
                // Adicionar ao histórico sempre que houver erro (todas as tentativas)
                String errorMsg = "Erro HTTP: " + e.getMessage();
                historicoTags.add(new TagHistorico(codeForUi, false, errorMsg));
                System.out.println("Tag adicionada ao histórico (ERRO - Exceção): " + codeForUi);
                
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
        
        // Ignorar tags com valor "0"
        String tagAtual = safeString(codeForUi).trim();
        if ("0".equals(tagAtual)) {
            System.out.println("Tag com valor '0' ignorada - passando para próxima leitura válida");
            System.out.println("========================================");
            return;
        }
        
        // Obter timestamp atual da leitura
        long timestampAtual = System.currentTimeMillis();
        
        // Verificar histórico de envio HTTP para esta tag
        HistoricoEnvioHttp historico = null;
        synchronized (historicoEnvioHttp) {
            historico = historicoEnvioHttp.get(tagAtual);
        }
        
        boolean deveEnviar = false;
        
        if (historico == null) {
            // Primeira vez que esta tag é detectada - criar histórico e enviar
            System.out.println("Nova tag detectada - criando histórico e enviando");
            historico = new HistoricoEnvioHttp(tagAtual, timestampAtual);
            synchronized (historicoEnvioHttp) {
                historicoEnvioHttp.put(tagAtual, historico);
            }
            deveEnviar = true;
        } else {
            // Tag já existe no histórico - verificar diferença de tempo primeiro
            long timestampUltimoEnvio = historico.getTimestampUltimoEnvio();
            long diferencaSegundos = (timestampAtual - timestampUltimoEnvio) / 1000;
            
            System.out.println("Tag já existe no histórico - diferença: " + diferencaSegundos + " segundos");
            
            if (diferencaSegundos < TEMPO_MINIMO_ENTRE_ENVIOS_SEGUNDOS) {
                // Diferença menor que o tempo mínimo - apenas atualizar timestamp da última leitura
                System.out.println("Diferença menor que " + TEMPO_MINIMO_ENTRE_ENVIOS_SEGUNDOS + "s - não enviando, apenas atualizando timestamp");
                historico.atualizarTimestampLeitura(timestampAtual);
                deveEnviar = false;
            } else {
                // Diferença maior ou igual ao tempo mínimo - verificar se último envio deu sucesso
                System.out.println("Diferença maior ou igual a " + TEMPO_MINIMO_ENTRE_ENVIOS_SEGUNDOS + "s - verificando se último envio deu sucesso");
                
                // Verificar se a última tag teve sucesso e é a mesma tag
                boolean ultimoEnvioSucesso = ultimaTagTeveSucesso && ultimaTagComSucesso != null && ultimaTagComSucesso.equals(tagAtual);
                
                if (ultimoEnvioSucesso) {
                    // Último envio deu sucesso - não enviar novamente, apenas atualizar timestamp
                    System.out.println("Último envio deu sucesso - não será reenviado, apenas atualizando timestamp");
                    historico.atualizarTimestampLeitura(timestampAtual);
                    deveEnviar = false;
                } else {
                    // Último envio não deu sucesso ou não existe - fazer novo envio
                    System.out.println("Último envio não deu sucesso ou não existe - processando novo envio");
                    historico.atualizarTimestampLeitura(timestampAtual);
                    deveEnviar = true;
                }
            }
        }
        
        // Atualizar UI com a tag detectada
        if (uiNotifier != null) {
            UiNotifier n = uiNotifier;
            SwingUtilities.invokeLater(() -> n.onTagDetected(tagAtual));
        }
        
        // Atualizar última tag lida
        ultimaTagLida = tagAtual;
        
        if (deveEnviar) {
            // Adicionar à fila para envio HTTP
            boolean offered = httpQueue.offer(codeForUi);
            if (!offered) {
                String msg = "Fila cheia. Descartando leitura.";
                System.err.println("ERROR tag=" + codeForUi + " " + msg);
                if (uiNotifier != null) {
                    UiNotifier n = uiNotifier;
                    SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, msg));
                }
            } else {
                System.out.println("Tag adicionada à fila para envio HTTP");
                // Atualizar status de conexão para ON quando tag é processada
                if (uiNotifier != null) {
                    UiNotifier n = uiNotifier;
                    SwingUtilities.invokeLater(() -> n.onConnectStatus(true));
                }
            }
        } else {
            System.out.println("Tag não será enviada - timestamp atualizado");
        }
        
        System.out.println("========================================");
    }

    private static synchronized void startHttpWorkerIfNeeded() {
        if (httpWorkerStarted) return;
        httpWorkerStarted = true;
        System.out.println("HTTP Worker iniciado - aguardando tags para envio via webServiceGetMovimentacaoObrigatoria");
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    String code = httpQueue.take();
                    System.out.println("========================================");
                    System.out.println("Processando tag da fila: " + code);
                    
                    // Construir JSON body simplificado - apenas codigoIdentificador com prefixo "02"
                    String codigoComPrefixo = "(02)" + code;
                    System.out.println("Enviando para: " + ENDPOINT_URL_CONSULTAR_MOVIMENTACAO);
                    System.out.println("codigoIdentificador (com prefixo 02): " + codigoComPrefixo);
                    
                    final String body = buildConsultarMovimentacaoJsonBody(code);
                    final Map<String, String> headers = buildDefaultHeaders();
                    
                    // Atualizar status de conexão para ON quando requisição é feita
                        if (uiNotifier != null) {
                            UiNotifier n = uiNotifier;
                        SwingUtilities.invokeLater(() -> n.onConnectStatus(true));
                    }
                    postJson(ENDPOINT_URL_CONSULTAR_MOVIMENTACAO, body, headers, code);
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
            linuxDemoInstance = demo; // Armazenar referência para poder atualizar power
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
            JFrame jf = new JFrame("RFID eship");
            jf.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            jf.setAlwaysOnTop(true);
            jf.setSize(1200, 600); // Aumentado para acomodar o histórico à direita
            jf.setLayout(new BorderLayout());
            
            // Painel superior com Conexão à esquerda e logo à direita
            JPanel pTopPanel = new JPanel(new BorderLayout());
            pTopPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            
            // Campo Conexão à esquerda
            JLabel lbConn = new JLabel("Conexão: OFF");
            lbConn.setForeground(Color.RED);
            pTopPanel.add(lbConn, BorderLayout.WEST);
            
            // Painel para a imagem no canto superior direito
            JPanel pImagePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            pImagePanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            try {
                java.net.URL imageUrl = null;
                // Tentar diferentes caminhos possíveis
                String[] possiblePaths = {
                    "/resources/images.png",
                    "resources/images.png",
                    "/images.png",
                    "images.png"
                };
                
                for (String path : possiblePaths) {
                    imageUrl = Test.class.getResource(path);
                    if (imageUrl == null) {
                        imageUrl = Test.class.getClassLoader().getResource(path);
                    }
                    if (imageUrl != null) {
                        break;
                    }
                }
                
                if (imageUrl != null) {
                    ImageIcon imageIcon = new ImageIcon(imageUrl);
                    Image image = imageIcon.getImage();
                    // Redimensionar a imagem se necessário (opcional, ajuste conforme necessário)
                    Image scaledImage = image.getScaledInstance(100, 100, Image.SCALE_SMOOTH);
                    ImageIcon scaledIcon = new ImageIcon(scaledImage);
                    JLabel lbImage = new JLabel(scaledIcon);
                    pImagePanel.add(lbImage);
                } else {
                    // Tentar carregar do sistema de arquivos como fallback
                    try {
                        java.io.File imageFile = new java.io.File("src/resources/images.png");
                        if (imageFile.exists()) {
                            ImageIcon imageIcon = new ImageIcon(imageFile.getAbsolutePath());
                            Image image = imageIcon.getImage();
                            Image scaledImage = image.getScaledInstance(100, 100, Image.SCALE_SMOOTH);
                            ImageIcon scaledIcon = new ImageIcon(scaledImage);
                            JLabel lbImage = new JLabel(scaledIcon);
                            pImagePanel.add(lbImage);
                        } else {
                            System.err.println("Imagem não encontrada em nenhum dos caminhos tentados");
                        }
                    } catch (Exception e2) {
                        System.err.println("Erro ao carregar imagem do sistema de arquivos: " + e2.getMessage());
                    }
                }
            } catch (Exception e) {
                System.err.println("Erro ao carregar imagem: " + e.getMessage());
            }
            pTopPanel.add(pImagePanel, BorderLayout.EAST);
            
            // Painel principal com o conteúdo existente - usando BoxLayout para espaçamento compacto
            JPanel pMainContent = new JPanel();
            pMainContent.setLayout(new BoxLayout(pMainContent, BoxLayout.Y_AXIS));
            pMainContent.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            pMainContent.setAlignmentX(Component.LEFT_ALIGNMENT);

            // Campo API Token (Autorização) - Rótulo acima do campo
            JPanel pApiToken = new JPanel();
            pApiToken.setLayout(new BoxLayout(pApiToken, BoxLayout.Y_AXIS));
            pApiToken.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel lbApiToken = new JLabel("Autorização (API Token):");
            lbApiToken.setAlignmentX(Component.LEFT_ALIGNMENT);
            JTextField tfApiTokenField = new JTextField(30);
            tfApiTokenField.setAlignmentX(Component.LEFT_ALIGNMENT);
            // Limitar largura máxima do campo de API Token
            tfApiTokenField.setMaximumSize(new Dimension(400, tfApiTokenField.getPreferredSize().height));
            
            // Tentar carregar apikey do arquivo .env
            java.util.Map<String, String> envMap = loadEnvFile();
            String apikeyFromEnv = envMap.get("apikey");
            if (apikeyFromEnv != null && !apikeyFromEnv.trim().isEmpty()) {
                tfApiTokenField.setText(apikeyFromEnv.trim());
                System.out.println("API Key carregada do arquivo .env");
            } else if (API_TOKEN != null && !API_TOKEN.trim().isEmpty() && !API_TOKEN.equals("P")) {
                tfApiTokenField.setText(API_TOKEN);
            }
            pApiToken.add(lbApiToken);
            pApiToken.add(Box.createVerticalStrut(2)); // Pequeno espaçamento entre rótulo e campo
            pApiToken.add(tfApiTokenField);
            pApiToken.setMaximumSize(new Dimension(Integer.MAX_VALUE, pApiToken.getPreferredSize().height));

            // Campo Power - Rótulo acima do campo e botão
            JPanel pPower = new JPanel();
            pPower.setLayout(new BoxLayout(pPower, BoxLayout.Y_AXIS));
            pPower.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel lbPower = new JLabel("Potência (0-100):");
            lbPower.setAlignmentX(Component.LEFT_ALIGNMENT);
            
            // Painel para campo e botão lado a lado
            JPanel pPowerInput = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            pPowerInput.setAlignmentX(Component.LEFT_ALIGNMENT);
            JTextField tfPowerField = new JTextField(5);
            // Converter valor interno (0-33) para valor da interface (0-100)
            // power = 33 (máximo) -> 100 na interface
            int powerUI = (power * 100) / 33;
            tfPowerField.setText(String.valueOf(powerUI));
            
            // Botão para atualizar potência
            JButton btnAtualizarPotencia = new JButton("Atualizar Potência");
            btnAtualizarPotencia.addActionListener(e -> {
                String powerText = tfPowerField.getText().trim();
                int powerUIValue;
                
                // Se campo vazio ou valor 0, usar potência máxima (100 na interface = 33 no módulo)
                if (powerText.isEmpty() || powerText.equals("0")) {
                    powerUIValue = 100;
                    tfPowerField.setText("100");
                } else {
                    try {
                        powerUIValue = Integer.parseInt(powerText);
                    } catch (NumberFormatException ex) {
                        // Se não for número válido, usar potência máxima
                        powerUIValue = 100;
                        tfPowerField.setText("100");
                        System.err.println("Valor inválido - usando potência máxima (100)");
                    }
                }
                
                // Validar e aplicar potência
                if (powerUIValue >= 0 && powerUIValue <= 100) {
                    // Converter valor da interface (0-100) para valor do módulo (1-33)
                    // Fórmula: valor_módulo = (valor_interface * 33) / 100
                    byte powerModule;
                    if (powerUIValue == 0) {
                        // Se 0 na interface, usar energia máxima (33 no módulo)
                        powerModule = 33;
                    } else {
                        powerModule = (byte) Math.round((powerUIValue * 33.0) / 100.0);
                        // Garantir que não seja menor que 1 (mínimo do módulo)
                        if (powerModule < 1) {
                            powerModule = 1;
                        }
                    }
                    power = powerModule;
                    // Atualizar power no módulo se estiver conectado
                    updatePowerIfConnected();
                    System.out.println("Potência atualizada: " + powerUIValue + "% (interface) = " + power + " (módulo)");
                } else {
                    // Se estiver fora do range, usar potência máxima
                    power = 33;
                    powerUIValue = 100;
                    tfPowerField.setText("100");
                    updatePowerIfConnected();
                    System.err.println("Potência fora do range - usando potência máxima (100)");
                    javax.swing.JOptionPane.showMessageDialog(null, 
                        "Potência deve estar entre 0 e 100. Usando potência máxima (100).", 
                        "Valor Ajustado", 
                        javax.swing.JOptionPane.INFORMATION_MESSAGE);
                }
            });
            
            pPowerInput.add(tfPowerField);
            pPowerInput.add(btnAtualizarPotencia);
            pPower.add(lbPower);
            pPower.add(Box.createVerticalStrut(2)); // Pequeno espaçamento entre rótulo e campo
            pPower.add(pPowerInput);
            pPower.setMaximumSize(new Dimension(Integer.MAX_VALUE, pPower.getPreferredSize().height));
            
            // Garantir que o painel Power está visível
            pPower.setVisible(true);
            lbPower.setVisible(true);
            tfPowerField.setVisible(true);
            btnAtualizarPotencia.setVisible(true);

            // Botão de teste GPIO
            JPanel pGpioTest = new JPanel();
            pGpioTest.setLayout(new BoxLayout(pGpioTest, BoxLayout.Y_AXIS));
            pGpioTest.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel lbGpioTest = new JLabel("Teste GPIO21:");
            lbGpioTest.setAlignmentX(Component.LEFT_ALIGNMENT);
            JButton btnTestGpio = new JButton("Alternar GPIO21");
            btnTestGpio.setAlignmentX(Component.LEFT_ALIGNMENT);
            btnTestGpio.addActionListener(e -> {
                gpioToggle();
            });
            pGpioTest.add(lbGpioTest);
            pGpioTest.add(Box.createVerticalStrut(2));
            pGpioTest.add(btnTestGpio);
            pGpioTest.setMaximumSize(new Dimension(Integer.MAX_VALUE, pGpioTest.getPreferredSize().height));

            // Labels informativos - Rótulo acima do valor
            JPanel pTag = new JPanel();
            pTag.setLayout(new BoxLayout(pTag, BoxLayout.Y_AXIS));
            pTag.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel lbTagLabel = new JLabel("Última tag:");
            lbTagLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel lbTag = new JLabel("-");
            lbTag.setAlignmentX(Component.LEFT_ALIGNMENT);
            pTag.add(lbTagLabel);
            pTag.add(Box.createVerticalStrut(2));
            pTag.add(lbTag);
            
            JPanel pApi = new JPanel();
            pApi.setLayout(new BoxLayout(pApi, BoxLayout.Y_AXIS));
            pApi.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel lbApiLabel = new JLabel("API:");
            lbApiLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel lbApi = new JLabel("-");
            lbApi.setAlignmentX(Component.LEFT_ALIGNMENT);
            pApi.add(lbApiLabel);
            pApi.add(Box.createVerticalStrut(2));
            pApi.add(lbApi);

            // Adicionar componentes com espaçamento fixo e pequeno
            pMainContent.add(pApiToken);
            pMainContent.add(Box.createVerticalStrut(4)); // Espaçamento reduzido de 4 pixels entre Autorização e Potência
            pMainContent.add(pPower);
            pMainContent.add(Box.createVerticalStrut(4)); // Espaçamento reduzido de 4 pixels entre Potência e GPIO
            pMainContent.add(pGpioTest);
            pMainContent.add(Box.createVerticalStrut(8)); // Espaçamento fixo de 8 pixels
            pMainContent.add(pTag);
            pMainContent.add(Box.createVerticalStrut(4)); // Espaçamento reduzido de 4 pixels entre Tag e API
            pMainContent.add(pApi);
            // Adicionar componente flexível no final para empurrar tudo para o topo
            pMainContent.add(Box.createVerticalGlue());
            
            // Painel direito com histórico de tags (abaixo do logo)
            JPanel pRightPanel = new JPanel(new BorderLayout());
            pRightPanel.setBorder(BorderFactory.createTitledBorder("Histórico de Tags"));
            pRightPanel.setPreferredSize(new java.awt.Dimension(550, 0));
            
            // Lista para exibir o histórico
            javax.swing.DefaultListModel<String> historicoListModel = new javax.swing.DefaultListModel<>();
            javax.swing.JList<String> historicoList = new javax.swing.JList<>(historicoListModel);
            historicoList.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 10));
            historicoList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
            
            // ScrollPane para a lista
            javax.swing.JScrollPane scrollHistorico = new javax.swing.JScrollPane(historicoList);
            scrollHistorico.setVerticalScrollBarPolicy(javax.swing.JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
            scrollHistorico.setHorizontalScrollBarPolicy(javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            
            // Método para atualizar o histórico na interface
            Runnable atualizarHistoricoUI = () -> {
                SwingUtilities.invokeLater(() -> {
                    java.util.List<TagHistorico> historico = getHistoricoTags();
                    historicoListModel.clear();
                    // Adicionar as últimas 100 entradas (mais recentes primeiro)
                    int inicio = Math.max(0, historico.size() - 100);
                    for (int i = historico.size() - 1; i >= inicio; i--) {
                        TagHistorico entrada = historico.get(i);
                        historicoListModel.addElement(entrada.toString());
                    }
                    // Auto-scroll para o topo (mais recente)
                    if (historicoListModel.getSize() > 0) {
                        historicoList.setSelectedIndex(0);
                        historicoList.ensureIndexIsVisible(0);
                    }
                });
            };
            
            // Painel superior com botão para limpar histórico
            JPanel pHistoricoTop = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton btnLimparHistorico = new JButton("Limpar Histórico");
            btnLimparHistorico.addActionListener(e -> {
                // Limpar o histórico
                synchronized (historicoTags) {
                    historicoTags.clear();
                }
                // Atualizar a interface
                atualizarHistoricoUI.run();
                System.out.println("Histórico de tags limpo");
            });
            pHistoricoTop.add(btnLimparHistorico);
            pRightPanel.add(pHistoricoTop, BorderLayout.NORTH);
            pRightPanel.add(scrollHistorico, BorderLayout.CENTER);
            
            // Atualizar histórico inicialmente
            atualizarHistoricoUI.run();
            
            // Adicionar os painéis ao JFrame
            jf.add(pTopPanel, BorderLayout.NORTH);
            jf.add(pMainContent, BorderLayout.CENTER);
            jf.add(pRightPanel, BorderLayout.EAST);
            
            // Forçar atualização do layout para garantir que todos os componentes sejam exibidos
            jf.revalidate();
            jf.repaint();
            
            jf.setLocationRelativeTo(null);
            jf.setVisible(true);

            tfApiToken = tfApiTokenField;
            tfPower = tfPowerField;
            
            // Armazenar referência para atualizar histórico quando novas tags forem adicionadas
            final Runnable atualizarHistoricoRef = atualizarHistoricoUI;

            uiNotifier = new UiNotifier() {
                @Override
                public void onConnectStatus(boolean connected) {
                    if (connected) {
                        lbConn.setText("Conexão: ON");
                        lbConn.setForeground(Color.GREEN);
                    } else {
                        lbConn.setText("Conexão: OFF");
                        lbConn.setForeground(Color.RED);
                    }
                }

                @Override
                public void onReadingStatus(boolean reading) {
                    // Campo Leitura removido - informação já mostrada em Última tag
                }

                @Override
                public void onTagDetected(String code) {
                    lbTag.setText(code);
                    // Só mostrar "aguardando resposta" se não for tag duplicada com sucesso
                    String tagAtual = safeString(code).trim();
                    boolean ehTagComSucesso = ultimaTagTeveSucesso && ultimaTagComSucesso != null && ultimaTagComSucesso.equals(tagAtual);
                    if (!ehTagComSucesso) {
                        lbApi.setText("aguardando resposta...");
                    }
                    // Se for tag com sucesso, mantém a última mensagem de sucesso
                }

                @Override
                public void onApiResult(boolean success, String code, String message) {
                    lbApi.setText((success ? "OK" : "ERRO") + " - " + message);
                    // Atualizar histórico na interface quando houver resultado da API
                    atualizarHistoricoRef.run();
                }
            };

            jf.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    gpioShutdown();
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
        Reader mReader; // Mudado para package-private para poder acessar de updatePowerIfConnected
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
                            
                            // Ignorar tags com valor "0" - não atualizar UI nem enviar
                            if ("0".equals(code.trim())) {
                                return;
                            }
                            
                            // Piscar LED quando tag é detectada
                            gpioBlinkOnTagDetected();
                            
                            // Atualizar status de conexão para ON quando tag é detectada
                            if (uiNotifier != null) {
                                UiNotifier n = uiNotifier;
                                SwingUtilities.invokeLater(() -> n.onConnectStatus(true));
                            }
                            
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