import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

// Flat top-down board, scaled to the panel. Pieces are drawn by the Game itself.
public class Renderer2D implements BoardRenderer {
	private static final Color LIGHT = new Color(240, 217, 181);
	private static final Color DARK = new Color(181, 136, 99);
	private static final Color SEL = new Color(255, 235, 59);
	private static final Color DEST = new Color(40, 190, 90, 170);
	private static final Color MOVABLE = new Color(0, 160, 220);
	private static final Color FORCED = new Color(230, 130, 0);

	private int cell(int w, int h) { return Math.min(w, h) / 8; }
	private int ox(int w, int h) { return (w - cell(w, h) * 8) / 2; }
	private int oy(int w, int h) { return (h - cell(w, h) * 8) / 2; }

	public int[] pick(int mx, int my, int w, int h) {
		int cell = cell(w, h), ox = ox(w, h), oy = oy(w, h);
		int col = (mx - ox) / cell, row = (my - oy) / cell;
		if (mx < ox || my < oy || row < 0 || row > 7 || col < 0 || col > 7) return null;
		return new int[]{row, col};
	}

	public void paint(Graphics2D g, Game game, int selR, int selC, boolean assist, int w, int h) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		int cell = cell(w, h), ox = ox(w, h), oy = oy(w, h);

		for (int r = 0; r < 8; r++)
			for (int c = 0; c < 8; c++) {
				g.setColor((r + c) % 2 == 0 ? LIGHT : DARK);
				g.fillRect(ox + c * cell, oy + r * cell, cell, cell);
			}

		if (assist && selR < 0 && !game.isOver()) {
			g.setColor(game.statusHint().length() == 0 ? MOVABLE : FORCED);
			g.setStroke(new java.awt.BasicStroke(3));
			for (int r = 0; r < 8; r++)
				for (int c = 0; c < 8; c++)
					if (game.hasPiece(r, c) && game.pieceTeam(r, c) == game.toMove()
							&& !game.legalDestinations(r, c).isEmpty())
						g.drawOval(ox + c * cell + 6, oy + r * cell + 6, cell - 12, cell - 12);
		}

		if (selR >= 0) {
			g.setColor(SEL);
			g.setStroke(new java.awt.BasicStroke(4));
			g.drawRect(ox + selC * cell + 2, oy + selR * cell + 2, cell - 4, cell - 4);
			if (assist) {
				g.setColor(DEST);
				for (int[] d : game.legalDestinations(selR, selC))
					g.fillOval(ox + d[1] * cell + cell / 3, oy + d[0] * cell + cell / 3, cell / 3, cell / 3);
			}
		}

		for (int r = 0; r < 8; r++)
			for (int c = 0; c < 8; c++)
				game.paintPiece(g, r, c, ox + c * cell, oy + r * cell, cell);
	}
}
