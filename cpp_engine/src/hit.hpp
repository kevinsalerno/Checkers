// Closest-ray-hit record.
#pragma once

#include "material.hpp"
#include "vec3.hpp"

namespace rt {

struct Hit {
    double t = 0;
    Vec3 point;
    Vec3 normal;
    const Material* material = nullptr;
    Hit() = default;
    Hit(double t_, const Vec3& p, const Vec3& n, const Material* m)
        : t(t_), point(p), normal(n), material(m) {}
};

}  // namespace rt
