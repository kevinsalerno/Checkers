import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

// Standard chess: all piece movement, captures, check/checkmate/stalemate,
// castling, en passant and pawn promotion (auto-queen). White (team true)
// starts at the bottom and moves first.
public class ChessBoard implements Game {
	public ChessPiece[][] cells = new ChessPiece[8][8];
	public boolean whiteToMove = true;
	public boolean gameOver = false;
	public Boolean winner = null;
	private int[] enPassant = null; // square that may be captured en passant this move

	public ChessBoard() {
		char[] back = {'R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R'};
		for (int c = 0; c < 8; c++) {
			cells[0][c] = new ChessPiece(false, back[c]); // black back rank
			cells[1][c] = new ChessPiece(false, 'P');
			cells[6][c] = new ChessPiece(true, 'P');
			cells[7][c] = new ChessPiece(true, back[c]); // white back rank
		}
	}

	private static boolean inBounds(int r, int c) {
		return r >= 0 && r < 8 && c >= 0 && c < 8;
	}

	private boolean enemy(int r, int c, boolean white) {
		return inBounds(r, c) && cells[r][c] != null && cells[r][c].white != white;
	}

	private boolean empty(int r, int c) {
		return inBounds(r, c) && cells[r][c] == null;
	}

	// Is square (r,c) attacked by any piece of colour byWhite?
	private boolean isAttacked(int r, int c, boolean byWhite) {
		// pawns (attacker moves toward the defender's side)
		int pr = byWhite ? r + 1 : r - 1; // attacking pawn sits one rank behind its capture
		for (int dc = -1; dc <= 1; dc += 2)
			if (inBounds(pr, c + dc) && cells[pr][c + dc] != null
					&& cells[pr][c + dc].white == byWhite && cells[pr][c + dc].type == 'P')
				return true;
		// knights
		int[][] kn = {{-2,-1},{-2,1},{-1,-2},{-1,2},{1,-2},{1,2},{2,-1},{2,1}};
		for (int[] d : kn)
			if (isType(r + d[0], c + d[1], byWhite, 'N')) return true;
		// king
		for (int dr = -1; dr <= 1; dr++)
			for (int dc = -1; dc <= 1; dc++)
				if ((dr != 0 || dc != 0) && isType(r + dr, c + dc, byWhite, 'K')) return true;
		// sliding: rook/queen orthogonally, bishop/queen diagonally
		int[][] orth = {{-1,0},{1,0},{0,-1},{0,1}};
		int[][] diag = {{-1,-1},{-1,1},{1,-1},{1,1}};
		if (raySlider(r, c, byWhite, orth, 'R')) return true;
		if (raySlider(r, c, byWhite, diag, 'B')) return true;
		return false;
	}

	private boolean isType(int r, int c, boolean white, char type) {
		return inBounds(r, c) && cells[r][c] != null
				&& cells[r][c].white == white && cells[r][c].type == type;
	}

	// Walk each ray until a piece; attack if it's a queen or the given slider type.
	private boolean raySlider(int r, int c, boolean byWhite, int[][] dirs, char slider) {
		for (int[] d : dirs) {
			int nr = r + d[0], nc = c + d[1];
			while (inBounds(nr, nc)) {
				ChessPiece p = cells[nr][nc];
				if (p != null) {
					if (p.white == byWhite && (p.type == 'Q' || p.type == slider)) return true;
					break;
				}
				nr += d[0]; nc += d[1];
			}
		}
		return false;
	}

	private int[] kingPos(boolean white) {
		for (int r = 0; r < 8; r++)
			for (int c = 0; c < 8; c++)
				if (isType(r, c, white, 'K')) return new int[]{r, c};
		return null; // should not happen in a legal game
	}

	private boolean inCheck(boolean white) {
		int[] k = kingPos(white);
		return k != null && isAttacked(k[0], k[1], !white);
	}

