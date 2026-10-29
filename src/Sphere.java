public final class Sphere implements Primitive {
    private final Vec3 center;
    private final double radius;
    private final Material material;

    public Sphere(Vec3 center, double radius, Material material) {
        this.center = center;
        this.radius = radius;
        this.material = material;
    }

    @Override
    public Hit intersect(Ray ray, double tMin, double tMax) {
        Vec3 oc = ray.origin.sub(center);
        double a = ray.dir.dot(ray.dir);
        double b = oc.dot(ray.dir);
        double c = oc.dot(oc) - radius * radius;
        double disc = b * b - a * c;
        if (disc <= 0) {
            return null;
        }
        double sq = Math.sqrt(disc);
        double t = (-b - sq) / a;
        if (t <= tMin || t >= tMax) {
            t = (-b + sq) / a;
            if (t <= tMin || t >= tMax) {
                return null;
            }
        }
        Vec3 p = ray.at(t);
        return new Hit(t, p, p.sub(center).scale(1.0 / radius), material);
    }
}
