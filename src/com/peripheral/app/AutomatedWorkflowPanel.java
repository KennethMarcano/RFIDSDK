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
    private final JLabel lbCameraSummary = new JLabel();
    private final ThemedButton btnConfigScale =
            WorkflowUiTheme.button("Configurar", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnConfigRfid =
            WorkflowUiTheme.button("Configurar", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnTestCamera =
            WorkflowUiTheme.button("Testar", ThemedButton.Variant.SECONDARY);

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
        top.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel peripherals = buildPeripheralsSection();
        JPanel order = buildOrderSection();
        JPanel process = buildProcessSection();
        JPanel hint = buildOperationHintSection();
        WorkflowUiTheme.prepareBoxSection(process);
        WorkflowUiTheme.prepareBoxSection(hint);

        JPanel configRow = WorkflowUiTheme.createResponsiveColumns(peripherals, order);
        top.add(configRow);
        top.add(Box.createVerticalStrut(4));
        top.add(process);
        top.add(Box.createVerticalStrut(4));
        top.add(hint);

        add(WorkflowUiTheme.wrapVerticalScroll(top), BorderLayout.CENTER);

        btnConfigScale.addActionListener(e -> openConfigDialog(PeripheralSlot.SCALE));
        btnConfigRfid.addActionListener(e -> openConfigDialog(PeripheralSlot.RFID_READER));
        btnTestCamera.addActionListener(e -> openCameraTestDialog());
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
        styleCheckBox(cbOrderValidation);
        styleCheckBox(cbPedidoMock);
        styleCheckBox(cbDemoDivergence);
        WorkflowUiTheme.styleCompactTextField(tfPedidoNumero, 8);
        WorkflowUiTheme.styleCompactSpinner(spTolerancePercent);
        WorkflowUiTheme.styleCompactSpinner(spToleranceKg);

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setAlignmentX(Component.LEFT_ALIGNMENT);

        cbOrderValidation.setFont(cbOrderValidation.getFont().deriveFont(Font.BOLD, 12f));
        JPanel toggleRow = WorkflowUiTheme.formRow(cbOrderValidation);
        column.add(toggleRow);
        column.add(Box.createVerticalStrut(12));

        JPanel searchContent = WorkflowUiTheme.formRow(
                WorkflowUiTheme.formLabel("Nº pedido"),
                tfPedidoNumero,
                btnLoadPedido);
        JPanel searchGroup = WorkflowUiTheme.createInsetGroup("Identificação do pedido", searchContent);
        searchGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(searchGroup);
        column.add(Box.createVerticalStrut(10));

        JPanel toleranceContent = new JPanel();
        toleranceContent.setOpaque(false);
        toleranceContent.setLayout(new BoxLayout(toleranceContent, BoxLayout.Y_AXIS));
        toleranceContent.add(WorkflowUiTheme.formRow(
                WorkflowUiTheme.formLabel("± %"), spTolerancePercent));
        toleranceContent.add(Box.createVerticalStrut(6));
        toleranceContent.add(WorkflowUiTheme.formRow(
                WorkflowUiTheme.formLabel("± kg"), spToleranceKg));

        JPanel demoContent = new JPanel();
        demoContent.setOpaque(false);
        demoContent.setLayout(new BoxLayout(demoContent, BoxLayout.Y_AXIS));
        demoContent.add(cbPedidoMock);
        demoContent.add(Box.createVerticalStrut(4));
        demoContent.add(cbDemoDivergence);

        JPanel settingsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        settingsRow.setOpaque(false);
        settingsRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        settingsRow.add(WorkflowUiTheme.createInsetGroup("Tolerância de peso", toleranceContent));
        settingsRow.add(WorkflowUiTheme.createInsetGroup("Opções demo", demoContent));
        column.add(settingsRow);
        column.add(Box.createVerticalStrut(10));

        lbPedidoResumo.setFont(WorkflowUiTheme.fontMeta(lbPedidoResumo));
        lbPedidoResumo.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        styleCameraStatusPill(false);

        JPanel statusStrip = WorkflowUiTheme.createStatusStrip();
        JLabel statusCaption = WorkflowUiTheme.formLabel("Status");
        WorkflowUiTheme.styleMutedCaption(statusCaption);
        statusStrip.add(statusCaption);
        statusStrip.add(lbPedidoResumo);
        statusStrip.add(lbCameraStatus);
        statusStrip.add(btnRecalibrateCamera);
        column.add(statusStrip);

        return WorkflowUiTheme.createSection("Pedido e câmera", column);
    }

    private void styleCameraStatusPill(boolean online) {
        if (online) {
            WorkflowUiTheme.styleStatusPill(lbCameraStatus,
                    new Color(0xD1, 0xFA, 0xE5), WorkflowUiTheme.SUCCESS);
        } else {
            WorkflowUiTheme.styleStatusPill(lbCameraStatus,
                    new Color(0xFE, 0xF3, 0xC7), WorkflowUiTheme.WARNING);
        }
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
            String resumo = formatPedidoResumo(loadedPedido);
            lbPedidoResumo.setText(resumo);
            lbPedidoResumo.setToolTipText(resumo);
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
                    .append(vol.getTotalSeriais()).append(" seriais, ")
                    .append(String.format("%.3f", vol.getPesoEsperadoKg())).append(" kg)");
        }
        return sb.toString();
    }

    private void recalibrateCamera() {
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                CameraMicroserviceLifecycle lifecycle = CameraMicroserviceLifecycle.getInstance();
                CameraMicroserviceClient client = lifecycle.getClient();
                if (!client.checkHealth()) {
                    lifecycle.start();
                    client.checkHealth();
                }
                if (client.isAvailable()) {
                    return client.recalibrate();
                }
                return com.peripheral.camera.CameraHardware.recalibrate();
            }

            @Override
            protected void done() {
                try {
                    String msg = get();
                    appendLog("Recalibração: " + msg);
                    JOptionPane.showMessageDialog(getDialogParent(), msg, "Câmera",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    showWorkflowMessage("Erro na recalibração: " + cause.getMessage(),
                            JOptionPane.ERROR_MESSAGE);
                }
                refreshCameraStatus();
            }
        }.execute();
    }

    private void refreshCameraStatus() {
        new SwingWorker<int[], Void>() {
            @Override
            protected int[] doInBackground() {
                CameraMicroserviceClient client = CameraMicroserviceLifecycle.getInstance().getClient();
                boolean serviceOk = client.checkHealth();
                boolean rpicam = com.peripheral.camera.CameraHardware.isRpicamAvailable();
                boolean hardwareOk = rpicam && com.peripheral.camera.CameraHardware.isCameraPresent();
                return new int[]{serviceOk ? 1 : 0, hardwareOk ? 1 : 0, rpicam ? 1 : 0};
            }

            @Override
            protected void done() {
                boolean serviceOk = false;
                boolean hardwareOk = false;
                try {
                    int[] flags = get();
                    serviceOk = flags[0] == 1;
                    hardwareOk = flags[1] == 1;
                } catch (Exception ignored) {
                }
                boolean ok = serviceOk || hardwareOk;
                if (hardwareOk) {
                    lbCameraStatus.setText("Câmera online");
                    lbCameraSummary.setText("Sony IMX500 — disponível");
                    WorkflowUiTheme.setStatusColor(lbCameraSummary, WorkflowUiTheme.SUCCESS);
                } else if (serviceOk) {
                    lbCameraStatus.setText("Câmera online");
                    lbCameraSummary.setText("Serviço de câmera online");
                    WorkflowUiTheme.setStatusColor(lbCameraSummary, WorkflowUiTheme.SUCCESS);
                } else {
                    lbCameraStatus.setText("Câmera indisponível");
                    lbCameraSummary.setText("Não detectada — use Testar");
                    WorkflowUiTheme.setStatusColor(lbCameraSummary, WorkflowUiTheme.WARNING);
                }
                styleCameraStatusPill(ok);
            }
        }.execute();
    }

    private void openCameraTestDialog() {
        if (workflowRunning) {
            JOptionPane.showMessageDialog(getDialogParent(),
                    "Pare o fluxo antes de testar a câmera.",
                    "Câmera", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Window parent = ownerWindow;
        if (parent == null) {
            Component ancestor = SwingUtilities.getWindowAncestor(this);
            if (ancestor instanceof Window) {
                parent = (Window) ancestor;
            }
        }
        CameraTestDialog dialog = new CameraTestDialog(parent, this::appendLog);
        dialog.showDialog();
        refreshCameraStatus();
    }

    private JPanel buildPeripheralRow(String title, boolean required, JLabel summary, ThemedButton action) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, WorkflowUiTheme.BORDER),
                WorkflowUiTheme.empty(10, 0, 10, 0)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

        JLabel name = WorkflowUiTheme.formLabel(title + (required ? " *" : ""));
        name.setPreferredSize(new Dimension(96, name.getPreferredSize().height));
        row.add(name, BorderLayout.WEST);

        summary.setFont(WorkflowUiTheme.fontStatus(summary));
        row.add(summary, BorderLayout.CENTER);
        row.add(action, BorderLayout.EAST);
        return row;
    }

    private JPanel buildPeripheralsSection() {
        JPanel rows = new JPanel();
        rows.setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.add(buildPeripheralRow("Balança", true, lbScaleSummary, btnConfigScale));
        rows.add(buildPeripheralRow("Leitor RFID", false, lbRfidSummary, btnConfigRfid));
        lbCameraSummary.setText("Verificando...");
        WorkflowUiTheme.setStatusColor(lbCameraSummary, WorkflowUiTheme.TEXT_MUTED);
        rows.add(buildPeripheralRow("Câmera", false, lbCameraSummary, btnTestCamera));
        return WorkflowUiTheme.createSection("Periféricos", rows);
    }

    private JPanel buildProcessSection() {
        JPanel checks = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
        checks.setOpaque(false);
        checks.setAlignmentX(Component.LEFT_ALIGNMENT);
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
        help.setBorder(WorkflowUiTheme.empty(4, 0, 0, 0));
        help.setAlignmentX(Component.LEFT_ALIGNMENT);

        btnStopWorkflow.setEnabled(false);
        btnRestartWorkflow.setEnabled(false);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        actions.setOpaque(false);
        actions.add(btnStartWorkflow);
        actions.add(btnStopWorkflow);
        actions.add(btnRestartWorkflow);

        lbWorkflowStatus.setFont(WorkflowUiTheme.fontStatus(lbWorkflowStatus));
        lbWorkflowStatus.setForeground(WorkflowUiTheme.TEXT_SECONDARY);

        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        statusRow.setOpaque(false);
        JLabel lbStatusCaption = WorkflowUiTheme.formLabel("Status");
        WorkflowUiTheme.styleMutedCaption(lbStatusCaption);
        statusRow.add(lbStatusCaption);
        statusRow.add(lbWorkflowStatus);

        JPanel actionBar = new JPanel(new BorderLayout(0, 8));
        actionBar.setOpaque(true);
        actionBar.setBackground(WorkflowUiTheme.CHIP_BG);
        actionBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.CHIP_BORDER),
                WorkflowUiTheme.empty(12, 12, 12, 12)));
        actionBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionBar.add(actions, BorderLayout.NORTH);
        actionBar.add(statusRow, BorderLayout.SOUTH);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(checks);
        content.add(Box.createVerticalStrut(10));
        content.add(help);
        content.add(Box.createVerticalStrut(12));
        content.add(actionBar);

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
                    lbCameraStatus.setText(available ? "Câmera online" : "Câmera indisponível");
                    styleCameraStatusPill(available);
                    if (available) {
                        lbCameraSummary.setText(detail != null && !detail.isEmpty()
                                ? detail : "Câmera disponível");
                        WorkflowUiTheme.setStatusColor(lbCameraSummary, WorkflowUiTheme.SUCCESS);
                    } else {
                        lbCameraSummary.setText("Não detectada — use Testar");
                        WorkflowUiTheme.setStatusColor(lbCameraSummary, WorkflowUiTheme.WARNING);
                    }
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