	// Moves ignoring whether they leave the mover's king in check (no castling).
	private List<int[]> pseudoMoves(int r, int c) {
		List<int[]> out = new ArrayList<int[]>();
		ChessPiece p = cells[r][c];
		if (p == null) return out;
		boolean w = p.white;
		switch (p.type) {
			case 'P': {
				int dir = w ? -1 : 1, start = w ? 6 : 1;
				if (empty(r + dir, c)) {
					out.add(new int[]{r + dir, c});
					if (r == start && empty(r + 2 * dir, c)) out.add(new int[]{r + 2 * dir, c});
				}
				for (int dc = -1; dc <= 1; dc += 2) {
					int tr = r + dir, tc = c + dc;
					if (enemy(tr, tc, w)) out.add(new int[]{tr, tc});
					else if (enPassant != null && enPassant[0] == tr && enPassant[1] == tc)
						out.add(new int[]{tr, tc}); // en passant
				}
				break;
			}
			case 'N': {
				int[][] kn = {{-2,-1},{-2,1},{-1,-2},{-1,2},{1,-2},{1,2},{2,-1},{2,1}};
				for (int[] d : kn)
					if (empty(r + d[0], c + d[1]) || enemy(r + d[0], c + d[1], w))
						out.add(new int[]{r + d[0], c + d[1]});
				break;
			}
			case 'K': {
				for (int dr = -1; dr <= 1; dr++)
					for (int dc = -1; dc <= 1; dc++)
						if ((dr != 0 || dc != 0) && (empty(r + dr, c + dc) || enemy(r + dr, c + dc, w)))
							out.add(new int[]{r + dr, c + dc});
				break;
			}
			case 'B': slide(out, r, c, w, new int[][]{{-1,-1},{-1,1},{1,-1},{1,1}}); break;
			case 'R': slide(out, r, c, w, new int[][]{{-1,0},{1,0},{0,-1},{0,1}}); break;
			case 'Q': slide(out, r, c, w, new int[][]{{-1,-1},{-1,1},{1,-1},{1,1},{-1,0},{1,0},{0,-1},{0,1}}); break;
		}
		return out;
	}

	private void slide(List<int[]> out, int r, int c, boolean w, int[][] dirs) {
		for (int[] d : dirs) {
			int nr = r + d[0], nc = c + d[1];
			while (empty(nr, nc)) { out.add(new int[]{nr, nc}); nr += d[0]; nc += d[1]; }
			if (enemy(nr, nc, w)) out.add(new int[]{nr, nc});
		}
	}

	// Simulate a move and report whether the mover's king ends up in check.
	private boolean leavesKingInCheck(int fr, int fc, int tr, int tc) {
		ChessPiece from = cells[fr][fc];
		ChessPiece to = cells[tr][tc];
		boolean isEnPassant = from.type == 'P' && fc != tc && to == null;
		ChessPiece epPawn = isEnPassant ? cells[fr][tc] : null;

		cells[tr][tc] = from;
		cells[fr][fc] = null;
		if (isEnPassant) cells[fr][tc] = null;

		boolean bad = inCheck(from.white);

		cells[fr][fc] = from;
		cells[tr][tc] = to;
		if (isEnPassant) cells[fr][tc] = epPawn;
		return bad;
	}

	public List<int[]> legalMoves(int r, int c) {
		List<int[]> out = new ArrayList<int[]>();
		ChessPiece p = cells[r][c];
		if (p == null || p.white != whiteToMove || gameOver) return out;
		for (int[] m : pseudoMoves(r, c))
			if (!leavesKingInCheck(r, c, m[0], m[1])) out.add(m);
		if (p.type == 'K' && !p.moved && !inCheck(p.white)) addCastling(out, r, c, p.white);
		return out;
	}

	// King-side and queen-side castling, fully validated (path empty, king not
	// moving through or into an attacked square).
	private void addCastling(List<int[]> out, int r, int c, boolean w) {
		boolean by = !w;
		ChessPiece kr = cells[r][7], qr = cells[r][0];
		if (kr != null && kr.type == 'R' && !kr.moved
				&& empty(r, 5) && empty(r, 6)
				&& !isAttacked(r, 5, by) && !isAttacked(r, 6, by))
			out.add(new int[]{r, 6});
		if (qr != null && qr.type == 'R' && !qr.moved
				&& empty(r, 1) && empty(r, 2) && empty(r, 3)
				&& !isAttacked(r, 3, by) && !isAttacked(r, 2, by))
			out.add(new int[]{r, 2});
	}

	private boolean hasAnyLegalMove(boolean white) {
		for (int r = 0; r < 8; r++)
			for (int c = 0; c < 8; c++)
				if (cells[r][c] != null && cells[r][c].white == white && !legalMoves(r, c).isEmpty())
					return true;
		return false;
	}

