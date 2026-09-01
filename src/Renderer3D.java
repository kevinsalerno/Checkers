import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

// Dependency-free software 3D: the 8x8 board is a plane at z=0 viewed from a
// tilted perspective camera; pieces are shaded 3D tokens standing on it. Clicks
// are mapped back to tiles by hit-testing the projected tile quads, so picking
// stays exact regardless of the camera.
public class Renderer3D implements BoardRenderer {
	private static final double PITCH = 0.82;   // camera tilt below horizontal (rad)
	private static final double BACK = 6.0;     // camera set-back behind near edge
	private static final double HEIGHT = 8.0;   // camera height above the board
	private static final double COS = Math.cos(PITCH), SIN = Math.sin(PITCH);

	private static final double LR = 6.0;              // radius of the light's orbit
	private static final double AMBIENT = 0.35, DIFFUSE = 0.65;

	// Player-controlled point light (moved by right-drag). Shadows and surface
	// shading are recomputed from it every paint, so it responds in real time.
	private double lightAz = -0.7;
	private double lightH = 9.0;

	private static final Color LIGHT = new Color(235, 210, 170);
	private static final Color DARK = new Color(150, 105, 70);
	private static final Color SEL = new Color(255, 235, 59);
	private static final Color DEST = new Color(40, 190, 90, 150);
	private static final Color MOVABLE = new Color(0, 160, 220);
	private static final Color FORCED = new Color(230, 130, 0);
	private static final Color RED = new Color(200, 55, 55);
	private static final Color CHK_BLACK = new Color(55, 55, 60);
	private static final Color IVORY = new Color(235, 225, 200);
	private static final Color CHESS_BLACK = new Color(60, 60, 66);

	// Pitched perspective camera set back and above the near edge, looking down at
	// the board (x=right, y=forward/away, z=up). Fitted to the panel; paint and
	// pick recompute it identically so screen<->tile stays an exact round-trip.
	private static final class Proj {
		double focal, cx, cy;

		static double[] cam(double x, double y, double z) {
			double dy = y + BACK, dz = z - HEIGHT;
			double yc = dy * SIN + dz * COS;   // camera-up
			double zc = dy * COS - dz * SIN;   // depth (into screen)
			return new double[]{x, yc, zc};
		}

		double[] project(double x, double y, double z) {
			double[] c = cam(x, y, z);
			double s = focal / c[2];
			return new double[]{cx + c[0] * s, cy - c[1] * s, c[2]}; // sx, sy, depth
		}

		static Proj fit(int w, int h) {
			double minU = 1e9, maxU = -1e9, minV = 1e9, maxV = -1e9;
			for (int sx = -4; sx <= 4; sx += 8)
				for (int sy = -4; sy <= 4; sy += 8)
					for (double sz = 0; sz <= 1.1; sz += 1.1) { // include tall pieces
						double[] c = cam(sx, sy, sz);
						double u = c[0] / c[2], v = c[1] / c[2];
						minU = Math.min(minU, u); maxU = Math.max(maxU, u);
						minV = Math.min(minV, v); maxV = Math.max(maxV, v);
					}
			Proj p = new Proj();
			p.focal = Math.min(0.92 * w / (maxU - minU), 0.92 * h / (maxV - minV));
			p.cx = w / 2.0 - p.focal * (minU + maxU) / 2;
			p.cy = h / 2.0 + p.focal * (minV + maxV) / 2;
			return p;
		}
	}

	// Board layout: col 0 = left, row 0 = far (top), row 7 = near (bottom).
	private static double wx(int col) { return col - 3.5; }
	private static double wy(int row) { return 3.5 - row; }

	// Screen polygon of a tile's four corners at z=0.
	private static double[][] tileQuad(Proj p, int r, int c) {
		return new double[][]{
			p.project(c - 4, 4 - r, 0), p.project(c - 3, 4 - r, 0),
			p.project(c - 3, 3 - r, 0), p.project(c - 4, 3 - r, 0)
		};
	}

