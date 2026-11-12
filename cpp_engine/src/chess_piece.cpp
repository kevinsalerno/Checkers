#include "chess_piece.hpp"

#include <cctype>
#include <string>

namespace engine {

char ChessPiece::letter(int type) {
    switch (type) {
        case Pawn: return 'P';
        case Knight: return 'N';
        case Bishop: return 'B';
        case Rook: return 'R';
        case Queen: return 'Q';
        case King: return 'K';
        default: return '?';
    }
}

int ChessPiece::typeFromLetter(char c) {
    switch (std::toupper(static_cast<unsigned char>(c))) {
        case 'P': return Pawn;
        case 'N': return Knight;
        case 'B': return Bishop;
        case 'R': return Rook;
        case 'Q': return Queen;
        case 'K': return King;
        default: throw std::invalid_argument(std::string("Bad piece letter: ") + c);
    }
}

char ChessPiece::fenLetter(int piece) {
    char c = letter(typeOf(piece));
    return piece > 0 ? c : static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
}

}  // namespace engine
