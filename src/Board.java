import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

// American checkers rules: diagonal moves, mandatory captures, multi-jumps,
// kings (one-square, both directions), crowning ends the turn, win when the
// side to move has no legal move (or no pieces).
public class Board implements Game {
	private static final Color RED_PIECE = new Color(200, 45, 45);
	private static final Color BLACK_PIECE = new Color(45, 45, 45);

	public BoardObject[][] cells = new BoardObject[8][8];
	public boolean currentTurn = true; // true = RED moves first
	public boolean gameOver = false;
	public Boolean winner = null; // team of winner, or null
	public BoardObject mustJumpPiece = null; // set during a multi-jump chain

	public Board() {
		placeStartingPieces("BLACK", new int[]{0, 1, 2});
		placeStartingPieces("RED", new int[]{5, 6, 7});
	}

	private void placeStartingPieces(String team, int[] rows) {
		for (int r : rows)
			for (int c = 0; c < 8; c++)
				if ((r + c) % 2 == 1) // dark squares only
					cells[r][c] = new BoardObject(team, r, c);
	}

	private static boolean inBounds(int r, int c) {
		return r >= 0 && r < 8 && c >= 0 && c < 8;
	}

	// Movement directions allowed for a piece (kings: all four).
	private int[][] dirs(BoardObject p) {
		if (p.king) return new int[][]{{-1,-1},{-1,1},{1,-1},{1,1}};
		return p.team ? new int[][]{{-1,-1},{-1,1}}  // RED moves up
		              : new int[][]{{1,-1},{1,1}};    // BLACK moves down
	}

	private List<int[]> captureDests(int r, int c) {
		List<int[]> out = new ArrayList<int[]>();
		BoardObject p = cells[r][c];
		if (p == null) return out;
		for (int[] d : dirs(p)) {
			int mr = r + d[0], mc = c + d[1];
			int lr = r + 2 * d[0], lc = c + 2 * d[1];
			if (inBounds(lr, lc) && cells[lr][lc] == null
					&& cells[mr][mc] != null && cells[mr][mc].team != p.team)
				out.add(new int[]{lr, lc});
		}
		return out;
	}

	private List<int[]> simpleDests(int r, int c) {
		List<int[]> out = new ArrayList<int[]>();
		BoardObject p = cells[r][c];
		if (p == null) return out;
		for (int[] d : dirs(p)) {
			int nr = r + d[0], nc = c + d[1];
			if (inBounds(nr, nc) && cells[nr][nc] == null)
				out.add(new int[]{nr, nc});
		}
		return out;
	}

	private boolean sideHasCapture(boolean team) {
		for (int r = 0; r < 8; r++)
			for (int c = 0; c < 8; c++)
				if (cells[r][c] != null && cells[r][c].team == team
						&& !captureDests(r, c).isEmpty())
					return true;
		return false;
	}

	// True when the side to move is obligated to capture this turn.
	public boolean forcedCaptureNow() {
		return mustJumpPiece != null || sideHasCapture(currentTurn);
	}

	// Legal destinations for the piece at (r,c), honoring turn, forced captures
	// and any in-progress multi-jump lock.
	public List<int[]> getLegalDestinations(int r, int c) {
		BoardObject p = cells[r][c];
		if (p == null || p.team != currentTurn || gameOver) return new ArrayList<int[]>();
		if (mustJumpPiece != null) return p == mustJumpPiece ? captureDests(r, c) : new ArrayList<int[]>();
		if (sideHasCapture(currentTurn)) return captureDests(r, c);
		return simpleDests(r, c);
	}

	public boolean hasLegalMove(boolean team) {
		for (int r = 0; r < 8; r++)
			for (int c = 0; c < 8; c++)
				if (cells[r][c] != null && cells[r][c].team == team
						&& !getLegalDestinations(r, c).isEmpty())
					return true;
		return false;
	}

	public MoveResult applyMove(int fromR, int fromC, int toR, int toC) {
		MoveResult res = new MoveResult();
		boolean legal = false;
		for (int[] d : getLegalDestinations(fromR, fromC))
			if (d[0] == toR && d[1] == toC) { legal = true; break; }
		if (!legal) return res; // res.valid stays false

		res.valid = true;
		BoardObject p = cells[fromR][fromC];
		boolean isCapture = Math.abs(toR - fromR) == 2;

		if (isCapture) {
			int mr = (fromR + toR) / 2, mc = (fromC + toC) / 2;
			cells[mr][mc] = null;
			res.captured = true;
		}

		cells[toR][toC] = p;
		cells[fromR][fromC] = null;
		p.setPositionX(toR);
		p.setPositionY(toC);

		// Crowning: reaching the far row makes a king and ends the turn.
		if (!p.king && ((p.team && toR == 0) || (!p.team && toR == 7))) {
			p.king = true;
			res.promoted = true;
		}

		res.continues = isCapture && !res.promoted && !captureDests(toR, toC).isEmpty();
		if (res.continues) {
			mustJumpPiece = p; // same player keeps jumping with this piece
		} else {
			mustJumpPiece = null;
			currentTurn = !currentTurn;
			if (count(currentTurn) == 0 || !hasLegalMove(currentTurn)) {
				gameOver = true;
				winner = !currentTurn;
			}
		}
		res.gameOver = gameOver;
		res.winner = winner;
		return res;
	}

	// --- Game interface ---

	public boolean toMove() { return currentTurn; }
	public boolean isOver() { return gameOver; }
	public Boolean winnerTeam() { return winner; }
	public List<int[]> legalDestinations(int r, int c) { return getLegalDestinations(r, c); }
	public boolean hasPiece(int r, int c) { return cells[r][c] != null; }
	public boolean pieceTeam(int r, int c) { return cells[r][c].team; }
	public char pieceKind(int r, int c) { return cells[r][c].king ? 'k' : 'm'; }
	public String statusHint() { return forcedCaptureNow() ? "must capture!" : ""; }
	public String teamName(boolean team) { return team ? "RED" : "BLACK"; }
	public Game fresh() { return new Board(); }
	public void timeout(boolean loser) { gameOver = true; winner = !loser; }

	public int count(boolean team) {
		int n = 0;
		for (int r = 0; r < 8; r++)
			for (int c = 0; c < 8; c++)
				if (cells[r][c] != null && cells[r][c].team == team) n++;
		return n;
	}

	public void paintPiece(Graphics2D g, int r, int c, int x, int y, int cell) {
		BoardObject p = cells[r][c];
		if (p == null) return;
		int px = x + 10, py = y + 10, d = cell - 20;
		g.setColor(p.team ? RED_PIECE : BLACK_PIECE);
		g.fillOval(px, py, d, d);
		g.setColor(Color.WHITE);
		g.setStroke(new java.awt.BasicStroke(2));
		g.drawOval(px, py, d, d);
		if (p.king) {
			g.setColor(new Color(255, 215, 0));
			g.setFont(g.getFont().deriveFont(Font.BOLD, cell * 0.4f));
			int kw = g.getFontMetrics().stringWidth("K");
			g.drawString("K", x + (cell - kw) / 2, y + cell / 2 + cell / 6);
		}
	}
}
