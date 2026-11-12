// App part 3: game screen (2D board + ray-traced 3D + overlays),
// board picking, move application, timers and sounds.
#include "app.hpp"

#include <algorithm>
#include <cmath>
#include <sstream>
#include <string>
#include <tuple>

#include "audio.hpp"

namespace app {

static const int kTopBar = 64;
static const int kBottomBar = 72;

std::string App::clockText(long ms) const {
    if (ms < 0) ms = 0;
    long s = (ms + 999) / 1000;
    std::ostringstream out;
    out << (s / 60) << ':';
    long rem = s % 60;
    if (rem < 10) out << '0';
    out << rem;
    return out.str();
}

void App::sendLocalMove(const std::string& notation) {
    if (peer_ && peer_->isConnected() && !notation.empty()) peer_->sendMove(notation);
}

void App::playResultSounds(const engine::GameController::ClickResult& r) {
    if (r.outcome != engine::GameController::ClickOutcome::Moved) return;
    if (r.wasCapture) engine::audio::playCapture();
    else engine::audio::playMove();
    if (r.givesCheck) engine::audio::playCheck();
    if (controller_.isGameOver()) {
        if (gameWinner().has_value()) engine::audio::playWin();
        else engine::audio::playDraw();
    }
}

void App::applyLocalResult(const engine::GameController::ClickResult& r) {
    using O = engine::GameController::ClickOutcome;
    switch (r.outcome) {
        case O::Moved:
            playResultSounds(r);
            sendLocalMove(r.notation);
            break;
        case O::NeedPromotion:
            break;  // dialog handles the rest
        case O::Illegal:
            engine::audio::playIllegal();
            setNotice(r.error);
            break;
        case O::Ignored:
            if (controller_.localSide().has_value() && !controller_.isLocalTurn())
                setNotice("Waiting for opponent...");
            break;
        default:
            break;
    }
}

// Board rectangle for the 2D view.
static void boardRect(int winW, int winH, int& ox, int& oy, int& sq) {
    int availW = winW - 40;
    int availH = winH - kTopBar - kBottomBar - 16;
    sq = std::min(availW / 8, availH / 8);
    if (sq < 8) sq = 8;
    ox = (winW - sq * 8) / 2;
    oy = kTopBar + (availH - sq * 8) / 2;
}

bool App::boardSquareAt(int x, int y, int& row, int& col) const {
    int ox, oy, sq;
    boardRect(winW_, winH_, ox, oy, sq);
    int c = (x - ox) / sq, r = (y - oy) / sq;
    if (c < 0 || c > 7 || r < 0 || r > 7) return false;
    row = r;
    col = c;
    return true;
}

bool App::pickSquare3D(int x, int y, int& row, int& col) const {
    int imgH = imageH();
    int py = y - kTopBar;
    if (x < 0 || x >= winW_ || py < 0 || py >= imgH) return false;
    rt::Ray ray = camera_.pickRay(x, py, winW_, imgH);
    if (std::abs(ray.dir.y) < 1e-9) return false;
    double t = -ray.origin.y / ray.dir.y;
    if (t <= 0) return false;
    rt::Vec3 p = ray.at(t);
    int c = static_cast<int>(std::floor(p.x + 4.5));
    int r = static_cast<int>(std::floor(p.z + 4.5));
    if (c < 0 || c > 7 || r < 0 || r > 7) return false;
    // Reject hits far outside the clicked square (grazing rays).
    double cx = (c - 3.5), cz = (r - 3.5);
    if (std::abs(p.x - cx) > 0.75 || std::abs(p.z - cz) > 0.75) return false;
    row = r;
    col = c;
    return true;
}

void App::gameClickAt(int x, int y) {
    if (controller_.hasPendingPromotion()) return;  // dialog owns clicks
    int row, col;
    bool hit = (view_ == ViewMode::Board2D) ? boardSquareAt(x, y, row, col)
                                            : pickSquare3D(x, y, row, col);
    if (!hit) return;
    applyLocalResult(controller_.click(row, col));
}

static uint32_t pieceColorLight(bool white) {
    return white ? 0xFFDCD4C2u : 0xFF2E2622u;
}
static uint32_t pieceColorDark(bool white) {
    return white ? 0xFF8F8674u : 0xFF120E0Bu;
}

void App::drawBoard2D(int ox, int oy, int sq) {
    auto& ctl = controller_;
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) {
            uint32_t col = ((r + c) % 2 == 0) ? ui::kSquareLight : ui::kSquareDark;
            display_.fillRect(ox + c * sq, oy + r * sq, sq, sq, col);
        }
    display_.rect(ox - 2, oy - 2, sq * 8 + 4, sq * 8 + 4, ui::kPanelEdge, 2);
    // Movable pieces get green rings, the selected piece a gold ring.
    for (auto& s : ctl.movableSquares()) {
        int cx = ox + s.second * sq + sq / 2, cy = oy + s.first * sq + sq / 2;
        display_.circle(cx, cy, sq / 2 - 2, ui::kGood, false);
    }
    if (ctl.hasSelection()) {
        int cx = ox + ctl.selectedCol() * sq + sq / 2;
        int cy = oy + ctl.selectedRow() * sq + sq / 2;
        display_.circle(cx, cy, sq / 2 - 2, ui::kGold, false);
        display_.circle(cx, cy, sq / 2 - 4, ui::kGold, false);
        // Quiet destinations green dots, captures red rings.
        for (auto& d : ctl.selectedDestinations()) {
            int dx = ox + std::get<1>(d) * sq + sq / 2;
            int dy = oy + std::get<0>(d) * sq + sq / 2;
            if (std::get<2>(d))
                display_.circle(dx, dy, sq / 4, ui::kBad, false);
            else
                display_.circle(dx, dy, sq / 8, ui::kGood, true);
        }
    }
    // Pieces.
    if (ctl.mode() == engine::GameController::Mode::Checkers) {
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++) {
                int p = ctl.checkers().pieceAt(r, c);
                if (p == engine::CheckersEngine::Empty) continue;
                bool white = engine::CheckersEngine::sideOf(p) == engine::Side::White;
                int cx = ox + c * sq + sq / 2, cy = oy + r * sq + sq / 2;
                int rad = sq * 38 / 100;
                display_.circle(cx, cy, rad, pieceColorLight(white), true);
                display_.circle(cx, cy, rad, pieceColorDark(white), false);
                display_.circle(cx, cy, rad * 6 / 10, pieceColorDark(white), false);
                if (engine::CheckersEngine::isKing(p)) {
                    display_.circle(cx, cy, rad / 3, ui::kGold, true);
                    display_.circle(cx, cy, rad / 3, 0xFF8A6D1Fu, false);
                }
            }
    } else {
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++) {
                int p = ctl.chess().pieceAt(r, c);
                if (p == engine::ChessPiece::Empty) continue;
                bool white = engine::ChessPiece::sideOf(p) == engine::Side::White;
                int cx = ox + c * sq + sq / 2, cy = oy + r * sq + sq / 2;
                int rad = sq * 40 / 100;
                display_.circle(cx, cy, rad, pieceColorLight(white), true);
                display_.circle(cx, cy, rad, pieceColorDark(white), false);
                char letter = engine::ChessPiece::letter(engine::ChessPiece::typeOf(p));
                std::string s(1, letter);
                uint32_t fg = white ? 0xFF20242Cu : 0xFFF2E8D8u;
                int sc = sq >= 64 ? 3 : 2;
                rt::font::drawText(display_, s, cx - rt::font::textWidth(s, sc) / 2,
                                   cy - rt::font::textHeight(sc) / 2, fg, sc);
            }
    }
    // Coordinates.
    for (int i = 0; i < 8; i++) {
        std::string file(1, static_cast<char>('a' + i));
        rt::font::drawText(display_, file, ox + i * sq + 3, oy + 8 * sq + 4, ui::kDim, 1);
        std::string rank(1, static_cast<char>('8' - i));
        rt::font::drawText(display_, rank, ox - 12, oy + i * sq + 3, ui::kDim, 1);
    }
}

