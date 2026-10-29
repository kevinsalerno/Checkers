import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full chess rules, decoupled from any UI.
 *
 * <p>Supports castling (both sides, through check is illegal), en passant,
 * promotion to any piece, check, checkmate, stalemate, the fifty-move
 * rule, threefold repetition and insufficient material. Moves are
 * generated pseudo-legally and filtered by king safety on a copy, so
 * pinned pieces, discovered checks and castling through check are all
 * handled by the same legality test.</p>
 *
 * <p>Coordinates: row 0 = rank 8, col 0 = file a. White starts on rows
 * 6-7 and moves first.</p>
 */
public class ChessEngine {
    public enum Outcome { IN_PROGRESS, WHITE_WINS, BLACK_WINS, DRAW }

    private final int[][] board = new int[8][8];
    private Side sideToMove = Side.WHITE;
    private boolean whiteKingside = true;
    private boolean whiteQueenside = true;
    private boolean blackKingside = true;
    private boolean blackQueenside = true;
    private int epRow = -1;
    private int epCol = -1;
    private int halfmoveClock = 0;
    private int fullmoveNumber = 1;
    private final Map<String, Integer> positionCounts = new HashMap<String, Integer>();
    private Outcome outcome = Outcome.IN_PROGRESS;
    private String outcomeReason = "";

    public ChessEngine() {
        setup();
    }

    private ChessEngine(boolean setup) {
        if (setup) {
            setup();
        }
    }

    private void setup() {
        int[] back = { ChessPiece.ROOK, ChessPiece.KNIGHT, ChessPiece.BISHOP,
                ChessPiece.QUEEN, ChessPiece.KING, ChessPiece.BISHOP,
                ChessPiece.KNIGHT, ChessPiece.ROOK };
        for (int c = 0; c < 8; c++) {
            board[0][c] = ChessPiece.of(back[c], Side.BLACK);
            board[1][c] = ChessPiece.of(ChessPiece.PAWN, Side.BLACK);
            board[6][c] = ChessPiece.of(ChessPiece.PAWN, Side.WHITE);
            board[7][c] = ChessPiece.of(back[c], Side.WHITE);
        }
        for (int r = 2; r < 6; r++) {
            for (int c = 0; c < 8; c++) {
                board[r][c] = ChessPiece.EMPTY;
            }
        }
        sideToMove = Side.WHITE;
        whiteKingside = true;
        whiteQueenside = true;
        blackKingside = true;
        blackQueenside = true;
        epRow = -1;
        epCol = -1;
        halfmoveClock = 0;
        fullmoveNumber = 1;
        outcome = Outcome.IN_PROGRESS;
        outcomeReason = "";
        positionCounts.clear();
        recordPosition();
        updateOutcome();
    }

    public ChessEngine copy() {
        ChessEngine e = new ChessEngine(false);
        for (int r = 0; r < 8; r++) {
            System.arraycopy(board[r], 0, e.board[r], 0, 8);
        }
        e.sideToMove = sideToMove;
        e.whiteKingside = whiteKingside;
        e.whiteQueenside = whiteQueenside;
        e.blackKingside = blackKingside;
        e.blackQueenside = blackQueenside;
        e.epRow = epRow;
        e.epCol = epCol;
        e.halfmoveClock = halfmoveClock;
        e.fullmoveNumber = fullmoveNumber;
        e.positionCounts.putAll(positionCounts);
        e.outcome = outcome;
        e.outcomeReason = outcomeReason;
        return e;
    }

    public int pieceAt(int row, int col) {
        return board[row][col];
    }

    public Side sideToMove() {
        return sideToMove;
    }

    public int halfmoveClock() {
        return halfmoveClock;
    }

