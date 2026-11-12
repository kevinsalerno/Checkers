// Owns game state and selection for one game, with no UI dependency,
// so the UI (SDL or headless) is a thin view over it.
//
// Selection model: clicking one of your movable pieces selects it
// and exposes its destinations; clicking a destination applies the
// move. Clicking your own other piece re-selects; clicking elsewhere
// clears the selection. A mid-chain click may only continue the chain.
// A chess promotion click returns NeedPromotion and keeps the
// pending move until applyPromotion() is called.
#pragma once

#include <functional>
#include <optional>
#include <string>
#include <vector>

#include "checkers_engine.hpp"
#include "chess_engine.hpp"
#include "side.hpp"

namespace engine {

class GameController {
public:
    enum class Mode { Checkers, Chess };
    enum class ClickOutcome { Selected, Moved, NeedPromotion, Cleared, Ignored, Illegal };

    struct ClickResult {
        ClickOutcome outcome = ClickOutcome::Ignored;
        bool wasCapture = false;
        bool givesCheck = false;
        std::string notation;
        std::string error;
    };

    using ChangeListener = std::function<void()>;

    GameController() = default;

    void addChangeListener(ChangeListener l) { listeners_.push_back(std::move(l)); }

    Mode mode() const { return mode_; }
    CheckersEngine& checkers() { return checkers_; }
    const CheckersEngine& checkers() const { return checkers_; }
    ChessEngine& chess() { return chess_; }
    const ChessEngine& chess() const { return chess_; }
    Side sideToMove() const {
        return mode_ == Mode::Checkers ? checkers_.sideToMove() : chess_.sideToMove();
    }

    void newGame(Mode newMode);

    void setClockMinutes(int minutes);
    long remainingMs(Side side) const { return remainingMs_[side == Side::White ? 0 : 1]; }

    // Advances the active player's clock; a flag fall ends the game.
    void tick(long elapsedMs);

    bool isGameOver() const;
    std::string outcomeText() const;

    void resign(Side winner);

    void setLocalSide(Side side, bool isHost);
    void clearLocalSide() { localSide_.reset(); }
    std::optional<Side> localSide() const { return localSide_; }
    bool isLocalTurn() const { return !localSide_.has_value() || *localSide_ == sideToMove(); }

    bool hasSelection() const { return selectedRow_ >= 0; }
    int selectedRow() const { return selectedRow_; }
    int selectedCol() const { return selectedCol_; }
    bool hasPendingPromotion() const { return pendingPromotion_.has_value(); }

    std::vector<std::string> moveHistory() const { return moveHistory_; }
    const std::string& lastError() const { return lastError_; }

    // Squares that hold a piece with at least one legal move.
    std::vector<std::pair<int, int>> movableSquares() const;
    // Legal destinations from the selected square, with capture flags {row, col, capture}.
    std::vector<std::tuple<int, int, int>> selectedDestinations() const;
    bool isMovable(int row, int col) const;

    // Applies a move given in engine notation (coordinate/SAN for chess,
    // coordinate or chain notation for checkers). Used by the network
    // peer and the headless harness path. Returns false on illegal input.
    bool applyNotation(const std::string& notation);

    // Applies one move received from a network peer. For checkers this
    // is a single step, so a multi-jump chain arrives step by step and
    // may legitimately leave the chain open.
    bool applyRemoteMove(const std::string& notation);

    // Handles a board click; see the class docs for the selection model.
    ClickResult click(int row, int col);

    // Completes a pending promotion with the chosen piece type.
    ClickResult applyPromotion(int promotionType);
    void cancelPromotion();

    std::string statusText() const;

private:
    void fireChange();
    void clearSelection() {
        selectedRow_ = -1;
        selectedCol_ = -1;
    }
    ClickResult applyCheckersStep(const CheckersMove& step);
    ClickResult applyChessMove(const ChessMove& move);

    Mode mode_ = Mode::Checkers;
    CheckersEngine checkers_;
    ChessEngine chess_;
    int selectedRow_ = -1, selectedCol_ = -1;
    std::optional<ChessMove> pendingPromotion_;
    std::vector<std::string> moveHistory_;
    std::vector<ChangeListener> listeners_;
    // nullopt = both sides local; otherwise only this side is local.
    std::optional<Side> localSide_;
    bool localSideIsHost_ = false;
    long remainingMs_[2] = {10 * 60 * 1000L, 10 * 60 * 1000L};
    bool timeoutOccurred_ = false;
    Side timeoutWinner_ = Side::White;
    bool resigned_ = false;
    Side resignWinner_ = Side::White;
    std::string lastError_;
};

}  // namespace engine
