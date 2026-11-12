// SDL2 platform backend: window creation, pixel-buffer blitting,
// input events and PCM audio output. The ray tracer itself never
// touches SDL — it renders into a CPU FrameBuffer that is uploaded
// here as a streaming texture.
//
// Built only when HAVE_SDL2 is defined (see Makefile).
#include "platform_gui.hpp"

#ifdef HAVE_SDL2

#include <SDL.h>

#include <chrono>
#include <iostream>

#include "app.hpp"
#include "audio.hpp"

namespace {

app::AppKey mapKey(SDL_Keycode k) {
    switch (k) {
        case SDLK_ESCAPE: return app::AppKey::Escape;
        case SDLK_RETURN:
        case SDLK_KP_ENTER: return app::AppKey::Enter;
        case SDLK_BACKSPACE: return app::AppKey::Backspace;
        case SDLK_UP: return app::AppKey::Up;
        case SDLK_DOWN: return app::AppKey::Down;
        case SDLK_LEFT: return app::AppKey::Left;
        case SDLK_RIGHT: return app::AppKey::Right;
        case SDLK_2: return app::AppKey::Key2;
        case SDLK_3: return app::AppKey::Key3;
        case SDLK_n: return app::AppKey::KeyN;
        case SDLK_s: return app::AppKey::KeyS;
        case SDLK_m: return app::AppKey::KeyM;
        case SDLK_r: return app::AppKey::KeyR;
        case SDLK_h: return app::AppKey::KeyH;
        default: return app::AppKey::Escape;  // unmapped; caller filters
    }
}

bool isMapped(SDL_Keycode k) {
    switch (k) {
        case SDLK_ESCAPE:
        case SDLK_RETURN:
        case SDLK_KP_ENTER:
        case SDLK_BACKSPACE:
        case SDLK_UP:
        case SDLK_DOWN:
        case SDLK_LEFT:
        case SDLK_RIGHT:
        case SDLK_2:
        case SDLK_3:
        case SDLK_n:
        case SDLK_s:
        case SDLK_m:
        case SDLK_r:
        case SDLK_h:
            return true;
        default:
            return false;
    }
}

}  // namespace

int runGui() {
    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO) != 0) {
        std::cerr << "SDL_Init failed: " << SDL_GetError() << "\n";
        return 1;
    }
    SDL_Window* window = SDL_CreateWindow(
        "Checkers & Chess - CPU Ray Tracer", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
        960, 640, SDL_WINDOW_RESIZABLE);
    if (!window) {
        std::cerr << "SDL_CreateWindow failed: " << SDL_GetError() << "\n";
        SDL_Quit();
        return 1;
    }
    SDL_Renderer* renderer =
        SDL_CreateRenderer(window, -1, SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
    if (!renderer) renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_SOFTWARE);
    if (!renderer) {
        std::cerr << "SDL_CreateRenderer failed: " << SDL_GetError() << "\n";
        SDL_DestroyWindow(window);
        SDL_Quit();
        return 1;
    }
    SDL_Texture* texture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_ARGB8888,
                                             SDL_TEXTUREACCESS_STREAMING, 960, 640);
    if (!texture) {
        std::cerr << "SDL_CreateTexture failed: " << SDL_GetError() << "\n";
        SDL_DestroyRenderer(renderer);
        SDL_DestroyWindow(window);
        SDL_Quit();
        return 1;
    }

    // Audio: 22050 Hz mono S16, matching the synthesised PCM.
    SDL_AudioSpec want{}, have{};
    want.freq = 22050;
    want.format = AUDIO_S16SYS;
    want.channels = 1;
    want.samples = 1024;
    SDL_AudioDeviceID audioDev = SDL_OpenAudioDevice(nullptr, 0, &want, &have, 0);
    if (audioDev > 0) {
        engine::audio::setSink([audioDev](const std::vector<int16_t>& samples) {
            SDL_QueueAudio(audioDev, samples.data(),
                           samples.size() * sizeof(int16_t));
        });
        SDL_PauseAudioDevice(audioDev, 0);
    }

    app::App app;
    SDL_StartTextInput();
    auto last = std::chrono::steady_clock::now();
    bool running = true;
    while (running && !app.shouldQuit()) {
        SDL_Event ev;
        while (SDL_PollEvent(&ev)) {
            switch (ev.type) {
                case SDL_QUIT:
                    running = false;
                    break;
                case SDL_WINDOWEVENT:
                    if (ev.window.event == SDL_WINDOWEVENT_RESIZED) {
                        int w = ev.window.data1, h = ev.window.data2;
                        app.setWindowSize(w, h);
                        SDL_DestroyTexture(texture);
                        texture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_ARGB8888,
                                                    SDL_TEXTUREACCESS_STREAMING, w, h);
                        if (!texture) running = false;
                    }
                    break;
                case SDL_MOUSEMOTION:
                    app.onMouseMove(ev.motion.x, ev.motion.y);
                    break;
                case SDL_MOUSEBUTTONDOWN:
                    if (ev.button.button == SDL_BUTTON_LEFT) app.onMouseDown(ev.button.x,
                                                                             ev.button.y, 1);
                    else if (ev.button.button == SDL_BUTTON_RIGHT)
                        app.onMouseDown(ev.button.x, ev.button.y, 3);
                    break;
                case SDL_MOUSEBUTTONUP:
                    if (ev.button.button == SDL_BUTTON_LEFT) app.onMouseUp(ev.button.x,
                                                                           ev.button.y, 1);
                    else if (ev.button.button == SDL_BUTTON_RIGHT)
                        app.onMouseUp(ev.button.x, ev.button.y, 3);
                    break;
                case SDL_MOUSEWHEEL:
                    app.onWheel(ev.wheel.y);
                    break;
                case SDL_KEYDOWN:
                    if (!ev.key.repeat && isMapped(ev.key.keysym.sym))
                        app.onKey(mapKey(ev.key.keysym.sym), true);
                    break;
                case SDL_TEXTINPUT:
                    app.onTextInput(ev.text.text);
                    break;
            }
        }
        auto now = std::chrono::steady_clock::now();
        double dt = std::chrono::duration<double>(now - last).count();
        last = now;
        if (dt > 0.25) dt = 0.25;
        app.update(dt);
        const rt::FrameBuffer& fb = app.frame();
        SDL_UpdateTexture(texture, nullptr, fb.px.data(), fb.w * 4);
        SDL_RenderClear(renderer);
        SDL_RenderCopy(renderer, texture, nullptr, nullptr);
        SDL_RenderPresent(renderer);
    }

    engine::audio::clearSink();
    if (audioDev > 0) SDL_CloseAudioDevice(audioDev);
    SDL_StopTextInput();
    SDL_DestroyTexture(texture);
    SDL_DestroyRenderer(renderer);
    SDL_DestroyWindow(window);
    SDL_Quit();
    return 0;
}

