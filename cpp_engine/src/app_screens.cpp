// App part 2: menu, settings and multiplayer-setup screens.
#include "app.hpp"

#include <algorithm>
#include <cmath>
#include <sstream>

#include "audio.hpp"

namespace app {

static std::string pct(double v) {
    return std::to_string(static_cast<int>(v * 100 + 0.5)) + "%";
}

void App::draw() {
    switch (screen_) {
        case Screen::Menu: drawMenu(); break;
        case Screen::Settings: drawSettings(); break;
        case Screen::NetSetup: drawNetSetup(); break;
        case Screen::Game: drawGame(); break;
    }
}

void App::drawMenu() {
    display_.fill(ui::kBg);
    int cx = winW_ / 2;
    ui::centerLabel(display_, "CHECKERS & CHESS", cx, 70, ui::kText, 5);
    ui::centerLabel(display_, "CPU ray-traced 3D suite", cx, 130, ui::kDim, 2);
    int bw = 340, bh = 44, x = cx - bw / 2, y = 200;
    const int gap = 14;
    if (ui::button(display_, uiCtx_, x, y, bw, bh, "Play Checkers")) {
        controller_.newGame(engine::GameController::Mode::Checkers);
        screen_ = Screen::Game;
    }
    y += bh + gap;
    if (ui::button(display_, uiCtx_, x, y, bw, bh, "Play Chess")) {
        controller_.newGame(engine::GameController::Mode::Chess);
        screen_ = Screen::Game;
    }
    y += bh + gap;
    if (ui::button(display_, uiCtx_, x, y, bw, bh,
                   std::string("View: ") +
                       (view_ == ViewMode::Board2D ? "2D board" : "3D ray-traced"))) {
        view_ = (view_ == ViewMode::Board2D) ? ViewMode::RayTraced3D : ViewMode::Board2D;
    }
    y += bh + gap;
    if (ui::button(display_, uiCtx_, x, y, bw, bh, "Settings")) {
        settingsReturn_ = Screen::Menu;
        screen_ = Screen::Settings;
    }
    y += bh + gap;
    if (ui::button(display_, uiCtx_, x, y, bw, bh, "Multiplayer (LAN)")) {
        screen_ = Screen::NetSetup;
    }
    y += bh + gap;
    if (ui::button(display_, uiCtx_, x, y, bw, bh, "Quit")) requestQuit();
    ui::centerLabel(display_, "2/3: switch view in game   N: new game   drag: orbit / light",
                    cx, winH_ - 40, ui::kDim, 2);
}

void App::drawSettings() {
    display_.fill(ui::kBg);
    ui::centerLabel(display_, "GRAPHICS SETTINGS", winW_ / 2, 30, ui::kText, 3);
    ui::centerLabel(display_, "Lower these on weak hardware to raise the framerate", winW_ / 2,
                    70, ui::kDim, 2);
    int x = winW_ / 2 - 300, w = 600, y = 120, h = 34;
    const int gap = 12;
    int delta = 0;

    // Preset cycler.
    if (ui::stepper(display_, uiCtx_, x, y, w, h, "Preset", gfx_.presetName, delta)) {
        static const char* order[] = {"Fast", "Balanced", "High", "Ultra"};
        int idx = 1;
        for (int i = 0; i < 4; i++)
            if (gfx_.presetName == order[i]) idx = i;
        idx = (idx + delta + 4) % 4;
        if (idx == 0) gfx_ = rt::GraphicsSettings::fast();
        else if (idx == 1) gfx_ = rt::GraphicsSettings::balanced();
        else if (idx == 2) gfx_ = rt::GraphicsSettings::high();
        else gfx_ = rt::GraphicsSettings::ultra();
    }
    y += h + gap;

    static const double scales[] = {0.25, 0.5, 0.75, 1.0};
    if (ui::stepper(display_, uiCtx_, x, y, w, h, "Resolution scale", pct(gfx_.resolutionScale),
                    delta)) {
        int idx = 2;
        for (int i = 0; i < 4; i++)
            if (std::abs(gfx_.resolutionScale - scales[i]) < 1e-9) idx = i;
        idx = std::max(0, std::min(3, idx + delta));
        gfx_.resolutionScale = scales[idx];
        gfx_.presetName = "Custom";
    }
    y += h + gap;

    if (ui::stepper(display_, uiCtx_, x, y, w, h, "Ray bounces",
                    std::to_string(gfx_.maxBounces), delta)) {
        gfx_.maxBounces = std::max(0, std::min(5, gfx_.maxBounces + delta));
        gfx_.presetName = "Custom";
    }
    y += h + gap;

    {
        bool soft = gfx_.shadowQuality == rt::ShadowQuality::Soft;
        bool before = soft;
        if (ui::toggle(display_, uiCtx_, x, y, w, h, "Soft shadows", soft)) {
            gfx_.shadowQuality =
                soft ? rt::ShadowQuality::Soft : rt::ShadowQuality::Hard;
            gfx_.presetName = "Custom";
        }
        (void)before;
    }
    y += h + gap;

    static const int shadowSteps[] = {1, 4, 8, 16, 24};
    {
        bool hard = gfx_.shadowQuality == rt::ShadowQuality::Hard;
        std::string val = hard ? "1 (hard)" : std::to_string(gfx_.softShadowSamples);
        int d2 = 0;
        if (ui::stepper(display_, uiCtx_, x, y, w, h, "Shadow samples", val, d2, 2) && !hard) {
            int idx = 2;
            for (int i = 0; i < 5; i++)
                if (gfx_.softShadowSamples == shadowSteps[i]) idx = i;
            idx = std::max(0, std::min(4, idx + d2));
            gfx_.softShadowSamples = shadowSteps[idx];
            gfx_.presetName = "Custom";
        }
    }
    y += h + gap;

    static const int aaSteps[] = {1, 4, 9};
    {
        std::string v = gfx_.samplesPerPixel == 1 ? "1 (off)" : std::to_string(gfx_.samplesPerPixel);
        int d2 = 0;
        if (ui::stepper(display_, uiCtx_, x, y, w, h, "AA samples/px", v, d2)) {
            int idx = 1;
            for (int i = 0; i < 3; i++)
                if (gfx_.samplesPerPixel == aaSteps[i]) idx = i;
            idx = std::max(0, std::min(2, idx + d2));
            gfx_.samplesPerPixel = aaSteps[idx];
            gfx_.presetName = "Custom";
        }
    }
    y += h + gap;

    {
        bool sound = engine::audio::isEnabled();
        if (ui::toggle(display_, uiCtx_, x, y, w, h, "Sound", sound))
            engine::audio::setEnabled(sound);
    }
    y += h + gap;

    int d3 = 0;
    static int clockMin = 10;
    if (ui::stepper(display_, uiCtx_, x, y, w, h, "Clock (min)",
                    std::to_string(clockMin), d3)) {
        clockMin = std::max(1, std::min(60, clockMin + d3));
        controller_.setClockMinutes(clockMin);
    }
    y += h + gap;

    gfx_.clamp();
    int iw, ih;
    gfx_.internalSize(winW_, imageH(), iw, ih);
    std::ostringstream info;
    info << "Internal: " << iw << "x" << ih << " -> " << winW_ << "x" << imageH()
         << "   FPS: " << static_cast<int>(fpsEma_ + 0.5);
    ui::centerLabel(display_, info.str(), winW_ / 2, y, ui::kGold, 2);
    y += 30;

    int bw = 200, bh = 40;
    if (ui::button(display_, uiCtx_, winW_ / 2 - bw - 10, y, bw, bh, "Save")) {
        gfx_.save("config.ini");
        setNotice("Settings saved to config.ini");
        screen_ = settingsReturn_;
    }
    if (ui::button(display_, uiCtx_, winW_ / 2 + 10, y, bw, bh, "Back"))
        screen_ = settingsReturn_;
}

void App::drawNetSetup() {
    display_.fill(ui::kBg);
    ui::centerLabel(display_, "MULTIPLAYER (LAN)", winW_ / 2, 30, ui::kText, 3);
    ui::centerLabel(display_, "Direct TCP peer-to-peer: host takes the first-moving side "
                              "(Black in checkers, White in chess).",
                    winW_ / 2, 70, ui::kDim, 2);
    int x = winW_ / 2 - 260, w = 520, y = 130, h = 36;
    const int gap = 12;
    int delta = 0;
    std::string modeStr =
        controller_.mode() == engine::GameController::Mode::Checkers ? "Checkers" : "Chess";
    if (ui::stepper(display_, uiCtx_, x, y, w, h, "Game", modeStr, delta)) {
        controller_.newGame(controller_.mode() == engine::GameController::Mode::Checkers
                                ? engine::GameController::Mode::Chess
                                : engine::GameController::Mode::Checkers);
    }
    y += h + gap;
    ui::label(display_, "Host / address", x, y, ui::kText, 2);
    ui::textField(display_, uiCtx_, x + 200, y, 320, h, hostField_, "127.0.0.1");
    y += h + gap;
    ui::label(display_, "Port", x, y, ui::kText, 2);
    ui::textField(display_, uiCtx_, x + 200, y, 320, h, portField_, "9876");
    y += h + gap;
    int bw = 250, bh = 44;
    if (ui::button(display_, uiCtx_, x, y, bw, bh, "Host game")) startHost();
    if (ui::button(display_, uiCtx_, x + w - bw, y, bw, bh, "Join game")) startJoin();
    y += bh + gap;
    ui::centerLabel(display_, netStatus_, winW_ / 2, y, ui::kGold, 2);
    y += 30;
    if (ui::button(display_, uiCtx_, winW_ / 2 - 100, y, 200, bh, "Back"))
        screen_ = Screen::Menu;
}

}  // namespace app
