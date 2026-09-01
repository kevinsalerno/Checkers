import java.util.List;

// Runnable self-check for the rules engine. Run: java -cp bin BoardTest
public class BoardTest {
	static int checks = 0;
	static void ok(boolean cond, String msg) {
		checks++;
		if (!cond) throw new RuntimeException("FAIL: " + msg);
	}

	// Build an empty board with a chosen side to move.
	static Board empty(boolean turn) {
		Board b = new Board();
		for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) b.cells[r][c] = null;
		b.currentTurn = turn;
		return b;
	}

	static boolean canGo(Board b, int r, int c, int tr, int tc) {
		for (int[] d : b.getLegalDestinations(r, c)) if (d[0] == tr && d[1] == tc) return true;
		return false;
	}

	public static void main(String[] a) {
		// Initial position: RED to move, 12 pieces each, no captures, only forward moves.
		Board b = new Board();
		ok(b.currentTurn, "RED moves first");
		ok(b.count(true) == 12 && b.count(false) == 12, "12 pieces each");
		ok(!b.forcedCaptureNow(), "no forced capture at start");
		ok(canGo(b, 5, 0, 4, 1), "RED man can step forward-up");
		ok(!canGo(b, 5, 0, 6, 1), "RED man cannot step backward");

		// Forced capture: RED at (5,2) must jump BLACK at (4,3) to (3,4).
		b = empty(true);
		b.cells[5][2] = new BoardObject("RED", 5, 2);
		b.cells[4][3] = new BoardObject("BLACK", 4, 3);
		ok(b.forcedCaptureNow(), "capture is forced");
		ok(canGo(b, 5, 2, 3, 4), "capture destination legal");
		ok(!canGo(b, 5, 2, 4, 1), "non-capture illegal when capture exists");

		// Multi-jump chain: one turn, same piece jumps twice, turn stays RED mid-chain.
		b = empty(true);
		b.cells[5][2] = new BoardObject("RED", 5, 2);
		b.cells[4][3] = new BoardObject("BLACK", 4, 3);
		b.cells[2][3] = new BoardObject("BLACK", 2, 3);
		MoveResult r1 = b.applyMove(5, 2, 3, 4);
		ok(r1.valid && r1.captured, "first jump captures");
		ok(r1.continues && b.currentTurn, "chain continues, still RED's turn");
		ok(b.mustJumpPiece != null, "jump lock set");
		MoveResult r2 = b.applyMove(3, 4, 1, 2);
		ok(r2.valid && r2.captured && !r2.continues, "second jump ends chain");
		ok(!b.currentTurn, "turn passes to BLACK");
		ok(b.count(false) == 0, "both BLACK pieces captured");
		ok(b.gameOver && Boolean.TRUE.equals(b.winner), "RED wins, no BLACK left");

		// Kinging ends the turn even if another jump would exist.
		b = empty(true);
		b.cells[2][1] = new BoardObject("RED", 2, 1);
		b.cells[1][2] = new BoardObject("BLACK", 1, 2); // jump to (0,3) -> king row
		b.cells[1][4] = new BoardObject("BLACK", 1, 4); // a further jump would exist as a king
		MoveResult rk = b.applyMove(2, 1, 0, 3);
		ok(rk.promoted && !rk.continues, "crowning ends the turn");
		ok(b.cells[0][3].isKing(), "piece is now a king");
		ok(!b.currentTurn, "turn passed after crowning");

		// King moves and captures backward.
		b = empty(true);
		BoardObject king = new BoardObject("RED", 4, 4);
		king.king = true;
		b.cells[4][4] = king;
		ok(canGo(b, 4, 4, 5, 5), "king moves backward");
		b.cells[5][5] = new BoardObject("BLACK", 5, 5);
		ok(canGo(b, 4, 4, 6, 6), "king captures backward");

		// Win by no legal move (blocked, not captured).
		b = empty(false); // BLACK to move
		b.cells[0][1] = new BoardObject("BLACK", 0, 1); // man blocked on both diagonals
		b.cells[1][0] = new BoardObject("RED", 1, 0);   // beyond is off-board: no capture
		b.cells[1][2] = new BoardObject("RED", 1, 2);
		b.cells[2][3] = new BoardObject("RED", 2, 3);   // blocks the (0,1)->(2,3) landing
		ok(!b.hasLegalMove(false), "BLACK has no legal move");

		System.out.println("All " + checks + " checks passed.");
	}
}