	// Screen pixel at the center of tile (r,c); inverse of pick(). Used by the
	// projection round-trip self-check.
	public int[] tileCenterScreen(int r, int c, int w, int h) {
		double[] s = Proj.fit(w, h).project(wx(c), wy(r), 0);
		return new int[]{(int) Math.round(s[0]), (int) Math.round(s[1])};
	}

	// Move the light: horizontal drag orbits it, vertical drag raises/lowers it
	// (higher = shorter shadows). Called live from GameUI on a right-drag.
	public void moveLight(int dx, int dy) {
		lightAz += dx * 0.012;
		lightH = Math.max(2.0, Math.min(20.0, lightH - dy * 0.04));
	}

	private double[] light() {
		return new double[]{LR * Math.cos(lightAz), LR * Math.sin(lightAz), lightH};
	}

	// Where the ray from light L through world point P lands on the board plane
	// z=0. Null when the light is at/below the point (nothing to cast downward).
	static double[] groundShadow(double px, double py, double pz, double[] L) {
		double denom = L[2] - pz;
		if (denom <= 1e-6) return null;
		double s = L[2] / denom;
		return new double[]{L[0] + s * (px - L[0]), L[1] + s * (py - L[1])};
	}

	// Andrew's monotone chain. Input {x,y} points; returns hull vertices in order.
	static double[][] convexHull(double[][] pts) {
		int n = pts.length;
		if (n < 3) return pts;
		double[][] p = pts.clone();
		java.util.Arrays.sort(p, new java.util.Comparator<double[]>() {
			public int compare(double[] a, double[] b) {
				return a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]);
			}
		});
		double[][] h = new double[2 * n][];
		int k = 0;
		for (int i = 0; i < n; i++) {
			while (k >= 2 && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
			h[k++] = p[i];
		}
		for (int i = n - 2, lower = k + 1; i >= 0; i--) {
			while (k >= lower && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
			h[k++] = p[i];
		}
		double[][] out = new double[k - 1][];
		System.arraycopy(h, 0, out, 0, k - 1);
		return out;
	}

	private static double cross(double[] o, double[] a, double[] b) {
		return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
	}

	public int[] pick(int mx, int my, int w, int h) {
		Proj p = Proj.fit(w, h);
		int[] best = null;
		double bestDepth = 1e9;
		for (int r = 0; r < 8; r++)
			for (int c = 0; c < 8; c++) {
				double[][] q = tileQuad(p, r, c);
				if (contains(q, mx, my)) {
					double depth = (q[0][2] + q[2][2]) / 2;
					if (depth < bestDepth) { bestDepth = depth; best = new int[]{r, c}; }
				}
			}
		return best;
	}

	private static boolean contains(double[][] poly, double x, double y) {
		boolean in = false;
		for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
			double xi = poly[i][0], yi = poly[i][1], xj = poly[j][0], yj = poly[j][1];
			if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
		}
		return in;
	}

	public void paint(Graphics2D g, Game game, int selR, int selC, boolean assist, int w, int h) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(new Color(30, 32, 38));
		g.fillRect(0, 0, w, h);
		Proj p = Proj.fit(w, h);
		double[] L = light();

		boolean forced = game.statusHint().length() != 0;

		// Tiles + highlights, far rows first (row 0 is farthest).
		for (int r = 0; r < 8; r++)
			for (int c = 0; c < 8; c++) {
				double[][] q = tileQuad(p, r, c);
				fillQuad(g, q, (r + c) % 2 == 0 ? LIGHT : DARK);
				g.setColor(new Color(0, 0, 0, 40));
				drawQuad(g, q, 1f);

				if (assist && selR < 0 && !game.isOver() && game.hasPiece(r, c)
						&& game.pieceTeam(r, c) == game.toMove() && !game.legalDestinations(r, c).isEmpty()) {
					g.setColor(forced ? FORCED : MOVABLE);
					drawQuad(g, q, 3f);
				}
			}

		// Cast shadows on the board, far rows first (they lie under everything).
		for (int r = 0; r < 8; r++)
			for (int c = 0; c < 8; c++)
				if (game.hasPiece(r, c))
					drawShadow(g, p, r, c, game.pieceKind(r, c), L);

		// Selection + legal destinations, drawn on top of the base tiles.
		if (selR >= 0) {
			fillQuad(g, tileQuad(p, selR, selC), new Color(255, 235, 59, 90));
			g.setColor(SEL);
			drawQuad(g, tileQuad(p, selR, selC), 3f);
			if (assist)
				for (int[] d : game.legalDestinations(selR, selC))
					fillQuad(g, tileQuad(p, d[0], d[1]), DEST);
		}

		// Pieces, far rows first so nearer pieces overdraw farther ones.
		for (int r = 0; r < 8; r++)
			for (int c = 0; c < 8; c++)
				if (game.hasPiece(r, c))
					drawPiece(g, p, r, c, game.pieceKind(r, c), game.pieceTeam(r, c), L);

		// Light marker, so the player can see where they are dragging it.
		double[] lp = p.project(L[0], L[1], L[2]);
		if (lp[2] > 0) {
			int gx = (int) lp[0], gy = (int) lp[1];
			g.setColor(new Color(255, 240, 150, 80));
			g.fillOval(gx - 16, gy - 16, 32, 32);
			g.setColor(new Color(255, 220, 60));
			g.fillOval(gx - 7, gy - 7, 14, 14);
		}
	}

	// Shadow of a cylindrical piece = convex hull of its base circle and its top
	// circle projected through the light onto the board. Exact for a cylinder;
	// ground-only (a piece won't cast onto another piece's body). ponytail: planar
	// shadows, upgrade to a shadow map / per-pixel tracer if inter-piece shadows matter.
	private void drawShadow(Graphics2D g, Proj p, int row, int col, char kind, double[] L) {
		double r = (Character.isUpperCase(kind) ? 0.34 : 0.40) * 1.05;
		double H = height(kind);
		double cx = wx(col), cy = wy(row);
		int n = 14;
		double[][] pts = new double[2 * n][];
		for (int i = 0; i < n; i++) {
			double a = 2 * Math.PI * i / n;
			double ox = r * Math.cos(a), oy = r * Math.sin(a);
			pts[i] = new double[]{cx + ox, cy + oy}; // base rim already on the ground
			double[] s = groundShadow(cx + ox, cy + oy, H, L);
			pts[n + i] = s != null ? s : pts[i];
		}
		double[][] hull = convexHull(pts);
		Path2D path = new Path2D.Double();
		for (int i = 0; i < hull.length; i++) {
			double[] sp = p.project(hull[i][0], hull[i][1], 0);
			if (i == 0) path.moveTo(sp[0], sp[1]); else path.lineTo(sp[0], sp[1]);
		}
		path.closePath();
		g.setColor(new Color(0, 0, 0, 95));
		g.fill(path);
	}

	private void drawPiece(Graphics2D g, Proj p, int row, int col, char kind, boolean team, double[] L) {
		boolean chess = Character.isUpperCase(kind);
		double radius = chess ? 0.34 : 0.40;
		double height = height(kind);
		double cx = wx(col), cy = wy(row);

		double[] base = p.project(cx, cy, 0);
		double[] top = p.project(cx, cy, height);
		double rxB = radius * p.focal / base[2], ryB = rxB * SIN;
		double rxT = radius * p.focal / top[2], ryT = rxT * SIN;

		// Lambert shading: top cap normal is up; the visible side faces the camera.
		Color base0 = chess ? (team ? IVORY : CHESS_BLACK) : (team ? RED : CHK_BLACK);
		Color face = shade(base0, shadeFactor(cx, cy, height, L, 0, 0, 1));
		Color side = shade(base0, shadeFactor(cx, cy, height * 0.5, L, 0, -1, 0) * 0.9);

		// Cylinder body: bottom cap, side band, top cap.
		g.setColor(side);
		fillOval(g, base[0], base[1], rxB, ryB);
		Path2D band = new Path2D.Double();
		band.moveTo(base[0] - rxB, base[1]);
		band.lineTo(top[0] - rxT, top[1]);
		band.lineTo(top[0] + rxT, top[1]);
		band.lineTo(base[0] + rxB, base[1]);
		band.closePath();
		g.fill(band);
		g.setColor(face);
		fillOval(g, top[0], top[1], rxT, ryT);
		g.setColor(darker(face, 0.8));
		g.setStroke(new BasicStroke(1f));
		drawOval(g, top[0], top[1], rxT, ryT);

		if (chess) {
			String s = glyph(kind, g);
			g.setFont(g.getFont().deriveFont(Font.BOLD, (float) (ryT * 2.2)));
			FontMetrics fm = g.getFontMetrics();
			int gx = (int) (top[0] - fm.stringWidth(s) / 2.0);
			int gy = (int) (top[1] + fm.getAscent() / 2.0 - fm.getDescent() / 2.0);
			g.setColor(team ? new Color(40, 40, 40) : new Color(220, 220, 220));
			g.drawString(s, gx, gy);
		} else if (kind == 'k') { // checkers king: gold crown marker
			g.setColor(new Color(255, 215, 0));
			g.setStroke(new BasicStroke(2f));
			drawOval(g, top[0], top[1], rxT * 0.55, ryT * 0.55);
		}
	}

	private static double height(char kind) {
		switch (kind) {
			case 'm': return 0.22;
			case 'k': return 0.34; // checkers king (stacked)
			case 'P': return 0.55;
			case 'N': case 'B': return 0.75;
			case 'R': return 0.70;
			case 'Q': return 0.95;
			case 'K': return 1.05;
			default:  return 0.5;
		}
	}

	private static String glyph(char type, Graphics2D g) {
		String sym;
		switch (type) {
			case 'K': sym = "♚"; break;
			case 'Q': sym = "♛"; break;
			case 'R': sym = "♜"; break;
			case 'B': sym = "♝"; break;
			case 'N': sym = "♞"; break;
			default:  sym = "♟"; break;
		}
		return g.getFont().canDisplay('♚') ? sym : String.valueOf(type);
	}

	private static Color darker(Color c, double f) {
		return new Color((int) (c.getRed() * f), (int) (c.getGreen() * f), (int) (c.getBlue() * f), c.getAlpha());
	}

	// Diffuse term for a surface at P with normal N lit by point light L.
	private static double shadeFactor(double px, double py, double pz, double[] L,
			double nx, double ny, double nz) {
		double dx = L[0] - px, dy = L[1] - py, dz = L[2] - pz;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-9) return 1;
		double d = (dx * nx + dy * ny + dz * nz) / len;
		return AMBIENT + DIFFUSE * Math.max(0, d);
	}

	private static Color shade(Color c, double f) {
		f = Math.max(0, Math.min(1, f));
		return new Color((int) (c.getRed() * f), (int) (c.getGreen() * f), (int) (c.getBlue() * f), c.getAlpha());
	}

	private static void fillQuad(Graphics2D g, double[][] q, Color color) {
		Path2D path = new Path2D.Double();
		path.moveTo(q[0][0], q[0][1]);
		for (int i = 1; i < q.length; i++) path.lineTo(q[i][0], q[i][1]);
		path.closePath();
		g.setColor(color);
		g.fill(path);
	}

	private static void drawQuad(Graphics2D g, double[][] q, float stroke) {
		Path2D path = new Path2D.Double();
		path.moveTo(q[0][0], q[0][1]);
		for (int i = 1; i < q.length; i++) path.lineTo(q[i][0], q[i][1]);
		path.closePath();
		g.setStroke(new BasicStroke(stroke));
		g.draw(path);
	}

	private static void fillOval(Graphics2D g, double cx, double cy, double rx, double ry) {
		g.fillOval((int) (cx - rx), (int) (cy - ry), (int) (2 * rx), (int) (2 * ry));
	}

	private static void drawOval(Graphics2D g, double cx, double cy, double rx, double ry) {
		g.drawOval((int) (cx - rx), (int) (cy - ry), (int) (2 * rx), (int) (2 * ry));
	}
}
