package com.peripheral.app;

import com.peripheral.session.PeripheralSessionManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainFrame extends JFrame {

    private static final int MAX_LOG_CHARS = 40_000;

    private final PeripheralSessionManager sessionManager = new PeripheralSessionManager();
    private final JTextArea taLog = new JTextArea(16, 44);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    private AutomatedWorkflowPanel workflowPanel;

    public MainFrame() {
        super("Periféricos eship — Fluxo automatizado");
        // setUndecorated precisa acontecer antes de qualquer peer nativo (buildUi/setVisible).
        if (WorkflowUiTheme.isFullScreenEnabled()) {
            setUndecorated(true);
            setResizable(false);
        }
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                WorkflowUiTheme.keepFullScreen(MainFrame.this);
            }

            @Override
            public void windowActivated(WindowEvent e) {
                WorkflowUiTheme.keepFullScreen(MainFrame.this);
            }

            @Override
            public void windowClosing(WindowEvent e) {
                exitApplication();
            }
        });
        buildUi();
        WorkflowUiTheme.styleFrame(this);
        WorkflowUiTheme.applyTouchScreenSize(this);
        setVisible(true);
        // Alguns WMs no Raspberry redimensionam logo após o show.
        SwingUtilities.invokeLater(() -> WorkflowUiTheme.keepFullScreen(this));
    }

    private void buildUi() {
        setLayout(new BorderLayout(0, 0));

        taLog.setEditable(false);
        taLog.setLineWrap(true);
        taLog.setWrapStyleWord(true);
        taLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        WorkflowUiTheme.styleTextArea(taLog);

        workflowPanel = new AutomatedWorkflowPanel(sessionManager, this::appendLog);
        workflowPanel.setOwnerWindow(this);

        ThemedButton btnLog = WorkflowUiTheme.button("Log", ThemedButton.Variant.SECONDARY)
                .withSize(ThemedButton.Size.SMALL);
        btnLog.addActionListener(e -> showLogDialog());

        ThemedButton btnUpdate = WorkflowUiTheme.button("Atualizar", ThemedButton.Variant.SECONDARY)
                .withSize(ThemedButton.Size.SMALL);
        btnUpdate.setToolTipText("Baixa a versão mais recente (git), recompila e reinicia a app.");
        btnUpdate.addActionListener(e -> confirmAndUpdate());

        ThemedButton btnExit = WorkflowUiTheme.button("Sair", ThemedButton.Variant.DANGER)
                .withSize(ThemedButton.Size.SMALL);
        btnExit.addActionListener(e -> confirmExit());

        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        headerActions.setOpaque(false);
        headerActions.add(btnUpdate);
        headerActions.add(btnLog);
        headerActions.add(btnExit);

        add(WorkflowUiTheme.createHeader(
                "Periféricos eship",
                "Fluxo automatizado de pesagem, RFID e etiqueta",
                headerActions), BorderLayout.NORTH);
        add(workflowPanel, BorderLayout.CENTER);
    }

    private void showLogDialog() {
        JScrollPane scroll = new JScrollPane(taLog);
        WorkflowUiTheme.styleScrollPane(scroll);
        Rectangle screen = WorkflowUiTheme.availableScreenBounds();
        scroll.setPreferredSize(new Dimension(
                Math.min(screen.width - 80, 700),
                Math.min(screen.height - 140, 340)));

        JDialog dialog = new JDialog(this, "Log da sessão", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBackground(WorkflowUiTheme.BG_PAGE);
        content.setBorder(WorkflowUiTheme.empty(12, 12, 12, 12));
        content.add(scroll, BorderLayout.CENTER);

        ThemedButton btnClear = WorkflowUiTheme.button("Limpar", ThemedButton.Variant.SECONDARY);
        btnClear.addActionListener(e -> taLog.setText(""));
        ThemedButton btnClose = WorkflowUiTheme.button("Fechar", ThemedButton.Variant.PRIMARY);
        btnClose.addActionListener(e -> dialog.dispose());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(btnClear);
        actions.add(btnClose);
        content.add(actions, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void confirmAndUpdate() {
        int choice = JOptionPane.showConfirmDialog(this,
                "Baixar a versão mais recente, recompilar e reiniciar a aplicação?\n\n"
                        + "A janela vai fechar e reabrir sozinha em alguns segundos.",
                "Atualizar",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        if (workflowPanel != null) {
            workflowPanel.stopWorkflowIfRunning();
        }
        AppUpdater.runUpdateAsync(this, this::appendLog);
    }

    private void confirmExit() {
        int choice = JOptionPane.showConfirmDialog(this,
                "Deseja encerrar a aplicação?",
                "Sair",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            exitApplication();
        }
    }

    private void exitApplication() {
        if (workflowPanel != null) {
            workflowPanel.stopWorkflowIfRunning();
        }
        sessionManager.disconnectAll();
        dispose();
        System.exit(0);
    }

    private void appendLog(String msg) {
        SwingUtilities.invokeLater(() -> {
            taLog.append("[" + timeFormat.format(new Date()) + "] " + msg + "\n");
            int length = taLog.getDocument().getLength();
            if (length > MAX_LOG_CHARS) {
                taLog.replaceRange("", 0, length - MAX_LOG_CHARS);
            }
            taLog.setCaretPosition(taLog.getDocument().getLength());
        });
    }
}
