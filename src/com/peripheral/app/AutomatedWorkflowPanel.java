package com.peripheral.app;

import com.peripheral.core.DeviceModelEntry;
import com.peripheral.core.PeripheralDataEvent;
import com.peripheral.core.PeripheralException;
import com.peripheral.session.PeripheralConnectionHandle;
import com.peripheral.session.PeripheralSessionManager;
import com.peripheral.session.PeripheralSlot;
import com.peripheral.workflow.WeighingWorkflowOrchestrator;
import com.peripheral.workflow.WorkflowConfig;
import com.peripheral.workflow.WorkflowContext;
import com.peripheral.workflow.WorkflowListener;
import com.peripheral.workflow.WorkflowStep;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

public class AutomatedWorkflowPanel extends JPanel {

    private static final int MAX_ROWS = 500;
    private static final Color COLOR_OK = new Color(0, 128, 0);
    private static final Color COLOR_WARN = new Color(180, 100, 0);
    private static final Color COLOR_MUTED = new Color(100, 100, 100);

    private final PeripheralSessionManager sessionManager;
    private final Consumer<String> logConsumer;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    private final JLabel lbScaleSummary = new JLabel();
    private final JLabel lbRfidSummary = new JLabel();
    private final JButton btnConfigScale = new JButton("Configurar");
    private final JButton btnConfigRfid = new JButton("Configurar");

    private final JCheckBox cbRfid = new JCheckBox("Leitura RFID após estabilizar", true);
    private final JCheckBox cbPhoto = new JCheckBox("Capturar foto", false);
    private final JCheckBox cbLabel = new JCheckBox("Imprimir etiqueta", false);
    private final JCheckBox cbWeighing = new JCheckBox("Pesagem (obrigatório)", true);

    private final JLabel lbWorkflowStatus = new JLabel("Pronto para iniciar o fluxo");
    private final JButton btnStartWorkflow = new JButton("Iniciar fluxo");
    private final JButton btnNextReady = new JButton("Próximo / Listo");
    private final JButton btnStopWorkflow = new JButton("Parar fluxo");

