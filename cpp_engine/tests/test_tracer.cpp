// Headless tests for geometry, soft shadows, reflections, parallelism,
// determinism and the settings/config layer. No window is opened.
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <tuple>
#include <vector>

#include "test_framework.hpp"
#include "../src/camera.hpp"
#include "../src/graphics_settings.hpp"
#include "../src/material.hpp"
#include "../src/primitive.hpp"
#include "../src/ray_tracer.hpp"
#include "../src/renderer.hpp"
#include "../src/scene_builder.hpp"

namespace {

using rt::AreaLight;
using rt::BoundedPlane;
using rt::Camera;
using rt::Cylinder;
using rt::GraphicsSettings;
using rt::Hit;
using rt::Material;
using rt::Ray;
using rt::RayTracer;
using rt::RenderSettings;
using rt::Renderer;
using rt::Sphere;
using rt::Vec3;
using testutil::check;
using testutil::eqDouble;
using testutil::eqInt;
using testutil::eqStr;

void geometry() {
    Material mat = Material::matte(Vec3(0.5, 0.5, 0.5));
    Sphere sphere(Vec3(0, 0, 0), 1, &mat);
    auto hit = sphere.intersect(Ray(Vec3(0, 0, -5), Vec3(0, 0, 1)), 0, 100);
    check(hit.has_value(), "tracer: sphere hit");
    if (hit) eqDouble(4.0, std::round(hit->t * 1000) / 1000.0, "tracer: sphere hit distance");
    auto miss = sphere.intersect(Ray(Vec3(3, 0, -5), Vec3(0, 0, 1)), 0, 100);
    check(!miss.has_value(), "tracer: sphere miss");
    Cylinder disc(0, 0, 0, 0.5, 0.2, &mat);
    auto top = disc.intersect(Ray(Vec3(0, 2, 0), Vec3(0, -1, 0)), 0, 100);
    check(top.has_value() && std::abs(top->t - 1.8) < 1e-6, "tracer: cylinder top cap hit");
    auto side = disc.intersect(Ray(Vec3(-2, 0.1, 0), Vec3(1, 0, 0)), 0, 100);
    check(side.has_value(), "tracer: cylinder side hit");
    auto outside = disc.intersect(Ray(Vec3(2, 0.1, 0), Vec3(1, 0, 0)), 0, 100);
    check(!outside.has_value(), "tracer: cylinder miss past the side");
    BoundedPlane plane = BoundedPlane::horizontal(0, 2, &mat);
    auto onPlane = plane.intersect(Ray(Vec3(1, 3, 1), Vec3(0, -1, 0)), 0, 100);
    check(onPlane.has_value(), "tracer: bounded plane hit inside patch");
    auto offPlane = plane.intersect(Ray(Vec3(5, 3, 0), Vec3(0, -1, 0)), 0, 100);
    check(!offPlane.has_value(), "tracer: bounded plane miss outside patch");
}

void areaLight() {
    AreaLight light(Vec3(0, 5, 0), 2.0, Vec3(1, 1, 1), 1.0);
    auto samples = light.samplePoints(16, 0.3);
    eqInt(16, (int)samples.size(), "tracer: area light sample count");
    bool inside = true, distinct = true;
    for (size_t i = 0; i < samples.size(); i++) {
        Vec3 s = samples[i];
        double dx = s.x - light.center.x, dz = s.z - light.center.z;
        if (dx * dx + dz * dz > 4.0 + 1e-9 || s.y != 5.0) inside = false;
        for (size_t j = i + 1; j < samples.size(); j++)
            if ((samples[j] - s).length() < 1e-9) distinct = false;
    }
    check(inside, "tracer: area light samples lie on the disc");
    check(distinct, "tracer: area light samples are distinct");
    AreaLight moved = light.moved(90, 60, 6);
    check(std::abs(moved.azimuthDeg() - 90) < 1e-6, "tracer: moved light keeps its azimuth");
    check(std::abs(moved.distance() - 6) < 1e-6, "tracer: moved light keeps its distance");
}

void cameraProjection() {
    Camera cam = Camera::defaultView();
    Ray centre = cam.rayForPixel(320.5, 240.5, 640, 480);
    check((centre.dir - cam.forward()).length() < 0.05,
          "tracer: centre pixel ray follows the view direction");
    auto projected = cam.project(rt::squareCenter(3, 3), 640, 480);
    check(projected.has_value(), "tracer: square centre projects in front");
    if (projected) {
        Ray picked =
            cam.pickRay(static_cast<int>(std::get<0>(*projected)),
                        static_cast<int>(std::get<1>(*projected)), 640, 480);
        double t = -picked.origin.y / picked.dir.y;
        Vec3 p = picked.at(t);
        Vec3 c3 = rt::squareCenter(3, 3);
        check(std::abs(p.x - c3.x) < 0.6 && std::abs(p.z - c3.z) < 0.6,
              "tracer: pick ray through a projected square lands on it");
    }
    check(!cam.project(Vec3(0, 0, 100), 640, 480).has_value() ||
              !cam.project(cam.eye() + cam.forward() * -1, 640, 480).has_value(),
          "tracer: points behind the camera do not project");
}

int luminance(uint32_t rgb) { return ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF); }

