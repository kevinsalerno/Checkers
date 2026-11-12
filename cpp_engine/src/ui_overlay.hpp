// Immediate-mode 2D UI primitives drawn in software into a FrameBuffer.
// The platform layer feeds mouse/keyboard state through Context each
// frame; widgets return true on the frame they are activated. There is
// no retained widget tree and no GPU dependency.
#pragma once

#include <cstdint>
#include <string>

#include "framebuffer.hpp"

namespace ui {

constexpr uint32_t kBg = 0xFF1A1D24u;
constexpr uint32_t kPanel = 0xFF262B36u;
constexpr uint32_t kPanelEdge = 0xFF3A4150u;
constexpr uint32_t kText = 0xFFE8E8E8u;
constexpr uint32_t kDim = 0xFF9AA0AEu;
constexpr uint32_t kAccent = 0xFF4DA3FFu;
constexpr uint32_t kAccentDark = 0xFF2A5A8Fu;
constexpr uint32_t kGood = 0xFF3FD67Cu;
constexpr uint32_t kBad = 0xFFE05252u;
constexpr uint32_t kGold = 0xFFFFC94Du;
constexpr uint32_t kSquareLight = 0xFFB9926Bu;
constexpr uint32_t kSquareDark = 0xFF59391Fu;
constexpr uint32_t kSquareHi = 0xFF2E7D4Fu;

struct Context {
    int mouseX = 0, mouseY = 0;
    bool leftHeld = false;
    // Edge-triggered click, consumed by exactly one widget per frame.
    bool clicked = false;
    int clickX = 0, clickY = 0;
    void consumeClick() { clicked = false; }
};

inline bool inside(int x, int y, int w, int h, int px, int py) {
    return px >= x && py >= y && px < x + w && py < y + h;
}

// A button. Returns true on the frame it is clicked. `enabled=false`
// renders dimmed and ignores input.
bool button(rt::FrameBuffer& fb, Context& ctx, int x, int y, int w, int h,
            const std::string& label, bool enabled = true, int scale = 2);

// A labelled stepper row: "< value >" with optional unit suffix.
// `delta` is set to -1/+1 on the frame the user steps. Returns true if stepped.
bool stepper(rt::FrameBuffer& fb, Context& ctx, int x, int y, int w, int h,
             const std::string& caption, const std::string& value, int& delta, int scale = 2);

// A toggle button showing ON/OFF. Returns true on flip.
bool toggle(rt::FrameBuffer& fb, Context& ctx, int x, int y, int w, int h,
            const std::string& caption, bool& value, int scale = 2);

// Single-line text field. `active` tracks focus; the platform calls
// inputChar() while active. Handles focus click + backspace key flag.
struct TextField {
    std::string text;
    bool active = false;
    // Set by the platform before draw(): queued character input and
    // backspace/enter edges for this frame.
    std::string pendingText;
    bool backspace = false;
    bool enterPressed = false;
};
bool textField(rt::FrameBuffer& fb, Context& ctx, int x, int y, int w, int h, TextField& field,
               const std::string& placeholder = "", int scale = 2);

void panel(rt::FrameBuffer& fb, int x, int y, int w, int h);
void label(rt::FrameBuffer& fb, const std::string& text, int x, int y, uint32_t color = kText,
           int scale = 2);
void centerLabel(rt::FrameBuffer& fb, const std::string& text, int cx, int y,
                 uint32_t color = kText, int scale = 2);

}  // namespace ui
