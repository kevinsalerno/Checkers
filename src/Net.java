import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

// Peer-to-peer move sync over a plain TCP socket (stdlib only). One player hosts
// (listens), the other joins (connects); moves are newline-delimited text and
// both ends run the same deterministic Game, so only the four coordinates travel.
//
// This is the transport a WebRTC data channel provides — reliable and ordered —
// minus NAT traversal and encryption. ponytail: sockets over a native WebRTC
// stack. For cross-NAT or browser peers, swap this for webrtc-java + a signaling
// server (a real dependency and build step); the GameUI wiring stays the same.
public class Net {
	public interface MoveListener {
		void onMove(int fr, int fc, int tr, int tc); // delivered off the EDT
		void onClose();
	}

	private final Socket socket;
	private final PrintWriter out;
	private final BufferedReader in;
	public final boolean hostSide; // host plays team true (moves first)
	private MoveListener listener;

	private Net(Socket s, boolean hostSide, BufferedReader in) throws IOException {
		this.socket = s;
		this.hostSide = hostSide;
		this.in = in;
		this.out = new PrintWriter(s.getOutputStream(), true);
	}

	// Blocks until an opponent connects. Caller should run this off the EDT.
	public static Net host(int port, String mode) throws IOException {
		ServerSocket server = new ServerSocket(port);
		try {
			Socket s = server.accept();
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
			Net net = new Net(s, true, in);
			net.out.println("GAME " + mode); // handshake: announce the game mode
			return net;
		} finally {
			server.close(); // one game per host
		}
	}

	// Blocks until connected. Caller should run this off the EDT.
	public static Net join(String host, int port, String mode) throws IOException {
		Socket s = new Socket(host, port);
		BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
		String hello = in.readLine();
		if (hello == null || !hello.equals("GAME " + mode)) {
			s.close();
			throw new IOException("Opponent is not hosting " + mode + " (got: " + hello + ")");
		}
		return new Net(s, false, in);
	}

	// Set the listener BEFORE start() so no incoming move is missed.
	public void setListener(MoveListener l) { this.listener = l; }

	public void start() {
		Thread t = new Thread("net-reader") {
			public void run() { readLoop(); }
		};
		t.setDaemon(true);
		t.start();
	}

	private void readLoop() {
		try {
			String line;
			while ((line = in.readLine()) != null) {
				String[] p = line.split(" ");
				if (p.length == 5 && p[0].equals("MOVE") && listener != null)
					listener.onMove(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
							Integer.parseInt(p[3]), Integer.parseInt(p[4]));
				// unknown lines are ignored (forward-compatible)
			}
		} catch (IOException ignored) {
			// socket closed / dropped -> fall through to onClose
		} catch (NumberFormatException ignored) {
			// malformed peer message -> treat as a dropped connection
		}
		if (listener != null) listener.onClose();
	}

	public void send(int fr, int fc, int tr, int tc) {
		out.println("MOVE " + fr + " " + fc + " " + tr + " " + tc);
	}

	public void close() {
		try { socket.close(); } catch (IOException ignored) { }
	}
}
