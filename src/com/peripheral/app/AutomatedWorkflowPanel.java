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
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

public class AutomatedWorkflowPanel extends JPanel {

    private final PeripheralSessionManager sessionManager;
    private final Consumer<String> logConsumer;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    private final JLabel lbScaleSummary = new JLabel();
    private final JLabel lbRfidSummary = new JLabel();
    private final ThemedButton btnConfigScale =
            WorkflowUiTheme.button("Configurar", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnConfigRfid =
            WorkflowUiTheme.button("Configurar", ThemedButton.Variant.SECONDARY);

    private final JCheckBox cbRfid = new JCheckBox("Leitura RFID após estabilizar", true);
    private final JCheckBox cbPhoto = new JCheckBox("Capturar foto", false);
    private final JCheckBox cbLabel = new JCheckBox("Imprimir etiqueta", false);
    private final JCheckBox cbWeighing = new JCheckBox("Pesagem (obrigatório)", true);
    private final JCheckBox cbSimulation = new JCheckBox("Modo simulação (sem hardware)", false);

    private final JLabel lbWorkflowStatus = new JLabel("Pronto para iniciar o fluxo");
    private final ThemedButton btnStartWorkflow =
            WorkflowUiTheme.button("Iniciar fluxo", ThemedButton.Variant.PRIMARY);
    private final ThemedButton btnStopWorkflow =
            WorkflowUiTheme.button("Parar fluxo", ThemedButton.Variant.DANGER);
    private final ThemedButton btnRestartWorkflow =
            WorkflowUiTheme.button("Reiniciar sessão", ThemedButton.Variant.SECONDARY);

    private WeighingWorkflowOrchestrator orchestrator;
    private WorkflowOperationWindow operationWindow;
    private Window ownerWindow;
    private boolean workflowRunning;

    public AutomatedWorkflowPanel(PeripheralSessionManager sessionManager, Consumer<String> logConsumer) {
        super(new BorderLayout(0, 0));
        this.sessionManager = sessionManager;
        this.logConsumer = logConsumer;
        WorkflowUiTheme.stylePanel(this);
        setBorder(WorkflowUiTheme.empty(12, 12, 12, 12));
        buildUi();
        refreshPeripheralSummaries();
        updateWorkflowControls();
    }

    public void setOwnerWindow(Window owner) {
        this.ownerWindow = owner;
    }

    public void stopWorkflowIfRunning() {
        closeOperationWindow();
        if (orchestrator != null && orchestrator.isRunning()) {
            orchestrator.stop();
            orchestrator = null;
        }
        workflowRunning = false;
        updateWorkflowControls();
    }

    private void buildUi() {
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(buildPeripheralsSection());
        top.add(buildProcessSection());
        top.add(buildOperationHintSection());
        add(top, BorderLayout.NORTH);

        btnConfigScale.addActionListener(e -> openConfigDialog(PeripheralSlot.SCALE));
        btnConfigRfid.addActionListener(e -> openConfigDialog(PeripheralSlot.RFID_READER));
        btnStartWorkflow.addActionListener(e -> startWorkflow());
        btnStopWorkflow.addActionListener(e -> stopWorkflow());
        btnRestartWorkflow.addActionListener(e -> restartWorkflowSession());
        cbRfid.addActionListener(e -> updateWorkflowControls());
        cbSimulation.addActionListener(e -> updateWorkflowControls());
    }

    private JPanel buildPeripheralsSection() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel lbScale = new JLabel("Balança *");
        lbScale.setFont(WorkflowUiTheme.fontMeta(lbScale));
        lbScale.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        grid.add(lbScale, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        lbScaleSummary.setFont(WorkflowUiTheme.fontStatus(lbScaleSummary));
        grid.add(lbScaleSummary, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        grid.add(btnConfigScale, gbc);

        JLabel lbRfid = new JLabel("Leitor RFID");
        lbRfid.setFont(WorkflowUiTheme.fontMeta(lbRfid));
        lbRfid.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        gbc.gridx = 0;
        gbc.gridy = 1;
        grid.add(lbRfid, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        lbRfidSummary.setFont(WorkflowUiTheme.fontStatus(lbRfidSummary));
        grid.add(lbRfidSummary, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        grid.add(btnConfigRfid, gbc);

        return WorkflowUiTheme.createSection("Periféricos", grid);
    }

    private JPanel buildProcessSection() {
        JPanel checks = new JPanel();
        checks.setOpaque(false);
        checks.setLayout(new BoxLayout(checks, BoxLayout.Y_AXIS));
        cbWeighing.setSelected(true);
        cbWeighing.setEnabled(false);
        cbWeighing.setOpaque(false);
        cbWeighing.setForeground(WorkflowUiTheme.TEXT_PRIMARY);
        styleCheckBox(cbRfid);
        styleCheckBox(cbPhoto);
        styleCheckBox(cbLabel);
        styleCheckBox(cbSimulation);
        checks.add(cbWeighing);
        checks.add(cbRfid);
        checks.add(cbPhoto);
        checks.add(cbLabel);
        checks.add(cbSimulation);

        JLabel help = WorkflowUiTheme.createHintLabel(
                "<html>Após estabilizar 1,5 s → RFID (1 s) → foto → etiqueta. "
                        + "Cada execução guarda um histórico na janela de operação. "
                        + "Use <b>Reiniciar sessão</b> para limpar o histórico sem parar o fluxo.</html>");
        help.setBorder(WorkflowUiTheme.empty(10, 0, 0, 0));

        btnStopWorkflow.setEnabled(false);
        btnRestartWorkflow.setEnabled(false);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(btnStartWorkflow);
        actions.add(btnStopWorkflow);
        actions.add(btnRestartWorkflow);

        lbWorkflowStatus.setFont(WorkflowUiTheme.fontStatus(lbWorkflowStatus));
        lbWorkflowStatus.setForeground(WorkflowUiTheme.TEXT_SECONDARY);

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statusRow.setOpaque(false);
        JLabel lbStatusCaption = new JLabel("Status:");
        lbStatusCaption.setFont(WorkflowUiTheme.fontMeta(lbStatusCaption));
        lbStatusCaption.setForeground(WorkflowUiTheme.TEXT_MUTED);
        statusRow.add(lbStatusCaption);
        statusRow.add(lbWorkflowStatus);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.add(actions, BorderLayout.NORTH);
        south.add(statusRow, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setOpaque(false);
        content.add(checks, BorderLayout.NORTH);
        content.add(help, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);

        return WorkflowUiTheme.createSection("Processos do fluxo", content);
    }

    private JPanel buildOperationHintSection() {
        JLabel hint = WorkflowUiTheme.createHintLabel(
                "<html>A operação abre em uma janela separada ao iniciar o fluxo. "
                        + "Use <b>Iniciar pesagem</b> e <b>Próximo</b> nessa janela para controlar cada ciclo.</html>");
        return WorkflowUiTheme.createSection("Operação", hint);
    }

    private void styleCheckBox(JCheckBox checkBox) {
        checkBox.setOpaque(false);
        checkBox.setForeground(WorkflowUiTheme.TEXT_PRIMARY);
        checkBox.setFont(WorkflowUiTheme.fontMeta(checkBox));
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
            WorkflowUiTheme.setStatusColor(label, WorkflowUiTheme.SUCCESS);
            label.setToolTipText(model.getDisplayLabel() + (port != null ? " @ " + port : ""));
            return;
        }
        if (required) {
            label.setText("Não configurada (obrigatória)");
            WorkflowUiTheme.setStatusColor(label, WorkflowUiTheme.WARNING);
        } else {
            label.setText("Não configurada (opcional)");
            WorkflowUiTheme.setStatusColor(label, WorkflowUiTheme.TEXT_MUTED);
        }
        label.setToolTipText(null);
    }

    private void updateWorkflowControls() {
        boolean simulation = cbSimulation.isSelected();
        boolean scaleOk = sessionManager.isConnected(PeripheralSlot.SCALE);
        boolean rfidOk = sessionManager.isConnected(PeripheralSlot.RFID_READER);
        boolean rfidNeeded = cbRfid.isSelected() && !simulation;

        btnStartWorkflow.setEnabled(!workflowRunning && (simulation || (scaleOk && (!rfidNeeded || rfidOk))));
        btnRestartWorkflow.setEnabled(workflowRunning);
        btnConfigScale.setEnabled(!workflowRunning && !simulation);
        btnConfigRfid.setEnabled(!workflowRunning && !simulation);
        cbRfid.setEnabled(!workflowRunning);
        cbPhoto.setEnabled(!workflowRunning);
        cbLabel.setEnabled(!workflowRunning);
        cbSimulation.setEnabled(!workflowRunning);

        if (simulation && !workflowRunning) {
            lbWorkflowStatus.setText("Modo simulação — pronto para iniciar sem hardware");
        } else if (!scaleOk && !workflowRunning) {
            lbWorkflowStatus.setText("Configure a balança para iniciar o fluxo");
        } else if (rfidNeeded && !rfidOk && !workflowRunning) {
            lbWorkflowStatus.setText("Configure o leitor RFID ou desmarque a leitura RFID");
        } else if (!workflowRunning) {
            lbWorkflowStatus.setText("Pronto para iniciar o fluxo");
        }
        WorkflowUiTheme.setStatusColor(lbWorkflowStatus, WorkflowUiTheme.TEXT_SECONDARY);
    }

    private void startWorkflow() {
        boolean simulation = cbSimulation.isSelected();
        if (!simulation && !sessionManager.isConnected(PeripheralSlot.SCALE)) {
            JOptionPane.showMessageDialog(getDialogParent(),
                    "Configure e conecte a balança antes de iniciar.", "Fluxo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!simulation && cbRfid.isSelected() && !sessionManager.isConnected(PeripheralSlot.RFID_READER)) {
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

        WorkflowConfig config = new WorkflowConfig(steps, WorkflowConfig.DEFAULT_RFID_READ_MS, simulation);
        orchestrator = new WeighingWorkflowOrchestrator(sessionManager);
        closeOperationWindow();

        Window parent = getOwnerWindow();
        operationWindow = new WorkflowOperationWindow(parent, orchestrator, config);

        try {
            orchestrator.start(config, createWorkflowListener(operationWindow));
            workflowRunning = true;
            lbWorkflowStatus.setText(simulation
                    ? "Simulação em execução — use a janela de operação"
                    : "Fluxo em execução — use a janela de operação");
            appendLog(simulation ? "Fluxo iniciado (simulação)." : "Fluxo iniciado.");
            updateWorkflowControls();
            btnStopWorkflow.setEnabled(true);
            btnRestartWorkflow.setEnabled(true);
            operationWindow.setVisible(true);
        } catch (PeripheralException e) {
            closeOperationWindow();
            orchestrator = null;
            JOptionPane.showMessageDialog(getDialogParent(), e.getMessage(), "Fluxo", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopWorkflow() {
        if (orchestrator != null) {
            orchestrator.stop();
            orchestrator = null;
        }
        closeOperationWindow();
        workflowRunning = false;
        lbWorkflowStatus.setText("Fluxo parado");
        btnStopWorkflow.setEnabled(false);
        btnRestartWorkflow.setEnabled(false);
        appendLog("Fluxo parado.");
        updateWorkflowControls();
    }

    private void restartWorkflowSession() {
        if (operationWindow != null) {
            operationWindow.restartSession();
            appendLog("Sessão reiniciada — histórico limpo.");
            lbWorkflowStatus.setText("Sessão reiniciada — aguardando nova leitura");
            return;
        }
        if (orchestrator != null && orchestrator.isRunning()) {
            try {
                orchestrator.restartSession();
                appendLog("Sessão reiniciada — histórico limpo.");
                lbWorkflowStatus.setText("Sessão reiniciada — aguardando nova leitura");
            } catch (PeripheralException e) {
                JOptionPane.showMessageDialog(getDialogParent(), e.getMessage(),
                        "Reiniciar sessão", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void closeOperationWindow() {
        if (operationWindow != null) {
            operationWindow.dispose();
            operationWindow = null;
        }
    }

    private Window getOwnerWindow() {
        if (ownerWindow != null) {
            return ownerWindow;
        }
        Component ancestor = SwingUtilities.getWindowAncestor(this);
        return ancestor instanceof Window ? (Window) ancestor : null;
    }

    private WorkflowListener createWorkflowListener(WorkflowOperationWindow window) {
        return new WorkflowListener() {
            @Override
            public void onWeightUpdate(PeripheralDataEvent event) {
                window.onWeightUpdate(event);
            }

            @Override
            public void onTagRead(PeripheralDataEvent event) {
                window.onTagRead(event);
            }

            @Override
            public void onStepChanged(WorkflowStep step, String message) {
                window.onStepChanged(step, message);
                SwingUtilities.invokeLater(() -> {
                    lbWorkflowStatus.setText(message);
                    appendLog("[" + step.getLabel() + "] " + message);
                });
            }

            @Override
            public void onAwaitingWeighingStart() {
                window.onAwaitingWeighingStart();
            }

            @Override
            public void onStabilizationProgress(String message) {
                window.onStabilizationProgress(message);
            }

            @Override
            public void onCycleCompleted(WorkflowContext context) {
                window.onCycleCompleted(context);
            }

            @Override
            public void onReadingRecorded(com.peripheral.workflow.WorkflowReadingRecord record) {
                window.onReadingRecorded(record);
                SwingUtilities.invokeLater(() -> appendLog("Leitura #" + record.getIndex()
                        + " — peso: " + record.getWeightKg() + " kg, produtos: "
                        + record.getTagCodes().size()));
            }

            @Override
            public void onSessionCleared() {
                window.onSessionCleared();
            }

            @Override
            public void onWaitingForNext() {
                window.onWaitingForNext();
                SwingUtilities.invokeLater(() ->
                        lbWorkflowStatus.setText("Ciclo concluído — aguardando Próximo na janela de operação"));
            }

            @Override
            public void onError(String message, Throwable cause) {
                window.onError(message, cause);
                SwingUtilities.invokeLater(() -> {
                    appendLog("ERRO: " + message);
                    lbWorkflowStatus.setText("Erro: " + message);
                });
            }

            @Override
            public void onStopped() {
                window.onStopped();
                SwingUtilities.invokeLater(() -> {
                    workflowRunning = false;
                    lbWorkflowStatus.setText("Fluxo parado");
                    btnStopWorkflow.setEnabled(false);
                    btnRestartWorkflow.setEnabled(false);
                    operationWindow = null;
                    updateWorkflowControls();
                });
            }
        };
    }

    private void appendLog(String msg) {
        String line = "[" + timeFormat.format(new Date()) + "] " + msg;
        if (logConsumer != null) {
            logConsumer.accept(line);
        }
    }

    private Component getDialogParent() {
        Window w = getOwnerWindow();
        return w != null ? w : this;
    }
}
