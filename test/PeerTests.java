import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Loopback test for the socket peer-to-peer connection. */
public final class PeerTests {
    private PeerTests() {
    }

    public static int run() {
        TestUtil.reset();
        loopbackExchange();
        return TestUtil.report("PeerTests");
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    private static int freePort() throws Exception {
        ServerSocket s = new ServerSocket(0);
        int port = s.getLocalPort();
        s.close();
        return port;
    }

    private static void loopbackExchange() {
        try {
            final int port = freePort();
            final CountDownLatch connected = new CountDownLatch(2);
            final CountDownLatch moveReceived = new CountDownLatch(1);
            final CountDownLatch chatReceived = new CountDownLatch(1);
            final AtomicReference<String> move = new AtomicReference<String>();
            final AtomicReference<String> chat = new AtomicReference<String>();
            PeerConnection.Listener listener = new PeerConnection.Listener() {
                @Override
                public void onConnected(String remote) {
                    connected.countDown();
                }

                @Override
                public void onMove(String notation) {
                    move.set(notation);
                    moveReceived.countDown();
                }

                @Override
                public void onNewGame(String mode) {
                }

                @Override
                public void onResign() {
                }

                @Override
                public void onChat(String text) {
                    chat.set(text);
                    chatReceived.countDown();
                }

                @Override
                public void onDisconnected(String reason) {
                }
            };
            PeerConnection host = new PeerConnection(listener);
            PeerConnection guest = new PeerConnection(listener);
            host.host(port);
            guest.join("127.0.0.1", port);
            TestUtil.check(connected.await(5, TimeUnit.SECONDS),
                    "peer: host and guest both connect");
            guest.sendMove("e2e4");
            guest.sendChat("hello");
            TestUtil.check(moveReceived.await(5, TimeUnit.SECONDS),
                    "peer: move arrives over the socket");
            TestUtil.eq("e2e4", move.get(), "peer: move notation arrives intact");
            TestUtil.check(chatReceived.await(5, TimeUnit.SECONDS),
                    "peer: chat arrives over the socket");
            TestUtil.eq("hello", chat.get(), "peer: chat text arrives intact");
            TestUtil.check(host.isConnected() && guest.isConnected(),
                    "peer: both ends report connected");
            host.close();
            guest.close();
        } catch (Exception e) {
            TestUtil.check(false, "peer: loopback exchange threw " + e);
        }
    }
}
