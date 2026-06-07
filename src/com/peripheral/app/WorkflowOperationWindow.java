package com.peripheral.app;

import com.peripheral.workflow.WeighingWorkflowOrchestrator;
import com.peripheral.workflow.WorkflowConfig;
import com.peripheral.workflow.WorkflowListener;
import com.peripheral.workflow.WorkflowMockData;
import com.peripheral.workflow.WorkflowMockScenario;
import com.peripheral.workflow.WorkflowReadingRecord;
import com.peripheral.workflow.WorkflowStep;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public class WorkflowOperationWindow extends JDialog implements WorkflowListener {

    private static final Color COLOR_OK = new Color(0, 128, 0);
    private static final Color COLOR_WARN = new Color(180, 100, 0);
    private static final Color COLOR_MUTED = new Color(100, 100, 100);

    private final WeighingWorkflowOrchestrator orchestrator;
    private final boolean photoEnabled;
    private final boolean simulationMode;
    private final DecimalFormat weightFormat = new DecimalFormat("#,##0.000");
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    private final JLabel lbStatus = new JLabel("Aguardando início do fluxo...");
    private final DefaultTableModel historyModel = new DefaultTableModel(
            new String[]{"#", "Hora", "Peso (kg)", "Produtos", "Foto"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable historyTable = new JTable(historyModel);
    private final JButton btnViewPhoto = new JButton("Ver foto (F2)");
    private final JButton btnStartWeighing = new JButton("Iniciar pesagem");
    private final JButton btnNext = new JButton("Próximo");
    private final JButton btnRestartSession = new JButton("Reiniciar sessão");

    private final JPanel simulationPanel = new JPanel(new GridBagLayout());
    private final JSpinner spMockWeight = new JSpinner(new SpinnerNumberModel(3.125, 0.001, 9999.999, 0.001));
    private final JTextField tfMockTags = new JTextField(WorkflowMockData.DEFAULT_TAGS_TEXT, 28);
    private final JCheckBox cbFastStabilization = new JCheckBox("Estabilização rápida (~200 ms)", true);
    private final JButton btnLoadSample = new JButton("Exemplo");
    private final JButton btnSimulate = new JButton("Simular pesagem estável");

    public WorkflowOperationWindow(Window owner,
                                   WeighingWorkflowOrchestrator orchestrator,
                                   WorkflowConfig config) {
        super(owner, "Operação — Fluxo automatizado", ModalityType.MODELESS);
        this.orchestrator = orchestrator;
        this.photoEnabled = config.isEnabled(WorkflowStep.CAPTURE_PHOTO);
        this.simulationMode = config.isSimulationMode();

        buildUi();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                setVisible(false);
            }
        });
        setSize(simulationMode ? 760 : 720, simulationMode ? 680 : 620);
        setMinimumSize(new Dimension(640, simulationMode ? 580 : 520));
        setLocationRelativeTo(owner);
    }

    public void restartSession() {
        if (orchestrator == null) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Reiniciar a sessão apagará todo o histórico e as fotos desta execução.\n"
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
        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel header = new JPanel(new BorderLayout(8, 4));
        header.add(BrandingAssets.createEshipLogoLabel(48), BorderLayout.WEST);
        JLabel title = new JLabel("Operação do fluxo automatizado");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        header.add(title, BorderLayout.CENTER);

        lbStatus.setFont(lbStatus.getFont().deriveFont(Font.PLAIN, 14f));
        lbStatus.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));

        JPanel north = new JPanel(new BorderLayout(8, 8));
        north.add(header, BorderLayout.NORTH);
        north.add(lbStatus, BorderLayout.SOUTH);
        content.add(north, BorderLayout.NORTH);

        historyTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        historyTable.setRowHeight(24);
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyTable.getTableHeader().setReorderingAllowed(false);
        configureHistoryColumns();
        historyTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openPhotoForSelectedRow();
                }
            }
        });
        historyTable.getSelectionModel().addListSelectionListener(e -> updateViewPhotoButton());

        JPanel historyPanel = new JPanel(new BorderLayout(4, 4));
        historyPanel.setBorder(new TitledBorder("Histórico da sessão"));
        historyPanel.add(new JScrollPane(historyTable), BorderLayout.CENTER);

        btnViewPhoto.setEnabled(false);
        btnViewPhoto.addActionListener(e -> openPhotoForSelectedRow());
        JPanel historyActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        historyActions.add(btnViewPhoto);
        historyPanel.add(historyActions, BorderLayout.SOUTH);

        JPanel mainCenter = new JPanel(new BorderLayout(8, 8));
        mainCenter.add(historyPanel, BorderLayout.CENTER);
        if (simulationMode) {
            mainCenter.add(buildSimulationPanel(), BorderLayout.SOUTH);
        }
        content.add(mainCenter, BorderLayout.CENTER);

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

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        actions.add(btnRestartSession);
        actions.add(btnStartWeighing);
        actions.add(btnNext);
        content.add(actions, BorderLayout.SOUTH);

        setContentPane(content);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "viewPhoto");
        getRootPane().getActionMap().put("viewPhoto", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openPhotoForSelectedRow();
            }
        });
    }

    private void configureHistoryColumns() {
        TableColumnModel columns = historyTable.getColumnModel();
        columns.getColumn(0).setPreferredWidth(36);
        columns.getColumn(1).setPreferredWidth(72);
        columns.getColumn(2).setPreferredWidth(90);
        columns.getColumn(3).setPreferredWidth(280);
        columns.getColumn(4).setPreferredWidth(48);
    }

    private JPanel buildSimulationPanel() {
        simulationPanel.setBorder(new TitledBorder("Simulação de dados"));
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
        historyModel.setRowCount(0);
        btnViewPhoto.setEnabled(false);
    }

    private void addReadingToHistory(WorkflowReadingRecord record) {
        String products = formatProducts(record);
        String photoLabel = record.hasPhoto() ? "Sim" : "—";
        historyModel.addRow(new Object[]{
                record.getIndex(),
                timeFormat.format(new Date(record.getTimestampMs())),
                weightFormat.format(record.getWeightKg()),
                products,
                photoLabel
        });
        int lastRow = historyModel.getRowCount() - 1;
        historyTable.setRowSelectionInterval(lastRow, lastRow);
        historyTable.scrollRectToVisible(historyTable.getCellRect(lastRow, 0, true));
        updateViewPhotoButton();
    }

    private String formatProducts(WorkflowReadingRecord record) {
        if (record.getTagCodes().isEmpty()) {
            return "Nenhum produto identificado";
        }
        return String.join(", ", record.getTagCodes());
    }

    private void updateViewPhotoButton() {
        WorkflowReadingRecord record = getSelectedRecord();
        boolean canView = photoEnabled
                && record != null
                && record.hasPhoto()
                && new File(record.getPhotoPath()).isFile();
        btnViewPhoto.setEnabled(canView);
    }

    private WorkflowReadingRecord getSelectedRecord() {
        int row = historyTable.getSelectedRow();
        if (row < 0 || orchestrator == null) {
            return null;
        }
        int modelRow = historyTable.convertRowIndexToModel(row);
        if (modelRow < 0 || modelRow >= orchestrator.getSessionStore().getRecords().size()) {
            return null;
        }
        return orchestrator.getSessionStore().getRecords().get(modelRow);
    }

    private void openPhotoForSelectedRow() {
        WorkflowReadingRecord record = getSelectedRecord();
        if (record == null || !record.hasPhoto()) {
            JOptionPane.showMessageDialog(this,
                    "Selecione uma leitura com foto no histórico.",
                    "Foto", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        WorkflowPhotoPreviewDialog.showPreview(this, record.getPhotoPath());
    }

    private void setAwaitingStartState() {
        btnStartWeighing.setEnabled(true);
        btnNext.setEnabled(false);
        btnRestartSession.setEnabled(true);
        if (simulationMode) {
            btnSimulate.setEnabled(false);
            lbStatus.setText("Clique em Iniciar pesagem e depois simule os dados.");
        } else {
            lbStatus.setText("Clique em Iniciar pesagem para começar a leitura.");
        }
        lbStatus.setForeground(COLOR_MUTED);
    }

    private void setWaitingForNextState() {
        btnStartWeighing.setEnabled(false);
        btnNext.setEnabled(true);
        btnRestartSession.setEnabled(true);
        lbStatus.setText("Ciclo concluído — clique em Próximo para nova leitura.");
        lbStatus.setForeground(COLOR_OK);
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
            lbStatus.setText(message);
            lbStatus.setForeground(COLOR_WARN);
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
            lbStatus.setText(message);
            lbStatus.setForeground(COLOR_WARN);
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
            lbStatus.setText("Erro: " + message);
            lbStatus.setForeground(Color.RED);
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
            lbStatus.setText("Fluxo parado.");
            lbStatus.setForeground(COLOR_MUTED);
            dispose();
        });
    }
}
