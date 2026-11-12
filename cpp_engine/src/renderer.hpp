// Multi-threaded, progressive rendering over std::thread workers.
//
// Rows are split across a ThreadPool sized to the machine. A render may
// be cancelled through a CancelCheck when the camera or light moves: the
// UI renders a cheap preview while dragging and requests the
// high-quality pass only once interaction stops.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <thread>
#include <vector>

#include "camera.hpp"
#include "framebuffer.hpp"
#include "ray_tracer.hpp"
#include "scene.hpp"
#include "thread_pool.hpp"

namespace rt {

class Renderer {
public:
    explicit Renderer(size_t threads = std::thread::hardware_concurrency())
        : pool_(threads == 0 ? 2 : threads) {}

    size_t threadCount() const { return pool_.threadCount(); }

    // Renders packed sRGB pixels, row-major. `cancel`, when set, is
    // polled by workers; a cancelled render still returns a buffer (rows
    // never started keep their previous contents, i.e. zeros here).
    PixelBuffer render(const Scene& scene, const Camera& camera, int width, int height,
                       const RenderSettings& settings,
                       const std::function<bool()>& cancel = {}) {
        PixelBuffer pixels(static_cast<size_t>(width) * static_cast<size_t>(height), 0);
        renderInto(scene, camera, width, height, settings, pixels, cancel);
        return pixels;
    }

    void renderInto(const Scene& scene, const Camera& camera, int width, int height,
                    const RenderSettings& settings, PixelBuffer& pixels,
                    const std::function<bool()>& cancel = {}) {
        auto cancelled = [&cancel] { return cancel && cancel(); };
        RayTracer tracer(&scene);
        constexpr int kRowThreshold = 12;
        std::atomic<int> nextRow(0);
        size_t workers = pool_.threadCount();
        std::atomic<size_t> done(0);
        for (size_t w = 0; w < workers; w++) {
            pool_.enqueue([&] {
                for (;;) {
                    if (cancelled()) break;
                    int y0 = nextRow.fetch_add(kRowThreshold);
                    if (y0 >= height) break;
                    int y1 = y0 + kRowThreshold;
                    if (y1 > height) y1 = height;
                    for (int y = y0; y < y1; y++) {
                        if (cancelled()) break;
                        size_t base = static_cast<size_t>(y) * static_cast<size_t>(width);
                        for (int x = 0; x < width; x++)
                            pixels[base + static_cast<size_t>(x)] =
                                RayTracer::toRgb(tracer.shadePixel(camera, x, y, width, height, settings));
                    }
                }
                done.fetch_add(1);
            });
        }
        // Wait for all chunks (busy-light spin on the counter).
        while (done.load() < workers) std::this_thread::yield();
    }

private:
    ThreadPool pool_;
};

}  // namespace rt