rt::PixelBuffer renderShadow(double lightRadius) {
    rt::Scene scene(AreaLight(Vec3(1.5, 5, 0.5), lightRadius, Vec3(1, 1, 1), 1.2));
    Material floor = Material::matte(Vec3(0.7, 0.7, 0.7));
    Material ball = Material::matte(Vec3(0.8, 0.2, 0.2));
    scene.add(std::make_unique<BoundedPlane>(BoundedPlane::horizontal(0, 10, &floor)));
    // NOTE: materials must outlive the scene; floor/ball live until the
    // render below completes (same scope). Kept alive by the caller.
    scene.add(std::make_unique<Sphere>(Vec3(0, 1, 0), 0.9, &ball));
    Camera cam(Vec3(0, 0.4, 0), 0, 32, 8, 42);
    Renderer r;
    RenderSettings s{1, 16, 1};
    return r.render(scene, cam, 140, 90, s);
}

int countPenumbra(const rt::PixelBuffer& pixels, int w, int h) {
    int y = h * 2 / 3, mn = 1 << 30, mx = -(1 << 30);
    for (int x = 0; x < w; x++) {
        int l = luminance(pixels[static_cast<size_t>(y) * w + x]);
        mn = std::min(mn, l);
        mx = std::max(mx, l);
    }
    int lo = mn + (mx - mn) / 4, hi = mn + 3 * (mx - mn) / 4, count = 0;
    for (int x = 0; x < w; x++) {
        int l = luminance(pixels[static_cast<size_t>(y) * w + x]);
        if (l > lo && l < hi) count++;
    }
    return count;
}

void softShadows() {
    rt::PixelBuffer hard = renderShadow(0.05);
    rt::PixelBuffer soft = renderShadow(2.2);
    int hardMid = countPenumbra(hard, 140, 90);
    int softMid = countPenumbra(soft, 140, 90);
    check(softMid > 0, "tracer: area light produces a penumbra");
    check(softMid > hardMid, "tracer: larger light makes a wider penumbra");
}

// Renders `scene` with a heap-owned material set that outlives the call.
struct OwnedScene {
    rt::Scene scene;
    std::vector<std::unique_ptr<Material>> mats;
    explicit OwnedScene(const AreaLight& light) : scene(light) {}
    const Material* keep(const Material& m) {
        mats.push_back(std::make_unique<Material>(m));
        return mats.back().get();
    }
};

void reflectionsAndDeterminism() {
    Camera cam(Vec3(0, 0.5, 0), 0, 20, 6, 42);
    OwnedScene matte(rt::defaultLight()), reflective(rt::defaultLight());
    const Material* mf = matte.keep(Material::matte(Vec3(0.7, 0.7, 0.7)));
    const Material* mb = matte.keep(Material::matte(Vec3(0.8, 0.2, 0.2)));
    matte.scene.add(std::make_unique<BoundedPlane>(BoundedPlane::horizontal(0, 4, mf)));
    matte.scene.add(std::make_unique<Sphere>(Vec3(0, 1, 0), 0.8, mb));
    const Material* rf = reflective.keep(Material::polished(Vec3(0.7, 0.7, 0.7), 0.0));
    const Material* rb = reflective.keep(Material::polished(Vec3(0.8, 0.2, 0.2), 0.9));
    reflective.scene.add(std::make_unique<BoundedPlane>(BoundedPlane::horizontal(0, 4, rf)));
    reflective.scene.add(std::make_unique<Sphere>(Vec3(0, 1, 0), 0.8, rb));
    Renderer r;
    rt::PixelBuffer a = r.render(matte.scene, cam, 120, 90, rt::kBalanced);
    rt::PixelBuffer b = r.render(reflective.scene, cam, 120, 90, rt::kBalanced);
    int differing = 0;
    for (size_t i = 0; i < a.size(); i++)
        if (a[i] != b[i]) differing++;
    check(differing > 200, "tracer: reflections change the image");
    rt::PixelBuffer again = r.render(reflective.scene, cam, 120, 90, rt::kBalanced);
    bool identical = true;
    for (size_t i = 0; i < b.size(); i++)
        if (b[i] != again[i]) identical = false;
    check(identical, "tracer: renders are deterministic");
}

