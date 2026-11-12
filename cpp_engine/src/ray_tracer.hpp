// Whitted-style ray tracer: Lambert diffuse + Blinn-Phong specular,
// soft shadows from an area light, and recursive reflections with a
// Schlick Fresnel term. Sampling is deterministic per pixel, so the
// same scene, camera and settings always produce the same image
// regardless of thread scheduling.
//
// CPU-only: plain C++17 with std::thread workers (see renderer.hpp).
// No OpenGL/Vulkan/DirectX or any hardware acceleration is used.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "camera.hpp"
#include "scene.hpp"
#include "math_const.hpp"

namespace rt {

struct RenderSettings {
    // Anti-aliasing grid per pixel side (1, 2 or 3).
    int aaAxis = 2;
    // Shadow rays per shading point (1 = hard shadows).
    int shadowSamples = 8;
    // Maximum reflection bounces.
    int maxDepth = 2;

    int samplesPerPixel() const { return aaAxis * aaAxis; }
};

inline const RenderSettings kPreview{1, 4, 1};
inline const RenderSettings kBalanced{2, 8, 2};
inline const RenderSettings kHigh{2, 16, 3};
inline const RenderSettings kUltra{3, 24, 3};

// Deterministic hash in [0,1) from integer inputs. Bit-compatible with
// the Java port's hash01: 32-bit wrapping arithmetic plus Java's
// arithmetic (sign-extending) right shift, both emulated portably
// (no reliance on C++ signed-shift semantics).
inline double hash01(int a, int b, int c) {
    auto toU = [](int v) { return static_cast<uint32_t>(v); };
    // Java's >> on the signed interpretation of v.
    auto arithShift = [](uint32_t v, int s) -> uint32_t {
        uint32_t logical = v >> s;
        if (v & 0x80000000u) logical |= (0xFFFFFFFFu << (32 - s));
        return logical;
    };
    uint32_t h = toU(a) * 374761393u + toU(b) * 668265263u + toU(c) * 974634239u;
    h = (h ^ arithShift(h, 13)) * 1274126177u;
    h = h ^ arithShift(h, 16);
    return (h & 0x7fffffffu) / 2147483648.0;
}

class RayTracer {
public:
    explicit RayTracer(const Scene* scene) : scene_(scene) {}

    Vec3 shade(const Ray& ray, int depth, double rotation, int shadowSamples, int maxDepth) const {
        auto hit = scene_->trace(ray, 1e-4, HUGE_VAL);
        if (!hit) return scene_->skyColor(ray.dir);
        Vec3 diffuse = hit->material->diffuseAt(hit->point);
        Vec3 n = hit->normal;
        Vec3 view = ray.dir * -1;
        const AreaLight& light = scene_->light();

        // Soft shadow: fraction of the disc visible from the hit point.
        double visibility = 0;
        Vec3 lightAccum(0, 0, 0);
        auto samples = light.samplePoints(std::max(1, shadowSamples), rotation);
        for (const Vec3& sample : samples) {
            Vec3 toLight = sample - hit->point;
            double dist = toLight.length();
            Vec3 l = toLight * (1.0 / dist);
            double ndotl = std::max(0.0, n.dot(l));
            if (ndotl <= 0) continue;
            bool blocked = scene_->occluded(hit->point + n * 1e-3, l, dist);
            if (!blocked) {
                visibility += 1.0;
                // Mild distance falloff keeps near pieces bright
                // without drowning the far side in darkness.
                double falloff = 1.0 / (1.0 + 0.012 * dist * dist);
                lightAccum = lightAccum + light.color * (ndotl * falloff);
            }
        }
        visibility /= samples.size();
        Vec3 direct = diffuse * scene_->ambient();
        Vec3 diffuseTerm = diffuse.mul(lightAccum) * (light.intensity / samples.size());
        direct = direct + diffuseTerm;
        // Specular highlight, softened by the same visibility
        // fraction so highlights fade inside the penumbra.
        Vec3 toCenter = (light.center - hit->point).normalized();
        double ndotlC = std::max(0.0, n.dot(toCenter));
        if (ndotlC > 0) {
            Vec3 h = (toCenter + view).normalized();
            double spec = std::pow(std::max(0.0, n.dot(h)), hit->material->shininess) *
                          hit->material->specular * visibility * ndotlC;
            direct = direct + light.color * spec;
        }
        // Recursive reflection with Schlick Fresnel.
        double f0 = hit->material->reflectivity;
        if (f0 > 0 && depth < maxDepth) {
            double cosTheta = std::max(0.0, n.dot(view));
            double f = f0 + (1 - f0) * std::pow(1 - cosTheta, 5);
            Vec3 reflDir = Vec3::reflect(ray.dir, n);
            Vec3 reflected =
                shade(Ray(hit->point + n * 1e-3, reflDir), depth + 1, rotation * 1.7 + 0.31,
                      shadowSamples, maxDepth);
            return direct * (1 - f) + reflected * f;
        }
        return direct;
    }

    // Shades one pixel, averaging stratified AA samples.
    Vec3 shadePixel(const Camera& camera, int x, int y, int width, int height,
                    const RenderSettings& settings) const {
        Vec3 accum(0, 0, 0);
        int n = settings.aaAxis;
        for (int sy = 0; sy < n; sy++)
            for (int sx = 0; sx < n; sx++) {
                double jx = hash01(x, y, sx * 7 + sy * 13 + 1);
                double jy = hash01(x, y, sx * 11 + sy * 5 + 2);
                double px = x + (sx + jx) / n;
                double py = y + (sy + jy) / n;
                double rotation = hash01(x, y, 99) * kPi * 2;
                accum = accum + shade(camera.rayForPixel(px, py, width, height), 0, rotation,
                                      settings.shadowSamples, settings.maxDepth);
            }
        return accum * (1.0 / (n * n));
    }

    // Linear colour to packed sRGB int (0xFFRRGGBB).
    static uint32_t toRgb(const Vec3& linear) {
        Vec3 c = linear.clamp01();
        auto enc = [](double v) {
            return static_cast<uint32_t>(std::pow(v, 1.0 / 2.2) * 255 + 0.5);
        };
        return (0xFFu << 24) | (enc(c.x) << 16) | (enc(c.y) << 8) | enc(c.z);
    }

private:
    const Scene* scene_;
};

}  // namespace rt
