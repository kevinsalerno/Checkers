import java.awt.Graphics2D;

// How a board is drawn and how a pixel maps back to a (row,col). GameUI owns
// all game logic; renderers are pure view + hit-testing, so 2D and 3D swap freely.
public interface BoardRenderer {
	void paint(Graphics2D g, Game game, int selR, int selC, boolean assist, int w, int h);
	int[] pick(int mx, int my, int w, int h); // {row,col} clicked, or null if off-board
}
