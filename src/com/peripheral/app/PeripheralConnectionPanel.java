package com.peripheral.app;

import com.peripheral.core.DeviceModelEntry;
import com.peripheral.core.ParityOption;
import com.peripheral.core.PeripheralCatalog;
import com.peripheral.core.PeripheralDataEvent;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.PeripheralType;
import com.peripheral.core.PortProbeFactory;
import com.peripheral.core.PortProbeResult;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.core.RfidConfigurable;
import com.peripheral.core.SerialConnectionConfig;
import com.peripheral.core.SerialPortProber;
import com.peripheral.session.PeripheralConnectionHandle;
import com.peripheral.session.PeripheralSessionManager;
import com.peripheral.session.PeripheralSlot;
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
            WorkflowUiTheme.button("Atualizar portas", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnTestPort =
            WorkflowUiTheme.button("Testar porta", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnConnect =
            WorkflowUiTheme.button("Conectar", ThemedButton.Variant.PRIMARY);
    private final ThemedButton btnDisconnect =
            WorkflowUiTheme.button("Desconectar", ThemedButton.Variant.DANGER);
    private final JLabel lbStatus = new JLabel("Desconectado");
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

    private final JPanel liveWeightPanel = new JPanel(new BorderLayout(8, 4));
    private final JLabel lbLiveWeight = new JLabel("Peso: —", SwingConstants.CENTER);
    private final JLabel lbLiveWeightHint = new JLabel("Conecte a balança para ver o peso em tempo real");

    private DeviceModelEntry selectedModel;
    private Window ownerWindow;
    private boolean liveWeightActive;

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
        JPanel selection = new JPanel(new GridBagLayout());
        selection.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, 8);
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

        JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        portRow.setOpaque(false);
        portRow.add(fieldLabel("Porta:"));
        setupPortCombo();
        portRow.add(cbPort);
        portRow.add(btnRefreshPorts);
        portRow.add(btnTestPort);
        portRow.add(btnConnect);
        portRow.add(btnDisconnect);

        buildRfidOptions();
        buildScaleOptions();
        buildLiveWeightPanel();
        boolean isRfid = slot.getPeripheralType() == PeripheralType.RFID_READER;
        boolean isScale = slot.getPeripheralType() == PeripheralType.SCALE;
        rfidOptionsPanel.setVisible(isRfid);
        scaleOptionsPanel.setVisible(isScale);
        liveWeightPanel.setVisible(isScale);

        lbStatus.setFont(WorkflowUiTheme.fontStatus(lbStatus));
        lbStatus.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        lbDeviceInfo.setFont(WorkflowUiTheme.fontMeta(lbDeviceInfo));
        lbDeviceInfo.setForeground(WorkflowUiTheme.TEXT_MUTED);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(selection);
        center.add(portRow);
        center.add(lbStatus);
        center.add(lbDeviceInfo);
        center.add(rfidOptionsPanel);
        center.add(scaleOptionsPanel);
        center.add(liveWeightPanel);

        add(center, BorderLayout.CENTER);

        cbVendor.addActionListener(e -> onVendorChanged());
        cbModel.addActionListener(e -> onModelChanged());
        btnRefreshPorts.addActionListener(e -> refreshPorts());
        btnTestPort.addActionListener(e -> testPort());
        btnConnect.addActionListener(e -> connectDevice());
        btnDisconnect.addActionListener(e -> disconnectDevice());
        btnApplyPower.addActionListener(e -> applyPower());

        btnDisconnect.setEnabled(false);
        setSelectionEnabled(true);
    }

    private JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(WorkflowUiTheme.fontMeta(label));
        label.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        return label;
    }

    private void buildRfidOptions() {
        rfidOptionsPanel.setOpaque(false);
        rfidOptionsPanel.setBorder(WorkflowUiTheme.empty(8, 0, 0, 0));
        rfidOptionsPanel.add(fieldLabel("Potência (%):"));
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
        liveWeightPanel.setOpaque(false);
        liveWeightPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(WorkflowUiTheme.BORDER), "Peso em tempo real"),
                WorkflowUiTheme.empty(8, 12, 8, 12)));
        liveWeightPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        lbLiveWeight.setFont(lbLiveWeight.getFont().deriveFont(Font.BOLD, 32f));
        lbLiveWeight.setForeground(WorkflowUiTheme.TEXT_PRIMARY);
        lbLiveWeight.setOpaque(true);
        lbLiveWeight.setBackground(WorkflowUiTheme.CHIP_BG);
        lbLiveWeight.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.CHIP_BORDER),
                WorkflowUiTheme.empty(16, 12, 16, 12)));

        lbLiveWeightHint.setFont(WorkflowUiTheme.fontMeta(lbLiveWeightHint));
        lbLiveWeightHint.setForeground(WorkflowUiTheme.TEXT_MUTED);

        liveWeightPanel.add(lbLiveWeight, BorderLayout.CENTER);
        liveWeightPanel.add(lbLiveWeightHint, BorderLayout.SOUTH);
    }

    public void syncFromSession() {
        if (!isConnected()) {
            resetLiveWeightDisplay();
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
                device.stopContinuousReading();
            }
            liveWeightActive = true;
            lbLiveWeightHint.setText("Atualizando continuamente — coloque o item sobre a balança");
            device.startContinuousReading(event -> SwingUtilities.invokeLater(() -> updateLiveWeight(event)));
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
        if (device != null && device.isReading()) {
            device.stopContinuousReading();
        }
    }

    private void updateLiveWeight(PeripheralDataEvent event) {
        if (!liveWeightActive || event == null) {
            return;
        }
        String weight = event.getWeight();
        if (weight != null && !weight.isEmpty()) {
            boolean stable = Boolean.TRUE.equals(event.getStable());
            lbLiveWeight.setText("Peso: " + weight + " kg" + (stable ? "  ● estável" : "  ○ instável"));
            lbLiveWeight.setForeground(stable ? WorkflowUiTheme.SUCCESS : WorkflowUiTheme.WARNING);
            lbLiveWeightHint.setText(stable
                    ? "Peso estável — pronto para o fluxo"
                    : "Aguardando estabilização...");
            WorkflowUiTheme.setStatusColor(lbLiveWeightHint,
                    stable ? WorkflowUiTheme.SUCCESS : WorkflowUiTheme.WARNING);
        } else if (event.getDisplayText() != null && !event.getDisplayText().isEmpty()) {
            lbLiveWeight.setText(event.getDisplayText());
        }
    }

    private void resetLiveWeightDisplay() {
        lbLiveWeight.setText("Peso: —");
        lbLiveWeight.setForeground(WorkflowUiTheme.TEXT_PRIMARY);
        lbLiveWeightHint.setText("Conecte a balança para ver o peso em tempo real");
        lbLiveWeightHint.setForeground(WorkflowUiTheme.TEXT_MUTED);
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
            log("ERRO portas (" + slot.getLabel() + "): " + e.getMessage());
            lbStatus.setText("Erro ao listar portas");
            WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.DANGER);
        }
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

        new SwingWorker<PortProbeResult, Void>() {
            @Override
            protected PortProbeResult doInBackground() {
                SerialPortProber prober = PortProbeFactory.forModel(selectedModel);
                return prober.probe(buildSerialConfig(), PortProbeFactory.defaultTimeoutMs(selectedModel));
            }

            @Override
            protected void done() {
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
                        showProbeResultDialog("Conexão — " + slot.getLabel(), probe);
                        return;
                    }
                    if (probe.isSuspicious()) {
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
                    }
                    performConnect(port);
                } catch (Exception e) {
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
                log("Conectado " + slot.getLabel() + " @ " + port);
                startLiveWeightReading();
                notifyConnectionChanged(true);
            }
        }.execute();
    }

    public void disconnectDevice() {
        stopLiveWeightReading();
        sessionManager.disconnect(slot);
        lbStatus.setText("Desconectado");
        WorkflowUiTheme.setStatusColor(lbStatus, WorkflowUiTheme.TEXT_SECONDARY);
        lbDeviceInfo.setText("-");
        btnDisconnect.setEnabled(false);
        setSelectionEnabled(true);
        resetLiveWeightDisplay();
        notifyConnectionChanged(false);
    }

    private void applyPower() {
        if (!sessionManager.isConnected(slot)) {
            return;
        }
        if (!(sessionManager.getDevice(slot) instanceof RfidConfigurable)) {
            return;
        }
        try {
            ((RfidConfigurable) sessionManager.getDevice(slot))
                    .setPowerPercent((Integer) spPower.getValue());
            log("Potência " + slot.getLabel() + ": " + spPower.getValue() + "%");
        } catch (PeripheralException e) {
            JOptionPane.showMessageDialog(getDialogParent(), e.getMessage(), "Potência", JOptionPane.ERROR_MESSAGE);
        }
    }

    private int[] collectSelectedAntennas() {
        Set<Integer> ids = new LinkedHashSet<>();
        for (int i = 0; i < antennaChecks.length; i++) {
            if (antennaChecks[i] != null && antennaChecks[i].isSelected()) {
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
