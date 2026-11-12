// Software pixel buffer shared by the ray tracer blit path and the
// immediate-mode 2D UI overlay. Everything is CPU-side uint32 pixels;
// the platform layer only uploads the finished buffer to a window.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

namespace rt {

using PixelBuffer = std::vector<uint32_t>;  // packed 0xFFRRGGBB, row-major

struct FrameBuffer {
    int w = 0, h = 0;
    std::vector<uint32_t> px;  // 0xFFRRGGBB, row-major

    FrameBuffer() = default;
    FrameBuffer(int w_, int h_) { resize(w_, h_); }

    void resize(int w_, int h_) {
        w = w_;
        h = h_;
        px.assign(static_cast<size_t>(w) * static_cast<size_t>(h), 0xFF000000u);
    }

    void fill(uint32_t color) { std::fill(px.begin(), px.end(), color); }

    void set(int x, int y, uint32_t color) {
        if (x >= 0 && y >= 0 && x < w && y < h)
            px[static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)] = color;
    }

    uint32_t get(int x, int y) const {
        if (x < 0 || y < 0 || x >= w || y >= h) return 0;
        return px[static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)];
    }

    void fillRect(int x0, int y0, int rw, int rh, uint32_t color) {
        int x1 = std::min(w, x0 + rw), y1 = std::min(h, y0 + rh);
        for (int y = std::max(0, y0); y < y1; y++)
            for (int x = std::max(0, x0); x < x1; x++) set(x, y, color);
    }

    void rect(int x0, int y0, int rw, int rh, uint32_t color, int thick = 1) {
        fillRect(x0, y0, rw, thick, color);
        fillRect(x0, y0 + rh - thick, rw, thick, color);
        fillRect(x0, y0, thick, rh, color);
        fillRect(x0 + rw - thick, y0, thick, rh, color);
    }

    void hline(int x0, int x1, int y, uint32_t color) {
        if (y < 0 || y >= h) return;
        for (int x = std::max(0, x0); x <= std::min(w - 1, x1); x++) set(x, y, color);
    }

    void line(int x0, int y0, int x1, int y1, uint32_t color) {
        int dx = std::abs(x1 - x0), dy = -std::abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        for (;;) {
            set(x0, y0, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    void circle(int cx, int cy, int r, uint32_t color, bool filled) {
        if (filled) {
            for (int y = -r; y <= r; y++) {
                int half = static_cast<int>(std::sqrt(static_cast<double>(r * r - y * y)));
                hline(cx - half, cx + half, cy + y, color);
            }
        } else {
            int x = r, y = 0, err = 0;
            while (x >= y) {
                set(cx + x, cy + y, color);
                set(cx + y, cy + x, color);
                set(cx - y, cy + x, color);
                set(cx - x, cy + y, color);
                set(cx - x, cy - y, color);
                set(cx - y, cy - x, color);
                set(cx + y, cy - x, color);
                set(cx + x, cy - y, color);
                y++;
                err += 1 + 2 * y;
                if (2 * (err - x) + 1 > 0) {
                    x--;
                    err += 1 - 2 * x;
                }
            }
        }
    }

    // Nearest-neighbour upscale blit (fast preview path).
    static FrameBuffer upscaleNearest(const PixelBuffer& src, int sw, int sh, int dw, int dh) {
        FrameBuffer out(dw, dh);
        for (int y = 0; y < dh; y++) {
            int sy = y * sh / dh;
            for (int x = 0; x < dw; x++) {
                int sx = x * sw / dw;
                out.px[static_cast<size_t>(y) * static_cast<size_t>(dw) + static_cast<size_t>(x)] =
                    src[static_cast<size_t>(sy) * static_cast<size_t>(sw) + static_cast<size_t>(sx)];
            }
        }
        return out;
    }

    // Bilinear upscale (default presentation path: smoother than nearest
    // when rendering below window resolution).
    static FrameBuffer upscaleBilinear(const PixelBuffer& src, int sw, int sh, int dw, int dh) {
        FrameBuffer out(dw, dh);
        auto sample = [&](int x, int y) -> const uint32_t& {
            x = std::max(0, std::min(sw - 1, x));
            y = std::max(0, std::min(sh - 1, y));
            return src[static_cast<size_t>(y) * static_cast<size_t>(sw) + static_cast<size_t>(x)];
        };
        for (int y = 0; y < dh; y++) {
            double sy = (dh == 1) ? 0 : (y + 0.5) * sh / dh - 0.5;
            int y0 = static_cast<int>(std::floor(sy));
            double fy = sy - y0;
            if (y0 < 0) {
                y0 = 0;
                fy = 0;
            }
            if (y0 >= sh - 1) {
                y0 = sh - 1;
                fy = 0;
            }
            for (int x = 0; x < dw; x++) {
                double sx = (dw == 1) ? 0 : (x + 0.5) * sw / dw - 0.5;
                int x0 = static_cast<int>(std::floor(sx));
                double fx = sx - x0;
                if (x0 < 0) {
                    x0 = 0;
                    fx = 0;
                }
                if (x0 >= sw - 1) {
                    x0 = sw - 1;
                    fx = 0;
                }
                uint32_t a = sample(x0, y0), b = sample(x0 + 1, y0);
                uint32_t c = sample(x0, y0 + 1), d = sample(x0 + 1, y0 + 1);
                auto mix2 = [&](uint32_t p, uint32_t q, double t, int shift) {
                    double vp = ((p >> shift) & 0xFF) * (1 - t) + ((q >> shift) & 0xFF) * t;
                    return static_cast<uint32_t>(vp + 0.5);
                };
                auto mix4 = [&](int shift) {
                    double top = ((a >> shift) & 0xFF) * (1 - fx) + ((b >> shift) & 0xFF) * fx;
                    double bot = ((c >> shift) & 0xFF) * (1 - fx) + ((d >> shift) & 0xFF) * fx;
                    return static_cast<uint32_t>(top * (1 - fy) + bot * fy + 0.5);
                };
                (void)mix2;
                out.px[static_cast<size_t>(y) * static_cast<size_t>(dw) + static_cast<size_t>(x)] =
                    (0xFFu << 24) | (mix4(16) << 16) | (mix4(8) << 8) | mix4(0);
            }
        }
        return out;
    }
};

// ---------------------------------------------------------------------------
// Tiny built-in 5x7 bitmap font (ASCII 32..126). Renders in one flat colour.
// ---------------------------------------------------------------------------
namespace font {
using Glyph = uint8_t[7];  // 5 columns per row, LSB = left pixel

const uint8_t* glyphFor(char ch);

inline void drawChar(FrameBuffer& fb, char ch, int x, int y, uint32_t color, int scale = 2) {
    const uint8_t* g = glyphFor(ch);
    for (int row = 0; row < 7; row++)
        for (int col = 0; col < 5; col++)
            if (g[row] & (1u << (4 - col))) fb.fillRect(x + col * scale, y + row * scale, scale,
                                                        scale, color);
}

inline void drawText(FrameBuffer& fb, const std::string& text, int x, int y, uint32_t color,
                     int scale = 2) {
    int cx = x;
    for (char ch : text) {
        if (ch == '\n') {
            y += 9 * scale;
            cx = x;
            continue;
        }
        drawChar(fb, ch, cx, y, color, scale);
        cx += 6 * scale;
    }
}

inline int textWidth(const std::string& text, int scale = 2) {
    return static_cast<int>(text.size()) * 6 * scale;
}
inline int textHeight(int scale = 2) { return 7 * scale; }

}  // namespace font
}  // namespace rt
