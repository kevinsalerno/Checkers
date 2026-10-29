import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/**
 * Swing UI: mode and view switching, legal-move guidance (in both
 * views), per-player countdown timers, synthesised sounds, and
 * peer-to-peer multiplayer. All rules live in {@link GameController}
 * and the engines; this class only wires controls to it.
 */
public class GameUI extends JFrame {
    private final GameController controller = new GameController();
    private final CardLayout cards = new CardLayout();
    private final JPanel centerPanel = new JPanel(cards);
    private BoardPanel2D panel2d;
    private RayTracedPanel panel3d;
    private final JLabel statusLabel = new JLabel();
    private final JLabel whiteTimerLabel = new JLabel();
    private final JLabel blackTimerLabel = new JLabel();
    private final JTextArea moveList = new JTextArea(12, 22);
    private final JTextArea chatLog = new JTextArea(5, 22);
    private final JLabel netStatus = new JLabel("Local game (2 players)");
    private PeerConnection peer;
    private final JComboBox<String> modeBox = new JComboBox<String>(
            new String[] { "Checkers", "Chess" });
    private final JComboBox<String> viewBox = new JComboBox<String>(
            new String[] { "3D Ray Traced", "2D Board" });
    // Initial values match SceneBuilder.defaultLight() (azimuth ~56, elevation ~61).
    private final JSlider lightAzimuth = new JSlider(-180, 180, 56);
    private final JSlider lightElevation = new JSlider(12, 85, 61);
    private boolean gameOverAnnounced = false;
    private Timer clockTimer;

    public GameUI() {
        super("Checkers & Chess - Ray Traced");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        buildToolbar();
        buildCenter();
        buildSidePanel();
        pack();
        setLocationRelativeTo(null);
        controller.addChangeListener(new GameController.ChangeListener() {
            @Override
            public void onChange() {
                refresh();
            }
        });
        startClock();
        refresh();
    }

