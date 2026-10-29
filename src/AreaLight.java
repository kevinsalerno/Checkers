import java.util.ArrayList;
import java.util.List;

/**
 * Circular area light facing downwards. Soft shadows come from tracing
 * one shadow ray to each of several deterministic sample points on the
 * disc: a point lit by only part of the disc sits in the penumbra.
 * Sample points use a golden-angle spiral, rotated per primary ray so
 * neighbouring pixels do not share identical shadow patterns.
 */
public final class AreaLight {
    public final Vec3 center;
    public final double radius;
    public final Vec3 color;
    public final double intensity;

    public AreaLight(Vec3 center, double radius, Vec3 color, double intensity) {
        this.center = center;
        this.radius = radius;
        this.color = color;
        this.intensity = intensity;
    }

    public List<Vec3> samplePoints(int count, double rotation) {
        List<Vec3> points = new ArrayList<Vec3>(count);
        double golden = 2.39996322972865332;
        for (int i = 0; i < count; i++) {
            double r = radius * Math.sqrt((i + 0.5) / count);
            double theta = rotation + i * golden;
            points.add(new Vec3(center.x + r * Math.cos(theta),
                    center.y, center.z + r * Math.sin(theta)));
        }
        return points;
    }

    /** Moves the light on a sphere around the board centre. */
    public AreaLight moved(double azimuthDeg, double elevationDeg, double distance) {
        double az = Math.toRadians(azimuthDeg);
        double el = Math.toRadians(elevationDeg);
        double x = distance * Math.cos(el) * Math.sin(az);
        double z = distance * Math.cos(el) * Math.cos(az);
        double y = distance * Math.sin(el);
        return new AreaLight(new Vec3(x, Math.max(1.5, y), z), radius, color, intensity);
    }

    public double azimuthDeg() {
        return Math.toDegrees(Math.atan2(center.x, center.z));
    }

    public double elevationDeg() {
        double horiz = Math.sqrt(center.x * center.x + center.z * center.z);
        return Math.toDegrees(Math.atan2(center.y, horiz));
    }

    public double distance() {
        return center.length();
    }
}
