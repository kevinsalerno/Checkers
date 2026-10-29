import java.util.List;

/** Headless tests for geometry, soft shadows, reflections and parallelism. */
public final class RayTracerTests {
    private RayTracerTests() {
    }

    public static int run() {
        TestUtil.reset();
        geometry();
        areaLight();
        cameraProjection();
        softShadows();
        reflectionsAndDeterminism();
        parallelismAndCancellation();
        sceneBuilder();
        return TestUtil.report("RayTracerTests");
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    private static void geometry() {
        Material mat = Material.matte(new Vec3(0.5, 0.5, 0.5));
        Sphere sphere = new Sphere(new Vec3(0, 0, 0), 1, mat);
        Hit hit = sphere.intersect(new Ray(new Vec3(0, 0, -5), new Vec3(0, 0, 1)), 0, 100);
        TestUtil.check(hit != null, "tracer: sphere hit");
        TestUtil.eq(4.0, Math.round(hit.t * 1000) / 1000.0, "tracer: sphere hit distance");
        Hit miss = sphere.intersect(new Ray(new Vec3(3, 0, -5), new Vec3(0, 0, 1)), 0, 100);
        TestUtil.check(miss == null, "tracer: sphere miss");
        Cylinder disc = new Cylinder(0, 0, 0, 0.5, 0.2, mat);
        Hit top = disc.intersect(new Ray(new Vec3(0, 2, 0), new Vec3(0, -1, 0)), 0, 100);
        TestUtil.check(top != null && Math.abs(top.t - 1.8) < 1e-6,
                "tracer: cylinder top cap hit");
        Hit side = disc.intersect(new Ray(new Vec3(-2, 0.1, 0), new Vec3(1, 0, 0)), 0, 100);
        TestUtil.check(side != null, "tracer: cylinder side hit");
        Hit outside = disc.intersect(new Ray(new Vec3(2, 0.1, 0), new Vec3(1, 0, 0)), 0, 100);
        TestUtil.check(outside == null, "tracer: cylinder miss past the side");
        BoundedPlane plane = BoundedPlane.horizontal(0, 2, mat);
        Hit onPlane = plane.intersect(new Ray(new Vec3(1, 3, 1), new Vec3(0, -1, 0)), 0, 100);
        TestUtil.check(onPlane != null, "tracer: bounded plane hit inside patch");
        Hit offPlane = plane.intersect(new Ray(new Vec3(5, 3, 0), new Vec3(0, -1, 0)), 0, 100);
        TestUtil.check(offPlane == null, "tracer: bounded plane miss outside patch");
    }

    private static void areaLight() {
        AreaLight light = new AreaLight(new Vec3(0, 5, 0), 2.0,
                new Vec3(1, 1, 1), 1.0);
        List<Vec3> samples = light.samplePoints(16, 0.3);
        TestUtil.eq(16, samples.size(), "tracer: area light sample count");
        boolean inside = true;
        boolean distinct = true;
        for (int i = 0; i < samples.size(); i++) {
            Vec3 s = samples.get(i);
            double dx = s.x - light.center.x;
            double dz = s.z - light.center.z;
            if (dx * dx + dz * dz > 2.0 * 2.0 + 1e-9 || s.y != 5.0) {
                inside = false;
            }
            for (int j = i + 1; j < samples.size(); j++) {
                if (samples.get(j).sub(s).length() < 1e-9) {
                    distinct = false;
                }
            }
        }
        TestUtil.check(inside, "tracer: area light samples lie on the disc");
        TestUtil.check(distinct, "tracer: area light samples are distinct");
        AreaLight moved = light.moved(90, 60, 6);
        TestUtil.check(Math.abs(moved.azimuthDeg() - 90) < 1e-6,
                "tracer: moved light keeps its azimuth");
        TestUtil.check(Math.abs(moved.distance() - 6) < 1e-6,
                "tracer: moved light keeps its distance");
    }

    private static void cameraProjection() {
        Camera cam = Camera.defaultView();
        Ray centre = cam.rayForPixel(320.5, 240.5, 640, 480);
        TestUtil.check(centre.dir.sub(cam.forward()).length() < 0.05,
                "tracer: centre pixel ray follows the view direction");
        double[] projected = cam.project(SceneBuilder.squareCenter(3, 3), 640, 480);
        TestUtil.check(projected != null, "tracer: square centre projects in front");
        Ray picked = cam.pickRay((int) projected[0], (int) projected[1], 640, 480);
        double t = -picked.origin.y / picked.dir.y;
        Vec3 p = picked.at(t);
        Vec3 centre3 = SceneBuilder.squareCenter(3, 3);
        TestUtil.check(Math.abs(p.x - centre3.x) < 0.6 && Math.abs(p.z - centre3.z) < 0.6,
                "tracer: pick ray through a projected square lands on it");
        TestUtil.check(cam.project(new Vec3(0, 0, 100), 640, 480) == null
                || cam.project(cam.eye().add(cam.forward().scale(-1)), 640, 480) == null,
                "tracer: points behind the camera do not project");
    }

    private static int luminance(int rgb) {
        return ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
    }

    /** Renders a sphere shadow with the given light radius. */
    private static int[] renderShadow(double lightRadius) {
        Scene scene = new Scene(new AreaLight(new Vec3(1.5, 5, 0.5), lightRadius,
                new Vec3(1, 1, 1), 1.2));
        scene.add(BoundedPlane.horizontal(0, 10, Material.matte(new Vec3(0.7, 0.7, 0.7))));
        scene.add(new Sphere(new Vec3(0, 1, 0), 0.9, Material.matte(new Vec3(0.8, 0.2, 0.2))));
        Camera cam = new Camera(new Vec3(0, 0.4, 0), 0, 32, 8, 42);
        ProgressiveRenderer r = new ProgressiveRenderer();
        try {
            return r.render(scene, cam, 140, 90,
                    new RayTracer.Settings(1, 16, 1));
        } finally {
            r.shutdown();
        }
    }

    private static void softShadows() {
        int[] hard = renderShadow(0.05);
        int[] soft = renderShadow(2.2);
        // Scan the row through the shadow: count pixels strictly between
        // the darkest shadow and the brightly lit floor on that row.
        int hardMid = countPenumbra(hard, 140, 90);
        int softMid = countPenumbra(soft, 140, 90);
        TestUtil.check(softMid > 0, "tracer: area light produces a penumbra");
        TestUtil.check(softMid > hardMid,
                "tracer: larger light makes a wider penumbra (soft " + softMid
                        + " vs hard " + hardMid + ")");
    }

    private static int countPenumbra(int[] pixels, int w, int h) {
        int y = h * 2 / 3;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x = 0; x < w; x++) {
            int l = luminance(pixels[y * w + x]);
            min = Math.min(min, l);
            max = Math.max(max, l);
        }
        int lo = min + (max - min) / 4;
        int hi = min + 3 * (max - min) / 4;
        int count = 0;
        for (int x = 0; x < w; x++) {
            int l = luminance(pixels[y * w + x]);
            if (l > lo && l < hi) {
                count++;
            }
        }
        return count;
    }

