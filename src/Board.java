import java.util.ArrayList;
import java.util.List;


public class Board {
	private int turns = 0;
	private static Board instance = null;
	private List<BoardObject> graveyard = new ArrayList<BoardObject>();
	
	public BoardObject[][] cells;
	
	protected Board() {
		setupGame();
		//cells[7][0].printTeam();
		System.out.println("board ready!");
//		printBoard();
//		this.move(cells[7][0], -6, 0);
	}
	
	//singleton
	public static Board getInstance() {
		if (instance == null) {
			instance = new Board();
		}
		return instance;
	}
	
	public void printBoard() {
		System.out.println("Move: "+ this.turns);
		System.out.println("**********");
		int i;
		for (i = 0; i < this.cells[0].length; i++) {
			System.out.print("*");
			for (int j = 0; j < this.cells[i].length; j++) {
				if (cells[i][j] != null) {
					System.out.print((cells[i][j].team) ? "o" : "x");
				} else {
					System.out.print(" ");
				}
			}
			System.out.println("*");
		}
		System.out.println("**********");
		System.out.println("Graveyard: " + this.graveyard.size());
	}
	
	public void move(BoardObject piece, int x, int y) {
		// BOARD BOUNDARY CHECK
		if (piece != null && (piece.getPositionX() + x) >= 0 
		&& (piece.getPositionX() + x <= 7)
		&& (piece.getPositionY() + y) >= 0 
		&& (piece.getPositionY() + y <= 7)) {
			System.out.println("Valid move!");
			turns++;
			//Piece collision check
			if (cells[piece.getPositionX() + x][piece.getPositionY() + y] != null) {
				if ((piece.team) != (cells[piece.getPositionX() + x][piece.getPositionY() + y].team)){
					// Kill enemy player!
					this.graveyard.add(cells[piece.getPositionX() + x][piece.getPositionY() + y]);
				} else {
					// You can't jump your own player!
					return;
				}
			}
			int tempX = piece.getPositionX();
			int tempY = piece.getPositionY();
			piece.setPositionX(tempX);
			piece.setPositionY(tempY);
			cells[tempX + x][tempY + y] = piece;
			cells[tempX][tempY] = null;
		} else {
			System.out.println("invalid move!");
		}
	
		
		
	}
	
	private void setupGame() {
		//create board canvas data container
		this.cells = new BoardObject[8][8];
		
		//place starting pieces for each team
		placeStartingPieces("BLACK");
		placeStartingPieces("WHITE");
	}
	
	private void placeStartingPieces(String team) {
		int[] rows;
		//determine which rows to place
		if (team.equals("BLACK")) {
			//black starts at top of board (cells[0, 1, 2][x])
			rows = new int[]{0, 1, 2};
		} else {
			//white stops at bottom of board (cells[5, 6, 7][x])
			rows = new int[]{5, 6, 7};
		}
		
		int i;
		
		// iterate each row
		for (i = 0; i < rows.length; i++) {
			int j;
			//iterate each column in row
			for (j = 0; j < 8; j++) {
				//Check for place able spaces)
					if (rows[i] % 2 == 0 && j % 2 != 0
						|| rows[i] % 2 != 0 && j % 2 == 0) {
						System.out.println("Placing object at " + rows[i] + "x" + j);
						this.cells[rows[i]][j] = new BoardObject(team, rows[i], j);
					}
				
			}
		}
	
	}
}
