#include "chess_move.hpp"

#include <cctype>
#include <stdexcept>

#include "chess_piece.hpp"

namespace engine {

std::string ChessMove::squareName(int row, int col) {
    std::string s;
    s += static_cast<char>('a' + col);
    s += static_cast<char>('0' + (8 - row));
    return s;
}

std::pair<int, int> ChessMove::parseSquare(const std::string& text) {
    if (text.size() != 2) throw std::invalid_argument("Bad square: " + text);
    char file = static_cast<char>(std::tolower(static_cast<unsigned char>(text[0])));
    char rank = text[1];
    if (file < 'a' || file > 'h' || rank < '1' || rank > '8')
        throw std::invalid_argument("Bad square: " + text);
    return {8 - (rank - '0'), file - 'a'};
}

std::string ChessMove::toString() const {
    std::string s = squareName(fromRow, fromCol) + squareName(toRow, toCol);
    if (promotion != ChessPiece::Empty)
        s += static_cast<char>(std::tolower(
            static_cast<unsigned char>(ChessPiece::letter(promotion))));
    return s;
}

}  // namespace engine
