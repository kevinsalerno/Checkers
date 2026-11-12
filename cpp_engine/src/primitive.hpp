// Ray-intersectable surfaces. Materials are owned by the caller (the
// SceneBuilder keeps them alive in the Scene); Hit stores a pointer.
#pragma once

#include <cmath>
#include <memory>
#include <optional>
#include <vector>

#include "hit.hpp"
#include "ray.hpp"

namespace rt {

struct Primitive {
    virtual ~Primitive() = default;
    // Nearest hit with t in (tMin, tMax), or nullopt.
    virtual std::optional<Hit> intersect(const Ray& ray, double tMin, double tMax) const = 0;
};

struct Sphere final : Primitive {
    Vec3 center;
    double radius;
    const Material* material;
    Sphere(const Vec3& c, double r, const Material* m) : center(c), radius(r), material(m) {}
    std::optional<Hit> intersect(const Ray& ray, double tMin, double tMax) const override {
        Vec3 oc = ray.origin - center;
        double a = ray.dir.dot(ray.dir);
        double b = oc.dot(ray.dir);
        double c = oc.dot(oc) - radius * radius;
        double disc = b * b - a * c;
        if (disc <= 0) return std::nullopt;
        double sq = std::sqrt(disc);
        double t = (-b - sq) / a;
        if (t <= tMin || t >= tMax) {
            t = (-b + sq) / a;
            if (t <= tMin || t >= tMax) return std::nullopt;
        }
        Vec3 p = ray.at(t);
        return Hit(t, p, (p - center) * (1.0 / radius), material);
    }
};

// A rectangular plane patch. Used for the table, board frame and board
// surface. The normal is flipped to face the incoming ray, so patches
// shade correctly from either side.
struct BoundedPlane final : Primitive {
    Vec3 point, normal, uAxis, vAxis;
    double halfU, halfV;
    const Material* material;
    BoundedPlane(const Vec3& pt, const Vec3& n, const Vec3& u, const Vec3& v, double hu,
                 double hv, const Material* m)
        : point(pt),
          normal(n.normalized()),
          uAxis(u.normalized()),
          vAxis(v.normalized()),
          halfU(hu),
          halfV(hv),
          material(m) {}

    static BoundedPlane horizontal(double y, double half, const Material* m) {
        return BoundedPlane({0, y, 0}, {0, 1, 0}, {1, 0, 0}, {0, 0, 1}, half, half, m);
    }

    std::optional<Hit> intersect(const Ray& ray, double tMin, double tMax) const override {
        double denom = ray.dir.dot(normal);
        if (std::fabs(denom) < 1e-9) return std::nullopt;
        double t = (point - ray.origin).dot(normal) / denom;
        if (t <= tMin || t >= tMax) return std::nullopt;
        Vec3 p = ray.at(t);
        Vec3 d = p - point;
        double u = d.dot(uAxis), v = d.dot(vAxis);
        if (std::fabs(u) > halfU || std::fabs(v) > halfV) return std::nullopt;
        Vec3 n = denom < 0 ? normal : normal * -1;
        return Hit(t, p, n, material);
    }
};

// Finite cylinder along the Y axis, with flat caps.
struct Cylinder final : Primitive {
    double cx, baseY, cz, radius, height;
    const Material* material;
    Cylinder(double cx_, double baseY_, double cz_, double r, double h, const Material* m)
        : cx(cx_), baseY(baseY_), cz(cz_), radius(r), height(h), material(m) {}

    std::optional<Hit> cap(const Ray& ray, double y, const Vec3& capNormal, double tMin,
                           double tMax) const {
        if (std::fabs(ray.dir.y) < 1e-9) return std::nullopt;
        double t = (y - ray.origin.y) / ray.dir.y;
        if (t <= tMin || t >= tMax) return std::nullopt;
        Vec3 p = ray.at(t);
        double dx = p.x - cx, dz = p.z - cz;
        if (dx * dx + dz * dz > radius * radius) return std::nullopt;
        Vec3 n = ray.dir.dot(capNormal) < 0 ? capNormal : capNormal * -1;
        return Hit(t, p, n, material);
    }

    std::optional<Hit> intersect(const Ray& ray, double tMin, double tMax) const override {
        std::optional<Hit> best;
        // Side wall: quadratic in x/z only.
        double ox = ray.origin.x - cx, oz = ray.origin.z - cz;
        double a = ray.dir.x * ray.dir.x + ray.dir.z * ray.dir.z;
        if (a > 1e-12) {
            double b = ox * ray.dir.x + oz * ray.dir.z;
            double c = ox * ox + oz * oz - radius * radius;
            double disc = b * b - a * c;
            if (disc > 0) {
                double sq = std::sqrt(disc);
                double ts[2] = {(-b - sq) / a, (-b + sq) / a};
                for (double t : ts) {
                    if (t > tMin && t < tMax) {
                        Vec3 p = ray.at(t);
                        if (p.y >= baseY && p.y <= baseY + height) {
                            Vec3 n = Vec3(p.x - cx, 0, p.z - cz) * (1.0 / radius);
                            best = Hit(t, p, n, material);
                            break;
                        }
                    }
                }
            }
        }
        double top = best ? best->t : tMax;
        if (auto bottom = cap(ray, baseY, Vec3(0, -1, 0), tMin, top)) {
            best = bottom;
            top = bottom->t;
        }
        if (auto topHit = cap(ray, baseY + height, Vec3(0, 1, 0), tMin, top)) best = topHit;
        return best;
    }
};

struct PrimitiveList final : Primitive {
    std::vector<std::unique_ptr<Primitive>> items;
    void add(std::unique_ptr<Primitive> p) { items.push_back(std::move(p)); }
    std::optional<Hit> intersect(const Ray& ray, double tMin, double tMax) const override {
        std::optional<Hit> best;
        double limit = tMax;
        for (const auto& p : items) {
            if (auto h = p->intersect(ray, tMin, limit)) {
                if (!best || h->t < best->t) {
                    best = h;
                    limit = h->t;
                }
            }
        }
        return best;
    }
};

}  // namespace rt
