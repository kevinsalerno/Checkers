// Circular area light facing downwards. Soft shadows come from tracing
// one shadow ray to each of several deterministic sample points on the
// disc: a point lit by only part of the disc sits in the penumbra.
// Sample points use a golden-angle spiral, rotated per primary ray so
// neighbouring pixels do not share identical shadow patterns.
#pragma once

#include <cmath>
#include <vector>

#include "vec3.hpp"
#include "math_const.hpp"

namespace rt {

struct AreaLight {
    Vec3 center;
    double radius = 1.0;
    Vec3 color{1, 1, 1};
    double intensity = 1.0;

    AreaLight() = default;
    AreaLight(const Vec3& c, double r, const Vec3& col, double i)
        : center(c), radius(r), color(col), intensity(i) {}

    std::vector<Vec3> samplePoints(int count, double rotation) const {
        std::vector<Vec3> points;
        points.reserve(static_cast<size_t>(count));
        constexpr double kGolden = 2.39996322972865332;
        for (int i = 0; i < count; i++) {
            double r = radius * std::sqrt((i + 0.5) / count);
            double theta = rotation + i * kGolden;
            points.emplace_back(center.x + r * std::cos(theta), center.y,
                                center.z + r * std::sin(theta));
        }
        return points;
    }

    // Moves the light on a sphere around the board centre.
    AreaLight moved(double azimuthDeg, double elevationDeg, double distance) const {
        double az = azimuthDeg * kPi / 180.0;
        double el = elevationDeg * kPi / 180.0;
        double x = distance * std::cos(el) * std::sin(az);
        double z = distance * std::cos(el) * std::cos(az);
        double y = distance * std::sin(el);
        return {Vec3(x, y < 1.5 ? 1.5 : y, z), radius, color, intensity};
    }

    double azimuthDeg() const { return std::atan2(center.x, center.z) * 180.0 / kPi; }
    double elevationDeg() const {
        double horiz = std::sqrt(center.x * center.x + center.z * center.z);
        return std::atan2(center.y, horiz) * 180.0 / kPi;
    }
    double distance() const { return center.length(); }
};

}  // namespace rt
