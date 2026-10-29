/**
 * Whitted-style ray tracer: Lambert diffuse + Blinn-Phong specular,
 * soft shadows from an area light, and recursive reflections with a
 * Schlick Fresnel term. Sampling is deterministic per pixel, so the
 * same scene, camera and settings always produce the same image
 * regardless of thread scheduling.
 */
public final class RayTracer {
    /** Render quality settings. */
    public static final class Settings {
        /** Anti-aliasing grid per pixel side (1, 2 or 4). */
        public final int aaAxis;
        /** Shadow rays per shading point. */
        public final int shadowSamples;
        /** Maximum reflection bounces. */
        public final int maxDepth;

        public Settings(int aaAxis, int shadowSamples, int maxDepth) {
            this.aaAxis = aaAxis;
            this.shadowSamples = shadowSamples;
            this.maxDepth = maxDepth;
        }

        public int samplesPerPixel() {
            return aaAxis * aaAxis;
        }
    }

    public static final Settings PREVIEW = new Settings(1, 4, 1);
    public static final Settings BALANCED = new Settings(2, 8, 2);
    public static final Settings HIGH = new Settings(2, 16, 3);
    public static final Settings ULTRA = new Settings(3, 24, 3);

    private final Scene scene;

    public RayTracer(Scene scene) {
        this.scene = scene;
    }

    /** Deterministic hash in [0,1) from integer inputs. */
    static double hash01(int a, int b, int c) {
        int h = a * 374761393 + b * 668265263 + c * 974634239;
        h = (h ^ (h >> 13)) * 1274126177;
        h = h ^ (h >> 16);
        return (h & 0x7fffffff) / 2147483648.0;
    }

    public Vec3 shade(Ray ray, int depth, double rotation,
                      int shadowSamples, int maxDepth) {
        Hit hit = scene.trace(ray, 1e-4, Double.POSITIVE_INFINITY);
        if (hit == null) {
            return scene.skyColor(ray.dir);
        }
        Vec3 diffuse = hit.material.diffuseAt(hit.point);
        Vec3 n = hit.normal;
        Vec3 view = ray.dir.scale(-1);
        AreaLight light = scene.light();

        // Soft shadow: fraction of the disc visible from the hit point.
        double visibility = 0;
        Vec3 lightAccum = new Vec3(0, 0, 0);
        java.util.List<Vec3> samples = light.samplePoints(
                Math.max(1, shadowSamples), rotation);
        for (Vec3 sample : samples) {
            Vec3 toLight = sample.sub(hit.point);
            double dist = toLight.length();
            Vec3 l = toLight.scale(1.0 / dist);
            double ndotl = Math.max(0, n.dot(l));
            if (ndotl <= 0) {
                continue;
            }
            boolean blocked = scene.occluded(
                    hit.point.add(n.scale(1e-3)), l, dist);
            if (!blocked) {
                visibility += 1.0;
                // Mild distance falloff keeps near pieces bright
                // without drowning the far side in darkness.
                double falloff = 1.0 / (1.0 + 0.012 * dist * dist);
                lightAccum = lightAccum.add(light.color.scale(ndotl * falloff));
            }
        }
        visibility /= samples.size();
        Vec3 direct = diffuse.scale(scene.ambient());
        if (diffuse != null) {
            Vec3 diffuseTerm = diffuse.mul(lightAccum)
                    .scale(light.intensity / samples.size());
            direct = direct.add(diffuseTerm);
            // Specular highlight, softened by the same visibility
            // fraction so highlights fade inside the penumbra.
            Vec3 toCenter = light.center.sub(hit.point).normalize();
            double ndotlC = Math.max(0, n.dot(toCenter));
            if (ndotlC > 0) {
                Vec3 h = toCenter.add(view).normalize();
                double spec = Math.pow(Math.max(0, n.dot(h)), hit.material.shininess)
                        * hit.material.specular * visibility * ndotlC;
                direct = direct.add(light.color.scale(spec));
            }
        }
        // Recursive reflection with Schlick Fresnel.
        double f0 = hit.material.reflectivity;
        if (f0 > 0 && depth < maxDepth) {
            double cosTheta = Math.max(0, n.dot(view));
            double f = f0 + (1 - f0) * Math.pow(1 - cosTheta, 5);
            Vec3 reflDir = Vec3.reflect(ray.dir, n);
            Vec3 reflected = shade(new Ray(hit.point.add(n.scale(1e-3)), reflDir),
                    depth + 1, rotation * 1.7 + 0.31, shadowSamples, maxDepth);
            return direct.scale(1 - f).add(reflected.scale(f));
        }
        return direct;
    }

    /** Shades one pixel centre, averaging stratified AA samples. */
    public Vec3 shadePixel(Camera camera, int x, int y, int width, int height,
                           Settings settings) {
        Vec3 accum = new Vec3(0, 0, 0);
        int n = settings.aaAxis;
        for (int sy = 0; sy < n; sy++) {
            for (int sx = 0; sx < n; sx++) {
                double jx = hash01(x, y, sx * 7 + sy * 13 + 1);
                double jy = hash01(x, y, sx * 11 + sy * 5 + 2);
                double px = x + (sx + jx) / n;
                double py = y + (sy + jy) / n;
                double rotation = hash01(x, y, 99) * Math.PI * 2;
                accum = accum.add(shade(camera.rayForPixel(px, py, width, height),
                        0, rotation, settings.shadowSamples, settings.maxDepth));
            }
        }
        return accum.scale(1.0 / (n * n));
    }

    /** Linear colour to packed sRGB int. */
    public static int toRgb(Vec3 linear) {
        Vec3 c = linear.clamp01();
        int r = (int) (Math.pow(c.x, 1.0 / 2.2) * 255 + 0.5);
        int g = (int) (Math.pow(c.y, 1.0 / 2.2) * 255 + 0.5);
        int b = (int) (Math.pow(c.z, 1.0 / 2.2) * 255 + 0.5);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
}
