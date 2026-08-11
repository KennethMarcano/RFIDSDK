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
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

public class AutomatedWorkflowPanel extends JPanel {

    private static final int PERIPHERAL_ROW_HEIGHT = 58;

    private final PeripheralSessionManager sessionManager;
    private final Consumer<String> logConsumer;

    private final JLabel lbScaleSummary = new JLabel();
    private final JLabel lbRfidSummary = new JLabel();
    private final JLabel lbCameraSummary = new JLabel();
    private final JLabel lbPrinterSummary = new JLabel();
    private final ThemedButton btnConfigScale =
            WorkflowUiTheme.button("Configurar", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnConfigRfid =
            WorkflowUiTheme.button("Configurar", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnTestCamera =
            WorkflowUiTheme.button("Testar", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnConfigPrinter =
            WorkflowUiTheme.button("Configurar", ThemedButton.Variant.SECONDARY);

    private final JCheckBox cbRfid = new JCheckBox("Leitura RFID contínua durante a pesagem", true);
    private final JCheckBox cbPhoto = new JCheckBox("Capturar foto", true);
    private final JCheckBox cbLabel = new JCheckBox("Imprimir etiqueta", true);
    private final JCheckBox cbWeighing = new JCheckBox("Pesagem (obrigatório)", true);
    private final JCheckBox cbSimulation = new JCheckBox("Modo simulação (sem hardware)", false);
    private final JCheckBox cbOrderValidation = new JCheckBox("Validar pedido (peso + RFID)", true);
    private final JCheckBox cbAiFallback = new JCheckBox("IA fallback (análise de vídeo na divergência)", true);
    private final JCheckBox cbPedidoMock = new JCheckBox("Usar pedido mock (demo)", true);
    private final JCheckBox cbDemoDivergence = new JCheckBox("Cenário demo (forçar divergência)", false);
    private final JTextField tfPedidoNumero = new JTextField("1001", 8);
    private final JLabel lbPedidoResumo = new JLabel("Nenhum pedido carregado");
    private final JLabel lbCameraStatus = new JLabel("Câmera: verificando...");
    private final JSpinner spTolerancePercent = new JSpinner(
            new SpinnerNumberModel(WorkflowConfig.DEFAULT_WEIGHT_TOLERANCE_PERCENT, 0.1, 50.0, 0.5));
    private final JSpinner spToleranceGrams = new JSpinner(
            new SpinnerNumberModel(WorkflowConfig.DEFAULT_WEIGHT_TOLERANCE_GRAMS, 1, 10_000, 1));
    private final ThemedButton btnLoadPedido =
            WorkflowUiTheme.button("Carregar todos", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnRecalibrateCamera =
            WorkflowUiTheme.button("Recalibrar câmera", ThemedButton.Variant.SECONDARY);

    private WorkflowController orchestrator;
    private Pedido loadedPedido;
    private java.util.List<Pedido> loadedPedidos = java.util.Collections.emptyList();
    private final ThemedButton btnStartWorkflow =
            WorkflowUiTheme.button("Iniciar fluxo", ThemedButton.Variant.PRIMARY)
                    .withSize(ThemedButton.Size.LARGE);
    private final ThemedButton btnStopWorkflow =
            WorkflowUiTheme.button("Parar", ThemedButton.Variant.DANGER);
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
        setBorder(WorkflowUiTheme.empty(0, 8, 8, 8));
        buildUi();
        refreshPeripheralSummaries();
        refreshCameraStatus();
        // Acelera o setup de campo: pedidos mock já carregados com validação + IA ligadas.
        loadPedido();
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
        JTabbedPane tabs = new JTabbedPane();
        WorkflowUiTheme.styleTabbedPane(tabs);
        tabs.addTab("Periféricos", WorkflowUiTheme.wrapVerticalScroll(buildPeripheralsTab()));
        tabs.addTab("Pedido", WorkflowUiTheme.wrapVerticalScroll(buildOrderTab()));
        tabs.addTab("Processos", WorkflowUiTheme.wrapVerticalScroll(buildProcessTab()));

        add(tabs, BorderLayout.CENTER);
        add(buildActionBar(), BorderLayout.SOUTH);

        btnConfigScale.addActionListener(e -> openConfigDialog(PeripheralSlot.SCALE));
        btnConfigRfid.addActionListener(e -> openConfigDialog(PeripheralSlot.RFID_READER));
        btnConfigPrinter.addActionListener(e -> openConfigDialog(PeripheralSlot.PRINTER));
        btnTestCamera.addActionListener(e -> openCameraTestDialog());
        btnStartWorkflow.addActionListener(e -> startWorkflow());
        btnStopWorkflow.addActionListener(e -> stopWorkflow());
        btnRestartWorkflow.addActionListener(e -> restartWorkflowSession());
        cbRfid.addActionListener(e -> updateWorkflowControls());
        cbSimulation.addActionListener(e -> updateWorkflowControls());
        cbOrderValidation.addActionListener(e -> {
            if (!cbOrderValidation.isSelected()) {
                cbAiFallback.setSelected(false);
            }
            updateWorkflowControls();
        });
        cbAiFallback.addActionListener(e -> updateWorkflowControls());
        btnLoadPedido.addActionListener(e -> loadPedido());
        btnRecalibrateCamera.addActionListener(e -> recalibrateCamera());
    }

    private JPanel buildPeripheralsTab() {
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBorder(WorkflowUiTheme.empty(8, 4, 4, 4));
        column.setAlignmentX(Component.LEFT_ALIGNMENT);

        column.add(buildPeripheralRow("Balança", true, lbScaleSummary, btnConfigScale));
        column.add(buildPeripheralRow("Leitor RFID", false, lbRfidSummary, btnConfigRfid));
        column.add(buildPeripheralRow("Impressora", false, lbPrinterSummary, btnConfigPrinter));
        lbCameraSummary.setText("Verificando...");
        WorkflowUiTheme.setStatusColor(lbCameraSummary, WorkflowUiTheme.TEXT_MUTED);
        column.add(buildPeripheralRow("Câmera", false, lbCameraSummary, btnTestCamera));

        styleCameraStatusPill(false);
        JPanel statusStrip = WorkflowUiTheme.createStatusStrip();
        statusStrip.add(lbCameraStatus);
        statusStrip.add(btnRecalibrateCamera);
        statusStrip.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        column.add(Box.createVerticalStrut(8));
        column.add(statusStrip);

        JLabel hint = WorkflowUiTheme.createHintLabel(
                "Em Configurar: peso ao vivo na balança e tags com repetições no RFID.");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(WorkflowUiTheme.empty(8, 2, 0, 2));
        column.add(hint);

        return column;
    }

    private JPanel buildPeripheralRow(String title, boolean required, JLabel summary, ThemedButton action) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, WorkflowUiTheme.BORDER),
                WorkflowUiTheme.empty(6, 2, 6, 2)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, PERIPHERAL_ROW_HEIGHT));

