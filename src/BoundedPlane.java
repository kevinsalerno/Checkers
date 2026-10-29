/**
 * A rectangular plane patch. Used for the table, board frame and board
 * surface. The normal is flipped to face the incoming ray, so patches
 * shade correctly from either side.
 */
public final class BoundedPlane implements Primitive {
    private final Vec3 point;
    private final Vec3 normal;
    private final Vec3 uAxis;
    private final Vec3 vAxis;
    private final double halfU;
    private final double halfV;
    private final Material material;

    public BoundedPlane(Vec3 point, Vec3 normal, Vec3 uAxis, Vec3 vAxis,
                        double halfU, double halfV, Material material) {
        this.point = point;
        this.normal = normal.normalize();
        this.uAxis = uAxis.normalize();
        this.vAxis = vAxis.normalize();
        this.halfU = halfU;
        this.halfV = halfV;
        this.material = material;
    }

    /** Horizontal patch at height y, spanning x/z in [-half, half]. */
    public static BoundedPlane horizontal(double y, double half, Material material) {
        return new BoundedPlane(new Vec3(0, y, 0), new Vec3(0, 1, 0),
                new Vec3(1, 0, 0), new Vec3(0, 0, 1), half, half, material);
    }

    @Override
    public Hit intersect(Ray ray, double tMin, double tMax) {
        double denom = ray.dir.dot(normal);
        if (Math.abs(denom) < 1e-9) {
            return null;
        }
        double t = point.sub(ray.origin).dot(normal) / denom;
        if (t <= tMin || t >= tMax) {
            return null;
        }
        Vec3 p = ray.at(t);
        Vec3 d = p.sub(point);
        double u = d.dot(uAxis);
        double v = d.dot(vAxis);
        if (Math.abs(u) > halfU || Math.abs(v) > halfV) {
            return null;
        }
        Vec3 n = denom < 0 ? normal : normal.scale(-1);
        return new Hit(t, p, n, material);
    }
}
