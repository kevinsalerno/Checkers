// Scalable graphics settings. The whole point of this module: users on
// weaker hardware scale the ray tracing intensity down in real time
// (via the in-game settings menu or config.ini) to reach a playable
// framerate.
//
// - Resolution Scale: renders at a lower internal resolution (e.g. 50%,
//   75%) and upscales to the window size.
// - Max Ray Bounces: 0-5 (0 disables reflections, 1 is fast, 3-5 is
//   high-quality).
// - Shadow Quality: Hard (single shadow ray per shading point) or Soft
//   (multiple rays to the area light, with an adjustable sample count).
// - Samples Per Pixel (anti-aliasing): 1, 4 or 9 (1x1, 2x2, 3x3 grid).
#pragma once

#include <string>

#include "ray_tracer.hpp"

namespace rt {

enum class ShadowQuality { Hard, Soft };

struct GraphicsSettings {
    // 0.25 .. 1.0, e.g. 0.5 = half-resolution internal buffer.
    double resolutionScale = 1.0;
    int maxBounces = 2;  // 0..5
    ShadowQuality shadowQuality = ShadowQuality::Soft;
    int softShadowSamples = 8;  // used when shadowQuality == Soft
    int samplesPerPixel = 4;    // 1, 4 or 9

    std::string presetName = "Balanced";

    static GraphicsSettings fast() {
        GraphicsSettings g;
        g.resolutionScale = 0.5;
        g.maxBounces = 1;
        g.shadowQuality = ShadowQuality::Hard;
        g.softShadowSamples = 4;
        g.samplesPerPixel = 1;
        g.presetName = "Fast";
        return g;
    }
    static GraphicsSettings balanced() {
        GraphicsSettings g;
        g.resolutionScale = 0.75;
        g.maxBounces = 2;
        g.shadowQuality = ShadowQuality::Soft;
        g.softShadowSamples = 8;
        g.samplesPerPixel = 4;
        g.presetName = "Balanced";
        return g;
    }
    static GraphicsSettings high() {
        GraphicsSettings g;
        g.resolutionScale = 1.0;
        g.maxBounces = 3;
        g.shadowQuality = ShadowQuality::Soft;
        g.softShadowSamples = 16;
        g.samplesPerPixel = 4;
        g.presetName = "High";
        return g;
    }
    static GraphicsSettings ultra() {
        GraphicsSettings g;
        g.resolutionScale = 1.0;
        g.maxBounces = 5;
        g.shadowQuality = ShadowQuality::Soft;
        g.softShadowSamples = 24;
        g.samplesPerPixel = 9;
        g.presetName = "Ultra";
        return g;
    }

    // Maps to the tracer's per-pixel controls.
    RenderSettings toRenderSettings() const {
        RenderSettings r;
        r.aaAxis = (samplesPerPixel >= 9) ? 3 : (samplesPerPixel >= 4 ? 2 : 1);
        r.shadowSamples =
            (shadowQuality == ShadowQuality::Hard) ? 1 : softShadowSamples;
        r.maxDepth = maxBounces;
        return r;
    }

    // Internal buffer size for a window of (w, h).
    void internalSize(int w, int h, int& outW, int& outH) const {
        outW = static_cast<int>(w * resolutionScale + 0.5);
        outH = static_cast<int>(h * resolutionScale + 0.5);
        if (outW < 8) outW = 8;
        if (outH < 8) outH = 8;
    }

    // Validates/clamps every field (called after load and after UI edits).
    void clamp();

    // config.ini persistence. Returns false on I/O error (keeps defaults).
    bool load(const std::string& path);
    bool save(const std::string& path) const;
};

}  // namespace rt
