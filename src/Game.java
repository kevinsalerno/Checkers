import java.awt.Graphics2D;
import java.util.List;

// A board game GameUI can drive. Both games are 8x8; team true = the side that
// moves first (RED for checkers, WHITE for chess).
public interface Game {
	boolean toMove();                 // team to move
	boolean isOver();
	Boolean winnerTeam();             // null = draw or ongoing

	List<int[]> legalDestinations(int r, int c); // legal {row,col} targets for the piece at r,c
	MoveResult applyMove(int fr, int fc, int tr, int tc);

	boolean hasPiece(int r, int c);
	boolean pieceTeam(int r, int c);  // team of the piece at r,c
	char pieceKind(int r, int c);     // checkers: 'm'/'k'; chess: P N B R Q K (used by 3D renderer)
	void paintPiece(Graphics2D g, int r, int c, int x, int y, int cell);

	String statusHint();              // "" or e.g. "must capture!" / "check!"
	int count(boolean team);
	String teamName(boolean team);
	Game fresh();                     // a new game of the same type (New Game button)
	void timeout(boolean loser);      // loser ran out of time: end the game, other side wins
}