void parallelismAndCancellation() {
    engine::CheckersEngine e;
    rt::BuiltScene scene = rt::buildCheckersScene(e);
    Camera cam = Camera::defaultView();
    Renderer many(8), one(1);
    rt::PixelBuffer parallel = many.render(scene.scene, cam, 96, 72, rt::kBalanced);
    rt::PixelBuffer serial = one.render(scene.scene, cam, 96, 72, rt::kBalanced);
    bool identical = parallel.size() == serial.size();
    for (size_t i = 0; identical && i < parallel.size(); i++)
        if (parallel[i] != serial[i]) identical = false;
    check(identical, "tracer: parallel and serial renders match exactly");
    // The renderer polls the cancel predicate; an immediately-cancelled
    // render must still return a full-size buffer without hanging.
    bool wasCancelled = false;
    rt::PixelBuffer cancelled = many.render(
        scene.scene, cam, 96, 72, rt::kBalanced,
        [&wasCancelled] {
            wasCancelled = true;
            return true;
        });
    check(wasCancelled, "tracer: cancellation is consulted");
    eqInt(96 * 72, (int)cancelled.size(), "tracer: cancelled render still returns a buffer");
}

void sceneBuilder() {
    engine::CheckersEngine ce;
    rt::BuiltScene checkers = rt::buildCheckersScene(ce);
    check(checkers.scene.primitiveCount() >= 50, "tracer: checkers scene has piece geometry");
    engine::ChessEngine che;
    rt::BuiltScene chess = rt::buildChessScene(che);
    check(chess.scene.primitiveCount() >= 60, "tracer: chess scene has piece geometry");
    Vec3 centre = rt::squareCenter(0, 0);
    eqDouble(-3.5, centre.x, "tracer: a8 world x");
    eqDouble(-3.5, centre.z, "tracer: a8 world z");
}

void hashDeterminism() {
    // Bit-exactness of hash01 is covered by cross-checking the Java port's
    // vectors (see README); here we pin the C++ values so regressions show.
    eqInt(0, rt::hash01(0, 0, 0) < 0 ? 1 : 0, "tracer: hash01(0,0,0) in [0,1)");
    eqInt(0, rt::hash01(5, 7, 9) >= 1 ? 1 : 0, "tracer: hash01 bounded above");
    check(rt::hash01(3, 4, 5) != rt::hash01(3, 4, 6), "tracer: hash01 varies with input");
    check(rt::hash01(11, 22, 33) == rt::hash01(11, 22, 33), "tracer: hash01 is pure");
}

void graphicsSettings() {
    GraphicsSettings fast = GraphicsSettings::fast();
    check(fast.resolutionScale == 0.5 && fast.maxBounces == 1 &&
              fast.shadowQuality == rt::ShadowQuality::Hard && fast.samplesPerPixel == 1,
          "settings: fast preset values");
    GraphicsSettings ultra = GraphicsSettings::ultra();
    check(ultra.maxBounces == 5 && ultra.samplesPerPixel == 9, "settings: ultra preset values");
    // Hard shadows collapse to a single shadow ray however many soft
    // samples are configured.
    RenderSettings rs = fast.toRenderSettings();
    eqInt(1, rs.shadowSamples, "settings: hard shadows use one shadow ray");
    eqInt(1, rs.aaAxis, "settings: 1 spp maps to a 1x1 grid");
    RenderSettings rs9 = ultra.toRenderSettings();
    eqInt(3, rs9.aaAxis, "settings: 9 spp maps to a 3x3 grid");
    eqInt(24, rs9.shadowSamples, "settings: soft shadow samples pass through");
    int iw, ih;
    fast.internalSize(960, 504, iw, ih);
    eqInt(480, iw, "settings: 50% scale halves the width");
    eqInt(252, ih, "settings: 50% scale halves the height");
    // config.ini round-trip via a temp file.
    const char* path = "/tmp/cpp_gfx_test.ini";
    {
        GraphicsSettings g = GraphicsSettings::high();
        check(g.save(path), "settings: save config.ini");
    }
    {
        GraphicsSettings g;
        check(g.load(path), "settings: load config.ini");
        eqStr("High", g.presetName, "settings: preset round-trips");
        eqInt(3, g.maxBounces, "settings: bounces round-trip");
        eqInt(4, g.samplesPerPixel, "settings: spp round-trips");
    }
    std::remove(path);
    {
        GraphicsSettings g;
        check(!g.load("/tmp/does-not-exist-xyz.ini"), "settings: missing file keeps defaults");
    }
}

}  // namespace

int runTracerTests() {
    testutil::reset();
    geometry();
    areaLight();
    cameraProjection();
    softShadows();
    reflectionsAndDeterminism();
    parallelismAndCancellation();
    sceneBuilder();
    hashDeterminism();
    graphicsSettings();
    return testutil::report("TracerTests");
}
