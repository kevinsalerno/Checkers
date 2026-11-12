#include "checkers_engine.hpp"

#include <cctype>
#include <sstream>
#include <stdexcept>

namespace engine {

CheckersEngine::CheckersEngine() { setup(); }

CheckersEngine::CheckersEngine(bool setup) {
    if (setup) this->setup();
}

void CheckersEngine::setup() {
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) board_[r][c] = Empty;
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 8; c++)
            if ((r + c) % 2 == 1) board_[r][c] = BlackMan;
    for (int r = 5; r < 8; r++)
        for (int c = 0; c < 8; c++)
            if ((r + c) % 2 == 1) board_[r][c] = WhiteMan;
    sideToMove_ = Side::Black;
    chainRow_ = -1;
    chainCol_ = -1;
    nonCapturePly_ = 0;
    plyCount_ = 0;
    outcome_ = Outcome::InProgress;
    outcomeReason_.clear();
    positionCounts_.clear();
    recordPosition();
    updateOutcome();
}

CheckersEngine CheckersEngine::copy() const {
    CheckersEngine e(false);
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) e.board_[r][c] = board_[r][c];
    e.sideToMove_ = sideToMove_;
    e.chainRow_ = chainRow_;
    e.chainCol_ = chainCol_;
    e.nonCapturePly_ = nonCapturePly_;
    e.plyCount_ = plyCount_;
    e.positionCounts_ = positionCounts_;
    e.outcome_ = outcome_;
    e.outcomeReason_ = outcomeReason_;
    return e;
}

void CheckersEngine::clearBoard() {
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) board_[r][c] = Empty;
    sideToMove_ = Side::Black;
    chainRow_ = -1;
    chainCol_ = -1;
    nonCapturePly_ = 0;
    plyCount_ = 0;
    outcome_ = Outcome::InProgress;
    outcomeReason_.clear();
    positionCounts_.clear();
    recordPosition();
    updateOutcome();
}

void CheckersEngine::setPiece(int row, int col, int piece) {
    checkSquare(row, col);
    board_[row][col] = piece;
    positionCounts_.clear();
    recordPosition();
    updateOutcome();
}

void CheckersEngine::setSideToMove(Side side) {
    sideToMove_ = side;
    positionCounts_.clear();
    recordPosition();
    updateOutcome();
}

int CheckersEngine::pieceAt(int row, int col) const {
    checkSquare(row, col);
    return board_[row][col];
}

void CheckersEngine::checkSquare(int row, int col) {
    if (!inBounds(row, col))
        throw std::invalid_argument("Square out of bounds: " + std::to_string(row) + "," +
                                    std::to_string(col));
}

Side CheckersEngine::sideOf(int piece) {
    if (piece == WhiteMan || piece == WhiteKing) return Side::White;
    if (piece == BlackMan || piece == BlackKing) return Side::Black;
    throw std::invalid_argument("sideOf(empty)");
}

bool CheckersEngine::isKing(int piece) { return piece == WhiteKing || piece == BlackKing; }

int CheckersEngine::countPieces(Side side) const {
    int n = 0;
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) {
            int p = board_[r][c];
            if (p != Empty && sideOf(p) == side) n++;
        }
    return n;
}

// Directions a piece may step/jump in: {dRow, dCol}.
static void pieceDirections(int piece, int out[4][2], int& n) {
    if (CheckersEngine::isKing(piece)) {
        out[0][0] = -1; out[0][1] = -1;
        out[1][0] = -1; out[1][1] = 1;
        out[2][0] = 1;  out[2][1] = -1;
        out[3][0] = 1;  out[3][1] = 1;
        n = 4;
        return;
    }
    int fwd = CheckersEngine::sideOf(piece) == Side::White ? -1 : 1;
    out[0][0] = fwd; out[0][1] = -1;
    out[1][0] = fwd; out[1][1] = 1;
    n = 2;
}

void CheckersEngine::jumpsFrom(int row, int col, std::vector<CheckersMove>& out) const {
    int piece = board_[row][col];
    int dirs[4][2];
    int n = 0;
    pieceDirections(piece, dirs, n);
    for (int i = 0; i < n; i++) {
        int midR = row + dirs[i][0], midC = col + dirs[i][1];
        int landR = row + 2 * dirs[i][0], landC = col + 2 * dirs[i][1];
        if (!inBounds(landR, landC)) continue;
        int mid = board_[midR][midC];
        if (mid != Empty && sideOf(mid) == opponentOf(sideOf(piece)) &&
            board_[landR][landC] == Empty)
            out.emplace_back(row, col, landR, landC, midR, midC);
    }
}

