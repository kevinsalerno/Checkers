import java.awt.Frame;
import java.io.IOException;

import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public class Main {
	public static void main(String[] args) {
		SwingUtilities.invokeLater(new Runnable() {
			public void run() { start(); }
		});
	}

	private static void start() {
		String[] games = {"Checkers", "Chess"};
		int g = JOptionPane.showOptionDialog(null, "Choose a game", "Board Games",
				JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, games, games[0]);
		if (g < 0) return;
		final String mode = games[g];

		String[] how = {"Local (hotseat)", "Host online", "Join online"};
		int m = JOptionPane.showOptionDialog(null, "How do you want to play?", mode,
				JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, how, how[0]);
		if (m < 0) return;

		if (m == 0) { launch(mode, null); return; }

		if (m == 1) {
			String s = JOptionPane.showInputDialog(null, "Port to host on:", "5000");
			if (s == null) return;
			final int port = parsePort(s);
			if (port < 0) { error("Invalid port: " + s); return; }
			connect("Waiting for opponent on port " + port + " …", mode, new Connector() {
				public Net open() throws IOException { return Net.host(port, mode); }
			});
		} else {
			String s = JOptionPane.showInputDialog(null, "Connect to (host:port):", "localhost:5000");
			if (s == null) return;
			String[] hp = s.split(":");
			if (hp.length != 2 || parsePort(hp[1]) < 0) { error("Invalid address: " + s); return; }
			final String host = hp[0];
			final int port = parsePort(hp[1]);
			connect("Connecting to " + host + ":" + port + " …", mode, new Connector() {
				public Net open() throws IOException { return Net.join(host, port, mode); }
			});
		}
	}

	private interface Connector { Net open() throws IOException; }

	// Run a blocking host()/join() off the EDT behind a modal "please wait" dialog.
	// The dialog's nested event loop keeps pumping, so the worker's invokeLater
	// (which disposes it and opens the game) still runs.
	private static void connect(String message, final String mode, final Connector c) {
		final JDialog wait = new JDialog((Frame) null, "Networking", true);
		wait.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		wait.add(new JLabel("   " + message + "   "));
		wait.pack();
		wait.setLocationRelativeTo(null);

		Thread t = new Thread("net-connect") {
			public void run() {
				try {
					final Net net = c.open();
					SwingUtilities.invokeLater(new Runnable() {
						public void run() { wait.dispose(); launch(mode, net); }
					});
				} catch (final IOException ex) {
					SwingUtilities.invokeLater(new Runnable() {
						public void run() { wait.dispose(); error(ex.getMessage()); }
					});
				}
			}
		};
		t.setDaemon(true); // let the JVM exit if the user closes the wait dialog
		t.start();
		wait.setVisible(true);
	}

	private static void launch(String mode, Net net) {
		Game game = mode.equals("Chess") ? new ChessBoard() : new Board();
		new GameUI(game, mode, net).setVisible(true);
	}

	private static int parsePort(String s) {
		try {
			int p = Integer.parseInt(s.trim());
			return (p >= 1 && p <= 65535) ? p : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static void error(String msg) {
		JOptionPane.showMessageDialog(null, msg, "Connection failed", JOptionPane.ERROR_MESSAGE);
	}
}
