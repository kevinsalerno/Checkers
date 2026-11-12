// App part 1: lifecycle, input, clocks, networking, render worker.
#include "app.hpp"

#include <chrono>
#include <cmath>
#include <functional>
#include <sstream>
#include <thread>

#include "audio.hpp"

namespace app {

static std::string modeName(engine::GameController::Mode m) {
    return m == engine::GameController::Mode::Checkers ? "CHECKERS" : "CHESS";
}

App::App() {
    hostField_.text = "127.0.0.1";
    portField_.text = "9876";
    display_.resize(winW_, winH_);
    gfx_.load("config.ini");
    gfx_.clamp();
    worker_ = std::thread([this] { workerLoop(); });
    controller_.addChangeListener([] { /* scene keyed off history size */ });
}

App::~App() {
    {
        std::lock_guard<std::mutex> lock(renderMutex_);
        workerStop_ = true;
        cancelFlag_.store(true);
    }
    renderCv_.notify_all();
    if (worker_.joinable()) worker_.join();
    if (peer_) peer_->close();
    // NOTE: settings are only written on the in-game Save button, never
    // implicitly, so headless runs and tests never mutate config.ini.
}

void App::setWindowSize(int w, int h) {
    if (w < 320) w = 320;
    if (h < 240) h = 240;
    winW_ = w;
    winH_ = h;
    display_.resize(w, h);
    rendered3D_ = rt::FrameBuffer();
    renderedKey_ = 0;  // force re-render at the new size
}

void App::onMouseMove(int x, int y) {
    uiCtx_.mouseX = x;
    uiCtx_.mouseY = y;
    if (leftDown_ || rightDown_) {
        int dx = x - lastX_, dy = y - lastY_;
        if (!dragging_ &&
            (std::abs(x - downX_) + std::abs(y - downY_) > 4))
            dragging_ = true;
        if (dragging_) {
            idleSinceInteraction_ = 0;
            if (view_ == ViewMode::RayTraced3D) {
                if (leftDown_ && screen_ == Screen::Game) {
                    camera_.orbit(dx * 0.35, -dy * 0.35);
                }
                if (rightDown_) {
                    lightAz_ += dx * 0.5;
                    lightEl_ = std::max(15.0, std::min(85.0, lightEl_ - dy * 0.4));
                }
            }
        }
    }
    lastX_ = x;
    lastY_ = y;
}

void App::onMouseDown(int x, int y, int button) {
    onMouseMove(x, y);
    if (button == 1) {
        leftDown_ = true;
        uiCtx_.leftHeld = true;
        dragging_ = false;
        downX_ = x;
        downY_ = y;
    } else if (button == 3) {
        rightDown_ = true;
        dragging_ = false;
        downX_ = x;
        downY_ = y;
    }
}

void App::onMouseUp(int x, int y, int button) {
    onMouseMove(x, y);
    if (button == 1) {
        leftDown_ = false;
        uiCtx_.leftHeld = false;
        if (!dragging_) {
            uiCtx_.clicked = true;
            uiCtx_.clickX = x;
            uiCtx_.clickY = y;
        }
        dragging_ = false;
    } else if (button == 3) {
        rightDown_ = false;
        dragging_ = false;
    }
}

void App::onWheel(int dy) {
    if (screen_ == Screen::Game && view_ == ViewMode::RayTraced3D) {
        camera_.zoom(dy < 0 ? 1.1 : 1.0 / 1.1);
        idleSinceInteraction_ = 0;
    }
}

void App::onKey(AppKey key, bool pressed) {
    if (!pressed) return;
    auto feed = [&](ui::TextField& f) {
        if (key == AppKey::Backspace) f.backspace = true;
        if (key == AppKey::Enter) f.enterPressed = true;
    };
    feed(hostField_);
    feed(portField_);
    feed(chatField_);
    if (key == AppKey::Escape) {
        if (controller_.hasPendingPromotion()) {
            controller_.cancelPromotion();
        } else if (screen_ == Screen::Game) {
            screen_ = Screen::Menu;
        } else if (screen_ == Screen::Settings) {
            screen_ = settingsReturn_;
        } else if (screen_ == Screen::NetSetup) {
            screen_ = Screen::Menu;
        }
    } else if (key == AppKey::Key2 && screen_ == Screen::Game) {
        view_ = ViewMode::Board2D;
    } else if (key == AppKey::Key3 && screen_ == Screen::Game) {
        view_ = ViewMode::RayTraced3D;
    } else if (key == AppKey::KeyN && screen_ == Screen::Game) {
        controller_.newGame(controller_.mode());
        if (peer_) peer_->sendNewGame(modeName(controller_.mode()));
        renderedKey_ = 0;
    }
}

void App::onTextInput(const std::string& text) {
    if (hostField_.active) hostField_.pendingText += text;
    if (portField_.active) portField_.pendingText += text;
    if (chatField_.active) chatField_.pendingText += text;
}

// --- networking ---------------------------------------------------------------

engine::Side App::firstMover(engine::GameController::Mode m) {
    return m == engine::GameController::Mode::Checkers ? engine::Side::Black
                                                       : engine::Side::White;
}

void App::ensurePeer() {
    if (peer_) return;
    engine::net::Listener l;
    l.onConnected = [this](const std::string& remote) {
        std::lock_guard<std::mutex> lock(netMutex_);
        netQueue_.push_back({NetEvent::Type::Connected, remote});
    };
    l.onMove = [this](const std::string& n) {
        std::lock_guard<std::mutex> lock(netMutex_);
        netQueue_.push_back({NetEvent::Type::Move, n});
    };
    l.onNewGame = [this](const std::string& m) {
        std::lock_guard<std::mutex> lock(netMutex_);
        netQueue_.push_back({NetEvent::Type::NewGame, m});
    };
    l.onResign = [this] {
        std::lock_guard<std::mutex> lock(netMutex_);
        netQueue_.push_back({NetEvent::Type::Resign, ""});
    };
    l.onChat = [this](const std::string& t) {
        std::lock_guard<std::mutex> lock(netMutex_);
        netQueue_.push_back({NetEvent::Type::Chat, t});
    };
    l.onDisconnected = [this](const std::string& reason) {
        std::lock_guard<std::mutex> lock(netMutex_);
        netQueue_.push_back({NetEvent::Type::Disconnected, reason});
    };
    peer_ = std::make_unique<engine::net::PeerConnection>(std::move(l));
}

void App::startHost() {
    ensurePeer();
    int port = 9876;
    try {
        port = std::stoi(portField_.text);
    } catch (...) {
    }
    iHosted_ = true;
    netStatus_ = "Hosting on port " + std::to_string(port) + "...";
    engine::Side me = firstMover(controller_.mode());
    controller_.setLocalSide(me, true);
    peer_->host(port);
    peer_->sendHello(modeName(controller_.mode()));
}

void App::startJoin() {
    ensurePeer();
    int port = 9876;
    try {
        port = std::stoi(portField_.text);
    } catch (...) {
    }
    iHosted_ = false;
    netStatus_ = "Connecting to " + hostField_.text + ":" + std::to_string(port) + "...";
    engine::Side me = engine::opponentOf(firstMover(controller_.mode()));
    controller_.setLocalSide(me, false);
    peer_->join(hostField_.text.empty() ? "127.0.0.1" : hostField_.text, port);
}

void App::pumpNetQueue() {
    std::vector<NetEvent> events;
    {
        std::lock_guard<std::mutex> lock(netMutex_);
        events.swap(netQueue_);
    }
    for (auto& e : events) {
        switch (e.type) {
            case NetEvent::Type::Connected: {
                netStatus_ = "Connected: " + e.payload;
                // Host takes the first-moving side; the guest takes the other.
                engine::Side me = iHosted_
                                      ? firstMover(controller_.mode())
                                      : engine::opponentOf(firstMover(controller_.mode()));
                controller_.setLocalSide(me, iHosted_);
                if (iHosted_) peer_->sendHello(modeName(controller_.mode()));
                screen_ = Screen::Game;
                break;
            }
            case NetEvent::Type::Move: {
                if (controller_.applyRemoteMove(e.payload)) {
                    engine::audio::playMove();
                    if (controller_.isGameOver()) engine::audio::playWin();
                    renderedKey_.store(0);
                } else {
                    setNotice("Remote move rejected: " + controller_.lastError());
                }
                break;
            }
            case NetEvent::Type::NewGame: {
                auto m = (e.payload == "CHESS" || e.payload == "chess")
                             ? engine::GameController::Mode::Chess
                             : engine::GameController::Mode::Checkers;
                controller_.newGame(m);
                engine::Side me =
                    iHosted_ ? firstMover(m) : engine::opponentOf(firstMover(m));
                controller_.setLocalSide(me, iHosted_);
                renderedKey_ = 0;
                setNotice("Opponent started a new game");
                break;
            }
            case NetEvent::Type::Resign: {
                auto local = controller_.localSide();
                engine::Side winner =
                    local.value_or(engine::opponentOf(controller_.sideToMove()));
                controller_.resign(winner);
                engine::audio::playWin();
                break;
            }
            case NetEvent::Type::Chat: {
                lastChat_ = e.payload;
                noticeTime_ = 6.0;
                break;
            }
            case NetEvent::Type::Disconnected: {
                netStatus_ = "Disconnected: " + e.payload;
                controller_.clearLocalSide();
                break;
            }
        }
    }
}

// --- per-frame update -----------------------------------------------------------

void App::update(double dtSeconds) {
    if (dtSeconds > 0) {
        double fps = 1.0 / (dtSeconds + 1e-9);
        fpsEma_ += (fps - fpsEma_) * 0.05;
    }
    idleSinceInteraction_ += dtSeconds;
    if (noticeTime_ > 0) noticeTime_ -= dtSeconds;

    // Per-player countdown.
    if (screen_ == Screen::Game)
        controller_.tick(static_cast<long>(dtSeconds * 1000));

    pumpNetQueue();
    // NOTE: uiCtx_.clicked is set by onMouseUp and consumed by widgets in
    // draw() (via frame()); it must survive update() untouched.

    rebuildSceneIfNeeded();
    kickRenderIfNeeded();
}

// --- background render worker -----------------------------------------------------

unsigned long App::currentSceneKey() const {
    unsigned long moves = controller_.moveHistory().size();
    unsigned long modeBit =
        controller_.mode() == engine::GameController::Mode::Chess ? 1000000u : 0u;
    unsigned long lightKey = static_cast<unsigned long>(lightAz_ * 10) * 100000003u +
                             static_cast<unsigned long>(lightEl_ * 10) * 100003u +
                             static_cast<unsigned long>(lightDist_ * 10);
    return moves * 7u + modeBit + lightKey +
           (controller_.isGameOver() ? 5000000u : 0u);
}

std::optional<engine::Side> App::gameWinner() const {
    if (!controller_.isGameOver()) return std::nullopt;
    const std::string text = controller_.outcomeText();
    if (text.rfind("White wins", 0) == 0) return engine::Side::White;
    if (text.rfind("Black wins", 0) == 0) return engine::Side::Black;
    return std::nullopt;  // draw or timeout wording handled by caller
}

unsigned long App::renderKeyFor(unsigned long sceneKey, int iw, int ih,
                                const rt::RenderSettings& rs, bool preview, int winW, int winH) {
    unsigned long k = sceneKey * 1315423911u + static_cast<unsigned long>(iw) * 131u +
                      static_cast<unsigned long>(ih) * 17u +
                      static_cast<unsigned long>(rs.aaAxis) +
                      static_cast<unsigned long>(rs.shadowSamples) * 4u +
                      static_cast<unsigned long>(rs.maxDepth) * 64u +
                      static_cast<unsigned long>(winW) * 1000003u +
                      static_cast<unsigned long>(winH) * 1009u;
    if (preview) k += 1000000007u;
    return k;
}

void App::rebuildSceneIfNeeded() {
    unsigned long key = currentSceneKey();
    if (scene_ && key == sceneKey_) return;
    // The worker may be reading scene_; defer the rebuild until it idles.
    if (renderBusy_.load()) return;
    rt::AreaLight light = rt::defaultLight().moved(lightAz_, lightEl_, lightDist_);
    if (controller_.mode() == engine::GameController::Mode::Checkers)
        scene_ = std::make_unique<rt::BuiltScene>(
            rt::buildCheckersScene(controller_.checkers(), light));
    else
        scene_ =
            std::make_unique<rt::BuiltScene>(rt::buildChessScene(controller_.chess(), light));
    sceneKey_ = key;
}

int App::imageH() const { return winH_ - 64 - 72 > 8 ? winH_ - 64 - 72 : 8; }

bool App::computeWantKey(unsigned long& out, rt::RenderSettings& rs, int& iw, int& ih,
                        bool& preview) {
    if (screen_ != Screen::Game || view_ != ViewMode::RayTraced3D || !scene_) return false;
    gfx_.internalSize(winW_, imageH(), iw, ih);
    bool interacting = idleSinceInteraction_ < 0.18;
    rs = gfx_.toRenderSettings();
    if (interacting) {
        // Cheap preview while the camera or light is dragged.
        rs.aaAxis = 1;
        rs.maxDepth = std::min(rs.maxDepth, 1);
        rs.shadowSamples = (gfx_.shadowQuality == rt::ShadowQuality::Hard)
                               ? 1
                               : std::min(rs.shadowSamples, 4);
        iw = std::min(iw, winW_ / 2);
        ih = std::min(ih, imageH() / 2);
    }
    preview = interacting;
    out = renderKeyFor(sceneKey_, iw, ih, rs, interacting, winW_, winH_);
    return true;
}

bool App::is3DUpToDate() {
    unsigned long want;
    rt::RenderSettings rs;
    int iw, ih;
    bool preview;
    if (!computeWantKey(want, rs, iw, ih, preview)) return false;
    return want == renderedKey_.load();
}

bool App::waitFor3D(int timeoutMs) {
    auto start = std::chrono::steady_clock::now();
    for (;;) {
        update(0.016);
        if (is3DUpToDate()) return true;
        auto elapsed = std::chrono::steady_clock::now() - start;
        if (std::chrono::duration_cast<std::chrono::milliseconds>(elapsed).count() > timeoutMs)
            return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}

void App::showScreen(Screen s) {
    screen_ = s;
    if (s == Screen::Game) {
        settingsReturn_ = Screen::Menu;
        rebuildSceneIfNeeded();
    }
    if (s == Screen::Settings) settingsReturn_ = Screen::Menu;
}

void App::kickRenderIfNeeded() {
    unsigned long wantKey;
    rt::RenderSettings rs;
    int iw, ih;
    bool interacting;
    if (!computeWantKey(wantKey, rs, iw, ih, interacting)) return;
    if (wantKey == renderedKey_.load() || wantKey == inflightKey_.load()) return;
    {
        std::lock_guard<std::mutex> lock(renderMutex_);
        if (renderBusy_.load()) {
            cancelFlag_.store(true);
            return;  // retry next frame once the worker idles
        }
        renderGen_.fetch_add(1);
        job_.gen = renderGen_.load();
        job_.key = wantKey;
        job_.scene = &scene_->scene;
        job_.camera = camera_;
        job_.iw = iw;
        job_.ih = ih;
        job_.dw = winW_;
        job_.dh = imageH();
        job_.settings = rs;
        job_.preview = interacting;
        cancelFlag_.store(false);
        workPending_ = true;
        renderBusy_.store(true);
        inflightKey_.store(wantKey);
    }
    renderCv_.notify_one();
}

void App::workerLoop() {
    for (;;) {
        RenderJob job;
        {
            std::unique_lock<std::mutex> lock(renderMutex_);
            renderCv_.wait(lock, [this] { return workPending_ || workerStop_; });
            if (workerStop_) return;
            workPending_ = false;
            job = job_;  // includes the scene pointer; the main thread
                         // never replaces scene_ while renderBusy_ is set.
        }
        rt::PixelBuffer internal;
        bool ok = false;
        if (job.scene && job.iw > 0 && job.ih > 0) {
            internal.assign(static_cast<size_t>(job.iw) * static_cast<size_t>(job.ih),
                            0xFF000000u);
            std::function<bool()> pred = [this] { return cancelFlag_.load(); };
            renderer_.renderInto(*job.scene, job.camera, job.iw, job.ih, job.settings,
                                 internal, pred);
            ok = !cancelFlag_.load();
        }
        {
            std::lock_guard<std::mutex> lock(renderMutex_);
            if (ok && job.gen == renderGen_.load() && !cancelFlag_.load()) {
                rendered3D_ = rt::FrameBuffer::upscaleBilinear(internal, job.iw, job.ih, job.dw,
                                                              job.dh);
                renderHaveResult_.store(true);
                renderedKey_.store(job.key);
            }
            renderBusy_.store(false);
            inflightKey_.store(0);
        }
    }
}

const rt::FrameBuffer& App::frame() {
    draw();
    return display_;
}

}  // namespace app
