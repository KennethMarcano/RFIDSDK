package com.peripheral.app;

import com.peripheral.core.DeviceModelEntry;
import com.peripheral.core.ParityOption;
import com.peripheral.core.PeripheralCatalog;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.PeripheralType;
import com.peripheral.core.PortProbeFactory;
import com.peripheral.core.PortProbeResult;
import com.peripheral.core.RfidConfigurable;
import com.peripheral.core.SerialConnectionConfig;
import com.peripheral.core.SerialPortProber;
import com.peripheral.session.PeripheralSessionManager;
import com.peripheral.session.PeripheralSlot;
import com.rfid.core.SerialPortDiscovery;
import com.rfid.core.SerialPortInfo;

import javax.swing.*;
import javax.swing.border.TitledBorder;
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
    private final JButton btnRefreshPorts = new JButton("Atualizar portas");
    private final JButton btnTestPort = new JButton("Testar porta");
    private final JButton btnConnect = new JButton("Conectar");
    private final JButton btnDisconnect = new JButton("Desconectar");
    private final JLabel lbStatus = new JLabel("Desconectado");
    private final JLabel lbDeviceInfo = new JLabel("-");

    private final JPanel rfidOptionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JSpinner spPower = new JSpinner(new SpinnerNumberModel(50, 1, 100, 1));
    private final JButton btnApplyPower = new JButton("Aplicar potência");
    private final JPanel antennaPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JCheckBox[] antennaChecks = new JCheckBox[16];

    private final JPanel scaleOptionsPanel = new JPanel(new GridBagLayout());
    private final JSpinner spBaud = new JSpinner(new SpinnerNumberModel(9600, 300, 115200, 300));
    private final JSpinner spDataBits = new JSpinner(new SpinnerNumberModel(8, 5, 8, 1));
    private final JSpinner spStopBits = new JSpinner(new SpinnerNumberModel(1, 1, 2, 1));
    private final JComboBox<ParityOption> cbParity = new JComboBox<>(ParityOption.values());

    private DeviceModelEntry selectedModel;
    private Window ownerWindow;

    public PeripheralConnectionPanel(PeripheralSlot slot, PeripheralSessionManager sessionManager,
                                     ConnectionListener connectionListener,
                                     Consumer<String> portConflictChecker) {
        super(new BorderLayout(4, 4));
        this.slot = slot;
        this.sessionManager = sessionManager;
        this.connectionListener = connectionListener;
        this.portConflictChecker = portConflictChecker;
        setBorder(new TitledBorder(slot.getLabel()));
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
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 3, 3, 3);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        selection.add(new JLabel("Fabricante:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        selection.add(cbVendor, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        selection.add(new JLabel("Modelo:"), gbc);
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

        JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        portRow.add(new JLabel("Porta:"));
        setupPortCombo();
        portRow.add(cbPort);
        portRow.add(btnRefreshPorts);
        portRow.add(btnTestPort);
        portRow.add(btnConnect);
        portRow.add(btnDisconnect);

        buildRfidOptions();
        buildScaleOptions();
        boolean isRfid = slot.getPeripheralType() == PeripheralType.RFID_READER;
        rfidOptionsPanel.setVisible(isRfid);
        scaleOptionsPanel.setVisible(!isRfid);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(selection);
        center.add(portRow);
        center.add(lbStatus);
        center.add(lbDeviceInfo);
        center.add(rfidOptionsPanel);
        center.add(scaleOptionsPanel);

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

    private void buildRfidOptions() {
        rfidOptionsPanel.setBorder(new TitledBorder("Opções RFID"));
        rfidOptionsPanel.add(new JLabel("Potência (%):"));
        rfidOptionsPanel.add(spPower);
        rfidOptionsPanel.add(btnApplyPower);
        antennaPanel.setBorder(BorderFactory.createTitledBorder("Antenas"));
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
        scaleOptionsPanel.setBorder(new TitledBorder("Opções serial"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        scaleOptionsPanel.add(new JLabel("Baud:"), gbc);
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
            lbStatus.setForeground(Color.RED);
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
                        lbStatus.setForeground(new Color(0, 128, 0));
                    } else if (result.isBlocking()) {
                        lbStatus.setText("Teste falhou");
                        lbStatus.setForeground(Color.RED);
                    } else {
                        lbStatus.setText("Porta suspeita");
                        lbStatus.setForeground(new Color(180, 100, 0));
                    }
                } catch (Exception e) {
                    lbStatus.setText("Erro no teste");
                    lbStatus.setForeground(Color.RED);
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
                        lbStatus.setForeground(Color.RED);
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
                    lbStatus.setForeground(Color.RED);
                    log("ERRO conexão " + slot.getLabel() + ": " + error);
                    notifyConnectionChanged(false);
                    return;
                }
                lbStatus.setText("Conectado em " + port);
                lbStatus.setForeground(new Color(0, 128, 0));
                lbDeviceInfo.setText(sessionManager.getDevice(slot).getDeviceInfo());
                btnDisconnect.setEnabled(true);
                btnTestPort.setEnabled(false);
                setSelectionEnabled(false);
                log("Conectado " + slot.getLabel() + " @ " + port);
                notifyConnectionChanged(true);
            }
        }.execute();
    }

    public void disconnectDevice() {
        sessionManager.disconnect(slot);
        lbStatus.setText("Desconectado");
        lbStatus.setForeground(Color.BLACK);
        lbDeviceInfo.setText("-");
        btnDisconnect.setEnabled(false);
        setSelectionEnabled(true);
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
