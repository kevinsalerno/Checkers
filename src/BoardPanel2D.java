import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;

/**
 * Flat 2D board view. Draws legal-move guidance: movable pieces get a
 * green ring, the selected piece a gold ring, quiet destinations a
 * green dot and capture destinations a red ring.
 */
public class BoardPanel2D extends JPanel {
    private static final Color LIGHT_SQ = new Color(0xE8, 0xD0, 0xAA);
    private static final Color DARK_SQ = new Color(0x8B, 0x5A, 0x2B);
    private static final Color WHITE_PIECE = new Color(0xF5, 0xF0, 0xE6);
    private static final Color BLACK_PIECE = new Color(0x2A, 0x1E, 0x16);

    public interface ClickHandler {
        void onSquareClicked(int row, int col);
    }

    private final GameController controller;
    private final ClickHandler handler;

    public BoardPanel2D(GameController controller, ClickHandler handler) {
        this.controller = controller;
        this.handler = handler;
        setPreferredSize(new Dimension(520, 520));
        setBackground(Color.DARK_GRAY);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int[] sq = squareAt(e.getX(), e.getY());
                if (sq != null) {
                    handler.onSquareClicked(sq[0], sq[1]);
                }
            }
        });
        controller.addChangeListener(new GameController.ChangeListener() {
            @Override
            public void onChange() {
                repaint();
            }
        });
    }

    private int[] squareAt(int x, int y) {
        int size = Math.min(getWidth(), getHeight());
        int cell = size / 8;
        if (cell <= 0) {
            return null;
        }
        int col = x / cell;
        int row = y / cell;
        if (row < 0 || row > 7 || col < 0 || col > 7) {
            return null;
        }
        return new int[] { row, col };
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        int size = Math.min(getWidth(), getHeight());
        int cell = size / 8;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                g2.setColor((r + c) % 2 == 0 ? LIGHT_SQ : DARK_SQ);
                g2.fillRect(c * cell, r * cell, cell, cell);
            }
        }
        // File/rank labels.
        g2.setColor(new Color(0, 0, 0, 120));
        g2.setFont(new Font("SansSerif", Font.PLAIN, Math.max(10, cell / 5)));
        for (int c = 0; c < 8; c++) {
            g2.drawString(String.valueOf((char) ('a' + c)), c * cell + 3, size - 3);
        }
        for (int r = 0; r < 8; r++) {
            g2.drawString(String.valueOf(8 - r), 3, r * cell + 14);
        }
        // Movable-piece rings.
        g2.setStroke(new BasicStroke(3));
        g2.setColor(new Color(0x2E, 0xA8, 0x3A));
        for (int[] sq : controller.movableSquares()) {
            g2.drawOval(sq[1] * cell + 3, sq[0] * cell + 3, cell - 6, cell - 6);
        }
        // Selection ring.
        if (controller.hasSelection()) {
            g2.setColor(new Color(0xFF, 0xC8, 0x00));
            g2.setStroke(new BasicStroke(4));
            g2.drawRect(controller.selectedCol() * cell + 2,
                    controller.selectedRow() * cell + 2, cell - 4, cell - 4);
        }
        // Destination markers.
        for (int[] dest : controller.selectedDestinations()) {
            int cx = dest[1] * cell + cell / 2;
            int cy = dest[0] * cell + cell / 2;
            if (dest[2] == 1) {
                g2.setColor(new Color(0xD0, 0x20, 0x20));
                g2.setStroke(new BasicStroke(4));
                g2.drawOval(cx - cell / 3, cy - cell / 3, 2 * cell / 3, 2 * cell / 3);
            } else {
                g2.setColor(new Color(0x2E, 0xA8, 0x3A, 200));
                g2.fillOval(cx - cell / 8, cy - cell / 8, cell / 4, cell / 4);
            }
        }
        // Pieces.
        if (controller.mode() == GameController.Mode.CHECKERS) {
            CheckersEngine e = controller.checkers();
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    int p = e.pieceAt(r, c);
                    if (p != CheckersEngine.EMPTY) {
                        drawChecker(g2, r, c, cell,
                                CheckersEngine.sideOf(p) == Side.WHITE,
                                CheckersEngine.isKing(p));
                    }
                }
            }
        } else {
            ChessEngine e = controller.chess();
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    int p = e.pieceAt(r, c);
                    if (p != ChessPiece.EMPTY) {
                        drawChessPiece(g2, r, c, cell, p);
                    }
                }
            }
        }
        // Check highlight on the king's square.
        if (controller.mode() == GameController.Mode.CHESS && controller.chess().inCheck()) {
            g2.setColor(new Color(0xD0, 0x20, 0x20, 90));
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) {
                    if (controller.chess().pieceAt(r, c)
                            == ChessPiece.of(ChessPiece.KING, controller.sideToMove())) {
                        g2.fillRect(c * cell, r * cell, cell, cell);
                    }
                }
            }
        }
        g2.dispose();
    }

    private void drawChecker(Graphics2D g2, int row, int col, int cell,
                             boolean white, boolean king) {
        int cx = col * cell + cell / 2;
        int cy = row * cell + cell / 2;
        int rad = cell * 3 / 8;
        g2.setColor(Color.BLACK);
        g2.fillOval(cx - rad, cy - rad + 2, 2 * rad, 2 * rad);
        g2.setColor(white ? WHITE_PIECE : BLACK_PIECE);
        g2.fillOval(cx - rad, cy - rad, 2 * rad, 2 * rad);
        g2.setColor(white ? new Color(0xB0, 0xA8, 0x98) : new Color(0x55, 0x42, 0x30));
        g2.setStroke(new BasicStroke(2));
        g2.drawOval(cx - rad * 2 / 3, cy - rad * 2 / 3, 4 * rad / 3, 4 * rad / 3);
        if (king) {
            g2.setColor(white ? new Color(0xB8, 0x8A, 0x00) : new Color(0xFF, 0xD7, 0x00));
            g2.setFont(new Font("SansSerif", Font.BOLD, cell / 3));
            String crown = "\u265B";
            int w = g2.getFontMetrics().stringWidth(crown);
            g2.drawString(crown, cx - w / 2, cy + cell / 9);
        }
    }

    private void drawChessPiece(Graphics2D g2, int row, int col, int cell, int piece) {
        int cx = col * cell + cell / 2;
        int cy = row * cell + cell / 2;
        int rad = cell * 3 / 8;
        boolean white = ChessPiece.sideOf(piece) == Side.WHITE;
        g2.setColor(white ? WHITE_PIECE : BLACK_PIECE);
        g2.fillOval(cx - rad, cy - rad, 2 * rad, 2 * rad);
        g2.setColor(white ? Color.BLACK : Color.WHITE);
        g2.setStroke(new BasicStroke(2));
        g2.drawOval(cx - rad, cy - rad, 2 * rad, 2 * rad);
        g2.setFont(new Font("Serif", Font.BOLD, cell / 2));
        String letter;
        switch (ChessPiece.typeOf(piece)) {
            case ChessPiece.PAWN: letter = "P"; break;
            case ChessPiece.KNIGHT: letter = "N"; break;
            case ChessPiece.BISHOP: letter = "B"; break;
            case ChessPiece.ROOK: letter = "R"; break;
            case ChessPiece.QUEEN: letter = "Q"; break;
            default: letter = "K"; break;
        }
        int w = g2.getFontMetrics().stringWidth(letter);
        int h = g2.getFontMetrics().getAscent();
        g2.drawString(letter, cx - w / 2, cy + h / 2 - 2);
    }
}
