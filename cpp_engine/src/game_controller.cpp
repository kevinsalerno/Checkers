#include "game_controller.hpp"

#include "audio.hpp"

namespace engine {

void GameController::fireChange() {
    for (auto& l : listeners_) l();
}

void GameController::newGame(Mode newMode) {
    mode_ = newMode;
    if (newMode == Mode::Checkers) {
        checkers_ = CheckersEngine();
    } else {
        chess_ = ChessEngine();
    }
    selectedRow_ = selectedCol_ = -1;
    pendingPromotion_.reset();
    moveHistory_.clear();
    timeoutOccurred_ = false;
    resigned_ = false;
    lastError_.clear();
    // Keep the configured clock; do not reset localSide.
    fireChange();
}

void GameController::setClockMinutes(int minutes) {
    long ms = (minutes < 1 ? 1 : minutes) * 60L * 1000L;
    remainingMs_[0] = remainingMs_[1] = ms;
    timeoutOccurred_ = false;
    fireChange();
}

void GameController::tick(long elapsedMs) {
    if (isGameOver() || elapsedMs <= 0) return;
    int idx = (sideToMove() == Side::White) ? 0 : 1;
    remainingMs_[idx] -= elapsedMs;
    if (remainingMs_[idx] <= 0) {
        remainingMs_[idx] = 0;
        timeoutOccurred_ = true;
        timeoutWinner_ = opponentOf(sideToMove());
        fireChange();
    }
}

bool GameController::isGameOver() const {
    if (timeoutOccurred_ || resigned_) return true;
    return mode_ == Mode::Checkers ? checkers_.isGameOver() : chess_.isGameOver();
}

std::string GameController::outcomeText() const {
    if (timeoutOccurred_)
        return std::string(sideName(timeoutWinner_)) + " wins on time";
    if (resigned_) return std::string(sideName(resignWinner_)) + " wins by resignation";
    if (mode_ == Mode::Checkers) {
        switch (checkers_.outcome()) {
            case CheckersEngine::Outcome::WhiteWins:
                return "White wins - " + checkers_.outcomeReason();
            case CheckersEngine::Outcome::BlackWins:
                return "Black wins - " + checkers_.outcomeReason();
            case CheckersEngine::Outcome::Draw:
                return "Draw - " + checkers_.outcomeReason();
            default:
                return "In progress";
        }
    }
    switch (chess_.outcome()) {
        case ChessEngine::Outcome::WhiteWins:
            return "White wins - " + chess_.outcomeReason();
        case ChessEngine::Outcome::BlackWins:
            return "Black wins - " + chess_.outcomeReason();
        case ChessEngine::Outcome::Draw:
            return "Draw - " + chess_.outcomeReason();
        default:
            return chess_.inCheck() ? "Check" : "In progress";
    }
}

void GameController::resign(Side winner) {
    resigned_ = true;
    resignWinner_ = winner;
    fireChange();
}

void GameController::setLocalSide(Side side, bool isHost) {
    localSide_ = side;
    localSideIsHost_ = isHost;
}

std::vector<std::pair<int, int>> GameController::movableSquares() const {
    std::vector<std::pair<int, int>> result;
    if (isGameOver()) return result;
    auto note = [&result](int r, int c) {
        for (auto& sq : result)
            if (sq.first == r && sq.second == c) return;
        result.emplace_back(r, c);
    };
    if (mode_ == Mode::Checkers) {
        for (const auto& m : checkers_.legalMoves()) note(m.fromRow, m.fromCol);
    } else {
        for (const auto& m : chess_.legalMoves()) note(m.fromRow, m.fromCol);
    }
    return result;
}

std::vector<std::tuple<int, int, int>> GameController::selectedDestinations() const {
    std::vector<std::tuple<int, int, int>> result;
    if (!hasSelection()) return result;
    if (mode_ == Mode::Checkers) {
        for (const auto& m : checkers_.legalMovesFor(selectedRow_, selectedCol_))
            result.emplace_back(m.toRow, m.toCol, m.isJump() ? 1 : 0);
    } else {
        for (const auto& m : chess_.legalMovesFor(selectedRow_, selectedCol_))
            result.emplace_back(m.toRow, m.toCol, chess_.isCapture(m) ? 1 : 0);
    }
    return result;
}

bool GameController::isMovable(int row, int col) const {
    for (auto& sq : movableSquares())
        if (sq.first == row && sq.second == col) return true;
    return false;
}

bool GameController::applyNotation(const std::string& notation) {
    lastError_.clear();
    try {
        if (mode_ == Mode::Checkers) {
            for (auto& step : checkers_.applyNotation(notation)) moveHistory_.push_back(step.toString());
        } else {
            ChessMove move = chess_.applyNotation(notation);
            moveHistory_.push_back(move.toString());
        }
        clearSelection();
        pendingPromotion_.reset();
        fireChange();
        return true;
    } catch (const std::invalid_argument& e) {
        lastError_ = e.what();
        return false;
    }
}

bool GameController::applyRemoteMove(const std::string& notation) {
    lastError_.clear();
    try {
        if (mode_ == Mode::Checkers) {
            CheckersMove step = checkers_.parseSingleStep(notation);
            checkers_.applyMove(step);
            moveHistory_.push_back(step.toString());
        } else {
            ChessMove move = chess_.applyNotation(notation);
            moveHistory_.push_back(move.toString());
        }
        if (mode_ != Mode::Checkers || !checkers_.isChainActive()) {
            clearSelection();
        } else {
            selectedRow_ = checkers_.chainRow();
            selectedCol_ = checkers_.chainCol();
        }
        pendingPromotion_.reset();
        fireChange();
        return true;
    } catch (const std::invalid_argument& e) {
        lastError_ = e.what();
        return false;
    }
}

GameController::ClickResult GameController::click(int row, int col) {
    lastError_.clear();
    ClickResult r;
    if (isGameOver() || !isLocalTurn()) return r;
    if (hasPendingPromotion()) return r;
    // Destination click?
    if (hasSelection()) {
        if (mode_ == Mode::Checkers) {
            for (const auto& m : checkers_.legalMovesFor(selectedRow_, selectedCol_))
                if (m.toRow == row && m.toCol == col) return applyCheckersStep(m);
        } else {
            std::vector<ChessMove> targets;
            for (const auto& m : chess_.legalMovesFor(selectedRow_, selectedCol_))
                if (m.toRow == row && m.toCol == col) targets.push_back(m);
            if (!targets.empty()) {
                if (targets[0].promotion != ChessPiece::Empty) {
                    pendingPromotion_ = targets[0];
                    r.outcome = ClickOutcome::NeedPromotion;
                    r.wasCapture = chess_.isCapture(targets[0]);
                    return r;
                }
                return applyChessMove(targets[0]);
            }
        }
        // Not a destination: fall through to (re)selection.
    }
    if (isMovable(row, col)) {
        selectedRow_ = row;
        selectedCol_ = col;
        audio::playSelect();
        fireChange();
        r.outcome = ClickOutcome::Selected;
        return r;
    }
    if (hasSelection()) {
        clearSelection();
        fireChange();
        r.outcome = ClickOutcome::Cleared;
        return r;
    }
    return r;
}

GameController::ClickResult GameController::applyCheckersStep(const CheckersMove& step) {
    bool wasJump = step.isJump();
    checkers_.applyMove(step);
    moveHistory_.push_back(step.toString());
    if (checkers_.isChainActive()) {
        selectedRow_ = step.toRow;
        selectedCol_ = step.toCol;
    } else {
        clearSelection();
    }
    fireChange();
    ClickResult r;
    r.outcome = ClickOutcome::Moved;
    r.wasCapture = wasJump;
    r.notation = step.toString();
    return r;
}

GameController::ClickResult GameController::applyChessMove(const ChessMove& move) {
    bool wasCapture = chess_.isCapture(move);
    chess_.applyMove(move);
    moveHistory_.push_back(move.toString());
    bool givesCheck = chess_.inCheck();
    clearSelection();
    fireChange();
    ClickResult r;
    r.outcome = ClickOutcome::Moved;
    r.wasCapture = wasCapture;
    r.givesCheck = givesCheck;
    r.notation = move.toString();
    return r;
}

GameController::ClickResult GameController::applyPromotion(int promotionType) {
    ClickResult r;
    if (!pendingPromotion_.has_value()) {
        r.outcome = ClickOutcome::Illegal;
        r.error = "No promotion is pending";
        return r;
    }
    ChessMove chosen(0, 0, 0, 0);
    bool found = false;
    for (const auto& m :
         chess_.legalMovesFor(pendingPromotion_->fromRow, pendingPromotion_->fromCol))
        if (m.toRow == pendingPromotion_->toRow && m.toCol == pendingPromotion_->toCol &&
            m.promotion == promotionType) {
            chosen = m;
            found = true;
            break;
        }
    pendingPromotion_.reset();
    if (!found) {
        r.outcome = ClickOutcome::Illegal;
        r.error = "That promotion is not legal here";
        return r;
    }
    return applyChessMove(chosen);
}

void GameController::cancelPromotion() {
    pendingPromotion_.reset();
    clearSelection();
    fireChange();
}

std::string GameController::statusText() const {
    if (isGameOver()) return outcomeText();
    std::string sb = std::string(sideName(sideToMove())) + " to move";
    if (mode_ == Mode::Chess && chess_.inCheck()) sb += " - check";
    if (mode_ == Mode::Checkers && checkers_.isChainActive()) sb += " - complete the multi-jump";
    if (localSide_.has_value() && !isLocalTurn()) sb += " (waiting for opponent)";
    return sb;
}

}  // namespace engine
