// American / English draughts rules, decoupled from any UI.
//
// - Men move one diagonal step forward only; kings move one diagonal
//   step in any direction. Kings are non-flying: a jump is always over
//   exactly one adjacent enemy piece onto the empty square behind it.
// - Captures are mandatory: when any jump exists for the side to move,
//   only jumps are legal.
// - A jump that leaves a further jump open for the same piece must be
//   continued (multi-jump chain); the turn does not pass until the chain
//   ends. Reaching the far row crowns the piece and ends the turn at once,
//   even if a jump would be open to the new king.
// - The game ends when a side has no pieces or no legal move (that side
//   loses). Draws: threefold repetition, and 80 ply without a capture or a
//   crowning (the 40-move rule).
//
// Playable squares are the dark squares, where (row + col) is odd.
// BLACK starts on rows 0-2 and moves first (the UI may swap first player).
// Coordinates: row 0 = rank 8, col 0 = file a.
#pragma once

#include <map>
#include <string>
#include <vector>

#include "checkers_move.hpp"
#include "side.hpp"

namespace engine {

class CheckersEngine {
public:
    static constexpr int Empty = 0;
    static constexpr int WhiteMan = 1;
    static constexpr int WhiteKing = 2;
    static constexpr int BlackMan = 3;
    static constexpr int BlackKing = 4;

    // Ply without a capture or a crowning at which the game is drawn.
    static constexpr int DrawPlyLimit = 80;

    enum class Outcome { InProgress, WhiteWins, BlackWins, Draw };

    CheckersEngine();

    CheckersEngine copy() const;

    // Removes every piece. Test / setup helper; resets move state.
    void clearBoard();
    void setPiece(int row, int col, int piece);
    void setSideToMove(Side side);

    int pieceAt(int row, int col) const;
    Side sideToMove() const { return sideToMove_; }

    bool isChainActive() const { return chainRow_ >= 0; }
    int chainRow() const { return chainRow_; }
    int chainCol() const { return chainCol_; }
    int nonCapturePly() const { return nonCapturePly_; }
    int plyCount() const { return plyCount_; }

    Outcome outcome() const { return outcome_; }
    const std::string& outcomeReason() const { return outcomeReason_; }
    bool isGameOver() const { return outcome_ != Outcome::InProgress; }

    static Side sideOf(int piece);  // throws on Empty
    static bool isKing(int piece);
    static bool inBounds(int row, int col) { return row >= 0 && row < 8 && col >= 0 && col < 8; }

    int countPieces(Side side) const;

    // All legal steps from one square, honouring mandatory captures.
    std::vector<CheckersMove> legalMovesFor(int row, int col) const;
    // All legal steps for the side to move (chain-restricted if mid-chain).
    std::vector<CheckersMove> legalMoves() const;
    bool isLegal(const CheckersMove& move) const;

    // Applies one legal step. Throws std::invalid_argument otherwise
    // and leaves the position unchanged.
    void applyMove(const CheckersMove& move);

    // Applies a coordinate notation such as "a3-b4", "a3xb4", "a3b4"
    // or a chain "a3xc5xe7". Returns the steps applied. The whole
    // notation is validated before anything is applied: an illegal chain
    // leaves the position unchanged.
    std::vector<CheckersMove> applyNotation(const std::string& notation);
    // Parses notation against a copy, so failures mutate nothing.
    std::vector<CheckersMove> parseNotation(const std::string& notation) const;
    // Parses one step ("a3-b4" / "a3xb4") against the current position,
    // without requiring a multi-jump chain to be complete. Used for
    // single-step network moves.
    CheckersMove parseSingleStep(const std::string& notation) const;

    // Human-readable board diagram, row 8 at the top.
    std::string boardDiagram() const;

private:
    CheckersEngine(bool setup);
    void setup();
    static void checkSquare(int row, int col);
    void jumpsFrom(int row, int col, std::vector<CheckersMove>& out) const;
    void quietsFrom(int row, int col, std::vector<CheckersMove>& out) const;
    std::string positionKey() const;
    void recordPosition();
    void updateOutcome();

    int board_[8][8];
    Side sideToMove_ = Side::Black;
    int chainRow_ = -1, chainCol_ = -1;
    int nonCapturePly_ = 0, plyCount_ = 0;
    std::map<std::string, int> positionCounts_;
    Outcome outcome_ = Outcome::InProgress;
    std::string outcomeReason_;
};

inline std::string checkersOutcomeName(CheckersEngine::Outcome o) {
    switch (o) {
        case CheckersEngine::Outcome::WhiteWins: return "WHITE_WINS";
        case CheckersEngine::Outcome::BlackWins: return "BLACK_WINS";
        case CheckersEngine::Outcome::Draw: return "DRAW";
        default: return "IN_PROGRESS";
    }
}

}  // namespace engine
