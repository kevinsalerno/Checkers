import java.util.ArrayList;
import java.util.List;

/**
 * Owns game state and selection for one game, with no UI dependency,
 * so the UI (Swing or headless) is a thin view over it.
 *
 * <p>Selection model: clicking one of your movable pieces selects it
 * and exposes its destinations; clicking a destination applies the
 * move. Clicking your own other piece re-selects; clicking elsewhere
 * clears the selection. A mid-chain click may only continue the chain.
 * A chess promotion click returns {@code NEED_PROMOTION} and keeps the
 * pending move until {@link #applyPromotion(int)} is called.</p>
 */
public final class GameController {
    public enum Mode { CHECKERS, CHESS }

    public enum ClickOutcome { SELECTED, MOVED, NEED_PROMOTION, CLEARED, IGNORED, ILLEGAL }

    public static final class ClickResult {
        public final ClickOutcome outcome;
        public final boolean wasCapture;
        public final boolean givesCheck;
        public final String notation;
        public final String error;

        ClickResult(ClickOutcome outcome, boolean wasCapture, boolean givesCheck,
                    String notation, String error) {
            this.outcome = outcome;
            this.wasCapture = wasCapture;
            this.givesCheck = givesCheck;
            this.notation = notation;
            this.error = error;
        }
    }

    public interface ChangeListener {
        void onChange();
    }

    private Mode mode = Mode.CHECKERS;
    private CheckersEngine checkers = new CheckersEngine();
    private ChessEngine chess = new ChessEngine();
    private int selectedRow = -1;
    private int selectedCol = -1;
    private ChessMove pendingPromotion;
    private final List<String> moveHistory = new ArrayList<String>();
    private final List<ChangeListener> listeners = new ArrayList<ChangeListener>();
    /** Null = both sides local; otherwise only this side is local. */
    private Side localSide;
    private boolean localSideIsHost;
    private long[] remainingMs = { 10 * 60 * 1000L, 10 * 60 * 1000L };
    private boolean timeoutOccurred = false;
    private Side timeoutWinner;
    private boolean resigned = false;
    private Side resignWinner;
    private String lastError = "";

    public GameController() {
    }

    public void addChangeListener(ChangeListener l) {
        listeners.add(l);
    }

    private void fireChange() {
        for (ChangeListener l : listeners) {
            l.onChange();
        }
    }

    public Mode mode() {
        return mode;
    }

    public CheckersEngine checkers() {
        return checkers;
    }

    public ChessEngine chess() {
        return chess;
    }

    public Side sideToMove() {
        return mode == Mode.CHECKERS ? checkers.sideToMove() : chess.sideToMove();
    }

    public void newGame(Mode newMode) {
        mode = newMode;
        if (newMode == Mode.CHECKERS) {
            checkers = new CheckersEngine();
        } else {
            chess = new ChessEngine();
        }
        selectedRow = -1;
        selectedCol = -1;
        pendingPromotion = null;
        moveHistory.clear();
        timeoutOccurred = false;
        resigned = false;
        lastError = "";
        // Keep the configured clock; do not reset localSide.
        fireChange();
    }

    public void setClockMinutes(int minutes) {
        long ms = Math.max(1, minutes) * 60L * 1000L;
        remainingMs[0] = ms;
        remainingMs[1] = ms;
        timeoutOccurred = false;
        fireChange();
    }

    public long remainingMs(Side side) {
        return remainingMs[side == Side.WHITE ? 0 : 1];
    }

    /** Advances the active player's clock; a flag fall ends the game. */
    public void tick(long elapsedMs) {
        if (isGameOver() || elapsedMs <= 0) {
            return;
        }
        int idx = sideToMove() == Side.WHITE ? 0 : 1;
        remainingMs[idx] -= elapsedMs;
        if (remainingMs[idx] <= 0) {
            remainingMs[idx] = 0;
            timeoutOccurred = true;
            timeoutWinner = sideToMove().opponent();
            fireChange();
        }
    }

    public boolean isGameOver() {
        if (timeoutOccurred || resigned) {
            return true;
        }
        return mode == Mode.CHECKERS ? checkers.isGameOver() : chess.isGameOver();
    }

