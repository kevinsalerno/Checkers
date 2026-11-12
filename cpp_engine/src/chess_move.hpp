// One chess move. Immutable. Coordinates are rows/columns 0..7,
// row 0 = rank 8, col 0 = file a. `promotion` is a piece type
// (ChessPiece::Queen etc.) or ChessPiece::Empty when the move does
// not promote. Equality mirrors the Java port: only from/to/promotion
// participate (isEnPassant / isCastling are board-shape flags).
#pragma once

#include <string>
#include <utility>

namespace engine {

struct ChessMove {
    int fromRow = 0, fromCol = 0, toRow = 0, toCol = 0;
    int promotion = 0;  // piece type or ChessPiece::Empty
    bool isEnPassant = false;
    bool isCastling = false;

    ChessMove() = default;
    ChessMove(int fr, int fc, int tr, int tc, int promo = 0, bool ep = false, bool castle = false)
        : fromRow(fr), fromCol(fc), toRow(tr), toCol(tc),
          promotion(promo), isEnPassant(ep), isCastling(castle) {}

    bool operator==(const ChessMove& o) const {
        return fromRow == o.fromRow && fromCol == o.fromCol && toRow == o.toRow &&
               toCol == o.toCol && promotion == o.promotion;
    }
    bool operator!=(const ChessMove& o) const { return !(*this == o); }

    ChessMove withPromotion(int promotionType) const {
        return ChessMove(fromRow, fromCol, toRow, toCol, promotionType, isEnPassant, isCastling);
    }

    std::string toString() const;  // coordinate notation "e2e4" + promo suffix

    static std::string squareName(int row, int col);
    static std::pair<int, int> parseSquare(const std::string& text);
};

}  // namespace engine
