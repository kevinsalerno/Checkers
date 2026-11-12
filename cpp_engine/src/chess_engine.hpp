// Full chess rules, decoupled from any UI.
//
// Supports castling (both sides, through check is illegal), en passant,
// promotion to any piece, check, checkmate, stalemate, the fifty-move
// rule, threefold repetition and insufficient material. Moves are
// generated pseudo-legally and filtered by king safety on a copy, so
// pinned pieces, discovered checks and castling through check are all
// handled by the same legality test.
//
// Coordinates: row 0 = rank 8, col 0 = file a. White starts on rows
// 6-7 and moves first.
#pragma once

#include <map>
#include <string>
#include <vector>

#include "chess_move.hpp"
#include "chess_piece.hpp"
#include "side.hpp"

namespace engine {

class ChessEngine {
public:
    enum class Outcome { InProgress, WhiteWins, BlackWins, Draw };

    static const std::string StartFen;

    ChessEngine();

    ChessEngine copy() const;

    int pieceAt(int row, int col) const { return board_[row][col]; }
    Side sideToMove() const { return sideToMove_; }
    int halfmoveClock() const { return halfmoveClock_; }
    int fullmoveNumber() const { return fullmoveNumber_; }
    Outcome outcome() const { return outcome_; }
    const std::string& outcomeReason() const { return outcomeReason_; }
    bool isGameOver() const { return outcome_ != Outcome::InProgress; }

    // Is the given side's king currently in check?
    bool inCheck(Side side) const;
    bool inCheck() const { return inCheck(sideToMove_); }

    static bool inBounds(int row, int col) { return row >= 0 && row < 8 && col >= 0 && col < 8; }

    // Test/setup helpers: clear the board and reset all state.
    void clearBoard();
    void setPiece(int row, int col, int piece);
    void setSideToMove(Side side);
    void setCastlingRights(bool wk, bool wq, bool bk, bool bq);
    void setEnPassant(int row, int col);
    bool canCastleKingside(Side side) const {
        return side == Side::White ? whiteKingside_ : blackKingside_;
    }
    bool canCastleQueenside(Side side) const {
        return side == Side::White ? whiteQueenside_ : blackQueenside_;
    }

    // Is (row,col) attacked by any piece of `by`?
    bool isSquareAttacked(int row, int col, Side by) const;

    // Pseudo-legal moves (king safety not yet checked).
    std::vector<ChessMove> pseudoLegalMoves() const;
    // Fully legal moves for the side to move.
    std::vector<ChessMove> legalMoves() const;
    std::vector<ChessMove> legalMovesFor(int row, int col) const;
    bool isLegal(const ChessMove& move) const;

    // Is this move a capture (including en passant)?
    bool isCapture(const ChessMove& move) const;

    // Applies a move without legality or history bookkeeping.
    // Public (like the Java package-private original) so perft and
    // copying probes in tests can use it directly.
    void applyUnchecked(const ChessMove& move);

    // Applies one legal move. Throws std::invalid_argument otherwise
    // and leaves the position unchanged.
    void applyMove(const ChessMove& move);

    bool insufficientMaterial() const;

    // Parses coordinate notation: "e2e4", "e2-e4", "e7e8q", "e7e8=Q".
    ChessMove parseCoordinate(const std::string& text) const;
    // Parses Standard Algebraic Notation: "Nf3", "exd5", "O-O",
    // "e8=Q", "R1e2", "Qxf7+". Check/mate suffixes are ignored.
    ChessMove parseSan(const std::string& text) const;
    // Parses either coordinate or SAN notation and applies it.
    // Throws std::invalid_argument on illegal input, mutating nothing.
    ChessMove applyNotation(const std::string& notation);

    std::string toFen() const;
    // Loads a FEN position, resetting history to that position.
    void loadFen(const std::string& fen);

    // Human-readable board diagram, rank 8 at the top.
    std::string boardDiagram() const;

private:
    ChessEngine(bool setup);
    void setup();
    std::pair<int, int> findKing(Side side) const;  // {-1,-1} if absent
    void addSliderMoves(int row, int col, const int dirs[][2], int n,
                        std::vector<ChessMove>& out) const;
    void addStepMoves(int row, int col, const int offsets[][2], int n,
                      std::vector<ChessMove>& out) const;
    void addPawnMoves(int row, int col, std::vector<ChessMove>& out) const;
    void addPawnAdvance(int fromRow, int fromCol, int toRow, int toCol, int promoRow,
                        std::vector<ChessMove>& out) const;
    void addCastlingMoves(int row, int col, std::vector<ChessMove>& out) const;
    void clearRookRight(int row, int col);
    std::string positionKey() const;
    bool canCaptureEnPassant() const;
    void recordPosition();
    void updateOutcome();
    static bool isCoordinateNotation(const std::string& trimmed);

    int board_[8][8];
    Side sideToMove_ = Side::White;
    bool whiteKingside_ = true, whiteQueenside_ = true;
    bool blackKingside_ = true, blackQueenside_ = true;
    int epRow_ = -1, epCol_ = -1;
    int halfmoveClock_ = 0, fullmoveNumber_ = 1;
    std::map<std::string, int> positionCounts_;
    Outcome outcome_ = Outcome::InProgress;
    std::string outcomeReason_;
};

inline std::string chessOutcomeName(ChessEngine::Outcome o) {
    switch (o) {
        case ChessEngine::Outcome::WhiteWins: return "WHITE_WINS";
        case ChessEngine::Outcome::BlackWins: return "BLACK_WINS";
        case ChessEngine::Outcome::Draw: return "DRAW";
        default: return "IN_PROGRESS";
    }
}

}  // namespace engine
