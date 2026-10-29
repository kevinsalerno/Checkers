/** Finite cylinder along the Y axis, with flat caps. */
public final class Cylinder implements Primitive {
    private final double cx;
    private final double baseY;
    private final double cz;
    private final double radius;
    private final double height;
    private final Material material;

    public Cylinder(double cx, double baseY, double cz, double radius,
                    double height, Material material) {
        this.cx = cx;
        this.baseY = baseY;
        this.cz = cz;
        this.radius = radius;
        this.height = height;
        this.material = material;
    }

    private Hit cap(Ray ray, double y, Vec3 capNormal, double tMin, double tMax) {
        if (Math.abs(ray.dir.y) < 1e-9) {
            return null;
        }
        double t = (y - ray.origin.y) / ray.dir.y;
        if (t <= tMin || t >= tMax) {
            return null;
        }
        Vec3 p = ray.at(t);
        double dx = p.x - cx;
        double dz = p.z - cz;
        if (dx * dx + dz * dz > radius * radius) {
            return null;
        }
        Vec3 n = ray.dir.dot(capNormal) < 0 ? capNormal : capNormal.scale(-1);
        return new Hit(t, p, n, material);
    }

    @Override
    public Hit intersect(Ray ray, double tMin, double tMax) {
        Hit best = null;
        // Side wall: quadratic in x/z only.
        double ox = ray.origin.x - cx;
        double oz = ray.origin.z - cz;
        double a = ray.dir.x * ray.dir.x + ray.dir.z * ray.dir.z;
        if (a > 1e-12) {
            double b = ox * ray.dir.x + oz * ray.dir.z;
            double c = ox * ox + oz * oz - radius * radius;
            double disc = b * b - a * c;
            if (disc > 0) {
                double sq = Math.sqrt(disc);
                for (double t : new double[] { (-b - sq) / a, (-b + sq) / a }) {
                    if (t > tMin && t < tMax) {
                        Vec3 p = ray.at(t);
                        if (p.y >= baseY && p.y <= baseY + height) {
                            Vec3 n = new Vec3(p.x - cx, 0, p.z - cz)
                                    .scale(1.0 / radius);
                            Hit h = new Hit(t, p, n, material);
                            if (best == null || t < best.t) {
                                best = h;
                            }
                            break;
                        }
                    }
                }
            }
        }
        double top = best == null ? tMax : best.t;
        Hit bottom = cap(ray, baseY, new Vec3(0, -1, 0), tMin, top);
        if (bottom != null) {
            best = bottom;
            top = bottom.t;
        }
        Hit topHit = cap(ray, baseY + height, new Vec3(0, 1, 0), tMin, top);
        if (topHit != null) {
            best = topHit;
        }
        return best;
    }
}
