#include "graphics_settings.hpp"

#include <algorithm>
#include <cctype>
#include <fstream>
#include <map>
#include <sstream>

namespace rt {

void GraphicsSettings::clamp() {
    resolutionScale = std::max(0.25, std::min(1.0, resolutionScale));
    maxBounces = std::max(0, std::min(5, maxBounces));
    softShadowSamples = std::max(1, std::min(64, softShadowSamples));
    if (samplesPerPixel != 1 && samplesPerPixel != 4 && samplesPerPixel != 9) {
        samplesPerPixel = (samplesPerPixel < 3) ? 1 : (samplesPerPixel < 7 ? 4 : 9);
    }
}

static std::string lowerStr(std::string s) {
    for (auto& ch : s) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    return s;
}

bool GraphicsSettings::load(const std::string& path) {
    std::ifstream in(path);
    if (!in) return false;
    std::map<std::string, std::string> kv;
    std::string line, section;
    auto trim = [](std::string s) {
        size_t a = 0;
        while (a < s.size() && std::isspace(static_cast<unsigned char>(s[a]))) a++;
        size_t b = s.size();
        while (b > a && (std::isspace(static_cast<unsigned char>(s[b - 1])) || s[b - 1] == '\r'))
            b--;
        return s.substr(a, b - a);
    };
    while (std::getline(in, line)) {
        line = trim(line);
        if (line.empty() || line[0] == '#' || line[0] == ';') continue;
        if (line.front() == '[' && line.back() == ']') {
            section = lowerStr(trim(line.substr(1, line.size() - 2)));
            continue;
        }
        size_t eq = line.find('=');
        if (eq == std::string::npos) continue;
        kv[section + "." + lowerStr(trim(line.substr(0, eq)))] = trim(line.substr(eq + 1));
    }
    auto get = [&](const char* key, const std::string& fallback) {
        auto it = kv.find(key);
        return it == kv.end() ? fallback : it->second;
    };
    std::string preset = get("graphics.preset", presetName);
    if (lowerStr(preset) == "fast") {
        *this = fast();
    } else if (lowerStr(preset) == "balanced") {
        *this = balanced();
    } else if (lowerStr(preset) == "high") {
        *this = high();
    } else if (lowerStr(preset) == "ultra") {
        *this = ultra();
    }
    try {
        if (kv.count("graphics.resolution_scale"))
            resolutionScale = std::stod(kv["graphics.resolution_scale"]);
        if (kv.count("graphics.max_bounces")) maxBounces = std::stoi(kv["graphics.max_bounces"]);
        if (kv.count("graphics.shadow_quality"))
            shadowQuality = (lowerStr(kv["graphics.shadow_quality"]) == "hard")
                                ? ShadowQuality::Hard
                                : ShadowQuality::Soft;
        if (kv.count("graphics.shadow_samples"))
            softShadowSamples = std::stoi(kv["graphics.shadow_samples"]);
        if (kv.count("graphics.samples_per_pixel"))
            samplesPerPixel = std::stoi(kv["graphics.samples_per_pixel"]);
    } catch (...) {
        // Malformed numbers fall back to the preset; still clamp.
    }
    if (kv.count("graphics.preset")) presetName = preset;
    clamp();
    return true;
}

bool GraphicsSettings::save(const std::string& path) const {
    std::ofstream out(path);
    if (!out) return false;
    out << "# Checkers & Chess Suite - graphics settings.\n"
        << "# Lower these to raise the framerate on weaker hardware.\n"
        << "[graphics]\n"
        << "preset = " << presetName << "\n"
        << "resolution_scale = " << resolutionScale << "  # 0.25..1.0\n"
        << "max_bounces = " << maxBounces << "  # 0..5\n"
        << "shadow_quality = " << (shadowQuality == ShadowQuality::Hard ? "hard" : "soft") << "\n"
        << "shadow_samples = " << softShadowSamples << "  # soft-shadow rays per pixel\n"
        << "samples_per_pixel = " << samplesPerPixel << "  # 1, 4 or 9\n";
    return static_cast<bool>(out);
}

}  // namespace rt
