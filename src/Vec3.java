/** Immutable 3-vector of doubles. Linear colour and geometry share it. */
public final class Vec3 {
    public final double x;
    public final double y;
    public final double z;

    public Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 scale(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public Vec3 mul(Vec3 o) {
        return new Vec3(x * o.x, y * o.y, z * o.z);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public Vec3 normalize() {
        double len = length();
        if (len == 0) {
            return this;
        }
        return scale(1.0 / len);
    }

    public Vec3 clamp01() {
        return new Vec3(clamp(x), clamp(y), clamp(z));
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    public static Vec3 reflect(Vec3 d, Vec3 n) {
        return d.sub(n.scale(2 * d.dot(n)));
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
