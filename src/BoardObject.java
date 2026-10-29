/**
 * One checkers piece as seen by the legacy {@link Board} facade.
 *
 * <p>{@code team} keeps its original meaning: {@code true} = WHITE,
 * {@code false} = BLACK. Coordinates are rows/columns 0..7 and are
 * always kept in sync with the piece's actual square by
 * {@link Board#move} (the original bug left them stale).</p>
 *
 * <p>{@link #getAvailableMoves()} now scans diagonally, not orthogonally.
 * It returns four flags for the diagonal directions NW, NE, SW, SE
 * (in that order). When this piece is on a {@link Board}, a flag is set
 * only when a step or jump in that direction is legal under the full
 * rules (mandatory captures, multi-jump chains, forward-only men);
 * without a board it falls back to in-bounds diagonal steps in the
 * piece's allowed directions.</p>
 */
public class BoardObject {
    String name;
    boolean team;
    int positionX;
    int positionY;
    boolean king;
    Board boardRef;

    BoardObject(String team, int x, int y) {
        this.positionX = x;
        this.positionY = y;
        this.king = false;
        if (team.equals("BLACK")) {
            this.team = false;
        } else {
            this.team = true;
        }
        this.name = team;
    }

    BoardObject(String team, int x, int y, boolean king) {
        this(team, x, y);
        this.king = king;
    }

    void printTeam() {
        System.out.println("TEAM: " + ((this.team) ? "WHITE" : "BLACK")
                + (king ? " KING" : ""));
    }

    public int getPositionX() {
        return this.positionX;
    }

    public void setPositionX(int x) {
        this.positionX = x;
    }

    public int getPositionY() {
        return this.positionY;
    }

    public void setPositionY(int y) {
        this.positionY = y;
    }

    public boolean isKing() {
        return king;
    }

    public void setKing(boolean king) {
        this.king = king;
    }

    public Side side() {
        return team ? Side.WHITE : Side.BLACK;
    }

    int enginePiece() {
        if (team) {
            return king ? CheckersEngine.WHITE_KING : CheckersEngine.WHITE_MAN;
        }
        return king ? CheckersEngine.BLACK_KING : CheckersEngine.BLACK_MAN;
    }

    public boolean[] getAvailableMoves() {
        // Order: NW (-1,-1), NE (-1,+1), SW (+1,-1), SE (+1,+1).
        boolean[] availableMoves = new boolean[4];
        int[][] dirs = { { -1, -1 }, { -1, 1 }, { 1, -1 }, { 1, 1 } };
        if (boardRef != null && boardRef.engine != null
                && boardRef.cells[positionX][positionY] == this) {
            java.util.List<CheckersMove> legal =
                    boardRef.engine.legalMovesFor(positionX, positionY);
            for (CheckersMove m : legal) {
                int dr = m.toRow - m.fromRow;
                int dc = m.toCol - m.fromCol;
                for (int i = 0; i < 4; i++) {
                    if (Integer.signum(dr) == dirs[i][0]
                            && Integer.signum(dc) == dirs[i][1]) {
                        availableMoves[i] = true;
                    }
                }
            }
            return availableMoves;
        }
        // Standalone fallback: diagonal steps in bounds, forward-only
        // for men (white moves toward row 0, black toward row 7).
        for (int i = 0; i < 4; i++) {
            if (!king) {
                int fwd = team ? -1 : 1;
                if (dirs[i][0] != fwd) {
                    continue;
                }
            }
            int r = positionX + dirs[i][0];
            int c = positionY + dirs[i][1];
            availableMoves[i] = r >= 0 && r <= 7 && c >= 0 && c <= 7;
        }
        return availableMoves;
    }
}