void CheckersEngine::quietsFrom(int row, int col, std::vector<CheckersMove>& out) const {
    int piece = board_[row][col];
    int dirs[4][2];
    int n = 0;
    pieceDirections(piece, dirs, n);
    for (int i = 0; i < n; i++) {
        int r = row + dirs[i][0], c = col + dirs[i][1];
        if (inBounds(r, c) && board_[r][c] == Empty) out.emplace_back(row, col, r, c);
    }
}

std::vector<CheckersMove> CheckersEngine::legalMovesFor(int row, int col) const {
    checkSquare(row, col);
    std::vector<CheckersMove> result;
    int piece = board_[row][col];
    if (isGameOver() || piece == Empty || sideOf(piece) != sideToMove_) return result;
    if (isChainActive() && (row != chainRow_ || col != chainCol_)) return result;
    if (isChainActive()) {
        jumpsFrom(row, col, result);
        return result;
    }
    bool anyJump = false;
    for (int r = 0; r < 8 && !anyJump; r++)
        for (int c = 0; c < 8 && !anyJump; c++) {
            if (board_[r][c] != Empty && sideOf(board_[r][c]) == sideToMove_) {
                std::vector<CheckersMove> tmp;
                jumpsFrom(r, c, tmp);
                if (!tmp.empty()) anyJump = true;
            }
        }
    if (anyJump) {
        jumpsFrom(row, col, result);
        return result;
    }
    quietsFrom(row, col, result);
    return result;
}

std::vector<CheckersMove> CheckersEngine::legalMoves() const {
    std::vector<CheckersMove> result;
    if (isGameOver()) return result;
    if (isChainActive()) {
        jumpsFrom(chainRow_, chainCol_, result);
        return result;
    }
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++)
            if (board_[r][c] != Empty && sideOf(board_[r][c]) == sideToMove_)
                jumpsFrom(r, c, result);
    if (!result.empty()) return result;
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++)
            if (board_[r][c] != Empty && sideOf(board_[r][c]) == sideToMove_)
                quietsFrom(r, c, result);
    return result;
}

bool CheckersEngine::isLegal(const CheckersMove& move) const {
    for (const auto& m : legalMoves())
        if (m == move) return true;
    return false;
}

void CheckersEngine::applyMove(const CheckersMove& move) {
    if (!isLegal(move))
        throw std::invalid_argument("Illegal checkers move: " + move.toString());
    int piece = board_[move.fromRow][move.fromCol];
    board_[move.fromRow][move.fromCol] = Empty;
    if (move.isJump()) board_[move.capturedRow][move.capturedCol] = Empty;
    bool crowned = false;
    if (!isKing(piece)) {
        if (piece == WhiteMan && move.toRow == 0) {
            piece = WhiteKing;
            crowned = true;
        } else if (piece == BlackMan && move.toRow == 7) {
            piece = BlackKing;
            crowned = true;
        }
    }
    board_[move.toRow][move.toCol] = piece;
    plyCount_++;
    nonCapturePly_ = (move.isJump() || crowned) ? 0 : nonCapturePly_ + 1;
    // Chain continuation: same piece, same turn, unless crowned.
    chainRow_ = -1;
    chainCol_ = -1;
    if (move.isJump() && !crowned) {
        std::vector<CheckersMove> further;
        jumpsFrom(move.toRow, move.toCol, further);
        if (!further.empty()) {
            chainRow_ = move.toRow;
            chainCol_ = move.toCol;
        }
    }
    if (!isChainActive()) sideToMove_ = opponentOf(sideToMove_);
    recordPosition();
    updateOutcome();
}

static std::string stripSeparators(const std::string& s) {
    std::string out;
    for (char ch : s) {
        if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == ',') continue;
        out += ch;
    }
    return out;
}

std::vector<CheckersMove> CheckersEngine::parseNotation(const std::string& notation) const {
    std::string cleaned = stripSeparators(notation);
    if (cleaned.empty()) throw std::invalid_argument("Empty checkers notation");
    // Split on '-'/'x'/'X', keeping square tokens.
    std::vector<std::string> parts;
    std::string cur;
    for (char ch : cleaned) {
        if (ch == '-' || ch == 'x' || ch == 'X') {
            parts.push_back(cur);
            cur.clear();
        } else {
            cur += ch;
        }
    }
    parts.push_back(cur);
    if (parts.size() < 2) throw std::invalid_argument("Bad checkers notation: " + notation);
    std::vector<std::pair<int, int>> squares;
    for (const auto& part : parts) squares.push_back(CheckersMove::parseSquare(part));
    CheckersEngine probe = copy();
    std::vector<CheckersMove> steps;
    for (size_t i = 0; i + 1 < squares.size(); i++) {
        bool found = false;
        CheckersMove match;
        for (const auto& m : probe.legalMoves()) {
            if (m.fromRow == squares[i].first && m.fromCol == squares[i].second &&
                m.toRow == squares[i + 1].first && m.toCol == squares[i + 1].second) {
                match = m;
                found = true;
                break;
            }
        }
        if (!found)
            throw std::invalid_argument(
                "Illegal checkers step " +
                CheckersMove::squareName(squares[i].first, squares[i].second) + " to " +
                CheckersMove::squareName(squares[i + 1].first, squares[i + 1].second) +
                " in notation: " + notation);
        steps.push_back(match);
        probe.applyMove(match);
    }
    // A chain notation must be complete: it may not stop mid-chain
    // while a further jump is compulsory.
    if (probe.isChainActive())
        throw std::invalid_argument("Incomplete multi-jump in notation: " + notation);
    return steps;
}

