/**
 * Player side. WHITE moves toward row 0, BLACK moves toward row 7.
 * Row 0 is the top of the board (rank 8), row 7 the bottom (rank 1).
 */
public enum Side {
    WHITE, BLACK;

    public Side opponent() {
        return this == WHITE ? BLACK : WHITE;
    }
}
