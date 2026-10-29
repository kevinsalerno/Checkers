import java.awt.GraphicsEnvironment;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.io.File;

/**
 * Entry point.
 *
 * <ul>
 * <li>{@code java Main} - launches the Swing UI (2D + ray-traced 3D).</li>
 * <li>{@code java Main --simulate checkers|chess &lt;moves...&gt;} -
 * headless game simulation (see {@link HeadlessSimulation}).</li>
 * <li>{@code java Main --file moves.txt} - headless simulation from a file.</li>
 * <li>{@code java Main --render out.png [checkers|chess]} - renders the
 * starting position with the ray tracer and writes a PNG, headlessly.</li>
 * </ul>
 */
public class Main {
    public static void main(String[] args) {
        if (args.length > 0) {
            if ("--simulate".equals(args[0])) {
                String[] rest = new String[args.length - 1];
                System.arraycopy(args, 1, rest, 0, rest.length);
                HeadlessSimulation.main(rest);
                return;
            }
            if ("--file".equals(args[0])) {
                HeadlessSimulation.main(args);
                return;
            }
            if ("--render".equals(args[0])) {
                renderPng(args);
                return;
            }
            if ("--help".equals(args[0]) || "-h".equals(args[0])) {
                printHelp();
                return;
            }
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("No display available. Run with --help, "
                    + "--simulate, --file or --render for headless modes.");
            printHelp();
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new GameUI().showUI();
            }
        });
    }

    private static void printHelp() {
        System.out.println("Usage:");
        System.out.println("  java Main                                  Launch the Swing UI");
        System.out.println("  java Main --simulate checkers|chess <moves...>   Headless simulation");
        System.out.println("  java Main --file moves.txt                 Headless simulation from file");
        System.out.println("  java Main --render out.png [checkers|chess] Render a PNG headlessly");
    }

    private static void renderPng(String[] args) {
        try {
            if (args.length < 2) {
                System.err.println("Usage: java Main --render out.png [checkers|chess]");
                System.exit(1);
            }
            String mode = args.length > 2 ? args[2] : "checkers";
            Scene scene;
            if ("chess".equalsIgnoreCase(mode)) {
                scene = SceneBuilder.buildChess(new ChessEngine());
            } else {
                scene = SceneBuilder.buildCheckers(new CheckersEngine());
            }
            ProgressiveRenderer renderer = new ProgressiveRenderer();
            try {
                java.awt.image.BufferedImage image = renderer.renderImage(
                        scene, Camera.defaultView(), 640, 480, RayTracer.HIGH,
                        new ProgressiveRenderer.CancelCheck() {
                            @Override
                            public boolean cancelled() {
                                return false;
                            }
                        });
                ImageIO.write(image, "png", new File(args[1]));
                System.out.println("Wrote " + args[1]);
            } finally {
                renderer.shutdown();
            }
        } catch (Exception e) {
            System.err.println("Render failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
