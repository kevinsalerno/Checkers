#include "ui_overlay.hpp"

namespace ui {

bool button(rt::FrameBuffer& fb, Context& ctx, int x, int y, int w, int h,
            const std::string& label, bool enabled, int scale) {
    bool hover = enabled && inside(x, y, w, h, ctx.mouseX, ctx.mouseY);
    bool pressed = hover && ctx.leftHeld;
    uint32_t bg = !enabled ? kPanel : (pressed ? kAccentDark : (hover ? kAccent : kPanel));
    fb.fillRect(x, y, w, h, bg);
    fb.rect(x, y, w, h, enabled ? kPanelEdge : kDim);
    uint32_t fg = !enabled ? kDim : (hover ? 0xFFFFFFFFu : kText);
    int tw = rt::font::textWidth(label, scale);
    int th = rt::font::textHeight(scale);
    rt::font::drawText(fb, label, x + (w - tw) / 2, y + (h - th) / 2, fg, scale);
    if (hover && ctx.clicked) {
        ctx.consumeClick();
        return true;
    }
    return false;
}

bool stepper(rt::FrameBuffer& fb, Context& ctx, int x, int y, int w, int h,
             const std::string& caption, const std::string& value, int& delta, int scale) {
    delta = 0;
    label(fb, caption, x, y, kText, scale);
    int bw = 44, vw = 200;
    int vx = x + w - (bw * 2 + vw);
    if (vx < x + 200) vx = x + 200;
    centerLabel(fb, value, vx + bw + vw / 2, y, kGold, scale);
    if (button(fb, ctx, vx, y, bw, h, "<")) {
        delta = -1;
        return true;
    }
    if (button(fb, ctx, vx + bw + vw, y, bw, h, ">")) {
        delta = 1;
        return true;
    }
    return false;
}

bool toggle(rt::FrameBuffer& fb, Context& ctx, int x, int y, int w, int h,
            const std::string& caption, bool& value, int scale) {
    label(fb, caption, x, y, kText, scale);
    if (button(fb, ctx, x + w - 110, y, 110, h, value ? "ON" : "OFF")) {
        value = !value;
        return true;
    }
    return false;
}

bool textField(rt::FrameBuffer& fb, Context& ctx, int x, int y, int w, int h, TextField& field,
               const std::string& placeholder, int scale) {
    if (!field.pendingText.empty() && field.active) {
        field.text += field.pendingText;
        field.pendingText.clear();
    }
    if (field.backspace && field.active && !field.text.empty()) {
        // Erase one UTF-8 code point.
        size_t n = field.text.size();
        size_t cut = n - 1;
        while (cut > 0 && (field.text[cut] & 0xC0) == 0x80) cut--;
        field.text.erase(cut);
    }
    field.backspace = false;
    if (ctx.clicked && inside(x, y, w, h, ctx.clickX, ctx.clickY)) {
        field.active = true;
        ctx.consumeClick();
    } else if (ctx.clicked && field.active &&
               !inside(x, y, w, h, ctx.clickX, ctx.clickY)) {
        field.active = false;
    }
    fb.fillRect(x, y, w, h, 0xFF101318u);
    fb.rect(x, y, w, h, field.active ? kAccent : kPanelEdge);
    std::string shown = field.text.empty() ? placeholder : field.text;
    uint32_t fg = field.text.empty() ? kDim : kText;
    // Clip the tail if the string is too long.
    int maxChars = (w - 12) / (6 * scale);
    if (static_cast<int>(shown.size()) > maxChars && maxChars > 0)
        shown = shown.substr(shown.size() - static_cast<size_t>(maxChars));
    rt::font::drawText(fb, shown, x + 6, y + (h - rt::font::textHeight(scale)) / 2, fg, scale);
    if (field.active && field.enterPressed) {
        field.enterPressed = false;
        return true;
    }
    field.enterPressed = false;
    return false;
}

void panel(rt::FrameBuffer& fb, int x, int y, int w, int h) {
    fb.fillRect(x, y, w, h, kPanel);
    fb.rect(x, y, w, h, kPanelEdge);
}

void label(rt::FrameBuffer& fb, const std::string& text, int x, int y, uint32_t color,
           int scale) {
    rt::font::drawText(fb, text, x, y, color, scale);
}

void centerLabel(rt::FrameBuffer& fb, const std::string& text, int cx, int y, uint32_t color,
                 int scale) {
    rt::font::drawText(fb, text, cx - rt::font::textWidth(text, scale) / 2, y, color, scale);
}

}  // namespace ui
