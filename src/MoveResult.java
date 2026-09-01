// Outcome of an applied move, shared by every game mode. Flags drive the
// sound effects and turn handling in GameUI.
public class MoveResult {
	public boolean valid;     // was the move legal and applied?
	public boolean captured;  // did it remove an enemy piece?
	public boolean promoted;  // checkers king / chess promotion
	public boolean continues; // same player must move again (checkers multi-jump)
	public boolean gameOver;
	public Boolean winner;    // team of winner, or null for draw/ongoing
}