void App::drawRayTraced3D() {
    int imgH = imageH();
    bool have = renderHaveResult_.load();
    {
        std::lock_guard<std::mutex> lock(renderMutex_);
        if (have && rendered3D_.w == winW_ && rendered3D_.h == imgH && !rendered3D_.px.empty()) {
            // Blit cached frame at (0, topBar).
            for (int y = 0; y < imgH; y++) {
                size_t dst = static_cast<size_t>(kTopBar + y) * static_cast<size_t>(winW_);
                size_t src = static_cast<size_t>(y) * static_cast<size_t>(winW_);
                std::copy(rendered3D_.px.begin() + static_cast<ptrdiff_t>(src),
                          rendered3D_.px.begin() + static_cast<ptrdiff_t>(src + winW_),
                          display_.px.begin() + static_cast<ptrdiff_t>(dst));
            }
        } else {
            have = false;
        }
    }
    if (!have) {
        display_.fillRect(0, kTopBar, winW_, imgH, 0xFF0B0D12u);
        ui::centerLabel(display_, "Rendering...", winW_ / 2, kTopBar + imgH / 2, ui::kDim, 2);
    }
    // Move-assistance markers projected onto the image.
    auto& ctl = controller_;
    auto marker = [&](int r, int c, uint32_t color, bool filled, int rad) {
        auto pr = camera_.project(rt::squareCenter(r, c) + rt::Vec3(0, 0.25, 0), winW_, imgH);
        if (!pr) return;
        int sx = static_cast<int>(std::get<0>(*pr));
        int sy = static_cast<int>(std::get<1>(*pr)) + kTopBar;
        display_.circle(sx, sy, rad, color, filled);
    };
    for (auto& s : ctl.movableSquares()) marker(s.first, s.second, ui::kGood, false, 10);
    if (ctl.hasSelection()) {
        marker(ctl.selectedRow(), ctl.selectedCol(), ui::kGold, false, 12);
        marker(ctl.selectedRow(), ctl.selectedCol(), ui::kGold, false, 9);
        for (auto& d : ctl.selectedDestinations()) {
            if (std::get<2>(d))
                marker(std::get<0>(d), std::get<1>(d), ui::kBad, false, 12);
            else
                marker(std::get<0>(d), std::get<1>(d), ui::kGood, true, 5);
        }
    }
    // Hint while the high-quality pass resolves.
    if (idleSinceInteraction_ < 0.18)
        ui::label(display_, "preview", 10, kTopBar + imgH - 24, ui::kDim, 2);
}

