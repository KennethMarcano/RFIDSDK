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

public class Test {
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
    private static final String ENDPOINT_URL = "https://fulle.eship.com.br/v3/?api&funcao=webServicePutProduto";/* configure aqui */
    private static final String API_TOKEN = "f060512c2c3b0f8866df1dda5cef80a9";/* configure aqui (ex.: "eyJ...") */
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
        if (API_TOKEN != null && API_TOKEN.trim().length() > 0) {
            headers.put("Api", API_TOKEN.trim());
        }
        return headers;
    }

    private static String buildTagJsonBody(InventoryTag tag) {
        String epcHex = safeString(tag != null ? tag.getEpc() : "");
        // System.out.println("epcHex: " + epcHex);
        String epcHexClean = epcHex.replaceAll("[^0-9A-Fa-f]", "");
        /* Converter bytes HEX em ASCII e manter apenas dígitos */
        String epcDecimal;
        if (epcHexClean.length() >= 2) {
            try {
                int hexLen = epcHexClean.length();
                if ((hexLen & 1) == 1) {
                    hexLen -= 1;/* ignora nibble final solto */
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
        String osName = safeString(System.getProperty("os.name"));
        long ts = System.currentTimeMillis();
        /* JSON simples e padronizado; ajuste conforme sua API */
        StringBuilder sb = new StringBuilder(256);
        sb.append("{")
                // .append("\"timestamp\":").append(ts).append(",")
                .append("\"descricao\":").append("\"EDITADO PELO RFID\"").append(",")
                // .append("\"sourceOs\":\"").append(escapeJson(osName)).append("\",")
                .append("\"cnpjCadastro\":\"").append("62.742.738/0001-81").append("\",")
                // .append("\"epcHex\":\"").append(escapeJson(epcHexClean)).append("\",")
                .append("\"idCodigoProduto\":\"").append(escapeJson(epcDecimal)).append("\"")
                .append("}");
        return sb.toString();
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

    private static void postJson(String urlStr, String jsonBody, Map<String, String> headers) {
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
                if (code < 200 || code >= 300) {
                    System.err.println("HTTP post failed: " + code);
                }
                if (code >= 200 && code < 300) {
                    /* Sucesso HTTP: pulso no GPIO (alto por ~300ms) */
                    gpioSetHighPulse();
                }
                break;/* sucesso ou erro não-redirecionado: sai do loop */
            } catch (Exception e) {
                System.err.println("HTTP post error: " + e.getMessage());
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

    private static void sendTag(InventoryTag tag) {
        if (ENDPOINT_URL == null || ENDPOINT_URL.trim().isEmpty()) {
            return;
        }
        final String body = buildTagJsonBody(tag);
        final Map<String, String> headers = buildDefaultHeaders();
        new Thread(() -> postJson(ENDPOINT_URL, body, headers), "rfid-http-post").start();
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
        for (SerialPort commPort : commPorts) {
            System.out.println("CommPort:" + commPort.getDescriptivePortName() + "-->" + commPort.getSystemPortName());
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
            gpioInitIfLinux();
            LinuxDemo demo = new LinuxDemo();
            boolean connect = demo.connect();
            if (connect) {
                demo.startInventory();
            }
        }
    }

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
                    String hexString = ArrayUtils.bytesToHexString(onSend, 0, onSend.length);
                    System.out.println("---reader send :" + hexString);
//                endMs = System.currentTimeMillis();
                }
            }, new Consumer<byte[]>() {
                @Override
                public void accept(byte[] onReceive) throws Exception {
                    String hexString = ArrayUtils.bytesToHexString(onReceive, 0, onReceive.length);
                    System.out.println("===reader recv:" + hexString);
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
            sendTag(tag);
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
        }

        private boolean connect() {
//            NetworkHandle handle = new NetworkHandle("169.254.252.120", 4001);/*34*/
            ConnectHandle handle = new JSerialPortHandle(mPortName, 115200);/*78*/

            mReader = ReaderImpl.create(antCount);
            boolean linkSuccess = mReader.connect(handle);
            if (!linkSuccess) {
                System.err.println(LLLog.getStackTrace(2) + "\nReader connect fail...");
                return false;
            }
            inventoryParamConfig();

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
                    String hexString = ArrayUtils.bytesToHexString(onSend, 0, onSend.length);
                    System.out.println("---reader send :" + hexString);
//                endMs = System.currentTimeMillis();
                }
            }, new Consumer<byte[]>() {
                @Override
                public void accept(byte[] onReceive) throws Exception {
                    System.out.println("===reader recv:" + ArrayUtils.bytesToHexString(onReceive, 0, onReceive.length));
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
                            System.out.println("tag: " + tag.toString());
                            /* Enviar tag via HTTP (Linux) */
                            sendTag(tag);
                        }
                    }).setOnInventoryTagEndSuccess(new Consumer<InventoryTagEnd>() {
                        @Override
                        public void accept(InventoryTagEnd tagEnd) throws Exception {
                            System.out.println("tag: " + tagEnd.toString());

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