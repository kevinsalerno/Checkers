
public class Main {
	public static void main(String args[]) {
		Board board = Board.getInstance();
		System.out.println("Welcome to Checkers");
		board.printBoard();
		board.move(board.cells[7][0], -6, 0);
		board.printBoard();
		board.move(board.cells[1][0], 1, 0);
		board.printBoard();
		board.move(board.cells[1][2], 0, -2);
		board.printBoard();
		board.move(board.cells[7][2], -7, 1);
		board.printBoard();
		
		boolean[] ava = board.cells[0][3].getAvailableMoves();
		System.out.println("Left: " + ava[0] + " Up: " + ava[1] + " Right: " + ava[2] + " Down: " + ava[3]);
	}
}
