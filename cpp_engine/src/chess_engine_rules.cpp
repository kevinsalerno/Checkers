// ChessEngine part 2: repetition, draws, SAN/coordinate parsing, FEN, diagram.
#include "chess_engine.hpp"

#include <cctype>
#include <sstream>
#include <stdexcept>

namespace engine {

static std::string trimStr(const std::string& s) {
    size_t a = 0;
    while (a < s.size() && std::isspace(static_cast<unsigned char>(s[a]))) a++;
    size_t b = s.size();
    while (b > a && std::isspace(static_cast<unsigned char>(s[b - 1]))) b--;
    return s.substr(a, b - a);
}

static std::vector<std::string> splitWs(const std::string& s) {
    std::vector<std::string> out;
    std::string cur;
    for (char ch : s) {
        if (std::isspace(static_cast<unsigned char>(ch))) {
            if (!cur.empty()) {
                out.push_back(cur);
                cur.clear();
            }
        } else {
            cur += ch;
        }
    }
    if (!cur.empty()) out.push_back(cur);
    return out;
}

std::string ChessEngine::positionKey() const {
    std::string sb;
    sb.reserve(90);
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) {
            sb += std::to_string(board_[r][c]);
            sb += ',';
        }
    sb += (sideToMove_ == Side::White ? 'w' : 'b');
    sb += whiteKingside_ ? 'K' : '-';
    sb += whiteQueenside_ ? 'Q' : '-';
    sb += blackKingside_ ? 'k' : '-';
    sb += blackQueenside_ ? 'q' : '-';
    // En passant counts toward repetition only when a capture
    // onto it is actually possible.
    if (epRow_ >= 0 && canCaptureEnPassant()) sb += ChessMove::squareName(epRow_, epCol_);
    return sb;
}

bool ChessEngine::canCaptureEnPassant() const {
    Side enemy = sideToMove_;
    int pawn = ChessPiece::of(ChessPiece::Pawn, enemy);
    for (int dc : {-1, 1}) {
        int c = epCol_ + dc;
        if (inBounds(epRow_, c) && board_[epRow_][c] == pawn) return true;
    }
    return false;
}

void ChessEngine::recordPosition() { positionCounts_[positionKey()]++; }

bool ChessEngine::insufficientMaterial() const {
    bool hasPawnRookQueen = false;
    int minorCount = 0;
    int bishopSquareColour = -1;
    bool bishopsSameColour = true;
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) {
            int t = ChessPiece::typeOf(board_[r][c]);
            if (t == ChessPiece::Pawn || t == ChessPiece::Rook || t == ChessPiece::Queen) {
                hasPawnRookQueen = true;
            } else if (t == ChessPiece::Knight || t == ChessPiece::Bishop) {
                minorCount++;
                if (t == ChessPiece::Bishop) {
                    int colour = (r + c) % 2;
                    if (bishopSquareColour < 0) {
                        bishopSquareColour = colour;
                    } else if (bishopSquareColour != colour) {
                        bishopsSameColour = false;
                    }
                } else {
                    bishopsSameColour = false;
                }
            }
        }
    if (hasPawnRookQueen) return false;
    if (minorCount <= 1) return true;
    return minorCount == 2 && bishopsSameColour;
}

void ChessEngine::updateOutcome() {
    outcome_ = Outcome::InProgress;
    outcomeReason_.clear();
    if (legalMoves().empty()) {
        if (inCheck()) {
            outcome_ = (sideToMove_ == Side::White) ? Outcome::BlackWins : Outcome::WhiteWins;
            outcomeReason_ = "Checkmate";
        } else {
            outcome_ = Outcome::Draw;
            outcomeReason_ = "Stalemate";
        }
        return;
    }
    if (halfmoveClock_ >= 100) {
        outcome_ = Outcome::Draw;
        outcomeReason_ = "Fifty-move rule";
        return;
    }
    auto it = positionCounts_.find(positionKey());
    if (it != positionCounts_.end() && it->second >= 3) {
        outcome_ = Outcome::Draw;
        outcomeReason_ = "Threefold repetition";
        return;
    }
    if (insufficientMaterial()) {
        outcome_ = Outcome::Draw;
        outcomeReason_ = "Insufficient mating material";
    }
}

// Coordinate notation: "e2e4", "e2-e4", "e7e8q", "e7e8=Q".
bool ChessEngine::isCoordinateNotation(const std::string& t) {
    size_t n = t.size();
    if (n != 4 && n != 5 && n != 6) return false;
    auto isFile = [](char ch) {
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
        return ch >= 'a' && ch <= 'h';
    };
    auto isRank = [](char ch) { return ch >= '1' && ch <= '8'; };
    if (!isFile(t[0]) || !isRank(t[1])) return false;
    size_t i = 2;
    if (t[i] == '-') {
        i++;
        if (n < 5) return false;
    }
    if (i + 1 >= n) return false;
    if (!isFile(t[i]) || !isRank(t[i + 1])) return false;
    i += 2;
    if (i == n) return true;
    if (t[i] == '=' || t[i] == ' ') {
        i++;
        if (i >= n) return false;
    }
    if (i != n - 1) return false;
    char p = static_cast<char>(std::toupper(static_cast<unsigned char>(t[i])));
    return p == 'Q' || p == 'R' || p == 'N' || p == 'B';
}

