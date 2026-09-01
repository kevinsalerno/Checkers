
// A single checkers piece. team: true = RED (starts bottom, moves up),
// false = BLACK (starts top, moves down). Kings move/capture both directions.
public class BoardObject {
	boolean team;
	int positionX; // row
	int positionY; // col
	boolean king;

	BoardObject(String team, int x, int y) {
		this.positionX = x;
		this.positionY = y;
		// keep the original string-based constructor; RED at bottom, BLACK at top
		this.team = team.equals("RED");
	}

	public int getPositionX() { return this.positionX; }
	public void setPositionX(int x) { this.positionX = x; }
	public int getPositionY() { return this.positionY; }
	public void setPositionY(int y) { this.positionY = y; }
	public boolean isKing() { return this.king; }
}
