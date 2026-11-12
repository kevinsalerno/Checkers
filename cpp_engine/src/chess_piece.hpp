// Chess piece codes for the int board in ChessEngine.
// Positive = White, negative = Black, 0 = empty. The type is abs(piece).
#pragma once

#include <stdexcept>

#include "side.hpp"

namespace engine {

struct ChessPiece {
    static constexpr int Empty = 0;
    static constexpr int Pawn = 1;
    static constexpr int Knight = 2;
    static constexpr int Bishop = 3;
    static constexpr int Rook = 4;
    static constexpr int Queen = 5;
    static constexpr int King = 6;

    static int of(int type, Side side) { return side == Side::White ? type : -type; }
    static int typeOf(int piece) { return piece < 0 ? -piece : piece; }
    static Side sideOf(int piece) {
        if (piece > 0) return Side::White;
        if (piece < 0) return Side::Black;
        throw std::invalid_argument("sideOf(empty)");
    }

    static char letter(int type);
    static int typeFromLetter(char c);
    // FEN letter for a coded piece, or '?' for empty.
    static char fenLetter(int piece);
};

}  // namespace engine
