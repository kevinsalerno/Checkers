/**
 * One chess move. Immutable. Coordinates are rows/columns 0..7,
 * row 0 = rank 8, col 0 = file a. {@code promotion} is a piece type
 * ({@link ChessPiece#QUEEN} etc.) or {@link ChessPiece#EMPTY} when the
 * move does not promote. Castling and en-passant are recognised from
 * the board shape of the move, with explicit flags for clarity.
 */
public final class ChessMove {
    public final int fromRow;
    public final int fromCol;
    public final int toRow;
    public final int toCol;
    public final int promotion;
    public final boolean isEnPassant;
    public final boolean isCastling;

    public ChessMove(int fromRow, int fromCol, int toRow, int toCol,
                     int promotion, boolean isEnPassant, boolean isCastling) {
        this.fromRow = fromRow;
        this.fromCol = fromCol;
        this.toRow = toRow;
        this.toCol = toCol;
        this.promotion = promotion;
        this.isEnPassant = isEnPassant;
        this.isCastling = isCastling;
    }

    public ChessMove(int fromRow, int fromCol, int toRow, int toCol) {
        this(fromRow, fromCol, toRow, toCol, ChessPiece.EMPTY, false, false);
    }

    public ChessMove withPromotion(int promotionType) {
        return new ChessMove(fromRow, fromCol, toRow, toCol,
                promotionType, isEnPassant, isCastling);
    }

    public static String squareName(int row, int col) {
        return String.valueOf((char) ('a' + col)) + (8 - row);
    }

    public static int[] parseSquare(String text) {
        if (text == null || text.length() != 2) {
            throw new IllegalArgumentException("Bad square: " + text);
        }
        char file = Character.toLowerCase(text.charAt(0));
        char rank = text.charAt(1);
        if (file < 'a' || file > 'h' || rank < '1' || rank > '8') {
            throw new IllegalArgumentException("Bad square: " + text);
        }
        return new int[] { 8 - (rank - '0'), file - 'a' };
    }

    /** Coordinate notation "e2e4", with optional promotion suffix. */
    @Override
    public String toString() {
        String s = squareName(fromRow, fromCol) + squareName(toRow, toCol);
        if (promotion != ChessPiece.EMPTY) {
            s += Character.toLowerCase(ChessPiece.letter(promotion));
        }
        return s;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ChessMove)) {
            return false;
        }
        ChessMove m = (ChessMove) o;
        return fromRow == m.fromRow && fromCol == m.fromCol
                && toRow == m.toRow && toCol == m.toCol
                && promotion == m.promotion;
    }

    @Override
    public int hashCode() {
        int h = fromRow * 31 + fromCol;
        h = h * 31 + toRow;
        h = h * 31 + toCol;
        return h * 31 + promotion;
    }
}
