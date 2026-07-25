package com.peripheral.app;

import com.peripheral.camera.CameraHardware;
import com.peripheral.pedido.Pedido;
import com.peripheral.pedido.PedidoItem;
import com.peripheral.pedido.PedidoSerial;
import com.peripheral.pedido.PedidoVolume;
import com.peripheral.scale.ScaleWeightFormat;
import com.peripheral.workflow.PedidoValidationService;
import com.peripheral.workflow.WorkflowController;
import com.peripheral.workflow.WorkflowConfig;
import com.peripheral.workflow.WorkflowListener;
import com.peripheral.workflow.WorkflowMockData;
import com.peripheral.workflow.WorkflowMockScenario;
import com.peripheral.workflow.WorkflowReadingRecord;
import com.peripheral.workflow.WorkflowStep;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

public class WorkflowOperationWindow extends JDialog implements WorkflowListener {

    private static final int MONITOR_ROW_HEIGHT = 168;

    private final WorkflowController orchestrator;
    private final boolean photoEnabled;
    private final boolean labelEnabled;
    private final boolean simulationMode;
    private final boolean orderValidationEnabled;

    private Pedido currentPedido;
    private int currentVolumeIndex = 1;

    private Double lastRawScaleKg;
    private boolean lastRawStable;
    private String lastRawPayload;
    private double activeTareKg;
    private boolean activeTareOn;

    private final JLabel lbVolume = new JLabel("");
    private final JLabel lbLiveWeightValue =
            new JLabel(ScaleWeightFormat.PLACEHOLDER, SwingConstants.CENTER);
    private final JLabel lbLiveWeightUnit = new JLabel(ScaleWeightFormat.UNIT);
    private final JLabel lbLiveWeightStable = new JLabel("Aguardando leitura da balança", SwingConstants.CENTER);
    private final JLabel lbGrossTareInfo = new JLabel("Balança — · Tara —", SwingConstants.CENTER);
    private final JLabel lbTareBadge = new JLabel("SEM TARA", SwingConstants.CENTER);
    private final CameraLiveMonitorPanel cameraMonitor = new CameraLiveMonitorPanel();
    private final RfidTagMonitorPanel liveTagMonitor =
            new RfidTagMonitorPanel("RFID — PRODUTOS DO PEDIDO", false);
    private final JLabel lbTagProgress = new JLabel("Tags: 0", SwingConstants.LEFT);

