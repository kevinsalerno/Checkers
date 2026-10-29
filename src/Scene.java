import java.util.ArrayList;
import java.util.List;

/** A scene: primitives, one area light, ambient light, sky colours. */
public final class Scene {
    private final List<Primitive> primitives = new ArrayList<Primitive>();
    private AreaLight light;
    private double ambient = 0.14;
    private final Vec3 skyTop = new Vec3(0.16, 0.22, 0.34);
    private final Vec3 skyBottom = new Vec3(0.52, 0.55, 0.60);

    public Scene(AreaLight light) {
        this.light = light;
    }

    public void add(Primitive p) {
        primitives.add(p);
    }

    public List<Primitive> primitives() {
        return primitives;
    }

    public AreaLight light() {
        return light;
    }

    public void setLight(AreaLight light) {
        this.light = light;
    }

    public double ambient() {
        return ambient;
    }

    public void setAmbient(double ambient) {
        this.ambient = ambient;
    }

    /** Nearest hit, or null. */
    public Hit trace(Ray ray, double tMin, double tMax) {
        Hit best = null;
        for (Primitive p : primitives) {
            Hit h = p.intersect(ray, tMin, tMax);
            if (h != null && (best == null || h.t < best.t)) {
                best = h;
                tMax = h.t;
            }
        }
        return best;
    }

    /** True when any surface blocks the segment to distance maxDist. */
    public boolean occluded(Vec3 origin, Vec3 dir, double maxDist) {
        Ray ray = new Ray(origin, dir);
        return trace(ray, 1e-4, maxDist - 1e-3) != null;
    }

    public Vec3 skyColor(Vec3 dir) {
        double t = 0.5 + 0.5 * Math.max(-1, Math.min(1, dir.y));
        return skyBottom.scale(1 - t).add(skyTop.scale(t));
    }
}
