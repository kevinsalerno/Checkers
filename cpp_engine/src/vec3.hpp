// Immutable 3-vector of doubles. Linear colour and geometry share it.
#pragma once

#include <cmath>

namespace rt {

struct Vec3 {
    double x = 0, y = 0, z = 0;
    Vec3() = default;
    Vec3(double x_, double y_, double z_) : x(x_), y(y_), z(z_) {}

    Vec3 operator+(const Vec3& o) const { return {x + o.x, y + o.y, z + o.z}; }
    Vec3 operator-(const Vec3& o) const { return {x - o.x, y - o.y, z - o.z}; }
    Vec3 operator*(double s) const { return {x * s, y * s, z * s}; }
    Vec3 mul(const Vec3& o) const { return {x * o.x, y * o.y, z * o.z}; }
    double dot(const Vec3& o) const { return x * o.x + y * o.y + z * o.z; }
    Vec3 cross(const Vec3& o) const {
        return {y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x};
    }
    double length() const { return std::sqrt(x * x + y * y + z * z); }
    Vec3 normalized() const {
        double len = length();
        return len == 0 ? *this : (*this) * (1.0 / len);
    }
    Vec3 clamp01() const {
        auto cl = [](double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); };
        return {cl(x), cl(y), cl(z)};
    }
    static Vec3 reflect(const Vec3& d, const Vec3& n) { return d - n * (2 * d.dot(n)); }
};

}  // namespace rt
