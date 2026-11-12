// ChessEngine part 1: setup, attacks, move generation, applyUnchecked.
#include "chess_engine.hpp"

#include <cmath>
#include <sstream>
#include <stdexcept>

namespace engine {

const std::string ChessEngine::StartFen =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

ChessEngine::ChessEngine() { setup(); }

ChessEngine::ChessEngine(bool setup) {
    if (setup) this->setup();
}

void ChessEngine::setup() {
    const int back[8] = {ChessPiece::Rook, ChessPiece::Knight, ChessPiece::Bishop,
                         ChessPiece::Queen, ChessPiece::King, ChessPiece::Bishop,
                         ChessPiece::Knight, ChessPiece::Rook};
    for (int c = 0; c < 8; c++) {
        board_[0][c] = ChessPiece::of(back[c], Side::Black);
        board_[1][c] = ChessPiece::of(ChessPiece::Pawn, Side::Black);
        board_[6][c] = ChessPiece::of(ChessPiece::Pawn, Side::White);
        board_[7][c] = ChessPiece::of(back[c], Side::White);
    }
    for (int r = 2; r < 6; r++)
        for (int c = 0; c < 8; c++) board_[r][c] = ChessPiece::Empty;
    sideToMove_ = Side::White;
    whiteKingside_ = whiteQueenside_ = true;
    blackKingside_ = blackQueenside_ = true;
    epRow_ = epCol_ = -1;
    halfmoveClock_ = 0;
    fullmoveNumber_ = 1;
    outcome_ = Outcome::InProgress;
    outcomeReason_.clear();
    positionCounts_.clear();
    recordPosition();
    updateOutcome();
}

ChessEngine ChessEngine::copy() const {
    ChessEngine e(false);
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) e.board_[r][c] = board_[r][c];
    e.sideToMove_ = sideToMove_;
    e.whiteKingside_ = whiteKingside_;
    e.whiteQueenside_ = whiteQueenside_;
    e.blackKingside_ = blackKingside_;
    e.blackQueenside_ = blackQueenside_;
    e.epRow_ = epRow_;
    e.epCol_ = epCol_;
    e.halfmoveClock_ = halfmoveClock_;
    e.fullmoveNumber_ = fullmoveNumber_;
    e.positionCounts_ = positionCounts_;
    e.outcome_ = outcome_;
    e.outcomeReason_ = outcomeReason_;
    return e;
}

void ChessEngine::clearBoard() {
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) board_[r][c] = ChessPiece::Empty;
    sideToMove_ = Side::White;
    whiteKingside_ = whiteQueenside_ = true;
    blackKingside_ = blackQueenside_ = true;
    epRow_ = epCol_ = -1;
    halfmoveClock_ = 0;
    fullmoveNumber_ = 1;
    outcome_ = Outcome::InProgress;
    outcomeReason_.clear();
    positionCounts_.clear();
    recordPosition();
    updateOutcome();
}

void ChessEngine::setPiece(int row, int col, int piece) {
    board_[row][col] = piece;
    positionCounts_.clear();
    recordPosition();
    updateOutcome();
}

void ChessEngine::setSideToMove(Side side) {
    sideToMove_ = side;
    positionCounts_.clear();
    recordPosition();
    updateOutcome();
}

void ChessEngine::setCastlingRights(bool wk, bool wq, bool bk, bool bq) {
    whiteKingside_ = wk;
    whiteQueenside_ = wq;
    blackKingside_ = bk;
    blackQueenside_ = bq;
    positionCounts_.clear();
    recordPosition();
    updateOutcome();
}

void ChessEngine::setEnPassant(int row, int col) {
    epRow_ = row;
    epCol_ = col;
}

std::pair<int, int> ChessEngine::findKing(Side side) const {
    int wanted = ChessPiece::of(ChessPiece::King, side);
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++)
            if (board_[r][c] == wanted) return {r, c};
    return {-1, -1};
}

bool ChessEngine::inCheck(Side side) const {
    auto king = findKing(side);
    if (king.first < 0) return false;
    return isSquareAttacked(king.first, king.second, opponentOf(side));
}