    private final DefaultTableModel dataModel = new DefaultTableModel(
            new String[]{"Hora", "Etapa", "Dado"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable dataTable = new JTable(dataModel);
    private final JTextArea taLog = new JTextArea(5, 40);

    private WeighingWorkflowOrchestrator orchestrator;
    private Window ownerWindow;
    private boolean workflowRunning;

    public AutomatedWorkflowPanel(PeripheralSessionManager sessionManager, Consumer<String> logConsumer) {
        super(new BorderLayout(10, 10));
        this.sessionManager = sessionManager;
        this.logConsumer = logConsumer;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        buildUi();
        refreshPeripheralSummaries();
        updateWorkflowControls();
    }

    public void setOwnerWindow(Window owner) {
        this.ownerWindow = owner;
    }

    public void stopWorkflowIfRunning() {
        if (orchestrator != null && orchestrator.isRunning()) {
            orchestrator.stop();
            orchestrator = null;
        }
        workflowRunning = false;
        updateWorkflowControls();
    }

    private void buildUi() {
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(buildPeripheralsSection());
        top.add(Box.createVerticalStrut(10));
        top.add(buildProcessSection());

        JPanel operation = buildOperationSection();

        add(top, BorderLayout.NORTH);
        add(operation, BorderLayout.CENTER);

        btnConfigScale.addActionListener(e -> openConfigDialog(PeripheralSlot.SCALE));
        btnConfigRfid.addActionListener(e -> openConfigDialog(PeripheralSlot.RFID_READER));
        btnStartWorkflow.addActionListener(e -> startWorkflow());
        btnStopWorkflow.addActionListener(e -> stopWorkflow());
        btnNextReady.addActionListener(e -> {
            if (orchestrator != null) {
                orchestrator.acknowledgeNext();
                btnNextReady.setEnabled(false);
                appendLog("Pronto para próximo ciclo.");
            }
        });

        cbRfid.addActionListener(e -> updateWorkflowControls());
    }

    private JPanel buildPeripheralsSection() {
        JPanel section = new JPanel(new BorderLayout(8, 8));
        section.setBorder(new TitledBorder("Periféricos"));

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        grid.add(new JLabel("Balança *"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        lbScaleSummary.setFont(lbScaleSummary.getFont().deriveFont(Font.PLAIN, 13f));
        grid.add(lbScaleSummary, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        grid.add(btnConfigScale, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        grid.add(new JLabel("Leitor RFID"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        lbRfidSummary.setFont(lbRfidSummary.getFont().deriveFont(Font.PLAIN, 13f));
        grid.add(lbRfidSummary, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        grid.add(btnConfigRfid, gbc);

        section.add(grid, BorderLayout.CENTER);
        return section;
    }

    private JPanel buildProcessSection() {
        JPanel section = new JPanel(new BorderLayout(8, 8));
        section.setBorder(new TitledBorder("Processos do fluxo"));

        JPanel checks = new JPanel();
        checks.setLayout(new BoxLayout(checks, BoxLayout.Y_AXIS));
        cbWeighing.setSelected(true);
        cbWeighing.setEnabled(false);
        checks.add(cbWeighing);
        checks.add(cbRfid);
        checks.add(cbPhoto);
        checks.add(cbLabel);

        JLabel help = new JLabel("<html><small>Ativador: peso estável e &gt; 0 → RFID (1 s) → foto → etiqueta. "
                + "Após cada ciclo, use <b>Próximo / Listo</b>.</small></html>");
        help.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        btnStopWorkflow.setEnabled(false);
        btnNextReady.setEnabled(false);
        actions.add(btnStartWorkflow);
        actions.add(btnStopWorkflow);
        actions.add(btnNextReady);

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statusRow.add(new JLabel("Status:"));
        statusRow.add(lbWorkflowStatus);

        JPanel south = new JPanel(new BorderLayout());
        south.add(actions, BorderLayout.NORTH);
        south.add(statusRow, BorderLayout.SOUTH);

        section.add(checks, BorderLayout.NORTH);
        section.add(help, BorderLayout.CENTER);
        section.add(south, BorderLayout.SOUTH);
        return section;
    }

    private JPanel buildOperationSection() {
        JPanel section = new JPanel(new BorderLayout(4, 4));
        section.setBorder(new TitledBorder("Operação"));

        dataTable.setAutoCreateRowSorter(true);
        dataTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollData = new JScrollPane(dataTable);
        scrollData.setPreferredSize(new Dimension(700, 200));

        taLog.setEditable(false);
        taLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane scrollLog = new JScrollPane(taLog);
        scrollLog.setPreferredSize(new Dimension(700, 120));

        section.add(scrollData, BorderLayout.CENTER);
        section.add(scrollLog, BorderLayout.SOUTH);
        return section;
    }

    private void openConfigDialog(PeripheralSlot slot) {
        if (workflowRunning) {
            JOptionPane.showMessageDialog(getDialogParent(),
                    "Pare o fluxo antes de alterar a configuração dos periféricos.",
                    "Configuração", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Window parent = ownerWindow;
        if (parent == null) {
            Component ancestor = SwingUtilities.getWindowAncestor(this);
            if (ancestor instanceof Window) {
                parent = (Window) ancestor;
            }
        }
        PeripheralConfigDialog dialog = new PeripheralConfigDialog(
                parent,
                slot,
                sessionManager,
                (s, connected) -> {
                    refreshPeripheralSummaries();
                    updateWorkflowControls();
                    appendLog((connected ? "Configurado: " : "Configuração fechada: ") + s.getLabel());
                },
                this::appendLog);
        dialog.showDialog();
        refreshPeripheralSummaries();
        updateWorkflowControls();
    }

    private void refreshPeripheralSummaries() {
        updateSummaryLabel(lbScaleSummary, PeripheralSlot.SCALE, true);
        updateSummaryLabel(lbRfidSummary, PeripheralSlot.RFID_READER, false);
    }

    private void updateSummaryLabel(JLabel label, PeripheralSlot slot, boolean required) {
        PeripheralConnectionHandle handle = sessionManager.getHandle(slot);
        if (handle != null && handle.isConnected() && handle.getModel() != null) {
            DeviceModelEntry model = handle.getModel();
            String port = handle.getPortName();
            String text = model.getVendorName() + " — " + model.getModelName();
            if (port != null && !port.isEmpty()) {
                text = text + " | " + port;
            }
            label.setText(text);
            label.setForeground(COLOR_OK);
            label.setToolTipText(model.getDisplayLabel() + (port != null ? " @ " + port : ""));
            return;
        }
        if (required) {
            label.setText("Não configurada (obrigatória)");
            label.setForeground(COLOR_WARN);
        } else {
            label.setText("Não configurada (opcional)");
            label.setForeground(COLOR_MUTED);
        }
        label.setToolTipText(null);
    }

    private void updateWorkflowControls() {
        boolean scaleOk = sessionManager.isConnected(PeripheralSlot.SCALE);
        boolean rfidOk = sessionManager.isConnected(PeripheralSlot.RFID_READER);
        boolean rfidNeeded = cbRfid.isSelected();

        btnStartWorkflow.setEnabled(!workflowRunning && scaleOk && (!rfidNeeded || rfidOk));
        btnConfigScale.setEnabled(!workflowRunning);
        btnConfigRfid.setEnabled(!workflowRunning);
        cbRfid.setEnabled(!workflowRunning);
        cbPhoto.setEnabled(!workflowRunning);
        cbLabel.setEnabled(!workflowRunning);

        if (!scaleOk && !workflowRunning) {
            lbWorkflowStatus.setText("Configure a balança para iniciar o fluxo");
        } else if (rfidNeeded && !rfidOk && !workflowRunning) {
            lbWorkflowStatus.setText("Configure o leitor RFID ou desmarque a leitura RFID");
        } else if (!workflowRunning) {
            lbWorkflowStatus.setText("Pronto para iniciar o fluxo");
        }
    }

    private void startWorkflow() {
        if (!sessionManager.isConnected(PeripheralSlot.SCALE)) {
            JOptionPane.showMessageDialog(getDialogParent(),
                    "Configure e conecte a balança antes de iniciar.", "Fluxo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (cbRfid.isSelected() && !sessionManager.isConnected(PeripheralSlot.RFID_READER)) {
            JOptionPane.showMessageDialog(getDialogParent(),
                    "Configure o leitor RFID ou desmarque a leitura RFID.", "Fluxo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Set<WorkflowStep> steps = EnumSet.of(WorkflowStep.WEIGHING);
        if (cbRfid.isSelected()) {
            steps.add(WorkflowStep.RFID_READ);
        }
        if (cbPhoto.isSelected()) {
            steps.add(WorkflowStep.CAPTURE_PHOTO);
        }
        if (cbLabel.isSelected()) {
            steps.add(WorkflowStep.PRINT_LABEL);
        }

        WorkflowConfig config = new WorkflowConfig(steps, WorkflowConfig.DEFAULT_RFID_READ_MS);
        orchestrator = new WeighingWorkflowOrchestrator(sessionManager);
        dataModel.setRowCount(0);
        taLog.setText("");

        try {
            orchestrator.start(config, createWorkflowListener());
            workflowRunning = true;
            lbWorkflowStatus.setText("Fluxo em execução — aguardando peso estável");
            btnNextReady.setEnabled(false);
            appendLog("Fluxo iniciado.");
            updateWorkflowControls();
            btnStopWorkflow.setEnabled(true);
        } catch (PeripheralException e) {
            JOptionPane.showMessageDialog(getDialogParent(), e.getMessage(), "Fluxo", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopWorkflow() {
        if (orchestrator != null) {
            orchestrator.stop();
            orchestrator = null;
        }
        workflowRunning = false;
        lbWorkflowStatus.setText("Fluxo parado");
        btnNextReady.setEnabled(false);
        btnStopWorkflow.setEnabled(false);
        appendLog("Fluxo parado.");
        updateWorkflowControls();
    }

    private WorkflowListener createWorkflowListener() {
        return new WorkflowListener() {
            @Override
            public void onWeightUpdate(PeripheralDataEvent event) {
                SwingUtilities.invokeLater(() -> appendDataRow("Pesagem", event.getDisplayText()));
            }

            @Override
            public void onTagRead(PeripheralDataEvent event) {
                SwingUtilities.invokeLater(() -> appendDataRow("RFID", event.getDisplayText()));
            }

            @Override
            public void onStepChanged(WorkflowStep step, String message) {
                SwingUtilities.invokeLater(() -> {
                    lbWorkflowStatus.setText(message);
                    appendLog("[" + step.getLabel() + "] " + message);
                });
            }

            @Override
            public void onCycleCompleted(WorkflowContext context) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("Ciclo concluído — peso: " + context.getWeightKg()
                            + " kg, tags: " + context.getTagCodes().size());
                    appendDataRow("Ciclo", "Peso " + context.getWeightKg() + " kg | "
                            + context.getTagCodes().size() + " tag(s)");
                });
            }

            @Override
            public void onWaitingForNext() {
                SwingUtilities.invokeLater(() -> {
                    lbWorkflowStatus.setText("Ciclo concluído — clique em Próximo / Listo");
                    btnNextReady.setEnabled(true);
                });
            }

            @Override
            public void onError(String message, Throwable cause) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("ERRO: " + message);
                    lbWorkflowStatus.setText("Erro: " + message);
                });
            }

            @Override
            public void onStopped() {
                SwingUtilities.invokeLater(() -> {
                    workflowRunning = false;
                    lbWorkflowStatus.setText("Fluxo parado");
                    btnNextReady.setEnabled(false);
                    btnStopWorkflow.setEnabled(false);
                    updateWorkflowControls();
                });
            }
        };
    }

    private void appendDataRow(String step, String data) {
        String time = timeFormat.format(new Date());
        dataModel.insertRow(0, new Object[]{time, step, data});
        while (dataModel.getRowCount() > MAX_ROWS) {
            dataModel.removeRow(dataModel.getRowCount() - 1);
        }
    }

    private void appendLog(String msg) {
        String line = "[" + timeFormat.format(new Date()) + "] " + msg;
        taLog.append(line + "\n");
        taLog.setCaretPosition(taLog.getDocument().getLength());
        if (logConsumer != null) {
            logConsumer.accept(line);
        }
    }

    private Component getDialogParent() {
        if (ownerWindow != null) {
            return ownerWindow;
        }
        Window w = SwingUtilities.getWindowAncestor(this);
        return w != null ? w : this;
    }
}