        JLabel name = new JLabel(title + (required ? " *" : ""));
        name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
        name.setForeground(WorkflowUiTheme.TEXT_PRIMARY);
        name.setPreferredSize(new Dimension(104, name.getPreferredSize().height));

        summary.setFont(WorkflowUiTheme.fontMeta(summary));

        row.add(name, BorderLayout.WEST);
        row.add(summary, BorderLayout.CENTER);
        row.add(action, BorderLayout.EAST);
        return row;
    }

    private JPanel buildOrderTab() {
        WorkflowUiTheme.styleTouchCheckBox(cbOrderValidation);
        WorkflowUiTheme.styleTouchCheckBox(cbAiFallback);
        WorkflowUiTheme.styleTouchCheckBox(cbPedidoMock);
        WorkflowUiTheme.styleTouchCheckBox(cbDemoDivergence);
        WorkflowUiTheme.styleCompactTextField(tfPedidoNumero, 8);
        WorkflowUiTheme.styleCompactSpinner(spTolerancePercent);
        WorkflowUiTheme.styleCompactSpinner(spToleranceGrams);

        cbOrderValidation.setFont(cbOrderValidation.getFont().deriveFont(Font.BOLD, 13f));

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBorder(WorkflowUiTheme.empty(8, 4, 4, 4));
        column.setAlignmentX(Component.LEFT_ALIGNMENT);

        column.add(WorkflowUiTheme.formRow(cbOrderValidation));
        cbAiFallback.setBorder(WorkflowUiTheme.empty(0, 24, 0, 0));
        column.add(WorkflowUiTheme.formRow(cbAiFallback));

        JPanel numberRow = WorkflowUiTheme.formRow(
                WorkflowUiTheme.formLabel("Nº (API)"),
                tfPedidoNumero,
                btnLoadPedido);
        JPanel numberGroup = WorkflowUiTheme.createInsetGroup(
                "Pedidos (mock = carrega todos)", numberRow);
        numberGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
        numberGroup.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        column.add(numberGroup);
        column.add(Box.createVerticalStrut(8));

        JPanel toleranceContent = WorkflowUiTheme.formRow(
                WorkflowUiTheme.formLabel("± %"), spTolerancePercent,
                WorkflowUiTheme.formLabel("± g"), spToleranceGrams);
        JPanel toleranceGroup = WorkflowUiTheme.createInsetGroup("Tolerância de peso", toleranceContent);
        toleranceGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
        toleranceGroup.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
        column.add(toleranceGroup);
        column.add(Box.createVerticalStrut(8));

        JPanel demoContent = new JPanel();
        demoContent.setOpaque(false);
        demoContent.setLayout(new BoxLayout(demoContent, BoxLayout.Y_AXIS));
        cbPedidoMock.setAlignmentX(Component.LEFT_ALIGNMENT);
        cbDemoDivergence.setAlignmentX(Component.LEFT_ALIGNMENT);
        demoContent.add(cbPedidoMock);
        demoContent.add(cbDemoDivergence);
        JPanel demoGroup = WorkflowUiTheme.createInsetGroup("Opções demo", demoContent);
        demoGroup.setAlignmentX(Component.LEFT_ALIGNMENT);
        demoGroup.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        column.add(demoGroup);
        column.add(Box.createVerticalStrut(8));

        lbPedidoResumo.setFont(WorkflowUiTheme.fontMeta(lbPedidoResumo));
        lbPedidoResumo.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        JPanel statusStrip = WorkflowUiTheme.createStatusStrip();
        statusStrip.add(lbPedidoResumo);
        statusStrip.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        column.add(statusStrip);

        return column;
    }

    private JPanel buildProcessTab() {
        cbWeighing.setSelected(true);
        cbWeighing.setEnabled(false);
        WorkflowUiTheme.styleTouchCheckBox(cbWeighing);
        WorkflowUiTheme.styleTouchCheckBox(cbRfid);
        WorkflowUiTheme.styleTouchCheckBox(cbPhoto);
        WorkflowUiTheme.styleTouchCheckBox(cbLabel);
        WorkflowUiTheme.styleTouchCheckBox(cbSimulation);

        JPanel checks = new JPanel(new GridLayout(0, 2, 8, 2));
        checks.setOpaque(false);
        checks.setAlignmentX(Component.LEFT_ALIGNMENT);
        checks.add(cbWeighing);
        checks.add(cbRfid);
        checks.add(cbPhoto);
        checks.add(cbLabel);
        checks.add(cbSimulation);
        checks.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));

        JLabel help = WorkflowUiTheme.createHintLabel(
                "<html>Fluxo em fases: 1) Iniciar leitura tags → 2) Iniciar leitura peso (RFID parado). "
                        + "UI mostra só as tags lidas; a validação compara com o pedido (se ativa). "
                        + "Divergência: revisão do operador (+ foto/IA se fallback marcado). "
                        + "Caminho OK: etiqueta PDF+ZPL na Zebra (só se o pedido conferir) e foto se marcada.</html>");
        help.setAlignmentX(Component.LEFT_ALIGNMENT);
        help.setBorder(WorkflowUiTheme.empty(10, 2, 0, 2));

        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBorder(WorkflowUiTheme.empty(8, 4, 4, 4));
        column.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(checks);
        column.add(help);
        return column;
    }

    private JPanel buildActionBar() {
        btnStopWorkflow.setEnabled(false);
        btnRestartWorkflow.setEnabled(false);

        lbWorkflowStatus.setFont(WorkflowUiTheme.fontStatus(lbWorkflowStatus));
        lbWorkflowStatus.setForeground(WorkflowUiTheme.TEXT_SECONDARY);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(btnRestartWorkflow);
        actions.add(btnStopWorkflow);
        actions.add(btnStartWorkflow);

        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setOpaque(true);
        bar.setBackground(WorkflowUiTheme.BG_CARD);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, WorkflowUiTheme.BORDER),
                WorkflowUiTheme.empty(8, 10, 8, 10)));
        bar.add(lbWorkflowStatus, BorderLayout.CENTER);
        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    private void styleCameraStatusPill(boolean online) {
        if (online) {
            WorkflowUiTheme.styleStatusPill(lbCameraStatus,
                    WorkflowUiTheme.BG_CARD_HIGHLIGHT, WorkflowUiTheme.SUCCESS);
        } else {
            WorkflowUiTheme.styleStatusPill(lbCameraStatus,
                    WorkflowUiTheme.ACCENT, WorkflowUiTheme.TEXT_ON_ACCENT);
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
            if (cbPedidoMock.isSelected()) {
                loadedPedidos = client.fetchAllPedidos();
                loadedPedido = loadedPedidos.isEmpty() ? null : loadedPedidos.get(0);
                String resumo = formatPedidoQueueResumo(loadedPedidos);
                lbPedidoResumo.setText(resumo);
                lbPedidoResumo.setToolTipText(resumo);
                appendLog("Pedidos carregados (" + loadedPedidos.size() + "): " + resumo);
            } else {
                loadedPedido = client.fetchPedido(tfPedidoNumero.getText().trim());
                loadedPedidos = java.util.Collections.singletonList(loadedPedido);
                String resumo = formatPedidoResumo(loadedPedido);
                lbPedidoResumo.setText(resumo);
                lbPedidoResumo.setToolTipText(resumo);
                appendLog("Pedido carregado: " + loadedPedido.getNumero()
                        + " (" + formatPedidoResumo(loadedPedido) + ")");
            }
        } catch (PedidoException e) {
            loadedPedido = null;
            loadedPedidos = java.util.Collections.emptyList();
            lbPedidoResumo.setText("Erro: " + e.getMessage());
            showWorkflowMessage(e.getMessage(), JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String formatPedidoQueueResumo(java.util.List<Pedido> pedidos) {
        if (pedidos == null || pedidos.isEmpty()) {
            return "Nenhum pedido carregado";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(pedidos.size()).append(" pedido(s): ");
        for (int i = 0; i < pedidos.size(); i++) {
            if (i > 0) {
                sb.append(" → ");
            }
            Pedido p = pedidos.get(i);
            PedidoVolume vol = p.getVolume(0);
            sb.append(p.getNumero());
            if (vol != null && !vol.getItens().isEmpty()) {
                sb.append(" [");
                for (int j = 0; j < vol.getItens().size(); j++) {
                    if (j > 0) {
                        sb.append("+");
                    }
                    sb.append(vol.getItens().get(j).getCodigoProduto());
                }
                sb.append("]");
            }
        }
        return sb.toString();
    }

    private static String formatPedidoResumo(Pedido pedido) {
        if (pedido.getVolumeCount() == 0) {
            return "Pedido " + pedido.getNumero() + " — sem itens";
        }
        PedidoVolume vol = pedido.getVolume(0);
        int produtos = vol != null ? vol.getItens().size() : 0;
        int seriais = vol != null ? vol.getTotalSeriais() : 0;
        double peso = vol != null ? vol.getPesoEsperadoKg() : 0;
        StringBuilder produtosTxt = new StringBuilder();
        if (vol != null) {
            for (int i = 0; i < vol.getItens().size(); i++) {
                if (i > 0) {
                    produtosTxt.append(", ");
                }
                produtosTxt.append(vol.getItens().get(i).getCodigoProduto());
            }
        }
        return "Pedido " + pedido.getNumero()
                + " — " + produtos + " produto(s) [" + produtosTxt + "]"
                + ", " + seriais + " tag(s), "
                + String.format(java.util.Locale.US, "%.3f kg", peso);
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
                boolean modelOk = client.checkReady();
                boolean rpicam = com.peripheral.camera.CameraHardware.isRpicamAvailable();
                boolean hardwareOk = rpicam && com.peripheral.camera.CameraHardware.isCameraPresent();
                return new int[]{
                        serviceOk ? 1 : 0,
                        hardwareOk ? 1 : 0,
                        modelOk ? 1 : 0
                };
            }

            @Override
            protected void done() {
                boolean serviceOk = false;
                boolean hardwareOk = false;
                boolean modelOk = false;
                try {
                    int[] flags = get();
                    serviceOk = flags[0] == 1;
                    hardwareOk = flags[1] == 1;
                    modelOk = flags[2] == 1;
                } catch (Exception ignored) {
                }
                boolean ok = serviceOk || hardwareOk;
                if (hardwareOk && modelOk) {
                    lbCameraStatus.setText("Câmera + IA");
                    lbCameraSummary.setText("Sony IMX500 — modelo carregado");
                    WorkflowUiTheme.setStatusColor(lbCameraSummary, WorkflowUiTheme.SUCCESS);
                } else if (hardwareOk) {
                    lbCameraStatus.setText("Câmera online");
                    lbCameraSummary.setText("Sony IMX500 — disponível");
                    WorkflowUiTheme.setStatusColor(lbCameraSummary, WorkflowUiTheme.SUCCESS);
                } else if (serviceOk && modelOk) {
                    lbCameraStatus.setText("Câmera + IA");
                    lbCameraSummary.setText("Serviço online — modelo IA pronto");
                    WorkflowUiTheme.setStatusColor(lbCameraSummary, WorkflowUiTheme.SUCCESS);
                } else if (serviceOk) {
                    lbCameraStatus.setText("Câmera online");
                    lbCameraSummary.setText("Serviço online (modelo IA pendente)");
                    WorkflowUiTheme.setStatusColor(lbCameraSummary, WorkflowUiTheme.WARNING);
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
        updateSummaryLabel(lbPrinterSummary, PeripheralSlot.PRINTER, false);
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
        btnConfigPrinter.setEnabled(!workflowRunning && !simulation);
        cbRfid.setEnabled(!workflowRunning);
        cbPhoto.setEnabled(!workflowRunning);
        cbLabel.setEnabled(!workflowRunning);
        cbSimulation.setEnabled(!workflowRunning);
        cbOrderValidation.setEnabled(!workflowRunning);
        cbAiFallback.setEnabled(!workflowRunning && cbOrderValidation.isSelected());
        cbPedidoMock.setEnabled(!workflowRunning);
        cbDemoDivergence.setEnabled(!workflowRunning && cbOrderValidation.isSelected());
        tfPedidoNumero.setEnabled(!workflowRunning);
        btnLoadPedido.setEnabled(!workflowRunning);
        spTolerancePercent.setEnabled(!workflowRunning);
        spToleranceGrams.setEnabled(!workflowRunning);
        btnRecalibrateCamera.setEnabled(!workflowRunning);

        if (cbOrderValidation.isSelected()
                && (loadedPedidos == null || loadedPedidos.isEmpty())
                && !workflowRunning) {
            btnStartWorkflow.setEnabled(false);
            btnStartWorkflow.setToolTipText("Carregue os pedidos antes de iniciar.");
            lbWorkflowStatus.setText("Carregue os pedidos para iniciar validação");
            return;
        }

        if (simulation && !workflowRunning) {
            lbWorkflowStatus.setText("Modo simulação — pronto para iniciar sem hardware");
        } else if (!scaleOk && !workflowRunning) {
            lbWorkflowStatus.setText("Configure a balança para iniciar o fluxo");
        } else if (rfidNeeded && !rfidOk && !workflowRunning) {
            lbWorkflowStatus.setText("Configure o leitor RFID ou desmarque a leitura RFID");
        } else if (!workflowRunning) {
            if (cbOrderValidation.isSelected() && loadedPedidos.size() > 1) {
                lbWorkflowStatus.setText("Pronto — fila com " + loadedPedidos.size() + " pedidos");
            } else {
                lbWorkflowStatus.setText("Pronto para iniciar o fluxo");
            }
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
        if (!simulation && cbLabel.isSelected() && !sessionManager.isConnected(PeripheralSlot.PRINTER)) {
            int choice = JOptionPane.showConfirmDialog(getDialogParent(),
                    "Impressora Zebra não conectada.\n"
                            + "A etiqueta PDF/ZPL será salva, mas não será impressa.\n\nContinuar?",
                    "Impressora",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }

        if (cbOrderValidation.isSelected() && (loadedPedidos == null || loadedPedidos.isEmpty())) {
            showWorkflowMessage("Carregue os pedidos antes de iniciar a validação.", JOptionPane.WARNING_MESSAGE);
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
        int tolGrams = ((Number) spToleranceGrams.getValue()).intValue();
        double tolKg = com.peripheral.scale.ScaleWeightFormat.toKg(Math.max(1, tolGrams));
        WorkflowConfig config = new WorkflowConfig(
                steps,
                WorkflowConfig.DEFAULT_RFID_READ_MS,
                simulation,
                cbOrderValidation.isSelected(),
                tolPercent,
                tolKg,
                cbDemoDivergence.isSelected(),
                cbAiFallback.isSelected() && cbOrderValidation.isSelected());
        closeOperationWindow();

        CameraMicroserviceClient cameraClient = CameraMicroserviceLifecycle.getInstance().getClient();
        refreshCameraStatus();

        try {
            if (cbOrderValidation.isSelected()) {
                orchestrator = new OrderAwareWorkflowOrchestrator(
                        sessionManager, loadedPedidos, cameraClient);
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
            public void onTagInventoryUpdated(java.util.List<String> detectedCodes, int expectedCount) {
                window.onTagInventoryUpdated(detectedCodes, expectedCount);
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
            public void onAwaitingTagReadingStart() {
                window.onAwaitingTagReadingStart();
            }

            public void onTagReadingInProgress() {
                window.onTagReadingInProgress();
            }

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
                        + " — peso: "
                        + com.peripheral.scale.ScaleWeightFormat.formatGramsWithUnit(record.getWeightKg())
                        + ", produtos: " + record.getTagCodes().size()));
            }

            @Override
            public void onSessionCleared() {
                window.onSessionCleared();
            }

            @Override
            public void onWaitingForNext() {
                window.onWaitingForNext();
                SwingUtilities.invokeLater(() ->
                        lbWorkflowStatus.setText("Ciclo concluído — carregando próximo automaticamente"));
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
            public void onAiAnalysisResult(boolean identified, String message, WorkflowContext context) {
                window.onAiAnalysisResult(identified, message, context);
            }

            @Override
            public void onAiAnalysisStarted(String message) {
                window.onAiAnalysisStarted(message);
                SwingUtilities.invokeLater(() -> appendLog(message != null ? message : "Analisando pedido..."));
            }

            @Override
            public void onAiAnalysisFinished() {
                window.onAiAnalysisFinished();
            }

            @Override
            public void onDivergenceOutcome(String detail, WorkflowContext context) {
                window.onDivergenceOutcome(detail, context);
                SwingUtilities.invokeLater(() -> {
                    lbWorkflowStatus.setText("Divergência detectada");
                    if (detail != null) {
                        for (String line : detail.split("\n")) {
                            if (!line.trim().isEmpty()) {
                                appendLog("Divergência: " + line.trim());
                            }
                        }
                    }
                });
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

            @Override
            public void onOrderQueueUpdated(int currentIndex, int totalOrders) {
                window.onOrderQueueUpdated(currentIndex, totalOrders);
            }

            @Override
            public void onNextPedidoStarted(Pedido completed, Pedido next, int nextIndex, int total) {
                window.onNextPedidoStarted(completed, next, nextIndex, total);
                SwingUtilities.invokeLater(() -> {
                    lbWorkflowStatus.setText("Pedido " + next.getNumero()
                            + " (" + nextIndex + "/" + total + ")");
                    appendLog("Próximo pedido: " + next.getNumero()
                            + " (" + nextIndex + "/" + total + ")");
                });
            }

            @Override
            public void onPreparingNextPedido(Pedido completed, Pedido next, int nextIndex, int total,
                                              String message) {
                window.onPreparingNextPedido(completed, next, nextIndex, total, message);
                SwingUtilities.invokeLater(() -> {
                    lbWorkflowStatus.setText("Carregando próximo pedido...");
                    appendLog(message != null ? message : "Carregando próximo pedido");
                });
            }

            @Override
            public void onDivergenceRestart(String message, WorkflowContext context) {
                window.onDivergenceRestart(message, context);
                SwingUtilities.invokeLater(() -> {
                    lbWorkflowStatus.setText("Divergência — reiniciando pedido");
                    appendLog("Divergência — reinício: "
                            + (message != null ? message : ""));
                });
            }

            @Override
            public void onAllOrdersCompleted() {
                window.onAllOrdersCompleted();
                SwingUtilities.invokeLater(() -> {
                    lbWorkflowStatus.setText("Fila concluída — reiniciando do primeiro pedido");
                    appendLog("Fila concluída — ciclo reinicia do primeiro pedido.");
                });
            }

            @Override
            public void onTareChanged(double tareKg, boolean measuring, String message) {
                window.onTareChanged(tareKg, measuring, message);
                SwingUtilities.invokeLater(() -> appendLog(message));
            }
        };
    }

    private void appendLog(String msg) {
        if (logConsumer != null) {
            logConsumer.accept(msg);
        }
    }

    private Component getDialogParent() {
        Window w = getOwnerWindow();
        return w != null ? w : this;
    }
}