    private final JPanel operatorReviewPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    private final ThemedButton btnRereadRfid =
            WorkflowUiTheme.button("Reler tags", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnCapturePhoto =
            WorkflowUiTheme.button("Tirar foto", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnReanalyze =
            WorkflowUiTheme.button("Re-analisar IA", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnConfirmOperator =
            WorkflowUiTheme.button("Finalizar pedido", ThemedButton.Variant.SUCCESS);

    private final ThemedButton btnTare =
            WorkflowUiTheme.button("Tarar", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnClearTare =
            WorkflowUiTheme.button("Limpar tara", ThemedButton.Variant.SECONDARY);

    private final JLabel lbStatus = new JLabel("Aguardando início do fluxo...");
    private final JPanel statusIndicator = new JPanel();
    private final JPanel historyList = new JPanel();
    private final JPanel emptyState = new JPanel();
    private final JLabel lbEmptyTitle = new JLabel("Nenhuma leitura ainda");
    private final JLabel lbEmptyHint = new JLabel("As leituras aparecerão aqui após cada ciclo.");
    private final List<WorkflowReadingCard> readingCards = new ArrayList<>();
    private JPanel historyHost;

    private final ThemedButton btnStartWeighing =
            WorkflowUiTheme.button("Iniciar pesagem", ThemedButton.Variant.PRIMARY)
                    .withSize(ThemedButton.Size.LARGE);
    private final ThemedButton btnNext =
            WorkflowUiTheme.button("Próximo", ThemedButton.Variant.SUCCESS)
                    .withSize(ThemedButton.Size.LARGE);
    private final ThemedButton btnRestartSession =
            WorkflowUiTheme.button("Reiniciar", ThemedButton.Variant.SECONDARY);
    /** Em tela cheia não há barra de título: esta é a saída visível do fluxo. */
    private final ThemedButton btnEndWorkflow =
            WorkflowUiTheme.button("Encerrar", ThemedButton.Variant.DANGER);

    private final JPanel simulationPanel = new JPanel(new GridBagLayout());
    private final JSpinner spMockWeight =
            new JSpinner(new SpinnerNumberModel(WorkflowMockData.DEFAULT_WEIGHT_KG, 0.001,
                    ScaleWeightFormat.MAX_KG, 0.001));
    private final JTextField tfMockTags = new JTextField(WorkflowMockData.DEFAULT_TAGS_TEXT, 22);
    private final JCheckBox cbFastStabilization = new JCheckBox("Estabilização rápida (~200 ms)", true);
    private final ThemedButton btnLoadSample =
            WorkflowUiTheme.button("Exemplo", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnSimulate =
            WorkflowUiTheme.button("Simular pesagem", ThemedButton.Variant.PRIMARY);

    public WorkflowOperationWindow(Window owner,
                                   WorkflowController orchestrator,
                                   WorkflowConfig config,
                                   boolean orderValidationEnabled) {
        super(owner, "Operação — Fluxo automatizado", ModalityType.MODELESS);
        this.orchestrator = orchestrator;
        this.photoEnabled = config.isEnabled(WorkflowStep.CAPTURE_PHOTO);
        this.labelEnabled = config.isEnabled(WorkflowStep.PRINT_LABEL);
        this.simulationMode = config.isSimulationMode();
        this.orderValidationEnabled = orderValidationEnabled;

        buildUi();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                cameraMonitor.startLivePreview();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                cameraMonitor.stopLivePreview();
                setVisible(false);
            }
        });
        WorkflowUiTheme.applyTouchScreenSize(this);
    }

    public void restartSession() {
        if (orchestrator == null) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Reiniciar a sessão apagará todo o histórico, fotos e etiquetas desta execução.\n"
                        + "O fluxo continuará ativo. Deseja continuar?",
                "Reiniciar sessão",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            orchestrator.restartSession();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Reiniciar sessão",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void buildUi() {
        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBackground(WorkflowUiTheme.BG_PAGE);
        content.setBorder(WorkflowUiTheme.empty(8, 10, 8, 10));

        content.add(buildHeader(), BorderLayout.NORTH);
        content.add(buildMainCenter(), BorderLayout.CENTER);
        content.add(buildFooter(), BorderLayout.SOUTH);

        getContentPane().setBackground(WorkflowUiTheme.BG_PAGE);
        setContentPane(content);
    }

    private JPanel buildHeader() {
        JPanel statusBar = new JPanel(new BorderLayout(8, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(WorkflowUiTheme.BG_CARD);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
                g2.setColor(WorkflowUiTheme.BORDER);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 10, 10));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        statusBar.setOpaque(false);
        statusBar.setBorder(WorkflowUiTheme.empty(8, 10, 8, 10));

        statusIndicator.setPreferredSize(new Dimension(4, 20));
        statusIndicator.setOpaque(true);
        statusIndicator.setBackground(WorkflowUiTheme.TEXT_MUTED);

        lbStatus.setFont(WorkflowUiTheme.fontStatus(lbStatus));
        lbStatus.setForeground(WorkflowUiTheme.TEXT_SECONDARY);

        lbVolume.setFont(WorkflowUiTheme.fontChip(lbVolume).deriveFont(Font.BOLD));
        lbVolume.setForeground(WorkflowUiTheme.TEXT_PRIMARY);

        JPanel statusText = new JPanel();
        statusText.setOpaque(false);
        statusText.setLayout(new BoxLayout(statusText, BoxLayout.Y_AXIS));
        lbStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbVolume.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusText.add(lbStatus);
        if (orderValidationEnabled) {
            statusText.add(lbVolume);
        }

        statusBar.add(statusIndicator, BorderLayout.WEST);
        statusBar.add(statusText, BorderLayout.CENTER);
        statusBar.add(BrandingAssets.createEshipLogoLabel(28), BorderLayout.EAST);
        return statusBar;
    }

    private JPanel buildScaleMonitorPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setOpaque(true);
        panel.setBackground(WorkflowUiTheme.MONITOR_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.MONITOR_BORDER, 1),
                WorkflowUiTheme.empty(8, 12, 8, 12)));

