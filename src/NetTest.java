import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Loopback check for the multiplayer transport: a move sent by one peer must
// arrive verbatim at the other, and a mode mismatch must be refused. Guards the
// wire protocol that keeps two boards in sync.
public class NetTest {
	public static void main(String[] args) throws Exception {
		int checks = 0, failures = 0;

		final int port = 5099;
		final int[] got = new int[4];
		final CountDownLatch received = new CountDownLatch(1);

		Thread hostThread = new Thread() {
			public void run() {
				try {
					Net host = Net.host(port, "Chess");
					host.setListener(new Net.MoveListener() {
						public void onMove(int fr, int fc, int tr, int tc) {
							got[0] = fr; got[1] = fc; got[2] = tr; got[3] = tc;
							received.countDown();
						}
						public void onClose() { }
					});
					host.start();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		};
		hostThread.setDaemon(true);
		hostThread.start();

		Net client = connectWithRetry("localhost", port, "Chess");
		checks++;
		if (client == null) { failures++; System.out.println("FAIL: client could not connect"); }
		else {
			client.send(6, 4, 4, 4);
			boolean ok = received.await(2, TimeUnit.SECONDS)
					&& got[0] == 6 && got[1] == 4 && got[2] == 4 && got[3] == 4;
			checks++;
			if (!ok) { failures++; System.out.println("FAIL: move did not round-trip (" + str(got) + ")"); }
		}

		// A peer hosting a different game must be rejected at the handshake.
		Thread h2 = new Thread() {
			public void run() { try { Net.host(port + 1, "Chess"); } catch (IOException e) { } }
		};
		h2.setDaemon(true);
		h2.start();

		boolean rejected = false;
		for (int i = 0; i < 30 && !rejected; i++) {
			try {
				Net.join("localhost", port + 1, "Checkers");
				break; // unexpected success
			} catch (IOException e) {
				if (e.getMessage() != null && e.getMessage().contains("not hosting")) rejected = true;
				else Thread.sleep(100); // server not bound yet -> retry
			}
		}
		checks++;
		if (!rejected) { failures++; System.out.println("FAIL: mode mismatch was not rejected"); }

		System.out.println(failures == 0
				? "NetTest: all " + checks + " checks passed"
				: "NetTest: " + failures + "/" + checks + " FAILED");
		if (failures != 0) System.exit(1);
	}

	private static Net connectWithRetry(String host, int port, String mode) throws InterruptedException {
		for (int i = 0; i < 30; i++) {
			try { return Net.join(host, port, mode); }
			catch (IOException e) { Thread.sleep(100); } // wait for the server to bind
		}
		return null;
	}

	private static String str(int[] a) {
		return a[0] + "," + a[1] + "," + a[2] + "," + a[3];
	}
}
