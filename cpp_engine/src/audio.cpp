// PCM synthesis for the sound effects in audio.hpp. The SDL2 platform
// (platform_sdl.cpp) registers a sample sink via setSink(); without a
// registered sink every play* call is a silent no-op, which is exactly
// what the headless simulation path wants.
#include "audio.hpp"

#include <cmath>
#include <cstdint>
#include <functional>
#include <mutex>
#include <random>
#include <thread>
#include <vector>

namespace engine {
namespace audio {

namespace {

constexpr int kSampleRate = 22050;
constexpr double kAudioPi = 3.14159265358979323846;
std::mutex gMutex;
bool gEnabled = true;
std::function<void(const std::vector<int16_t>&)> gSink;

void emit(const std::vector<int16_t>& samples) {
    std::function<void(const std::vector<int16_t>&)> sink;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (!gEnabled || !gSink) return;
        sink = gSink;
    }
    try {
        sink(samples);
    } catch (...) {
        // Sound must never break the game.
    }
}

std::vector<int16_t> makeTone(double freq, int ms, double volume) {
    int frames = static_cast<int>(kSampleRate * ms / 1000.0);
    std::vector<int16_t> data(static_cast<size_t>(frames));
    for (int i = 0; i < frames; i++) {
        double attack = std::min(1.0, i / (kSampleRate * 0.008));
        double env = attack * (1.0 - static_cast<double>(i) / frames);
        double v = std::sin(2 * kAudioPi * freq * i / kSampleRate) * env * volume;
        data[static_cast<size_t>(i)] = static_cast<int16_t>(v * 30000);
    }
    return data;
}

std::vector<int16_t> makeNoise(int ms, double volume) {
    int frames = static_cast<int>(kSampleRate * ms / 1000.0);
    std::vector<int16_t> data(static_cast<size_t>(frames));
    std::mt19937_64 rnd(42);
    std::normal_distribution<double> gauss(0.0, 1.0);
    for (int i = 0; i < frames; i++) {
        double env = 1.0 - static_cast<double>(i) / frames;
        data[static_cast<size_t>(i)] =
            static_cast<int16_t>(gauss(rnd) * 9000 * env * volume * 2);
    }
    return data;
}

void playToneAsync(double freq, int ms, double volume) {
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (!gEnabled || !gSink) return;
    }
    std::thread([freq, ms, volume] { emit(makeTone(freq, ms, volume)); }).detach();
}

}  // namespace

void setEnabled(bool on) {
    std::lock_guard<std::mutex> lock(gMutex);
    gEnabled = on;
}

bool isEnabled() {
    std::lock_guard<std::mutex> lock(gMutex);
    return gEnabled;
}

void setSink(std::function<void(const std::vector<int16_t>&)> sink) {
    std::lock_guard<std::mutex> lock(gMutex);
    gSink = std::move(sink);
}

void clearSink() {
    std::lock_guard<std::mutex> lock(gMutex);
    gSink = nullptr;
}

bool backendReady() {
    std::lock_guard<std::mutex> lock(gMutex);
    return static_cast<bool>(gSink);
}

void initBackend() {}     // The SDL platform registers a sink; kept for API symmetry.
void shutdownBackend() { clearSink(); }

void playMove() { playToneAsync(660, 70, 0.35); }
void playSelect() { playToneAsync(520, 45, 0.22); }
void playCapture() {
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (!gEnabled || !gSink) return;
    }
    std::thread([] {
        emit(makeNoise(120, 0.5));
        emit(makeTone(220, 110, 0.4));
    }).detach();
}
void playCheck() {
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (!gEnabled || !gSink) return;
    }
    std::thread([] {
        emit(makeTone(880, 90, 0.35));
        emit(makeTone(1174, 130, 0.35));
    }).detach();
}
void playIllegal() { playToneAsync(140, 160, 0.4); }
void playWin() {
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (!gEnabled || !gSink) return;
    }
    std::thread([] {
        emit(makeTone(523, 130, 0.35));
        emit(makeTone(659, 130, 0.35));
        emit(makeTone(784, 130, 0.35));
        emit(makeTone(1046, 260, 0.4));
    }).detach();
}
void playDraw() {
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (!gEnabled || !gSink) return;
    }
    std::thread([] {
        emit(makeTone(440, 200, 0.35));
        emit(makeTone(440, 200, 0.35));
    }).detach();
}

}  // namespace audio
}  // namespace engine
