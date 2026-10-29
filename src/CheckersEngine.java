import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * American / English draughts rules, decoupled from any UI.
 *
 * <ul>
 * <li>Men move one diagonal step forward only; kings move one diagonal
 * step in any direction. Kings are non-flying: a jump is always over
 * exactly one adjacent enemy piece onto the empty square behind it.</li>
 * <li>Captures are mandatory: when any jump exists for the side to move,
 * only jumps are legal.</li>
 * <li>A jump that leaves a further jump open for the same piece must be
 * continued (multi-jump chain); the turn does not pass until the chain
 * ends. Reaching the far row crowns the piece and ends the turn at once,
 * even if a jump would be open to the new king.</li>
 * <li>The game ends when a side has no pieces or no legal move (that side
 * loses). Draws: threefold repetition, and 80 ply without a capture or a
 * crowning (the 40-move rule).</li>
 * </ul>
 * Playable squares are the dark squares, where (row + col) is odd.
 * BLACK starts on rows 0-2 and moves first in this engine (the side to
 * move is BLACK in the initial position; the UI may swap first player).
 * Coordinates: row 0 = rank 8, col 0 = file a.
 */
public class CheckersEngine {
    public static final int EMPTY = 0;
    public static final int WHITE_MAN = 1;
    public static final int WHITE_KING = 2;
    public static final int BLACK_MAN = 3;
    public static final int BLACK_KING = 4;

    /** Ply without a capture or a crowning at which the game is drawn. */
    public static final int DRAW_PLY_LIMIT = 80;

    public enum Outcome { IN_PROGRESS, WHITE_WINS, BLACK_WINS, DRAW }

    private final int[][] board = new int[8][8];
    private Side sideToMove = Side.BLACK;
    private int chainRow = -1;
    private int chainCol = -1;
    private int nonCapturePly = 0;
    private int plyCount = 0;
    private final Map<String, Integer> positionCounts = new HashMap<String, Integer>();
    private Outcome outcome = Outcome.IN_PROGRESS;
    private String outcomeReason = "";

    public CheckersEngine() {
        setup();
    }

    private CheckersEngine(boolean setup) {
        if (setup) {
            setup();
        }
    }

