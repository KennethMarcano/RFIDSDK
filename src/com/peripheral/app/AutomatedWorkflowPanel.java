package com.peripheral.app;

import com.peripheral.core.DeviceModelEntry;
import com.peripheral.core.PeripheralDataEvent;
import com.peripheral.core.PeripheralException;
import com.peripheral.session.PeripheralConnectionHandle;
import com.peripheral.camera.CameraMicroserviceClient;
import com.peripheral.camera.CameraMicroserviceLifecycle;
import com.peripheral.pedido.Pedido;
import com.peripheral.pedido.PedidoClient;
import com.peripheral.pedido.PedidoClients;
import com.peripheral.pedido.PedidoException;
import com.peripheral.pedido.PedidoItem;
import com.peripheral.pedido.PedidoVolume;
import com.peripheral.session.PeripheralSessionManager;
import com.peripheral.session.PeripheralSlot;
import com.peripheral.workflow.OrderAwareWorkflowOrchestrator;
import com.peripheral.workflow.WeighingWorkflowOrchestrator;
import com.peripheral.workflow.WorkflowConfig;
import com.peripheral.workflow.WorkflowContext;
import com.peripheral.workflow.WorkflowController;
import com.peripheral.workflow.WorkflowEnvironment;
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
    private final JCheckBox cbOrderValidation = new JCheckBox("Validar pedido (peso + RFID + IA fallback)", false);
    private final JCheckBox cbPedidoMock = new JCheckBox("Usar pedido mock (demo)", true);
    private final JCheckBox cbDemoDivergence = new JCheckBox("Cenário demo (forçar divergência)", false);
    private final JTextField tfPedidoNumero = new JTextField("1001", 10);
    private final JLabel lbPedidoResumo = new JLabel("Nenhum pedido carregado");
    private final JLabel lbCameraStatus = new JLabel("Câmera: verificando...");
    private final JSpinner spTolerancePercent = new JSpinner(
            new SpinnerNumberModel(WorkflowConfig.DEFAULT_WEIGHT_TOLERANCE_PERCENT, 0.1, 50.0, 0.5));
    private final JSpinner spToleranceKg = new JSpinner(
            new SpinnerNumberModel(WorkflowConfig.DEFAULT_WEIGHT_TOLERANCE_KG, 0.001, 10.0, 0.01));
    private final ThemedButton btnLoadPedido =
            WorkflowUiTheme.button("Carregar pedido", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnRecalibrateCamera =
            WorkflowUiTheme.button("Recalibrar câmera", ThemedButton.Variant.SECONDARY);

    private WorkflowController orchestrator;
    private Pedido loadedPedido;
    private final ThemedButton btnStartWorkflow =
            WorkflowUiTheme.button("Iniciar fluxo", ThemedButton.Variant.PRIMARY);
    private final ThemedButton btnStopWorkflow =
            WorkflowUiTheme.button("Parar fluxo", ThemedButton.Variant.DANGER);
    private final ThemedButton btnRestartWorkflow =
            WorkflowUiTheme.button("Reiniciar sessão", ThemedButton.Variant.SECONDARY);

    private final JLabel lbWorkflowStatus = new JLabel("Pronto para iniciar o fluxo");
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
        refreshCameraStatus();
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
        top.add(buildOrderSection());
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
        cbOrderValidation.addActionListener(e -> updateWorkflowControls());
        btnLoadPedido.addActionListener(e -> loadPedido());
        btnRecalibrateCamera.addActionListener(e -> recalibrateCamera());
    }

    private JPanel buildOrderSection() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 0, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        styleCheckBox(cbOrderValidation);
        styleCheckBox(cbPedidoMock);
        styleCheckBox(cbDemoDivergence);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 3;
        grid.add(cbOrderValidation, gbc);

        gbc.gridwidth = 1;
        gbc.gridy = 1;
        gbc.gridx = 0;
        grid.add(new JLabel("Nº pedido:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        grid.add(tfPedidoNumero, gbc);
        gbc.gridx = 2;
        gbc.weightx = 0;
        grid.add(btnLoadPedido, gbc);

        gbc.gridy = 2;
        gbc.gridx = 0;
        grid.add(new JLabel("Tolerância ±%:"), gbc);
        gbc.gridx = 1;
        grid.add(spTolerancePercent, gbc);
        gbc.gridx = 0;
        gbc.gridy = 3;
        grid.add(new JLabel("Tolerância ±kg:"), gbc);
        gbc.gridx = 1;
        grid.add(spToleranceKg, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 3;
        grid.add(cbPedidoMock, gbc);
        gbc.gridy = 5;
        grid.add(cbDemoDivergence, gbc);

        lbPedidoResumo.setFont(WorkflowUiTheme.fontMeta(lbPedidoResumo));
        lbPedidoResumo.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        gbc.gridy = 6;
        grid.add(lbPedidoResumo, gbc);

        lbCameraStatus.setFont(WorkflowUiTheme.fontMeta(lbCameraStatus));
        gbc.gridy = 7;
        grid.add(lbCameraStatus, gbc);

        gbc.gridy = 8;
        grid.add(btnRecalibrateCamera, gbc);

        return WorkflowUiTheme.createSection("Pedido e câmera", grid);
    }

    private void loadPedido() {
        if (workflowRunning) {
            showWorkflowMessage("Pare o fluxo antes de carregar outro pedido.", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            PedidoClient client = cbPedidoMock.isSelected()
                    ? new com.peripheral.pedido.MockPedidoClient()
                    : PedidoClients.createDefault();
            if (!cbPedidoMock.isSelected()) {
                System.setProperty("rfidsdk.pedido.mock", "false");
            }
            loadedPedido = client.fetchPedido(tfPedidoNumero.getText().trim());
            lbPedidoResumo.setText(formatPedidoResumo(loadedPedido));
            appendLog("Pedido carregado: " + loadedPedido.getNumero()
                    + " (" + loadedPedido.getVolumeCount() + " volumes)");
        } catch (PedidoException e) {
            loadedPedido = null;
            lbPedidoResumo.setText("Erro: " + e.getMessage());
            showWorkflowMessage(e.getMessage(), JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String formatPedidoResumo(Pedido pedido) {
        StringBuilder sb = new StringBuilder();
        sb.append("Pedido ").append(pedido.getNumero())
                .append(" — ").append(pedido.getVolumeCount()).append(" volume(s): ");
        for (int i = 0; i < pedido.getVolumeCount(); i++) {
            PedidoVolume vol = pedido.getVolume(i);
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append("Vol.").append(vol.getIndice()).append(" (")
                    .append(String.format("%.3f", vol.getPesoEsperadoKg())).append(" kg)");
        }
        return sb.toString();
    }

    private void recalibrateCamera() {
        CameraMicroserviceClient client = CameraMicroserviceLifecycle.getInstance().getClient();
        if (!client.isAvailable()) {
            showWorkflowMessage("Serviço de câmera indisponível.", JOptionPane.WARNING_MESSAGE);
            refreshCameraStatus();
            return;
        }
        try {
            String msg = client.recalibrate();
            appendLog("Recalibração: " + msg);
            JOptionPane.showMessageDialog(getDialogParent(), msg, "Câmera", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            showWorkflowMessage("Erro na recalibração: " + e.getMessage(), JOptionPane.ERROR_MESSAGE);
        }
        refreshCameraStatus();
    }

    private void refreshCameraStatus() {
        CameraMicroserviceClient client = CameraMicroserviceLifecycle.getInstance().getClient();
        boolean ok = client.checkHealth();
        lbCameraStatus.setText(ok ? "Câmera: online" : "Câmera: indisponível");
        WorkflowUiTheme.setStatusColor(lbCameraStatus, ok ? WorkflowUiTheme.SUCCESS : WorkflowUiTheme.WARNING);
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
                "<html>Após estabilizar 1,5 s → RFID (1 s) → validação do pedido (se ativa). "
                        + "Divergência: foto + IA fallback + revisão do operador. "
                        + "Caminho OK: etiqueta (e foto se marcada). "
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

        boolean canStart = !workflowRunning && (simulation || (scaleOk && (!rfidNeeded || rfidOk)));
        btnStartWorkflow.setEnabled(canStart);
        btnStartWorkflow.setToolTipText(canStart ? null : buildStartBlockedTooltip(simulation, scaleOk, rfidNeeded, rfidOk));
        btnRestartWorkflow.setEnabled(workflowRunning);
        btnConfigScale.setEnabled(!workflowRunning && !simulation);
        btnConfigRfid.setEnabled(!workflowRunning && !simulation);
        cbRfid.setEnabled(!workflowRunning);
        cbPhoto.setEnabled(!workflowRunning);
        cbLabel.setEnabled(!workflowRunning);
        cbSimulation.setEnabled(!workflowRunning);
        cbOrderValidation.setEnabled(!workflowRunning);
        cbPedidoMock.setEnabled(!workflowRunning);
        cbDemoDivergence.setEnabled(!workflowRunning && cbOrderValidation.isSelected());
        tfPedidoNumero.setEnabled(!workflowRunning);
        btnLoadPedido.setEnabled(!workflowRunning);
        spTolerancePercent.setEnabled(!workflowRunning);
        spToleranceKg.setEnabled(!workflowRunning);
        btnRecalibrateCamera.setEnabled(!workflowRunning);

        if (cbOrderValidation.isSelected() && loadedPedido == null && !workflowRunning) {
            btnStartWorkflow.setEnabled(false);
            btnStartWorkflow.setToolTipText("Carregue um pedido antes de iniciar.");
            lbWorkflowStatus.setText("Carregue um pedido para iniciar validação");
            return;
        }

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

    private String buildStartBlockedTooltip(boolean simulation, boolean scaleOk,
                                            boolean rfidNeeded, boolean rfidOk) {
        if (simulation) {
            return null;
        }
        if (!scaleOk) {
            return "Configure e conecte a balança, ou ative o modo simulação.";
        }
        if (rfidNeeded && !rfidOk) {
            return "Configure o leitor RFID ou desmarque a leitura RFID.";
        }
        return null;
    }

    private void startWorkflow() {
        boolean simulation = cbSimulation.isSelected();
        appendLog("Solicitação de início do fluxo (simulação=" + simulation + ").");

        if (!simulation && !sessionManager.isConnected(PeripheralSlot.SCALE)) {
            showWorkflowMessage("Configure e conecte a balança antes de iniciar, ou ative o modo simulação.",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!simulation && cbRfid.isSelected() && !sessionManager.isConnected(PeripheralSlot.RFID_READER)) {
            showWorkflowMessage("Configure o leitor RFID ou desmarque a leitura RFID.",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (cbOrderValidation.isSelected() && loadedPedido == null) {
            showWorkflowMessage("Carregue um pedido antes de iniciar a validação.", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Set<WorkflowStep> steps = EnumSet.of(WorkflowStep.WEIGHING);
        if (cbRfid.isSelected()) {
            steps.add(WorkflowStep.RFID_READ);
        }
        if (cbPhoto.isSelected()) {
            steps.add(WorkflowStep.CAPTURE_PHOTO);
        }
        if (cbLabel.isSelected() || cbOrderValidation.isSelected()) {
            steps.add(WorkflowStep.PRINT_LABEL);
        }

        String sessionError = WorkflowEnvironment.checkSessionDirectoryWritable();
        if (sessionError != null) {
            showWorkflowError(sessionError);
            return;
        }
        if (steps.contains(WorkflowStep.PRINT_LABEL)) {
            String pdfError = WorkflowEnvironment.checkPdfLibrariesAvailable();
            if (pdfError != null) {
                showWorkflowError(pdfError);
                return;
            }
        }

        double tolPercent = ((Number) spTolerancePercent.getValue()).doubleValue();
        double tolKg = ((Number) spToleranceKg.getValue()).doubleValue();
        WorkflowConfig config = new WorkflowConfig(
                steps,
                WorkflowConfig.DEFAULT_RFID_READ_MS,
                simulation,
                cbOrderValidation.isSelected(),
                tolPercent,
                tolKg,
                cbDemoDivergence.isSelected());
        closeOperationWindow();

        CameraMicroserviceClient cameraClient = CameraMicroserviceLifecycle.getInstance().getClient();
        refreshCameraStatus();

        try {
            if (cbOrderValidation.isSelected()) {
                orchestrator = new OrderAwareWorkflowOrchestrator(
                        sessionManager, loadedPedido, cameraClient);
            } else {
                orchestrator = new WeighingWorkflowOrchestrator(sessionManager);
            }
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            showWorkflowError("Dependência ausente ao iniciar o fluxo: " + e.getMessage()
                    + ". No Linux use ./iniciar.sh para incluir todas as bibliotecas.");
            return;
        } catch (Exception e) {
            showWorkflowError("Erro ao preparar o fluxo: " + e.getMessage());
            return;
        }

        Window parent = getOwnerWindow();
        try {
            operationWindow = new WorkflowOperationWindow(
                    parent, orchestrator, config, cbOrderValidation.isSelected());
        } catch (Exception ex) {
            orchestrator = null;
            appendLog("ERRO ao abrir janela de operação: " + ex.getMessage());
            showWorkflowMessage("Não foi possível abrir a janela de operação: " + ex.getMessage(),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        btnStartWorkflow.setEnabled(false);
        lbWorkflowStatus.setText("Iniciando fluxo...");

        final WorkflowOperationWindow window = operationWindow;
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    orchestrator.start(config, createWorkflowListener(window));
                    return null;
                } catch (PeripheralException e) {
                    return e.getMessage();
                } catch (Exception e) {
                    return e.getClass().getSimpleName() + ": " + e.getMessage();
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
                    closeOperationWindow();
                    orchestrator = null;
                    appendLog("ERRO ao iniciar fluxo: " + error);
                    lbWorkflowStatus.setText("Erro ao iniciar o fluxo");
                    showWorkflowMessage(error, JOptionPane.ERROR_MESSAGE);
                    updateWorkflowControls();
                    return;
                }

                workflowRunning = true;
                lbWorkflowStatus.setText(simulation
                        ? "Simulação em execução — use a janela de operação"
                        : "Fluxo em execução — use a janela de operação");
                appendLog(simulation ? "Fluxo iniciado (simulação)." : "Fluxo iniciado.");
                updateWorkflowControls();
                btnStopWorkflow.setEnabled(true);
                btnRestartWorkflow.setEnabled(true);
                showOperationWindow(window);
            }
        }.execute();
    }

    private void showOperationWindow(WorkflowOperationWindow window) {
        if (window == null) {
            return;
        }
        window.setVisible(true);
        window.toFront();
        window.requestFocus();
        // Alguns gerenciadores de janela no Linux (ex.: Raspberry Pi) não trazem diálogos modeless à frente.
        window.setAlwaysOnTop(true);
        Timer raiseTimer = new Timer(400, e -> window.setAlwaysOnTop(false));
        raiseTimer.setRepeats(false);
        raiseTimer.start();
    }

    private void showWorkflowMessage(String message, int messageType) {
        appendLog(message);
        JOptionPane.showMessageDialog(getDialogParent(), message, "Fluxo", messageType);
    }

    private void showWorkflowError(String message) {
        appendLog("ERRO: " + message);
        lbWorkflowStatus.setText(message);
        WorkflowUiTheme.setStatusColor(lbWorkflowStatus, WorkflowUiTheme.DANGER);
        JOptionPane.showMessageDialog(getDialogParent(), message, "Fluxo", JOptionPane.ERROR_MESSAGE);
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
                    refreshCameraStatus();
                });
            }

            @Override
            public void onOrderLoaded(Pedido pedido) {
                window.onOrderLoaded(pedido);
            }

            @Override
            public void onVolumeChanged(int currentIndex, int totalVolumes) {
                window.onVolumeChanged(currentIndex, totalVolumes);
            }

            @Override
            public void onValidationResult(com.peripheral.workflow.PedidoValidationService.ValidationResult result) {
                window.onValidationResult(result);
            }

            @Override
            public void onOperatorReviewRequired(String message, WorkflowContext context) {
                window.onOperatorReviewRequired(message, context);
            }

            @Override
            public void onCameraServiceStatus(boolean available, String detail) {
                SwingUtilities.invokeLater(() -> {
                    lbCameraStatus.setText(available ? "Câmera: online" : "Câmera: indisponível");
                    WorkflowUiTheme.setStatusColor(lbCameraStatus,
                            available ? WorkflowUiTheme.SUCCESS : WorkflowUiTheme.WARNING);
                    window.onCameraServiceStatus(available, detail);
                });
            }

            @Override
            public void onOrderCompleted(Pedido pedido) {
                window.onOrderCompleted(pedido);
                SwingUtilities.invokeLater(() -> {
                    lbWorkflowStatus.setText("Pedido " + pedido.getNumero() + " concluído");
                    appendLog("Pedido concluído: " + pedido.getNumero());
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
