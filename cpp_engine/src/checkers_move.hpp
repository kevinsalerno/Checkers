// One checkers step: either a quiet diagonal step or a single jump.
// A multi-jump chain is a sequence of these steps by the same piece.
// Immutable. Coordinates are rows/columns 0..7, row 0 = rank 8.
#pragma once

#include <string>
#include <utility>

namespace engine {

struct CheckersMove {
    int fromRow = 0, fromCol = 0, toRow = 0, toCol = 0;
    // Captured square, or -1/-1 when this step captures nothing.
    int capturedRow = -1, capturedCol = -1;

    CheckersMove() = default;
    CheckersMove(int fr, int fc, int tr, int tc, int cr = -1, int cc = -1)
        : fromRow(fr), fromCol(fc), toRow(tr), toCol(tc), capturedRow(cr), capturedCol(cc) {}

    bool isJump() const { return capturedRow >= 0; }

    bool operator==(const CheckersMove& o) const {
        return fromRow == o.fromRow && fromCol == o.fromCol && toRow == o.toRow &&
               toCol == o.toCol && capturedRow == o.capturedRow && capturedCol == o.capturedCol;
    }
    bool operator!=(const CheckersMove& o) const { return !(*this == o); }

    std::string toString() const;  // "a3-b4" or "a3xb4"

    // Square name such as "a3": file a-h, rank 1-8.
    static std::string squareName(int row, int col);
    // Parses one square name ("a3", "h8"). Throws std::invalid_argument.
    static std::pair<int, int> parseSquare(const std::string& text);
};

}  // namespace engine