bool ChessEngine::isSquareAttacked(int row, int col, Side by) const {
    // Pawns: an attacking pawn sits one step behind the target
    // along its direction of travel.
    int pawnDir = (by == Side::White) ? -1 : 1;
    int pawnRow = row - pawnDir;
    for (int dc : {-1, 1}) {
        int c = col + dc;
        if (inBounds(pawnRow, c) && board_[pawnRow][c] == ChessPiece::of(ChessPiece::Pawn, by))
            return true;
    }
    static const int knightOffsets[8][2] = {{-2, -1}, {-2, 1}, {-1, -2}, {-1, 2},
                                            {1, -2}, {1, 2}, {2, -1}, {2, 1}};
    for (const auto& o : knightOffsets) {
        int r = row + o[0], c = col + o[1];
        if (inBounds(r, c) && board_[r][c] == ChessPiece::of(ChessPiece::Knight, by))
            return true;
    }
    for (int dr = -1; dr <= 1; dr++)
        for (int dc = -1; dc <= 1; dc++) {
            if (dr == 0 && dc == 0) continue;
            int r = row + dr, c = col + dc;
            if (inBounds(r, c) && board_[r][c] == ChessPiece::of(ChessPiece::King, by))
                return true;
        }
    static const int ortho[4][2] = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    for (const auto& d : ortho) {
        int r = row + d[0], c = col + d[1];
        while (inBounds(r, c)) {
            int p = board_[r][c];
            if (p != ChessPiece::Empty) {
                int t = ChessPiece::typeOf(p);
                if (ChessPiece::sideOf(p) == by && (t == ChessPiece::Rook || t == ChessPiece::Queen))
                    return true;
                break;
            }
            r += d[0];
            c += d[1];
        }
    }
    static const int diag[4][2] = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
    for (const auto& d : diag) {
        int r = row + d[0], c = col + d[1];
        while (inBounds(r, c)) {
            int p = board_[r][c];
            if (p != ChessPiece::Empty) {
                int t = ChessPiece::typeOf(p);
                if (ChessPiece::sideOf(p) == by &&
                    (t == ChessPiece::Bishop || t == ChessPiece::Queen))
                    return true;
                break;
            }
            r += d[0];
            c += d[1];
        }
    }
    return false;
}

void ChessEngine::addSliderMoves(int row, int col, const int dirs[][2], int n,
                                 std::vector<ChessMove>& out) const {
    int piece = board_[row][col];
    Side side = ChessPiece::sideOf(piece);
    for (int i = 0; i < n; i++) {
        int r = row + dirs[i][0], c = col + dirs[i][1];
        while (inBounds(r, c)) {
            int target = board_[r][c];
            if (target == ChessPiece::Empty) {
                out.emplace_back(row, col, r, c);
            } else {
                if (ChessPiece::sideOf(target) != side) out.emplace_back(row, col, r, c);
                break;
            }
            r += dirs[i][0];
            c += dirs[i][1];
        }
    }
}

void ChessEngine::addStepMoves(int row, int col, const int offsets[][2], int n,
                               std::vector<ChessMove>& out) const {
    int piece = board_[row][col];
    Side side = ChessPiece::sideOf(piece);
    for (int i = 0; i < n; i++) {
        int r = row + offsets[i][0], c = col + offsets[i][1];
        if (!inBounds(r, c)) continue;
        int target = board_[r][c];
        if (target == ChessPiece::Empty || ChessPiece::sideOf(target) != side)
            out.emplace_back(row, col, r, c);
    }
}

