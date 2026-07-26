package com.peripheral.app;

import com.peripheral.camera.CameraHardware;
import com.peripheral.pedido.Pedido;
import com.peripheral.scale.DigitronDgnParser;
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
    private final boolean aiFallbackEnabled;
    private final boolean rfidEnabled;
    /** Só exibe peso real na fase de pesagem (RF desligado). */
    private volatile boolean weightLiveEnabled;

    private Pedido currentPedido;
    private int currentVolumeIndex = 1;

    private String lastRawPayload;

    private final JLabel lbVolume = new JLabel("");
    private final JLabel lbLiveWeightValue =
            new JLabel(ScaleWeightFormat.PLACEHOLDER, SwingConstants.CENTER);
    private final JLabel lbLiveWeightUnit = new JLabel(ScaleWeightFormat.UNIT);
    private final JLabel lbLiveWeightStable = new JLabel("Aguardando leitura da balança", SwingConstants.CENTER);
    private final JLabel lbScaleRawLine = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel lbTareInfo = new JLabel("Tara: —", SwingConstants.CENTER);
    private final ThemedButton btnCaptureTare =
            WorkflowUiTheme.button("Definir tara", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnClearTare =
            WorkflowUiTheme.button("Limpar tara", ThemedButton.Variant.SECONDARY);
    private final CameraLiveMonitorPanel cameraMonitor = new CameraLiveMonitorPanel();
    private final RfidTagMonitorPanel liveTagMonitor =
            new RfidTagMonitorPanel("TAGS LIDAS", false);
    private final ThemedButton btnClearTags =
            WorkflowUiTheme.button("Limpar tags", ThemedButton.Variant.SECONDARY);

    private final JPanel operatorReviewPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    private final ThemedButton btnRereadRfid =
            WorkflowUiTheme.button("Reler tags", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnCapturePhoto =
            WorkflowUiTheme.button("Tirar foto", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnReanalyze =
            WorkflowUiTheme.button("Re-analisar IA", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnConfirmOperator =
            WorkflowUiTheme.button("Revalidar e finalizar", ThemedButton.Variant.SUCCESS);

    private JPanel monitorsRow;
    private JTabbedPane contentTabs;
    private boolean divergenceLayoutActive;

    private final JLabel lbStatus = new JLabel("Aguardando início do fluxo...");
    private final JPanel statusIndicator = new JPanel();
    private final JPanel historyList = new JPanel();
    private final JPanel emptyState = new JPanel();
    private final JLabel lbEmptyTitle = new JLabel("Nenhuma leitura ainda");
    private final JLabel lbEmptyHint = new JLabel("As leituras aparecerão aqui após cada ciclo.");
    private final List<WorkflowReadingCard> readingCards = new ArrayList<>();
    private JPanel historyHost;

    private final ThemedButton btnStartTags =
            WorkflowUiTheme.button("Iniciar leitura tags", ThemedButton.Variant.PRIMARY)
                    .withSize(ThemedButton.Size.LARGE);
    private final ThemedButton btnStartWeighing =
            WorkflowUiTheme.button("Iniciar leitura peso", ThemedButton.Variant.PRIMARY)
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
        this.aiFallbackEnabled = config.isAiFallbackEnabled();
        this.rfidEnabled = config.isEnabled(WorkflowStep.RFID_READ);

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

        lbLiveWeightValue.setFont(new Font(Font.MONOSPACED, Font.BOLD, 48));
        lbLiveWeightValue.setForeground(Color.WHITE);

        lbLiveWeightUnit.setFont(lbLiveWeightUnit.getFont().deriveFont(Font.BOLD, 16f));
        lbLiveWeightUnit.setForeground(WorkflowUiTheme.MONITOR_TEXT);
        lbLiveWeightUnit.setBorder(WorkflowUiTheme.empty(14, 6, 0, 0));

        lbLiveWeightStable.setFont(lbLiveWeightStable.getFont().deriveFont(Font.PLAIN, 11f));
        lbLiveWeightStable.setForeground(WorkflowUiTheme.MONITOR_CAPTION);

        lbScaleRawLine.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        lbScaleRawLine.setForeground(WorkflowUiTheme.MONITOR_CAPTION);

        lbTareInfo.setFont(lbTareInfo.getFont().deriveFont(Font.PLAIN, 11f));
        lbTareInfo.setForeground(WorkflowUiTheme.MONITOR_VALUE);
        refreshTareLabel();

        btnCaptureTare.withSize(ThemedButton.Size.SMALL);
        btnClearTare.withSize(ThemedButton.Size.SMALL);
        btnCaptureTare.setToolTipText(
                "Desliga o RFID, mede a caixa vazia e volta para a etapa atual do fluxo.");
        btnCaptureTare.addActionListener(e -> runOperatorAction(() -> orchestrator.captureTare()));
        btnClearTare.addActionListener(e -> {
            if (orchestrator != null) {
                orchestrator.clearTare();
                refreshTareLabel();
                setStatus("Tara limpa — próximo pedido começa sem caixa.",
                        WorkflowUiTheme.TEXT_MUTED, WorkflowUiTheme.TEXT_SECONDARY);
            }
        });

        JPanel valueRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        valueRow.setOpaque(false);
        valueRow.add(lbLiveWeightValue);
        valueRow.add(lbLiveWeightUnit);

        JPanel tareRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        tareRow.setOpaque(false);
        tareRow.add(btnCaptureTare);
        tareRow.add(btnClearTare);

        JPanel south = new JPanel();
        south.setOpaque(false);
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        lbLiveWeightStable.setAlignmentX(Component.CENTER_ALIGNMENT);
        lbTareInfo.setAlignmentX(Component.CENTER_ALIGNMENT);
        lbScaleRawLine.setAlignmentX(Component.CENTER_ALIGNMENT);
        tareRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        south.add(lbLiveWeightStable);
        south.add(Box.createVerticalStrut(2));
        south.add(lbTareInfo);
        south.add(Box.createVerticalStrut(4));
        south.add(tareRow);
        south.add(Box.createVerticalStrut(2));
        south.add(lbScaleRawLine);

        panel.add(caption, BorderLayout.NORTH);
        panel.add(valueRow, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private void refreshTareLabel() {
        double tare = orchestrator != null ? orchestrator.getTareKg() : 0;
        if (tare <= 0.0005) {
            lbTareInfo.setText("Tara: — (sem caixa)");
        } else {
            lbTareInfo.setText("Tara: " + ScaleWeightFormat.formatGramsPlain(tare));
        }
        lbTareInfo.setForeground(WorkflowUiTheme.MONITOR_VALUE);
    }

    public void onTareChanged(double tareKg, boolean measuring, String message) {
        SwingUtilities.invokeLater(() -> {
            btnCaptureTare.setEnabled(!measuring);
            btnClearTare.setEnabled(!measuring);
            if (measuring) {
                lbTareInfo.setText("Tara: medindo... (RFID desligado)");
                lbTareInfo.setForeground(WorkflowUiTheme.WARNING);
                setStatus(message, WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);
                return;
            }
            refreshTareLabel();
            Color color = tareKg > 0.0005 ? WorkflowUiTheme.SUCCESS : WorkflowUiTheme.TEXT_SECONDARY;
            setStatus(message, color, color);
        });
    }

    private JPanel buildMainCenter() {
        monitorsRow = new JPanel(new GridLayout(1, 2, 8, 0));
        monitorsRow.setOpaque(false);
        monitorsRow.add(buildScaleMonitorPanel());
        monitorsRow.add(cameraMonitor);
        monitorsRow.setPreferredSize(new Dimension(0, MONITOR_ROW_HEIGHT));

        liveTagMonitor.setHint("Aguardando iniciar leitura de tags...");

        contentTabs = new JTabbedPane();
        WorkflowUiTheme.styleTabbedPane(contentTabs);
        contentTabs.addTab("Produtos", liveTagMonitor);
        contentTabs.addTab("Histórico", buildHistoryPanel());
        if (simulationMode) {
            contentTabs.addTab("Simulação", new JScrollPane(buildSimulationPanel()));
        }

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setOpaque(false);
        center.add(monitorsRow, BorderLayout.NORTH);
        center.add(contentTabs, BorderLayout.CENTER);
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
        // RFID inicia automaticamente ao abrir a janela — sem botão de tags.
        btnStartTags.setVisible(false);
        btnStartTags.setEnabled(false);
        btnStartWeighing.addActionListener(e -> {
            if (orchestrator != null) {
                liveTagMonitor.setHint("Pesagem iniciada — RFID parado para medir o peso.");
                orchestrator.confirmWeighingStart();
            }
        });
        btnRestartSession.addActionListener(e -> restartSession());
        btnEndWorkflow.addActionListener(e -> confirmEndWorkflow());

        btnRereadRfid.addActionListener(e -> runOperatorAction(() -> {
            liveTagMonitor.clearDetections();
            liveTagMonitor.setHint("Detecções limpas — releitura RFID ativa...");
            orchestrator.operatorRereadRfid();
        }));
        btnCapturePhoto.addActionListener(e -> runOperatorAction(() -> orchestrator.operatorCapturePhoto()));
        btnReanalyze.addActionListener(e -> runOperatorAction(() -> orchestrator.operatorReanalyze()));
        btnConfirmOperator.setToolTipText(
                "Só finaliza se as tags e o peso atuais conferirem com o pedido.");
        btnConfirmOperator.addActionListener(e -> confirmOperatorVolume());

        btnClearTags.withSize(ThemedButton.Size.SMALL);
        btnClearTags.setToolTipText("Apaga as tags lidas e permite ler de novo.");
        btnClearTags.addActionListener(e -> runOperatorAction(() -> {
            liveTagMonitor.clearDetections();
            liveTagMonitor.setHint("Tags limpas — aproxime os produtos novamente...");
            orchestrator.clearReadTags();
        }));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(btnClearTags);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(btnEndWorkflow);
        actions.add(btnRestartSession);
        actions.add(btnStartWeighing);

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
                "<html>Informe o <b>peso</b> (kg) e os códigos das tags.<br/>"
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

    private void setAwaitingTagReadingState() {
        // Fallback legado — RFID agora inicia sozinho; mostra estado de leitura.
        setTagReadingInProgressState();
    }

    private void setTagReadingInProgressState() {
        setWeightLiveEnabled(false);
        btnStartTags.setEnabled(false);
        btnStartWeighing.setEnabled(true);
        btnRestartSession.setEnabled(true);
        setOperatorReviewVisible(false);
        cameraMonitor.ensureLivePreview();
        liveTagMonitor.reset();
        liveTagMonitor.setHint("Lendo tags — aproxime os produtos...");
        if (simulationMode) {
            btnSimulate.setEnabled(true);
            setStatus("Lendo tags automaticamente — Simular para injetar códigos, depois Iniciar leitura peso.",
                    WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);
        } else {
            setStatus("Lendo tags automaticamente — quando terminar, toque em Iniciar leitura peso.",
                    WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);
        }
    }

    private void setAwaitingStartState() {
        // Ainda não pesando: peso fica em 0 até clicar em Iniciar leitura peso.
        setWeightLiveEnabled(false);
        btnStartTags.setEnabled(false);
        btnStartWeighing.setEnabled(true);
        btnRestartSession.setEnabled(true);
        setOperatorReviewVisible(false);
        cameraMonitor.ensureLivePreview();
        if (simulationMode) {
            btnSimulate.setEnabled(false);
            setStatus("Toque em Iniciar leitura peso e depois em Simular.",
                    WorkflowUiTheme.TEXT_MUTED, WorkflowUiTheme.TEXT_SECONDARY);
        } else {
            setStatus(rfidEnabled
                    ? "Toque em Iniciar leitura peso para medir (RFID já está lendo)."
                    : "Toque em Iniciar leitura peso para medir.",
                    WorkflowUiTheme.TEXT_MUTED, WorkflowUiTheme.TEXT_SECONDARY);
        }
    }

    private void setWaitingForNextState() {
        setWeightLiveEnabled(false);
        btnStartTags.setEnabled(false);
        btnStartWeighing.setEnabled(false);
        btnRestartSession.setEnabled(true);
        cameraMonitor.ensureLivePreview();
        setStatus("Ciclo concluído — iniciando próxima leitura...",
                WorkflowUiTheme.SUCCESS, WorkflowUiTheme.SUCCESS);
        // Sem botão Próximo: avança sozinho (fluxo só de pesagem).
        if (orchestrator != null) {
            orchestrator.acknowledgeNext();
        }
    }

    private void setWeightLiveEnabled(boolean enabled) {
        weightLiveEnabled = enabled;
        if (!enabled) {
            showZeroWeight();
        }
    }

    private void showZeroWeight() {
        lbLiveWeightValue.setText(ScaleWeightFormat.formatGrams(0));
        lbLiveWeightUnit.setText(ScaleWeightFormat.UNIT);
        lbLiveWeightValue.setForeground(Color.WHITE);
        lbLiveWeightStable.setText("Peso oculto — aguardando fase de pesagem");
        lbLiveWeightStable.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        lbScaleRawLine.setText(" ");
        lbLiveWeightValue.setToolTipText("O peso real só aparece após Iniciar leitura peso");
    }

    private void buildOperatorReviewPanel() {
        operatorReviewPanel.setOpaque(true);
        operatorReviewPanel.setBackground(WorkflowUiTheme.BG_CARD);
        operatorReviewPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.WARNING),
                WorkflowUiTheme.empty(4, 8, 4, 8)));
        operatorReviewPanel.add(btnRereadRfid);
        operatorReviewPanel.add(btnCapturePhoto);
        // Botão de IA só existe quando o fallback de vídeo está habilitado.
        if (aiFallbackEnabled) {
            operatorReviewPanel.add(btnReanalyze);
        }
        operatorReviewPanel.add(btnConfirmOperator);
        setOperatorReviewVisible(false);
    }

    private void setOperatorReviewVisible(boolean visible) {
        operatorReviewPanel.setVisible(visible);
        btnRereadRfid.setEnabled(visible);
        btnCapturePhoto.setEnabled(visible);
        btnReanalyze.setEnabled(visible && aiFallbackEnabled);
        btnConfirmOperator.setEnabled(visible);
        if (!visible) {
            setDivergenceLayout(false);
        }
    }

    /**
     * Em divergência: encolhe balança/câmera e amplia a grade de tags
     * para o operador conseguir ler os códigos com clareza.
     */
    private void setDivergenceLayout(boolean active) {
        if (divergenceLayoutActive == active) {
            if (active && contentTabs != null) {
                contentTabs.setSelectedIndex(0);
            }
            return;
        }
        divergenceLayoutActive = active;
        liveTagMonitor.setEmphasisMode(active);
        if (monitorsRow != null) {
            monitorsRow.setPreferredSize(new Dimension(0,
                    active ? Math.max(96, MONITOR_ROW_HEIGHT / 2) : MONITOR_ROW_HEIGHT));
            monitorsRow.revalidate();
        }
        if (contentTabs != null) {
            contentTabs.setSelectedIndex(0);
            contentTabs.revalidate();
        }
        liveTagMonitor.setHint(active
                ? "Divergência — confira as tags lidas abaixo (layout ampliado)"
                : liveTagMonitor.getUniqueTagCount() > 0
                ? "Tags detectadas"
                : "Aguardando leitura das tags...");
        revalidate();
        repaint();
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
                "Revalidar tags e peso atuais?\n\n"
                        + "O RFID será desligado para a balança ler o peso sem interferência.\n"
                        + "Só finaliza se AMBOS estiverem corretos.\n"
                        + "Se o peso ou as tags ainda divergirem, o pedido NÃO avança.",
                "Revalidar e finalizar",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        runOperatorAction(() -> {
            orchestrator.operatorConfirmVolume();
            refreshTareLabel();
        });
    }

    public void onOrderLoaded(Pedido pedido) {
        SwingUtilities.invokeLater(() -> {
            currentPedido = pedido;
            if (pedido != null) {
                lbVolume.setText("Pedido " + pedido.getNumero()
                        + " — validação interna após tags + peso");
            }
        });
    }

    public void onVolumeChanged(int currentIndex, int totalVolumes) {
        SwingUtilities.invokeLater(() -> {
            currentVolumeIndex = Math.max(1, currentIndex);
            if (totalVolumes <= 1) {
                if (currentPedido != null) {
                    lbVolume.setText("Pedido " + currentPedido.getNumero()
                            + " — validação interna após tags + peso");
                } else {
                    lbVolume.setText("Validação do pedido");
                }
            } else {
                lbVolume.setText("Volume " + currentIndex + " de " + totalVolumes
                        + (currentPedido != null ? " — Pedido " + currentPedido.getNumero() : ""));
            }
        });
    }

    public void onValidationResult(PedidoValidationService.ValidationResult result) {
        SwingUtilities.invokeLater(() -> {
            if (result == null) {
                return;
            }
            boolean ok = result.isValid();
            Color color = ok ? WorkflowUiTheme.SUCCESS : WorkflowUiTheme.DANGER;
            String summary = result.getSummaryMessage();
            setStatus(ok ? "SUCESSO — " + summary : "DIVERGÊNCIA — " + summary, color, color);

            String title = ok
                    ? "Volume concluído com sucesso"
                    : "Divergência após pesagem";
            StringBuilder detail = new StringBuilder();
            detail.append(summary);
            if (result.getExpectedWeightKg() > 0 || result.getActualWeightKg() > 0) {
                detail.append("\n\nPeso: ")
                        .append(ScaleWeightFormat.formatGramsPlain(result.getActualWeightKg()))
                        .append(" (esperado ")
                        .append(ScaleWeightFormat.formatGramsPlain(result.getExpectedWeightKg()))
                        .append(")");
            }
            if (!ok && result.getUnknownSerials() != null && !result.getUnknownSerials().isEmpty()) {
                detail.append("\nSeriais fora do pedido: ")
                        .append(String.join(", ", result.getUnknownSerials()));
            }
            WorkflowUiTheme.showValidationOutcome(this, ok, title, detail.toString());
        });
    }

    @Override
    public void onAiAnalysisResult(boolean identified, String message,
                                   com.peripheral.workflow.WorkflowContext context) {
        SwingUtilities.invokeLater(() -> {
            Color color = identified ? WorkflowUiTheme.SUCCESS : WorkflowUiTheme.DANGER;
            String prefix = identified ? "IA: produtos identificados" : "IA: divergência";
            setStatus(prefix + " — " + (message != null ? message : ""), color, color);

            StringBuilder detail = new StringBuilder();
            if (message != null && !message.trim().isEmpty()) {
                detail.append(message.trim());
            }
            if (context != null && context.getMissingProducts() != null
                    && !context.getMissingProducts().isEmpty()) {
                detail.append("\n\nNão identificados: ")
                        .append(String.join(", ", context.getMissingProducts()));
            }
            if (context != null && context.getUnexpectedProducts() != null
                    && !context.getUnexpectedProducts().isEmpty()) {
                detail.append("\n\nFora do pedido: ")
                        .append(String.join(", ", context.getUnexpectedProducts()));
            }
            String title = identified
                    ? "IA: produtos confirmados"
                    : "IA: revise manualmente";
            WorkflowUiTheme.showValidationOutcome(this, identified, title, detail.toString());
        });
    }

    public void onOperatorReviewRequired(String message, com.peripheral.workflow.WorkflowContext context) {
        SwingUtilities.invokeLater(() -> {
            setOperatorReviewVisible(true);
            setDivergenceLayout(true);
            setWeightLiveEnabled(true);
            btnStartTags.setEnabled(false);
            btnStartWeighing.setEnabled(false);
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
            } else if (aiFallbackEnabled) {
                setStatus("Câmera indisponível — IA de fallback pode não funcionar; revise manualmente.",
                        WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);
            }
        });
    }

    public void onOrderCompleted(Pedido pedido) {
        SwingUtilities.invokeLater(() -> {
            // Um pedido da fila terminou — se houver próximo, onNextPedidoStarted reativa o fluxo.
            String numero = pedido != null ? pedido.getNumero() : "";
            setStatus("Pedido " + numero + " concluído.",
                    WorkflowUiTheme.SUCCESS, WorkflowUiTheme.SUCCESS);
            refreshTareLabel();
        });
    }

    public void onOrderQueueUpdated(int currentIndex, int totalOrders) {
        SwingUtilities.invokeLater(() -> {
            if (totalOrders <= 1) {
                return;
            }
            String base = currentPedido != null
                    ? "Pedido " + currentPedido.getNumero()
                    : "Pedido";
            lbVolume.setText(base + " — " + currentIndex + " de " + totalOrders + " na fila");
        });
    }

    public void onNextPedidoStarted(Pedido completed, Pedido next, int nextIndex, int total) {
        SwingUtilities.invokeLater(() -> {
            setOperatorReviewVisible(false);
            setWeightLiveEnabled(false);
            btnStartTags.setEnabled(false);
            btnStartWeighing.setEnabled(false);
            btnRestartSession.setEnabled(true);
            refreshTareLabel();
            liveTagMonitor.reset();
            liveTagMonitor.setHint("Retire os produtos anteriores — aguardando campo RFID vazio...");
            cameraMonitor.ensureLivePreview();

            String completedNum = completed != null ? completed.getNumero() : "?";
            String nextNum = next != null ? next.getNumero() : "?";
            String detail = "Pedido " + completedNum + " finalizado.\n"
                    + "Próximo: " + nextNum + " (" + nextIndex + "/" + total + ")\n"
                    + "Retire os produtos anteriores. As tags serão apagadas antes da nova leitura.\n"
                    + "Tara reiniciada — processo do zero.";
            WorkflowUiTheme.showValidationOutcome(this, true,
                    "Próximo pedido da fila", detail);
            setStatus("Pedido " + nextNum + " (" + nextIndex + "/" + total
                            + ") — retire os produtos anteriores...",
                    WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);
        });
    }

    public void onAllOrdersCompleted() {
        SwingUtilities.invokeLater(() -> {
            setOperatorReviewVisible(false);
            setWeightLiveEnabled(false);
            btnStartTags.setEnabled(false);
            btnStartWeighing.setEnabled(false);
            btnRestartSession.setEnabled(true);
            refreshTareLabel();
            cameraMonitor.ensureLivePreview();
            setStatus("Todos os pedidos concluídos — toque em Encerrar para sair.",
                    WorkflowUiTheme.SUCCESS, WorkflowUiTheme.SUCCESS);
            WorkflowUiTheme.showValidationOutcome(this, true,
                    "Fila concluída",
                    "Todos os pedidos foram processados.\nToque em Encerrar para sair.");
        });
    }

    /**
     * Mesma fonte da tela de configuração: reparseia a linha DGN e mostra só a
     * carga de produto (status M/O/zero → 0 g). A linha bruta fica visível para diagnóstico.
     */
    @Override
    public void onWeightUpdate(com.peripheral.core.PeripheralDataEvent event) {
        SwingUtilities.invokeLater(() -> {
            if (event == null) {
                return;
            }
            // Durante leitura de tags o RF interfere na balança — UI fica em 0 g.
            if (!weightLiveEnabled) {
                showZeroWeight();
                return;
            }
            DigitronDgnParser.ParseResult parsed = DigitronDgnParser.parse(event.getRawPayload());
            double kg;
            boolean stable;
            String raw;
            if (parsed.isParsed()) {
                kg = parsed.getWeightKg();
                stable = parsed.isStable();
                raw = parsed.getRaw();
            } else {
                Double fromField = ScaleWeightFormat.parseKg(event.getWeight());
                if (fromField == null) {
                    return;
                }
                kg = Math.max(0, fromField);
                stable = Boolean.TRUE.equals(event.getStable());
                raw = event.getRawPayload() != null ? event.getRawPayload() : "";
            }
            lastRawPayload = raw;
            double tare = orchestrator != null ? orchestrator.getTareKg() : 0;
            double net = Math.max(0, kg - Math.max(0, tare));
            updateWeightDisplay(net, stable, raw, tare);
        });
    }

    private void updateWeightDisplay(double netKg, boolean stable, String rawLine, double tareKg) {
        boolean overload = ScaleWeightFormat.isOverload(netKg + Math.max(0, tareKg));
        lbLiveWeightValue.setText(ScaleWeightFormat.formatGrams(netKg));
        lbLiveWeightUnit.setText(ScaleWeightFormat.UNIT);
        lbLiveWeightValue.setForeground(stable ? WorkflowUiTheme.MONITOR_VALUE : Color.WHITE);
        if (overload) {
            lbLiveWeightStable.setText("!  ACIMA DE "
                    + ScaleWeightFormat.MAX_GRAMS + " " + ScaleWeightFormat.UNIT);
            lbLiveWeightStable.setForeground(WorkflowUiTheme.MONITOR_ALERT);
        } else {
            lbLiveWeightStable.setText(stable ? "●  PESO ESTÁVEL (líquido)" : "○  PESO INSTÁVEL — aguarde");
            lbLiveWeightStable.setForeground(stable
                    ? WorkflowUiTheme.MONITOR_VALUE
                    : WorkflowUiTheme.MONITOR_ALERT);
        }
        if (tareKg > 0.0005) {
            lbTareInfo.setText("Tara: " + ScaleWeightFormat.formatGramsPlain(tareKg));
        } else {
            lbTareInfo.setText("Tara: — (sem caixa)");
        }
        if (rawLine != null && !rawLine.isEmpty()) {
            lbScaleRawLine.setText(rawLine);
            lbLiveWeightValue.setToolTipText("Líquido = bruto − tara. Linha: " + rawLine);
        } else {
            lbScaleRawLine.setText(" ");
        }
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
            if (detectedCodes != null) {
                liveTagMonitor.syncDetectedCodes(detectedCodes);
            }
        });
    }

    @Override
    public void onStepChanged(WorkflowStep step, String message) {
        SwingUtilities.invokeLater(() -> {
            if (step == WorkflowStep.RFID_READ) {
                setWeightLiveEnabled(false);
                btnStartTags.setEnabled(false);
                btnStartWeighing.setEnabled(true);
                if (simulationMode) {
                    btnSimulate.setEnabled(true);
                }
                cameraMonitor.ensureLivePreview();
            } else if (step == WorkflowStep.WEIGHING) {
                setWeightLiveEnabled(true);
                btnStartTags.setEnabled(false);
                btnStartWeighing.setEnabled(false);
                if (simulationMode) {
                    btnSimulate.setEnabled(true);
                }
                cameraMonitor.ensureLivePreview();
            } else if (step == WorkflowStep.CAPTURE_PHOTO) {
                btnStartTags.setEnabled(false);
                btnStartWeighing.setEnabled(false);
                // Preview deve ficar parado — keep-alive respeita exclusiveCapture.
                cameraMonitor.showCapturingPlaceholder();
            } else {
                btnStartTags.setEnabled(false);
                btnStartWeighing.setEnabled(false);
                cameraMonitor.ensureLivePreview();
            }
            setStatus(message, WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);
        });
    }

    @Override
    public void onAwaitingTagReadingStart() {
        SwingUtilities.invokeLater(this::setAwaitingTagReadingState);
    }

    @Override
    public void onTagReadingInProgress() {
        SwingUtilities.invokeLater(this::setTagReadingInProgressState);
    }

    @Override
    public void onAwaitingWeighingStart() {
        SwingUtilities.invokeLater(this::setAwaitingStartState);
    }

    @Override
    public void onStabilizationProgress(String message) {
        SwingUtilities.invokeLater(() -> {
            btnStartTags.setEnabled(false);
            btnStartWeighing.setEnabled(false);
            btnSimulate.setEnabled(false);
            setStatus(message, WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);
        });
    }

    @Override
    public void onCycleCompleted(com.peripheral.workflow.WorkflowContext context) {
        SwingUtilities.invokeLater(() -> {
            setOperatorReviewVisible(false);
            setDivergenceLayout(false);
        });
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
            liveTagMonitor.setHint("Aguardando iniciar leitura de tags...");
            setWeightLiveEnabled(false);
            lastRawPayload = null;
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
            if (rfidEnabled) {
                setAwaitingTagReadingState();
            } else {
                setAwaitingStartState();
            }
            btnRestartSession.setEnabled(true);
            cameraMonitor.ensureLivePreview();
        });
    }

    @Override
    public void onStopped() {
        SwingUtilities.invokeLater(() -> {
            clearHistory();
            cameraMonitor.stopLivePreview();
            btnStartTags.setEnabled(false);
            btnStartWeighing.setEnabled(false);
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
