/** A ray with a normalised direction. */
public final class Ray {
    public final Vec3 origin;
    public final Vec3 dir;

    public Ray(Vec3 origin, Vec3 dir) {
        this.origin = origin;
        this.dir = dir.normalize();
    }

    public Vec3 at(double t) {
        return origin.add(dir.scale(t));
    }
}
