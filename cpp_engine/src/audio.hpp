// Synthesised sound effects. No audio files are needed: tones and
// noise bursts are generated as PCM samples. All playback happens on
// background threads and failures (headless environment, no audio
// device) are swallowed silently so gameplay is never blocked by sound.
//
// Backend: when the SDL2 platform is linked, samples are queued to the
// SDL audio device; otherwise play* calls are silent no-ops. The engine
// (GameController) calls these directly and never blocks on audio.
#pragma once

#include <cstdint>
#include <functional>
#include <vector>

namespace engine {
namespace audio {

void setEnabled(bool on);
bool isEnabled();

// Must be called once by the GUI platform layer when audio output is
// available. The headless harness never calls it, so simulation runs
// silently and without touching any audio device.
void initBackend();
void shutdownBackend();
bool backendReady();

// Platform-layer hooks: the SDL backend registers a PCM sink that
// receives 22050 Hz mono int16 samples. Declared here so the platform
// can link against the core without extra headers.
void setSink(std::function<void(const std::vector<std::int16_t>&)> sink);
void clearSink();

void playMove();
void playSelect();
void playCapture();
void playCheck();
void playIllegal();
void playWin();
void playDraw();

}  // namespace audio
}  // namespace engine
