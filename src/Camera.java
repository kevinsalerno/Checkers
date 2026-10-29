/**
 * Orbit camera. Yaw 0 puts the eye on the +Z side (the White side of
 * the board); pitch is the elevation above the board plane.
 */
public final class Camera {
    private Vec3 target;
    private double yawDeg;
    private double pitchDeg;
    private double distance;
    private double fovDeg;

    public Camera(Vec3 target, double yawDeg, double pitchDeg,
                  double distance, double fovDeg) {
        this.target = target;
        this.yawDeg = yawDeg;
        this.pitchDeg = pitchDeg;
        this.distance = distance;
        this.fovDeg = fovDeg;
    }

    public static Camera defaultView() {
        return new Camera(new Vec3(0, 0.1, 0), 0, 38, 11.5, 42);
    }

    public Camera copy() {
        return new Camera(target, yawDeg, pitchDeg, distance, fovDeg);
    }

    public Vec3 eye() {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch);
        return new Vec3(
                target.x + distance * cp * Math.sin(yaw),
                target.y + distance * Math.sin(pitch),
                target.z + distance * cp * Math.cos(yaw));
    }

    public Vec3 forward() {
        return target.sub(eye()).normalize();
    }

    public Vec3 right() {
        return forward().cross(new Vec3(0, 1, 0)).normalize();
    }

    public Vec3 up() {
        return right().cross(forward()).normalize();
    }

    /** Primary ray through sub-pixel position (x + fx, y + fy). */
    public Ray rayForPixel(double px, double py, int width, int height) {
        double aspect = (double) width / height;
        double tanHalf = Math.tan(Math.toRadians(fovDeg) / 2.0);
        double ndcX = (2.0 * px / width - 1.0) * aspect * tanHalf;
        double ndcY = (1.0 - 2.0 * py / height) * tanHalf;
        Vec3 dir = forward().add(right().scale(ndcX)).add(up().scale(ndcY)).normalize();
        return new Ray(eye(), dir);
    }

    /**
     * Projects a world point to pixel coordinates {sx, sy, depth},
     * or null when behind the camera. Used for 2D overlays on the
     * ray-traced image.
     */
    public double[] project(Vec3 p, int width, int height) {
        Vec3 d = p.sub(eye());
        double zc = d.dot(forward());
        if (zc <= 0.01) {
            return null;
        }
        double aspect = (double) width / height;
        double tanHalf = Math.tan(Math.toRadians(fovDeg) / 2.0);
        double sx = (0.5 + 0.5 * d.dot(right()) / (zc * tanHalf * aspect)) * width;
        double sy = (0.5 - 0.5 * d.dot(up()) / (zc * tanHalf)) * height;
        return new double[] { sx, sy, zc };
    }

    /** Ray through a pixel centre (for mouse picking). */
    public Ray pickRay(int px, int py, int width, int height) {
        return rayForPixel(px + 0.5, py + 0.5, width, height);
    }

    public Vec3 target() {
        return target;
    }

    public double yawDeg() {
        return yawDeg;
    }

    public double pitchDeg() {
        return pitchDeg;
    }

    public double distance() {
        return distance;
    }

    public double fovDeg() {
        return fovDeg;
    }

    public void orbit(double dYaw, double dPitch) {
        yawDeg += dYaw;
        pitchDeg = Math.max(8, Math.min(80, pitchDeg + dPitch));
    }

    public void zoom(double factor) {
        distance = Math.max(5.5, Math.min(22, distance * factor));
    }

    public void setYaw(double yawDeg) {
        this.yawDeg = yawDeg;
    }

    public void setPitch(double pitchDeg) {
        this.pitchDeg = Math.max(8, Math.min(80, pitchDeg));
    }

    public void setDistance(double distance) {
        this.distance = Math.max(5.5, Math.min(22, distance));
    }
}
