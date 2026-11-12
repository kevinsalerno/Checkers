// Entry point.
//
//   ./checkers                        Launch the interactive UI (needs SDL2)
//   ./checkers --simulate <checkers|chess> <moves...>   Headless simulation
//   ./checkers --file <moves.txt>     Headless simulation from a file
//   ./checkers --render <out.ppm> [checkers|chess] [fast|balanced|high|ultra]
//                                     Render the start position headlessly
//
// The headless paths never create a window or touch the audio device.
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

#include "app.hpp"
#include "camera.hpp"
#include "checkers_engine.hpp"
#include "chess_engine.hpp"
#include "graphics_settings.hpp"
#include "headless.hpp"
#include "platform_gui.hpp"
#include "renderer.hpp"
#include "scene_builder.hpp"

namespace {

void printHelp() {
    std::cout << "Usage:\n"
                 "  checkers                               Launch the SDL2 UI\n"
                 "  checkers --simulate checkers|chess <moves...>   Headless simulation\n"
                 "  checkers --file moves.txt              Headless simulation from file\n"
                 "  checkers --render out.ppm [checkers|chess] [fast|balanced|high|ultra]\n"
                 "                                         Render a PPM headlessly\n"
                 "  checkers --shot <menu|settings|net|checkers2d|chess2d|checkers3d|chess3d>\n"
                 "      <out.ppm>                          Capture a UI screen headlessly\n"
                 "  checkers --smoke-gui [seconds]         Open the SDL2 window briefly\n";
}

bool writePpm(const std::string& path, const rt::PixelBuffer& px, int w, int h);
bool writePpm(const std::string& path, const rt::FrameBuffer& fb);

int runShot(const std::vector<std::string>& args) {
    // args: --shot <which> <out.ppm> (args[0] is "--shot")
    if (args.size() < 3) {
        std::cerr << "Usage: checkers --shot "
                     "<menu|settings|net|checkers2d|chess2d|checkers3d|chess3d> <out.ppm>\n";
        return 1;
    }
    const std::string& which = args[1];
    app::App app;
    app.setWindowSize(960, 640);
    bool need3D = false;
    if (which == "menu") {
        app.showScreen(app::Screen::Menu);
    } else if (which == "settings") {
        app.showScreen(app::Screen::Settings);
    } else if (which == "net") {
        app.showScreen(app::Screen::NetSetup);
    } else if (which == "checkers2d" || which == "chess2d") {
        app.controller().newGame(which == "chess2d" ? engine::GameController::Mode::Chess
                                                    : engine::GameController::Mode::Checkers);
        // Play a couple of opening moves so the board is not pristine.
        if (which == "chess2d") {
            app.controller().applyNotation("e4");
            app.controller().applyNotation("e5");
        } else {
            app.controller().applyNotation("b6-a5");
            app.controller().applyNotation("c3-b4");
        }
        app.showScreen(app::Screen::Game);
        app.showView(app::ViewMode::Board2D);
    } else if (which == "checkers3d" || which == "chess3d") {
        app.controller().newGame(which == "chess3d" ? engine::GameController::Mode::Chess
                                                    : engine::GameController::Mode::Checkers);
        if (which == "chess3d") {
            app.controller().applyNotation("e4");
            app.controller().applyNotation("e5");
        } else {
            app.controller().applyNotation("b6-a5");
            app.controller().applyNotation("c3-b4");
        }
        app.showScreen(app::Screen::Game);
        app.showView(app::ViewMode::RayTraced3D);
        app.settings() = rt::GraphicsSettings::high();
        need3D = true;
    } else {
        std::cerr << "Unknown shot: " << which << "\n";
        return 1;
    }
    app.update(0.016);
    app.update(0.016);
    if (need3D && !app.waitFor3D(180000)) {
        std::cerr << "Shot failed: 3D render did not finish in time\n";
        return 1;
    }
    app.update(0.0);
    if (!writePpm(args[2], app.frame())) {
        std::cerr << "Shot failed: cannot write " << args[2] << "\n";
        return 1;
    }
    std::cout << "Wrote " << args[2] << "\n";
    return 0;
}

bool writePpm(const std::string& path, const rt::PixelBuffer& px, int w, int h) {
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    out << "P6\n" << w << ' ' << h << "\n255\n";
    for (uint32_t p : px) {
        unsigned char rgb[3] = {static_cast<unsigned char>((p >> 16) & 0xFF),
                                static_cast<unsigned char>((p >> 8) & 0xFF),
                                static_cast<unsigned char>(p & 0xFF)};
        out.write(reinterpret_cast<char*>(rgb), 3);
    }
    return static_cast<bool>(out);
}

bool writePpm(const std::string& path, const rt::FrameBuffer& fb) {
    return writePpm(path, fb.px, fb.w, fb.h);
}

int runRender(const std::vector<std::string>& args) {
    // args: --render out.ppm [game] [preset]
    if (args.size() < 2) {
        std::cerr << "Usage: checkers --render out.ppm [checkers|chess] "
                     "[fast|balanced|high|ultra]\n";
        return 1;
    }
    std::string game = args.size() > 2 ? args[2] : "checkers";
    std::string preset = args.size() > 3 ? args[3] : "high";
    rt::GraphicsSettings gfx;
    if (preset == "fast") gfx = rt::GraphicsSettings::fast();
    else if (preset == "balanced") gfx = rt::GraphicsSettings::balanced();
    else if (preset == "high") gfx = rt::GraphicsSettings::high();
    else if (preset == "ultra") gfx = rt::GraphicsSettings::ultra();
    else {
        std::cerr << "Unknown preset: " << preset << "\n";
        return 1;
    }
    rt::BuiltScene scene = [&]() -> rt::BuiltScene {
        if (game == "chess") {
            engine::ChessEngine e;
            return rt::buildChessScene(e);
        }
        engine::CheckersEngine e;
        return rt::buildCheckersScene(e);
    }();
    rt::Renderer renderer;
    rt::PixelBuffer px =
        renderer.render(scene.scene, rt::Camera::defaultView(), 640, 480,
                        gfx.toRenderSettings());
    if (!writePpm(args[1], px, 640, 480)) {
        std::cerr << "Render failed: cannot write " << args[1] << "\n";
        return 1;
    }
    std::cout << "Wrote " << args[1] << "\n";
    return 0;
}

std::vector<std::string> splitTokens(const std::string& text) {
    std::vector<std::string> out;
    std::string cur;
    for (char ch : text) {
        if (ch == ',' || ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
            if (!cur.empty()) {
                out.push_back(cur);
                cur.clear();
            }
        } else {
            cur += ch;
        }
    }
    if (!cur.empty()) out.push_back(cur);
    return out;
}

}  // namespace

