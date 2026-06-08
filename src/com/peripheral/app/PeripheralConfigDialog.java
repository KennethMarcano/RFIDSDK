package com.peripheral.app;

import com.peripheral.session.PeripheralSessionManager;
import com.peripheral.session.PeripheralSlot;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

public class PeripheralConfigDialog extends JDialog {

    public interface ConfigDialogListener {
        void onConfigurationClosed(PeripheralSlot slot, boolean connected);
    }

    private final PeripheralConnectionPanel connectionPanel;
    private final ConfigDialogListener listener;
    private final PeripheralSlot slot;
    private boolean notified;

    public PeripheralConfigDialog(Window owner, PeripheralSlot slot,
                                  PeripheralSessionManager sessionManager,
                                  ConfigDialogListener listener,
                                  Consumer<String> logConsumer) {
        super(owner, "Configurar " + slot.getLabel(), ModalityType.APPLICATION_MODAL);
        this.slot = slot;
        this.listener = listener;

        PeripheralConnectionPanel.ConnectionListener connListener =
                new PeripheralConnectionPanel.ConnectionListener() {
                    @Override
                    public void onConnectionChanged(PeripheralSlot s, boolean connected) {
                        if (listener != null) {
                            listener.onConfigurationClosed(s, connected);
                        }
                    }

                    @Override
                    public void onLog(String message) {
                        if (logConsumer != null) {
                            logConsumer.accept(message);
                        }
                    }
                };

        connectionPanel = new PeripheralConnectionPanel(
                slot, sessionManager, connListener, port -> {});
        connectionPanel.setOwnerWindow(this);
        connectionPanel.setBorder(null);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setOpaque(false);
        content.setBorder(WorkflowUiTheme.empty(16, 16, 16, 16));

        JLabel hint = WorkflowUiTheme.createHintLabel(
                "<html>Selecione fabricante, modelo e porta. "
                        + "Use <b>Testar porta</b> e <b>Conectar</b>. Ao terminar, clique em <b>Concluído</b>.</html>");
        content.add(hint, BorderLayout.NORTH);

        JPanel section = WorkflowUiTheme.createSection(slot.getLabel(), connectionPanel);
        JScrollPane scroll = new JScrollPane(section);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        content.add(scroll, BorderLayout.CENTER);

        ThemedButton btnDone = WorkflowUiTheme.button("Concluído", ThemedButton.Variant.PRIMARY);
        btnDone.addActionListener(e -> closeDialog());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        south.setOpaque(false);
        south.add(btnDone);
        content.add(south, BorderLayout.SOUTH);

        getContentPane().setBackground(WorkflowUiTheme.BG_PAGE);
        setContentPane(content);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                notifyClosed();
            }
        });
        pack();
        setMinimumSize(new Dimension(Math.max(560, getWidth()), Math.max(460, getHeight())));
        setLocationRelativeTo(owner);
    }

    public void showDialog() {
        connectionPanel.refreshPortsFromOutside();
        setVisible(true);
    }

    private void closeDialog() {
        dispose();
        notifyClosed();
    }

    private void notifyClosed() {
        if (notified) {
            return;
        }
        notified = true;
        if (listener != null) {
            listener.onConfigurationClosed(slot, connectionPanel.isConnected());
        }
    }
}
