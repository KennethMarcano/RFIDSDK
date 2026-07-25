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
    private boolean closing;

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

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setOpaque(false);
        content.setBorder(WorkflowUiTheme.empty(10, 10, 10, 10));

        String hintHtml = slot == PeripheralSlot.SCALE
                ? "<html>Selecione fabricante, modelo e porta. Use <b>Testar porta</b> e <b>Conectar</b>. "
                + "Após conectar, o <b>peso atualiza em tempo real</b>.</html>"
                : "<html>Selecione fabricante, modelo e porta e toque em <b>Conectar</b>. "
                + "O teste mostra cada <b>tag detectada</b> e <b>quantas vezes</b> ela foi lida.</html>";
        JLabel hint = WorkflowUiTheme.createHintLabel(hintHtml);
        content.add(hint, BorderLayout.NORTH);

        JPanel section = WorkflowUiTheme.createSection(slot.getLabel(), connectionPanel);
        content.add(section, BorderLayout.CENTER);

        ThemedButton btnDone = WorkflowUiTheme.button("Concluído", ThemedButton.Variant.PRIMARY);
        btnDone.addActionListener(e -> beginClose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        south.setOpaque(false);
        south.add(btnDone);
        content.add(south, BorderLayout.SOUTH);

        getContentPane().setBackground(WorkflowUiTheme.BG_PAGE);
        setContentPane(content);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                beginClose();
            }
        });
        WorkflowUiTheme.applyTouchScreenSize(this);
    }

    public void showDialog() {
        connectionPanel.refreshPortsFromOutside();
        connectionPanel.syncFromSession();
        setVisible(true);
    }

    private void beginClose() {
        if (closing) {
            return;
        }
        closing = true;
        connectionPanel.stopLiveReadingAsync(() -> {
            dispose();
            notifyClosed();
        });
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