    private void setup() {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                board[r][c] = EMPTY;
            }
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 8; c++) {
                if ((r + c) % 2 == 1) {
                    board[r][c] = BLACK_MAN;
                }
            }
        }
        for (int r = 5; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if ((r + c) % 2 == 1) {
                    board[r][c] = WHITE_MAN;
                }
            }
        }
        sideToMove = Side.BLACK;
        chainRow = -1;
        chainCol = -1;
        nonCapturePly = 0;
        plyCount = 0;
        outcome = Outcome.IN_PROGRESS;
        outcomeReason = "";
        positionCounts.clear();
        recordPosition();
        updateOutcome();
    }

    /** Deep copy, including history counts and chain state. */
    public CheckersEngine copy() {
        CheckersEngine e = new CheckersEngine(false);
        for (int r = 0; r < 8; r++) {
            System.arraycopy(board[r], 0, e.board[r], 0, 8);
        }
        e.sideToMove = sideToMove;
        e.chainRow = chainRow;
        e.chainCol = chainCol;
        e.nonCapturePly = nonCapturePly;
        e.plyCount = plyCount;
        e.positionCounts.putAll(positionCounts);
        e.outcome = outcome;
        e.outcomeReason = outcomeReason;
        return e;
    }

    /** Removes every piece. Test / setup helper; resets move state. */
    public void clearBoard() {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                board[r][c] = EMPTY;
            }
        }
        sideToMove = Side.BLACK;
        chainRow = -1;
        chainCol = -1;
        nonCapturePly = 0;
        plyCount = 0;
        outcome = Outcome.IN_PROGRESS;
        outcomeReason = "";
        positionCounts.clear();
        recordPosition();
        updateOutcome();
    }

    public void setPiece(int row, int col, int piece) {
        checkSquare(row, col);
        board[row][col] = piece;
        positionCounts.clear();
        recordPosition();
        updateOutcome();
    }

    public void setSideToMove(Side side) {
        sideToMove = side;
        positionCounts.clear();
        recordPosition();
        updateOutcome();
    }

    public int pieceAt(int row, int col) {
        checkSquare(row, col);
        return board[row][col];
    }

    public Side sideToMove() {
        return sideToMove;
    }

    public boolean isChainActive() {
        return chainRow >= 0;
    }

    public int chainRow() {
        return chainRow;
    }

    public int chainCol() {
        return chainCol;
    }

    public int nonCapturePly() {
        return nonCapturePly;
    }

    public int plyCount() {
        return plyCount;
    }

    public Outcome outcome() {
        return outcome;
    }

    public String outcomeReason() {
        return outcomeReason;
    }

    public boolean isGameOver() {
        return outcome != Outcome.IN_PROGRESS;
    }

    public static Side sideOf(int piece) {
        if (piece == WHITE_MAN || piece == WHITE_KING) {
            return Side.WHITE;
        }
        if (piece == BLACK_MAN || piece == BLACK_KING) {
            return Side.BLACK;
        }
        return null;
    }

    public static boolean isKing(int piece) {
        return piece == WHITE_KING || piece == BLACK_KING;
    }

    public static boolean inBounds(int row, int col) {
        return row >= 0 && row < 8 && col >= 0 && col < 8;
    }

    private static void checkSquare(int row, int col) {
        if (!inBounds(row, col)) {
            throw new IllegalArgumentException("Square out of bounds: " + row + "," + col);
        }
    }

    public int countPieces(Side side) {
        int n = 0;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (sideOf(board[r][c]) == side) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Directions a piece may step/jump in: {dRow, dCol}. */
    private static int[][] directions(int piece) {
        if (isKing(piece)) {
            return new int[][] { { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 } };
        }
        int fwd = sideOf(piece) == Side.WHITE ? -1 : 1;
        return new int[][] { { fwd, -1 }, { fwd, 1 } };
    }

    private void jumpsFrom(int row, int col, List<CheckersMove> out) {
        int piece = board[row][col];
        for (int[] d : directions(piece)) {
            int midR = row + d[0];
            int midC = col + d[1];
            int landR = row + 2 * d[0];
            int landC = col + 2 * d[1];
            if (!inBounds(landR, landC)) {
                continue;
            }
            int mid = board[midR][midC];
            if (mid != EMPTY && sideOf(mid) == sideOf(piece).opponent()
                    && board[landR][landC] == EMPTY) {
                out.add(new CheckersMove(row, col, landR, landC, midR, midC));
            }
        }
    }

    private void quietsFrom(int row, int col, List<CheckersMove> out) {
        int piece = board[row][col];
        for (int[] d : directions(piece)) {
            int r = row + d[0];
            int c = col + d[1];
            if (inBounds(r, c) && board[r][c] == EMPTY) {
                out.add(new CheckersMove(row, col, r, c));
            }
        }
    }

    /** All legal steps from one square, honouring mandatory captures. */
    public List<CheckersMove> legalMovesFor(int row, int col) {
        checkSquare(row, col);
        List<CheckersMove> result = new ArrayList<CheckersMove>();
        if (isGameOver() || sideOf(board[row][col]) != sideToMove) {
            return result;
        }
        if (isChainActive() && (row != chainRow || col != chainCol)) {
            return result;
        }
        List<CheckersMove> jumps = new ArrayList<CheckersMove>();
        if (isChainActive()) {
            jumpsFrom(row, col, jumps);
            return jumps;
        }
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (sideOf(board[r][c]) == sideToMove) {
                    jumpsFrom(r, c, jumps);
                }
            }
        }
        if (!jumps.isEmpty()) {
            jumpsFrom(row, col, result);
            return result;
        }
        quietsFrom(row, col, result);
        return result;
    }

    /** All legal steps for the side to move (chain-restricted if mid-chain). */
    public List<CheckersMove> legalMoves() {
        List<CheckersMove> result = new ArrayList<CheckersMove>();
        if (isGameOver()) {
            return result;
        }
        if (isChainActive()) {
            jumpsFrom(chainRow, chainCol, result);
            return result;
        }
        List<CheckersMove> jumps = new ArrayList<CheckersMove>();
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (sideOf(board[r][c]) == sideToMove) {
                    jumpsFrom(r, c, jumps);
                }
            }
        }
        if (!jumps.isEmpty()) {
            return jumps;
        }
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (sideOf(board[r][c]) == sideToMove) {
                    quietsFrom(r, c, result);
                }
            }
        }
        return result;
    }

    public boolean isLegal(CheckersMove move) {
        return legalMoves().contains(move);
    }

    /**
     * Applies one legal step. Throws IllegalArgumentException otherwise
     * and leaves the position unchanged.
     */
    public void applyMove(CheckersMove move) {
        if (move == null || !legalMoves().contains(move)) {
            throw new IllegalArgumentException("Illegal checkers move: " + move);
        }
        int piece = board[move.fromRow][move.fromCol];
        board[move.fromRow][move.fromCol] = EMPTY;
        if (move.isJump()) {
            board[move.capturedRow][move.capturedCol] = EMPTY;
        }
        boolean crowned = false;
        if (!isKing(piece)) {
            if (piece == WHITE_MAN && move.toRow == 0) {
                piece = WHITE_KING;
                crowned = true;
            } else if (piece == BLACK_MAN && move.toRow == 7) {
                piece = BLACK_KING;
                crowned = true;
            }
        }
        board[move.toRow][move.toCol] = piece;
        plyCount++;
        if (move.isJump() || crowned) {
            nonCapturePly = 0;
        } else {
            nonCapturePly++;
        }
        // Chain continuation: same piece, same turn, unless crowned.
        chainRow = -1;
        chainCol = -1;
        if (move.isJump() && !crowned) {
            List<CheckersMove> further = new ArrayList<CheckersMove>();
            // Side has not switched yet; jumpsFrom reads board only.
            Side saved = sideToMove;
            jumpsFrom(move.toRow, move.toCol, further);
            sideToMove = saved;
            if (!further.isEmpty()) {
                chainRow = move.toRow;
                chainCol = move.toCol;
            }
        }
        if (!isChainActive()) {
            sideToMove = sideToMove.opponent();
        }
        recordPosition();
        updateOutcome();
    }

    /**
     * Applies a coordinate notation such as "a3-b4", "a3xb4",
     * "a3b4" or a chain "a3xc5xe7". Returns the steps applied.
     * The whole notation is validated before anything is applied:
     * an illegal chain leaves the position unchanged.
     */
    public List<CheckersMove> applyNotation(String notation) {
        List<CheckersMove> steps = parseNotation(notation);
        for (CheckersMove step : steps) {
            applyMove(step);
        }
        return steps;
    }

    /** Parses notation against a copy, so failures mutate nothing. */
    public List<CheckersMove> parseNotation(String notation) {
        if (notation == null) {
            throw new IllegalArgumentException("Null checkers notation");
        }
        String cleaned = notation.trim().replaceAll("[\\s,]+", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Empty checkers notation");
        }
        String[] parts = cleaned.split("[-xX]");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Bad checkers notation: " + notation);
        }
        List<int[]> squares = new ArrayList<int[]>();
        for (String part : parts) {
            squares.add(CheckersMove.parseSquare(part));
        }
        CheckersEngine probe = copy();
        // A fresh notation must not start mid-chain unless this engine is.
        List<CheckersMove> steps = new ArrayList<CheckersMove>();
        for (int i = 0; i + 1 < squares.size(); i++) {
            int[] from = squares.get(i);
            int[] to = squares.get(i + 1);
            CheckersMove found = null;
            for (CheckersMove m : probe.legalMoves()) {
                if (m.fromRow == from[0] && m.fromCol == from[1]
                        && m.toRow == to[0] && m.toCol == to[1]) {
                    found = m;
                    break;
                }
            }
            if (found == null) {
                throw new IllegalArgumentException(
                        "Illegal checkers step " + CheckersMove.squareName(from[0], from[1])
                        + " to " + CheckersMove.squareName(to[0], to[1])
                        + " in notation: " + notation);
            }
            steps.add(found);
            probe.applyMove(found);
        }
        // A chain notation must be complete: it may not stop mid-chain
        // while a further jump is compulsory.
        if (probe.isChainActive()) {
            throw new IllegalArgumentException(
                    "Incomplete multi-jump in notation: " + notation);
        }
        return steps;
    }

    /**
     * Parses one step ("a3-b4" / "a3xb4") against the current position,
     * without requiring a multi-jump chain to be complete. Used for
     * single-step network moves; {@link #parseNotation} is the
     * complete-turn form.
     */
    public CheckersMove parseSingleStep(String notation) {
        if (notation == null) {
            throw new IllegalArgumentException("Null checkers notation");
        }
        String cleaned = notation.trim().replaceAll("[\\s,]+", "");
        String[] parts = cleaned.split("[-xX]");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Bad checkers step: " + notation);
        }
        int[] from = CheckersMove.parseSquare(parts[0]);
        int[] to = CheckersMove.parseSquare(parts[1]);
        for (CheckersMove m : legalMoves()) {
            if (m.fromRow == from[0] && m.fromCol == from[1]
                    && m.toRow == to[0] && m.toCol == to[1]) {
                return m;
            }
        }
        throw new IllegalArgumentException("Illegal checkers step in: " + notation);
    }

    private String positionKey() {
        StringBuilder sb = new StringBuilder(80);
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                sb.append((char) ('0' + board[r][c]));
            }
        }
        sb.append(sideToMove == Side.WHITE ? 'w' : 'b');
        sb.append(chainRow).append(',').append(chainCol);
        return sb.toString();
    }

    private void recordPosition() {
        String key = positionKey();
        Integer n = positionCounts.get(key);
        positionCounts.put(key, n == null ? 1 : n + 1);
    }

    private void updateOutcome() {
        // Recompute from scratch so test positions built with setPiece
        // in several steps are evaluated on their final position.
        outcome = Outcome.IN_PROGRESS;
        outcomeReason = "";
        if (countPieces(Side.WHITE) == 0) {
            outcome = Outcome.BLACK_WINS;
            outcomeReason = "White has no pieces left";
            return;
        }
        if (countPieces(Side.BLACK) == 0) {
            outcome = Outcome.WHITE_WINS;
            outcomeReason = "Black has no pieces left";
            return;
        }
        Integer reps = positionCounts.get(positionKey());
        if (reps != null && reps >= 3) {
            outcome = Outcome.DRAW;
            outcomeReason = "Threefold repetition";
            return;
        }
        if (nonCapturePly >= DRAW_PLY_LIMIT) {
            outcome = Outcome.DRAW;
            outcomeReason = "40-move rule: " + DRAW_PLY_LIMIT
                    + " ply without a capture or crowning";
            return;
        }
        // No legal move: only meaningful once a side is actually to move
        // with pieces present; legalMoves() is empty exactly then.
        if (!isChainActive() && legalMoves().isEmpty()) {
            outcome = sideToMove == Side.WHITE ? Outcome.BLACK_WINS : Outcome.WHITE_WINS;
            outcomeReason = (sideToMove == Side.WHITE ? "White" : "Black")
                    + " has no legal move";
        }
    }

    /** Human-readable board diagram, row 8 at the top. */
    public String boardDiagram() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            sb.append(8 - r).append(' ');
            for (int c = 0; c < 8; c++) {
                char ch;
                switch (board[r][c]) {
                    case WHITE_MAN: ch = 'w'; break;
                    case WHITE_KING: ch = 'W'; break;
                    case BLACK_MAN: ch = 'b'; break;
                    case BLACK_KING: ch = 'B'; break;
                    default: ch = ((r + c) % 2 == 1) ? '.' : ' ';
                }
                sb.append(ch).append(' ');
            }
            sb.append('\n');
        }
        sb.append("  a b c d e f g h\n");
        sb.append(sideToMove == Side.WHITE ? "White" : "Black").append(" to move");
        if (isChainActive()) {
            sb.append(", chain at ").append(CheckersMove.squareName(chainRow, chainCol));
        }
        return sb.toString();
    }
}



