// Orbit camera. Yaw 0 puts the eye on the +Z side (the White side of
// the board); pitch is the elevation above the board plane.
#pragma once

#include <algorithm>
#include <cmath>
#include <optional>
#include <tuple>

#include "ray.hpp"
#include "vec3.hpp"
#include "math_const.hpp"

namespace rt {

class Camera {
public:
    Camera(const Vec3& target, double yawDeg, double pitchDeg, double distance, double fovDeg)
        : target_(target), yawDeg_(yawDeg), pitchDeg_(pitchDeg), distance_(distance),
          fovDeg_(fovDeg) {}

    static Camera defaultView() { return {{0, 0.1, 0}, 0, 38, 11.5, 42}; }

    Vec3 eye() const {
        double yaw = yawDeg_ * kPi / 180.0;
        double pitch = pitchDeg_ * kPi / 180.0;
        double cp = std::cos(pitch);
        return {target_.x + distance_ * cp * std::sin(yaw),
                target_.y + distance_ * std::sin(pitch),
                target_.z + distance_ * cp * std::cos(yaw)};
    }

    Vec3 forward() const { return (target_ - eye()).normalized(); }
    Vec3 right() const { return forward().cross(Vec3(0, 1, 0)).normalized(); }
    Vec3 up() const { return right().cross(forward()).normalized(); }

    // Primary ray through sub-pixel position (px, py).
    Ray rayForPixel(double px, double py, int width, int height) const {
        double aspect = static_cast<double>(width) / height;
        double tanHalf = std::tan(fovDeg_ * kPi / 360.0);
        double ndcX = (2.0 * px / width - 1.0) * aspect * tanHalf;
        double ndcY = (1.0 - 2.0 * py / height) * tanHalf;
        Vec3 dir = forward() + right() * ndcX + up() * ndcY;
        return {eye(), dir};
    }

    // Projects a world point to pixel coordinates {sx, sy, depth},
    // or nullopt when behind the camera. Used for 2D overlays on the
    // ray-traced image.
    std::optional<std::tuple<double, double, double>> project(const Vec3& p, int width,
                                                              int height) const {
        Vec3 d = p - eye();
        Vec3 fwd = forward();
        double zc = d.dot(fwd);
        if (zc <= 0.01) return std::nullopt;
        double aspect = static_cast<double>(width) / height;
        double tanHalf = std::tan(fovDeg_ * kPi / 360.0);
        double sx = (0.5 + 0.5 * d.dot(right()) / (zc * tanHalf * aspect)) * width;
        double sy = (0.5 - 0.5 * d.dot(up()) / (zc * tanHalf)) * height;
        return std::make_tuple(sx, sy, zc);
    }

    // Ray through a pixel centre (for mouse picking).
    Ray pickRay(int px, int py, int width, int height) const {
        return rayForPixel(px + 0.5, py + 0.5, width, height);
    }

    const Vec3& target() const { return target_; }
    double yawDeg() const { return yawDeg_; }
    double pitchDeg() const { return pitchDeg_; }
    double distance() const { return distance_; }
    double fovDeg() const { return fovDeg_; }

    void orbit(double dYaw, double dPitch) {
        yawDeg_ += dYaw;
        setPitch(pitchDeg_ + dPitch);
    }
    void zoom(double factor) { setDistance(distance_ * factor); }
    void setYaw(double v) { yawDeg_ = v; }
    void setPitch(double v) { pitchDeg_ = std::max(8.0, std::min(80.0, v)); }
    void setDistance(double v) { distance_ = std::max(5.5, std::min(22.0, v)); }

private:
    Vec3 target_;
    double yawDeg_, pitchDeg_, distance_, fovDeg_;
};

}  // namespace rt
