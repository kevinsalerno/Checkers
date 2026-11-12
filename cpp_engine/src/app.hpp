// Interactive application: menu, game views (2D + ray-traced 3D),
// graphics settings, per-player timers, audio triggers and network
// peer wiring. Depends only on the engine, the CPU ray tracer and the
// software UI overlay — never on SDL. The platform layer (platform_sdl)
// feeds input events and uploads frame() to a window.
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include "camera.hpp"
#include "framebuffer.hpp"
#include "game_controller.hpp"
#include "graphics_settings.hpp"
#include "net.hpp"
#include "renderer.hpp"
#include "scene_builder.hpp"
#include "ui_overlay.hpp"

namespace app {

enum class AppKey {
    Escape, Enter, Backspace,
    Up, Down, Left, Right,
    Key2, Key3, KeyN, KeyS, KeyM, KeyR, KeyH,
};

enum class Screen { Menu, Game, Settings, NetSetup };
enum class ViewMode { Board2D, RayTraced3D };

struct NetEvent {
    enum class Type { Connected, Move, NewGame, Resign, Chat, Disconnected } type;
    std::string payload;
};

class App {
public:
    App();
    ~App();

    App(const App&) = delete;
    App& operator=(const App&) = delete;

    // Platform input.
    void onMouseMove(int x, int y);
    void onMouseDown(int x, int y, int button);  // button 1=left, 3=right
    void onMouseUp(int x, int y, int button);
    void onWheel(int dy);
    void onKey(AppKey key, bool pressed);
    void onTextInput(const std::string& text);
    void setWindowSize(int w, int h);
    void requestQuit() { quit_ = true; }
    bool shouldQuit() const { return quit_; }

    // Starts a hosted/joined peer from the NetSetup screen state.
    void startHost();
    void startJoin();

    // Per-frame update (dt in seconds): clocks, net pump, render jobs.
    void update(double dtSeconds);
    // Current window-sized frame for the platform to blit.
    const rt::FrameBuffer& frame();

    // Headless/smoke-test hooks.
    engine::GameController& controller() { return controller_; }
    rt::GraphicsSettings& settings() { return gfx_; }
    Screen screen() const { return screen_; }
    // Screenshot/self-test hooks (synchronous, no window needed).
    void showScreen(Screen s);
    void showView(ViewMode v) { view_ = v; }
    // True when the cached 3D frame matches the current state/settings.
    bool is3DUpToDate();
    // Kicks renders until the 3D frame is current (or timeout). Returns
    // whether the frame is current now.
    bool waitFor3D(int timeoutMs);

private:
    // --- state -------------------------------------------------------------
    engine::GameController controller_;
    rt::GraphicsSettings gfx_;
    Screen screen_ = Screen::Menu;
    Screen settingsReturn_ = Screen::Menu;
    ViewMode view_ = ViewMode::Board2D;
    rt::Camera camera_ = rt::Camera::defaultView();
    double lightAz_ = 56.3, lightEl_ = 61.0, lightDist_ = 7.4;

    int winW_ = 960, winH_ = 640;
    rt::FrameBuffer display_;
    ui::Context uiCtx_;
    bool quit_ = false;

    // drag state
    bool leftDown_ = false;
    bool rightDown_ = false;
    bool dragging_ = false;
    int downX_ = 0, downY_ = 0;
    int lastX_ = 0, lastY_ = 0;
    double idleSinceInteraction_ = 1.0;  // seconds since camera/light drag

    // text fields
    ui::TextField hostField_;
    ui::TextField portField_;
    ui::TextField chatField_;

    // transient status + fps
    std::string notice_;
    double noticeTime_ = 0;
    double fpsEma_ = 60.0;

    // clocks label cache not needed; computed per frame.

    // --- networking ----------------------------------------------------------
    std::unique_ptr<engine::net::PeerConnection> peer_;
    std::mutex netMutex_;
    std::vector<NetEvent> netQueue_;
    bool iHosted_ = false;  // true if this instance hosted the session
    std::string netStatus_ = "Not connected";
    std::string lastChat_;
    void ensurePeer();
    void pumpNetQueue();
    static engine::Side firstMover(engine::GameController::Mode m);

    // --- rendering (background thread) ----------------------------------------
    // Main thread owns scene_ (rebuilt only while no job is in flight);
    // the worker reads it through the raw job pointer handed over under
    // renderMutex_. Cross-thread flags are atomics; job_/rendered3D_
    // are guarded by renderMutex_.
    rt::Renderer renderer_;
    std::unique_ptr<rt::BuiltScene> scene_;
    unsigned long sceneKey_ = 0;  // moveHistory size + light epoch (main thread only)
    std::atomic<unsigned long> renderedKey_{0};
    std::atomic<unsigned long> inflightKey_{0};
    std::atomic<int> renderGen_{0};
    rt::FrameBuffer rendered3D_;  // window-sized cache (under renderMutex_)
    std::atomic<bool> renderBusy_{false};
    std::atomic<bool> renderHaveResult_{false};
    bool workerStop_ = false;  // under renderMutex_
    std::thread worker_;
    std::mutex renderMutex_;
    std::condition_variable renderCv_;
    bool workPending_ = false;  // under renderMutex_
    struct RenderJob {
        int gen = 0;
        unsigned long key = 0;  // renderedKey_ value stored on success
        const rt::Scene* scene = nullptr;
        rt::Camera camera = rt::Camera::defaultView();
        int iw = 8, ih = 8;
        int dw = 8, dh = 8;  // window-sized upscale target (captured at kick)
        rt::RenderSettings settings = rt::kBalanced;
        bool preview = false;
    } job_;
    std::atomic<bool> cancelFlag_{false};
    static unsigned long renderKeyFor(unsigned long sceneKey, int iw, int ih,
                                      const rt::RenderSettings& rs, bool preview, int winW,
                                      int winH);
    bool computeWantKey(unsigned long& out, rt::RenderSettings& rs, int& iw, int& ih,
                        bool& preview);
    void workerLoop();
    void kickRenderIfNeeded();
    void rebuildSceneIfNeeded();
    unsigned long currentSceneKey() const;

    // --- per-screen draw/update -------------------------------------------------
    void draw();
    void drawMenu();
    void drawSettings();
    void drawNetSetup();
    void drawGame();
    void drawBoard2D(int ox, int oy, int sq);
    void drawRayTraced3D();
    void drawTopBar();
    void drawPromotionDialog();
    void gameClickAt(int x, int y);
    bool boardSquareAt(int x, int y, int& row, int& col) const;  // 2D mapping
    bool pickSquare3D(int x, int y, int& row, int& col) const;   // ray pick
    void applyLocalResult(const engine::GameController::ClickResult& r);
    void playResultSounds(const engine::GameController::ClickResult& r);
    void sendLocalMove(const std::string& notation);
    // Winner of the finished game, or nullopt for draw/in-progress.
    std::optional<engine::Side> gameWinner() const;
    int imageH() const;
    std::string clockText(long ms) const;
    void setNotice(const std::string& s) {
        notice_ = s;
        noticeTime_ = 4.0;
    }
};

}  // namespace app