int runGuiSmoke(int seconds) {
    if (seconds < 1) seconds = 1;
    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO) != 0) {
        std::cerr << "SDL_Init failed: " << SDL_GetError() << "\n";
        return 1;
    }
    SDL_Window* window = SDL_CreateWindow(
        "Checkers & Chess - smoke", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED, 960,
        640, SDL_WINDOW_RESIZABLE);
    if (!window) {
        std::cerr << "SDL_CreateWindow failed: " << SDL_GetError() << "\n";
        SDL_Quit();
        return 1;
    }
    SDL_Renderer* renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_SOFTWARE);
    if (!renderer) {
        std::cerr << "SDL_CreateRenderer failed: " << SDL_GetError() << "\n";
        SDL_DestroyWindow(window);
        SDL_Quit();
        return 1;
    }
    SDL_Texture* texture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_ARGB8888,
                                             SDL_TEXTUREACCESS_STREAMING, 960, 640);
    if (!texture) {
        std::cerr << "SDL_CreateTexture failed: " << SDL_GetError() << "\n";
        SDL_DestroyRenderer(renderer);
        SDL_DestroyWindow(window);
        SDL_Quit();
        return 1;
    }
    app::App app;  // headless-safe: no audio backend registered, silent
    auto start = std::chrono::steady_clock::now();
    auto last = start;
    int frames = 0;
    while (!app.shouldQuit()) {
        SDL_Event ev;
        while (SDL_PollEvent(&ev)) {
            if (ev.type == SDL_QUIT) app.requestQuit();
            if (ev.type == SDL_WINDOWEVENT && ev.window.event == SDL_WINDOWEVENT_RESIZED) {
                app.setWindowSize(ev.window.data1, ev.window.data2);
                SDL_DestroyTexture(texture);
                texture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_ARGB8888,
                                            SDL_TEXTUREACCESS_STREAMING, ev.window.data1,
                                            ev.window.data2);
                if (!texture) {
                    SDL_DestroyRenderer(renderer);
                    SDL_DestroyWindow(window);
                    SDL_Quit();
                    return 1;
                }
            }
        }
        auto now = std::chrono::steady_clock::now();
        double dt = std::chrono::duration<double>(now - last).count();
        last = now;
        if (dt > 0.25) dt = 0.25;
        app.update(dt);
        const rt::FrameBuffer& fb = app.frame();
        SDL_UpdateTexture(texture, nullptr, fb.px.data(), fb.w * 4);
        SDL_RenderClear(renderer);
        SDL_RenderCopy(renderer, texture, nullptr, nullptr);
        SDL_RenderPresent(renderer);
        frames++;
        if (std::chrono::duration<double>(now - start).count() >= seconds) break;
    }
    SDL_DestroyTexture(texture);
    SDL_DestroyRenderer(renderer);
    SDL_DestroyWindow(window);
    SDL_Quit();
    std::cout << "GUI smoke OK (" << frames << " frames)\n";
    return 0;
}

#endif  // HAVE_SDL2