        JLabel caption = new JLabel("PESO LÍQUIDO");
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, 12f));
        caption.setForeground(WorkflowUiTheme.MONITOR_CAPTION);

        lbTareBadge.setFont(lbTareBadge.getFont().deriveFont(Font.BOLD, 10f));
        lbTareBadge.setOpaque(true);
        lbTareBadge.setBackground(WorkflowUiTheme.MONITOR_ROW_BG);
        lbTareBadge.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        lbTareBadge.setBorder(WorkflowUiTheme.empty(2, 8, 2, 8));

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(caption, BorderLayout.WEST);
        top.add(lbTareBadge, BorderLayout.EAST);

        lbLiveWeightValue.setFont(new Font(Font.MONOSPACED, Font.BOLD, 48));
        lbLiveWeightValue.setForeground(Color.WHITE);

        lbLiveWeightUnit.setFont(lbLiveWeightUnit.getFont().deriveFont(Font.BOLD, 16f));
        lbLiveWeightUnit.setForeground(WorkflowUiTheme.MONITOR_TEXT);
        lbLiveWeightUnit.setBorder(WorkflowUiTheme.empty(14, 6, 0, 0));

        lbLiveWeightStable.setFont(lbLiveWeightStable.getFont().deriveFont(Font.PLAIN, 11f));
        lbLiveWeightStable.setForeground(WorkflowUiTheme.MONITOR_CAPTION);

        lbGrossTareInfo.setFont(WorkflowUiTheme.fontMeta(lbGrossTareInfo));
        lbGrossTareInfo.setForeground(WorkflowUiTheme.MONITOR_CAPTION);

        JPanel valueRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        valueRow.setOpaque(false);
        valueRow.add(lbLiveWeightValue);
        valueRow.add(lbLiveWeightUnit);

        JPanel south = new JPanel();
        south.setOpaque(false);
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        lbLiveWeightStable.setAlignmentX(Component.CENTER_ALIGNMENT);
        lbGrossTareInfo.setAlignmentX(Component.CENTER_ALIGNMENT);
        south.add(lbLiveWeightStable);
        south.add(Box.createVerticalStrut(2));
        south.add(lbGrossTareInfo);

        panel.add(top, BorderLayout.NORTH);
        panel.add(valueRow, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildMainCenter() {
        JPanel monitors = new JPanel(new GridLayout(1, 2, 8, 0));
        monitors.setOpaque(false);
        monitors.add(buildScaleMonitorPanel());
        monitors.add(cameraMonitor);
        monitors.setPreferredSize(new Dimension(0, MONITOR_ROW_HEIGHT));

        liveTagMonitor.setHint("RFID contínuo — todos os produtos do pedido aparecem abaixo; "
                + "o código muda para DETECTADO ao identificar.");

        JTabbedPane tabs = new JTabbedPane();
        WorkflowUiTheme.styleTabbedPane(tabs);
        tabs.addTab("Produtos", liveTagMonitor);
        tabs.addTab("Histórico", buildHistoryPanel());
        if (simulationMode) {
            tabs.addTab("Simulação", new JScrollPane(buildSimulationPanel()));
        }

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setOpaque(false);
        center.add(monitors, BorderLayout.NORTH);
        center.add(tabs, BorderLayout.CENTER);
        return center;
    }

    private JPanel buildHistoryPanel() {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);

        historyList.setLayout(new BoxLayout(historyList, BoxLayout.Y_AXIS));
        historyList.setOpaque(false);

        emptyState.setLayout(new BoxLayout(emptyState, BoxLayout.Y_AXIS));
        emptyState.setOpaque(false);
        emptyState.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyState.setBorder(WorkflowUiTheme.empty(28, 16, 28, 16));

        lbEmptyTitle.setFont(lbEmptyTitle.getFont().deriveFont(Font.BOLD, 13f));
        lbEmptyTitle.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        lbEmptyTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        lbEmptyHint.setFont(WorkflowUiTheme.fontMeta(lbEmptyHint));
        lbEmptyHint.setForeground(WorkflowUiTheme.TEXT_MUTED);
        lbEmptyHint.setAlignmentX(Component.CENTER_ALIGNMENT);
        lbEmptyHint.setBorder(WorkflowUiTheme.empty(4, 0, 0, 0));

        emptyState.add(lbEmptyTitle);
        emptyState.add(lbEmptyHint);

        JScrollPane scroll = new JScrollPane(historyList);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(WorkflowUiTheme.BG_PAGE);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(20);

        historyHost = new JPanel(new CardLayout());
        historyHost.setOpaque(true);
        historyHost.setBackground(WorkflowUiTheme.BG_PAGE);
        historyHost.add(emptyState, "empty");
        historyHost.add(scroll, "list");

        updateEmptyStateVisibility();
        wrapper.add(historyHost, BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildFooter() {
        btnStartWeighing.addActionListener(e -> {
            if (orchestrator != null) {
                liveTagMonitor.clearDetections();
                liveTagMonitor.setHint("Pesagem iniciada — aproxime os produtos do leitor...");
                orchestrator.confirmWeighingStart();
            }
        });
        btnTare.addActionListener(e -> applyTareAction());
        btnClearTare.addActionListener(e -> {
            if (orchestrator != null) {
                orchestrator.clearTare();
            }
        });
        btnNext.setEnabled(false);
        btnNext.addActionListener(e -> {
            if (orchestrator != null) {
                orchestrator.acknowledgeNext();
            }
        });
        btnRestartSession.addActionListener(e -> restartSession());
        btnEndWorkflow.addActionListener(e -> confirmEndWorkflow());

        btnRereadRfid.addActionListener(e -> runOperatorAction(() -> {
            liveTagMonitor.clearDetections();
            liveTagMonitor.setHint("Detecções limpas — releitura contínua ativa...");
            orchestrator.operatorRereadRfid();
        }));
        btnCapturePhoto.addActionListener(e -> runOperatorAction(() -> orchestrator.operatorCapturePhoto()));
        btnReanalyze.addActionListener(e -> runOperatorAction(() -> orchestrator.operatorReanalyze()));
        btnConfirmOperator.addActionListener(e -> confirmOperatorVolume());

        lbTagProgress.setFont(WorkflowUiTheme.fontMeta(lbTagProgress));
        lbTagProgress.setForeground(WorkflowUiTheme.TEXT_SECONDARY);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(btnTare);
        left.add(btnClearTare);
        left.add(lbTagProgress);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(btnEndWorkflow);
        actions.add(btnRestartSession);
        actions.add(btnStartWeighing);
        actions.add(btnNext);

        JPanel southActions = new JPanel(new BorderLayout());
        southActions.setOpaque(false);
        southActions.add(left, BorderLayout.WEST);
        southActions.add(actions, BorderLayout.EAST);

        JPanel footer = new JPanel(new BorderLayout(0, 6));
        footer.setOpaque(false);
        if (orderValidationEnabled) {
            buildOperatorReviewPanel();
            footer.add(operatorReviewPanel, BorderLayout.NORTH);
        }
        footer.add(southActions, BorderLayout.SOUTH);
        return footer;
    }

    private void applyTareAction() {
        if (orchestrator == null) {
            return;
        }
        try {
            if (simulationMode) {
                double gross = ((Number) spMockWeight.getValue()).doubleValue();
                orchestrator.applyTare(gross);
            } else {
                orchestrator.applyTare();
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Tara", JOptionPane.WARNING_MESSAGE);
        }
    }

    private JPanel buildSimulationPanel() {
        simulationPanel.setOpaque(false);
        simulationPanel.setBorder(WorkflowUiTheme.empty(8, 8, 8, 8));
        WorkflowUiTheme.styleCompactSpinner(spMockWeight);
        WorkflowUiTheme.styleCompactTextField(tfMockTags, 22);
        WorkflowUiTheme.styleTouchCheckBox(cbFastStabilization);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        simulationPanel.add(WorkflowUiTheme.formLabel("Peso (kg):"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        simulationPanel.add(spMockWeight, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        simulationPanel.add(WorkflowUiTheme.formLabel("Tags:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        simulationPanel.add(tfMockTags, gbc);

        gbc.gridx = 1;
        gbc.gridy = 2;
        simulationPanel.add(cbFastStabilization, gbc);

        JLabel hint = WorkflowUiTheme.createHintLabel(
                "<html>Informe o <b>peso líquido</b> (kg) e os códigos das tags. "
                        + "Para tara: digite o peso da caixa, clique em <b>Tarar</b>, "
                        + "depois informe o líquido dos produtos.<br/>"
                        + "Ex.: <code>003509, 003511, 003907, 004077</code> · 0,466 kg</html>");
        gbc.gridy = 3;
        gbc.weightx = 1;
        simulationPanel.add(hint, gbc);

        btnLoadSample.addActionListener(e -> loadSampleData());
        btnSimulate.setEnabled(false);
        btnSimulate.addActionListener(e -> runSimulation());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(btnLoadSample);
        buttons.add(btnSimulate);
        gbc.gridy = 4;
        simulationPanel.add(buttons, gbc);

        return simulationPanel;
    }

    private void loadSampleData() {
        WorkflowMockScenario sample = WorkflowMockData.sample(cbFastStabilization.isSelected());
        spMockWeight.setValue(sample.getWeightKg());
        tfMockTags.setText(WorkflowMockData.formatTagsForDisplay(sample.getTags()));
    }

    private void runSimulation() {
        if (orchestrator == null || !simulationMode) {
            return;
        }
        double weight;
        try {
            weight = ((Number) spMockWeight.getValue()).doubleValue();
        } catch (ClassCastException ex) {
            JOptionPane.showMessageDialog(this, "Peso simulado inválido.", "Simulação",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (weight <= 0) {
            JOptionPane.showMessageDialog(this, "Informe um peso maior que zero.", "Simulação",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        WorkflowMockScenario scenario = WorkflowMockData.fromInput(
                weight, tfMockTags.getText(), cbFastStabilization.isSelected());
        orchestrator.simulateWeighing(scenario);
        btnSimulate.setEnabled(orchestrator.isOperatorReview());
    }

    private void clearHistory() {
        historyList.removeAll();
        readingCards.clear();
        updateEmptyStateVisibility();
        historyList.revalidate();
        historyList.repaint();
    }

    private void addReadingToHistory(WorkflowReadingRecord record) {
        for (WorkflowReadingCard card : readingCards) {
            card.setHighlight(false);
        }

        WorkflowReadingCard card = new WorkflowReadingCard(
                record, photoEnabled, labelEnabled, true, new WorkflowReadingCard.ActionListener() {
            @Override
            public void onViewPhoto(WorkflowReadingRecord r) {
                openPhoto(r);
            }

            @Override
            public void onViewLabel(WorkflowReadingRecord r) {
                openLabel(r);
            }

            @Override
            public void onViewDetails(WorkflowReadingRecord r) {
                openDetails(r);
            }
        });
        readingCards.add(card);
        // Mais recente no topo: a última leitura fica visível sem rolagem.
        historyList.add(card, 0);
        historyList.add(Box.createVerticalStrut(4), 1);
        updateEmptyStateVisibility();
        historyList.revalidate();
        historyList.repaint();

        SwingUtilities.invokeLater(() -> {
            JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(
                    JScrollPane.class, historyList);
            if (scroll != null) {
                scroll.getVerticalScrollBar().setValue(0);
            }
        });
    }

    private void openDetails(WorkflowReadingRecord record) {
        if (record == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Leitura #").append(record.getIndex()).append('\n');
        sb.append("Peso: ").append(ScaleWeightFormat.formatGramsWithUnit(record.getWeightKg())).append('\n');
        List<String> codes = record.getTagCodes();
        if (codes == null || codes.isEmpty()) {
            sb.append("\nNenhum produto identificado.");
        } else {
            sb.append("\nProdutos (").append(codes.size()).append("):\n");
            for (String code : codes) {
                sb.append("  • ").append(code).append('\n');
            }
        }

        JTextArea area = new JTextArea(sb.toString());
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        WorkflowUiTheme.styleTextArea(area);

        JScrollPane scroll = new JScrollPane(area);
        WorkflowUiTheme.styleScrollPane(scroll);
        Rectangle screen = WorkflowUiTheme.availableScreenBounds();
        scroll.setPreferredSize(new Dimension(
                Math.min(screen.width - 120, 420),
                Math.min(screen.height - 200, 240)));

        JOptionPane.showMessageDialog(this, scroll,
                "Detalhe da leitura", JOptionPane.PLAIN_MESSAGE);
    }

    private void updateEmptyStateVisibility() {
        if (historyHost == null) {
            return;
        }
        CardLayout layout = (CardLayout) historyHost.getLayout();
        layout.show(historyHost, readingCards.isEmpty() ? "empty" : "list");
    }

    private void openPhoto(WorkflowReadingRecord record) {
        if (record == null || !record.hasPhoto()) {
            return;
        }
        WorkflowPhotoPreviewDialog.showPreview(this, record.getPhotoPath());
    }

    private void openLabel(WorkflowReadingRecord record) {
        if (record == null || !record.hasLabel()) {
            return;
        }
        WorkflowLabelPreviewDialog.showPreview(this, record.getLabelPdfPath());
    }

    private void setStatus(String text, Color indicatorColor, Color textColor) {
        lbStatus.setText(text);
        lbStatus.setForeground(textColor);
        statusIndicator.setBackground(indicatorColor);
    }

    private void setAwaitingStartState() {
        btnStartWeighing.setEnabled(true);
        btnNext.setEnabled(false);
        btnRestartSession.setEnabled(true);
        btnTare.setEnabled(true);
        btnClearTare.setEnabled(true);
        setOperatorReviewVisible(false);
        cameraMonitor.ensureLivePreview();
        if (simulationMode) {
            btnSimulate.setEnabled(false);
            setStatus("Opcional: tarar a caixa → Iniciar pesagem → Simular.",
                    WorkflowUiTheme.TEXT_MUTED, WorkflowUiTheme.TEXT_SECONDARY);
        } else {
            setStatus("Opcional: coloque a caixa e tarar → Iniciar pesagem.",
                    WorkflowUiTheme.TEXT_MUTED, WorkflowUiTheme.TEXT_SECONDARY);
        }
    }

    private void setWaitingForNextState() {
        btnStartWeighing.setEnabled(false);
        btnNext.setEnabled(true);
        btnRestartSession.setEnabled(true);
        cameraMonitor.ensureLivePreview();
        setStatus("Ciclo concluído — toque em Próximo para nova leitura.",
                WorkflowUiTheme.SUCCESS, WorkflowUiTheme.SUCCESS);
    }

    private void buildOperatorReviewPanel() {
        operatorReviewPanel.setOpaque(true);
        operatorReviewPanel.setBackground(WorkflowUiTheme.BG_CARD);
        operatorReviewPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.WARNING),
                WorkflowUiTheme.empty(4, 8, 4, 8)));
        operatorReviewPanel.add(btnRereadRfid);
        operatorReviewPanel.add(btnCapturePhoto);
        operatorReviewPanel.add(btnReanalyze);
        operatorReviewPanel.add(btnConfirmOperator);
        setOperatorReviewVisible(false);
    }

    private void setOperatorReviewVisible(boolean visible) {
        operatorReviewPanel.setVisible(visible);
        btnRereadRfid.setEnabled(visible);
        btnCapturePhoto.setEnabled(visible);
        btnReanalyze.setEnabled(visible);
        btnConfirmOperator.setEnabled(visible);
    }

    private interface OperatorAction {
        void run() throws Exception;
    }

    private void runOperatorAction(OperatorAction action) {
        if (orchestrator == null) {
            return;
        }
        try {
            action.run();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Operação", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void confirmEndWorkflow() {
        if (orchestrator == null) {
            setVisible(false);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Encerrar o fluxo e voltar para a tela de configuração?",
                "Encerrar fluxo",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        orchestrator.stop();
    }

    private void confirmOperatorVolume() {
        if (orchestrator == null || !orchestrator.isOperatorReview()) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Confirmo que o pedido está OK e pode ser finalizado.",
                "Finalizar pedido",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        runOperatorAction(() -> orchestrator.operatorConfirmVolume());
    }

    public void onOrderLoaded(Pedido pedido) {
        SwingUtilities.invokeLater(() -> {
            currentPedido = pedido;
            if (pedido != null) {
                refreshExpectedProducts();
                int produtos = countExpectedProducts();
                lbVolume.setText("Pedido " + pedido.getNumero()
                        + " — " + produtos + " produto(s)");
            }
        });
    }

    public void onVolumeChanged(int currentIndex, int totalVolumes) {
        SwingUtilities.invokeLater(() -> {
            currentVolumeIndex = Math.max(1, currentIndex);
            refreshExpectedProducts();
            if (totalVolumes <= 1) {
                int produtos = countExpectedProducts();
                if (currentPedido != null) {
                    lbVolume.setText("Pedido " + currentPedido.getNumero()
                            + " — " + produtos + " produto(s)");
                } else {
                    lbVolume.setText("Validação do pedido");
                }
                setStatus("Aguardando início da pesagem...",
                        WorkflowUiTheme.TEXT_MUTED, WorkflowUiTheme.TEXT_SECONDARY);
            } else {
                lbVolume.setText("Volume " + currentIndex + " de " + totalVolumes);
                setStatus("Aguardando início do volume " + currentIndex + "...",
                        WorkflowUiTheme.TEXT_MUTED, WorkflowUiTheme.TEXT_SECONDARY);
            }
        });
    }

    private void refreshExpectedProducts() {
        List<RfidTagMonitorPanel.ProductEntry> entries = buildExpectedProductEntries();
        if (!entries.isEmpty()) {
            liveTagMonitor.setExpectedProducts(entries);
            liveTagMonitor.setHint("Produtos do pedido — o código identificado aparece como DETECTADO.");
        }
    }

    private List<RfidTagMonitorPanel.ProductEntry> buildExpectedProductEntries() {
        List<RfidTagMonitorPanel.ProductEntry> entries = new ArrayList<>();
        PedidoVolume volume = resolveCurrentVolume();
        if (volume == null) {
            return entries;
        }
        for (PedidoItem item : volume.getItens()) {
            if (item == null) {
                continue;
            }
            String name = item.getNome() != null ? item.getNome() : "";
            if (item.hasSeriais()) {
                int i = 0;
                for (PedidoSerial serial : item.getSeriais()) {
                    String code = serial.getSerial();
                    if (code == null || code.isEmpty()) {
                        code = serial.getEpc();
                    }
                    if (code != null && !code.isEmpty()) {
                        String rowId = code + "#" + (i++);
                        entries.add(new RfidTagMonitorPanel.ProductEntry(rowId, code, name));
                    }
                }
            } else {
                String code = item.getCodigoProduto();
                if (code != null && !code.isEmpty()) {
                    int qty = Math.max(1, item.getQuantidadeEsperada());
                    for (int i = 0; i < qty; i++) {
                        String label = qty > 1 ? (name + " (" + (i + 1) + "/" + qty + ")") : name;
                        String rowId = code + "#" + i;
                        entries.add(new RfidTagMonitorPanel.ProductEntry(rowId, code, label));
                    }
                }
            }
        }
        return entries;
    }

    private PedidoVolume resolveCurrentVolume() {
        if (currentPedido == null || currentPedido.getVolumeCount() <= 0) {
            return null;
        }
        int idx0 = Math.max(0, currentVolumeIndex - 1);
        if (idx0 >= currentPedido.getVolumeCount()) {
            idx0 = 0;
        }
        return currentPedido.getVolume(idx0);
    }

    private int countExpectedProducts() {
        return buildExpectedProductEntries().size();
    }

    public void onValidationResult(PedidoValidationService.ValidationResult result) {
        SwingUtilities.invokeLater(() -> {
            if (result == null) {
                return;
            }
            Color color = result.isValid() ? WorkflowUiTheme.SUCCESS : WorkflowUiTheme.DANGER;
            setStatus(result.getSummaryMessage(), color, color);
        });
    }

    public void onOperatorReviewRequired(String message, com.peripheral.workflow.WorkflowContext context) {
        SwingUtilities.invokeLater(() -> {
            setOperatorReviewVisible(true);
            btnStartWeighing.setEnabled(false);
            btnNext.setEnabled(false);
            btnTare.setEnabled(false);
            btnClearTare.setEnabled(false);
            if (simulationMode) {
                btnSimulate.setEnabled(true);
            }
            cameraMonitor.ensureLivePreview();
            setStatus(message, WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);
            if (context != null && context.getAiMessage() != null && !context.getAiMessage().isEmpty()) {
                lbStatus.setText(message + " | IA: " + context.getAiMessage());
            }
        });
    }

    public void onCameraServiceStatus(boolean available, String detail) {
        SwingUtilities.invokeLater(() -> {
            if (available) {
                cameraMonitor.ensureLivePreview();
            } else {
                setStatus("Câmera indisponível — fluxo continua; foto usará rpicam se possível.",
                        WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);
            }
        });
    }

    public void onOrderCompleted(Pedido pedido) {
        SwingUtilities.invokeLater(() -> {
            setOperatorReviewVisible(false);
            String numero = pedido != null ? pedido.getNumero() : "";
            setStatus("Pedido " + numero + " concluído.", WorkflowUiTheme.SUCCESS, WorkflowUiTheme.SUCCESS);
        });
    }

    /**
     * Fonte de verdade do display: o mesmo valor cru do evento da balança usado
     * na tela de configuração. Evita divergência entre as duas telas.
     */
    @Override
    public void onWeightUpdate(com.peripheral.core.PeripheralDataEvent event) {
        SwingUtilities.invokeLater(() -> {
            if (event == null) {
                return;
            }
            Double kg = ScaleWeightFormat.parseKg(event.getWeight());
            if (kg == null) {
                return;
            }
            lastRawScaleKg = kg;
            lastRawStable = Boolean.TRUE.equals(event.getStable());
            lastRawPayload = event.getRawPayload();
            renderWeightFromRaw();
        });
    }

    @Override
    public void onScaleReading(double grossKg, double netKg, double tareKg,
                               boolean tareActive, boolean stable) {
        SwingUtilities.invokeLater(() -> {
            activeTareKg = tareKg;
            activeTareOn = tareActive;
            if (lastRawScaleKg == null) {
                lastRawScaleKg = grossKg;
            }
            lastRawStable = stable;
            renderWeightFromRaw();
        });
    }

    /** Aplica a tara lógica sobre o valor cru, sem recalcular o peso em outro lugar. */
    private void renderWeightFromRaw() {
        double raw = lastRawScaleKg != null ? lastRawScaleKg : 0;
        double net = activeTareOn ? (raw - activeTareKg) : raw;
        updateWeightDisplay(raw, net, activeTareKg, activeTareOn, lastRawStable);
    }

    @Override
    public void onTareChanged(double tareKg, boolean active) {
        SwingUtilities.invokeLater(() -> {
            if (active) {
                lbTareBadge.setText("TARA ATIVA");
                lbTareBadge.setForeground(WorkflowUiTheme.MONITOR_VALUE);
            } else {
                lbTareBadge.setText("SEM TARA");
                lbTareBadge.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
            }
            btnClearTare.setEnabled(active || simulationMode);
        });
    }

    private void updateWeightDisplay(double grossKg, double netKg, double tareKg,
                                     boolean tareActive, boolean stable) {
        boolean overload = ScaleWeightFormat.isOverload(grossKg);
        lbLiveWeightValue.setText(ScaleWeightFormat.formatGrams(netKg));
        lbLiveWeightUnit.setText(ScaleWeightFormat.UNIT);
        lbLiveWeightValue.setForeground(stable ? WorkflowUiTheme.MONITOR_VALUE : Color.WHITE);
        if (overload) {
            lbLiveWeightStable.setText("!  ACIMA DE "
                    + ScaleWeightFormat.MAX_GRAMS + " " + ScaleWeightFormat.UNIT);
            lbLiveWeightStable.setForeground(WorkflowUiTheme.MONITOR_ALERT);
        } else {
            lbLiveWeightStable.setText(stable ? "●  PESO ESTÁVEL" : "○  PESO INSTÁVEL — aguarde");
            lbLiveWeightStable.setForeground(stable
                    ? WorkflowUiTheme.MONITOR_VALUE
                    : WorkflowUiTheme.MONITOR_ALERT);
        }
        lbGrossTareInfo.setText(String.format("Balança %s · Tara %s",
                ScaleWeightFormat.formatGramsWithUnit(grossKg),
                tareActive
                        ? ScaleWeightFormat.formatGramsWithUnit(tareKg)
                        : "—"));
        if (lastRawPayload != null && !lastRawPayload.isEmpty()) {
            lbGrossTareInfo.setToolTipText("Linha da balança: " + lastRawPayload);
            lbLiveWeightValue.setToolTipText("Linha da balança: " + lastRawPayload);
        }
        btnTare.setEnabled(stable || simulationMode);
    }

    @Override
    public void onTagRead(com.peripheral.core.PeripheralDataEvent event) {
        SwingUtilities.invokeLater(() -> {
            if (event == null) {
                return;
            }
            String code = event.getCode();
            if (code == null || code.isEmpty()) {
                code = event.getEpc();
            }
            if (code != null && !code.isEmpty()) {
                liveTagMonitor.registerTag(code);
            }
        });
    }

    @Override
    public void onTagInventoryUpdated(java.util.List<String> detectedCodes, int expectedCount) {
        SwingUtilities.invokeLater(() -> {
            int detected = detectedCodes != null ? detectedCodes.size() : 0;
            if (expectedCount > 0) {
                lbTagProgress.setText("Códigos: " + detected + " / " + expectedCount);
            } else {
                lbTagProgress.setText("Códigos: " + detected);
            }
            if (detectedCodes != null) {
                liveTagMonitor.syncDetectedCodes(detectedCodes);
            }
        });
    }

    @Override
    public void onStepChanged(WorkflowStep step, String message) {
        SwingUtilities.invokeLater(() -> {
            btnStartWeighing.setEnabled(false);
            btnNext.setEnabled(false);
            if (step == WorkflowStep.WEIGHING || step == WorkflowStep.RFID_READ) {
                btnTare.setEnabled(false);
            }
            setStatus(message, WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);

            if (simulationMode && (step == WorkflowStep.WEIGHING || step == WorkflowStep.RFID_READ)) {
                btnSimulate.setEnabled(true);
            }
            if (step != WorkflowStep.CAPTURE_PHOTO) {
                cameraMonitor.ensureLivePreview();
            }
        });
    }

    @Override
    public void onAwaitingWeighingStart() {
        SwingUtilities.invokeLater(this::setAwaitingStartState);
    }

    @Override
    public void onStabilizationProgress(String message) {
        SwingUtilities.invokeLater(() -> {
            btnStartWeighing.setEnabled(false);
            btnNext.setEnabled(false);
            btnTare.setEnabled(false);
            btnSimulate.setEnabled(false);
            setStatus(message, WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);
        });
    }

    @Override
    public void onCycleCompleted(com.peripheral.workflow.WorkflowContext context) {
    }

    @Override
    public void onReadingRecorded(WorkflowReadingRecord record) {
        SwingUtilities.invokeLater(() -> {
            addReadingToHistory(record);
            cameraMonitor.ensureLivePreview();
        });
    }

    @Override
    public void onSessionCleared() {
        SwingUtilities.invokeLater(() -> {
            clearHistory();
            liveTagMonitor.reset();
            refreshExpectedProducts();
            liveTagMonitor.setHint("Produtos do pedido — o código identificado aparece como DETECTADO.");
            lbTagProgress.setText("Códigos: 0");
            lbTareBadge.setText("SEM TARA");
            lbTareBadge.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
            lbGrossTareInfo.setText("Balança — · Tara —");
            activeTareKg = 0;
            activeTareOn = false;
            cameraMonitor.ensureLivePreview();
        });
    }

    @Override
    public void onWaitingForNext() {
        SwingUtilities.invokeLater(this::setWaitingForNextState);
    }

    @Override
    public void onError(String message, Throwable cause) {
        SwingUtilities.invokeLater(() -> {
            setStatus("Erro: " + message, WorkflowUiTheme.DANGER, WorkflowUiTheme.DANGER);
            btnStartWeighing.setEnabled(true);
            btnNext.setEnabled(false);
            btnTare.setEnabled(true);
            btnClearTare.setEnabled(true);
            btnRestartSession.setEnabled(true);
            cameraMonitor.ensureLivePreview();
        });
    }

    @Override
    public void onStopped() {
        SwingUtilities.invokeLater(() -> {
            clearHistory();
            cameraMonitor.stopLivePreview();
            btnStartWeighing.setEnabled(false);
            btnNext.setEnabled(false);
            btnRestartSession.setEnabled(false);
            setStatus("Fluxo parado.", WorkflowUiTheme.TEXT_MUTED, WorkflowUiTheme.TEXT_SECONDARY);
            dispose();
        });
    }

    @Override
    public void dispose() {
        cameraMonitor.stopLivePreview();
        CameraHardware.stopPreview();
        super.dispose();
    }
}
