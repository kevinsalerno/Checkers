import java.util.List;

// Verification harness: feed any Game a sequence of moves in "from-to" square
// notation (e.g. "e2e4", "a3-c5", "d7xe6") and it plays them out, rejecting any
// illegal move, then classifies the finished game (win/checkmate, draw/stalemate
// or still in progress). Works for chess and checkers alike via the Game API.
//
//   java -cp bin GameSim                 -> run the built-in verification suite
//   java -cp bin GameSim chess e2e4 ...  -> simulate a game from the command line
//   java -cp bin GameSim checkers c3d4 ...
public class GameSim {

	public static class Result {
		public int movesApplied;
		public boolean finished;
		public Boolean winner;   // team of winner, null = draw or ongoing
		public String label;     // human-readable classification
	}

	// Parse "e2e4" (optionally with a '-' or 'x' separator) into {fr,fc,tr,tc}.
	static int[] parse(String move) {
		String m = move.trim().toLowerCase().replace("-", "").replace("x", "");
		if (m.length() != 4)
			throw new IllegalArgumentException("Bad move '" + move + "' (expected like e2e4)");
		int fc = m.charAt(0) - 'a', fr = 8 - (m.charAt(1) - '0');
		int tc = m.charAt(2) - 'a', tr = 8 - (m.charAt(3) - '0');
		for (int v : new int[]{fr, fc, tr, tc})
			if (v < 0 || v > 7) throw new IllegalArgumentException("Off-board move '" + move + "'");
		return new int[]{fr, fc, tr, tc};
	}

	public static Result play(Game g, List<String> moves) {
		return play(g, moves.toArray(new String[0]));
	}

	public static Result play(Game g, String... moves) {
		Result res = new Result();
		for (int i = 0; i < moves.length; i++) {
			if (g.isOver())
				throw new IllegalStateException("Game already ended after " + res.movesApplied
						+ " moves; move #" + (i + 1) + " '" + moves[i] + "' cannot be played");
			int[] m = parse(moves[i]);
			MoveResult r = g.applyMove(m[0], m[1], m[2], m[3]);
			if (!r.valid)
				throw new IllegalArgumentException("Illegal move #" + (i + 1) + " '" + moves[i] + "'");
			res.movesApplied++;
		}
		res.finished = g.isOver();
		res.winner = g.winnerTeam();
		if (!res.finished) res.label = "in progress";
		else if (res.winner == null) res.label = "draw (stalemate)";
		else res.label = g.teamName(res.winner) + " wins (checkmate/no moves)";
		return res;
	}

	// --- built-in verification suite ---

	static int checks = 0;
	static void ok(boolean cond, String msg) {
		checks++;
		if (!cond) throw new RuntimeException("FAIL: " + msg);
	}

	static void suite() {
		// Checkmate: Fool's mate ends the game with Black winning.
		Result fm = play(new ChessBoard(), "f2f3", "e7e5", "g2g4", "d8h4");
		ok(fm.finished && Boolean.FALSE.equals(fm.winner), "fool's mate -> BLACK wins: " + fm.label);
		ok(fm.movesApplied == 4, "fool's mate applied 4 moves");

		// Stalemate: Sam Loyd's classic 10-move stalemate ends as a draw.
		Result st = play(new ChessBoard(),
				"e2e3", "a7a5", "d1h5", "a8a6", "h5a5", "h7h5", "a5c7", "a6h6",
				"h2h4", "f7f6", "c7d7", "e8f7", "d7b7", "d8d3", "b7b8", "d3h7",
				"b8c8", "f7g6", "c8e6");
		ok(st.finished && st.winner == null, "Loyd stalemate -> draw: " + st.label);

		// In-progress: a legal opening that has not ended.
		Result ip = play(new ChessBoard(), "e2e4", "e7e5", "g1f3", "b8c6");
		ok(!ip.finished && "in progress".equals(ip.label), "opening still in progress");

		// Illegal move is rejected with its index.
		boolean caught = false;
		try { play(new ChessBoard(), "e2e4", "e2e4"); }
		catch (IllegalArgumentException e) { caught = e.getMessage().contains("#2"); }
		ok(caught, "illegal 2nd move rejected");

		// Extra moves after the game is over are rejected.
		caught = false;
		try { play(new ChessBoard(), "f2f3", "e7e5", "g2g4", "d8h4", "a2a3"); }
		catch (IllegalStateException e) { caught = true; }
		ok(caught, "move after checkmate rejected");

		// Generic across game modes: checkers plays and stays in progress.
		Result ck = play(new Board(), "c3d4", "f6e5", "d4f6"); // RED steps, BLACK steps, RED jumps
		ok(!ck.finished && ck.movesApplied == 3, "checkers sequence applied: " + ck.label);

		// Checkers illegal move (moving onto own piece / non-diagonal) is rejected.
		caught = false;
		try { play(new Board(), "c3c4"); } // straight, not a legal checkers move
		catch (IllegalArgumentException e) { caught = true; }
		ok(caught, "illegal checkers move rejected");

		System.out.println("All " + checks + " checks passed.");
	}

	public static void main(String[] args) {
		if (args.length == 0) { suite(); return; }
		Game g = args[0].equalsIgnoreCase("chess") ? new ChessBoard() : new Board();
		String[] moves = new String[args.length - 1];
		System.arraycopy(args, 1, moves, 0, moves.length);
		Result r = play(g, moves);
		System.out.println("Applied " + r.movesApplied + " move(s). Result: " + r.label);
	}
}