void App::drawTopBar() {
    display_.fillRect(0, 0, winW_, kTopBar, ui::kPanel);
    ui::label(display_, controller_.statusText(), 10, 10, ui::kText, 2);
    // Per-player countdown timers.
    std::string timers = "W " + clockText(controller_.remainingMs(engine::Side::White)) +
                         "  B " + clockText(controller_.remainingMs(engine::Side::Black));
    uint32_t tcol = controller_.sideToMove() == engine::Side::White ? ui::kGold : ui::kText;
    ui::label(display_, timers, 10, 38, tcol, 2);

    int bx = winW_ - 10;
    auto btn = [&](const std::string& label, int w) {
        bx -= w;
        int x = bx;
        bx -= 8;
        return ui::button(display_, uiCtx_, x, 12, w, 40, label);
    };
    if (btn("Menu", 80)) screen_ = Screen::Menu;
    if (btn(view_ == ViewMode::Board2D ? "3D" : "2D", 60))
        view_ = (view_ == ViewMode::Board2D) ? ViewMode::RayTraced3D : ViewMode::Board2D;
    if (btn("New", 70)) {
        controller_.newGame(controller_.mode());
        if (peer_ && peer_->isConnected())
            peer_->sendNewGame(controller_.mode() == engine::GameController::Mode::Checkers
                                   ? "CHECKERS"
                                   : "CHESS");
    }
    if (btn("Resign", 90)) {
        auto local = controller_.localSide();
        engine::Side winner =
            local.value_or(engine::opponentOf(controller_.sideToMove()));
        controller_.resign(winner);
        if (peer_ && peer_->isConnected()) peer_->sendResign();
    }
    if (btn("Setup", 80)) {
        settingsReturn_ = Screen::Game;
        screen_ = Screen::Settings;
    }
}

