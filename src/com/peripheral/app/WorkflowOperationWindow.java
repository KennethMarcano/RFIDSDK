package com.peripheral.app;



import com.peripheral.workflow.WeighingWorkflowOrchestrator;

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



    private final WeighingWorkflowOrchestrator orchestrator;

    private final boolean photoEnabled;

    private final boolean labelEnabled;

    private final boolean simulationMode;



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

                                   WeighingWorkflowOrchestrator orchestrator,

                                   WorkflowConfig config) {

        super(owner, "Operação — Fluxo automatizado", ModalityType.MODELESS);

        this.orchestrator = orchestrator;

        this.photoEnabled = config.isEnabled(WorkflowStep.CAPTURE_PHOTO);

        this.labelEnabled = config.isEnabled(WorkflowStep.PRINT_LABEL);

        this.simulationMode = config.isSimulationMode();



        buildUi();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        addWindowListener(new WindowAdapter() {

            @Override

            public void windowClosing(WindowEvent e) {

                setVisible(false);

            }

        });

        setSize(simulationMode ? 820 : 780, simulationMode ? 720 : 660);

        setMinimumSize(new Dimension(680, simulationMode ? 600 : 560));

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

        content.add(buildMainCenter(), BorderLayout.CENTER);

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

        statusBar.add(lbStatus, BorderLayout.CENTER);

        header.add(statusBar, BorderLayout.SOUTH);



        header.setBorder(WorkflowUiTheme.empty(0, 0, 12, 0));

        return header;

    }



    private JPanel buildMainCenter() {

        JPanel mainCenter = new JPanel(new BorderLayout(0, 12));

        mainCenter.setOpaque(false);

        mainCenter.add(buildHistoryPanel(), BorderLayout.CENTER);

        if (simulationMode) {

            mainCenter.add(buildSimulationPanel(), BorderLayout.SOUTH);

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



        JLabel hint = new JLabel("<html><small>Formato: <code>CODIGO:EPC</code> separados por vírgula. "

                + "Use <b>Iniciar pesagem</b> e depois <b>Simular pesagem estável</b>.</small></html>");

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

        btnSimulate.setEnabled(false);

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



    @Override

    public void onWeightUpdate(com.peripheral.core.PeripheralDataEvent event) {

    }



    @Override

    public void onTagRead(com.peripheral.core.PeripheralDataEvent event) {

    }



    @Override

    public void onStepChanged(WorkflowStep step, String message) {

        SwingUtilities.invokeLater(() -> {

            btnStartWeighing.setEnabled(false);

            btnNext.setEnabled(false);

            setStatus(message, WorkflowUiTheme.WARNING, WorkflowUiTheme.WARNING);

            if (simulationMode && step == WorkflowStep.WEIGHING) {

                btnSimulate.setEnabled(true);

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

