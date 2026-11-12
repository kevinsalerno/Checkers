// Surface material. Colours are linear RGB in 0..1.
// Procedural patterns are evaluated from the hit point, so wood and
// marble never need textures.
#pragma once

#include <cmath>

#include "vec3.hpp"

namespace rt {

struct Material {
    enum class Pattern { None, Wood, Marble, Checker };

    Vec3 diffuse;
    Vec3 altDiffuse;
    Pattern pattern = Pattern::None;
    // Blinn-Phong specular strength (white highlight).
    double specular = 0.15;
    double shininess = 24;
    // Fresnel base reflectance for recursive reflections.
    double reflectivity = 0.0;

    Material() = default;
    Material(const Vec3& d, double spec, double shine, double refl)
        : diffuse(d), altDiffuse(d), specular(spec), shininess(shine), reflectivity(refl) {}
    Material(const Vec3& d, const Vec3& alt, Pattern p, double spec, double shine, double refl)
        : diffuse(d), altDiffuse(alt), pattern(p), specular(spec), shininess(shine),
          reflectivity(refl) {}

    static Material matte(const Vec3& d) { return {d, 0.15, 24, 0.0}; }
    static Material polished(const Vec3& d, double refl) { return {d, 0.7, 90, refl}; }
    static Material wood(const Vec3& light, const Vec3& dark) {
        return {light, dark, Pattern::Wood, 0.45, 60, 0.25};
    }
    static Material marble(const Vec3& light, const Vec3& vein) {
        return {light, vein, Pattern::Marble, 0.5, 70, 0.3};
    }

    // Diffuse colour at a surface point, with the pattern applied.
    Vec3 diffuseAt(const Vec3& p) const {
        switch (pattern) {
            case Pattern::Wood: {
                double grain = std::sin((p.x * 1.1 + p.z * 0.7) * 2.0 +
                                        1.6 * std::sin(p.z * 2.5 + p.x * 1.5));
                double rings = std::sin((p.x + p.z) * 3.5 + grain * 2.0);
                double t = 0.5 + 0.5 * rings * grain;
                return diffuse * (1 - t * 0.55) + altDiffuse * (t * 0.55);
            }
            case Pattern::Marble: {
                double v = std::sin(p.x * 3.1 + p.y * 2.3 + p.z * 2.7 +
                                    2.0 * std::sin(p.x * 5.0 + p.z * 4.0));
                double t = std::pow(std::fabs(v), 6.0);
                return diffuse * (1 - t * 0.6) + altDiffuse * (t * 0.6);
            }
            case Pattern::Checker: {
                int cx = static_cast<int>(std::floor(p.x + 4.0));
                int cz = static_cast<int>(std::floor(p.z + 4.0));
                return ((cx + cz) % 2 == 0) ? diffuse : altDiffuse;
            }
            default:
                return diffuse;
        }
    }
};

}  // namespace rt
