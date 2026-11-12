// A scene: primitives, one area light, ambient light, sky colours.
#pragma once

#include <algorithm>
#include <memory>
#include <vector>

#include "area_light.hpp"
#include "hit.hpp"
#include "primitive.hpp"
#include "ray.hpp"

namespace rt {

class Scene {
public:
    explicit Scene(const AreaLight& light) : light_(light) {}

    // Primitives are appended; materials must outlive the scene
    // (SceneBuilder stores them alongside).
    void add(std::unique_ptr<Primitive> p) { prims_.add(std::move(p)); }
    const PrimitiveList& primitives() const { return prims_; }
    size_t primitiveCount() const { return prims_.items.size(); }

    const AreaLight& light() const { return light_; }
    void setLight(const AreaLight& l) { light_ = l; }
    double ambient() const { return ambient_; }
    void setAmbient(double a) { ambient_ = a; }

    // Nearest hit, or nullopt.
    std::optional<Hit> trace(const Ray& ray, double tMin, double tMax) const {
        return prims_.intersect(ray, tMin, tMax);
    }

    // True when any surface blocks the segment to distance maxDist.
    bool occluded(const Vec3& origin, const Vec3& dir, double maxDist) const {
        return trace(Ray(origin, dir), 1e-4, maxDist - 1e-3).has_value();
    }

    Vec3 skyColor(const Vec3& dir) const {
        double t = 0.5 + 0.5 * std::max(-1.0, std::min(1.0, dir.y));
        return skyBottom_ * (1 - t) + skyTop_ * t;
    }

private:
    PrimitiveList prims_;
    AreaLight light_;
    double ambient_ = 0.14;
    const Vec3 skyTop_{0.16, 0.22, 0.34};
    const Vec3 skyBottom_{0.52, 0.55, 0.60};
};

}  // namespace rt
