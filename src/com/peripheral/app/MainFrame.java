package com.peripheral.app;

import com.peripheral.core.DeviceModelEntry;
import com.peripheral.core.ParityOption;
import com.peripheral.core.PeripheralCatalog;
import com.peripheral.core.PeripheralDataEvent;
import com.peripheral.core.PeripheralDataListener;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.PeripheralFactory;
import com.peripheral.core.PeripheralType;
import com.peripheral.core.PortProbeFactory;
import com.peripheral.core.PortProbeResult;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.core.RfidConfigurable;
import com.peripheral.core.SerialConnectionConfig;
import com.peripheral.core.SerialPortProber;
import com.peripheral.session.PeripheralSessionManager;
import com.rfid.core.SerialPortDiscovery;
import com.rfid.core.SerialPortInfo;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainFrame extends JFrame {

    private static final int MAX_ROWS = 500;
    private static final int READ_ONCE_TIMEOUT_MS = 2000;
    private static final int SCALE_NO_DATA_WARNING_MS = 8000;

    private final PeripheralSessionManager sessionManager = new PeripheralSessionManager();

    private final JComboBox<PeripheralType> cbPeripheral = new JComboBox<>(PeripheralType.values());
    private final JComboBox<String> cbVendor = new JComboBox<>();
    private final JComboBox<DeviceModelEntry> cbModel = new JComboBox<>();

    private final JComboBox<SerialPortInfo> cbPort = new JComboBox<>();
    private final ThemedButton btnRefreshPorts =
            WorkflowUiTheme.button("Atualizar portas", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnTestPort =
            WorkflowUiTheme.button("Testar porta", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnConnect =
            WorkflowUiTheme.button("Conectar", ThemedButton.Variant.PRIMARY);
    private final ThemedButton btnDisconnect =
            WorkflowUiTheme.button("Desconectar", ThemedButton.Variant.DANGER);
    private final JLabel lbStatus = new JLabel("Selecione periférico, fabricante e modelo.");
    private final JLabel lbDeviceInfo = new JLabel("-");

    private final JPanel rfidOptionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JSpinner spPower = new JSpinner(new SpinnerNumberModel(50, 1, 100, 1));
    private final ThemedButton btnApplyPower =
            WorkflowUiTheme.button("Aplicar potência", ThemedButton.Variant.SECONDARY);
    private final JPanel antennaPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JCheckBox[] antennaChecks = new JCheckBox[16];

    private final JPanel scaleOptionsPanel = new JPanel(new GridBagLayout());
    private final JSpinner spBaud = new JSpinner(new SpinnerNumberModel(9600, 300, 115200, 300));
    private final JSpinner spDataBits = new JSpinner(new SpinnerNumberModel(8, 5, 8, 1));
    private final JSpinner spStopBits = new JSpinner(new SpinnerNumberModel(1, 1, 2, 1));
    private final JComboBox<ParityOption> cbParity = new JComboBox<>(ParityOption.values());

    private final ThemedButton btnToggleContinuous =
            WorkflowUiTheme.button("Iniciar leitura contínua", ThemedButton.Variant.PRIMARY);
    private final ThemedButton btnReadOnce =
            WorkflowUiTheme.button("Ler agora", ThemedButton.Variant.SECONDARY);
    private final DefaultTableModel dataModel = new DefaultTableModel(
            new String[]{"Hora", "Periférico", "Fabricante", "Dado"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable dataTable = new JTable(dataModel);
    private final JTextArea taLog = new JTextArea(6, 40);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    private AutomatedWorkflowPanel workflowPanel;

    private ReadablePeripheral device;
    private DeviceModelEntry selectedModel;
    private boolean continuousActive;
    private Timer scaleNoDataTimer;
    private boolean scaleDataReceivedDuringContinuous;

    public MainFrame() {
        super("Periféricos eship — RFID / Balança");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (workflowPanel != null) {
                    workflowPanel.stopWorkflowIfRunning();
                }
                disconnectDevice();
                sessionManager.disconnectAll();
                dispose();
                System.exit(0);
            }
        });
        buildUi();
        WorkflowUiTheme.styleFrame(this);
        refreshPorts();
        onPeripheralChanged();
        pack();
        setMinimumSize(new Dimension(960, 680));
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void buildUi() {
        setLayout(new BorderLayout(0, 0));

        JPanel manualTab = buildManualTestTab();
        workflowPanel = new AutomatedWorkflowPanel(sessionManager, this::appendLog);
        workflowPanel.setOwnerWindow(this);

        JTabbedPane tabs = new JTabbedPane();
        WorkflowUiTheme.styleTabbedPane(tabs);
        tabs.setBorder(WorkflowUiTheme.empty(0, 12, 12, 12));
        tabs.addTab("Teste manual", manualTab);
        tabs.addTab("Fluxo automatizado", workflowPanel);

        JPanel northStack = new JPanel();
        northStack.setOpaque(false);
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.add(WorkflowUiTheme.createHeader(
                "Periféricos eship",
                "Teste manual de RFID e balança, ou execute o fluxo automatizado de pesagem"));

        add(northStack, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
    }

    private JPanel buildManualTestTab() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        WorkflowUiTheme.stylePanel(root);
        root.setBorder(WorkflowUiTheme.empty(4, 0, 0, 0));

        JPanel selection = new JPanel(new GridBagLayout());
        selection.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        selection.add(createFieldLabel("Periférico:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        selection.add(cbPeripheral, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        selection.add(createFieldLabel("Fabricante:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        selection.add(cbVendor, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        selection.add(createFieldLabel("Modelo:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        cbModel.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof DeviceModelEntry) {
                    setText(((DeviceModelEntry) value).getDisplayLabel());
                }
                return this;
            }
        });
        selection.add(cbModel, gbc);

        JPanel connectionBody = new JPanel();
        connectionBody.setOpaque(false);
        connectionBody.setLayout(new BoxLayout(connectionBody, BoxLayout.Y_AXIS));

        JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        portRow.setOpaque(false);
        portRow.add(createFieldLabel("Porta:"));
        setupPortCombo();
        portRow.add(cbPort);
        portRow.add(btnRefreshPorts);
        portRow.add(btnTestPort);
        portRow.add(btnConnect);
        portRow.add(btnDisconnect);
        connectionBody.add(portRow);

        lbStatus.setFont(WorkflowUiTheme.fontStatus(lbStatus));
        lbStatus.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        lbStatus.setBorder(WorkflowUiTheme.empty(4, 0, 0, 0));
        connectionBody.add(lbStatus);

        lbDeviceInfo.setFont(WorkflowUiTheme.fontMeta(lbDeviceInfo));
        lbDeviceInfo.setForeground(WorkflowUiTheme.TEXT_MUTED);
        connectionBody.add(lbDeviceInfo);

        buildRfidOptions();
        buildScaleOptions();
        connectionBody.add(rfidOptionsPanel);
        connectionBody.add(scaleOptionsPanel);

        JPanel readingBody = new JPanel(new BorderLayout(0, 10));
        readingBody.setOpaque(false);
        JPanel readBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        readBtns.setOpaque(false);
        readBtns.add(btnToggleContinuous);
        readBtns.add(btnReadOnce);
        readingBody.add(readBtns, BorderLayout.NORTH);

        dataTable.setAutoCreateRowSorter(true);
        dataTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        WorkflowUiTheme.styleTable(dataTable);
        JScrollPane scrollData = new JScrollPane(dataTable);
        WorkflowUiTheme.styleScrollPane(scrollData);
        scrollData.setPreferredSize(new Dimension(860, 260));
        readingBody.add(scrollData, BorderLayout.CENTER);

        taLog.setEditable(false);
        taLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        WorkflowUiTheme.styleTextArea(taLog);
        JScrollPane scrollLog = new JScrollPane(taLog);
        WorkflowUiTheme.styleScrollPane(scrollLog);
        scrollLog.setPreferredSize(new Dimension(860, 120));
        readingBody.add(scrollLog, BorderLayout.SOUTH);

        JPanel northManual = new JPanel();
        northManual.setOpaque(false);
        northManual.setLayout(new BoxLayout(northManual, BoxLayout.Y_AXIS));
        northManual.add(WorkflowUiTheme.createSection("Seleção", selection));
        northManual.add(WorkflowUiTheme.createSection("Conexão", connectionBody));

        root.add(northManual, BorderLayout.NORTH);
        root.add(WorkflowUiTheme.createSection("Leitura", readingBody), BorderLayout.CENTER);

        cbPeripheral.addActionListener(e -> onPeripheralChanged());
        cbVendor.addActionListener(e -> onVendorChanged());
        cbModel.addActionListener(e -> onModelChanged());
        btnRefreshPorts.addActionListener(e -> refreshPorts());
        btnTestPort.addActionListener(e -> testPort());
        btnConnect.addActionListener(e -> connectDevice());
        btnDisconnect.addActionListener(e -> disconnectDevice());
        btnApplyPower.addActionListener(e -> applyPower());
        btnToggleContinuous.addActionListener(e -> toggleContinuous());
        btnReadOnce.addActionListener(e -> readOnce());

        setReadingEnabled(false);
        btnDisconnect.setEnabled(false);
        return root;
    }

    private JLabel createFieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(WorkflowUiTheme.fontMeta(label));
        label.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        return label;
    }

    private void buildRfidOptions() {
        rfidOptionsPanel.setOpaque(false);
        rfidOptionsPanel.setBorder(WorkflowUiTheme.empty(8, 0, 0, 0));
        rfidOptionsPanel.add(createFieldLabel("Potência (%):"));
        rfidOptionsPanel.add(spPower);
        rfidOptionsPanel.add(btnApplyPower);
        antennaPanel.setOpaque(false);
        antennaPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.BORDER), "Antenas"));
        for (int i = 0; i < antennaChecks.length; i++) {
            antennaChecks[i] = new JCheckBox(String.valueOf(i));
            if (i == 0) {
                antennaChecks[i].setSelected(true);
            }
            antennaPanel.add(antennaChecks[i]);
        }
        rfidOptionsPanel.add(antennaPanel);
    }

    private void buildScaleOptions() {
        scaleOptionsPanel.setOpaque(false);
        scaleOptionsPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.BORDER), "Opções serial (balança)"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        scaleOptionsPanel.add(new JLabel("Baud rate:"), gbc);
        gbc.gridx = 1;
        scaleOptionsPanel.add(spBaud, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        scaleOptionsPanel.add(new JLabel("Data bits:"), gbc);
        gbc.gridx = 1;
        scaleOptionsPanel.add(spDataBits, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        scaleOptionsPanel.add(new JLabel("Stop bits:"), gbc);
        gbc.gridx = 1;
        scaleOptionsPanel.add(spStopBits, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        scaleOptionsPanel.add(new JLabel("Paridade:"), gbc);
        gbc.gridx = 1;
        scaleOptionsPanel.add(cbParity, gbc);
    }

    private void onPeripheralChanged() {
        disconnectDevice();
        PeripheralType type = (PeripheralType) cbPeripheral.getSelectedItem();
        cbVendor.removeAllItems();
        for (String vendor : PeripheralCatalog.vendorNamesFor(type)) {
            cbVendor.addItem(vendor);
        }
        onVendorChanged();
        boolean isRfid = type == PeripheralType.RFID_READER;
        rfidOptionsPanel.setVisible(isRfid);
        scaleOptionsPanel.setVisible(!isRfid);
        revalidate();
        repaint();
    }

    private void onVendorChanged() {
        disconnectDevice();
        cbModel.removeAllItems();
        PeripheralType type = (PeripheralType) cbPeripheral.getSelectedItem();
        String vendor = (String) cbVendor.getSelectedItem();
        if (type == null || vendor == null) {
            return;
        }
        for (DeviceModelEntry model : PeripheralCatalog.modelsForVendor(type, vendor)) {
            cbModel.addItem(model);
        }
        onModelChanged();
    }

    private void onModelChanged() {
        disconnectDevice();
        selectedModel = (DeviceModelEntry) cbModel.getSelectedItem();
        if (selectedModel == null) {
            return;
        }
        SerialConnectionConfig defaults = selectedModel.getDefaultSerialConfig();
        spBaud.setValue(defaults.getBaudRate());
        spDataBits.setValue(defaults.getDataBits());
        spStopBits.setValue(defaults.getStopBits());
        cbParity.setSelectedItem(defaults.getParity());
        appendLog("Modelo selecionado: " + selectedModel.getDisplayLabel()
                + " | SDK: " + selectedModel.getSdk().getDescription());
    }

    private void setupPortCombo() {
        cbPort.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof SerialPortInfo) {
                    setText(((SerialPortInfo) value).getDisplayLabel());
                }
                return this;
            }
        });
        cbPort.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updatePortComboTooltip();
            }
        });
        cbPort.addActionListener(e -> updatePortComboTooltip());
    }

    private void updatePortComboTooltip() {
        SerialPortInfo info = getSelectedPortInfo();
        if (info != null && !info.isPlaceholder()) {
            cbPort.setToolTipText(info.getDetailTooltip());
        } else {
            cbPort.setToolTipText(null);
        }
    }

    private SerialPortInfo getSelectedPortInfo() {
        Object selected = cbPort.getSelectedItem();
        return selected instanceof SerialPortInfo ? (SerialPortInfo) selected : null;
    }

    private String getSelectedPortName() {
        SerialPortInfo info = getSelectedPortInfo();
        if (info == null || info.isPlaceholder()) {
            return null;
        }
        return info.getSystemPortName();
    }

    private void refreshPorts() {
        String selectedPortName = getSelectedPortName();
        cbPort.removeAllItems();
        try {
            List<SerialPortInfo> ports = SerialPortDiscovery.listPorts();
            if (ports.isEmpty()) {
                cbPort.addItem(SerialPortInfo.placeholder("(nenhuma porta encontrada)"));
            } else {
                for (SerialPortInfo port : ports) {
                    cbPort.addItem(port);
                }
                if (selectedPortName != null) {
                    for (int i = 0; i < cbPort.getItemCount(); i++) {
                        SerialPortInfo item = cbPort.getItemAt(i);
                        if (item != null && selectedPortName.equalsIgnoreCase(item.getSystemPortName())) {
                            cbPort.setSelectedIndex(i);
                            break;
                        }
                    }
                }
            }
            updatePortComboTooltip();
        } catch (RuntimeException e) {
            cbPort.addItem(SerialPortInfo.placeholder("(erro ao listar portas)"));
            appendLog("ERRO portas seriais: " + e.getMessage());
            if (e.getCause() != null) {
                appendLog("Causa: " + e.getCause().getMessage());
            }
            appendLog("Solução: use jSerialComm 2.11.4+ com Java 25, ou JDK 21 LTS.");
            lbStatus.setText("Erro ao carregar jSerialComm — veja o log");
            WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.DANGER);
        }
    }

    private SerialConnectionConfig buildSerialConfig() {
        SerialConnectionConfig cfg = selectedModel != null
                ? selectedModel.getDefaultSerialConfig()
                : SerialConnectionConfig.rfidDefault();
        String port = getSelectedPortName();
        if (port != null) {
            cfg.setPortName(port);
        }
        if (selectedModel != null && selectedModel.getPeripheralType() == PeripheralType.SCALE) {
            cfg.setBaudRate((Integer) spBaud.getValue());
            cfg.setDataBits((Integer) spDataBits.getValue());
            cfg.setStopBits((Integer) spStopBits.getValue());
            cfg.setParity((ParityOption) cbParity.getSelectedItem());
        }
        return cfg;
    }

    private void testPort() {
        if (selectedModel == null) {
            JOptionPane.showMessageDialog(this, "Selecione um modelo.", "Testar porta", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String port = getSelectedPortName();
        if (port == null) {
            JOptionPane.showMessageDialog(this, "Selecione uma porta serial válida.", "Testar porta", JOptionPane.WARNING_MESSAGE);
            return;
        }
        btnTestPort.setEnabled(false);
        btnConnect.setEnabled(false);
        lbStatus.setText("Testando " + port + "...");
        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.TEXT_PRIMARY);

        new SwingWorker<PortProbeResult, Void>() {
            @Override
            protected PortProbeResult doInBackground() {
                SerialPortProber prober = PortProbeFactory.forModel(selectedModel);
                return prober.probe(buildSerialConfig(), PortProbeFactory.defaultTimeoutMs(selectedModel));
            }

            @Override
            protected void done() {
                btnTestPort.setEnabled(device == null);
                btnConnect.setEnabled(device == null);
                try {
                    PortProbeResult result = get();
                    showProbeResultDialog("Resultado do teste", result);
                    appendLog("Teste porta " + port + ": " + result.getStatus() + " — " + result.getMessage());
                    if (result.isMatch()) {
                        lbStatus.setText("Porta OK: " + port);
                        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.SUCCESS);
                    } else if (result.isBlocking()) {
                        lbStatus.setText("Teste falhou: " + result.getMessage());
                        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.DANGER);
                    } else {
                        lbStatus.setText("Porta suspeita: " + port);
                        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.WARNING);
                    }
                } catch (Exception e) {
                    lbStatus.setText("Erro no teste");
                    WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.DANGER);
                    appendLog("ERRO teste porta: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void showProbeResultDialog(String title, PortProbeResult result) {
        int messageType;
        if (result.isMatch()) {
            messageType = JOptionPane.INFORMATION_MESSAGE;
        } else if (result.isBlocking()) {
            messageType = JOptionPane.ERROR_MESSAGE;
        } else {
            messageType = JOptionPane.WARNING_MESSAGE;
        }
        String body = result.getMessage();
        if (!result.getDetail().isEmpty()) {
            body = body + "\n\n" + result.getDetail();
        }
        JOptionPane.showMessageDialog(this, body, title, messageType);
    }

    private void connectDevice() {
        if (selectedModel == null) {
            JOptionPane.showMessageDialog(this, "Selecione um modelo.", "Conexão", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String port = getSelectedPortName();
        if (port == null) {
            JOptionPane.showMessageDialog(this, "Selecione uma porta serial válida.", "Conexão", JOptionPane.WARNING_MESSAGE);
            return;
        }

        btnConnect.setEnabled(false);
        btnTestPort.setEnabled(false);
        lbStatus.setText("Verificando porta " + port + "...");
        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.TEXT_PRIMARY);

        new SwingWorker<PortProbeResult, Void>() {
            @Override
            protected PortProbeResult doInBackground() {
                SerialPortProber prober = PortProbeFactory.forModel(selectedModel);
                return prober.probe(buildSerialConfig(), PortProbeFactory.defaultTimeoutMs(selectedModel));
            }

            @Override
            protected void done() {
                try {
                    PortProbeResult probe = get();
                    if (probe.isBlocking()) {
                        btnConnect.setEnabled(true);
                        btnTestPort.setEnabled(true);
                        lbStatus.setText("Erro: " + probe.getMessage());
                        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.DANGER);
                        showProbeResultDialog("Conexão", probe);
                        appendLog("ERRO verificação porta: " + probe.getMessage());
                        return;
                    }
                    if (probe.isSuspicious()) {
                        String body = probe.getMessage();
                        if (!probe.getDetail().isEmpty()) {
                            body = body + "\n\n" + probe.getDetail();
                        }
                        body = body + "\n\nDeseja conectar mesmo assim?";
                        int choice = JOptionPane.showConfirmDialog(
                                MainFrame.this,
                                body,
                                "Porta suspeita",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE);
                        if (choice != JOptionPane.YES_OPTION) {
                            btnConnect.setEnabled(true);
                            btnTestPort.setEnabled(true);
                            lbStatus.setText("Conexão cancelada — porta suspeita");
                            WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.WARNING);
                            appendLog("Conexão cancelada: porta " + port + " não confirmada pelo usuário");
                            return;
                        }
                    } else if (probe.isMatch()) {
                        appendLog("Porta verificada: " + probe.getMessage());
                    }
                    performConnect(port);
                } catch (Exception e) {
                    btnConnect.setEnabled(true);
                    btnTestPort.setEnabled(true);
                    lbStatus.setText("Erro na verificação");
                    WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.DANGER);
                    appendLog("ERRO verificação: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void performConnect(String port) {
        lbStatus.setText("Conectando...");
        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.TEXT_PRIMARY);

        new SwingWorker<Void, Void>() {
            private String error;

            @Override
            protected Void doInBackground() {
                try {
                    device = PeripheralFactory.create(selectedModel, buildSerialConfig());
                    device.connect(buildSerialConfig());
                    if (device instanceof RfidConfigurable) {
                        RfidConfigurable rfid = (RfidConfigurable) device;
                        rfid.setPowerPercent((Integer) spPower.getValue());
                        rfid.setAntennaIds(collectSelectedAntennas());
                    }
                } catch (Exception e) {
                    error = e.getMessage();
                    if (device != null) {
                        device.disconnect();
                        device = null;
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                btnConnect.setEnabled(true);
                btnTestPort.setEnabled(false);
                if (error != null) {
                    lbStatus.setText("Erro: " + error);
                    WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.DANGER);
                    appendLog("ERRO conexão: " + error);
                    if (selectedModel.getSdk().requiresNativeLibrary()) {
                        appendLog("Dica: " + selectedModel.getSdk().getLibraryHint());
                    }
                    return;
                }
                lbStatus.setText("Conectado em " + port + " — " + selectedModel.getDisplayLabel());
                WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.SUCCESS);
                lbDeviceInfo.setText(device.getDeviceInfo());
                btnDisconnect.setEnabled(true);
                setSelectionEnabled(false);
                setReadingEnabled(true);
                appendLog("Conectado: " + selectedModel.getDisplayLabel() + " @ " + port);
            }
        }.execute();
    }

    private void disconnectDevice() {
        stopContinuousIfNeeded();
        cancelScaleNoDataTimer();
        if (device != null) {
            device.disconnect();
            device = null;
        }
        continuousActive = false;
        btnToggleContinuous.setText("Iniciar leitura contínua");
        lbStatus.setText("Desconectado");
        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.TEXT_PRIMARY);
        lbDeviceInfo.setText("-");
        btnDisconnect.setEnabled(false);
        setSelectionEnabled(true);
        setReadingEnabled(false);
    }

    private void applyPower() {
        if (!(device instanceof RfidConfigurable)) {
            return;
        }
        try {
            ((RfidConfigurable) device).setPowerPercent((Integer) spPower.getValue());
            appendLog("Potência aplicada: " + spPower.getValue() + "%");
        } catch (PeripheralException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Potência", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void toggleContinuous() {
        if (device == null) {
            return;
        }
        if (continuousActive) {
            stopContinuousIfNeeded();
            return;
        }
        try {
            device.startContinuousReading(createListener());
            continuousActive = true;
            btnToggleContinuous.setText("Pausar leitura contínua");
            btnReadOnce.setEnabled(false);
            appendLog("Leitura contínua iniciada");
            if (selectedModel != null && selectedModel.getPeripheralType() == PeripheralType.SCALE) {
                startScaleNoDataWatch();
            }
        } catch (PeripheralException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Leitura", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopContinuousIfNeeded() {
        if (device != null && continuousActive) {
            device.stopContinuousReading();
            continuousActive = false;
            btnToggleContinuous.setText("Iniciar leitura contínua");
            btnReadOnce.setEnabled(true);
            cancelScaleNoDataTimer();
            appendLog("Leitura contínua pausada");
        }
    }

    private void startScaleNoDataWatch() {
        cancelScaleNoDataTimer();
        scaleDataReceivedDuringContinuous = false;
        scaleNoDataTimer = new Timer(SCALE_NO_DATA_WARNING_MS, e -> {
            if (continuousActive && !scaleDataReceivedDuringContinuous) {
                appendLog("AVISO: nenhum peso recebido em " + (SCALE_NO_DATA_WARNING_MS / 1000)
                        + " s — confirme se a porta COM selecionada é a da balança.");
                lbStatus.setText("Sem dados da balança — verifique a porta COM");
                WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.WARNING);
            }
            cancelScaleNoDataTimer();
        });
        scaleNoDataTimer.setRepeats(false);
        scaleNoDataTimer.start();
    }

    private void cancelScaleNoDataTimer() {
        if (scaleNoDataTimer != null) {
            scaleNoDataTimer.stop();
            scaleNoDataTimer = null;
        }
    }

    private void readOnce() {
        if (device == null || continuousActive) {
            return;
        }
        btnReadOnce.setEnabled(false);
        try {
            device.readOnce(READ_ONCE_TIMEOUT_MS, createListener());
            appendLog("Leitura única solicitada...");
            Timer timer = new Timer(READ_ONCE_TIMEOUT_MS + 300, e -> btnReadOnce.setEnabled(device != null && !continuousActive));
            timer.setRepeats(false);
            timer.start();
        } catch (PeripheralException e) {
            btnReadOnce.setEnabled(true);
            JOptionPane.showMessageDialog(this, e.getMessage(), "Leitura", JOptionPane.ERROR_MESSAGE);
        }
    }

    private PeripheralDataListener createListener() {
        return new PeripheralDataListener() {
            @Override
            public void onData(PeripheralDataEvent event) {
                SwingUtilities.invokeLater(() -> appendDataRow(event));
            }

            @Override
            public void onError(Throwable error) {
                SwingUtilities.invokeLater(() -> appendLog("ERRO: " + error.getMessage()));
            }

            @Override
            public void onReadingStateChanged(boolean reading) {
                if (!reading && !continuousActive) {
                    SwingUtilities.invokeLater(() -> btnReadOnce.setEnabled(device != null));
                }
            }
        };
    }

    private void appendDataRow(PeripheralDataEvent event) {
        if (event == null) {
            return;
        }
        if (continuousActive && event.getSource() != null
                && event.getSource().getPeripheralType() == PeripheralType.SCALE) {
            scaleDataReceivedDuringContinuous = true;
            cancelScaleNoDataTimer();
        }
        String time = timeFormat.format(new Date(event.getTimestampMs()));
        DeviceModelEntry src = event.getSource();
        String peripheral = src != null ? src.getPeripheralType().getLabel() : "-";
        String vendor = src != null ? src.getVendorName() : "-";
        dataModel.insertRow(0, new Object[]{time, peripheral, vendor, event.getDisplayText()});
        while (dataModel.getRowCount() > MAX_ROWS) {
            dataModel.removeRow(dataModel.getRowCount() - 1);
        }
    }

    private int[] collectSelectedAntennas() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (int i = 0; i < antennaChecks.length; i++) {
            if (antennaChecks[i].isSelected()) {
                ids.add(i);
            }
        }
        if (ids.isEmpty()) {
            ids.add(0);
        }
        List<Integer> list = new ArrayList<>(ids);
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private void setSelectionEnabled(boolean enabled) {
        cbPeripheral.setEnabled(enabled);
        cbVendor.setEnabled(enabled);
        cbModel.setEnabled(enabled);
        cbPort.setEnabled(enabled);
        btnRefreshPorts.setEnabled(enabled);
        btnTestPort.setEnabled(enabled);
        btnConnect.setEnabled(enabled);
        spPower.setEnabled(enabled);
        btnApplyPower.setEnabled(enabled);
        for (JCheckBox cb : antennaChecks) {
            if (cb != null) {
                cb.setEnabled(enabled);
            }
        }
        spBaud.setEnabled(enabled);
        spDataBits.setEnabled(enabled);
        spStopBits.setEnabled(enabled);
        cbParity.setEnabled(enabled);
    }

    private void setReadingEnabled(boolean enabled) {
        btnToggleContinuous.setEnabled(enabled);
        btnReadOnce.setEnabled(enabled);
    }

    private void appendLog(String msg) {
        taLog.append("[" + timeFormat.format(new Date()) + "] " + msg + "\n");
        taLog.setCaretPosition(taLog.getDocument().getLength());
    }
}