    private void buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(new JLabel("Mode:"));
        bar.add(modeBox);
        bar.add(new JLabel("View:"));
        bar.add(viewBox);
        JButton newGame = new JButton("New Game");
        newGame.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startNewGame(selectedMode());
            }
        });
        bar.add(newGame);
        JButton resign = new JButton("Resign");
        resign.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                doResign();
            }
        });
        bar.add(resign);
        bar.add(new JLabel("Clock (min):"));
        final JSpinner minutes = new JSpinner(new SpinnerNumberModel(10, 1, 60, 1));
        minutes.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                controller.setClockMinutes((Integer) minutes.getValue());
                refresh();
            }
        });
        bar.add(minutes);
        JCheckBox sound = new JCheckBox("Sound", true);
        sound.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                SoundEffects.setEnabled(((JCheckBox) e.getSource()).isSelected());
            }
        });
        bar.add(sound);
        JCheckBox hq = new JCheckBox("High quality 3D", true);
        hq.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                panel3d.setHighQuality(((JCheckBox) e.getSource()).isSelected());
            }
        });
        bar.add(hq);
        modeBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startNewGame(selectedMode());
            }
        });
        viewBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showSelectedView();
            }
        });
        add(bar, BorderLayout.NORTH);
    }

    private GameController.Mode selectedMode() {
        return modeBox.getSelectedIndex() == 0
                ? GameController.Mode.CHECKERS : GameController.Mode.CHESS;
    }

    private void buildCenter() {
        BoardPanel2D.ClickHandler clickHandler = new BoardPanel2D.ClickHandler() {
            @Override
            public void onSquareClicked(int row, int col) {
                handleSquareClick(row, col);
            }
        };
        panel2d = new BoardPanel2D(controller, clickHandler);
        panel3d = new RayTracedPanel(controller, new RayTracedPanel.ClickHandler() {
            @Override
            public void onSquareClicked(int row, int col) {
                handleSquareClick(row, col);
            }
        });
        centerPanel.add(panel3d, "3d");
        centerPanel.add(panel2d, "2d");
        add(centerPanel, BorderLayout.CENTER);
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        statusBar.add(statusLabel);
        statusBar.add(new JLabel("  White:"));
        statusBar.add(whiteTimerLabel);
        statusBar.add(new JLabel("  Black:"));
        statusBar.add(blackTimerLabel);
        add(statusBar, BorderLayout.SOUTH);
    }

    private void buildSidePanel() {
        JPanel side = new JPanel(new BorderLayout());
        side.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JPanel lightPanel = new JPanel(new GridLayout(0, 1));
        lightPanel.setBorder(BorderFactory.createTitledBorder("Light"));
        lightPanel.add(new JLabel("Azimuth"));
        lightAzimuth.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                updateLightFromSliders();
            }
        });
        lightPanel.add(lightAzimuth);
        lightPanel.add(new JLabel("Elevation"));
        lightElevation.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                updateLightFromSliders();
            }
        });
        lightPanel.add(lightElevation);
        JButton resetView = new JButton("Reset camera / light");
        resetView.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                panel3d.resetView();
                lightAzimuth.setValue(56);
                lightElevation.setValue(61);
            }
        });
        lightPanel.add(resetView);
        side.add(lightPanel, BorderLayout.NORTH);
        moveList.setEditable(false);
        JScrollPane moves = new JScrollPane(moveList);
        moves.setBorder(BorderFactory.createTitledBorder("Moves"));
        side.add(moves, BorderLayout.CENTER);
        JPanel net = new JPanel(new GridLayout(0, 1));
        net.setBorder(BorderFactory.createTitledBorder("Multiplayer (direct P2P)"));
        net.add(netStatus);
        JPanel hostRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        final JTextField portField = new JTextField("50001", 6);
        JButton hostBtn = new JButton("Host");
        hostBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                hostGame(parsePort(portField.getText()));
            }
        });
        hostRow.add(new JLabel("Port:"));
        hostRow.add(portField);
        hostRow.add(hostBtn);
        net.add(hostRow);
        JPanel joinRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        final JTextField hostField = new JTextField("localhost", 10);
        final JTextField joinPortField = new JTextField("50001", 6);
        JButton joinBtn = new JButton("Join");
        joinBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                joinGame(hostField.getText(), parsePort(joinPortField.getText()));
            }
        });
        joinRow.add(hostField);
        joinRow.add(joinPortField);
        joinRow.add(joinBtn);
        net.add(joinRow);
        JButton disconnectBtn = new JButton("Disconnect");
        disconnectBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                disconnect();
            }
        });
        net.add(disconnectBtn);
        chatLog.setEditable(false);
        JScrollPane chatScroll = new JScrollPane(chatLog);
        chatScroll.setPreferredSize(new Dimension(220, 80));
        net.add(chatScroll);
        JPanel chatRow = new JPanel(new BorderLayout());
        final JTextField chatField = new JTextField();
        JButton chatSend = new JButton("Send");
        ActionListener sendChat = new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String text = chatField.getText().trim();
                if (!text.isEmpty()) {
                    chatLog.append("You: " + text + "\n");
                    if (peer != null) {
                        peer.sendChat(text);
                    }
                    chatField.setText("");
                }
            }
        };
        chatSend.addActionListener(sendChat);
        chatField.addActionListener(sendChat);
        chatRow.add(chatField, BorderLayout.CENTER);
        chatRow.add(chatSend, BorderLayout.EAST);
        net.add(chatRow);
        side.add(net, BorderLayout.SOUTH);
        add(side, BorderLayout.EAST);
    }

    private int parsePort(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 50001;
        }
    }

    private void updateLightFromSliders() {
        double az = lightAzimuth.getValue();
        double el = lightElevation.getValue();
        AreaLight current = panel3d.light();
        panel3d.setLight(current.moved(az, el, current.distance()));
    }

    private void showSelectedView() {
        boolean is3d = viewBox.getSelectedIndex() == 0;
        cards.show(centerPanel, is3d ? "3d" : "2d");
        if (is3d) {
            panel3d.start();
        }
    }

    private void startNewGame(GameController.Mode mode) {
        gameOverAnnounced = false;
        controller.newGame(mode);
        if (peer != null && peer.isConnected()) {
            peer.sendNewGame(mode == GameController.Mode.CHECKERS ? "CHECKERS" : "CHESS");
        }
        showSelectedView();
        refresh();
    }

    private void startClock() {
        clockTimer = new Timer(200, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                controller.tick(200);
                refreshTimers();
                if (controller.isGameOver() && !gameOverAnnounced) {
                    gameOverAnnounced = true;
                    announceGameOver();
                }
            }
        });
        clockTimer.start();
    }

    private void refreshTimers() {
        whiteTimerLabel.setText(formatTime(controller.remainingMs(Side.WHITE)));
        blackTimerLabel.setText(formatTime(controller.remainingMs(Side.BLACK)));
    }

    private static String formatTime(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private void handleSquareClick(int row, int col) {
        GameController.ClickResult result = controller.click(row, col);
        if (result.outcome == GameController.ClickOutcome.MOVED) {
            if (result.givesCheck) {
                SoundEffects.playCheck();
            } else if (result.wasCapture) {
                SoundEffects.playCapture();
            } else {
                SoundEffects.playMove();
            }
            if (peer != null && peer.isConnected() && result.notation != null) {
                peer.sendMove(result.notation);
            }
            if (controller.isGameOver() && !gameOverAnnounced) {
                gameOverAnnounced = true;
                announceGameOver();
            }
        } else if (result.outcome == GameController.ClickOutcome.NEED_PROMOTION) {
            askPromotion();
        } else if (result.outcome == GameController.ClickOutcome.ILLEGAL) {
            SoundEffects.playIllegal();
        }
        refresh();
    }

    private void askPromotion() {
        String[] options = { "Queen", "Rook", "Bishop", "Knight" };
        int choice = JOptionPane.showOptionDialog(this,
                "Promote the pawn to:", "Promotion",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        int[] types = { ChessPiece.QUEEN, ChessPiece.ROOK,
                ChessPiece.BISHOP, ChessPiece.KNIGHT };
        if (choice < 0) {
            controller.cancelPromotion();
            return;
        }
        GameController.ClickResult result = controller.applyPromotion(types[choice]);
        if (result.outcome == GameController.ClickOutcome.MOVED) {
            if (result.givesCheck) {
                SoundEffects.playCheck();
            } else if (result.wasCapture) {
                SoundEffects.playCapture();
            } else {
                SoundEffects.playMove();
            }
            if (peer != null && peer.isConnected() && result.notation != null) {
                peer.sendMove(result.notation);
            }
        }
        refresh();
    }

    private void doResign() {
        if (controller.isGameOver()) {
            return;
        }
        controller.resign(controller.sideToMove().opponent());
        if (peer != null && peer.isConnected()) {
            peer.sendResign();
        }
        SoundEffects.playDraw();
        gameOverAnnounced = true;
        announceGameOver();
        refresh();
    }

    private void announceGameOver() {
        String text = controller.outcomeText();
        if (text.startsWith("Draw")) {
            SoundEffects.playDraw();
        } else {
            SoundEffects.playWin();
        }
        JOptionPane.showMessageDialog(this, text, "Game over",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void refresh() {
        statusLabel.setText(controller.statusText());
        refreshTimers();
        StringBuilder sb = new StringBuilder();
        java.util.List<String> history = controller.moveHistory();
        for (int i = 0; i < history.size(); i++) {
            if (controller.mode() == GameController.Mode.CHESS && i % 2 == 0) {
                sb.append((i / 2 + 1)).append(". ");
            }
            sb.append(history.get(i)).append(' ');
            if (controller.mode() == GameController.Mode.CHESS && i % 2 == 1) {
                sb.append('\n');
            }
            if (controller.mode() == GameController.Mode.CHECKERS && i % 2 == 1) {
                sb.append('\n');
            }
        }
        moveList.setText(sb.toString());
        moveList.setCaretPosition(moveList.getDocument().getLength());
    }

    // ----- Multiplayer -----

    private PeerConnection.Listener peerListener(final boolean isHost) {
        return new PeerConnection.Listener() {
            @Override
            public void onConnected(final String remote) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        netStatus.setText("Connected: " + remote
                                + (isHost ? " (you host)" : " (you joined)"));
                        // Host takes the first-moving side.
                        Side local = controller.mode() == GameController.Mode.CHECKERS
                                ? (isHost ? Side.BLACK : Side.WHITE)
                                : (isHost ? Side.WHITE : Side.BLACK);
                        controller.setLocalSide(local, isHost);
                        if (peer != null) {
                            peer.sendHello(controller.mode() == GameController.Mode.CHECKERS
                                    ? "CHECKERS" : "CHESS");
                        }
                        refresh();
                    }
                });
            }

            @Override
            public void onMove(final String notation) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        if (!controller.applyRemoteMove(notation)) {
                            netStatus.setText("Rejected remote move: " + notation
                                    + " (" + controller.lastError() + ")");
                        }
                        refresh();
                    }
                });
            }

            @Override
            public void onNewGame(final String mode) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        gameOverAnnounced = false;
                        controller.newGame("CHESS".equalsIgnoreCase(mode)
                                ? GameController.Mode.CHESS : GameController.Mode.CHECKERS);
                        modeBox.setSelectedIndex(controller.mode()
                                == GameController.Mode.CHECKERS ? 0 : 1);
                        refresh();
                    }
                });
            }

            @Override
            public void onResign() {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        // The peer resigned, so the local side wins
                        // whatever the turn state is.
                        Side local = controller.localSide();
                        controller.resign(local == null
                                ? controller.sideToMove().opponent() : local);
                        gameOverAnnounced = true;
                        announceGameOver();
                        refresh();
                    }
                });
            }

            @Override
            public void onChat(final String text) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        chatLog.append("Peer: " + text + "\n");
                    }
                });
            }

            @Override
            public void onDisconnected(final String reason) {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        netStatus.setText("Disconnected: " + reason);
                        controller.clearLocalSide();
                        refresh();
                    }
                });
            }
        };
    }

    private void hostGame(int port) {
        disconnect();
        peer = new PeerConnection(peerListener(true));
        netStatus.setText("Hosting on port " + port + " - waiting for a peer...");
        peer.host(port);
    }

    private void joinGame(String host, int port) {
        disconnect();
        peer = new PeerConnection(peerListener(false));
        netStatus.setText("Connecting to " + host + ":" + port + "...");
        peer.join(host, port);
    }

    private void disconnect() {
        if (peer != null) {
            peer.close();
            peer = null;
        }
        controller.clearLocalSide();
        netStatus.setText("Local game (2 players)");
    }

    /** Shows the UI. Called on the event-dispatch thread by Main. */
    public void showUI() {
        setVisible(true);
        panel3d.start();
    }
}

