import java.util.ArrayList;
import java.util.List;

/**
 * Legacy facade over {@link CheckersEngine}.
 *
 * <p>Kept so existing callers using {@code Board.getInstance()},
 * {@code cells} and {@code move(piece, dx, dy)} keep working, but the
 * rules now live in the decoupled engine and every move is validated
 * there. The original {@code move} bug is fixed: the piece's
 * coordinates are updated to the destination square, illegal moves
 * mutate nothing and do not advance the turn counter, captures remove
 * the jumped piece, and crowning is honoured.</p>
 *
 * <p>{@code move(piece, x, y)} takes row/column deltas, as in the
 * original code: a quiet step is (+/-1, +/-1), a jump is (+/-2, +/-2).
 * Use {@link #attemptMove(BoardObject, int, int)} or the engine
 * directly when the boolean result matters.</p>
 */
public class Board {
    private int turns = 0;
    private static Board instance = null;
    private List<BoardObject> graveyard = new ArrayList<BoardObject>();

    final CheckersEngine engine = new CheckersEngine();

    public BoardObject[][] cells;

    protected Board() {
        syncCells();
        System.out.println("board ready!");
    }

    /** Singleton, as in the original code. */
    public static Board getInstance() {
        if (instance == null) {
            instance = new Board();
        }
        return instance;
    }

    /** Fresh, non-singleton board backed by a fresh engine (tests/UI). */
    public static Board createFresh() {
        return new Board();
    }

    /** Rebuilds {@code cells} from the engine. Must be called after
     * any direct engine mutation so coordinates never go stale. */
    public void syncCells() {
        this.cells = new BoardObject[8][8];
        graveyard = new ArrayList<BoardObject>();
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int p = engine.pieceAt(r, c);
                if (p != CheckersEngine.EMPTY) {
                    boolean white = CheckersEngine.sideOf(p) == Side.WHITE;
                    BoardObject obj = new BoardObject(
                            white ? "WHITE" : "BLACK", r, c,
                            CheckersEngine.isKing(p));
                    obj.boardRef = this;
                    cells[r][c] = obj;
                }
            }
        }
    }

    /** The decoupled rules engine behind this facade. */
    public CheckersEngine getEngine() {
        return engine;
    }

    public int getTurns() {
        return turns;
    }

    public List<BoardObject> getGraveyard() {
        return graveyard;
    }

    public void printBoard() {
        System.out.println("Move: " + this.turns);
        System.out.println(engine.boardDiagram());
        System.out.println("Graveyard: " + this.graveyard.size());
    }

    /**
     * Attempts the delta move; returns {@code true} iff it was legal
     * and applied. Coordinates, {@code cells} and the graveyard are
     * updated from the engine, so they cannot go stale.
     */
    public boolean attemptMove(BoardObject piece, int dx, int dy) {
        if (piece == null) {
            System.out.println("invalid move!");
            return false;
        }
        int fromRow = piece.getPositionX();
        int fromCol = piece.getPositionY();
        if (!CheckersEngine.inBounds(fromRow, fromCol)
                || cells[fromRow][fromCol] != piece) {
            System.out.println("invalid move!");
            return false;
        }
        int toRow = fromRow + dx;
        int toCol = fromCol + dy;
        if (!CheckersEngine.inBounds(toRow, toCol)) {
            System.out.println("invalid move!");
            return false;
        }
        CheckersMove wanted = null;
        for (CheckersMove m : engine.legalMoves()) {
            if (m.fromRow == fromRow && m.fromCol == fromCol
                    && m.toRow == toRow && m.toCol == toCol) {
                wanted = m;
                break;
            }
        }
        if (wanted == null) {
            System.out.println("invalid move!");
            return false;
        }
        BoardObject captured = null;
        if (wanted.isJump()) {
            captured = cells[wanted.capturedRow][wanted.capturedCol];
        }
        engine.applyMove(wanted);
        turns++;
        if (captured != null) {
            graveyard.add(captured);
        }
        syncCellsPreservingGraveyard();
        System.out.println("Valid move!");
        return true;
    }

    private void syncCellsPreservingGraveyard() {
        List<BoardObject> kept = graveyard;
        syncCells();
        graveyard = kept;
    }

    /** Legacy void form of {@link #attemptMove}. Illegal moves are no-ops. */
    public void move(BoardObject piece, int x, int y) {
        attemptMove(piece, x, y);
    }

    /** Resets the singleton board to the initial position. */
    public void reset() {
        // Rebuild the engine state by replacing its contents via a
        // fresh engine copy: easiest correct reset without exposing
        // engine internals is to re-sync from a new engine.
        CheckersEngine fresh = new CheckersEngine();
        // Copy by replaying setup through the public API: clear and
        // re-place using fresh piece values.
        engine.clearBoard();
        // clearBoard leaves an empty board; restore the start position
        // by copying each square from the fresh engine.
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int p = fresh.pieceAt(r, c);
                if (p != CheckersEngine.EMPTY) {
                    engine.setPiece(r, c, p);
                }
            }
        }
        engine.setSideToMove(fresh.sideToMove());
        turns = 0;
        syncCells();
    }
}
