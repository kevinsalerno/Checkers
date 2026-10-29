import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Peer-to-peer multiplayer over plain TCP sockets using only the JDK.
 *
 * <p>Line protocol (UTF-8, one message per line):</p>
 * <ul>
 * <li>{@code HELLO &lt;mode&gt;} - sent on connect, announces the game mode.</li>
 * <li>{@code MOVE &lt;notation&gt;} - one applied move; notation is the
 * same coordinate/SAN text the headless harness accepts, except a
 * checkers multi-jump chain is sent as one {@code MOVE} per step so
 * both peers stay in sync through a chain.</li>
 * <li>{@code NEW &lt;mode&gt;} - start a new game of the given mode.</li>
 * <li>{@code RESIGN} - the sender resigns.</li>
 * <li>{@code CHAT &lt;text&gt;} - chat text (single line).</li>
 * </ul>
 *
 * <p>Note on the task's WebRTC wording: a real WebRTC stack (ICE,
 * DTLS-SRTP, SCTP) has no pure Java SE 8 implementation and cannot be
 * built from the JDK alone without external libraries. The constraint
 * "zero third-party dependencies" therefore leaves direct socket
 * peer-to-peer as the honest pure-Java substitute: two peers connect
 * directly, exchange the same move messages a WebRTC data channel
 * would carry, and no server relays gameplay. Use a reachable host
 * (LAN address or forwarded port) to play across networks.</p>
 */
public final class PeerConnection {
    /** Callbacks, invoked on the connection's reader thread. */
    public interface Listener {
        void onConnected(String remote);
        void onMove(String notation);
        void onNewGame(String mode);
        void onResign();
        void onChat(String text);
        void onDisconnected(String reason);
    }

    private final Listener listener;
    private ServerSocket serverSocket;
    private Socket socket;
    private PrintWriter out;
    private volatile boolean closed = false;

    public PeerConnection(Listener listener) {
        this.listener = listener;
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !closed;
    }

    /** Listens on {@code port} and accepts the first peer. */
    public void host(final int port) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(port);
                    Socket accepted = serverSocket.accept();
                    attach(accepted);
                } catch (IOException e) {
                    if (!closed) {
                        listener.onDisconnected("Host failed: " + e.getMessage());
                    }
                }
            }
        }, "p2p-host");
        t.setDaemon(true);
        t.start();
    }

    /** Connects to a peer already hosting on {@code host:port}. */
    public void join(final String host, final int port) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    attach(new Socket(host, port));
                } catch (IOException e) {
                    listener.onDisconnected("Connect failed: " + e.getMessage());
                }
            }
        }, "p2p-join");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void attach(Socket s) throws IOException {
        socket = s;
        out = new PrintWriter(s.getOutputStream(), true);
        listener.onConnected(s.getRemoteSocketAddress().toString());
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "p2p-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                dispatch(line);
            }
            listener.onDisconnected("Peer closed the connection");
        } catch (IOException e) {
            if (!closed) {
                listener.onDisconnected("Connection lost: " + e.getMessage());
            }
        }
    }

    private void dispatch(String line) {
        if (line.startsWith("MOVE ")) {
            listener.onMove(line.substring(5).trim());
        } else if (line.startsWith("NEW ")) {
            listener.onNewGame(line.substring(4).trim());
        } else if (line.equals("RESIGN")) {
            listener.onResign();
        } else if (line.startsWith("CHAT ")) {
            listener.onChat(line.substring(5));
        }
        // HELLO and unknown lines are ignored.
    }

    public synchronized void send(String line) {
        if (out != null) {
            out.println(line);
        }
    }

    public void sendHello(String mode) {
        send("HELLO " + mode);
    }

    public void sendMove(String notation) {
        send("MOVE " + notation);
    }

    public void sendNewGame(String mode) {
        send("NEW " + mode);
    }

    public void sendResign() {
        send("RESIGN");
    }

    public void sendChat(String text) {
        send("CHAT " + text.replace('\n', ' ').replace('\r', ' '));
    }

    public synchronized void close() {
        closed = true;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
