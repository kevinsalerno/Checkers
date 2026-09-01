// Self-check for the software 3D renderer: the camera projection and the click
// picker must be exact inverses. If pick(center-of-tile) ever returns the wrong
// square, clicking the 3D board would move the wrong piece — so this guards it.
public class Render3DTest {
	private static int checks = 0, failures = 0;

	private static void ok(boolean cond, String msg) {
		checks++;
		if (!cond) { failures++; System.out.println("FAIL: " + msg); }
	}

	public static void main(String[] args) {
		Renderer3D r = new Renderer3D();

		// Round-trip every tile at a few panel sizes (incl. non-square / small).
		int[][] sizes = {{576, 576}, {800, 640}, {1000, 700}, {320, 400}};
		for (int[] s : sizes) {
			int w = s[0], h = s[1];
			for (int row = 0; row < 8; row++)
				for (int col = 0; col < 8; col++) {
					int[] px = r.tileCenterScreen(row, col, w, h);
					int[] rc = r.pick(px[0], px[1], w, h);
					ok(rc != null && rc[0] == row && rc[1] == col,
						"pick(center " + row + "," + col + ") @ " + w + "x" + h
							+ " -> " + (rc == null ? "null" : rc[0] + "," + col));
				}
		}

		// Depth ordering: the far edge (row 0) must sit higher on screen than the
		// near edge (row 7) — otherwise the tilt/painter's order is inverted.
		int[] far = r.tileCenterScreen(0, 3, 640, 640);
		int[] near = r.tileCenterScreen(7, 3, 640, 640);
		ok(far[1] < near[1], "far row should render above near row");

		// Clicks off the board return null (don't crash / pick a phantom tile).
		ok(r.pick(-50, -50, 640, 640) == null, "off-board top-left -> null");
		ok(r.pick(10_000, 10_000, 640, 640) == null, "off-board bottom-right -> null");

		// Shadow ray-to-plane: overhead light -> shadow directly under the piece.
		double[] sOver = Renderer3D.groundShadow(2, 3, 1, new double[]{2, 3, 100});
		ok(Math.abs(sOver[0] - 2) < 0.05 && Math.abs(sOver[1] - 3) < 0.05,
			"overhead light casts shadow under the piece");

		// Side light -> shadow falls to the opposite side; lower light -> longer.
		double[] sSide = Renderer3D.groundShadow(0, 3, 1, new double[]{10, 3, 10});
		double[] sLow = Renderer3D.groundShadow(0, 3, 1, new double[]{10, 3, 4});
		ok(sSide[0] < 0, "shadow falls opposite the light");
		ok(Math.abs(sLow[0]) > Math.abs(sSide[0]), "lower light casts a longer shadow");
		ok(Renderer3D.groundShadow(0, 0, 5, new double[]{0, 0, 5}) == null,
			"light at piece height casts no ground shadow");

		// Convex hull drops interior points (cylinder-silhouette correctness).
		double[][] hull = Renderer3D.convexHull(new double[][]{{0,0},{2,0},{2,2},{0,2},{1,1}});
		ok(hull.length == 4, "hull keeps 4 corners, drops interior (got " + hull.length + ")");

		// Paint smoke: every renderer must draw every game (fresh + mid-selection)
		// to an offscreen image without throwing.
		BoardRenderer[] renderers = {new Renderer2D(), new Renderer3D()};
		Game[] games = {new Board(), new ChessBoard()};
		java.awt.image.BufferedImage img =
			new java.awt.image.BufferedImage(640, 640, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		for (BoardRenderer rr : renderers)
			for (Game g : games) {
				boolean threw = false;
				try {
					java.awt.Graphics2D g2 = img.createGraphics();
					rr.paint(g2, g, -1, -1, true, 640, 640);   // nothing selected
					rr.paint(g2, g, 5, 0, true, 640, 640);      // a piece selected
					g2.dispose();
				} catch (RuntimeException ex) {
					threw = true;
				}
				ok(!threw, rr.getClass().getSimpleName() + " paints " + g.getClass().getSimpleName());
			}

		System.out.println(failures == 0
			? "Render3DTest: all " + checks + " checks passed"
			: "Render3DTest: " + failures + "/" + checks + " FAILED");
		if (failures != 0) System.exit(1);
	}
}
