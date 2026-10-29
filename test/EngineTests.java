import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Headless tests for the checkers and chess engines and the Board facade. */
public final class EngineTests {
    private EngineTests() {
    }

    public static int run() {
        TestUtil.reset();
        checkersInitial();
        boardFacade();
        checkersMenAndCaptures();
        checkersChainsAndCrowning();
        checkersEndgames();
        chessPerft();
        chessMatesAndCastling();
        chessEnPassantAndPromotion();
        chessPinsAndDraws();
        chessParsing();
        controllerBehaviour();
        return TestUtil.report("EngineTests");
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    private static void checkersInitial() {
        CheckersEngine e = new CheckersEngine();
        TestUtil.eq(12, e.countPieces(Side.WHITE), "checkers: 12 white pieces");
        TestUtil.eq(12, e.countPieces(Side.BLACK), "checkers: 12 black pieces");
        TestUtil.eq(Side.BLACK, e.sideToMove(), "checkers: black moves first");
        TestUtil.eq(7, e.legalMoves().size(), "checkers: 7 opening moves");
        TestUtil.check(!e.isGameOver(), "checkers: opening is in progress");
    }

    private static void boardFacade() {
        Board board = Board.createFresh();
        BoardObject piece = board.cells[2][1];
        TestUtil.check(piece != null, "board: piece at b6 exists");
        boolean moved = board.attemptMove(piece, 1, -1);
        TestUtil.check(moved, "board: diagonal move accepted");
        // The original bug: coordinates stayed stale after a move.
        BoardObject atDest = board.cells[3][0];
        TestUtil.check(atDest != null, "board: piece landed at a5");
        TestUtil.eq(3, atDest.getPositionX(), "board: moved piece row updated");
        TestUtil.eq(0, atDest.getPositionY(), "board: moved piece col updated");
        TestUtil.check(board.cells[2][1] == null, "board: origin square cleared");
        int turnsBefore = board.getTurns();
        BoardObject white = board.cells[5][0];
        // Orthogonal move (the old getAvailableMoves world): must be refused
        // and must not advance the turn counter or mutate the board.
        boolean orthogonal = board.attemptMove(white, 0, 1);
        TestUtil.check(!orthogonal, "board: orthogonal move refused");
        TestUtil.eq(turnsBefore, board.getTurns(), "board: illegal move keeps turn count");
        TestUtil.check(board.cells[5][0] == white, "board: illegal move mutates nothing");
        // Diagonal availability for a black man at b6 (fresh board):
        // NW/NE are occupied by own pieces, SW/SE are free forward steps.
        Board fresh = Board.createFresh();
        boolean[] avail = fresh.cells[2][1].getAvailableMoves();
        TestUtil.check(!avail[0] && !avail[1] && avail[2] && avail[3],
                "boardObject: diagonal availability NW,NE,SW,SE");
        BoardObject lone = new BoardObject("WHITE", 4, 4);
        boolean[] loneAvail = lone.getAvailableMoves();
        TestUtil.check(loneAvail[0] && loneAvail[1] && !loneAvail[2] && !loneAvail[3],
                "boardObject: lone white man moves forward diagonally only");
    }

    private static CheckersEngine customCheckers(int[][] pieces, Side toMove) {
        CheckersEngine e = new CheckersEngine();
        e.clearBoard();
        for (int[] p : pieces) {
            e.setPiece(p[0], p[1], p[2]);
        }
        e.setSideToMove(toMove);
        return e;
    }

    private static void checkersMenAndCaptures() {
        // Men move forward only.
        CheckersEngine e = customCheckers(new int[][] {
                { 4, 3, CheckersEngine.WHITE_MAN },
                { 1, 1, CheckersEngine.BLACK_MAN } }, Side.WHITE);
        Set<String> moves = new HashSet<String>();
        for (CheckersMove m : e.legalMoves()) {
            moves.add(m.toString());
        }
        TestUtil.check(moves.contains("d4-c5"), "checkers: man steps forward left");
        TestUtil.check(moves.contains("d4-e5"), "checkers: man steps forward right");
        TestUtil.check(!moves.contains("d4-c3") && !moves.contains("d4-e3"),
                "checkers: man never steps backward");
        // Kings move in every direction.
        CheckersEngine k = customCheckers(new int[][] {
                { 4, 3, CheckersEngine.WHITE_KING },
                { 1, 1, CheckersEngine.BLACK_MAN } }, Side.WHITE);
        Set<String> kmoves = new HashSet<String>();
        for (CheckersMove m : k.legalMoves()) {
            kmoves.add(m.toString());
        }
        TestUtil.check(kmoves.size() == 4, "checkers: king has 4 steps, got " + kmoves);
        // Captures are mandatory: a quiet move is illegal while a jump exists.
        CheckersEngine c = customCheckers(new int[][] {
                { 4, 3, CheckersEngine.WHITE_MAN },
                { 3, 2, CheckersEngine.BLACK_MAN },
                { 5, 0, CheckersEngine.WHITE_MAN },
                { 0, 0, CheckersEngine.BLACK_MAN } }, Side.WHITE);
        for (CheckersMove m : c.legalMoves()) {
            TestUtil.check(m.isJump(), "checkers: only jumps legal when a capture exists");
        }
        TestUtil.eq(1, c.legalMoves().size(), "checkers: exactly one capture");
        final CheckersEngine cc = c;
        TestUtil.rejects(new Runnable() {
            @Override
            public void run() {
                cc.applyNotation("a3-b4");
            }
        }, "checkers: quiet move rejected while capture is mandatory");
    }

    private static void checkersChainsAndCrowning() {
        // White man c3 jumps d4 and f6 in one chain: c3-e5-g7.
        CheckersEngine e = customCheckers(new int[][] {
                { 5, 2, CheckersEngine.WHITE_MAN },
                { 4, 3, CheckersEngine.BLACK_MAN },
                { 2, 5, CheckersEngine.BLACK_MAN },
                { 0, 1, CheckersEngine.BLACK_MAN } }, Side.WHITE);
        CheckersMove first = e.parseSingleStep("c3xe5");
        e.applyMove(first);
        TestUtil.check(e.isChainActive(), "checkers: chain stays open after first jump");
        TestUtil.eq(Side.WHITE, e.sideToMove(), "checkers: turn does not pass mid-chain");
        TestUtil.eq(1, e.legalMoves().size(), "checkers: only the chaining piece may move");
        TestUtil.eq(CheckersEngine.EMPTY, e.pieceAt(4, 3), "checkers: first victim removed");
        e.applyMove(e.parseSingleStep("e5xg7"));
        TestUtil.check(!e.isChainActive(), "checkers: chain ends after last jump");
        TestUtil.eq(Side.BLACK, e.sideToMove(), "checkers: turn passes after chain");
        TestUtil.eq(CheckersEngine.EMPTY, e.pieceAt(2, 5), "checkers: second victim removed");
        TestUtil.eq(1, e.countPieces(Side.BLACK), "checkers: one black piece left");
        // An incomplete chain notation is rejected without mutating anything.
        CheckersEngine e2 = customCheckers(new int[][] {
                { 5, 2, CheckersEngine.WHITE_MAN },
                { 4, 3, CheckersEngine.BLACK_MAN },
                { 2, 5, CheckersEngine.BLACK_MAN },
                { 0, 1, CheckersEngine.BLACK_MAN } }, Side.WHITE);
        final CheckersEngine e2f = e2;
        TestUtil.rejects(new Runnable() {
            @Override
            public void run() {
                e2f.applyNotation("c3xe5");
            }
        }, "checkers: incomplete multi-jump notation rejected");
        TestUtil.eq(CheckersEngine.WHITE_MAN, e2.pieceAt(5, 2),
                "checkers: rejected chain leaves position unchanged");
        TestUtil.eq(CheckersEngine.BLACK_MAN, e2.pieceAt(4, 3),
                "checkers: rejected chain keeps victims");
        // A complete chain notation applies both jumps at once.
        e2.applyNotation("c3xe5xg7");
        TestUtil.eq(CheckersEngine.WHITE_MAN, e2.pieceAt(1, 6),
                "checkers: full chain notation lands on g7");
        // Crowning ends the turn even if the new king could jump.
        CheckersEngine crown = customCheckers(new int[][] {
                { 1, 2, CheckersEngine.WHITE_MAN },
                { 1, 4, CheckersEngine.BLACK_MAN },
                { 6, 6, CheckersEngine.BLACK_MAN } }, Side.WHITE);
        crown.applyNotation("c7-d8");
        TestUtil.eq(CheckersEngine.WHITE_KING, crown.pieceAt(0, 3),
                "checkers: reaching the far row crowns the man");
        TestUtil.eq(Side.BLACK, crown.sideToMove(),
                "checkers: crowning ends the turn despite a king jump");
        TestUtil.eq(CheckersEngine.BLACK_MAN, crown.pieceAt(1, 4),
                "checkers: crowning does not continue into a king jump");
        // Non-flying kings: a jump is over exactly one adjacent piece.
        CheckersEngine fly = customCheckers(new int[][] {
                { 4, 3, CheckersEngine.WHITE_KING },
                { 2, 1, CheckersEngine.BLACK_MAN },
                { 7, 7, CheckersEngine.BLACK_MAN } }, Side.WHITE);
        boolean distantJump = false;
        for (CheckersMove m : fly.legalMoves()) {
            if (Math.abs(m.toRow - m.fromRow) > 2) {
                distantJump = true;
            }
        }
        TestUtil.check(!distantJump, "checkers: kings are non-flying (jumps span 2)");
    }

    private static void checkersEndgames() {
        // Capturing the last piece wins.
        CheckersEngine win = customCheckers(new int[][] {
                { 4, 3, CheckersEngine.WHITE_KING },
                { 3, 2, CheckersEngine.BLACK_MAN } }, Side.WHITE);
        win.applyNotation("d4xb6");
        TestUtil.eq(CheckersEngine.Outcome.WHITE_WINS, win.outcome(),
                "checkers: capturing the last piece wins");
        // Blocked side with no legal move loses.
        CheckersEngine blocked = customCheckers(new int[][] {
                { 0, 1, CheckersEngine.BLACK_MAN },
                { 1, 0, CheckersEngine.WHITE_MAN },
                { 1, 2, CheckersEngine.WHITE_MAN },
                { 2, 3, CheckersEngine.WHITE_MAN },
                { 6, 6, CheckersEngine.WHITE_KING } }, Side.BLACK);
        TestUtil.eq(CheckersEngine.Outcome.WHITE_WINS, blocked.outcome(),
                "checkers: side with no legal move loses");
        // Threefold repetition: two kings shuttle back and forth.
        CheckersEngine rep = customCheckers(new int[][] {
                { 3, 2, CheckersEngine.WHITE_KING },
                { 4, 5, CheckersEngine.BLACK_KING } }, Side.WHITE);
        String[] cycle = { "c5-d6", "f4-e3", "d6-c5", "e3-f4",
                "c5-d6", "f4-e3", "d6-c5", "e3-f4" };
        for (String s : cycle) {
            rep.applyNotation(s);
        }
        TestUtil.eq(CheckersEngine.Outcome.DRAW, rep.outcome(),
                "checkers: threefold repetition is a draw");
        TestUtil.check(rep.outcomeReason().contains("repetition"),
                "checkers: repetition reason reported");
        // 40-move rule: 80 ply without capture or crowning. A random
        // walk with restarts finds one: each step must be quiet, must
        // not step diagonally adjacent to the enemy king (that would
        // force a capture), and must not create a third occurrence of
        // any position (that would end the game by repetition first).
        CheckersEngine longGame = null;
        for (int attempt = 0; attempt < 500 && longGame == null; attempt++) {
            CheckersEngine walk = customCheckers(new int[][] {
                    { 3, 2, CheckersEngine.WHITE_KING },
                    { 4, 5, CheckersEngine.BLACK_KING } }, Side.WHITE);
            java.util.Map<String, Integer> seenCounts =
                    new java.util.HashMap<String, Integer>();
            seenCounts.put(walk.boardDiagram(), 1);
            java.util.Random rnd = new java.util.Random(attempt);
            boolean stuck = false;
            for (int ply = 0; ply < CheckersEngine.DRAW_PLY_LIMIT; ply++) {
                List<CheckersMove> safe = new ArrayList<CheckersMove>();
                for (CheckersMove m : walk.legalMoves()) {
                    if (m.isJump()) {
                        continue;
                    }
                    CheckersEngine probe = walk.copy();
                    probe.applyMove(m);
                    Integer seenCount = seenCounts.get(probe.boardDiagram());
                    if (seenCount != null && seenCount >= 2) {
                        continue;
                    }
                    if (kingsDiagonallyAdjacent(probe)) {
                        continue;
                    }
                    safe.add(m);
                }
                if (safe.isEmpty()) {
                    stuck = true;
                    break;
                }
                walk.applyMove(safe.get(rnd.nextInt(safe.size())));
                String key = walk.boardDiagram();
                Integer n = seenCounts.get(key);
                seenCounts.put(key, n == null ? 1 : n + 1);
                // On the final ply the 40-move draw itself ends the
                // game; only an earlier game-over means this walk
                // ended by repetition.
                if (walk.isGameOver() && ply < CheckersEngine.DRAW_PLY_LIMIT - 1) {
                    stuck = true;
                    break;
                }
            }
            if (!stuck) {
                longGame = walk;
            }
        }
        TestUtil.check(longGame != null,
                "checkers: a capture-free 80-ply walk exists");
        if (longGame == null) {
            longGame = customCheckers(new int[][] {
                    { 3, 2, CheckersEngine.WHITE_KING },
                    { 4, 5, CheckersEngine.BLACK_KING } }, Side.WHITE);
        }
        TestUtil.eq(CheckersEngine.Outcome.DRAW, longGame.outcome(),
                "checkers: 80 ply without capture/crowning is a draw");
        TestUtil.check(longGame.outcomeReason().contains("40-move"),
                "checkers: 40-move reason reported");
    }

    /** True when the kings stand diagonally adjacent (a capture is open). */
    private static boolean kingsDiagonallyAdjacent(CheckersEngine e) {
        int wr = -1;
        int wc = -1;
        int br = -1;
        int bc = -1;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (e.pieceAt(r, c) == CheckersEngine.WHITE_KING) {
                    wr = r;
                    wc = c;
                }
                if (e.pieceAt(r, c) == CheckersEngine.BLACK_KING) {
                    br = r;
                    bc = c;
                }
            }
        }
        return Math.abs(wr - br) == 1 && Math.abs(wc - bc) == 1;
    }

    private static long perft(ChessEngine e, int depth) {
        if (depth == 0) {
            return 1;
        }
        long n = 0;
        for (ChessMove m : e.legalMoves()) {
            ChessEngine c = e.copy();
            c.applyUnchecked(m);
            n += perft(c, depth - 1);
        }
        return n;
    }

    private static void chessPerft() {
        ChessEngine e = new ChessEngine();
        TestUtil.eq(ChessEngine.START_FEN, e.toFen(), "chess: start FEN");
        TestUtil.eq(20L, perft(e, 1), "chess: perft(1) = 20");
        TestUtil.eq(400L, perft(e, 2), "chess: perft(2) = 400");
        TestUtil.eq(8902L, perft(e, 3), "chess: perft(3) = 8902");
        ChessEngine kiwi = new ChessEngine();
        kiwi.loadFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        TestUtil.eq(97862L, perft(kiwi, 3), "chess: Kiwipete perft(3) = 97862");
        TestUtil.eq(20, e.legalMoves().size(), "chess: 20 opening moves");
    }

    private static void chessMatesAndCastling() {
        ChessEngine fools = new ChessEngine();
        for (String s : new String[] { "f3", "e5", "g4", "Qh4#" }) {
            fools.applyNotation(s);
        }
        TestUtil.eq(ChessEngine.Outcome.BLACK_WINS, fools.outcome(), "chess: fool's mate wins for black");
        TestUtil.eq("Checkmate", fools.outcomeReason(), "chess: fool's mate reason");
        ChessEngine scholars = new ChessEngine();
        for (String s : new String[] { "e4", "e5", "Bc4", "Nc6", "Qh5", "Nf6", "Qxf7#" }) {
            scholars.applyNotation(s);
        }
        TestUtil.eq(ChessEngine.Outcome.WHITE_WINS, scholars.outcome(),
                "chess: scholar's mate wins for white");
        // Stalemate position.
        ChessEngine stale = new ChessEngine();
        stale.loadFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        TestUtil.eq(ChessEngine.Outcome.DRAW, stale.outcome(), "chess: stalemate is a draw");
        TestUtil.eq("Stalemate", stale.outcomeReason(), "chess: stalemate reason");
        // Castling both wings, from a cleared position.
        ChessEngine castle = new ChessEngine();
        castle.loadFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        castle.applyNotation("O-O");
        TestUtil.eq(ChessPiece.KING, ChessPiece.typeOf(castle.pieceAt(7, 6)),
                "chess: kingside castling moves the king to g1");
        TestUtil.eq(ChessPiece.ROOK, ChessPiece.typeOf(castle.pieceAt(7, 5)),
                "chess: kingside castling moves the rook to f1");
        castle.applyNotation("O-O-O");
        TestUtil.eq(ChessPiece.KING, ChessPiece.typeOf(castle.pieceAt(0, 2)),
                "chess: queenside castling moves the king to c8");
        TestUtil.eq(ChessPiece.ROOK, ChessPiece.typeOf(castle.pieceAt(0, 3)),
                "chess: queenside castling moves the rook to d8");
        // Castling through check is illegal: black rook on the f-file.
        ChessEngine throughCheck = new ChessEngine();
        throughCheck.loadFen("5rk1/8/8/8/8/8/8/4K2R w K - 0 1");
        boolean canCastle = false;
        for (ChessMove m : throughCheck.legalMoves()) {
            if (m.isCastling) {
                canCastle = true;
            }
        }
        TestUtil.check(!canCastle, "chess: castling through check is illegal");
        // Castling while in check is illegal.
        ChessEngine inCheck = new ChessEngine();
        inCheck.loadFen("4r3/8/8/8/8/8/8/4K2R w K - 0 1");
        TestUtil.check(inCheck.inCheck(), "chess: test position is check");
        boolean canCastle2 = false;
        for (ChessMove m : inCheck.legalMoves()) {
            if (m.isCastling) {
                canCastle2 = true;
            }
        }
        TestUtil.check(!canCastle2, "chess: castling out of check is illegal");
        // Losing castling rights by moving the rook.
        ChessEngine rights = new ChessEngine();
        rights.loadFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        rights.applyNotation("Ra1a2");
        rights.applyNotation("Ra8a7");
        rights.applyNotation("Ra2a1");
        rights.applyNotation("Ra7a8");
        boolean whiteQueenside = false;
        boolean whiteKingside = false;
        for (ChessMove m : rights.legalMoves()) {
            if (m.isCastling && m.toCol == 2) {
                whiteQueenside = true;
            }
            if (m.isCastling && m.toCol == 6) {
                whiteKingside = true;
            }
        }
        TestUtil.check(!whiteQueenside,
                "chess: queenside rook move forfeits queenside castling");
        TestUtil.check(whiteKingside,
                "chess: the untouched kingside castling right survives");
    }

    private static void chessEnPassantAndPromotion() {
        ChessEngine ep = new ChessEngine();
        for (String s : new String[] { "e2e4", "a7a6", "e4e5", "d7d5", "e5d6" }) {
            ep.applyNotation(s);
        }
        TestUtil.eq(ChessPiece.PAWN, ChessPiece.typeOf(ep.pieceAt(2, 3)),
                "chess: en passant pawn lands on d6");
        TestUtil.eq(ChessPiece.EMPTY, ep.pieceAt(3, 3),
                "chess: en passant removes the bypassed pawn");
        // En passant expires after one move.
        ChessEngine staleEp = new ChessEngine();
        for (String s : new String[] { "e2e4", "a7a6", "e4e5", "d7d5" }) {
            staleEp.applyNotation(s);
        }
        staleEp.applyNotation("a2a3");
        staleEp.applyNotation("a6a5");
        final ChessEngine staleEpF = staleEp;
        TestUtil.rejects(new Runnable() {
            @Override
            public void run() {
                staleEpF.applyNotation("e5d6");
            }
        }, "chess: en passant is only available on the next move");
        // Promotion to queen and to knight, both notations.
        ChessEngine promo = new ChessEngine();
        promo.loadFen("8/P7/8/8/8/8/k6K/8 w - - 0 1");
        promo.applyNotation("a8=Q");
        TestUtil.eq(ChessPiece.of(ChessPiece.QUEEN, Side.WHITE), promo.pieceAt(0, 0),
                "chess: SAN promotion creates a queen");
        ChessEngine promo2 = new ChessEngine();
        promo2.loadFen("8/P7/8/8/8/8/k6K/8 w - - 0 1");
        promo2.applyNotation("a7a8n");
        TestUtil.eq(ChessPiece.of(ChessPiece.KNIGHT, Side.WHITE), promo2.pieceAt(0, 0),
                "chess: coordinate underpromotion creates a knight");
    }

    private static void chessPinsAndDraws() {
        // Pinned rook may slide along the pin but not off it.
        ChessEngine pin = new ChessEngine();
        pin.loadFen("4r3/8/8/8/8/8/4R3/4K2k w - - 0 1");
        Set<String> rookMoves = new HashSet<String>();
        for (ChessMove m : pin.legalMovesFor(6, 4)) {
            rookMoves.add(ChessMove.squareName(m.toRow, m.toCol));
        }
        TestUtil.check(rookMoves.contains("e3"), "chess: pinned rook slides along the file");
        TestUtil.check(!rookMoves.contains("d2"), "chess: pinned rook cannot leave the file");
        TestUtil.check(!rookMoves.contains("f2"), "chess: pinned rook cannot leave the file (2)");
        // Fifty-move rule.
        ChessEngine fifty = new ChessEngine();
        fifty.loadFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 99 1");
        fifty.applyNotation("a1a2");
        TestUtil.eq(ChessEngine.Outcome.DRAW, fifty.outcome(),
                "chess: fifty-move rule is a draw at 100 halfmoves");
        TestUtil.eq("Fifty-move rule", fifty.outcomeReason(), "chess: fifty-move reason");
        // Insufficient material.
        ChessEngine kingsOnly = new ChessEngine();
        kingsOnly.loadFen("8/8/8/8/8/8/8/4K2k w - - 0 1");
        TestUtil.eq(ChessEngine.Outcome.DRAW, kingsOnly.outcome(),
                "chess: king vs king is a draw");
        ChessEngine bishopOnly = new ChessEngine();
        bishopOnly.loadFen("8/8/8/8/8/2B5/8/4K2k w - - 0 1");
        TestUtil.eq(ChessEngine.Outcome.DRAW, bishopOnly.outcome(),
                "chess: king+bishop vs king is a draw");
        ChessEngine knightBishop = new ChessEngine();
        knightBishop.loadFen("8/8/8/8/8/2N5/8/4K1bk w - - 0 1");
        TestUtil.eq(ChessEngine.Outcome.IN_PROGRESS, knightBishop.outcome(),
                "chess: knight vs bishop is not insufficient material");
        // Threefold repetition by shuffling knights.
        ChessEngine rep = new ChessEngine();
        for (String s : new String[] { "Nf3", "Nf6", "Ng1", "Ng8",
                "Nf3", "Nf6", "Ng1", "Ng8" }) {
            rep.applyNotation(s);
        }
        TestUtil.eq(ChessEngine.Outcome.DRAW, rep.outcome(),
                "chess: threefold repetition is a draw");
        // FEN round-trip after e4.
        ChessEngine fen = new ChessEngine();
        fen.applyNotation("e4");
        TestUtil.eq("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                fen.toFen(), "chess: FEN after 1.e4");
        // Illegal moves are rejected and leave the position unchanged.
        ChessEngine illegal = new ChessEngine();
        String before = illegal.toFen();
        final ChessEngine illegalF = illegal;
        TestUtil.rejects(new Runnable() {
            @Override
            public void run() {
                illegalF.applyNotation("e2e5");
            }
        }, "chess: e2-e5 rejected as illegal");
        TestUtil.eq(before, illegal.toFen(), "chess: rejected move leaves FEN unchanged");
        TestUtil.rejects(new Runnable() {
            @Override
            public void run() {
                illegalF.applyNotation("Qh5");
            }
        }, "chess: Qh5 rejected when the queen is blocked");
    }

    private static void chessParsing() {
        // SAN disambiguation between two knights.
        ChessEngine amb = new ChessEngine();
        amb.loadFen("7k/8/8/8/8/8/8/1N1N1K2 w - - 0 1");
        final ChessEngine ambF = amb;
        TestUtil.rejects(new Runnable() {
            @Override
            public void run() {
                ambF.applyNotation("Nc3");
            }
        }, "chess: ambiguous SAN is rejected");
        amb.applyNotation("Nbc3");
        TestUtil.eq(ChessPiece.of(ChessPiece.KNIGHT, Side.WHITE), amb.pieceAt(5, 2),
                "chess: file disambiguation selects the b1 knight");
        ChessEngine amb2 = new ChessEngine();
        amb2.loadFen("7k/8/8/8/8/8/8/1N1N1K2 w - - 0 1");
        amb2.applyNotation("Ndc3");
        TestUtil.eq(ChessPiece.of(ChessPiece.KNIGHT, Side.WHITE), amb2.pieceAt(5, 2),
                "chess: file disambiguation selects the d1 knight");
        // Coordinate, hyphenated coordinate, SAN capture and check suffix.
        ChessEngine mix = new ChessEngine();
        mix.applyNotation("e2-e4");
        mix.applyNotation("e7-e5");
        mix.applyNotation("d2d4");
        mix.applyNotation("exd4");
        TestUtil.eq(ChessPiece.of(ChessPiece.PAWN, Side.BLACK), mix.pieceAt(4, 3),
                "chess: SAN pawn capture works");
        ChessEngine checkSuffix = new ChessEngine();
        checkSuffix.applyNotation("e4");
        checkSuffix.applyNotation("e5");
        checkSuffix.applyNotation("Bc4");
        checkSuffix.applyNotation("Nc6");
        checkSuffix.applyNotation("Qh5");
        checkSuffix.applyNotation("Nf6");
        checkSuffix.applyNotation("Qxf7+");
        TestUtil.check(checkSuffix.inCheck(), "chess: '+' suffix is ignored, check is real");
    }

    private static void controllerBehaviour() {
        GameController c = new GameController();
        TestUtil.eq(GameController.Mode.CHECKERS, c.mode(), "controller: starts in checkers");
        TestUtil.check(!c.movableSquares().isEmpty(), "controller: movable squares reported");
        int[] first = c.movableSquares().get(0);
        GameController.ClickResult sel = c.click(first[0], first[1]);
        TestUtil.eq(GameController.ClickOutcome.SELECTED, sel.outcome, "controller: click selects");
        TestUtil.check(!c.selectedDestinations().isEmpty(),
                "controller: destinations exposed after selection");
        int[] dest = c.selectedDestinations().get(0);
        GameController.ClickResult moved = c.click(dest[0], dest[1]);
        TestUtil.eq(GameController.ClickOutcome.MOVED, moved.outcome, "controller: click applies move");
        TestUtil.eq(1, c.moveHistory().size(), "controller: move recorded");
        // Clock: one minute, fully consumed, flags the side to move.
        c.setClockMinutes(1);
        TestUtil.check(!c.isGameOver(), "controller: game live before flag fall");
        c.tick(60 * 1000L);
        TestUtil.check(c.isGameOver(), "controller: flag fall ends the game");
        TestUtil.check(c.outcomeText().contains("on time"), "controller: timeout outcome text");
        // Promotion flow through clicks.
        GameController p = new GameController();
        p.newGame(GameController.Mode.CHESS);
        p.chess().loadFen("8/P7/8/8/8/8/k6K/8 w - - 0 1");
        GameController.ClickResult s2 = p.click(1, 0);
        TestUtil.eq(GameController.ClickOutcome.SELECTED, s2.outcome,
                "controller: promotion pawn selects");
        GameController.ClickResult promo = p.click(0, 0);
        TestUtil.eq(GameController.ClickOutcome.NEED_PROMOTION, promo.outcome,
                "controller: promotion click asks for a piece");
        GameController.ClickResult done = p.applyPromotion(ChessPiece.QUEEN);
        TestUtil.eq(GameController.ClickOutcome.MOVED, done.outcome,
                "controller: promotion applies");
        TestUtil.eq(ChessPiece.of(ChessPiece.QUEEN, Side.WHITE), p.chess().pieceAt(0, 0),
                "controller: promotion creates the chosen piece");
        // Remote checkers step: a chain may arrive one step at a time.
        GameController net = new GameController();
        net.checkers().clearBoard();
        net.checkers().setPiece(5, 2, CheckersEngine.WHITE_MAN);
        net.checkers().setPiece(4, 3, CheckersEngine.BLACK_MAN);
        net.checkers().setPiece(2, 5, CheckersEngine.BLACK_MAN);
        net.checkers().setPiece(0, 1, CheckersEngine.BLACK_MAN);
        net.checkers().setSideToMove(Side.WHITE);
        TestUtil.check(net.applyRemoteMove("c3xe5"), "controller: remote chain step 1 applies");
        TestUtil.check(net.checkers().isChainActive(), "controller: chain open after remote step 1");
        TestUtil.check(net.applyRemoteMove("e5xg7"), "controller: remote chain step 2 applies");
        TestUtil.check(!net.checkers().isChainActive(), "controller: chain closed after remote step 2");
        // Network play: only the local side's turns accept clicks.
        GameController loc = new GameController();
        loc.setLocalSide(Side.WHITE, true);
        int[] blackSq = loc.movableSquares().get(0);
        GameController.ClickResult ignored = loc.click(blackSq[0], blackSq[1]);
        TestUtil.eq(GameController.ClickOutcome.IGNORED, ignored.outcome,
                "controller: clicks are ignored on the opponent's turn");
        loc.setLocalSide(Side.BLACK, true);
        GameController.ClickResult allowed = loc.click(blackSq[0], blackSq[1]);
        TestUtil.eq(GameController.ClickOutcome.SELECTED, allowed.outcome,
                "controller: clicks work on the local side's turn");
    }
}