	public MoveResult applyMove(int fr, int fc, int tr, int tc) {
		MoveResult res = new MoveResult();
		boolean legal = false;
		for (int[] d : legalMoves(fr, fc))
			if (d[0] == tr && d[1] == tc) { legal = true; break; }
		if (!legal) return res;

		res.valid = true;
		ChessPiece p = cells[fr][fc];

		boolean isEnPassant = p.type == 'P' && fc != tc && cells[tr][tc] == null;
		if (cells[tr][tc] != null) res.captured = true;
		if (isEnPassant) { cells[fr][tc] = null; res.captured = true; }

		cells[tr][tc] = p;
		cells[fr][fc] = null;
		p.moved = true;

		// Castling: move the rook to the far side of the king.
		if (p.type == 'K' && Math.abs(tc - fc) == 2) {
			int rookFrom = tc > fc ? 7 : 0, rookTo = tc > fc ? tc - 1 : tc + 1;
			ChessPiece rook = cells[fr][rookFrom];
			cells[fr][rookTo] = rook;
			cells[fr][rookFrom] = null;
			if (rook != null) rook.moved = true;
		}

		// Promotion (auto-queen).
		if (p.type == 'P' && (tr == 0 || tr == 7)) { p.type = 'Q'; res.promoted = true; }

		// Record en-passant target if a pawn just advanced two squares.
		enPassant = (p.type == 'P' && Math.abs(tr - fr) == 2)
				? new int[]{(fr + tr) / 2, fc} : null;

		whiteToMove = !whiteToMove;
		if (!hasAnyLegalMove(whiteToMove)) {
			gameOver = true;
			winner = inCheck(whiteToMove) ? Boolean.valueOf(!whiteToMove) : null; // checkmate vs stalemate
		}
		res.gameOver = gameOver;
		res.winner = winner;
		return res;
	}

	// --- Game interface ---

	public boolean toMove() { return whiteToMove; }
	public boolean isOver() { return gameOver; }
	public Boolean winnerTeam() { return winner; }
	public List<int[]> legalDestinations(int r, int c) { return legalMoves(r, c); }
	public boolean hasPiece(int r, int c) { return cells[r][c] != null; }
	public boolean pieceTeam(int r, int c) { return cells[r][c].white; }
	public char pieceKind(int r, int c) { return cells[r][c].type; }
	public String teamName(boolean team) { return team ? "WHITE" : "BLACK"; }
	public Game fresh() { return new ChessBoard(); }
	public void timeout(boolean loser) { gameOver = true; winner = !loser; }

	public String statusHint() {
		return !gameOver && inCheck(whiteToMove) ? "check!" : "";
	}

	public int count(boolean team) {
		int n = 0;
		for (int r = 0; r < 8; r++)
			for (int c = 0; c < 8; c++)
				if (cells[r][c] != null && cells[r][c].white == team) n++;
		return n;
	}

	private static String glyph(char type) {
		switch (type) {
			case 'K': return "♚";
			case 'Q': return "♛";
			case 'R': return "♜";
			case 'B': return "♝";
			case 'N': return "♞";
			default:  return "♟"; // pawn
		}
	}

	public void paintPiece(Graphics2D g, int r, int c, int x, int y, int cell) {
		ChessPiece p = cells[r][c];
		if (p == null) return;
		g.setFont(g.getFont().deriveFont(Font.PLAIN, cell * 0.72f));
		// Fall back to letters if the font lacks chess glyphs, so pieces always render.
		String s = g.getFont().canDisplay('♚') ? glyph(p.type) : String.valueOf(p.type);
		FontMetrics fm = g.getFontMetrics();
		int gx = x + (cell - fm.stringWidth(s)) / 2;
		int gy = y + (cell + fm.getAscent() - fm.getDescent()) / 2;
		// contrasting outline for legibility on both square colours
		g.setColor(p.white ? Color.BLACK : new Color(215, 215, 215));
		for (int dx = -1; dx <= 1; dx++)
			for (int dy = -1; dy <= 1; dy++)
				if (dx != 0 || dy != 0) g.drawString(s, gx + dx, gy + dy);
		g.setColor(p.white ? Color.WHITE : new Color(25, 25, 25));
		g.drawString(s, gx, gy);
	}
}
