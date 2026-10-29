import java.awt.image.BufferedImage;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

/**
 * Multi-threaded, progressive rendering.
 *
 * <p>Rows are split across a {@link ForkJoinPool} sized to the machine.
 * Shading is a pure function of (scene, camera, pixel, settings) with
 * deterministic per-pixel sampling, so parallel rows produce exactly
 * the same image as a serial render. A render may be cancelled through
 * {@link CancelCheck} when the camera or light moves: the UI renders
 * a cheap preview while dragging and requests the high-quality pass
 * only once interaction stops.</p>
 */
public final class ProgressiveRenderer {
    /** Polled by worker tasks; true means abandon this render. */
    public interface CancelCheck {
        boolean cancelled();
    }

    private static final CancelCheck NEVER = new CancelCheck() {
        @Override
        public boolean cancelled() {
            return false;
        }
    };

    private final ForkJoinPool pool;

    public ProgressiveRenderer() {
        this(Runtime.getRuntime().availableProcessors());
    }

    public ProgressiveRenderer(int threads) {
        this.pool = new ForkJoinPool(Math.max(1, threads));
    }

    public void shutdown() {
        pool.shutdown();
    }

    public int threadCount() {
        return pool.getParallelism();
    }

    /** Renders packed sRGB pixels, row-major, without cancellation. */
    public int[] render(Scene scene, Camera camera, int width, int height,
                        RayTracer.Settings settings) {
        return render(scene, camera, width, height, settings, NEVER);
    }

    public int[] render(Scene scene, Camera camera, int width, int height,
                        RayTracer.Settings settings, CancelCheck cancel) {
        int[] pixels = new int[width * height];
        RayTracer tracer = new RayTracer(scene);
        pool.invoke(new RowTask(tracer, camera, width, height, settings,
                pixels, 0, height, cancel));
        return pixels;
    }

    public BufferedImage renderImage(Scene scene, Camera camera,
                                     int width, int height,
                                     RayTracer.Settings settings,
                                     CancelCheck cancel) {
        int[] pixels = render(scene, camera, width, height, settings, cancel);
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static final class RowTask extends RecursiveAction {
        private static final int ROW_THRESHOLD = 12;
        private final RayTracer tracer;
        private final Camera camera;
        private final int width;
        private final int height;
        private final RayTracer.Settings settings;
        private final int[] pixels;
        private final int fromRow;
        private final int toRow;
        private final CancelCheck cancel;

        RowTask(RayTracer tracer, Camera camera, int width, int height,
                RayTracer.Settings settings, int[] pixels,
                int fromRow, int toRow, CancelCheck cancel) {
            this.tracer = tracer;
            this.camera = camera;
            this.width = width;
            this.height = height;
            this.settings = settings;
            this.pixels = pixels;
            this.fromRow = fromRow;
            this.toRow = toRow;
            this.cancel = cancel;
        }

        @Override
        protected void compute() {
            if (cancel.cancelled()) {
                return;
            }
            if (toRow - fromRow <= ROW_THRESHOLD) {
                computeDirectly();
                return;
            }
            int mid = (fromRow + toRow) / 2;
            invokeAll(
                    new RowTask(tracer, camera, width, height, settings,
                            pixels, fromRow, mid, cancel),
                    new RowTask(tracer, camera, width, height, settings,
                            pixels, mid, toRow, cancel));
        }

        private void computeDirectly() {
            for (int y = fromRow; y < toRow; y++) {
                if (cancel.cancelled()) {
                    return;
                }
                int base = y * width;
                for (int x = 0; x < width; x++) {
                    Vec3 colour = tracer.shadePixel(camera, x, y, width, height, settings);
                    pixels[base + x] = RayTracer.toRgb(colour);
                }
            }
        }
    }
}
