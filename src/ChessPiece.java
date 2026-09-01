// A chess piece. white: true = white (moves first, starts at bottom).
// type: one of P N B R Q K. moved: used for castling rights.
public class ChessPiece {
	boolean white;
	char type;
	boolean moved;

	ChessPiece(boolean white, char type) {
		this.white = white;
		this.type = type;
	}
}
