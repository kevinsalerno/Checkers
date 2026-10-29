/**
 * One checkers step: either a quiet diagonal step or a single jump.
 * A multi-jump chain is a sequence of these steps by the same piece.
 * Immutable. Coordinates are rows/columns 0..7, row 0 = rank 8.
 */
public final class CheckersMove {
    public final int fromRow;
    public final int fromCol;
    public final int toRow;
    public final int toCol;
    /** Captured square, or -1/-1 when this step captures nothing. */
    public final int capturedRow;
    public final int capturedCol;

    public CheckersMove(int fromRow, int fromCol, int toRow, int toCol,
                        int capturedRow, int capturedCol) {
        this.fromRow = fromRow;
        this.fromCol = fromCol;
        this.toRow = toRow;
        this.toCol = toCol;
        this.capturedRow = capturedRow;
        this.capturedCol = capturedCol;
    }

    public CheckersMove(int fromRow, int fromCol, int toRow, int toCol) {
        this(fromRow, fromCol, toRow, toCol, -1, -1);
    }

    public boolean isJump() {
        return capturedRow >= 0;
    }

    /** Square name such as "a3": file a-h, rank 1-8. */
    public static String squareName(int row, int col) {
        return String.valueOf((char) ('a' + col)) + (8 - row);
    }

    /** Parses one square name ("a3", "h8"). Throws IllegalArgumentException. */
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

    @Override
    public String toString() {
        String sep = isJump() ? "x" : "-";
        return squareName(fromRow, fromCol) + sep + squareName(toRow, toCol);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CheckersMove)) {
            return false;
        }
        CheckersMove m = (CheckersMove) o;
        return fromRow == m.fromRow && fromCol == m.fromCol
                && toRow == m.toRow && toCol == m.toCol
                && capturedRow == m.capturedRow && capturedCol == m.capturedCol;
    }

    @Override
    public int hashCode() {
        int h = fromRow * 31 + fromCol;
        h = h * 31 + toRow;
        h = h * 31 + toCol;
        h = h * 31 + capturedRow;
        return h * 31 + capturedCol;
    }
}