ChessMove ChessEngine::parseCoordinate(const std::string& text) const {
    std::string t = trimStr(text);
    if (!isCoordinateNotation(t)) throw std::invalid_argument("Bad coordinate move: " + text);
    auto from = ChessMove::parseSquare(t.substr(0, 2));
    size_t i = 2;
    if (t[i] == '-') i++;
    auto to = ChessMove::parseSquare(t.substr(i, 2));
    i += 2;
    int promo = ChessPiece::Empty;
    if (i < t.size()) {
        if (t[i] == '=' || t[i] == ' ') i++;
        promo = ChessPiece::typeFromLetter(t[i]);
    }
    ChessMove wanted(from.first, from.second, to.first, to.second, promo, false, false);
    for (const auto& legal : legalMoves()) {
        if (legal.fromRow == wanted.fromRow && legal.fromCol == wanted.fromCol &&
            legal.toRow == wanted.toRow && legal.toCol == wanted.toCol &&
            (promo == ChessPiece::Empty || legal.promotion == promo)) {
            // Bare promotion notation without a piece defaults to queen.
            if (promo == ChessPiece::Empty && legal.promotion != ChessPiece::Empty)
                return ChessMove(legal.fromRow, legal.fromCol, legal.toRow, legal.toCol,
                                 ChessPiece::Queen, legal.isEnPassant, legal.isCastling);
            return legal;
        }
    }
    throw std::invalid_argument("Illegal chess move: " + text);
}

ChessMove ChessEngine::parseSan(const std::string& text) const {
    if (text.empty()) throw std::invalid_argument("Bad SAN move: " + text);
    std::string s = trimStr(text);
    while (!s.empty() &&
           (s.back() == '+' || s.back() == '#' || s.back() == '!' || s.back() == '?'))
        s.pop_back();
    if (s == "O-O" || s == "0-0" || s == "O-O-O" || s == "0-0-0") {
        bool queenside = (s.find("-O-O") != std::string::npos) || (s.find("-0-0") != std::string::npos);
        for (const auto& m : legalMoves()) {
            if (m.isCastling &&
                ((queenside && m.toCol == 2) || (!queenside && m.toCol == 6)))
                return m;
        }
        throw std::invalid_argument("Illegal castling: " + text);
    }
    int promotion = ChessPiece::Empty;
    size_t eq = s.find('=');
    if (eq != std::string::npos) {
        if (eq + 1 >= s.size()) throw std::invalid_argument("Bad promotion: " + text);
        promotion = ChessPiece::typeFromLetter(s[eq + 1]);
        s = s.substr(0, eq);
    } else if (s.size() >= 3 && std::isupper(static_cast<unsigned char>(s.back())) &&
               std::string("QRBN").find(s.back()) != std::string::npos &&
               std::isdigit(static_cast<unsigned char>(s[s.size() - 2]))) {
        // Trailing promotion letter without '=': "e8Q".
        promotion = ChessPiece::typeFromLetter(s.back());
        s.pop_back();
    }
    if (s.size() < 2) throw std::invalid_argument("Bad SAN move: " + text);
    std::string destText = s.substr(s.size() - 2);
    std::pair<int, int> dest;
    try {
        dest = ChessMove::parseSquare(destText);
    } catch (const std::invalid_argument&) {
        throw std::invalid_argument("Bad SAN move: " + text);
    }
    std::string prefix = s.substr(0, s.size() - 2);
    bool capture = prefix.find('x') != std::string::npos || prefix.find('X') != std::string::npos;
    std::string stripped;
    for (char ch : prefix)
        if (ch != 'x' && ch != 'X') stripped += ch;
    prefix = stripped;
    int pieceType;
    std::string disambiguation;
    if (!prefix.empty() && std::string("KQRBN").find(prefix[0]) != std::string::npos) {
        pieceType = ChessPiece::typeFromLetter(prefix[0]);
        disambiguation = prefix.substr(1);
    } else {
        pieceType = ChessPiece::Pawn;
        disambiguation = prefix;
    }
    std::vector<ChessMove> matches;
    for (const auto& m : legalMoves()) {
        if (ChessPiece::typeOf(board_[m.fromRow][m.fromCol]) != pieceType) continue;
        if (m.toRow != dest.first || m.toCol != dest.second) continue;
        if (isCapture(m) != capture && pieceType != ChessPiece::Pawn) continue;
        if (pieceType == ChessPiece::Pawn && capture && !isCapture(m)) continue;
        if (promotion != ChessPiece::Empty && m.promotion != promotion) continue;
        if (!disambiguation.empty()) {
            std::string fromSq = ChessMove::squareName(m.fromRow, m.fromCol);
            if (disambiguation.size() == 1) {
                char d = disambiguation[0];
                if (std::isalpha(static_cast<unsigned char>(d)) && fromSq[0] != d) continue;
                if (std::isdigit(static_cast<unsigned char>(d)) && fromSq[1] != d) continue;
            } else if (fromSq != disambiguation) {
                continue;
            }
        }
        matches.push_back(m);
    }
    if (matches.size() == 1) return matches[0];
    if (matches.empty()) throw std::invalid_argument("No legal move matches SAN: " + text);
    throw std::invalid_argument("Ambiguous SAN move: " + text);
}