void App::drawPromotionDialog() {
    int bw = 70, bh = 54, gap = 10;
    int total = 4 * bw + 3 * gap;
    int x = winW_ / 2 - total / 2, y = winH_ / 2 - 60;
    ui::panel(display_, x - 16, y - 44, total + 32, bh + 76);
    ui::centerLabel(display_, "Promote to:", winW_ / 2, y - 32, ui::kText, 2);
    const char* names[] = {"Queen", "Rook", "Bishop", "Knight"};
    const int types[] = {engine::ChessPiece::Queen, engine::ChessPiece::Rook,
                         engine::ChessPiece::Bishop, engine::ChessPiece::Knight};
    const char* keys[] = {"Q", "R", "B", "N"};
    for (int i = 0; i < 4; i++) {
        if (ui::button(display_, uiCtx_, x + i * (bw + gap), y, bw, bh, keys[i])) {
            applyLocalResult(controller_.applyPromotion(types[i]));
        }
        ui::centerLabel(display_, names[i], x + i * (bw + gap) + bw / 2, y + bh + 8, ui::kDim, 1);
    }
    (void)names;
}

void App::drawGame() {
    drawTopBar();
    if (view_ == ViewMode::Board2D) {
        int ox, oy, sq;
        boardRect(winW_, winH_, ox, oy, sq);
        display_.fillRect(0, kTopBar, winW_, winH_ - kTopBar - kBottomBar, ui::kBg);
        drawBoard2D(ox, oy, sq);
    } else {
        drawRayTraced3D();
    }
    // Bottom bar: notices, chat, move count, fps.
    int by = winH_ - kBottomBar;
    display_.fillRect(0, by, winW_, kBottomBar, ui::kPanel);
    std::string line;
    if (!lastChat_.empty() && noticeTime_ > 0)
        line = "Chat: " + lastChat_;
    else if (!notice_.empty() && noticeTime_ > 0)
        line = notice_;
    else if (peer_ && peer_->isConnected())
        line = netStatus_;
    else
        line = "Moves: " + std::to_string(controller_.moveHistory().size()) +
               "   FPS: " + std::to_string(static_cast<int>(fpsEma_ + 0.5));
    ui::label(display_, line.substr(0, 90), 10, by + 8, ui::kDim, 2);
    if (peer_ && peer_->isConnected()) {
        bool send =
            ui::textField(display_, uiCtx_, 10, by + 36, winW_ - 120, 30, chatField_, "Chat...");
        if (ui::button(display_, uiCtx_, winW_ - 100, by + 36, 90, 30, "Send") || send) {
            if (!chatField_.text.empty()) {
                peer_->sendChat(chatField_.text);
                chatField_.text.clear();
            }
        }
    } else {
        ui::label(display_, controller_.statusText(), 10, by + 36, ui::kText, 2);
    }
    // Dialogs on top (checked before board clicks below).
    if (controller_.hasPendingPromotion()) drawPromotionDialog();
    if (controller_.isGameOver()) {
        int pw = 460, ph = 150;
        int px = winW_ / 2 - pw / 2, py = winH_ / 2 - ph / 2;
        ui::panel(display_, px, py, pw, ph);
        ui::centerLabel(display_, controller_.outcomeText(), winW_ / 2, py + 24, ui::kGold, 2);
        if (ui::button(display_, uiCtx_, px + 30, py + ph - 60, 180, 40, "New game")) {
            controller_.newGame(controller_.mode());
            if (peer_ && peer_->isConnected())
                peer_->sendNewGame(controller_.mode() == engine::GameController::Mode::Checkers
                                       ? "CHECKERS"
                                       : "CHESS");
        }
        if (ui::button(display_, uiCtx_, px + pw - 210, py + ph - 60, 180, 40, "Menu"))
            screen_ = Screen::Menu;
    }
    // Board clicks last: only when no widget consumed the click.
    if (uiCtx_.clicked) {
        int cx = uiCtx_.clickX, cy = uiCtx_.clickY;
        uiCtx_.consumeClick();
        // Ignore clicks on the bars and dialogs; gameClickAt ignores the
        // rest geometrically anyway.
        if (cy >= kTopBar && cy < winH_ - kBottomBar) gameClickAt(cx, cy);
    }
}

}  // namespace app
