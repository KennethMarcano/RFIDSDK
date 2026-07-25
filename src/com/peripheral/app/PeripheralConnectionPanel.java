package com.peripheral.app;

import com.peripheral.core.DeviceModelEntry;
import com.peripheral.core.ParityOption;
import com.peripheral.core.PeripheralCatalog;
import com.peripheral.core.PeripheralDataEvent;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.PeripheralSafeIo;
import com.peripheral.core.PeripheralType;
import com.peripheral.core.PortProbeFactory;
import com.peripheral.core.PortProbeResult;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.core.RfidConfigurable;
import com.peripheral.core.PeripheralDataListener;
import com.peripheral.core.SerialConnectionConfig;
import com.peripheral.core.SerialPortProber;
import com.peripheral.scale.ScaleWeightFormat;
import com.peripheral.session.PeripheralConnectionHandle;
import com.peripheral.session.PeripheralSessionManager;
import com.peripheral.session.PeripheralSlot;
import com.peripheral.util.LinuxUsbSerialReset;
import com.rfid.core.SerialPortDiscovery;
import com.rfid.core.SerialPortInfo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class PeripheralConnectionPanel extends JPanel {

    private static final int MONITOR_COLUMN_WIDTH = 330;

    public interface ConnectionListener {
        void onConnectionChanged(PeripheralSlot slot, boolean connected);

        void onLog(String message);
    }

    private final PeripheralSlot slot;
    private final PeripheralSessionManager sessionManager;
    private final ConnectionListener connectionListener;
    private final Consumer<String> portConflictChecker;

    private final JComboBox<String> cbVendor = new JComboBox<>();
    private final JComboBox<DeviceModelEntry> cbModel = new JComboBox<>();
    private final JComboBox<SerialPortInfo> cbPort = new JComboBox<>();
    private final ThemedButton btnRefreshPorts =
            WorkflowUiTheme.button("Atualizar", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnRescanUsb =
            WorkflowUiTheme.button("Reescanear USB", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnTestPort =
            WorkflowUiTheme.button("Testar porta", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnConnect =
            WorkflowUiTheme.button("Conectar", ThemedButton.Variant.PRIMARY);
    private final ThemedButton btnDisconnect =
            WorkflowUiTheme.button("Desconectar", ThemedButton.Variant.DANGER);
    private final JLabel lbStatus = new JLabel("Desconectado");
    private final JLabel lbDeviceInfo = new JLabel("-");

    private final JPanel rfidOptionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JSpinner spPower = new JSpinner(new SpinnerNumberModel(100, 1, 100, 1));
    private final ThemedButton btnApplyPower =
            WorkflowUiTheme.button("Aplicar potência", ThemedButton.Variant.SECONDARY);
    private final JLabel lbPowerDbm = new JLabel("— dBm");
    private final JPanel antennaPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JCheckBox[] antennaChecks = new JCheckBox[16];

    private final JPanel scaleOptionsPanel = new JPanel(new GridBagLayout());
    private final JSpinner spBaud = new JSpinner(new SpinnerNumberModel(9600, 300, 115200, 300));
    private final JSpinner spDataBits = new JSpinner(new SpinnerNumberModel(8, 5, 8, 1));
    private final JSpinner spStopBits = new JSpinner(new SpinnerNumberModel(1, 1, 2, 1));
    private final JComboBox<ParityOption> cbParity = new JComboBox<>(ParityOption.values());

    private final JPanel liveWeightPanel = new JPanel(new BorderLayout(8, 4));
    private final JLabel lbLiveWeight = new JLabel(
            ScaleWeightFormat.PLACEHOLDER + " " + ScaleWeightFormat.UNIT, SwingConstants.CENTER);
    private final JLabel lbLiveWeightHint = new JLabel("Conecte a balança para ver o peso");

    private final JPanel rfidTestPanel = new JPanel(new BorderLayout(0, 6));
    private final RfidTagMonitorPanel tagMonitor = new RfidTagMonitorPanel("TESTE DE LEITURA RFID");
    private final ThemedButton btnToggleRfidTest =
            WorkflowUiTheme.button("Iniciar teste", ThemedButton.Variant.PRIMARY);
    private final ThemedButton btnClearTags =
            WorkflowUiTheme.button("Limpar", ThemedButton.Variant.SECONDARY);

    private DeviceModelEntry selectedModel;
    private Window ownerWindow;
    private boolean liveWeightActive;
    private boolean liveRfidActive;
    private boolean busyOperation;
    private boolean closingStopRequested;

    public PeripheralConnectionPanel(PeripheralSlot slot, PeripheralSessionManager sessionManager,
                                     ConnectionListener connectionListener,
                                     Consumer<String> portConflictChecker) {
        super(new BorderLayout(4, 4));
        this.slot = slot;
        this.sessionManager = sessionManager;
        this.connectionListener = connectionListener;
        this.portConflictChecker = portConflictChecker;
        setOpaque(false);
        buildUi();
        refreshVendors();
        refreshPorts();
    }

    public void setOwnerWindow(Window owner) {
        this.ownerWindow = owner;
    }

    public PeripheralSlot getSlot() {
        return slot;
    }

    public boolean isConnected() {
        return sessionManager.isConnected(slot);
    }

    public String getSelectedPortName() {
        SerialPortInfo info = getSelectedPortInfo();
        if (info == null || info.isPlaceholder()) {
            return null;
        }
        return info.getSystemPortName();
    }

    public void refreshPortsFromOutside() {
        refreshPorts();
    }

    private void buildUi() {
        WorkflowUiTheme.styleFormCombo(cbVendor, 160, 320);
        WorkflowUiTheme.styleFormCombo(cbModel, 180, 360);
        WorkflowUiTheme.styleFormCombo(cbPort, 150, 210);
        WorkflowUiTheme.styleFormCombo(cbParity, 110, 150);
        WorkflowUiTheme.styleCompactSpinner(spPower);
        WorkflowUiTheme.styleCompactSpinner(spBaud);
        WorkflowUiTheme.styleCompactSpinner(spDataBits);
        WorkflowUiTheme.styleCompactSpinner(spStopBits);

        JPanel selection = new JPanel(new GridBagLayout());
        selection.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 0, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        selection.add(fieldLabel("Fabricante:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        selection.add(cbVendor, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        selection.add(fieldLabel("Modelo:"), gbc);
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

        setupPortCombo();
        JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        portRow.setOpaque(false);
        portRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        portRow.add(fieldLabel("Porta:"));
        portRow.add(cbPort);
        portRow.add(btnRefreshPorts);
        if (LinuxUsbSerialReset.isSupported()) {
            btnRescanUsb.setToolTipText(
                    "Reinicia só conversores USB-serial (RFID/balança). Use se a porta não aparecer após o boot.");
            portRow.add(btnRescanUsb);
        }

        JPanel connectRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        connectRow.setOpaque(false);
        connectRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        connectRow.add(btnTestPort);
        connectRow.add(btnConnect);
        connectRow.add(btnDisconnect);

        buildRfidOptions();
        buildScaleOptions();
        buildLiveWeightPanel();
        buildRfidTestPanel();
        boolean isRfid = slot.getPeripheralType() == PeripheralType.RFID_READER;
        boolean isScale = slot.getPeripheralType() == PeripheralType.SCALE;
        rfidOptionsPanel.setVisible(isRfid);
        scaleOptionsPanel.setVisible(isScale);

        lbStatus.setFont(WorkflowUiTheme.fontStatus(lbStatus));
        lbStatus.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        lbStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbDeviceInfo.setFont(WorkflowUiTheme.fontMeta(lbDeviceInfo));
        lbDeviceInfo.setForeground(WorkflowUiTheme.TEXT_MUTED);
        lbDeviceInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        selection.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel settings = new JPanel();
        settings.setOpaque(false);
        settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));
        settings.add(selection);
        settings.add(portRow);
        settings.add(connectRow);
        settings.add(lbStatus);
        settings.add(lbDeviceInfo);
        settings.add(rfidOptionsPanel);
        settings.add(scaleOptionsPanel);

        // O monitor fica sempre visível ao lado das configurações, sem depender de rolagem.
        JPanel monitorHost = new JPanel(new BorderLayout());
        monitorHost.setOpaque(false);
        monitorHost.setBorder(WorkflowUiTheme.empty(0, 10, 0, 0));
        monitorHost.setPreferredSize(new Dimension(MONITOR_COLUMN_WIDTH, 10));
        monitorHost.setMinimumSize(new Dimension(MONITOR_COLUMN_WIDTH, 10));
        monitorHost.add(isRfid ? rfidTestPanel : liveWeightPanel, BorderLayout.CENTER);

        add(WorkflowUiTheme.wrapVerticalScroll(settings), BorderLayout.CENTER);
        add(monitorHost, BorderLayout.EAST);

        cbVendor.addActionListener(e -> onVendorChanged());
        cbModel.addActionListener(e -> onModelChanged());
        btnRefreshPorts.addActionListener(e -> refreshPorts());
        btnRescanUsb.addActionListener(e -> rescanUsbSerial());
        btnTestPort.addActionListener(e -> testPort());
        btnConnect.addActionListener(e -> connectDevice());
        btnDisconnect.addActionListener(e -> disconnectDevice());
        btnApplyPower.addActionListener(e -> applyPower());
        btnToggleRfidTest.addActionListener(e -> toggleRfidTest());
        btnClearTags.addActionListener(e -> {
            tagMonitor.reset();
            tagMonitor.setHint(liveRfidActive
                    ? "Aproxime as tags do leitor..."
                    : "Toque em Iniciar teste para ler as tags.");
        });

        btnDisconnect.setEnabled(false);
        setSelectionEnabled(true);
        updateRfidTestControls();
    }

    private JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(WorkflowUiTheme.fontMeta(label));
        label.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        return label;
    }

    private void buildRfidOptions() {
        rfidOptionsPanel.setOpaque(false);
        rfidOptionsPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 6, 2));
        rfidOptionsPanel.setBorder(WorkflowUiTheme.empty(6, 0, 0, 0));
        rfidOptionsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        rfidOptionsPanel.add(fieldLabel("Potência (%):"));
        rfidOptionsPanel.add(spPower);
        rfidOptionsPanel.add(lbPowerDbm);
        rfidOptionsPanel.add(btnApplyPower);

        lbPowerDbm.setFont(WorkflowUiTheme.fontMeta(lbPowerDbm));
        lbPowerDbm.setForeground(WorkflowUiTheme.TEXT_SECONDARY);

        antennaPanel.setOpaque(false);
        antennaPanel.setLayout(new GridLayout(0, 4, 2, 0));
        antennaPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.BORDER), "Antenas"));
        for (int i = 0; i < antennaChecks.length; i++) {
            int antennaId = i + 1; // canais 1..16 — sem opção 0
            antennaChecks[i] = new JCheckBox(String.valueOf(antennaId));
            antennaChecks[i].setOpaque(false);
            antennaChecks[i].setFont(WorkflowUiTheme.fontChip(antennaChecks[i]));
            antennaChecks[i].setIconTextGap(2);
            if (antennaId == 1) {
                antennaChecks[i].setSelected(true);
            }
            antennaPanel.add(antennaChecks[i]);
        }
        rfidOptionsPanel.add(antennaPanel);
    }

    private void buildRfidTestPanel() {
        rfidTestPanel.setOpaque(false);
        rfidTestPanel.setBorder(WorkflowUiTheme.empty(8, 0, 0, 0));
        rfidTestPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        tagMonitor.setHint("Conecte o leitor e toque em Iniciar teste.");

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(btnToggleRfidTest);
        actions.add(btnClearTags);

        rfidTestPanel.add(tagMonitor, BorderLayout.CENTER);
        rfidTestPanel.add(actions, BorderLayout.SOUTH);
    }

    private void buildScaleOptions() {
        scaleOptionsPanel.setOpaque(false);
        scaleOptionsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        scaleOptionsPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.BORDER), "Opções serial"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        scaleOptionsPanel.add(fieldLabel("Baud:"), gbc);
        gbc.gridx = 1;
        scaleOptionsPanel.add(spBaud, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        scaleOptionsPanel.add(fieldLabel("Data bits:"), gbc);
        gbc.gridx = 1;
        scaleOptionsPanel.add(spDataBits, gbc);
        gbc.gridx = 0;
        gbc.gridy = 2;
        scaleOptionsPanel.add(fieldLabel("Stop bits:"), gbc);
        gbc.gridx = 1;
        scaleOptionsPanel.add(spStopBits, gbc);
        gbc.gridx = 0;
        gbc.gridy = 3;
        scaleOptionsPanel.add(fieldLabel("Paridade:"), gbc);
        gbc.gridx = 1;
        scaleOptionsPanel.add(cbParity, gbc);
    }

    private void buildLiveWeightPanel() {
        liveWeightPanel.setOpaque(true);
        liveWeightPanel.setBackground(WorkflowUiTheme.MONITOR_BG);
        liveWeightPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.MONITOR_BORDER, 1),
                WorkflowUiTheme.empty(10, 12, 10, 12)));
        liveWeightPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel caption = new JLabel("PESO EM TEMPO REAL");
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, 12f));
        caption.setForeground(WorkflowUiTheme.MONITOR_CAPTION);

        // Monoespaçada: os 5 dígitos mantêm sempre a mesma largura.
        lbLiveWeight.setFont(new Font(Font.MONOSPACED, Font.BOLD, 34));
        lbLiveWeight.setForeground(Color.WHITE);

        lbLiveWeightHint.setFont(WorkflowUiTheme.fontMeta(lbLiveWeightHint));
        lbLiveWeightHint.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        lbLiveWeightHint.setHorizontalAlignment(SwingConstants.CENTER);

        liveWeightPanel.add(caption, BorderLayout.NORTH);
        liveWeightPanel.add(lbLiveWeight, BorderLayout.CENTER);
        liveWeightPanel.add(lbLiveWeightHint, BorderLayout.SOUTH);
    }

    public void syncFromSession() {
        if (!isConnected()) {
            resetLiveWeightDisplay();
            updateRfidTestControls();
            return;
        }
        PeripheralConnectionHandle handle = sessionManager.getHandle(slot);
        if (handle != null && handle.getModel() != null) {
            selectedModel = handle.getModel();
            lbDeviceInfo.setText(sessionManager.getDevice(slot).getDeviceInfo());
        }
        String port = sessionManager.getHandle(slot) != null
                ? sessionManager.getHandle(slot).getPortName() : null;
        lbStatus.setText(port != null ? "Conectado em " + port : "Conectado");
        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.SUCCESS);
        btnDisconnect.setEnabled(true);
        setSelectionEnabled(false);
        startLiveWeightReading();
        startLiveRfidReading();
    }

    public void startLiveWeightReading() {
        if (slot.getPeripheralType() != PeripheralType.SCALE) {
            return;
        }
        ReadablePeripheral device = sessionManager.getDevice(slot);
        if (device == null || !device.isConnected()) {
            resetLiveWeightDisplay();
            return;
        }
        try {
            if (device.isReading()) {
                PeripheralSafeIo.stopReading(device);
            }
            liveWeightActive = true;
            lbLiveWeightHint.setText("Atualizando continuamente — coloque o item sobre a balança");
            device.startContinuousReading(new PeripheralDataListener() {
                @Override
                public void onData(PeripheralDataEvent event) {
                    SwingUtilities.invokeLater(() -> updateLiveWeight(event));
                }

                @Override
                public void onError(Throwable error) {
                    SwingUtilities.invokeLater(() -> handleDeviceError(error));
                }
            });
        } catch (PeripheralException e) {
            liveWeightActive = false;
            lbLiveWeightHint.setText("Não foi possível iniciar a leitura: " + e.getMessage());
            WorkflowUiTheme.setStatusColor(lbLiveWeightHint, WorkflowUiTheme.WARNING);
            log("ERRO peso ao vivo: " + e.getMessage());
        }
    }

    public void stopLiveWeightReading() {
        if (!liveWeightActive && slot.getPeripheralType() != PeripheralType.SCALE) {
            return;
        }
        liveWeightActive = false;
        ReadablePeripheral device = sessionManager.getDevice(slot);
        if (device != null) {
            PeripheralSafeIo.stopReading(device);
        }
    }

    private void updateLiveWeight(PeripheralDataEvent event) {
        if (!liveWeightActive || event == null) {
            return;
        }
        Double kg = ScaleWeightFormat.parseKg(event.getWeight());
        if (kg == null) {
            return;
        }
        boolean stable = Boolean.TRUE.equals(event.getStable());
        lbLiveWeight.setText(ScaleWeightFormat.formatGramsWithUnit(kg));
        lbLiveWeight.setForeground(stable ? WorkflowUiTheme.MONITOR_VALUE : Color.WHITE);
        if (ScaleWeightFormat.isOverload(kg)) {
            lbLiveWeightHint.setText("!  Acima da capacidade de "
                    + ScaleWeightFormat.MAX_GRAMS + " " + ScaleWeightFormat.UNIT);
            WorkflowUiTheme.setStatusColor(lbLiveWeightHint, WorkflowUiTheme.MONITOR_ALERT);
        } else {
            lbLiveWeightHint.setText(stable
                    ? "●  PESO ESTÁVEL"
                    : "○  Aguardando estabilização...");
            WorkflowUiTheme.setStatusColor(lbLiveWeightHint,
                    stable ? WorkflowUiTheme.MONITOR_VALUE : WorkflowUiTheme.MONITOR_ALERT);
        }
    }

    private void resetLiveWeightDisplay() {
        lbLiveWeight.setText(ScaleWeightFormat.PLACEHOLDER + " " + ScaleWeightFormat.UNIT);
        lbLiveWeight.setForeground(Color.WHITE);
        lbLiveWeightHint.setText("Conecte a balança para ver o peso");
        lbLiveWeightHint.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
    }

    /** Interrompe qualquer leitura de teste (balança ou RFID) iniciada por este painel. */
    public void stopLiveReading() {
        stopLiveWeightReading();
        stopLiveRfidReading();
    }

    /**
     * Encerramento seguro em background (não bloqueia EDT).
     * Usar ao fechar o diálogo ou desconectar.
     */
    public void stopLiveReadingAsync(Runnable onDone) {
        showBusy("Encerrando leitura...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                ReadablePeripheral device = sessionManager.getDevice(slot);
                liveWeightActive = false;
                liveRfidActive = false;
                PeripheralSafeIo.stopReading(device);
                return null;
            }

            @Override
            protected void done() {
                hideBusy();
                updateRfidTestControls();
                if (onDone != null) {
                    onDone.run();
                }
            }
        }.execute();
    }

    private void showBusy(String message) {
        busyOperation = true;
        Window w = ownerWindow != null ? ownerWindow : SwingUtilities.getWindowAncestor(this);
        if (w != null) {
            WorkflowUiTheme.showBusy(w, message);
        } else {
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            lbStatus.setText(message);
            WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.WARNING);
        }
    }

    private void hideBusy() {
        busyOperation = false;
        Window w = ownerWindow != null ? ownerWindow : SwingUtilities.getWindowAncestor(this);
        if (w != null) {
            WorkflowUiTheme.hideBusy(w);
        }
        setCursor(Cursor.getDefaultCursor());
    }

    private void toggleRfidTest() {
        if (liveRfidActive) {
            showBusy("Parando teste RFID...");
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    liveRfidActive = false;
                    ReadablePeripheral device = sessionManager.getDevice(slot);
                    PeripheralSafeIo.stopReading(device);
                    return null;
                }

                @Override
                protected void done() {
                    hideBusy();
                    tagMonitor.setHint("Teste pausado — " + tagMonitor.getUniqueTagCount()
                            + " tag(s) única(s) em " + tagMonitor.getTotalReads() + " leitura(s).");
                    log("Teste RFID parado: " + tagMonitor.getUniqueTagCount() + " tag(s) única(s), "
                            + tagMonitor.getTotalReads() + " leitura(s)");
                    updateRfidTestControls();
                }
            }.execute();
        } else {
            startLiveRfidReading();
        }
    }

    public void startLiveRfidReading() {
        if (slot.getPeripheralType() != PeripheralType.RFID_READER) {
            return;
        }
        ReadablePeripheral device = sessionManager.getDevice(slot);
        if (device == null || !device.isConnected()) {
            tagMonitor.setHint("Conecte o leitor e toque em Iniciar teste.");
            updateRfidTestControls();
            return;
        }
        try {
            if (device.isReading()) {
                PeripheralSafeIo.stopReading(device);
            }
            liveRfidActive = true;
            tagMonitor.setHint("Aproxime as tags do leitor...");
            device.startContinuousReading(new PeripheralDataListener() {
                @Override
                public void onData(PeripheralDataEvent event) {
                    SwingUtilities.invokeLater(() -> registerTagEvent(event));
                }

                @Override
                public void onError(Throwable error) {
                    SwingUtilities.invokeLater(() -> handleDeviceError(error));
                }
            });
            log("Teste RFID iniciado (" + slot.getLabel() + ")");
        } catch (PeripheralException e) {
            liveRfidActive = false;
            tagMonitor.setHint("Não foi possível iniciar a leitura: " + e.getMessage());
            log("ERRO teste RFID: " + e.getMessage());
        }
        updateRfidTestControls();
    }

    public void stopLiveRfidReading() {
        if (slot.getPeripheralType() != PeripheralType.RFID_READER) {
            return;
        }
        boolean wasActive = liveRfidActive;
        liveRfidActive = false;
        ReadablePeripheral device = sessionManager.getDevice(slot);
        if (device != null) {
            PeripheralSafeIo.stopReading(device);
        }
        if (wasActive) {
            tagMonitor.setHint("Teste pausado — " + tagMonitor.getUniqueTagCount()
                    + " tag(s) única(s) em " + tagMonitor.getTotalReads() + " leitura(s).");
            log("Teste RFID parado: " + tagMonitor.getUniqueTagCount() + " tag(s) única(s), "
                    + tagMonitor.getTotalReads() + " leitura(s)");
        }
        updateRfidTestControls();
    }

    private void handleDeviceError(Throwable error) {
        String msg = error != null && error.getMessage() != null
                ? error.getMessage()
                : "Erro desconhecido no dispositivo";
        log("ERRO " + slot.getLabel() + ": " + msg);
        if (PeripheralSafeIo.looksLikeConnectionLoss(error) || !isConnected()) {
            handleConnectionLost(msg);
            return;
        }
        lbStatus.setText("Aviso: " + msg);
        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.WARNING);
        if (slot.getPeripheralType() == PeripheralType.RFID_READER) {
            tagMonitor.setHint("Aviso do leitor: " + msg);
        }
    }

    private void handleConnectionLost(String detail) {
        if (busyOperation && closingStopRequested) {
            return;
        }
        liveRfidActive = false;
        liveWeightActive = false;
        showBusy("Conexão perdida — recuperando...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                sessionManager.disconnect(slot);
                return null;
            }

            @Override
            protected void done() {
                hideBusy();
                lbStatus.setText("Conexão perdida");
                WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.DANGER);
                lbDeviceInfo.setText("-");
                lbPowerDbm.setText("— dBm");
                btnDisconnect.setEnabled(false);
                setSelectionEnabled(true);
                resetLiveWeightDisplay();
                if (slot.getPeripheralType() == PeripheralType.RFID_READER) {
                    tagMonitor.setHint("Conexão com o leitor foi perdida. Reconecte e tente novamente.");
                }
                updateRfidTestControls();
                notifyConnectionChanged(false);
                log("Conexão perdida (" + slot.getLabel() + "): " + detail);
                JOptionPane.showMessageDialog(
                        getDialogParent(),
                        "A conexão com o " + slot.getLabel().toLowerCase()
                                + " foi perdida.\n\nDetalhe: " + detail
                                + "\n\nA aplicação continua funcionando — reconecte o dispositivo.",
                        "Conexão perdida",
                        JOptionPane.WARNING_MESSAGE);
            }
        }.execute();
    }

    private void registerTagEvent(PeripheralDataEvent event) {
        if (!liveRfidActive || event == null) {
            return;
        }
        String code = event.getCode();
        if (code == null || code.isEmpty()) {
            code = event.getEpc();
        }
        if (code == null || code.isEmpty()) {
            code = event.getDisplayText();
        }
        tagMonitor.registerTag(code);
    }

    private void updateRfidTestControls() {
        if (slot.getPeripheralType() != PeripheralType.RFID_READER) {
            return;
        }
        boolean connected = isConnected();
        btnToggleRfidTest.setEnabled(connected);
        btnClearTags.setEnabled(connected || tagMonitor.getTotalReads() > 0);
        btnToggleRfidTest.setText(liveRfidActive ? "Parar teste" : "Iniciar teste");
        btnToggleRfidTest.setVariant(liveRfidActive
                ? ThemedButton.Variant.DANGER
                : ThemedButton.Variant.PRIMARY);
    }

    private void refreshVendors() {
        cbVendor.removeAllItems();
        for (String vendor : PeripheralCatalog.vendorNamesFor(slot.getPeripheralType())) {
            cbVendor.addItem(vendor);
        }
        onVendorChanged();
    }

    private void onVendorChanged() {
        if (isConnected()) {
            return;
        }
        cbModel.removeAllItems();
        String vendor = (String) cbVendor.getSelectedItem();
        if (vendor == null) {
            return;
        }
        for (DeviceModelEntry model : PeripheralCatalog.modelsForVendor(slot.getPeripheralType(), vendor)) {
            cbModel.addItem(model);
        }
        onModelChanged();
    }

    private void onModelChanged() {
        if (isConnected()) {
            return;
        }
        selectedModel = (DeviceModelEntry) cbModel.getSelectedItem();
        if (selectedModel == null) {
            return;
        }
        SerialConnectionConfig defaults = selectedModel.getDefaultSerialConfig();
        spBaud.setValue(defaults.getBaudRate());
        spDataBits.setValue(defaults.getDataBits());
        spStopBits.setValue(defaults.getStopBits());
        cbParity.setSelectedItem(defaults.getParity());
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

    private void refreshPorts() {
        String selectedPortName = getSelectedPortName();
        cbPort.removeAllItems();
        try {
            List<SerialPortInfo> ports = SerialPortDiscovery.listPorts();
            if (ports.isEmpty()) {
                cbPort.addItem(SerialPortInfo.placeholder("(nenhuma porta serial encontrada)"));
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
            log("ERRO portas (" + slot.getLabel() + "): " + e.getMessage());
            lbStatus.setText("Erro ao listar portas");
            WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.DANGER);
        }
    }

    private void rescanUsbSerial() {
        if (!LinuxUsbSerialReset.isSupported() || busyOperation) {
            return;
        }
        showBusy("Reescaneando USB-serial...");
        btnRescanUsb.setEnabled(false);
        new Thread(() -> {
            LinuxUsbSerialReset.Result result;
            try {
                result = LinuxUsbSerialReset.resetPreferredSerialAdapters();
            } catch (Exception e) {
                result = LinuxUsbSerialReset.Result.failed(e.getMessage());
            }
            final LinuxUsbSerialReset.Result finalResult = result;
            SwingUtilities.invokeLater(() -> {
                hideBusy();
                refreshPorts();
                log("[USB] " + finalResult);
                if (finalResult.getResetCount() > 0) {
                    lbStatus.setText("USB reescaneado");
                    WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.SUCCESS);
                } else if (finalResult.hasErrors()) {
                    lbStatus.setText("USB: sem permissão — use ./iniciar.sh");
                    WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.WARNING);
                } else {
                    lbStatus.setText("USB: nenhum conversor encontrado");
                    WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.TEXT_SECONDARY);
                }
                setSelectionEnabled(!sessionManager.isConnected(slot));
            });
        }, "usb-serial-rescan").start();
    }

    private SerialConnectionConfig buildSerialConfig() {
        SerialConnectionConfig cfg = selectedModel != null
                ? selectedModel.getDefaultSerialConfig()
                : (slot.getPeripheralType() == PeripheralType.SCALE
                ? SerialConnectionConfig.scaleDefault()
                : SerialConnectionConfig.rfidDefault());
        String port = getSelectedPortName();
        if (port != null) {
            cfg.setPortName(port);
        }
        if (slot.getPeripheralType() == PeripheralType.SCALE) {
            cfg.setBaudRate((Integer) spBaud.getValue());
            cfg.setDataBits((Integer) spDataBits.getValue());
            cfg.setStopBits((Integer) spStopBits.getValue());
            cfg.setParity((ParityOption) cbParity.getSelectedItem());
        }
        return cfg;
    }

    private void testPort() {
        if (selectedModel == null) {
            showWarning("Selecione um modelo.");
            return;
        }
        String port = getSelectedPortName();
        if (port == null) {
            showWarning("Selecione uma porta serial válida.");
            return;
        }
        String conflict = sessionManager.findPortConflictForSelection(slot, port);
        if (conflict != null) {
            showWarning(conflict);
            return;
        }
        btnTestPort.setEnabled(false);
        btnConnect.setEnabled(false);
        lbStatus.setText("Testando " + port + "...");
        showBusy("Testando porta " + port + "...");

        new SwingWorker<PortProbeResult, Void>() {
            @Override
            protected PortProbeResult doInBackground() {
                SerialPortProber prober = PortProbeFactory.forModel(selectedModel);
                return prober.probe(buildSerialConfig(), PortProbeFactory.defaultTimeoutMs(selectedModel));
            }

            @Override
            protected void done() {
                hideBusy();
                btnTestPort.setEnabled(!isConnected());
                btnConnect.setEnabled(!isConnected());
                try {
                    PortProbeResult result = get();
                    showProbeResultDialog("Resultado do teste — " + slot.getLabel(), result);
                    log("Teste " + slot.getLabel() + " porta " + port + ": " + result.getStatus());
                    if (result.isMatch()) {
                        lbStatus.setText("Porta OK: " + port);
                        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.SUCCESS);
                    } else if (result.isBlocking()) {
                        lbStatus.setText("Teste falhou");
                        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.DANGER);
                    } else {
                        lbStatus.setText("Porta suspeita");
                        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.WARNING);
                    }
                } catch (Exception e) {
                    lbStatus.setText("Erro no teste");
                    WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.DANGER);
                    log("ERRO teste: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void connectDevice() {
        if (selectedModel == null) {
            showWarning("Selecione um modelo.");
            return;
        }
        String port = getSelectedPortName();
        if (port == null) {
            showWarning("Selecione uma porta serial válida.");
            return;
        }
        String conflict = sessionManager.findPortConflictForSelection(slot, port);
        if (conflict != null) {
            showWarning(conflict);
            return;
        }
        if (portConflictChecker != null) {
            portConflictChecker.accept(port);
        }

        btnConnect.setEnabled(false);
        btnTestPort.setEnabled(false);
        lbStatus.setText("Verificando " + port + "...");
        showBusy("Verificando dispositivo em " + port + "...");

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
                        hideBusy();
                        btnConnect.setEnabled(true);
                        btnTestPort.setEnabled(true);
                        lbStatus.setText("Erro: " + probe.getMessage());
                        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.DANGER);
                        showProbeResultDialog("Conexão — " + slot.getLabel(), probe);
                        return;
                    }
                    if (probe.isSuspicious()) {
                        hideBusy();
                        String body = probe.getMessage();
                        if (!probe.getDetail().isEmpty()) {
                            body = body + "\n\n" + probe.getDetail();
                        }
                        body = body + "\n\nDeseja conectar mesmo assim?";
                        int choice = JOptionPane.showConfirmDialog(
                                getDialogParent(),
                                body,
                                "Porta suspeita",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE);
                        if (choice != JOptionPane.YES_OPTION) {
                            btnConnect.setEnabled(true);
                            btnTestPort.setEnabled(true);
                            lbStatus.setText("Conexão cancelada");
                            return;
                        }
                        showBusy("Conectando em " + port + "...");
                    }
                    performConnect(port);
                } catch (Exception e) {
                    hideBusy();
                    btnConnect.setEnabled(true);
                    btnTestPort.setEnabled(true);
                    lbStatus.setText("Erro na verificação");
                    log("ERRO verificação: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void performConnect(String port) {
        lbStatus.setText("Conectando...");
        showBusy("Conectando " + slot.getLabel().toLowerCase() + "...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    sessionManager.connect(slot, selectedModel, buildSerialConfig(),
                            (Integer) spPower.getValue(), collectSelectedAntennas());
                    return null;
                } catch (Exception e) {
                    return e.getMessage();
                }
            }

            @Override
            protected void done() {
                hideBusy();
                String error = null;
                try {
                    error = get();
                } catch (Exception e) {
                    error = e.getMessage();
                }
                if (error != null) {
                    btnConnect.setEnabled(true);
                    lbStatus.setText("Erro: " + error);
                    WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.DANGER);
                    log("ERRO conexão " + slot.getLabel() + ": " + error);
                    notifyConnectionChanged(false);
                    return;
                }
                lbStatus.setText("Conectado em " + port);
                WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.SUCCESS);
                lbDeviceInfo.setText(sessionManager.getDevice(slot).getDeviceInfo());
                btnDisconnect.setEnabled(true);
                btnTestPort.setEnabled(false);
                setSelectionEnabled(false);
                updatePowerDbmLabel();
                log("Conectado " + slot.getLabel() + " @ " + port);
                ReadablePeripheral connectedDevice = sessionManager.getDevice(slot);
                if (connectedDevice instanceof RfidConfigurable) {
                    String diag = ((RfidConfigurable) connectedDevice).getRfDiagnostics();
                    if (diag != null && !diag.isEmpty()) {
                        log(diag);
                    }
                }
                startLiveWeightReading();
                startLiveRfidReading();
                notifyConnectionChanged(true);
            }
        }.execute();
    }

    public void disconnectDevice() {
        if (busyOperation) {
            return;
        }
        closingStopRequested = true;
        showBusy("Desconectando " + slot.getLabel().toLowerCase() + "...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                liveWeightActive = false;
                liveRfidActive = false;
                ReadablePeripheral device = sessionManager.getDevice(slot);
                PeripheralSafeIo.stopReading(device);
                sessionManager.disconnect(slot);
                return null;
            }

            @Override
            protected void done() {
                closingStopRequested = false;
                hideBusy();
                lbStatus.setText("Desconectado");
                WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.TEXT_SECONDARY);
                lbDeviceInfo.setText("-");
                btnDisconnect.setEnabled(false);
                setSelectionEnabled(true);
                lbPowerDbm.setText("— dBm");
                resetLiveWeightDisplay();
                tagMonitor.reset();
                tagMonitor.setHint("Conecte o leitor e toque em Iniciar teste.");
                updateRfidTestControls();
                notifyConnectionChanged(false);
                log("Desconectado " + slot.getLabel());
            }
        }.execute();
    }

    private void applyPower() {
        if (!sessionManager.isConnected(slot)) {
            return;
        }
        if (!(sessionManager.getDevice(slot) instanceof RfidConfigurable)) {
            return;
        }
        try {
            RfidConfigurable rfid = (RfidConfigurable) sessionManager.getDevice(slot);
            rfid.setPowerPercent((Integer) spPower.getValue());
            rfid.setAntennaIds(collectSelectedAntennas());
            updatePowerDbmLabel();
            String diag = rfid.getRfDiagnostics();
            if (diag != null && !diag.isEmpty()) {
                log(diag);
            } else {
                log("Potência " + slot.getLabel() + ": " + spPower.getValue() + "%");
            }
            lbDeviceInfo.setText(sessionManager.getDevice(slot).getDeviceInfo());
        } catch (PeripheralException e) {
            JOptionPane.showMessageDialog(getDialogParent(), e.getMessage(), "Potência", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updatePowerDbmLabel() {
        if (!sessionManager.isConnected(slot)
                || !(sessionManager.getDevice(slot) instanceof RfidConfigurable)) {
            lbPowerDbm.setText("— dBm");
            return;
        }
        RfidConfigurable rfid = (RfidConfigurable) sessionManager.getDevice(slot);
        double applied = rfid.getAppliedPowerDbm();
        double max = rfid.getMaxPowerDbm();
        if (Double.isNaN(applied)) {
            lbPowerDbm.setText(spPower.getValue() + "%");
            return;
        }
        if (Double.isNaN(max)) {
            lbPowerDbm.setText(String.format(java.util.Locale.US, "%.1f dBm", applied));
        } else {
            lbPowerDbm.setText(String.format(java.util.Locale.US, "%.1f / %.1f dBm", applied, max));
        }
    }

    private int[] collectSelectedAntennas() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (int i = 0; i < antennaChecks.length; i++) {
            if (antennaChecks[i] != null && antennaChecks[i].isSelected()) {
                ids.add(i + 1); // antenas físicas 1..16
            }
        }
        if (ids.isEmpty()) {
            ids.add(1);
        }
        List<Integer> list = new ArrayList<>(ids);
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private void setSelectionEnabled(boolean enabled) {
        cbVendor.setEnabled(enabled);
        cbModel.setEnabled(enabled);
        cbPort.setEnabled(enabled);
        btnRefreshPorts.setEnabled(enabled);
        btnRescanUsb.setEnabled(enabled && LinuxUsbSerialReset.isSupported());
        btnTestPort.setEnabled(enabled);
        btnConnect.setEnabled(enabled);
        // Potência/antenas editáveis também com o leitor conectado
        boolean powerEditable = enabled || sessionManager.isConnected(slot);
        spPower.setEnabled(powerEditable && slot.getPeripheralType() == PeripheralType.RFID_READER);
        btnApplyPower.setEnabled(sessionManager.isConnected(slot)
                && slot.getPeripheralType() == PeripheralType.RFID_READER);
        boolean antennasEditable = enabled || (sessionManager.isConnected(slot)
                && slot.getPeripheralType() == PeripheralType.RFID_READER);
        for (JCheckBox cb : antennaChecks) {
            if (cb != null) {
                cb.setEnabled(antennasEditable);
            }
        }
        spBaud.setEnabled(enabled);
        spDataBits.setEnabled(enabled);
        spStopBits.setEnabled(enabled);
        cbParity.setEnabled(enabled);
    }

    private void showProbeResultDialog(String title, PortProbeResult result) {
        int messageType = result.isMatch() ? JOptionPane.INFORMATION_MESSAGE
                : result.isBlocking() ? JOptionPane.ERROR_MESSAGE : JOptionPane.WARNING_MESSAGE;
        String body = result.getMessage();
        if (!result.getDetail().isEmpty()) {
            body = body + "\n\n" + result.getDetail();
        }
        JOptionPane.showMessageDialog(getDialogParent(), body, title, messageType);
    }

    private void showWarning(String message) {
        JOptionPane.showMessageDialog(getDialogParent(), message, slot.getLabel(), JOptionPane.WARNING_MESSAGE);
    }

    private Component getDialogParent() {
        return ownerWindow != null ? ownerWindow : this;
    }

    private void log(String msg) {
        if (connectionListener != null) {
            connectionListener.onLog(msg);
        }
    }

    private void notifyConnectionChanged(boolean connected) {
        if (connectionListener != null) {
            connectionListener.onConnectionChanged(slot, connected);
        }
    }
}