void ChessEngine::addPawnMoves(int row, int col, std::vector<ChessMove>& out) const {
    int piece = board_[row][col];
    Side side = ChessPiece::sideOf(piece);
    int dir = (side == Side::White) ? -1 : 1;
    int startRow = (side == Side::White) ? 6 : 1;
    int promoRow = (side == Side::White) ? 0 : 7;
    int one = row + dir;
    if (inBounds(one, col) && board_[one][col] == ChessPiece::Empty) {
        addPawnAdvance(row, col, one, col, promoRow, out);
        int two = row + 2 * dir;
        if (row == startRow && board_[two][col] == ChessPiece::Empty)
            out.emplace_back(row, col, two, col);
    }
    for (int dc : {-1, 1}) {
        int c = col + dc;
        if (!inBounds(one, c)) continue;
        int target = board_[one][c];
        if (target != ChessPiece::Empty && ChessPiece::sideOf(target) != side) {
            addPawnAdvance(row, col, one, c, promoRow, out);
        } else if (one == epRow_ && c == epCol_ && target == ChessPiece::Empty) {
            out.emplace_back(row, col, one, c, ChessPiece::Empty, true, false);
        }
    }
}

void ChessEngine::addPawnAdvance(int fromRow, int fromCol, int toRow, int toCol, int promoRow,
                                 std::vector<ChessMove>& out) const {
    if (toRow == promoRow) {
        for (int promo : {ChessPiece::Queen, ChessPiece::Rook, ChessPiece::Bishop,
                          ChessPiece::Knight})
            out.emplace_back(fromRow, fromCol, toRow, toCol, promo, false, false);
    } else {
        out.emplace_back(fromRow, fromCol, toRow, toCol);
    }
}

void ChessEngine::addCastlingMoves(int row, int col, std::vector<ChessMove>& out) const {
    Side side = sideToMove_;
    int backRow = (side == Side::White) ? 7 : 0;
    if (row != backRow || col != 4) return;
    if (inCheck(side)) return;
    Side enemy = opponentOf(side);
    bool ks = (side == Side::White) ? whiteKingside_ : blackKingside_;
    bool qs = (side == Side::White) ? whiteQueenside_ : blackQueenside_;
    if (ks && board_[backRow][5] == ChessPiece::Empty && board_[backRow][6] == ChessPiece::Empty &&
        board_[backRow][7] == ChessPiece::of(ChessPiece::Rook, side) &&
        !isSquareAttacked(backRow, 5, enemy) && !isSquareAttacked(backRow, 6, enemy))
        out.emplace_back(row, col, backRow, 6, ChessPiece::Empty, false, true);
    if (qs && board_[backRow][3] == ChessPiece::Empty && board_[backRow][2] == ChessPiece::Empty &&
        board_[backRow][1] == ChessPiece::Empty &&
        board_[backRow][0] == ChessPiece::of(ChessPiece::Rook, side) &&
        !isSquareAttacked(backRow, 3, enemy) && !isSquareAttacked(backRow, 2, enemy))
        out.emplace_back(row, col, backRow, 2, ChessPiece::Empty, false, true);
}

std::vector<ChessMove> ChessEngine::pseudoLegalMoves() const {
    std::vector<ChessMove> out;
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) {
            int piece = board_[r][c];
            if (piece == ChessPiece::Empty || ChessPiece::sideOf(piece) != sideToMove_) continue;
            int type = ChessPiece::typeOf(piece);
            switch (type) {
                case ChessPiece::Pawn: addPawnMoves(r, c, out); break;
                case ChessPiece::Knight: {
                    static const int off[8][2] = {{-2, -1}, {-2, 1}, {-1, -2}, {-1, 2},
                                                  {1, -2}, {1, 2}, {2, -1}, {2, 1}};
                    addStepMoves(r, c, off, 8, out);
                    break;
                }
                case ChessPiece::Bishop: {
                    static const int d[4][2] = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
                    addSliderMoves(r, c, d, 4, out);
                    break;
                }
                case ChessPiece::Rook: {
                    static const int d[4][2] = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                    addSliderMoves(r, c, d, 4, out);
                    break;
                }
                case ChessPiece::Queen: {
                    static const int d[8][2] = {{-1, 0}, {1, 0}, {0, -1}, {0, 1},
                                                {-1, -1}, {-1, 1}, {1, -1}, {1, 1}};
                    addSliderMoves(r, c, d, 8, out);
                    break;
                }
                case ChessPiece::King: {
                    static const int off[8][2] = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1},
                                                  {0, 1}, {1, -1}, {1, 0}, {1, 1}};
                    addStepMoves(r, c, off, 8, out);
                    addCastlingMoves(r, c, out);
                    break;
                }
                default: break;
            }
        }
    return out;
}

