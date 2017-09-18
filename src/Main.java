
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
	}
}