    private static void reflectionsAndDeterminism() {
        Camera cam = new Camera(new Vec3(0, 0.5, 0), 0, 20, 6, 42);
        Scene matte = new Scene(SceneBuilder.defaultLight());
        matte.add(BoundedPlane.horizontal(0, 4, Material.matte(new Vec3(0.7, 0.7, 0.7))));
        matte.add(new Sphere(new Vec3(0, 1, 0), 0.8, Material.matte(new Vec3(0.8, 0.2, 0.2))));
        Scene reflective = new Scene(SceneBuilder.defaultLight());
        reflective.add(BoundedPlane.horizontal(0, 4, Material.polished(new Vec3(0.7, 0.7, 0.7), 0.0)));
        reflective.add(new Sphere(new Vec3(0, 1, 0), 0.8,
                Material.polished(new Vec3(0.8, 0.2, 0.2), 0.9)));
        ProgressiveRenderer r = new ProgressiveRenderer();
        try {
            int[] a = r.render(matte, cam, 120, 90, RayTracer.BALANCED);
            int[] b = r.render(reflective, cam, 120, 90, RayTracer.BALANCED);
            int differing = 0;
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i]) {
                    differing++;
                }
            }
            TestUtil.check(differing > 200,
                    "tracer: reflections change the image (" + differing + " pixels)");
            int[] again = r.render(reflective, cam, 120, 90, RayTracer.BALANCED);
            boolean identical = true;
            for (int i = 0; i < b.length; i++) {
                if (b[i] != again[i]) {
                    identical = false;
                }
            }
            TestUtil.check(identical, "tracer: renders are deterministic");
        } finally {
            r.shutdown();
        }
    }

    private static void parallelismAndCancellation() {
        CheckersEngine e = new CheckersEngine();
        Scene scene = SceneBuilder.buildCheckers(e);
        Camera cam = Camera.defaultView();
        ProgressiveRenderer many = new ProgressiveRenderer(8);
        ProgressiveRenderer one = new ProgressiveRenderer(1);
        try {
            int[] parallel = many.render(scene, cam, 96, 72, RayTracer.BALANCED);
            int[] serial = one.render(scene, cam, 96, 72, RayTracer.BALANCED);
            boolean identical = true;
            for (int i = 0; i < parallel.length; i++) {
                if (parallel[i] != serial[i]) {
                    identical = false;
                }
            }
            TestUtil.check(identical, "tracer: parallel and serial renders match exactly");
            final boolean[] wasCancelled = { false };
            int[] cancelled = many.render(scene, cam, 96, 72, RayTracer.BALANCED,
                    new ProgressiveRenderer.CancelCheck() {
                        @Override
                        public boolean cancelled() {
                            wasCancelled[0] = true;
                            return true;
                        }
                    });
            TestUtil.check(wasCancelled[0], "tracer: cancellation is consulted");
            TestUtil.eq(96 * 72, cancelled.length, "tracer: cancelled render still returns a buffer");
        } finally {
            many.shutdown();
            one.shutdown();
        }
    }

    private static void sceneBuilder() {
        Scene checkers = SceneBuilder.buildCheckers(new CheckersEngine());
        TestUtil.check(checkers.primitives().size() >= 50,
                "tracer: checkers scene has piece geometry (" + checkers.primitives().size() + ")");
        Scene chess = SceneBuilder.buildChess(new ChessEngine());
        TestUtil.check(chess.primitives().size() >= 60,
                "tracer: chess scene has piece geometry (" + chess.primitives().size() + ")");
        Vec3 centre = SceneBuilder.squareCenter(0, 0);
        TestUtil.eq(-3.5, centre.x, "tracer: a8 world x");
        TestUtil.eq(-3.5, centre.z, "tracer: a8 world z");
    }
}