std::vector<CheckersMove> CheckersEngine::applyNotation(const std::string& notation) {
    std::vector<CheckersMove> steps = parseNotation(notation);
    for (const auto& step : steps) applyMove(step);
    return steps;
}

CheckersMove CheckersEngine::parseSingleStep(const std::string& notation) const {
    std::string cleaned = stripSeparators(notation);
    std::vector<std::string> parts;
    std::string cur;
    for (char ch : cleaned) {
        if (ch == '-' || ch == 'x' || ch == 'X') {
            parts.push_back(cur);
            cur.clear();
        } else {
            cur += ch;
        }
    }
    parts.push_back(cur);
    if (parts.size() != 2) throw std::invalid_argument("Bad checkers step: " + notation);
    auto from = CheckersMove::parseSquare(parts[0]);
    auto to = CheckersMove::parseSquare(parts[1]);
    for (const auto& m : legalMoves())
        if (m.fromRow == from.first && m.fromCol == from.second && m.toRow == to.first &&
            m.toCol == to.second)
            return m;
    throw std::invalid_argument("Illegal checkers step in: " + notation);
}

std::string CheckersEngine::positionKey() const {
    std::string sb;
    sb.reserve(80);
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) sb += static_cast<char>('0' + board_[r][c]);
    sb += (sideToMove_ == Side::White ? 'w' : 'b');
    sb += std::to_string(chainRow_) + "," + std::to_string(chainCol_);
    return sb;
}

void CheckersEngine::recordPosition() {
    positionCounts_[positionKey()]++;
}

void CheckersEngine::updateOutcome() {
    // Recompute from scratch so test positions built with setPiece
    // in several steps are evaluated on their final position.
    outcome_ = Outcome::InProgress;
    outcomeReason_.clear();
    if (countPieces(Side::White) == 0) {
        outcome_ = Outcome::BlackWins;
        outcomeReason_ = "White has no pieces left";
        return;
    }
    if (countPieces(Side::Black) == 0) {
        outcome_ = Outcome::WhiteWins;
        outcomeReason_ = "Black has no pieces left";
        return;
    }
    auto it = positionCounts_.find(positionKey());
    if (it != positionCounts_.end() && it->second >= 3) {
        outcome_ = Outcome::Draw;
        outcomeReason_ = "Threefold repetition";
        return;
    }
    if (nonCapturePly_ >= DrawPlyLimit) {
        outcome_ = Outcome::Draw;
        outcomeReason_ = "40-move rule: " + std::to_string(DrawPlyLimit) +
                         " ply without a capture or crowning";
        return;
    }
    // No legal move: only meaningful once a side is actually to move
    // with pieces present; legalMoves() is empty exactly then.
    if (!isChainActive() && legalMoves().empty()) {
        outcome_ = (sideToMove_ == Side::White) ? Outcome::BlackWins : Outcome::WhiteWins;
        outcomeReason_ = std::string(sideName(sideToMove_)) + " has no legal move";
    }
}

std::string CheckersEngine::boardDiagram() const {
    std::ostringstream sb;
    for (int r = 0; r < 8; r++) {
        sb << (8 - r) << ' ';
        for (int c = 0; c < 8; c++) {
            char ch;
            switch (board_[r][c]) {
                case WhiteMan: ch = 'w'; break;
                case WhiteKing: ch = 'W'; break;
                case BlackMan: ch = 'b'; break;
                case BlackKing: ch = 'B'; break;
                default: ch = ((r + c) % 2 == 1) ? '.' : ' ';
            }
            sb << ch << ' ';
        }
        sb << '\n';
    }
    sb << "  a b c d e f g h\n";
    sb << sideName(sideToMove_) << " to move";
    if (isChainActive())
        sb << ", chain at " << CheckersMove::squareName(chainRow_, chainCol_);
    return sb.str();
}

}  // namespace engine