std::vector<ChessMove> ChessEngine::legalMoves() const {
    std::vector<ChessMove> result;
    if (isGameOver()) return result;
    for (const auto& m : pseudoLegalMoves()) {
        ChessEngine probe = copy();
        probe.applyUnchecked(m);
        if (!probe.inCheck(sideToMove_)) result.push_back(m);
    }
    return result;
}

std::vector<ChessMove> ChessEngine::legalMovesFor(int row, int col) const {
    std::vector<ChessMove> result;
    for (const auto& m : legalMoves())
        if (m.fromRow == row && m.fromCol == col) result.push_back(m);
    return result;
}

bool ChessEngine::isLegal(const ChessMove& move) const {
    for (const auto& m : legalMoves())
        if (m == move) return true;
    return false;
}

bool ChessEngine::isCapture(const ChessMove& move) const {
    return move.isEnPassant || board_[move.toRow][move.toCol] != ChessPiece::Empty;
}

void ChessEngine::applyUnchecked(const ChessMove& move) {
    int piece = board_[move.fromRow][move.fromCol];
    int captured = board_[move.toRow][move.toCol];
    Side mover = sideToMove_;
    board_[move.fromRow][move.fromCol] = ChessPiece::Empty;
    if (move.isEnPassant) {
        board_[move.fromRow][move.toCol] = ChessPiece::Empty;
        captured = ChessPiece::of(ChessPiece::Pawn, opponentOf(mover));
    }
    if (move.isCastling) {
        int backRow = (mover == Side::White) ? 7 : 0;
        if (move.toCol == 6) {
            board_[backRow][5] = board_[backRow][7];
            board_[backRow][7] = ChessPiece::Empty;
        } else {
            board_[backRow][3] = board_[backRow][0];
            board_[backRow][0] = ChessPiece::Empty;
        }
    }
    int placed = piece;
    if (move.promotion != ChessPiece::Empty) placed = ChessPiece::of(move.promotion, mover);
    board_[move.toRow][move.toCol] = placed;
    // Castling rights: king move clears both; rook move/capture
    // from a corner clears that side's right.
    if (ChessPiece::typeOf(piece) == ChessPiece::King) {
        if (mover == Side::White) {
            whiteKingside_ = false;
            whiteQueenside_ = false;
        } else {
            blackKingside_ = false;
            blackQueenside_ = false;
        }
    }
    clearRookRight(move.fromRow, move.fromCol);
    clearRookRight(move.toRow, move.toCol);
    // En passant target: only after a double pawn push.
    epRow_ = -1;
    epCol_ = -1;
    if (ChessPiece::typeOf(piece) == ChessPiece::Pawn && std::abs(move.toRow - move.fromRow) == 2) {
        epRow_ = (move.fromRow + move.toRow) / 2;
        epCol_ = move.fromCol;
    }
    halfmoveClock_ =
        (ChessPiece::typeOf(piece) == ChessPiece::Pawn || captured != ChessPiece::Empty)
            ? 0
            : halfmoveClock_ + 1;
    if (mover == Side::Black) fullmoveNumber_++;
    sideToMove_ = opponentOf(mover);
}

void ChessEngine::clearRookRight(int row, int col) {
    if (row == 7 && col == 0) {
        whiteQueenside_ = false;
    } else if (row == 7 && col == 7) {
        whiteKingside_ = false;
    } else if (row == 0 && col == 0) {
        blackQueenside_ = false;
    } else if (row == 0 && col == 7) {
        blackKingside_ = false;
    }
}

void ChessEngine::applyMove(const ChessMove& move) {
    bool legal = false;
    ChessMove canon;
    for (const auto& m : legalMoves())
        if (m == move) {
            legal = true;
            canon = m;
            break;
        }
    if (!legal) throw std::invalid_argument("Illegal chess move: " + move.toString());
    applyUnchecked(canon);
    recordPosition();
    updateOutcome();
}

}  // namespace engine
