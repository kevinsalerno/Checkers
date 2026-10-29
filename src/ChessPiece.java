/**
 * Chess piece codes for the int[][] board in {@link ChessEngine}.
 * Positive = White, negative = Black, 0 = empty.
 * The type is the absolute value.
 */
public final class ChessPiece {
    public static final int EMPTY = 0;
    public static final int PAWN = 1;
    public static final int KNIGHT = 2;
    public static final int BISHOP = 3;
    public static final int ROOK = 4;
    public static final int QUEEN = 5;
    public static final int KING = 6;

    private ChessPiece() {
    }

    public static int of(int type, Side side) {
        return side == Side.WHITE ? type : -type;
    }

    public static int typeOf(int piece) {
        return Math.abs(piece);
    }

    public static Side sideOf(int piece) {
        if (piece > 0) {
            return Side.WHITE;
        }
        if (piece < 0) {
            return Side.BLACK;
        }
        return null;
    }

    public static char letter(int type) {
        switch (type) {
            case PAWN: return 'P';
            case KNIGHT: return 'N';
            case BISHOP: return 'B';
            case ROOK: return 'R';
            case QUEEN: return 'Q';
            case KING: return 'K';
            default: return '?';
        }
    }

    public static int typeFromLetter(char c) {
        switch (Character.toUpperCase(c)) {
            case 'P': return PAWN;
            case 'N': return KNIGHT;
            case 'B': return BISHOP;
            case 'R': return ROOK;
            case 'Q': return QUEEN;
            case 'K': return KING;
            default: throw new IllegalArgumentException("Bad piece letter: " + c);
        }
    }

    /** FEN letter for a coded piece, or '?' for empty. */
    public static char fenLetter(int piece) {
        char c = letter(typeOf(piece));
        return piece > 0 ? c : Character.toLowerCase(c);
    }
}