    public String outcomeText() {
        if (timeoutOccurred) {
            return (timeoutWinner == Side.WHITE ? "White" : "Black")
                    + " wins on time";
        }
        if (resigned) {
            return (resignWinner == Side.WHITE ? "White" : "Black")
                    + " wins by resignation";
        }
        if (mode == Mode.CHECKERS) {
            switch (checkers.outcome()) {
                case WHITE_WINS: return "White wins - " + checkers.outcomeReason();
                case BLACK_WINS: return "Black wins - " + checkers.outcomeReason();
                case DRAW: return "Draw - " + checkers.outcomeReason();
                default: return "In progress";
            }
        }
        switch (chess.outcome()) {
            case WHITE_WINS: return "White wins - " + chess.outcomeReason();
            case BLACK_WINS: return "Black wins - " + chess.outcomeReason();
            case DRAW: return "Draw - " + chess.outcomeReason();
            default: return chess.inCheck() ? "Check" : "In progress";
        }
    }

    public void resign(Side winner) {
        resigned = true;
        resignWinner = winner;
        fireChange();
    }

    public void setLocalSide(Side side, boolean isHost) {
        localSide = side;
        localSideIsHost = isHost;
    }

    public void clearLocalSide() {
        localSide = null;
    }

    public Side localSide() {
        return localSide;
    }

    public boolean isLocalTurn() {
        return localSide == null || localSide == sideToMove();
    }

    public boolean hasSelection() {
        return selectedRow >= 0;
    }

    public int selectedRow() {
        return selectedRow;
    }

    public int selectedCol() {
        return selectedCol;
    }

    public boolean hasPendingPromotion() {
        return pendingPromotion != null;
    }

    public List<String> moveHistory() {
        return new ArrayList<String>(moveHistory);
    }

    public String lastError() {
        return lastError;
    }

    /** Squares that hold a piece with at least one legal move. */
    public List<int[]> movableSquares() {
        List<int[]> result = new ArrayList<int[]>();
        if (isGameOver()) {
            return result;
        }
        if (mode == Mode.CHECKERS) {
            for (CheckersMove m : checkers.legalMoves()) {
                boolean seen = false;
                for (int[] sq : result) {
                    if (sq[0] == m.fromRow && sq[1] == m.fromCol) {
                        seen = true;
                    }
                }
                if (!seen) {
                    result.add(new int[] { m.fromRow, m.fromCol });
                }
            }
        } else {
            for (ChessMove m : chess.legalMoves()) {
                boolean seen = false;
                for (int[] sq : result) {
                    if (sq[0] == m.fromRow && sq[1] == m.fromCol) {
                        seen = true;
                    }
                }
                if (!seen) {
                    result.add(new int[] { m.fromRow, m.fromCol });
                }
            }
        }
        return result;
    }

    /** Legal destinations from the selected square, with capture flags. */
    public List<int[]> selectedDestinations() {
        List<int[]> result = new ArrayList<int[]>();
        if (!hasSelection()) {
            return result;
        }
        if (mode == Mode.CHECKERS) {
            for (CheckersMove m : checkers.legalMovesFor(selectedRow, selectedCol)) {
                result.add(new int[] { m.toRow, m.toCol, m.isJump() ? 1 : 0 });
            }
        } else {
            for (ChessMove m : chess.legalMovesFor(selectedRow, selectedCol)) {
                result.add(new int[] { m.toRow, m.toCol, chess.isCapture(m) ? 1 : 0 });
            }
        }
        return result;
    }

    public boolean isMovable(int row, int col) {
        for (int[] sq : movableSquares()) {
            if (sq[0] == row && sq[1] == col) {
                return true;
            }
        }
        return false;
    }

    private void clearSelection() {
        selectedRow = -1;
        selectedCol = -1;
    }

    /**
     * Applies a move given in engine notation (coordinate/SAN for chess,
     * coordinate or chain notation for checkers). Used by the network
     * peer and the headless harness path. Returns false on illegal input.
     */
    public boolean applyNotation(String notation) {
        lastError = "";
        try {
            if (mode == Mode.CHECKERS) {
                List<CheckersMove> steps = checkers.applyNotation(notation);
                for (CheckersMove step : steps) {
                    moveHistory.add(step.toString());
                }
            } else {
                ChessMove move = chess.applyNotation(notation);
                moveHistory.add(move.toString());
            }
            clearSelection();
            pendingPromotion = null;
            fireChange();
            return true;
        } catch (IllegalArgumentException e) {
            lastError = e.getMessage();
            return false;
        }
    }

    /**
     * Applies one move received from a network peer. For checkers this
     * is a single step, so a multi-jump chain arrives step by step and
     * may legitimately leave the chain open; the complete-chain rule
     * in {@link #applyNotation} does not apply here.
     */
    public boolean applyRemoteMove(String notation) {
        lastError = "";
        try {
            if (mode == Mode.CHECKERS) {
                CheckersMove step = checkers.parseSingleStep(notation);
                checkers.applyMove(step);
                moveHistory.add(step.toString());
            } else {
                ChessMove move = chess.applyNotation(notation);
                moveHistory.add(move.toString());
            }
            if (mode != Mode.CHECKERS || !checkers.isChainActive()) {
                clearSelection();
            } else {
                selectedRow = checkers.chainRow();
                selectedCol = checkers.chainCol();
            }
            pendingPromotion = null;
            fireChange();
            return true;
        } catch (IllegalArgumentException e) {
            lastError = e.getMessage();
            return false;
        }
    }

