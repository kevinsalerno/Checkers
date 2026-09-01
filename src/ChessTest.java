// Runnable self-check for the chess rules engine. Run: java -cp bin ChessTest
public class ChessTest {
	static int checks = 0;
	static void ok(boolean cond, String msg) {
		checks++;
		if (!cond) throw new RuntimeException("FAIL: " + msg);
	}

	static ChessBoard empty(boolean whiteToMove) {
		ChessBoard b = new ChessBoard();
		for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) b.cells[r][c] = null;
		b.whiteToMove = whiteToMove;
		return b;
	}

	static boolean canGo(ChessBoard b, int r, int c, int tr, int tc) {
		for (int[] d : b.legalMoves(r, c)) if (d[0] == tr && d[1] == tc) return true;
		return false;
	}

	public static void main(String[] a) {
		// Opening position.
		ChessBoard b = new ChessBoard();
		ok(b.whiteToMove, "white moves first");
		ok(b.count(true) == 16 && b.count(false) == 16, "16 pieces each");
		ok(canGo(b, 6, 4, 4, 4), "e2-e4 (double pawn step)");
		ok(canGo(b, 6, 4, 5, 4), "e2-e3 (single pawn step)");
		ok(canGo(b, 7, 6, 5, 5), "knight g1-f3");
		ok(!canGo(b, 7, 4, 6, 4), "king blocked at start");

		// Pawn captures diagonally, not straight, into an enemy.
		b = empty(true);
		b.cells[4][4] = new ChessPiece(true, 'P');
		b.cells[3][3] = new ChessPiece(false, 'P');
		b.cells[3][4] = new ChessPiece(false, 'P'); // blocks straight advance
		b.cells[7][7] = new ChessPiece(true, 'K');
		b.cells[0][0] = new ChessPiece(false, 'K');
		ok(canGo(b, 4, 4, 3, 3), "pawn captures diagonally");
		ok(!canGo(b, 4, 4, 3, 4), "pawn cannot advance into a piece");

		// En passant.
		b = empty(true);
		b.cells[7][4] = new ChessPiece(true, 'K');
		b.cells[0][4] = new ChessPiece(false, 'K');
		b.cells[3][4] = new ChessPiece(true, 'P');  // white pawn on 5th rank
		b.cells[1][3] = new ChessPiece(false, 'P'); // black pawn about to double-step
		b.whiteToMove = false;
		b.applyMove(1, 3, 3, 3); // black d7-d5, sets en-passant target at (2,3)
		ok(canGo(b, 3, 4, 2, 3), "en passant capture available");
		MoveResult ep = b.applyMove(3, 4, 2, 3);
		ok(ep.valid && ep.captured, "en passant is a capture");
		ok(b.cells[3][3] == null, "captured pawn removed by en passant");

		// En passant only immediately; a delay forfeits the right.
		b = empty(true);
		b.cells[7][4] = new ChessPiece(true, 'K');
		b.cells[0][7] = new ChessPiece(false, 'K');
		b.cells[3][4] = new ChessPiece(true, 'P');
		b.cells[1][3] = new ChessPiece(false, 'P');
		b.whiteToMove = false;
		b.applyMove(1, 3, 3, 3);   // black double-step
		b.applyMove(7, 4, 7, 5);   // white king sidesteps instead of capturing
		b.applyMove(0, 7, 0, 6);   // black moves
		ok(!canGo(b, 3, 4, 2, 3), "en passant lost after a delay");

		// Castling king-side, and the rook lands correctly.
		b = empty(true);
		b.cells[7][4] = new ChessPiece(true, 'K');
		b.cells[7][7] = new ChessPiece(true, 'R');
		b.cells[0][0] = new ChessPiece(false, 'K');
		ok(canGo(b, 7, 4, 7, 6), "king-side castling available");
		b.applyMove(7, 4, 7, 6);
		ok(b.cells[7][6] != null && b.cells[7][6].type == 'K', "king moved to g1");
		ok(b.cells[7][5] != null && b.cells[7][5].type == 'R', "rook jumped to f1");

		// Cannot castle through an attacked square.
		b = empty(true);
		b.cells[7][4] = new ChessPiece(true, 'K');
		b.cells[7][7] = new ChessPiece(true, 'R');
		b.cells[0][5] = new ChessPiece(false, 'R'); // attacks f-file (col 5)
		b.cells[0][0] = new ChessPiece(false, 'K');
		ok(!canGo(b, 7, 4, 7, 6), "no castling through check");

		// A pinned piece cannot move off the pin line.
		b = empty(true);
		b.cells[7][4] = new ChessPiece(true, 'K');
		b.cells[7][5] = new ChessPiece(true, 'B'); // pinned along the back rank
		b.cells[7][7] = new ChessPiece(false, 'R');
		b.cells[0][0] = new ChessPiece(false, 'K');
		ok(b.legalMoves(7, 5).isEmpty(), "pinned bishop cannot move");

		// Promotion to queen.
		b = empty(true);
		b.cells[1][0] = new ChessPiece(true, 'P');
		b.cells[7][4] = new ChessPiece(true, 'K');
		b.cells[0][7] = new ChessPiece(false, 'K');
		MoveResult pr = b.applyMove(1, 0, 0, 0);
		ok(pr.promoted && b.cells[0][0].type == 'Q', "pawn promotes to queen");

		// Fool's mate: checkmate detected, white wins.
		b = new ChessBoard();
		b.applyMove(6, 5, 5, 5); // f2-f3
		b.applyMove(1, 4, 3, 4); // e7-e5
		b.applyMove(6, 6, 4, 6); // g2-g4
		MoveResult mate = b.applyMove(0, 3, 4, 7); // Qd8-h4#
		ok(mate.gameOver && Boolean.FALSE.equals(mate.winner), "fool's mate: black wins");

		// Stalemate: black's move leaves white with no move but not in check -> draw.
		b = empty(false);
		b.cells[0][0] = new ChessPiece(true, 'K');  // white king in the corner
		b.cells[2][1] = new ChessPiece(false, 'K'); // black king boxes it in
		b.cells[1][5] = new ChessPiece(false, 'Q'); // queen swings to b7, stalemating
		MoveResult stale = b.applyMove(1, 5, 1, 2);
		ok(stale.valid && stale.gameOver && stale.winner == null, "stalemate is a draw");
		ok(b.legalMoves(0, 0).isEmpty(), "stalemated king has no move");

		System.out.println("All " + checks + " checks passed.");
	}
}
