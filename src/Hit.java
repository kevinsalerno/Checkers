/** Closest-ray-hit record. */
public final class Hit {
    public final double t;
    public final Vec3 point;
    public final Vec3 normal;
    public final Material material;

    public Hit(double t, Vec3 point, Vec3 normal, Material material) {
        this.t = t;
        this.point = point;
        this.normal = normal;
        this.material = material;
    }
}
