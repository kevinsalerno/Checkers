// A ray with a normalised direction.
#pragma once

#include "vec3.hpp"

namespace rt {

struct Ray {
    Vec3 origin;
    Vec3 dir;
    Ray() = default;
    Ray(const Vec3& o, const Vec3& d) : origin(o), dir(d.normalized()) {}
    Vec3 at(double t) const { return origin + dir * t; }
};

}  // namespace rt
