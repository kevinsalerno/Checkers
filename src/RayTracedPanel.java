import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Ray-traced 3D view. Dragging orbits the camera, right-dragging (or
 * Ctrl-dragging) moves the area light, and the wheel zooms. While the
 * camera or light is moving a cheap preview renders; 300 ms after the
 * last interaction the high-quality anti-aliased pass replaces it.
 * Clicking a square (without dragging) plays through the controller,
 * with the same legal-move markers as the 2D view drawn as an overlay
 * by projecting square centres through the camera.
 */
public class RayTracedPanel extends JPanel {
    public interface ClickHandler {
        void onSquareClicked(int row, int col);
    }

    private final GameController controller;
    private final ClickHandler handler;
    private final ProgressiveRenderer renderer = new ProgressiveRenderer();
    private final Camera camera = Camera.defaultView();
    private AreaLight light = SceneBuilder.defaultLight();
    private volatile Scene scene;
    private volatile BufferedImage image;
    private volatile int generation = 0;
    private volatile boolean rendering = false;
    private boolean highQuality = true;
    private int lastX;
    private int lastY;
    private int pressX;
    private int pressY;
    private boolean dragging;
    private boolean lightDragging;

    public RayTracedPanel(GameController controller, ClickHandler handler) {
        this.controller = controller;
        this.handler = handler;
        setPreferredSize(new Dimension(640, 480));
        setBackground(Color.BLACK);
        rebuildScene();
        controller.addChangeListener(new GameController.ChangeListener() {
            @Override
            public void onChange() {
                rebuildScene();
                requestRender(RayTracer.BALANCED, false);
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pressX = e.getX();
                pressY = e.getY();
                lastX = e.getX();
                lastY = e.getY();
                dragging = false;
                lightDragging = SwingUtilities.isRightMouseButton(e) || e.isControlDown();
                requestFocusInWindow();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - lastX;
                int dy = e.getY() - lastY;
                lastX = e.getX();
                lastY = e.getY();
                if (Math.abs(e.getX() - pressX) + Math.abs(e.getY() - pressY) > 4) {
                    dragging = true;
                }
                if (!dragging) {
                    return;
                }
                if (lightDragging) {
                    double az = light.azimuthDeg() + dx * 0.6;
                    double el = Math.max(12, Math.min(85,
                            light.elevationDeg() - dy * 0.4));
                    light = light.moved(az, el, light.distance());
                } else {
                    camera.orbit(dx * 0.5, dy * 0.4);
                }
                rebuildScene();
                requestRender(RayTracer.PREVIEW, true);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!dragging) {
                    int[] sq = pickSquare(e.getX(), e.getY());
                    if (sq != null) {
                        handler.onSquareClicked(sq[0], sq[1]);
                    }
                }
                requestRender(highQuality ? RayTracer.HIGH : RayTracer.BALANCED, false);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                camera.zoom(e.getWheelRotation() > 0 ? 1.1 : 0.9);
                requestRender(RayTracer.PREVIEW, true);
                requestRender(highQuality ? RayTracer.HIGH : RayTracer.BALANCED, false);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        // Re-render after resizing stops (debounced), so the image
        // always matches the panel size.
        final javax.swing.Timer resizeTimer = new javax.swing.Timer(180, new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                requestRender(highQuality ? RayTracer.HIGH : RayTracer.BALANCED, false);
            }
        });
        resizeTimer.setRepeats(false);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (getWidth() > 80 && getHeight() > 60) {
                    resizeTimer.restart();
                }
            }
        });
    }

    public Camera camera() {
        return camera;
    }

    public AreaLight light() {
        return light;
    }

    public void setLight(AreaLight newLight) {
        light = newLight;
        rebuildScene();
        requestRender(RayTracer.BALANCED, false);
    }

    public void setHighQuality(boolean high) {
        highQuality = high;
        requestRender(high ? RayTracer.HIGH : RayTracer.BALANCED, false);
    }

    public void resetView() {
        Camera def = Camera.defaultView();
        camera.setYaw(def.yawDeg());
        camera.setPitch(def.pitchDeg());
        camera.setDistance(def.distance());
        light = SceneBuilder.defaultLight();
        rebuildScene();
        requestRender(highQuality ? RayTracer.HIGH : RayTracer.BALANCED, false);
    }

    private void rebuildScene() {
        if (controller.mode() == GameController.Mode.CHECKERS) {
            scene = SceneBuilder.buildCheckers(controller.checkers(), light);
        } else {
            scene = SceneBuilder.buildChess(controller.chess(), light);
        }
    }

    /** Renders in the background; stale generations are discarded. */
    public void requestRender(final RayTracer.Settings settings, boolean isPreview) {
        final int gen = ++generation;
        final int w = Math.max(80, getWidth());
        final int h = Math.max(60, getHeight());
        final Scene renderScene = scene;
        final Camera renderCamera = camera.copy();
        // Preview renders at half resolution for speed.
        final int rw = isPreview ? Math.max(80, w / 2) : w;
        final int rh = isPreview ? Math.max(60, h / 2) : h;
        if (rendering && isPreview) {
            return;
        }
        rendering = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final BufferedImage rendered = renderer.renderImage(
                            renderScene, renderCamera, rw, rh, settings,
                            new ProgressiveRenderer.CancelCheck() {
                                @Override
                                public boolean cancelled() {
                                    return gen != generation;
                                }
                            });
                    if (gen == generation) {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                image = rendered;
                                repaint();
                            }
                        });
                    }
                } finally {
                    rendering = false;
                }
            }
        }, "raytrace-render");
        t.setDaemon(true);
        t.start();
    }

    /** Initial render once the panel has a size. */
    public void start() {
        requestRender(highQuality ? RayTracer.HIGH : RayTracer.BALANCED, false);
    }

    private int[] pickSquare(int px, int py) {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        Ray ray = camera.pickRay(px, py, w, h);
        if (Math.abs(ray.dir.y) < 1e-6) {
            return null;
        }
        double t = -ray.origin.y / ray.dir.y;
        if (t <= 0) {
            return null;
        }
        Vec3 p = ray.at(t);
        int col = (int) Math.floor(p.x + 4);
        int row = (int) Math.floor(p.z + 4);
        if (row < 0 || row > 7 || col < 0 || col > 7) {
            return null;
        }
        return new int[] { row, col };
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        BufferedImage img = image;
        if (img != null) {
            g2.drawImage(img, 0, 0, getWidth(), getHeight(), null);
        } else {
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(Color.WHITE);
            g2.drawString("Rendering 3D scene...", 12, 20);
        }
        drawOverlay(g2);
        g2.dispose();
    }

    private void drawOverlay(Graphics2D g2) {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        // Light marker.
        double[] lightPos = camera.project(light.center, w, h);
        if (lightPos != null && lightPos[0] >= 0 && lightPos[0] <= w
                && lightPos[1] >= 0 && lightPos[1] <= h) {
            int lx = (int) lightPos[0];
            int ly = (int) lightPos[1];
            g2.setColor(new Color(0xFF, 0xE8, 0x66));
            g2.fillOval(lx - 8, ly - 8, 16, 16);
            g2.setColor(Color.BLACK);
            g2.drawOval(lx - 8, ly - 8, 16, 16);
        }
        // Movable pieces.
        g2.setStroke(new BasicStroke(2));
        for (int[] sq : controller.movableSquares()) {
            double[] p = projectSquare(sq[0], sq[1], w, h);
            if (p != null) {
                g2.setColor(new Color(0x2E, 0xD0, 0x4A, 220));
                g2.drawOval((int) p[0] - 18, (int) p[1] - 18, 36, 36);
            }
        }
        if (controller.hasSelection()) {
            double[] p = projectSquare(controller.selectedRow(),
                    controller.selectedCol(), w, h);
            if (p != null) {
                g2.setColor(new Color(0xFF, 0xC8, 0x00));
                g2.setStroke(new BasicStroke(3));
                g2.drawRect((int) p[0] - 20, (int) p[1] - 20, 40, 40);
            }
        }
        for (int[] dest : controller.selectedDestinations()) {
            double[] p = projectSquare(dest[0], dest[1], w, h);
            if (p == null) {
                continue;
            }
            if (dest[2] == 1) {
                g2.setColor(new Color(0xE0, 0x20, 0x20, 230));
                g2.setStroke(new BasicStroke(3));
                g2.drawOval((int) p[0] - 14, (int) p[1] - 14, 28, 28);
            } else {
                g2.setColor(new Color(0x2E, 0xD0, 0x4A, 230));
                g2.fillOval((int) p[0] - 7, (int) p[1] - 7, 14, 14);
            }
        }
        g2.setColor(new Color(255, 255, 255, 180));
        g2.drawString("Drag: orbit camera | Right-drag: move light | Wheel: zoom | Click: play",
                8, h - 8);
    }

    private double[] projectSquare(int row, int col, int w, int h) {
        Vec3 c = SceneBuilder.squareCenter(row, col);
        return camera.project(new Vec3(c.x, 0.35, c.z), w, h);
    }

    /** Renders the current view at the panel size for screenshots/tests. */
    public BufferedImage renderNow(RayTracer.Settings settings) {
        return renderer.renderImage(scene, camera.copy(),
                Math.max(80, getWidth()), Math.max(60, getHeight()),
                settings, new ProgressiveRenderer.CancelCheck() {
                    @Override
                    public boolean cancelled() {
                        return false;
                    }
                });
    }
}