    /** Handles a board click; see the class docs for the selection model. */
    public ClickResult click(int row, int col) {
        lastError = "";
        if (isGameOver() || !isLocalTurn()) {
            return new ClickResult(ClickOutcome.IGNORED, false, false, null, null);
        }
        if (hasPendingPromotion()) {
            return new ClickResult(ClickOutcome.IGNORED, false, false, null, null);
        }
        // Destination click?
        if (hasSelection()) {
            if (mode == Mode.CHECKERS) {
                for (CheckersMove m : checkers.legalMovesFor(selectedRow, selectedCol)) {
                    if (m.toRow == row && m.toCol == col) {
                        return applyCheckersStep(m);
                    }
                }
            } else {
                List<ChessMove> targets = new ArrayList<ChessMove>();
                for (ChessMove m : chess.legalMovesFor(selectedRow, selectedCol)) {
                    if (m.toRow == row && m.toCol == col) {
                        targets.add(m);
                    }
                }
                if (!targets.isEmpty()) {
                    boolean needsPromo = targets.get(0).promotion != ChessPiece.EMPTY;
                    if (needsPromo) {
                        pendingPromotion = targets.get(0);
                        return new ClickResult(ClickOutcome.NEED_PROMOTION,
                                chess.isCapture(targets.get(0)), false, null, null);
                    }
                    return applyChessMove(targets.get(0));
                }
            }
            // Not a destination: fall through to (re)selection.
        }
        if (isMovable(row, col)) {
            selectedRow = row;
            selectedCol = col;
            SoundEffects.playSelect();
            fireChange();
            return new ClickResult(ClickOutcome.SELECTED, false, false, null, null);
        }
        if (hasSelection()) {
            clearSelection();
            fireChange();
            return new ClickResult(ClickOutcome.CLEARED, false, false, null, null);
        }
        return new ClickResult(ClickOutcome.IGNORED, false, false, null, null);
    }

    private ClickResult applyCheckersStep(CheckersMove step) {
        boolean wasJump = step.isJump();
        checkers.applyMove(step);
        moveHistory.add(step.toString());
        boolean chainContinues = checkers.isChainActive();
        if (chainContinues) {
            selectedRow = step.toRow;
            selectedCol = step.toCol;
        } else {
            clearSelection();
        }
        fireChange();
        return new ClickResult(ClickOutcome.MOVED, wasJump, false,
                step.toString(), null);
    }

    private ClickResult applyChessMove(ChessMove move) {
        boolean wasCapture = chess.isCapture(move);
        chess.applyMove(move);
        moveHistory.add(move.toString());
        boolean givesCheck = chess.inCheck();
        clearSelection();
        fireChange();
        return new ClickResult(ClickOutcome.MOVED, wasCapture, givesCheck,
                move.toString(), null);
    }

    /** Completes a pending promotion with the chosen piece type. */
    public ClickResult applyPromotion(int promotionType) {
        if (pendingPromotion == null) {
            return new ClickResult(ClickOutcome.ILLEGAL, false, false, null,
                    "No promotion is pending");
        }
        ChessMove chosen = null;
        for (ChessMove m : chess.legalMovesFor(
                pendingPromotion.fromRow, pendingPromotion.fromCol)) {
            if (m.toRow == pendingPromotion.toRow
                    && m.toCol == pendingPromotion.toCol
                    && m.promotion == promotionType) {
                chosen = m;
                break;
            }
        }
        pendingPromotion = null;
        if (chosen == null) {
            return new ClickResult(ClickOutcome.ILLEGAL, false, false, null,
                    "That promotion is not legal here");
        }
        return applyChessMove(chosen);
    }

    public void cancelPromotion() {
        pendingPromotion = null;
        clearSelection();
        fireChange();
    }

    public String statusText() {
        if (isGameOver()) {
            return outcomeText();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(sideToMove() == Side.WHITE ? "White" : "Black").append(" to move");
        if (mode == Mode.CHESS && chess.inCheck()) {
            sb.append(" - check");
        }
        if (mode == Mode.CHECKERS && checkers.isChainActive()) {
            sb.append(" - complete the multi-jump");
        }
        if (localSide != null && !isLocalTurn()) {
            sb.append(" (waiting for opponent)");
        }
        return sb.toString();
    }
}