ChessMove ChessEngine::applyNotation(const std::string& notation) {
    std::string trimmed = trimStr(notation);
    ChessMove move(0, 0, 0, 0);
    if (isCoordinateNotation(trimmed)) {
        // Coordinate-shaped input is parsed as coordinates only,
        // so an illegal coordinate move reports itself accurately
        // instead of falling through to an unrelated SAN error.
        move = parseCoordinate(trimmed);
    } else {
        move = parseSan(trimmed);
    }
    applyMove(move);
    return move;
}

std::string ChessEngine::toFen() const {
    std::ostringstream sb;
    for (int r = 0; r < 8; r++) {
        int empty = 0;
        for (int c = 0; c < 8; c++) {
            int p = board_[r][c];
            if (p == ChessPiece::Empty) {
                empty++;
            } else {
                if (empty > 0) {
                    sb << empty;
                    empty = 0;
                }
                sb << ChessPiece::fenLetter(p);
            }
        }
        if (empty > 0) sb << empty;
        if (r < 7) sb << '/';
    }
    sb << (sideToMove_ == Side::White ? " w " : " b ");
    std::string castling;
    if (whiteKingside_) castling += 'K';
    if (whiteQueenside_) castling += 'Q';
    if (blackKingside_) castling += 'k';
    if (blackQueenside_) castling += 'q';
    sb << (castling.empty() ? "-" : castling) << ' ';
    sb << (epRow_ >= 0 ? ChessMove::squareName(epRow_, epCol_) : "-");
    sb << ' ' << halfmoveClock_ << ' ' << fullmoveNumber_;
    return sb.str();
}

void ChessEngine::loadFen(const std::string& fen) {
    std::vector<std::string> fields = splitWs(fen);
    if (fields.size() < 4) throw std::invalid_argument("Bad FEN: " + fen);
    std::vector<std::string> ranks;
    std::string cur;
    for (char ch : fields[0]) {
        if (ch == '/') {
            ranks.push_back(cur);
            cur.clear();
        } else {
            cur += ch;
        }
    }
    ranks.push_back(cur);
    if (ranks.size() != 8) throw std::invalid_argument("Bad FEN board: " + fen);
    for (int r = 0; r < 8; r++) {
        int c = 0;
        for (char ch : ranks[r]) {
            if (std::isdigit(static_cast<unsigned char>(ch))) {
                for (int k = 0; k < ch - '0'; k++) board_[r][c++] = ChessPiece::Empty;
            } else {
                int type = ChessPiece::typeFromLetter(ch);
                board_[r][c++] = std::isupper(static_cast<unsigned char>(ch))
                                     ? ChessPiece::of(type, Side::White)
                                     : ChessPiece::of(type, Side::Black);
            }
        }
        if (c != 8) throw std::invalid_argument("Bad FEN rank: " + ranks[r]);
    }
    sideToMove_ = (fields[1] == "b") ? Side::Black : Side::White;
    const std::string& castling = fields[2];
    whiteKingside_ = castling.find('K') != std::string::npos;
    whiteQueenside_ = castling.find('Q') != std::string::npos;
    blackKingside_ = castling.find('k') != std::string::npos;
    blackQueenside_ = castling.find('q') != std::string::npos;
    if (fields[3] == "-") {
        epRow_ = epCol_ = -1;
    } else {
        auto ep = ChessMove::parseSquare(fields[3]);
        epRow_ = ep.first;
        epCol_ = ep.second;
    }
    halfmoveClock_ = fields.size() > 4 ? std::stoi(fields[4]) : 0;
    fullmoveNumber_ = fields.size() > 5 ? std::stoi(fields[5]) : 1;
    outcome_ = Outcome::InProgress;
    outcomeReason_.clear();
    positionCounts_.clear();
    recordPosition();
    updateOutcome();
}

std::string ChessEngine::boardDiagram() const {
    std::ostringstream sb;
    for (int r = 0; r < 8; r++) {
        sb << (8 - r) << ' ';
        for (int c = 0; c < 8; c++) {
            int p = board_[r][c];
            sb << (p == ChessPiece::Empty ? '.' : ChessPiece::fenLetter(p)) << ' ';
        }
        sb << '\n';
    }
    sb << "  a b c d e f g h\n";
    sb << sideName(sideToMove_) << " to move";
    if (inCheck()) sb << " (check)";
    return sb.str();
}

}  // namespace engine
