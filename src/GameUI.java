import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

// Generic Swing front-end shared by every game mode: renders the board, handles
// clicks, runs a per-player chess clock, plays sound effects and highlights
// legal moves (assistance). Rules and piece drawing come from the Game.
public class GameUI extends JFrame {
	private static final int CELL = 72;
	private static final int START_TIME = 300; // 5:00 per player

	private Game game;
	private BoardRenderer renderer = new Renderer2D();
	private int selR = -1, selC = -1;
	private boolean started = false;
	private int time1 = START_TIME, time2 = START_TIME; // time1 = team true

	private final Net net;          // null for local (hotseat) play
	private final boolean myTeam;   // which side this client controls when online
	private final boolean online;

	private final BoardPanel panel = new BoardPanel();
	private final JLabel status = new JLabel("", SwingConstants.CENTER);
	private final JLabel label1 = new JLabel();
	private final JLabel label2 = new JLabel();
	private final JCheckBox assist = new JCheckBox("Show legal moves", true);
	private final javax.swing.Timer clock;

	public GameUI(Game game, String title) {
		this(game, title, null);
	}

	public GameUI(Game game, String title, Net net) {
		super(net == null ? title : title + " — you are " + game.teamName(net.hostSide));
		this.game = game;
		this.net = net;
		this.online = net != null;
		this.myTeam = net != null && net.hostSide;
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setLayout(new BorderLayout());

		status.setFont(status.getFont().deriveFont(Font.BOLD, 16f));
		status.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		add(status, BorderLayout.NORTH);
		add(panel, BorderLayout.CENTER);

		JButton newGame = new JButton("New Game");
		newGame.setEnabled(!online); // resetting mid-game would desync the peer
		newGame.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) { newGame(); }
		});
		final JButton view = new JButton("3D view");
		view.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				boolean is3D = renderer instanceof Renderer3D;
				renderer = is3D ? new Renderer2D() : new Renderer3D();
				view.setText(is3D ? "3D view" : "2D view");
				panel.repaint();
			}
		});
		assist.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) { panel.repaint(); }
		});

		JPanel south = new JPanel();
		label1.setFont(label1.getFont().deriveFont(Font.BOLD, 15f));
		label2.setFont(label2.getFont().deriveFont(Font.BOLD, 15f));
		south.add(label1);
		south.add(Box.createHorizontalStrut(20));
		south.add(label2);
		south.add(Box.createHorizontalStrut(20));
		south.add(assist);
		south.add(view);
		south.add(newGame);
		add(south, BorderLayout.SOUTH);

		clock = new javax.swing.Timer(1000, new ActionListener() {
			public void actionPerformed(ActionEvent e) { tick(); }
		});
		clock.start();

		updateLabels();
		pack();
		setLocationRelativeTo(null);

		if (online) {
			net.setListener(new Net.MoveListener() {
				public void onMove(final int fr, final int fc, final int tr, final int tc) {
					SwingUtilities.invokeLater(new Runnable() {
						public void run() { applyRemote(fr, fc, tr, tc); }
					});
				}
				public void onClose() {
					SwingUtilities.invokeLater(new Runnable() {
						public void run() { status.setText("Opponent disconnected"); }
					});
				}
			});
			net.start();
		}
	}

	// Apply a move received from the peer. Both ends run the same deterministic
	// engine, so the four coordinates fully reproduce the move (captures, kinging,
	// multi-jumps, castling, en passant, promotion all follow from applyMove).
	private void applyRemote(int fr, int fc, int tr, int tc) {
		MoveResult r = game.applyMove(fr, fc, tr, tc);
		if (!r.valid) return; // ignore a desynced/illegal peer message
		started = true;
		if (r.promoted) Sound.king();
		else if (r.captured) Sound.capture();
		else Sound.move();
		selR = selC = -1;
		if (game.isOver()) Sound.win();
		updateLabels();
		panel.repaint();
	}

	private void newGame() {
		game = game.fresh();
		selR = selC = -1;
		started = false;
		time1 = time2 = START_TIME;
		updateLabels();
		panel.repaint();
	}

	private void tick() {
		if (!started || game.isOver()) return;
		if (game.toMove()) time1--; else time2--;
		if (time1 <= 0 || time2 <= 0) {
			game.timeout(time1 <= 0); // team-true lost on time -> other side wins
			if (time1 < 0) time1 = 0;
			if (time2 < 0) time2 = 0;
			Sound.win();
		}
		updateLabels();
	}

	private static String fmt(int secs) {
		return String.format("%d:%02d", secs / 60, secs % 60);
	}

	private void updateLabels() {
		label1.setText(game.teamName(true) + "  " + fmt(time1) + "  (" + game.count(true) + ")");
		label2.setText(game.teamName(false) + "  " + fmt(time2) + "  (" + game.count(false) + ")");

		if (game.isOver()) {
			String w = game.winnerTeam() == null ? "Draw" : game.teamName(game.winnerTeam()) + " wins!";
			status.setText("Game over — " + w);
		} else {
			String hint = game.statusHint();
			status.setText(game.teamName(game.toMove()) + " to move" + (hint.length() == 0 ? "" : "  —  " + hint));
		}
	}

	private void handleClick(int row, int col) {
		if (game.isOver()) return;
		if (online && game.toMove() != myTeam) return; // not your turn

		if (selR < 0) {
			trySelect(row, col);
			return;
		}
		if (isLegalDest(row, col)) {
			int fromR = selR, fromC = selC;
			MoveResult r = game.applyMove(fromR, fromC, row, col);
			started = true;
			if (r.promoted) Sound.king();
			else if (r.captured) Sound.capture();
			else Sound.move();
			if (online) net.send(fromR, fromC, row, col);

			if (r.continues) {
				selR = row; selC = col; // keep moving the same piece (checkers multi-jump)
			} else {
				selR = selC = -1;
				if (game.isOver()) Sound.win();
			}
			updateLabels();
			panel.repaint();
		} else if (!trySelect(row, col)) {
			Sound.invalid();
		}
	}

	private static boolean inRange(int r, int c) {
		return r >= 0 && r < 8 && c >= 0 && c < 8;
	}

	// Select the piece at (row,col) if it belongs to the mover and can move.
	private boolean trySelect(int row, int col) {
		if (game.hasPiece(row, col) && game.pieceTeam(row, col) == game.toMove()
				&& !game.legalDestinations(row, col).isEmpty()) {
			selR = row; selC = col;
			panel.repaint();
			return true;
		}
		return false;
	}

	private boolean isLegalDest(int row, int col) {
		for (int[] d : game.legalDestinations(selR, selC))
			if (d[0] == row && d[1] == col) return true;
		return false;
	}

	private class BoardPanel extends JPanel {
		private int lastX, lastY;

		BoardPanel() {
			setPreferredSize(new Dimension(8 * CELL, 8 * CELL));
			setToolTipText("Left-click to move pieces · right-drag to move the light (3D view)");
			MouseAdapter mouse = new MouseAdapter() {
				public void mousePressed(MouseEvent e) {
					if (SwingUtilities.isRightMouseButton(e)) { lastX = e.getX(); lastY = e.getY(); return; }
					int[] rc = renderer.pick(e.getX(), e.getY(), getWidth(), getHeight());
					if (rc != null && inRange(rc[0], rc[1])) handleClick(rc[0], rc[1]);
				}
				public void mouseDragged(MouseEvent e) {
					if (!SwingUtilities.isRightMouseButton(e) || !(renderer instanceof Renderer3D)) return;
					((Renderer3D) renderer).moveLight(e.getX() - lastX, e.getY() - lastY);
					lastX = e.getX(); lastY = e.getY();
					repaint();
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
		}

		protected void paintComponent(Graphics g0) {
			super.paintComponent(g0);
			renderer.paint((Graphics2D) g0, game, selR, selC, assist.isSelected(), getWidth(), getHeight());
		}
	}
}