    public int fullmoveNumber() {
        return fullmoveNumber;
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

    /** Is the given side's king currently in check? */
    public boolean inCheck(Side side) {
        int[] king = findKing(side);
        if (king == null) {
            return false;
        }
        return isSquareAttacked(king[0], king[1], side.opponent());
    }

    public boolean inCheck() {
        return inCheck(sideToMove);
    }

    private int[] findKing(Side side) {
        int wanted = ChessPiece.of(ChessPiece.KING, side);
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (board[r][c] == wanted) {
                    return new int[] { r, c };
                }
            }
        }
        return null;
    }

    public static boolean inBounds(int row, int col) {
        return row >= 0 && row < 8 && col >= 0 && col < 8;
    }

    /** Test/setup helper: clears the board and resets all state. */
    public void clearBoard() {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                board[r][c] = ChessPiece.EMPTY;
            }
        }
        sideToMove = Side.WHITE;
        whiteKingside = true;
        whiteQueenside = true;
        blackKingside = true;
        blackQueenside = true;
        epRow = -1;
        epCol = -1;
        halfmoveClock = 0;
        fullmoveNumber = 1;
        outcome = Outcome.IN_PROGRESS;
        outcomeReason = "";
        positionCounts.clear();
        recordPosition();
        updateOutcome();
    }

    public void setPiece(int row, int col, int piece) {
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

    public void setCastlingRights(boolean wk, boolean wq, boolean bk, boolean bq) {
        whiteKingside = wk;
        whiteQueenside = wq;
        blackKingside = bk;
        blackQueenside = bq;
        positionCounts.clear();
        recordPosition();
        updateOutcome();
    }

    public void setEnPassant(int row, int col) {
        epRow = row;
        epCol = col;
    }

    public boolean canCastleKingside(Side side) {
        return side == Side.WHITE ? whiteKingside : blackKingside;
    }

    public boolean canCastleQueenside(Side side) {
        return side == Side.WHITE ? whiteQueenside : blackQueenside;
    }

    /** Is (row,col) attacked by any piece of {@code by}? */
    public boolean isSquareAttacked(int row, int col, Side by) {
        // Pawns: an attacking pawn sits one step behind the target
        // along its direction of travel.
        int pawnDir = by == Side.WHITE ? -1 : 1;
        int pawnRow = row - pawnDir;
        for (int dc : new int[] { -1, 1 }) {
            int c = col + dc;
            if (inBounds(pawnRow, c)
                    && board[pawnRow][c] == ChessPiece.of(ChessPiece.PAWN, by)) {
                return true;
            }
        }
        int[][] knightOffsets = { { -2, -1 }, { -2, 1 }, { -1, -2 }, { -1, 2 },
                { 1, -2 }, { 1, 2 }, { 2, -1 }, { 2, 1 } };
        for (int[] o : knightOffsets) {
            int r = row + o[0];
            int c = col + o[1];
            if (inBounds(r, c)
                    && board[r][c] == ChessPiece.of(ChessPiece.KNIGHT, by)) {
                return true;
            }
        }
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) {
                    continue;
                }
                int r = row + dr;
                int c = col + dc;
                if (inBounds(r, c)
                        && board[r][c] == ChessPiece.of(ChessPiece.KING, by)) {
                    return true;
                }
            }
        }
        int[][] ortho = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };
        for (int[] d : ortho) {
            int r = row + d[0];
            int c = col + d[1];
            while (inBounds(r, c)) {
                int p = board[r][c];
                if (p != ChessPiece.EMPTY) {
                    int t = ChessPiece.typeOf(p);
                    if (ChessPiece.sideOf(p) == by && (t == ChessPiece.ROOK || t == ChessPiece.QUEEN)) {
                        return true;
                    }
                    break;
                }
                r += d[0];
                c += d[1];
            }
        }
        int[][] diag = { { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 } };
        for (int[] d : diag) {
            int r = row + d[0];
            int c = col + d[1];
            while (inBounds(r, c)) {
                int p = board[r][c];
                if (p != ChessPiece.EMPTY) {
                    int t = ChessPiece.typeOf(p);
                    if (ChessPiece.sideOf(p) == by && (t == ChessPiece.BISHOP || t == ChessPiece.QUEEN)) {
                        return true;
                    }
                    break;
                }
                r += d[0];
                c += d[1];
            }
        }
        return false;
    }

    private void addSliderMoves(int row, int col, int[][] dirs,
                                List<ChessMove> out) {
        int piece = board[row][col];
        Side side = ChessPiece.sideOf(piece);
        for (int[] d : dirs) {
            int r = row + d[0];
            int c = col + d[1];
            while (inBounds(r, c)) {
                int target = board[r][c];
                if (target == ChessPiece.EMPTY) {
                    out.add(new ChessMove(row, col, r, c));
                } else {
                    if (ChessPiece.sideOf(target) != side) {
                        out.add(new ChessMove(row, col, r, c));
                    }
                    break;
                }
                r += d[0];
                c += d[1];
            }
        }
    }

    private void addStepMoves(int row, int col, int[][] offsets,
                              List<ChessMove> out) {
        int piece = board[row][col];
        Side side = ChessPiece.sideOf(piece);
        for (int[] o : offsets) {
            int r = row + o[0];
            int c = col + o[1];
            if (!inBounds(r, c)) {
                continue;
            }
            int target = board[r][c];
            if (target == ChessPiece.EMPTY || ChessPiece.sideOf(target) != side) {
                out.add(new ChessMove(row, col, r, c));
            }
        }
    }

    private void addPawnMoves(int row, int col, List<ChessMove> out) {
        int piece = board[row][col];
        Side side = ChessPiece.sideOf(piece);
        int dir = side == Side.WHITE ? -1 : 1;
        int startRow = side == Side.WHITE ? 6 : 1;
        int promoRow = side == Side.WHITE ? 0 : 7;
        int one = row + dir;
        if (inBounds(one, col) && board[one][col] == ChessPiece.EMPTY) {
            addPawnAdvance(row, col, one, col, promoRow, out);
            int two = row + 2 * dir;
            if (row == startRow && board[two][col] == ChessPiece.EMPTY) {
                out.add(new ChessMove(row, col, two, col));
            }
        }
        for (int dc : new int[] { -1, 1 }) {
            int c = col + dc;
            if (!inBounds(one, c)) {
                continue;
            }
            int target = board[one][c];
            if (target != ChessPiece.EMPTY && ChessPiece.sideOf(target) != side) {
                addPawnAdvance(row, col, one, c, promoRow, out);
            } else if (one == epRow && c == epCol && target == ChessPiece.EMPTY) {
                out.add(new ChessMove(row, col, one, c,
                        ChessPiece.EMPTY, true, false));
            }
        }
    }

    private void addPawnAdvance(int fromRow, int fromCol, int toRow, int toCol,
                                int promoRow, List<ChessMove> out) {
        if (toRow == promoRow) {
            for (int promo : new int[] { ChessPiece.QUEEN, ChessPiece.ROOK,
                    ChessPiece.BISHOP, ChessPiece.KNIGHT }) {
                out.add(new ChessMove(fromRow, fromCol, toRow, toCol,
                        promo, false, false));
            }
        } else {
            out.add(new ChessMove(fromRow, fromCol, toRow, toCol));
        }
    }

    private void addCastlingMoves(int row, int col, List<ChessMove> out) {
        Side side = sideToMove;
        int backRow = side == Side.WHITE ? 7 : 0;
        if (row != backRow || col != 4) {
            return;
        }
        if (inCheck(side)) {
            return;
        }
        Side enemy = side.opponent();
        boolean ks = side == Side.WHITE ? whiteKingside : blackKingside;
        boolean qs = side == Side.WHITE ? whiteQueenside : blackQueenside;
        if (ks && board[backRow][5] == ChessPiece.EMPTY
                && board[backRow][6] == ChessPiece.EMPTY
                && board[backRow][7] == ChessPiece.of(ChessPiece.ROOK, side)
                && !isSquareAttacked(backRow, 5, enemy)
                && !isSquareAttacked(backRow, 6, enemy)) {
            out.add(new ChessMove(row, col, backRow, 6,
                    ChessPiece.EMPTY, false, true));
        }
        if (qs && board[backRow][3] == ChessPiece.EMPTY
                && board[backRow][2] == ChessPiece.EMPTY
                && board[backRow][1] == ChessPiece.EMPTY
                && board[backRow][0] == ChessPiece.of(ChessPiece.ROOK, side)
                && !isSquareAttacked(backRow, 3, enemy)
                && !isSquareAttacked(backRow, 2, enemy)) {
            out.add(new ChessMove(row, col, backRow, 2,
                    ChessPiece.EMPTY, false, true));
        }
    }

    /** Pseudo-legal moves (king safety not yet checked). */
    public List<ChessMove> pseudoLegalMoves() {
        List<ChessMove> out = new ArrayList<ChessMove>();
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int piece = board[r][c];
                if (piece == ChessPiece.EMPTY || ChessPiece.sideOf(piece) != sideToMove) {
                    continue;
                }
                int type = ChessPiece.typeOf(piece);
                switch (type) {
                    case ChessPiece.PAWN:
                        addPawnMoves(r, c, out);
                        break;
                    case ChessPiece.KNIGHT:
                        addStepMoves(r, c, new int[][] { { -2, -1 }, { -2, 1 },
                                { -1, -2 }, { -1, 2 }, { 1, -2 }, { 1, 2 },
                                { 2, -1 }, { 2, 1 } }, out);
                        break;
                    case ChessPiece.BISHOP:
                        addSliderMoves(r, c, new int[][] { { -1, -1 }, { -1, 1 },
                                { 1, -1 }, { 1, 1 } }, out);
                        break;
                    case ChessPiece.ROOK:
                        addSliderMoves(r, c, new int[][] { { -1, 0 }, { 1, 0 },
                                { 0, -1 }, { 0, 1 } }, out);
                        break;
                    case ChessPiece.QUEEN:
                        addSliderMoves(r, c, new int[][] { { -1, 0 }, { 1, 0 },
                                { 0, -1 }, { 0, 1 }, { -1, -1 }, { -1, 1 },
                                { 1, -1 }, { 1, 1 } }, out);
                        break;
                    case ChessPiece.KING: {
                        addStepMoves(r, c, new int[][] { { -1, -1 }, { -1, 0 },
                                { -1, 1 }, { 0, -1 }, { 0, 1 }, { 1, -1 },
                                { 1, 0 }, { 1, 1 } }, out);
                        addCastlingMoves(r, c, out);
                        break;
                    }
                    default:
                        break;
                }
            }
        }
        return out;
    }

    /** Fully legal moves for the side to move. */
    public List<ChessMove> legalMoves() {
        List<ChessMove> result = new ArrayList<ChessMove>();
        if (isGameOver()) {
            return result;
        }
        for (ChessMove m : pseudoLegalMoves()) {
            ChessEngine probe = copy();
            probe.applyUnchecked(m);
            if (!probe.inCheck(sideToMove)) {
                result.add(m);
            }
        }
        return result;
    }

    public List<ChessMove> legalMovesFor(int row, int col) {
        List<ChessMove> result = new ArrayList<ChessMove>();
        for (ChessMove m : legalMoves()) {
            if (m.fromRow == row && m.fromCol == col) {
                result.add(m);
            }
        }
        return result;
    }

    public boolean isLegal(ChessMove move) {
        return legalMoves().contains(move);
    }

    /** Is this move a capture (including en passant)? */
    public boolean isCapture(ChessMove move) {
        return move.isEnPassant || board[move.toRow][move.toCol] != ChessPiece.EMPTY;
    }

    /** Applies a move without legality or history bookkeeping. */
    void applyUnchecked(ChessMove move) {
        int piece = board[move.fromRow][move.fromCol];
        int captured = board[move.toRow][move.toCol];
        Side mover = sideToMove;
        board[move.fromRow][move.fromCol] = ChessPiece.EMPTY;
        if (move.isEnPassant) {
            board[move.fromRow][move.toCol] = ChessPiece.EMPTY;
            captured = ChessPiece.of(ChessPiece.PAWN, mover.opponent());
        }
        if (move.isCastling) {
            int backRow = mover == Side.WHITE ? 7 : 0;
            if (move.toCol == 6) {
                board[backRow][5] = board[backRow][7];
                board[backRow][7] = ChessPiece.EMPTY;
            } else {
                board[backRow][3] = board[backRow][0];
                board[backRow][0] = ChessPiece.EMPTY;
            }
        }
        int placed = piece;
        if (move.promotion != ChessPiece.EMPTY) {
            placed = ChessPiece.of(move.promotion, mover);
        }
        board[move.toRow][move.toCol] = placed;
        // Castling rights: king move clears both; rook move/capture
        // from a corner clears that side's right.
        if (ChessPiece.typeOf(piece) == ChessPiece.KING) {
            if (mover == Side.WHITE) {
                whiteKingside = false;
                whiteQueenside = false;
            } else {
                blackKingside = false;
                blackQueenside = false;
            }
        }
        clearRookRight(move.fromRow, move.fromCol);
        clearRookRight(move.toRow, move.toCol);
        // En passant target: only after a double pawn push.
        epRow = -1;
        epCol = -1;
        if (ChessPiece.typeOf(piece) == ChessPiece.PAWN
                && Math.abs(move.toRow - move.fromRow) == 2) {
            epRow = (move.fromRow + move.toRow) / 2;
            epCol = move.fromCol;
        }
        if (ChessPiece.typeOf(piece) == ChessPiece.PAWN
                || captured != ChessPiece.EMPTY) {
            halfmoveClock = 0;
        } else {
            halfmoveClock++;
        }
        if (mover == Side.BLACK) {
            fullmoveNumber++;
        }
        sideToMove = mover.opponent();
    }

    private void clearRookRight(int row, int col) {
        if (row == 7 && col == 0) {
            whiteQueenside = false;
        } else if (row == 7 && col == 7) {
            whiteKingside = false;
        } else if (row == 0 && col == 0) {
            blackQueenside = false;
        } else if (row == 0 && col == 7) {
            blackKingside = false;
        }
    }

    /**
     * Applies one legal move. Throws IllegalArgumentException otherwise
     * and leaves the position unchanged.
     */
    public void applyMove(ChessMove move) {
        ChessMove legal = null;
        for (ChessMove m : legalMoves()) {
            if (m.equals(move)) {
                legal = m;
                break;
            }
        }
        if (legal == null) {
            throw new IllegalArgumentException("Illegal chess move: " + move);
        }
        applyUnchecked(legal);
        recordPosition();
        updateOutcome();
    }

    private String positionKey() {
        StringBuilder sb = new StringBuilder(80);
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                sb.append(board[r][c]).append(',');
            }
        }
        sb.append(sideToMove == Side.WHITE ? 'w' : 'b');
        sb.append(whiteKingside ? 'K' : '-');
        sb.append(whiteQueenside ? 'Q' : '-');
        sb.append(blackKingside ? 'k' : '-');
        sb.append(blackQueenside ? 'q' : '-');
        // En passant counts toward repetition only when a capture
        // onto it is actually possible.
        if (epRow >= 0 && canCaptureEnPassant()) {
            sb.append(ChessMove.squareName(epRow, epCol));
        }
        return sb.toString();
    }

    private boolean canCaptureEnPassant() {
        Side enemy = sideToMove;
        int pawn = ChessPiece.of(ChessPiece.PAWN, enemy);
        for (int dc : new int[] { -1, 1 }) {
            int c = epCol + dc;
            if (inBounds(epRow, c) && board[epRow][c] == pawn) {
                return true;
            }
        }
        return false;
    }

    private void recordPosition() {
        String key = positionKey();
        Integer n = positionCounts.get(key);
        positionCounts.put(key, n == null ? 1 : n + 1);
    }

    public boolean insufficientMaterial() {
        boolean hasPawnRookQueen = false;
        int minorCount = 0;
        int bishopSquareColour = -1;
        boolean bishopsSameColour = true;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int t = ChessPiece.typeOf(board[r][c]);
                if (t == ChessPiece.PAWN || t == ChessPiece.ROOK || t == ChessPiece.QUEEN) {
                    hasPawnRookQueen = true;
                } else if (t == ChessPiece.KNIGHT || t == ChessPiece.BISHOP) {
                    minorCount++;
                    if (t == ChessPiece.BISHOP) {
                        int colour = (r + c) % 2;
                        if (bishopSquareColour < 0) {
                            bishopSquareColour = colour;
                        } else if (bishopSquareColour != colour) {
                            bishopsSameColour = false;
                        }
                    } else {
                        bishopsSameColour = false;
                    }
                }
            }
        }
        if (hasPawnRookQueen) {
            return false;
        }
        if (minorCount <= 1) {
            return true;
        }
        return minorCount == 2 && bishopsSameColour;
    }

    private void updateOutcome() {
        outcome = Outcome.IN_PROGRESS;
        outcomeReason = "";
        if (legalMoves().isEmpty()) {
            if (inCheck()) {
                outcome = sideToMove == Side.WHITE ? Outcome.BLACK_WINS : Outcome.WHITE_WINS;
                outcomeReason = "Checkmate";
            } else {
                outcome = Outcome.DRAW;
                outcomeReason = "Stalemate";
            }
            return;
        }
        if (halfmoveClock >= 100) {
            outcome = Outcome.DRAW;
            outcomeReason = "Fifty-move rule";
            return;
        }
        Integer reps = positionCounts.get(positionKey());
        if (reps != null && reps >= 3) {
            outcome = Outcome.DRAW;
            outcomeReason = "Threefold repetition";
            return;
        }
        if (insufficientMaterial()) {
            outcome = Outcome.DRAW;
            outcomeReason = "Insufficient mating material";
        }
    }

    private static final Pattern COORD_PATTERN = Pattern.compile(
            "^([a-h][1-8])[-\\s]?([a-h][1-8])(?:[=\\s]?([qrnbQRNB]))?$");

    /** Parses coordinate notation: "e2e4", "e2-e4", "e7e8q", "e7e8=Q". */
    public ChessMove parseCoordinate(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Null chess move");
        }
        Matcher m = COORD_PATTERN.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Bad coordinate move: " + text);
        }
        int[] from = ChessMove.parseSquare(m.group(1));
        int[] to = ChessMove.parseSquare(m.group(2));
        int promo = ChessPiece.EMPTY;
        if (m.group(3) != null) {
            promo = ChessPiece.typeFromLetter(m.group(3).charAt(0));
        }
        ChessMove wanted = new ChessMove(from[0], from[1], to[0], to[1],
                promo, false, false);
        for (ChessMove legal : legalMoves()) {
            if (legal.fromRow == wanted.fromRow && legal.fromCol == wanted.fromCol
                    && legal.toRow == wanted.toRow && legal.toCol == wanted.toCol
                    && (promo == ChessPiece.EMPTY || legal.promotion == promo)) {
                // Bare promotion notation without a piece defaults to queen.
                if (promo == ChessPiece.EMPTY && legal.promotion != ChessPiece.EMPTY) {
                    return new ChessMove(legal.fromRow, legal.fromCol,
                            legal.toRow, legal.toCol, ChessPiece.QUEEN,
                            legal.isEnPassant, legal.isCastling);
                }
                return legal;
            }
        }
        throw new IllegalArgumentException("Illegal chess move: " + text);
    }

    /**
     * Parses Standard Algebraic Notation: "Nf3", "exd5", "O-O",
     * "e8=Q", "R1e2", "Qxf7+". Check/mate suffixes are ignored.
     */
    public ChessMove parseSan(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Null chess move");
        }
        String s = text.trim();
        while (s.endsWith("+") || s.endsWith("#")
                || s.endsWith("!") || s.endsWith("?")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.equals("O-O") || s.equals("0-0") || s.equals("O-O-O") || s.equals("0-0-0")) {
            boolean queenside = s.contains("-O-O") || s.contains("-0-0");
            for (ChessMove m : legalMoves()) {
                if (m.isCastling && ((queenside && m.toCol == 2) || (!queenside && m.toCol == 6))) {
                    return m;
                }
            }
            throw new IllegalArgumentException("Illegal castling: " + text);
        }
        int promotion = ChessPiece.EMPTY;
        int eq = s.indexOf('=');
        if (eq >= 0) {
            if (eq + 1 >= s.length()) {
                throw new IllegalArgumentException("Bad promotion: " + text);
            }
            promotion = ChessPiece.typeFromLetter(s.charAt(eq + 1));
            s = s.substring(0, eq);
        } else if (s.length() >= 3
                && Character.isUpperCase(s.charAt(s.length() - 1))
                && "QRBN".indexOf(s.charAt(s.length() - 1)) >= 0
                && Character.isDigit(s.charAt(s.length() - 2))) {
            // Trailing promotion letter without '=': "e8Q".
            promotion = ChessPiece.typeFromLetter(s.charAt(s.length() - 1));
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() < 2) {
            throw new IllegalArgumentException("Bad SAN move: " + text);
        }
        String destText = s.substring(s.length() - 2);
        int[] dest;
        try {
            dest = ChessMove.parseSquare(destText);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Bad SAN move: " + text);
        }
        String prefix = s.substring(0, s.length() - 2);
        boolean capture = prefix.contains("x") || prefix.contains("X");
        prefix = prefix.replace("x", "").replace("X", "");
        int pieceType;
        String disambiguation;
        if (!prefix.isEmpty() && "KQRBN".indexOf(prefix.charAt(0)) >= 0) {
            pieceType = ChessPiece.typeFromLetter(prefix.charAt(0));
            disambiguation = prefix.substring(1);
        } else {
            pieceType = ChessPiece.PAWN;
            disambiguation = prefix;
        }
        List<ChessMove> matches = new ArrayList<ChessMove>();
        for (ChessMove m : legalMoves()) {
            if (ChessPiece.typeOf(board[m.fromRow][m.fromCol]) != pieceType) {
                continue;
            }
            if (m.toRow != dest[0] || m.toCol != dest[1]) {
                continue;
            }
            if (isCapture(m) != capture && pieceType != ChessPiece.PAWN) {
                continue;
            }
            if (pieceType == ChessPiece.PAWN && capture && !isCapture(m)) {
                continue;
            }
            if (promotion != ChessPiece.EMPTY && m.promotion != promotion) {
                continue;
            }
            if (!disambiguation.isEmpty()) {
                String fromSq = ChessMove.squareName(m.fromRow, m.fromCol);
                if (disambiguation.length() == 1) {
                    char d = disambiguation.charAt(0);
                    if (Character.isLetter(d) && fromSq.charAt(0) != d) {
                        continue;
                    }
                    if (Character.isDigit(d) && fromSq.charAt(1) != d) {
                        continue;
                    }
                } else if (!fromSq.equals(disambiguation)) {
                    continue;
                }
            }
            matches.add(m);
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No legal move matches SAN: " + text);
        }
        throw new IllegalArgumentException("Ambiguous SAN move: " + text);
    }

    /**
     * Parses either coordinate or SAN notation and applies it.
     * Throws IllegalArgumentException on illegal input, mutating nothing.
     */
    public ChessMove applyNotation(String notation) {
        String trimmed = notation == null ? "" : notation.trim();
        ChessMove move;
        if (COORD_PATTERN.matcher(trimmed).matches()) {
            // Coordinate-shaped input is parsed as coordinates only,
            // so an illegal coordinate move reports itself accurately
            // instead of falling through to an unrelated SAN error.
            move = parseCoordinate(trimmed);
        } else {
            move = parseSan(trimmed);
        }
        applyMove(move);
        return move;
    }

    /** Standard starting FEN (also the result of {@link #toFen()}). */
    public static final String START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int empty = 0;
            for (int c = 0; c < 8; c++) {
                int p = board[r][c];
                if (p == ChessPiece.EMPTY) {
                    empty++;
                } else {
                    if (empty > 0) {
                        sb.append(empty);
                        empty = 0;
                    }
                    sb.append(ChessPiece.fenLetter(p));
                }
            }
            if (empty > 0) {
                sb.append(empty);
            }
            if (r < 7) {
                sb.append('/');
            }
        }
        sb.append(sideToMove == Side.WHITE ? " w " : " b ");
        String castling = (whiteKingside ? "K" : "") + (whiteQueenside ? "Q" : "")
                + (blackKingside ? "k" : "") + (blackQueenside ? "q" : "");
        sb.append(castling.isEmpty() ? "-" : castling).append(' ');
        sb.append(epRow >= 0 ? ChessMove.squareName(epRow, epCol) : "-");
        sb.append(' ').append(halfmoveClock).append(' ').append(fullmoveNumber);
        return sb.toString();
    }

    /** Loads a FEN position, resetting history to that position. */
    public void loadFen(String fen) {
        if (fen == null) {
            throw new IllegalArgumentException("Null FEN");
        }
        String[] fields = fen.trim().split("\\s+");
        if (fields.length < 4) {
            throw new IllegalArgumentException("Bad FEN: " + fen);
        }
        String[] ranks = fields[0].split("/");
        if (ranks.length != 8) {
            throw new IllegalArgumentException("Bad FEN board: " + fen);
        }
        for (int r = 0; r < 8; r++) {
            int c = 0;
            for (int i = 0; i < ranks[r].length(); i++) {
                char ch = ranks[r].charAt(i);
                if (Character.isDigit(ch)) {
                    for (int k = 0; k < ch - '0'; k++) {
                        board[r][c++] = ChessPiece.EMPTY;
                    }
                } else {
                    int type = ChessPiece.typeFromLetter(ch);
                    board[r][c++] = Character.isUpperCase(ch)
                            ? ChessPiece.of(type, Side.WHITE)
                            : ChessPiece.of(type, Side.BLACK);
                }
            }
            if (c != 8) {
                throw new IllegalArgumentException("Bad FEN rank: " + ranks[r]);
            }
        }
        sideToMove = fields[1].equals("b") ? Side.BLACK : Side.WHITE;
        String castling = fields[2];
        whiteKingside = castling.contains("K");
        whiteQueenside = castling.contains("Q");
        blackKingside = castling.contains("k");
        blackQueenside = castling.contains("q");
        if (fields[3].equals("-")) {
            epRow = -1;
            epCol = -1;
        } else {
            int[] ep = ChessMove.parseSquare(fields[3]);
            epRow = ep[0];
            epCol = ep[1];
        }
        halfmoveClock = fields.length > 4 ? Integer.parseInt(fields[4]) : 0;
        fullmoveNumber = fields.length > 5 ? Integer.parseInt(fields[5]) : 1;
        outcome = Outcome.IN_PROGRESS;
        outcomeReason = "";
        positionCounts.clear();
        recordPosition();
        updateOutcome();
    }

    /** Human-readable board diagram, rank 8 at the top. */
    public String boardDiagram() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            sb.append(8 - r).append(' ');
            for (int c = 0; c < 8; c++) {
                int p = board[r][c];
                sb.append(p == ChessPiece.EMPTY ? '.' : ChessPiece.fenLetter(p)).append(' ');
            }
            sb.append('\n');
        }
        sb.append("  a b c d e f g h\n");
        sb.append(sideToMove == Side.WHITE ? "White" : "Black").append(" to move");
        if (inCheck()) {
            sb.append(" (check)");
        }
        return sb.toString();
    }
}