int main(int argc, char** argv) {
    std::vector<std::string> args;
    for (int i = 0; i < argc; i++) args.emplace_back(argv[i]);
    if (args.size() > 1) {
        if (args[1] == "--simulate") {
            if (args.size() < 3) {
                std::cerr << "Usage: checkers --simulate <checkers|chess> <moves...>\n";
                return 1;
            }
            std::vector<std::string> moves;
            for (size_t i = 3; i < args.size(); i++)
                for (auto& t : splitTokens(args[i])) moves.push_back(t);
            try {
                engine::SimResult r = engine::simulate(args[2], moves);
                std::cout << r.toString();
                return r.success ? 0 : 2;
            } catch (const std::exception& e) {
                std::cerr << "ERROR: " << e.what() << "\n";
                return 1;
            }
        }
        if (args[1] == "--file") {
            if (args.size() < 3) {
                std::cerr << "Usage: checkers --file <moves.txt>\n";
                return 1;
            }
            try {
                engine::SimResult r = engine::simulateFile(args[2]);
                std::cout << r.toString();
                return r.success ? 0 : 2;
            } catch (const std::exception& e) {
                std::cerr << "ERROR: " << e.what() << "\n";
                return 1;
            }
        }
        if (args[1] == "--render") {
            std::vector<std::string> rest(args.begin() + 1, args.end());
            return runRender(rest);
        }
        if (args[1] == "--shot") {
            std::vector<std::string> rest(args.begin() + 1, args.end());
            return runShot(rest);
        }
        if (args[1] == "--smoke-gui") {
            int seconds = 5;
            if (args.size() > 2) {
                try {
                    seconds = std::stoi(args[2]);
                } catch (...) {
                }
            }
            return runGuiSmoke(seconds);
        }
        if (args[1] == "--help" || args[1] == "-h") {
            printHelp();
            return 0;
        }
        std::cerr << "Unknown flag: " << args[1] << "\n";
        printHelp();
        return 1;
    }
    return runGui();
}
