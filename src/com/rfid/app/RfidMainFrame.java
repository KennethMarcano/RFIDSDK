package com.rfid.app;

import com.rfid.core.RfidException;
import com.rfid.core.RfidReader;
import com.rfid.core.RfidReaderConfig;
import com.rfid.core.RfidReaderFactory;
import com.rfid.core.RfidSdkType;
import com.rfid.core.RfidTagEvent;
import com.rfid.core.RfidTagListener;
import com.rfid.core.SerialPortDiscovery;
import com.rfid.core.SerialPortInfo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class RfidMainFrame extends JFrame {

    private final JComboBox<RfidSdkType> cbSdk = new JComboBox<>(RfidSdkType.values());
    private final JComboBox<SerialPortInfo> cbPort = new JComboBox<>();
    private final JButton btnRefreshPorts = new JButton("Atualizar portas");
    private final JButton btnConnect = new JButton("Conectar");
    private final JButton btnDisconnect = new JButton("Desconectar");
    private final JLabel lbStatus = new JLabel("Selecione SDK e porta, depois conecte.");
    private final JLabel lbReaderInfo = new JLabel("-");

    private final JSpinner spPower = new JSpinner(new SpinnerNumberModel(50, 1, 100, 1));
    private final JButton btnApplyPower = new JButton("Aplicar potência");

    private final JRadioButton rbAuto = new JRadioButton("Leitura automática", true);
    private final JRadioButton rbManual = new JRadioButton("Leitura manual (botão)");
    private final JButton btnStartRead = new JButton("Iniciar leitura");
    private final JButton btnStopRead = new JButton("Parar leitura");
    private final JButton btnReadOnce = new JButton("Ler tag");
    private final JLabel lbLastTag = new JLabel("-");
    private final JTextArea taLog = new JTextArea(8, 40);

    private RfidReader reader;
    private final RfidReaderConfig readerConfig = new RfidReaderConfig();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public RfidMainFrame() {
        super("RFID - Configuração");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        buildUi();
        refreshPorts();
        setControlPanelEnabled(false);
        pack();
        setMinimumSize(new Dimension(640, 520));
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));

        JPanel setup = new JPanel(new GridBagLayout());
        setup.setBorder(BorderFactory.createTitledBorder("Conexão"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        setup.add(new JLabel("SDK:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        setup.add(cbSdk, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        setup.add(new JLabel("Porta:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        setupPortCombo();
        setup.add(cbPort, gbc);

        gbc.gridx = 2;
        gbc.gridy = 1;
        gbc.weightx = 0;
        setup.add(btnRefreshPorts, gbc);

        JPanel connectBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        connectBtns.add(btnConnect);
        connectBtns.add(btnDisconnect);
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 3;
        setup.add(connectBtns, gbc);

        gbc.gridy = 3;
        setup.add(lbStatus, gbc);
        gbc.gridy = 4;
        setup.add(lbReaderInfo, gbc);

        JPanel control = new JPanel();
        control.setLayout(new BoxLayout(control, BoxLayout.Y_AXIS));
        control.setBorder(BorderFactory.createTitledBorder("Controle (após conectar)"));

        JPanel pPower = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pPower.add(new JLabel("Potência (1-100%):"));
        pPower.add(spPower);
        pPower.add(btnApplyPower);

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(rbAuto);
        modeGroup.add(rbManual);
        JPanel pMode = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pMode.add(rbAuto);
        pMode.add(rbManual);

        JPanel pRead = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pRead.add(btnStartRead);
        pRead.add(btnStopRead);
        pRead.add(btnReadOnce);

        JPanel pTag = new JPanel(new BorderLayout());
        pTag.setBorder(BorderFactory.createTitledBorder("Última tag"));
        pTag.add(lbLastTag, BorderLayout.CENTER);

        taLog.setEditable(false);
        taLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane scrollLog = new JScrollPane(taLog);
        scrollLog.setBorder(BorderFactory.createTitledBorder("Log"));

        control.add(pPower);
        control.add(Box.createVerticalStrut(6));
        control.add(pMode);
        control.add(Box.createVerticalStrut(6));
        control.add(pRead);
        control.add(Box.createVerticalStrut(6));
        control.add(pTag);
        control.add(Box.createVerticalStrut(6));
        control.add(scrollLog);

        add(setup, BorderLayout.NORTH);
        add(control, BorderLayout.CENTER);

        btnRefreshPorts.addActionListener(e -> refreshPorts());
        btnConnect.addActionListener(e -> connectReader());
        btnDisconnect.addActionListener(e -> disconnectReader());
        btnApplyPower.addActionListener(e -> applyPower());
        rbAuto.addActionListener(e -> updateReadModeUi());
        rbManual.addActionListener(e -> updateReadModeUi());
        btnStartRead.addActionListener(e -> startContinuous());
        btnStopRead.addActionListener(e -> stopContinuous());
        btnReadOnce.addActionListener(e -> readOnce());

        updateReadModeUi();
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
                updatePortTooltip();
            }
        });
        cbPort.addActionListener(e -> updatePortTooltip());
    }

    private void updatePortTooltip() {
        Object selected = cbPort.getSelectedItem();
        if (selected instanceof SerialPortInfo && !((SerialPortInfo) selected).isPlaceholder()) {
            cbPort.setToolTipText(((SerialPortInfo) selected).getDetailTooltip());
        } else {
            cbPort.setToolTipText(null);
        }
    }

    private String getSelectedPortName() {
        Object selected = cbPort.getSelectedItem();
        if (!(selected instanceof SerialPortInfo)) {
            return null;
        }
        SerialPortInfo info = (SerialPortInfo) selected;
        return info.isPlaceholder() ? null : info.getSystemPortName();
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
            updatePortTooltip();
        } catch (RuntimeException e) {
            cbPort.addItem(SerialPortInfo.placeholder("(erro ao listar portas)"));
        }
    }

    private void connectReader() {
        String port = getSelectedPortName();
        if (port == null) {
            JOptionPane.showMessageDialog(this, "Selecione uma porta serial válida.", "Porta", JOptionPane.WARNING_MESSAGE);
            return;
        }
        RfidSdkType sdk = (RfidSdkType) cbSdk.getSelectedItem();
        btnConnect.setEnabled(false);
        lbStatus.setText("Conectando...");
        new SwingWorker<Void, Void>() {
            private String error;

            @Override
            protected Void doInBackground() {
                try {
                    reader = RfidReaderFactory.create(sdk, readerConfig);
                    reader.connect(port);
                } catch (Exception e) {
                    error = e.getMessage();
                    if (reader != null) {
                        try {
                            reader.disconnect();
                        } catch (Throwable ignored) {
                        }
                        reader = null;
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                btnConnect.setEnabled(true);
                if (error != null) {
                    lbStatus.setText("Erro: " + error);
                    lbStatus.setForeground(Color.RED);
                    appendLog("ERRO conexão: " + error);
                    if (sdk == RfidSdkType.MERCURY) {
                        appendLog("Mercury: verifique java.library.path (SDKMERCURY) e drivers USB.");
                    }
                    return;
                }
                lbStatus.setText("Conectado em " + port + " (" + sdk + ")");
                lbStatus.setForeground(new Color(0, 128, 0));
                lbReaderInfo.setText(reader.getReaderInfo());
                spPower.setValue(reader.getPowerPercent());
                setSetupEnabled(false);
                setControlPanelEnabled(true);
                appendLog("Conectado: " + port + " / " + sdk);
            }
        }.execute();
    }

    private void disconnectReader() {
        stopContinuous();
        if (reader != null) {
            reader.disconnect();
            reader = null;
        }
        lbStatus.setText("Desconectado");
        lbStatus.setForeground(Color.BLACK);
        lbReaderInfo.setText("-");
        setSetupEnabled(true);
        setControlPanelEnabled(false);
        appendLog("Desconectado");
    }

    private void applyPower() {
        if (reader == null) {
            return;
        }
        int percent = (Integer) spPower.getValue();
        try {
            reader.setPowerPercent(percent);
            appendLog("Potência aplicada: " + percent + "%");
        } catch (RfidException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Potência", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void startContinuous() {
        if (reader == null) {
            return;
        }
        try {
            reader.startContinuousReading(createTagListener());
            appendLog("Leitura automática iniciada");
            btnStartRead.setEnabled(false);
            btnStopRead.setEnabled(true);
        } catch (RfidException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Leitura", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopContinuous() {
        if (reader != null) {
            reader.stopContinuousReading();
        }
        btnStartRead.setEnabled(rbAuto.isSelected() && reader != null);
        btnStopRead.setEnabled(false);
        appendLog("Leitura parada");
    }

    private void readOnce() {
        if (reader == null) {
            return;
        }
        btnReadOnce.setEnabled(false);
        try {
            reader.readOnce(readerConfig.getReadOnceTimeoutMs(), createTagListener());
            appendLog("Leitura manual solicitada (" + readerConfig.getReadOnceTimeoutMs() + " ms)");
        } catch (RfidException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Leitura", JOptionPane.ERROR_MESSAGE);
            btnReadOnce.setEnabled(true);
        }
        Timer t = new Timer(1200, e -> btnReadOnce.setEnabled(rbManual.isSelected() && reader != null));
        t.setRepeats(false);
        t.start();
    }

    private RfidTagListener createTagListener() {
        return new RfidTagListener() {
            @Override
            public void onTag(RfidTagEvent event) {
                SwingUtilities.invokeLater(() -> {
                    lbLastTag.setText(event.getCode() + " (EPC: " + event.getEpc() + ")");
                    appendLog("TAG " + event.getCode() + " ant=" + event.getAntenna());
                });
            }

            @Override
            public void onError(Throwable error) {
                SwingUtilities.invokeLater(() -> {
                    String msg = error != null ? error.getMessage() : "erro desconhecido";
                    appendLog("ERRO: " + msg);
                });
            }

            @Override
            public void onReadingStateChanged(boolean reading) {
                SwingUtilities.invokeLater(() -> {
                    btnStopRead.setEnabled(reading && rbAuto.isSelected());
                    btnStartRead.setEnabled(!reading && rbAuto.isSelected() && reader != null);
                });
            }
        };
    }

    private void updateReadModeUi() {
        boolean auto = rbAuto.isSelected();
        boolean connected = reader != null;
        btnStartRead.setEnabled(auto && connected && !reader.isContinuousReading());
        btnStopRead.setEnabled(auto && connected && reader.isContinuousReading());
        btnReadOnce.setEnabled(!auto && connected);
        if (auto && reader != null && reader.isContinuousReading()) {
            btnStartRead.setEnabled(false);
            btnStopRead.setEnabled(true);
        }
    }

    private void setSetupEnabled(boolean enabled) {
        cbSdk.setEnabled(enabled);
        cbPort.setEnabled(enabled);
        btnRefreshPorts.setEnabled(enabled);
        btnConnect.setEnabled(enabled);
        btnDisconnect.setEnabled(!enabled);
    }

    private void setControlPanelEnabled(boolean enabled) {
        spPower.setEnabled(enabled);
        btnApplyPower.setEnabled(enabled);
        rbAuto.setEnabled(enabled);
        rbManual.setEnabled(enabled);
        btnStartRead.setEnabled(enabled && rbAuto.isSelected());
        btnStopRead.setEnabled(false);
        btnReadOnce.setEnabled(enabled && rbManual.isSelected());
        lbLastTag.setEnabled(enabled);
        taLog.setEnabled(enabled);
        if (!enabled) {
            btnStartRead.setEnabled(false);
            btnStopRead.setEnabled(false);
            btnReadOnce.setEnabled(false);
        }
    }

    private void appendLog(String line) {
        String ts = timeFormat.format(new Date());
        taLog.append("[" + ts + "] " + line + "\n");
        taLog.setCaretPosition(taLog.getDocument().getLength());
    }
}
