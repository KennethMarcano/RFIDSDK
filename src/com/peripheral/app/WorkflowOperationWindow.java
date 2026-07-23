package com.peripheral.app;

import com.peripheral.camera.CameraHardware;
import com.peripheral.camera.CameraMicroserviceClient;
import com.peripheral.camera.CameraMicroserviceLifecycle;
import com.peripheral.camera.CameraServiceException;
import com.peripheral.pedido.Pedido;
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
    private static final int HISTORY_VIEWPORT_HEIGHT = 240;

    private static void alignPanelWidth(JComponent panel) {
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private final WorkflowController orchestrator;
    private final boolean photoEnabled;
    private final boolean labelEnabled;
    private final boolean simulationMode;
    private final boolean orderValidationEnabled;
    private final JLabel lbVolume = new JLabel("");
    private final JLabel lbLiveWeightValue = new JLabel("—.—", SwingConstants.CENTER);
    private final JLabel lbLiveWeightUnit = new JLabel("kg", SwingConstants.CENTER);
    private final JLabel lbLiveWeightStable = new JLabel("Aguardando leitura da balança", SwingConstants.CENTER);
    private final JLabel lbLiveTags = new JLabel("Tags: —");
    private final JLabel lbCameraStatus = new JLabel("Câmera: verificando...");
    private final ThemedButton btnCameraPreview =
            WorkflowUiTheme.button("Abrir vídeo", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnCameraRecalibrate =
            WorkflowUiTheme.button("Recalibrar", ThemedButton.Variant.SECONDARY);
    private final JPanel operatorReviewPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
    private final ThemedButton btnRereadRfid =
            WorkflowUiTheme.button("Reler tags", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnCapturePhoto =
            WorkflowUiTheme.button("Tirar foto", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnReanalyze =
            WorkflowUiTheme.button("Re-analisar IA", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnConfirmOperator =
            WorkflowUiTheme.button("Finalizar volume", ThemedButton.Variant.SUCCESS);

    private final JLabel lbStatus = new JLabel("Aguardando início do fluxo...");
    private final JPanel statusIndicator = new JPanel();
    private final JPanel historyList = new JPanel();
    private final JPanel emptyState = new JPanel();
    private final JLabel lbEmptyTitle = new JLabel("Nenhuma leitura ainda");
    private final JLabel lbEmptyHint = new JLabel("As leituras aparecerão aqui após cada ciclo concluído.");
    private final List<WorkflowReadingCard> readingCards = new ArrayList<>();
    private JPanel historyHost;

    private final ThemedButton btnStartWeighing =
            WorkflowUiTheme.button("Iniciar pesagem", ThemedButton.Variant.PRIMARY);
    private final ThemedButton btnNext =
            WorkflowUiTheme.button("Próximo", ThemedButton.Variant.SUCCESS);
    private final ThemedButton btnRestartSession =
            WorkflowUiTheme.button("Reiniciar sessão", ThemedButton.Variant.SECONDARY);

    private final JPanel simulationPanel = new JPanel(new GridBagLayout());
    private final JSpinner spMockWeight = new JSpinner(new SpinnerNumberModel(3.125, 0.001, 9999.999, 0.001));
    private final JTextField tfMockTags = new JTextField(WorkflowMockData.DEFAULT_TAGS_TEXT, 28);
    private final JCheckBox cbFastStabilization = new JCheckBox("Estabilização rápida (~200 ms)", true);
    private final ThemedButton btnLoadSample =
            WorkflowUiTheme.button("Exemplo", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnSimulate =
            WorkflowUiTheme.button("Simular pesagem estável", ThemedButton.Variant.PRIMARY);

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
        refreshCameraStatusAsync();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                CameraHardware.stopPreview();
                setVisible(false);
            }
        });
        setSize(simulationMode ? 860 : 820, simulationMode ? 820 : 760);
        setMinimumSize(new Dimension(720, simulationMode ? 680 : 640));
        setLocationRelativeTo(owner);
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

        JPanel content = new JPanel(new BorderLayout(0, 0));

        content.setBackground(WorkflowUiTheme.BG_PAGE);

        content.setBorder(WorkflowUiTheme.empty(16, 16, 16, 16));



        content.add(buildHeader(), BorderLayout.NORTH);

        JScrollPane mainScroll = WorkflowUiTheme.wrapVerticalScroll(buildMainCenter());
        content.add(mainScroll, BorderLayout.CENTER);

        content.add(buildFooter(), BorderLayout.SOUTH);



        getContentPane().setBackground(WorkflowUiTheme.BG_PAGE);

        setContentPane(content);

    }



    private JPanel buildHeader() {

        JPanel header = new JPanel(new BorderLayout(12, 8));

        header.setOpaque(false);



        JPanel brandRow = new JPanel(new BorderLayout(12, 0));

        brandRow.setOpaque(false);

        brandRow.add(BrandingAssets.createEshipLogoLabel(44), BorderLayout.WEST);



        JPanel titles = new JPanel();

        titles.setOpaque(false);

        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));



        JLabel title = new JLabel("Operação do fluxo automatizado");

        title.setFont(WorkflowUiTheme.fontTitle(title));

        title.setForeground(WorkflowUiTheme.TEXT_PRIMARY);

        title.setAlignmentX(Component.LEFT_ALIGNMENT);



        JLabel subtitle = new JLabel("Histórico da sessão com peso, hora e acesso rápido a foto e etiqueta");

        subtitle.setFont(WorkflowUiTheme.fontMeta(subtitle));

        subtitle.setForeground(WorkflowUiTheme.TEXT_SECONDARY);

        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        subtitle.setBorder(WorkflowUiTheme.empty(2, 0, 0, 0));



        titles.add(title);

        titles.add(subtitle);

        brandRow.add(titles, BorderLayout.CENTER);

        header.add(brandRow, BorderLayout.NORTH);



        JPanel statusBar = new JPanel(new BorderLayout(10, 0)) {

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

        statusBar.setBorder(WorkflowUiTheme.empty(10, 12, 10, 12));



        statusIndicator.setPreferredSize(new Dimension(4, 20));

        statusIndicator.setOpaque(true);

        statusIndicator.setBackground(WorkflowUiTheme.TEXT_MUTED);



        lbStatus.setFont(WorkflowUiTheme.fontStatus(lbStatus));

        lbStatus.setForeground(WorkflowUiTheme.TEXT_SECONDARY);



        statusBar.add(statusIndicator, BorderLayout.WEST);

        JPanel statusText = new JPanel();
        statusText.setOpaque(false);
        statusText.setLayout(new BoxLayout(statusText, BoxLayout.Y_AXIS));
        lbVolume.setFont(WorkflowUiTheme.fontMeta(lbVolume));
        lbVolume.setForeground(WorkflowUiTheme.TEXT_PRIMARY);
        lbLiveTags.setFont(WorkflowUiTheme.fontMeta(lbLiveTags));
        statusText.add(lbStatus);
        if (orderValidationEnabled) {
            statusText.add(lbVolume);
            statusText.add(lbLiveTags);
        }
        statusBar.add(statusText, BorderLayout.CENTER);

        header.add(statusBar, BorderLayout.SOUTH);

        header.setBorder(WorkflowUiTheme.empty(0, 0, 12, 0));

        return header;
    }

    private JPanel buildScaleMonitorPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(true);
        panel.setBackground(new Color(0x0F, 0x17, 0x2A));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x1E, 0x29, 0x3B), 1),
                WorkflowUiTheme.empty(18, 20, 18, 20)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel caption = new JLabel("MONITOR DA BALANÇA", SwingConstants.CENTER);
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, 13f));
        caption.setForeground(new Color(0x94, 0xA3, 0xB8));
        caption.setAlignmentX(Component.CENTER_ALIGNMENT);

        lbLiveWeightValue.setFont(lbLiveWeightValue.getFont().deriveFont(Font.BOLD, 72f));
        lbLiveWeightValue.setForeground(Color.WHITE);
        lbLiveWeightValue.setAlignmentX(Component.CENTER_ALIGNMENT);

        lbLiveWeightUnit.setFont(lbLiveWeightUnit.getFont().deriveFont(Font.BOLD, 22f));
        lbLiveWeightUnit.setForeground(new Color(0xCB, 0xD5, 0xE1));
        lbLiveWeightUnit.setAlignmentX(Component.CENTER_ALIGNMENT);

        lbLiveWeightStable.setFont(lbLiveWeightStable.getFont().deriveFont(Font.PLAIN, 14f));
        lbLiveWeightStable.setForeground(new Color(0x94, 0xA3, 0xB8));
        lbLiveWeightStable.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(caption);
        center.add(Box.createVerticalStrut(8));
        center.add(lbLiveWeightValue);
        center.add(Box.createVerticalStrut(2));
        center.add(lbLiveWeightUnit);
        center.add(Box.createVerticalStrut(10));
        center.add(lbLiveWeightStable);

        panel.add(center, BorderLayout.CENTER);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
        panel.setPreferredSize(new Dimension(0, 200));
        return panel;
    }



    private JPanel buildMainCenter() {

        JPanel mainCenter = new JPanel();
        mainCenter.setOpaque(false);
        mainCenter.setLayout(new BoxLayout(mainCenter, BoxLayout.Y_AXIS));
        mainCenter.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel scaleMonitor = buildScaleMonitorPanel();
        alignPanelWidth(scaleMonitor);
        mainCenter.add(scaleMonitor);
        mainCenter.add(Box.createVerticalStrut(12));

        if (orderValidationEnabled) {
            buildOperatorReviewPanel();
            alignPanelWidth(operatorReviewPanel);
            mainCenter.add(operatorReviewPanel);
            mainCenter.add(Box.createVerticalStrut(12));
        }

        JPanel cameraPanel = buildCameraPanel();
        alignPanelWidth(cameraPanel);
        mainCenter.add(cameraPanel);
        mainCenter.add(Box.createVerticalStrut(12));

        JPanel history = buildHistoryPanel();
        alignPanelWidth(history);
        history.setPreferredSize(new Dimension(0, HISTORY_VIEWPORT_HEIGHT));
        history.setMinimumSize(new Dimension(0, 120));
        history.setMaximumSize(new Dimension(Integer.MAX_VALUE, HISTORY_VIEWPORT_HEIGHT));
        mainCenter.add(history);

        if (simulationMode) {
            mainCenter.add(Box.createVerticalStrut(12));
            JPanel simulation = buildSimulationPanel();
            alignPanelWidth(simulation);
            mainCenter.add(simulation);
        }

        return mainCenter;

    }



    private JPanel buildHistoryPanel() {

        JPanel wrapper = new JPanel(new BorderLayout());

        wrapper.setOpaque(false);



        historyList.setLayout(new BoxLayout(historyList, BoxLayout.Y_AXIS));

        historyList.setOpaque(false);



        emptyState.setLayout(new BoxLayout(emptyState, BoxLayout.Y_AXIS));

        emptyState.setOpaque(false);

        emptyState.setAlignmentX(Component.CENTER_ALIGNMENT);

        emptyState.setBorder(WorkflowUiTheme.empty(48, 24, 48, 24));



        lbEmptyTitle.setFont(lbEmptyTitle.getFont().deriveFont(Font.BOLD, 14f));

        lbEmptyTitle.setForeground(WorkflowUiTheme.TEXT_SECONDARY);

        lbEmptyTitle.setAlignmentX(Component.CENTER_ALIGNMENT);



        lbEmptyHint.setFont(WorkflowUiTheme.fontMeta(lbEmptyHint));

        lbEmptyHint.setForeground(WorkflowUiTheme.TEXT_MUTED);

        lbEmptyHint.setAlignmentX(Component.CENTER_ALIGNMENT);

        lbEmptyHint.setBorder(WorkflowUiTheme.empty(6, 0, 0, 0));



        emptyState.add(lbEmptyTitle);

        emptyState.add(lbEmptyHint);



        JScrollPane scroll = new JScrollPane(historyList);

        scroll.setBorder(BorderFactory.createLineBorder(WorkflowUiTheme.BORDER));

        scroll.getViewport().setBackground(WorkflowUiTheme.BG_PAGE);

        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        scroll.getVerticalScrollBar().setUnitIncrement(16);



        historyHost = new JPanel(new CardLayout());

        historyHost.setOpaque(false);

        historyHost.add(emptyState, "empty");

        historyHost.add(scroll, "list");



        updateEmptyStateVisibility();

        wrapper.add(historyHost, BorderLayout.CENTER);

        return wrapper;

    }



    private JPanel buildFooter() {

        btnStartWeighing.addActionListener(e -> {

            if (orchestrator != null) {

                orchestrator.confirmWeighingStart();

            }

        });

        btnNext.setEnabled(false);

        btnNext.addActionListener(e -> {

            if (orchestrator != null) {

                orchestrator.acknowledgeNext();

            }

        });

        btnRestartSession.addActionListener(e -> restartSession());

        btnRereadRfid.addActionListener(e -> runOperatorAction(() -> orchestrator.operatorRereadRfid()));
        btnCapturePhoto.addActionListener(e -> runOperatorAction(() -> orchestrator.operatorCapturePhoto()));
        btnReanalyze.addActionListener(e -> runOperatorAction(() -> orchestrator.operatorReanalyze()));
        btnConfirmOperator.addActionListener(e -> confirmOperatorVolume());



        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));

        actions.setOpaque(false);

        actions.setBorder(WorkflowUiTheme.empty(14, 0, 0, 0));

        actions.add(btnRestartSession);

        actions.add(btnStartWeighing);

        actions.add(btnNext);

        return actions;

    }



    private JPanel buildSimulationPanel() {

        simulationPanel.setOpaque(false);

        simulationPanel.setBorder(BorderFactory.createCompoundBorder(

                BorderFactory.createTitledBorder(

                        BorderFactory.createLineBorder(WorkflowUiTheme.BORDER), "Simulação de dados"),

                WorkflowUiTheme.empty(8, 8, 8, 8)));



        GridBagConstraints gbc = new GridBagConstraints();

        gbc.insets = new Insets(4, 4, 4, 4);

        gbc.anchor = GridBagConstraints.WEST;

        gbc.fill = GridBagConstraints.HORIZONTAL;



        gbc.gridx = 0;

        gbc.gridy = 0;

        gbc.weightx = 0;

        simulationPanel.add(new JLabel("Peso (kg):"), gbc);



        gbc.gridx = 1;

        gbc.weightx = 1;

        simulationPanel.add(spMockWeight, gbc);



        gbc.gridx = 0;

        gbc.gridy = 1;

        gbc.weightx = 0;

        simulationPanel.add(new JLabel("Tags:"), gbc);



        gbc.gridx = 1;

        gbc.weightx = 1;

        simulationPanel.add(tfMockTags, gbc);



        gbc.gridx = 1;

        gbc.gridy = 2;

        simulationPanel.add(cbFastStabilization, gbc);



        JLabel hint = new JLabel("<html><small>Informe os <b>seriais</b> esperados do volume, separados por vírgula "
                + "(EPC = serial). Ex. vol. 1 pedido 1001: <code>SN1001-001, SN1001-002, SN1001-003</code>. "
                + "Peso ≈ 1,800 kg. Use <b>Iniciar pesagem</b> e depois <b>Simular pesagem estável</b>.</small></html>");

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

        });

        readingCards.add(card);

        historyList.add(card);

        historyList.add(Box.createVerticalStrut(8));

        updateEmptyStateVisibility();

        historyList.revalidate();

        historyList.repaint();



        SwingUtilities.invokeLater(() -> {

            Container parent = historyList.getParent();

            if (parent instanceof JViewport) {

                JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, parent);

                if (scroll != null) {

                    scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());

                }

            }

        });

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

        setOperatorReviewVisible(false);

        lbLiveTags.setText("Tags: —");

        if (simulationMode) {

            btnSimulate.setEnabled(false);

            setStatus("Clique em Iniciar pesagem e depois simule os dados.",

                    WorkflowUiTheme.TEXT_MUTED, WorkflowUiTheme.TEXT_SECONDARY);

        } else {

            setStatus("Clique em Iniciar pesagem para começar a leitura.",

                    WorkflowUiTheme.TEXT_MUTED, WorkflowUiTheme.TEXT_SECONDARY);

        }

    }



    private void setWaitingForNextState() {

        btnStartWeighing.setEnabled(false);

        btnNext.setEnabled(true);

        btnRestartSession.setEnabled(true);

        setStatus("Ciclo concluído — clique em Próximo para nova leitura.",

                WorkflowUiTheme.SUCCESS, WorkflowUiTheme.SUCCESS);

    }



    private void buildOperatorReviewPanel() {
        operatorReviewPanel.setOpaque(false);
        operatorReviewPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.BORDER), "Revisão do operador"));
        operatorReviewPanel.add(btnRereadRfid);
        operatorReviewPanel.add(btnCapturePhoto);
        operatorReviewPanel.add(btnReanalyze);
        operatorReviewPanel.add(btnConfirmOperator);
        setOperatorReviewVisible(false);
    }

    private JPanel buildCameraPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 4));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(WorkflowUiTheme.BORDER), "Câmera Sony IMX500"),
                WorkflowUiTheme.empty(8, 8, 8, 8)));

        WorkflowUiTheme.styleStatusPill(lbCameraStatus,
                new Color(0xFE, 0xF3, 0xC7), WorkflowUiTheme.WARNING);
        panel.add(lbCameraStatus, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        btnCameraPreview.addActionListener(e -> openCameraPreview());
        btnCameraRecalibrate.addActionListener(e -> recalibrateCameraFromOperation());
        actions.add(btnCameraRecalibrate);
        actions.add(btnCameraPreview);
        panel.add(actions, BorderLayout.EAST);

        JLabel hint = WorkflowUiTheme.createHintLabel(
                photoEnabled
                        ? "Preview em vídeo e recalibração. A foto do fluxo usa rpicam-still (não o logo)."
                        : "Preview disponível. Ative \"Capturar foto\" no fluxo para gravar imagens.");
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    private void refreshCameraStatusAsync() {
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                CameraMicroserviceClient client = CameraMicroserviceLifecycle.getInstance().getClient();
                boolean serviceOk = client.checkHealth();
                boolean hardwareOk = CameraHardware.isCameraPresent();
                return serviceOk || hardwareOk;
            }

            @Override
            protected void done() {
                boolean ok = false;
                try {
                    ok = Boolean.TRUE.equals(get());
                } catch (Exception ignored) {
                }
                applyCameraStatus(ok, ok ? "Câmera online" : "Câmera indisponível");
            }
        }.execute();
    }

    private void applyCameraStatus(boolean available, String text) {
        lbCameraStatus.setText(text != null ? text : (available ? "Câmera online" : "Câmera indisponível"));
        if (available) {
            WorkflowUiTheme.styleStatusPill(lbCameraStatus,
                    new Color(0xD1, 0xFA, 0xE5), WorkflowUiTheme.SUCCESS);
        } else {
            WorkflowUiTheme.styleStatusPill(lbCameraStatus,
                    new Color(0xFE, 0xF3, 0xC7), WorkflowUiTheme.WARNING);
        }
        btnCameraPreview.setEnabled(true);
        btnCameraRecalibrate.setEnabled(true);
    }

    private void openCameraPreview() {
        btnCameraPreview.setEnabled(false);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    CameraHardware.startPreview();
                    return null;
                } catch (CameraServiceException e) {
                    return e.getMessage();
                }
            }

            @Override
            protected void done() {
                btnCameraPreview.setEnabled(true);
                try {
                    String error = get();
                    if (error != null) {
                        JOptionPane.showMessageDialog(WorkflowOperationWindow.this, error,
                                "Preview câmera", JOptionPane.ERROR_MESSAGE);
                        applyCameraStatus(false, "Câmera: falha no preview");
                    } else {
                        applyCameraStatus(true, "Câmera: preview aberto");
                        setStatus("Preview da câmera aberto (rpicam-hello --timeout 0).",
                                WorkflowUiTheme.SUCCESS, WorkflowUiTheme.SUCCESS);
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(WorkflowOperationWindow.this, e.getMessage(),
                            "Preview câmera", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void recalibrateCameraFromOperation() {
        btnCameraRecalibrate.setEnabled(false);
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
                return CameraHardware.recalibrate();
            }

            @Override
            protected void done() {
                btnCameraRecalibrate.setEnabled(true);
                try {
                    String msg = get();
                    applyCameraStatus(true, "Câmera online");
                    JOptionPane.showMessageDialog(WorkflowOperationWindow.this, msg,
                            "Recalibrar câmera", JOptionPane.INFORMATION_MESSAGE);
                    setStatus("Câmera recalibrada.", WorkflowUiTheme.SUCCESS, WorkflowUiTheme.SUCCESS);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    applyCameraStatus(false, "Câmera indisponível");
                    JOptionPane.showMessageDialog(WorkflowOperationWindow.this,
                            "Erro na recalibração: " + cause.getMessage(),
                            "Recalibrar câmera", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
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

    private void confirmOperatorVolume() {
        if (orchestrator == null || !orchestrator.isOperatorReview()) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Confirmo que o volume está OK e pode avançar.",
                "Finalizar volume",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        runOperatorAction(() -> orchestrator.operatorConfirmVolume());
    }

    public void onOrderLoaded(Pedido pedido) {
        SwingUtilities.invokeLater(() -> {
            if (pedido != null) {
                lbVolume.setText("Pedido " + pedido.getNumero() + " — Volume 1 de " + pedido.getVolumeCount());
            }
        });
    }

    public void onVolumeChanged(int currentIndex, int totalVolumes) {
        SwingUtilities.invokeLater(() -> {
            lbVolume.setText("Volume " + currentIndex + " de " + totalVolumes);
            setStatus("Aguardando início do volume " + currentIndex + "...",
                    WorkflowUiTheme.TEXT_MUTED, WorkflowUiTheme.TEXT_SECONDARY);
        });
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
            if (simulationMode) {
                btnSimulate.setEnabled(true);
            }
            setStatus(message, WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);
            if (context != null && context.getAiMessage() != null && !context.getAiMessage().isEmpty()) {
                lbStatus.setText(message + " | IA: " + context.getAiMessage());
            }
        });
    }

    public void onCameraServiceStatus(boolean available, String detail) {
        SwingUtilities.invokeLater(() -> {
            String text = available
                    ? (detail != null && !detail.isEmpty() ? "Câmera: " + detail : "Câmera online")
                    : "Câmera indisponível";
            applyCameraStatus(available, text);
            if (!available) {
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



    @Override
    public void onWeightUpdate(com.peripheral.core.PeripheralDataEvent event) {
        SwingUtilities.invokeLater(() -> {
            if (event == null) {
                return;
            }
            String weight = event.getWeight();
            if (weight == null || weight.isEmpty()) {
                if (event.getDisplayText() != null && !event.getDisplayText().isEmpty()) {
                    lbLiveWeightValue.setText(event.getDisplayText());
                    lbLiveWeightUnit.setText("");
                }
                return;
            }
            boolean stable = Boolean.TRUE.equals(event.getStable());
            lbLiveWeightValue.setText(weight);
            lbLiveWeightUnit.setText("kg");
            lbLiveWeightValue.setForeground(stable ? new Color(0x34, 0xD3, 0x99) : Color.WHITE);
            lbLiveWeightStable.setText(stable ? "●  PESO ESTÁVEL" : "○  PESO INSTÁVEL — aguarde");
            lbLiveWeightStable.setForeground(stable
                    ? new Color(0x34, 0xD3, 0x99)
                    : new Color(0xFB, 0xBF, 0x24));
        });
    }



    @Override

    public void onTagRead(com.peripheral.core.PeripheralDataEvent event) {

        SwingUtilities.invokeLater(() -> {
            if (event == null) {
                return;
            }
            String code = event.getCode();
            if (code != null && !code.isEmpty()) {
                String current = lbLiveTags.getText();
                if (current.equals("Tags: —")) {
                    lbLiveTags.setText("Tags: " + code);
                } else if (!current.contains(code)) {
                    lbLiveTags.setText(current + ", " + code);
                }
            }
        });

    }



    @Override

    public void onStepChanged(WorkflowStep step, String message) {

        SwingUtilities.invokeLater(() -> {

            btnStartWeighing.setEnabled(false);

            btnNext.setEnabled(false);

            setStatus(message, WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);

            if (simulationMode && (step == WorkflowStep.WEIGHING || step == WorkflowStep.RFID_READ)) {
                btnSimulate.setEnabled(true);
            }
            if (step == WorkflowStep.RFID_READ && orchestrator != null && orchestrator.isOperatorReview()) {
                lbLiveTags.setText("Tags: —");
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

            btnSimulate.setEnabled(false);

            setStatus(message, WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);

        });

    }



    @Override

    public void onCycleCompleted(com.peripheral.workflow.WorkflowContext context) {

    }



    @Override

    public void onReadingRecorded(WorkflowReadingRecord record) {

        SwingUtilities.invokeLater(() -> addReadingToHistory(record));

    }



    @Override

    public void onSessionCleared() {

        SwingUtilities.invokeLater(this::clearHistory);

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

            btnRestartSession.setEnabled(true);

        });

    }



    @Override

    public void onStopped() {

        SwingUtilities.invokeLater(() -> {

            clearHistory();

            btnStartWeighing.setEnabled(false);

            btnNext.setEnabled(false);

            btnRestartSession.setEnabled(false);

            setStatus("Fluxo parado.", WorkflowUiTheme.TEXT_MUTED, WorkflowUiTheme.TEXT_SECONDARY);

            dispose();

        });

    }

}

