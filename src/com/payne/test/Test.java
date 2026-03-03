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
    private static byte power = 16;/* Rang: (1 , 33) */
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

    /* GPIO (Raspberry Pi) ↓↓↓
     * GPIO pin BCM para controle via gpioset
     */
    private static final int GPIO_PIN_BCM = 21; /* GPIO BCM 21 (padrão) */
    /* GPIO ↑↑↑ */

    /* UI notifier (Linux) */
    private interface UiNotifier {
        void onConnectStatus(boolean connected);
        void onReadingStatus(boolean reading);
        void onTagDetected(String code);
        void onApiResult(boolean success, String code, String message);
        void onTimestampUpdated(String code, String message); // message pode ser null - apenas para atualizar visual
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
    
    /* Tempo mínimo em segundos entre leituras válidas da mesma tag */
    private static final int TEMPO_MINIMO_ENTRE_ENVIOS_SEGUNDOS = 2; /* configure aqui */
    
    /* Variável para armazenar a última tag lida e evitar duplicados */
    private static volatile String ultimaTagLida = null;
    
    /* Variável para armazenar a última tag que teve sucesso na API */
    private static volatile String ultimaTagComSucesso = null;
    
    /* Flag para indicar se a última tag teve sucesso */
    private static volatile boolean ultimaTagTeveSucesso = false;
    
    /* Rastreamento rápido de leituras para evitar duplicatas em menos de 300ms - usando AtomicReference para lock-free */
    private static final java.util.concurrent.atomic.AtomicReference<String> ultimaTagLidaRapidaRef = 
        new java.util.concurrent.atomic.AtomicReference<>(null);
    private static volatile long ultimaTagLidaRapidaTimestamp = 0;
    private static final long TEMPO_MINIMO_ENTRE_LEITURAS_MS = 300; /* 300ms para ignorar leituras duplicadas muito rápidas */
    
    /* Histórico de LEITURA: Map thread-safe para armazenar timestamp da última leitura válida por tag */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> historicoLeitura = 
        new java.util.concurrent.ConcurrentHashMap<>();
    
    /* Classe para representar histórico de ENVIO HTTP */
    private static class HistoricoEnvio {
        private final String tag;
        private volatile long timestampUltimoEnvio;   // Timestamp do último envio HTTP
        private volatile boolean ultimoEnvioSucesso;  // Se o último envio foi bem-sucedido
        
        public HistoricoEnvio(String tag, long timestampEnvio, boolean sucesso) {
            this.tag = tag;
            this.timestampUltimoEnvio = timestampEnvio;
            this.ultimoEnvioSucesso = sucesso;
        }
        
        public String getTag() { return tag; }
        public long getTimestampUltimoEnvio() { return timestampUltimoEnvio; }
        public boolean isUltimoEnvioSucesso() { return ultimoEnvioSucesso; }
        
        public void atualizarEnvio(long timestamp, boolean sucesso) {
            this.timestampUltimoEnvio = timestamp;
            this.ultimoEnvioSucesso = sucesso;
        }
    }
    
    /* Map thread-safe para armazenar histórico de ENVIOS HTTP por tag */
    private static final java.util.concurrent.ConcurrentHashMap<String, HistoricoEnvio> historicoEnvio = 
        new java.util.concurrent.ConcurrentHashMap<>();
    
    /* Classe para representar uma entrada no histórico de tags */
    private static class TagHistorico {
        private final String tag;
        private long timestamp;
        private final boolean sucesso;
        private final String mensagem;
        private volatile boolean timestampAtualizadoRecentemente; // Flag para indicar atualização recente
        private final boolean temErroOPE10161; // Flag permanente para indicar erro OPE10161
        
        public TagHistorico(String tag, boolean sucesso, String mensagem) {
            this(tag, sucesso, mensagem, null);
        }
        
        public TagHistorico(String tag, boolean sucesso, String mensagem, String codigoErro) {
            this.tag = tag;
            this.timestamp = System.currentTimeMillis();
            this.sucesso = sucesso;
            this.mensagem = mensagem;
            this.timestampAtualizadoRecentemente = false;
            // Verificar se o código de erro é OPE10161
            this.temErroOPE10161 = (codigoErro != null && codigoErro.equals("OPE10161"));
        }
        
        public String getTag() { return tag; }
        public long getTimestamp() { return timestamp; }
        public boolean isSucesso() { return sucesso; }
        public String getMensagem() { return mensagem; }
        public boolean isTimestampAtualizadoRecentemente() { return timestampAtualizadoRecentemente; }
        public boolean isTemErroOPE10161() { return temErroOPE10161; }
        
        public void atualizarTimestamp(long novoTimestamp) {
            this.timestamp = novoTimestamp;
            this.timestampAtualizadoRecentemente = true;
            // Resetar flag após 2 segundos (tempo suficiente para UI atualizar)
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    this.timestampAtualizadoRecentemente = false;
                } catch (InterruptedException ignored) {
                }
            }, "reset-timestamp-flag").start();
        }
        
        @Override
        public String toString() {
            // Usar ThreadLocal SimpleDateFormat para melhor performance
            java.text.SimpleDateFormat sdf = getSafeDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            return String.format("[%s] Tag: %s | %s | %s", 
                sdf.format(new java.util.Date(timestamp)), 
                tag, 
                sucesso ? "SUCESSO" : "ERRO", 
                mensagem);
        }
    }
    
    /* Lista thread-safe para armazenar histórico de tags - usando ConcurrentLinkedQueue para melhor performance */
    private static final java.util.concurrent.ConcurrentLinkedQueue<TagHistorico> historicoTagsQueue = 
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    
    /* Map para acesso rápido ao último registro de cada tag (O(1) ao invés de O(n)) */
    private static final java.util.concurrent.ConcurrentHashMap<String, TagHistorico> historicoTagsMap = 
        new java.util.concurrent.ConcurrentHashMap<>();
    
    /* Limite máximo de itens no histórico para evitar memory leak */
    private static final int MAX_HISTORICO_TAGS = 1000;
    
    /* Método para obter uma cópia do histórico de tags (thread-safe) - otimizado */
    public static java.util.List<TagHistorico> getHistoricoTags() {
        // Criar lista a partir da queue (já é thread-safe, não precisa de synchronized)
        java.util.List<TagHistorico> resultado = new java.util.ArrayList<>();
        int queueSize = historicoTagsQueue.size();
        int maxItems = Math.min(200, queueSize);
        
        if (queueSize == 0) {
            return resultado;
        }
        
        // Converter queue para array para acesso mais eficiente
        // ConcurrentLinkedQueue mantém ordem FIFO: primeiro = mais antigo, último = mais recente
        TagHistorico[] array = historicoTagsQueue.toArray(new TagHistorico[0]);
        // Pegar os últimos maxItems e adicionar em ordem reversa (mais recente primeiro)
        int inicio = Math.max(0, array.length - maxItems);
        // Iterar do final para o início para ter os mais recentes primeiro
        for (int i = array.length - 1; i >= inicio; i--) {
            resultado.add(array[i]);
        }
        // Agora resultado já tem os mais recentes primeiro, sem precisar de reverse
        return resultado;
    }
    
    /* Método para obter o tamanho do histórico */
    public static int getHistoricoTagsSize() {
        return historicoTagsQueue.size();
    }
    
    /* Método para adicionar ao histórico com limite automático */
    private static void adicionarAoHistorico(TagHistorico entrada) {
        // Adicionar à queue
        historicoTagsQueue.offer(entrada);
        // Atualizar map para acesso rápido
        historicoTagsMap.put(entrada.getTag(), entrada);
        
        // Limitar tamanho da queue (remover os mais antigos se necessário)
        while (historicoTagsQueue.size() > MAX_HISTORICO_TAGS) {
            TagHistorico removido = historicoTagsQueue.poll();
            if (removido != null) {
                // Verificar se ainda é o mais recente antes de remover do map
                TagHistorico atual = historicoTagsMap.get(removido.getTag());
                if (atual == removido) {
                    historicoTagsMap.remove(removido.getTag());
                }
            }
        }
    }
    
    /* Método para atualizar o timestamp do registro mais recente de uma tag - otimizado com O(1) */
    private static void atualizarTimestampRegistroMaisRecente(String tag, long novoTimestamp) {
        // Acesso O(1) ao invés de busca linear O(n)
        TagHistorico registro = historicoTagsMap.get(tag);
        if (registro != null) {
            registro.atualizarTimestamp(novoTimestamp);
        }
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

    /* ---------------------- GPIO helpers (gpioset) ---------------------- */
    /**
     * Define o estado do GPIO usando gpioset
     * @param value 0 para LOW, 1 para HIGH
     */
    private static void gpioSetValue(int value) {
        String os = safeString(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return;
        try {
            Process p = Runtime.getRuntime().exec(
                new String[]{"gpioset", "-t 0", "--chip", "gpiochip0", GPIO_PIN_BCM + "=" + value}
            );
            // Adicionar timeout para evitar travamento indefinido
            boolean finished = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                System.err.println("GPIO gpioset timeout - processo destruído");
                return;
            }
            if (p.exitValue() != 0) {
                System.err.println("Erro ao executar gpioset: código de saída " + p.exitValue());
            }
        } catch (Exception e) {
            System.err.println("GPIO gpioset error: " + e.getMessage());
        }
    }
    
    /**
     * Faz o LED piscar (HIGH por 300ms, depois LOW)
     * Usado quando uma tag é detectada do módulo
     */
    private static void gpioBlinkLed() {
        String os = safeString(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return;
        new Thread(() -> {
            try {
                // Ligar LED (HIGH)
                gpioSetValue(1);
                Thread.sleep(300);
                // Desligar LED (LOW)
                gpioSetValue(0);
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                System.err.println("Erro ao piscar LED: " + e.getMessage());
            }
        }, "gpio-led-blink").start();
    }
    
    /**
     * Alterna o estado do GPIO (para teste)
     * @return o novo estado (0 ou 1)
     */
    private static int gpioToggle() {
        String os = safeString(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return -1;
        // Como não podemos ler o estado atual facilmente com gpioset,
        // vamos usar uma variável para rastrear o estado
        // Por padrão, começamos com 0 (LOW)
        synchronized (Test.class) {
            // Usar uma variável estática para rastrear o estado
            if (!gpioStateInitialized) {
                gpioCurrentState = 0;
                gpioStateInitialized = true;
            }
            gpioCurrentState = (gpioCurrentState == 0) ? 1 : 0;
            gpioSetValue(gpioCurrentState);
            return gpioCurrentState;
        }
    }
    
    private static volatile boolean gpioStateInitialized = false;
    private static volatile int gpioCurrentState = 0;
    /* -------------------- GPIO helpers (end) -------------------- */

    /* ---------------------- PWM Buzzer helpers ---------------------- */
    private static final String PWM_CHIP_PATH = "/sys/class/pwm/pwmchip0";
    private static final String PWM_CHANNEL = "0";
    private static final String PWM_BASE_PATH = PWM_CHIP_PATH + "/pwm" + PWM_CHANNEL;
    private static final long PWM_PERIOD = 1000000L; // 1MHz = 1 segundo em nanosegundos
    private static final long PWM_DUTY_CYCLE = 500000L; // 50% duty cycle
    private static final int BUZZER_DURATION_MS = 100; // Duração do som em milissegundos
    private static volatile boolean pwmInitialized = false;
    
    /**
     * Inicializa o PWM hardware uma única vez no arranque da aplicação
     * Exporta o canal, configura period e duty_cycle, deixa pronto para uso
     */
    private static void initPwmBuzzer() {
        String os = safeString(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) {
            System.out.println("PWM Buzzer: Sistema não é Linux - funcionalidade desabilitada");
            return;
        }
        
        // Double-check locking para evitar bloqueio desnecessário após inicialização
        if (pwmInitialized) {
            return; // Já inicializado
        }
        
        synchronized (Test.class) {
            // Verificar novamente dentro do lock (double-check)
            if (pwmInitialized) {
                return;
            }
            
            try {
                java.nio.file.Path exportPath = java.nio.file.Paths.get(PWM_CHIP_PATH + "/export");
                java.nio.file.Path periodPath = java.nio.file.Paths.get(PWM_BASE_PATH + "/period");
                java.nio.file.Path dutyCyclePath = java.nio.file.Paths.get(PWM_BASE_PATH + "/duty_cycle");
                java.nio.file.Path enablePath = java.nio.file.Paths.get(PWM_BASE_PATH + "/enable");
                
                // Verificar se o PWM já está exportado
                if (!java.nio.file.Files.exists(java.nio.file.Paths.get(PWM_BASE_PATH))) {
                    // Exportar o canal PWM
                    System.out.println("PWM Buzzer: Exportando canal " + PWM_CHANNEL);
                    java.nio.file.Files.writeString(exportPath, PWM_CHANNEL, java.nio.charset.StandardCharsets.UTF_8);
                    
                    // Aguardar um pouco para o sistema criar os arquivos
                    Thread.sleep(100);
                }
                
                // Configurar period
                System.out.println("PWM Buzzer: Configurando period = " + PWM_PERIOD);
                java.nio.file.Files.writeString(periodPath, String.valueOf(PWM_PERIOD), java.nio.charset.StandardCharsets.UTF_8);
                
                // Configurar duty_cycle
                System.out.println("PWM Buzzer: Configurando duty_cycle = " + PWM_DUTY_CYCLE);
                java.nio.file.Files.writeString(dutyCyclePath, String.valueOf(PWM_DUTY_CYCLE), java.nio.charset.StandardCharsets.UTF_8);
                
                // Garantir que está desabilitado inicialmente
                java.nio.file.Files.writeString(enablePath, "0", java.nio.charset.StandardCharsets.UTF_8);
                
                pwmInitialized = true;
                System.out.println("PWM Buzzer: Inicializado com sucesso");
            } catch (Exception e) {
                System.err.println("PWM Buzzer: Erro ao inicializar - " + e.getMessage());
                // Não marcar como inicializado se houve erro
                pwmInitialized = false;
            }
        }
    }
    
    /**
     * Ativa o buzzer por um período de tempo
     * Ativa enable=1, espera X milissegundos, desativa enable=0
     */
    private static void pwmBuzzerBeep() {
        String os = safeString(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return;
        
        if (!pwmInitialized) {
            // Tentar inicializar se ainda não foi feito
            initPwmBuzzer();
            if (!pwmInitialized) {
                return; // Se ainda não conseguiu inicializar, não fazer nada
            }
        }
        
        // Executar em thread separada para não bloquear
        new Thread(() -> {
            try {
                java.nio.file.Path enablePath = java.nio.file.Paths.get(PWM_BASE_PATH + "/enable");
                
                // Ativar buzzer (enable = 1) com timeout
                try {
                    java.nio.file.Files.writeString(enablePath, "1", java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.nio.file.FileSystemException e) {
                    // Se falhar, não continuar
                    System.err.println("PWM Buzzer: Erro ao ativar - " + e.getMessage());
                    return;
                }
                
                // Esperar X milissegundos
                Thread.sleep(BUZZER_DURATION_MS);
                
                // Desativar buzzer (enable = 0) com tratamento de erro
                try {
                    java.nio.file.Files.writeString(enablePath, "0", java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.nio.file.FileSystemException e) {
                    System.err.println("PWM Buzzer: Erro ao desativar - " + e.getMessage());
                }
            } catch (InterruptedException ignored) {
                // Se foi interrompido, garantir que o buzzer seja desativado
                try {
                    java.nio.file.Path enablePath = java.nio.file.Paths.get(PWM_BASE_PATH + "/enable");
                    java.nio.file.Files.writeString(enablePath, "0", java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    // Ignorar erro ao desativar
                }
            } catch (Exception e) {
                System.err.println("PWM Buzzer: Erro ao emitir som - " + e.getMessage());
            }
        }, "pwm-buzzer-beep").start();
    }
    
    /**
     * Limpa recursos do PWM ao encerrar a aplicação
     */
    private static void cleanupPwmBuzzer() {
        if (!pwmInitialized) return;
        
        String os = safeString(System.getProperty("os.name")).toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return;
        
        try {
            java.nio.file.Path enablePath = java.nio.file.Paths.get(PWM_BASE_PATH + "/enable");
            java.nio.file.Path unexportPath = java.nio.file.Paths.get(PWM_CHIP_PATH + "/unexport");
            
            // Desativar buzzer
            if (java.nio.file.Files.exists(enablePath)) {
                java.nio.file.Files.writeString(enablePath, "0", java.nio.charset.StandardCharsets.UTF_8);
            }
            
            // Unexport do canal (opcional, mas limpa recursos)
            if (java.nio.file.Files.exists(unexportPath)) {
                java.nio.file.Files.writeString(unexportPath, PWM_CHANNEL, java.nio.charset.StandardCharsets.UTF_8);
            }
            
            System.out.println("PWM Buzzer: Recursos liberados");
        } catch (Exception e) {
            System.err.println("PWM Buzzer: Erro ao limpar recursos - " + e.getMessage());
        }
    }
    /* -------------------- PWM Buzzer helpers (end) -------------------- */

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
                String codigoErro = null;
                if (bodyErr) {
                    String em = extractFirstErrorMessage(responseBody);
                    codigoErro = extractFirstErrorCode(responseBody);
                    msg = (em != null ? em : "Erro retornado pela API") + (codigoErro != null ? " (" + codigoErro + ")" : "");
                } else {
                    msg = "Sucesso";
                }

                if (!success && !httpOk) {
                    System.err.println("HTTP post failed: " + code);
                }

                // Atualizar histórico de ENVIO HTTP (ConcurrentHashMap - não precisa synchronized)
                long timestampEnvioConcluido = System.currentTimeMillis();
                HistoricoEnvio historico = historicoEnvio.computeIfAbsent(codeForUi, 
                    k -> new HistoricoEnvio(codeForUi, timestampEnvioConcluido, success));
                if (historico != null) {
                    historico.atualizarEnvio(timestampEnvioConcluido, success);
                }
                
                if (success) {
                    // Marcar que a última tag teve sucesso
                    ultimaTagComSucesso = codeForUi;
                    ultimaTagTeveSucesso = true;
                    
                    // Adicionar ao histórico de auditoria (para UI)
                    adicionarAoHistorico(new TagHistorico(codeForUi, true, msg, codigoErro));
                    System.out.println("Tag adicionada ao histórico de auditoria (SUCESSO): " + codeForUi);
                    
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
                    
                    // Adicionar ao histórico de auditoria (para UI)
                    adicionarAoHistorico(new TagHistorico(codeForUi, false, msg, codigoErro));
                    System.out.println("Tag adicionada ao histórico de auditoria (ERRO): " + codeForUi);
                    
                    if (uiNotifier != null) {
                        UiNotifier n = uiNotifier;
                        SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, msg));
                    }
                    System.err.println("ERROR tag=" + codeForUi + " " + msg);
                }
                break;/* sucesso ou erro não-redirecionado: sai do loop */
            } catch (java.net.SocketTimeoutException e) {
                // Atualizar histórico de ENVIO HTTP (mesmo em caso de erro) - ConcurrentHashMap
                long timestampEnvioConcluido = System.currentTimeMillis();
                HistoricoEnvio historico = historicoEnvio.computeIfAbsent(codeForUi, 
                    k -> new HistoricoEnvio(codeForUi, timestampEnvioConcluido, false));
                if (historico != null) {
                    historico.atualizarEnvio(timestampEnvioConcluido, false);
                }
                
                // Em caso de erro, limpar flag de sucesso para permitir reenvio
                if (ultimaTagComSucesso != null && ultimaTagComSucesso.equals(codeForUi)) {
                    ultimaTagTeveSucesso = false;
                    ultimaTagComSucesso = null;
                }
                
                // Adicionar ao histórico de auditoria (para UI)
                String errorMsg = "Timeout na conexão HTTP: " + e.getMessage();
                adicionarAoHistorico(new TagHistorico(codeForUi, false, errorMsg, null));
                System.out.println("Tag adicionada ao histórico de auditoria (ERRO - Timeout): " + codeForUi);
                
                System.err.println("HTTP post timeout: " + e.getMessage());
                System.err.println("NOTA: Timeout HTTP não afeta a conexão com a antena");
                if (uiNotifier != null) {
                    UiNotifier n = uiNotifier;
                    SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, "Timeout na conexão HTTP"));
                }
                // Timeout HTTP não deve afetar a conexão com a antena
                break;
            } catch (java.net.ConnectException e) {
                // Atualizar histórico de ENVIO HTTP (mesmo em caso de erro) - ConcurrentHashMap
                long timestampEnvioConcluido = System.currentTimeMillis();
                HistoricoEnvio historico = historicoEnvio.computeIfAbsent(codeForUi, 
                    k -> new HistoricoEnvio(codeForUi, timestampEnvioConcluido, false));
                if (historico != null) {
                    historico.atualizarEnvio(timestampEnvioConcluido, false);
                }
                
                // Em caso de erro, limpar flag de sucesso para permitir reenvio
                if (ultimaTagComSucesso != null && ultimaTagComSucesso.equals(codeForUi)) {
                    ultimaTagTeveSucesso = false;
                    ultimaTagComSucesso = null;
                }
                
                // Adicionar ao histórico de auditoria (para UI)
                String errorMsg = "Erro de conexão HTTP: " + e.getMessage();
                adicionarAoHistorico(new TagHistorico(codeForUi, false, errorMsg, null));
                System.out.println("Tag adicionada ao histórico de auditoria (ERRO - Conexão): " + codeForUi);
                
                System.err.println("HTTP post connection error: " + e.getMessage());
                System.err.println("NOTA: Erro de conexão HTTP não afeta a conexão com a antena");
                if (uiNotifier != null) {
                    UiNotifier n = uiNotifier;
                    SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, "Erro de conexão HTTP"));
                }
                // Erro de conexão HTTP não deve afetar a conexão com a antena
                break;
            } catch (Exception e) {
                // Atualizar histórico de ENVIO HTTP (mesmo em caso de erro) - ConcurrentHashMap
                long timestampEnvioConcluido = System.currentTimeMillis();
                HistoricoEnvio historico = historicoEnvio.computeIfAbsent(codeForUi, 
                    k -> new HistoricoEnvio(codeForUi, timestampEnvioConcluido, false));
                if (historico != null) {
                    historico.atualizarEnvio(timestampEnvioConcluido, false);
                }
                
                // Em caso de erro, limpar flag de sucesso para permitir reenvio
                if (ultimaTagComSucesso != null && ultimaTagComSucesso.equals(codeForUi)) {
                    ultimaTagTeveSucesso = false;
                    ultimaTagComSucesso = null;
                }
                
                // Adicionar ao histórico de auditoria (para UI)
                String errorMsg = "Erro HTTP: " + e.getMessage();
                adicionarAoHistorico(new TagHistorico(codeForUi, false, errorMsg, null));
                System.out.println("Tag adicionada ao histórico de auditoria (ERRO - Exceção): " + codeForUi);
                
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

    /* Adiciona tag à lista de envio - validação de tempo será feita no worker HTTP */
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
        
        // Verificar se a mesma tag foi lida em menos de 300ms (ignorar leitura duplicada muito rápida) - lock-free
        long timestampAtual = System.currentTimeMillis();
        String ultimaTag = ultimaTagLidaRapidaRef.get();
        if (ultimaTag != null && ultimaTag.equals(tagAtual)) {
            long diferencaMs = timestampAtual - ultimaTagLidaRapidaTimestamp;
            if (diferencaMs < TEMPO_MINIMO_ENTRE_LEITURAS_MS) {
                // Mesma tag lida em menos de 300ms - ignorar esta leitura
                System.out.println("Tag '" + tagAtual + "' lida novamente em " + diferencaMs + "ms (< " + TEMPO_MINIMO_ENTRE_LEITURAS_MS + "ms) - IGNORANDO leitura do módulo");
                System.out.println("========================================");
                return; // Ignorar e passar para próxima leitura
            }
        }
        // Atualizar rastreamento rápido (lock-free com AtomicReference)
        ultimaTagLidaRapidaRef.set(tagAtual);
        ultimaTagLidaRapidaTimestamp = timestampAtual;
        
        // Atualizar UI com a tag detectada
        if (uiNotifier != null) {
            UiNotifier n = uiNotifier;
            SwingUtilities.invokeLater(() -> n.onTagDetected(tagAtual));
        }
        
        // Atualizar última tag lida
        ultimaTagLida = tagAtual;
        
        // Adicionar à fila para envio HTTP - validação de tempo será feita no worker
        boolean offered = httpQueue.offer(codeForUi);
        if (!offered) {
            String msg = "Fila cheia. Descartando leitura.";
            System.err.println("ERROR tag=" + codeForUi + " " + msg);
            if (uiNotifier != null) {
                UiNotifier n = uiNotifier;
                SwingUtilities.invokeLater(() -> n.onApiResult(false, codeForUi, msg));
            }
        } else {
            System.out.println("Tag adicionada à lista de envio (será processada pelo worker HTTP)");
        }
        
        System.out.println("========================================");
    }

    private static synchronized void startHttpWorkerIfNeeded() {
        if (httpWorkerStarted) return;
        httpWorkerStarted = true;
        System.out.println("HTTP Worker iniciado - aguardando tags da lista de envio");
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    String code = httpQueue.take();
                    System.out.println("========================================");
                    System.out.println("Processando tag da lista de envio: " + code);
                    
                    // Obter timestamp atual
                    long timestampAtual = System.currentTimeMillis();
                    
                    // Verificar histórico de ENVIO HTTP para esta tag (ConcurrentHashMap - não precisa synchronized)
                    HistoricoEnvio historicoEnvioTag = historicoEnvio.get(code);
                    
                    boolean deveEnviar = false;
                    
                    if (historicoEnvioTag == null) {
                        // Tag não existe no histórico de envio - primeira vez que será enviada
                        System.out.println("Tag não existe no histórico de envio - será enviada pela primeira vez");
                        deveEnviar = true;
                    } else {
                        // Tag já existe no histórico de envio - verificar diferença de tempo
                        long timestampUltimoEnvio = historicoEnvioTag.getTimestampUltimoEnvio();
                        long diferencaSegundos = (timestampAtual - timestampUltimoEnvio) / 1000;
                        
                        System.out.println("Tag existe no histórico de envio - último envio há " + diferencaSegundos + " segundos");
                        
                        if (diferencaSegundos < TEMPO_MINIMO_ENTRE_ENVIOS_SEGUNDOS) {
                            // Diferença menor que o tempo mínimo - DESCONSIDERAR este envio
                            // Atualizar apenas o timestamp no histórico de envio (sem alterar sucesso)
                            System.out.println("Diferença menor que " + TEMPO_MINIMO_ENTRE_ENVIOS_SEGUNDOS + "s - envio DESCONSIDERADO, atualizando timestamp no histórico");
                            
                            // ConcurrentHashMap - não precisa synchronized, mas HistoricoEnvio usa volatile
                            boolean ultimoEnvioSucesso = historicoEnvioTag.isUltimoEnvioSucesso();
                            historicoEnvioTag.atualizarEnvio(timestampAtual, ultimoEnvioSucesso);
                            
                            // Atualizar timestamp do registro mais recente no histórico de tags (se existir)
                            atualizarTimestampRegistroMaisRecente(code, timestampAtual);
                            
                            // Informar na interface que o timestamp foi atualizado (apenas para atualizar visual)
                            if (uiNotifier != null) {
                                UiNotifier n = uiNotifier;
                                SwingUtilities.invokeLater(() -> n.onTimestampUpdated(code, null));
                            }
                            
                            deveEnviar = false;
                        } else {
                            // Diferença maior ou igual ao tempo mínimo - pode enviar
                            System.out.println("Diferença maior ou igual a " + TEMPO_MINIMO_ENTRE_ENVIOS_SEGUNDOS + "s - envio será realizado");
                            deveEnviar = true;
                        }
                    }
                    
                    if (deveEnviar) {
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
                    } else {
                        System.out.println("Tag não será enviada - diferença de tempo menor que o mínimo permitido");
                    }
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
            // Inicializar PWM Buzzer uma vez no arranque
            initPwmBuzzer();
            initLinuxUI();
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

            // Botão para testar GPIO - COMENTADO TEMPORARIAMENTE
            /*
            JPanel pGpioTest = new JPanel();
            pGpioTest.setLayout(new BoxLayout(pGpioTest, BoxLayout.Y_AXIS));
            pGpioTest.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel lbGpioTest = new JLabel("Teste GPIO (Pin " + GPIO_PIN_BCM + "):");
            lbGpioTest.setAlignmentX(Component.LEFT_ALIGNMENT);
            JButton btnTestGpio = new JButton("Alternar GPIO");
            btnTestGpio.setAlignmentX(Component.LEFT_ALIGNMENT);
            btnTestGpio.addActionListener(e -> {
                int novoEstado = gpioToggle();
                if (novoEstado >= 0) {
                    String estadoStr = (novoEstado == 1) ? "HIGH" : "LOW";
                    System.out.println("GPIO " + GPIO_PIN_BCM + " alternado para: " + estadoStr);
                    javax.swing.JOptionPane.showMessageDialog(null, 
                        "GPIO " + GPIO_PIN_BCM + " alterado para " + estadoStr, 
                        "Teste GPIO", 
                        javax.swing.JOptionPane.INFORMATION_MESSAGE);
                } else {
                    javax.swing.JOptionPane.showMessageDialog(null, 
                        "Erro ao alternar GPIO. Verifique se está executando no Linux.", 
                        "Erro", 
                        javax.swing.JOptionPane.ERROR_MESSAGE);
                }
            });
            pGpioTest.add(lbGpioTest);
            pGpioTest.add(Box.createVerticalStrut(2));
            pGpioTest.add(btnTestGpio);
            pGpioTest.setMaximumSize(new Dimension(Integer.MAX_VALUE, pGpioTest.getPreferredSize().height));
            */

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
            
            // Campo API - COMENTADO TEMPORARIAMENTE
            /*
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
            */

            // Adicionar componentes com espaçamento fixo e pequeno
            pMainContent.add(pApiToken);
            pMainContent.add(Box.createVerticalStrut(4)); // Espaçamento reduzido de 4 pixels entre Autorização e Potência
            pMainContent.add(pPower);
            pMainContent.add(Box.createVerticalStrut(8)); // Espaçamento fixo de 8 pixels
            // pMainContent.add(pGpioTest); // COMENTADO TEMPORARIAMENTE
            // pMainContent.add(Box.createVerticalStrut(8)); // Espaçamento fixo de 8 pixels
            pMainContent.add(pTag);
            pMainContent.add(Box.createVerticalStrut(4)); // Espaçamento reduzido de 4 pixels entre Tag e API
            // pMainContent.add(pApi); // COMENTADO TEMPORARIAMENTE
            // Adicionar componente flexível no final para empurrar tudo para o topo
            pMainContent.add(Box.createVerticalGlue());
            
            // Painel direito com histórico de tags (abaixo do logo)
            JPanel pRightPanel = new JPanel(new BorderLayout());
            pRightPanel.setBorder(BorderFactory.createTitledBorder("Histórico de Tags"));
            pRightPanel.setPreferredSize(new java.awt.Dimension(550, 0));
            
            // Lista para exibir o histórico - usando TagHistorico diretamente
            javax.swing.DefaultListModel<TagHistorico> historicoListModel = new javax.swing.DefaultListModel<>();
            javax.swing.JList<TagHistorico> historicoList = new javax.swing.JList<>(historicoListModel);
            historicoList.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 10));
            historicoList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
            
            // Cores pré-definidas para melhor performance (evitar criar objetos Color toda vez)
            final java.awt.Color corErroOPE10161 = new java.awt.Color(255, 200, 200); // Light red
            final java.awt.Color corTimestampAtualizado = new java.awt.Color(144, 238, 144); // Light green
            
            // Renderer customizado para mostrar background verde quando timestamp foi atualizado
            historicoList.setCellRenderer(new javax.swing.ListCellRenderer<TagHistorico>() {
                @Override
                public java.awt.Component getListCellRendererComponent(
                        javax.swing.JList<? extends TagHistorico> list,
                        TagHistorico value,
                        int index,
                        boolean isSelected,
                        boolean cellHasFocus) {
                    javax.swing.JLabel label = new javax.swing.JLabel(value.toString());
                    label.setOpaque(true);
                    
                    // Prioridade: Erro OPE10161 (vermelho permanente) > Timestamp atualizado (verde temporário) > Seleção normal
                    if (value.isTemErroOPE10161()) {
                        // Background vermelho permanente para erro OPE10161
                        label.setBackground(corErroOPE10161);
                        label.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
                    } else if (value.isTimestampAtualizadoRecentemente()) {
                        // Background verde temporário para timestamp atualizado
                        label.setBackground(corTimestampAtualizado);
                        label.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
                    } else if (isSelected) {
                        label.setBackground(list.getSelectionBackground());
                        label.setForeground(list.getSelectionForeground());
                    } else {
                        label.setBackground(list.getBackground());
                        label.setForeground(list.getForeground());
                    }
                    
                    return label;
                }
            });
            
            // ScrollPane para a lista
            javax.swing.JScrollPane scrollHistorico = new javax.swing.JScrollPane(historicoList);
            scrollHistorico.setVerticalScrollBarPolicy(javax.swing.JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
            scrollHistorico.setHorizontalScrollBarPolicy(javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            
            // Debounce/throttle para atualização da UI (evitar atualizações muito frequentes)
            final long[] ultimaAtualizacaoUI = {0}; // Array para permitir modificação dentro do lambda
            final long INTERVALO_MINIMO_ATUALIZACAO_UI_MS = 200; // 200ms entre atualizações
            
            // Método para atualizar o histórico na interface (com throttling)
            // Usar AtomicReference para permitir referência dentro do próprio lambda
            final java.util.concurrent.atomic.AtomicReference<Runnable> atualizarHistoricoUIRef = 
                new java.util.concurrent.atomic.AtomicReference<>();
            
            Runnable atualizarHistoricoUI = () -> {
                long agora = System.currentTimeMillis();
                long ultima = ultimaAtualizacaoUI[0];
                
                // Se passou menos de 200ms desde a última atualização, agendar para depois
                if (agora - ultima < INTERVALO_MINIMO_ATUALIZACAO_UI_MS) {
                    // Agendar atualização após o intervalo mínimo
                    final Runnable runnableRef = atualizarHistoricoUIRef.get();
                    if (runnableRef != null) {
                        new Thread(() -> {
                            try {
                                Thread.sleep(INTERVALO_MINIMO_ATUALIZACAO_UI_MS - (agora - ultima));
                                runnableRef.run();
                            } catch (InterruptedException ignored) {
                            }
                        }, "throttle-ui-update").start();
                    }
                    return;
                }
                
                ultimaAtualizacaoUI[0] = agora;
                SwingUtilities.invokeLater(() -> {
                    java.util.List<TagHistorico> historico = getHistoricoTags();
                    historicoListModel.clear();
                    // getHistoricoTags() já retorna com os mais recentes primeiro
                    // Adicionar no máximo 100 entradas (mais recentes primeiro)
                    int maxItems = Math.min(100, historico.size());
                    for (int i = 0; i < maxItems; i++) {
                        TagHistorico entrada = historico.get(i);
                        historicoListModel.addElement(entrada);
                    }
                    // Auto-scroll para o topo (mais recente) apenas se necessário
                    if (historicoListModel.getSize() > 0 && historicoList.getSelectedIndex() != 0) {
                        historicoList.setSelectedIndex(0);
                        historicoList.ensureIndexIsVisible(0);
                    }
                    // Forçar repaint para atualizar cores
                    historicoList.repaint();
                });
            };
            
            // Atribuir a referência após criar o Runnable
            atualizarHistoricoUIRef.set(atualizarHistoricoUI);
            
            // Painel superior com botão para limpar histórico
            JPanel pHistoricoTop = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton btnLimparHistorico = new JButton("Limpar Histórico");
            btnLimparHistorico.addActionListener(e -> {
                // Limpar o histórico (ConcurrentLinkedQueue e ConcurrentHashMap - não precisa synchronized)
                historicoTagsQueue.clear();
                historicoTagsMap.clear();
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
                    // COMENTADO TEMPORARIAMENTE
                    /*
                    if (!ehTagComSucesso) {
                        lbApi.setText("aguardando resposta...");
                    }
                    */
                    // Se for tag com sucesso, mantém a última mensagem de sucesso
                }

                @Override
                public void onApiResult(boolean success, String code, String message) {
                    // COMENTADO TEMPORARIAMENTE
                    // lbApi.setText((success ? "OK" : "ERRO") + " - " + message);
                    // Atualizar histórico na interface quando houver resultado da API
                    atualizarHistoricoRef.run();
                }

                @Override
                public void onTimestampUpdated(String code, String message) {
                    // Não mostrar mensagem - apenas atualizar histórico para mostrar background verde
                    // Atualizar histórico na interface quando timestamp for atualizado
                    atualizarHistoricoRef.run();
                }
            };

            jf.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    // Limpar recursos do PWM antes de sair
                    cleanupPwmBuzzer();
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
                            
                            // Piscar LED quando tag é detectada do módulo
                            gpioBlinkLed();
                            
                            // Emitir som do buzzer quando tag é detectada
                            pwmBuzzerBeep();
                            
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