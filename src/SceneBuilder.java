/**
 * Builds ray-traceable scenes from the rules engines. World mapping:
 * square (row, col) is centred at (col - 3.5, 0, row - 3.5), Y up.
 * The board occupies [-4, 4] in X and Z; each square is 1 unit.
 */
public final class SceneBuilder {
    private SceneBuilder() {
    }

    public static Vec3 squareCenter(int row, int col) {
        return new Vec3(col - 3.5, 0, row - 3.5);
    }

    public static AreaLight defaultLight() {
        return new AreaLight(new Vec3(3.0, 6.5, 2.0), 1.7,
                new Vec3(1.0, 0.96, 0.88), 1.15);
    }

    private static void addBoard(Scene scene) {
        // Table.
        scene.add(BoundedPlane.horizontal(-0.35, 16, Material.wood(
                new Vec3(0.23, 0.13, 0.07), new Vec3(0.13, 0.07, 0.04))));
        // Frame slightly below the playing surface.
        scene.add(BoundedPlane.horizontal(-0.06, 4.55, Material.wood(
                new Vec3(0.42, 0.26, 0.13), new Vec3(0.28, 0.16, 0.08))));
        // Playing surface: checker pattern from the material itself.
        Material boardMat = new Material(
                new Vec3(0.72, 0.58, 0.40), new Vec3(0.22, 0.14, 0.09),
                Material.Pattern.CHECKER, 0.35, 55, 0.22);
        scene.add(BoundedPlane.horizontal(0, 4, boardMat));
    }

    private static Material whiteMaterial() {
        return Material.marble(new Vec3(0.82, 0.80, 0.74),
                new Vec3(0.55, 0.53, 0.48));
    }

    private static Material blackMaterial() {
        return new Material(new Vec3(0.10, 0.07, 0.06),
                new Vec3(0.16, 0.11, 0.08), Material.Pattern.WOOD,
                0.8, 110, 0.45);
    }

    private static void disc(Scene scene, double x, double z, double r,
                             double y, double h, Material mat) {
        scene.add(new Cylinder(x, y, z, r, h, mat));
    }

    private static void checkerPiece(Scene scene, int row, int col,
                                     boolean white, boolean king) {
        Vec3 c = squareCenter(row, col);
        Material mat = white ? whiteMaterial() : blackMaterial();
        disc(scene, c.x, c.z, 0.42, 0, 0.13, mat);
        disc(scene, c.x, c.z, 0.36, 0.13, 0.09, mat);
        scene.add(new Sphere(new Vec3(c.x, 0.24, c.z), 0.27, mat));
        if (king) {
            // Crown: a smaller raised disc and a golden knob.
            Material gold = Material.polished(new Vec3(0.85, 0.62, 0.22), 0.6);
            disc(scene, c.x, c.z, 0.24, 0.38, 0.08, gold);
            scene.add(new Sphere(new Vec3(c.x, 0.52, c.z), 0.13, gold));
        }
    }

    public static Scene buildCheckers(CheckersEngine engine, AreaLight light) {
        Scene scene = new Scene(light);
        addBoard(scene);
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int p = engine.pieceAt(r, c);
                if (p != CheckersEngine.EMPTY) {
                    checkerPiece(scene, r, c,
                            CheckersEngine.sideOf(p) == Side.WHITE,
                            CheckersEngine.isKing(p));
                }
            }
        }
        return scene;
    }

    public static Scene buildCheckers(CheckersEngine engine) {
        return buildCheckers(engine, defaultLight());
    }

    private static void chessPiece(Scene scene, int row, int col,
                                   int piece, Material mat) {
        Vec3 c = squareCenter(row, col);
        double x = c.x;
        double z = c.z;
        int type = ChessPiece.typeOf(piece);
        Side side = ChessPiece.sideOf(piece);
        // Every piece stands on a round base.
        disc(scene, x, z, 0.36, 0, 0.10, mat);
        switch (type) {
            case ChessPiece.PAWN:
                disc(scene, x, z, 0.17, 0.10, 0.26, mat);
                scene.add(new Sphere(new Vec3(x, 0.50, z), 0.21, mat));
                break;
            case ChessPiece.ROOK:
                disc(scene, x, z, 0.26, 0.10, 0.42, mat);
                disc(scene, x, z, 0.33, 0.52, 0.12, mat);
                break;
            case ChessPiece.KNIGHT: {
                disc(scene, x, z, 0.22, 0.10, 0.34, mat);
                scene.add(new Sphere(new Vec3(x, 0.52, z), 0.22, mat));
                // Muzzle pointing toward the enemy side.
                double fwd = side == Side.WHITE ? -0.20 : 0.20;
                scene.add(new Sphere(new Vec3(x, 0.46, z + fwd), 0.13, mat));
                break;
            }
            case ChessPiece.BISHOP:
                disc(scene, x, z, 0.18, 0.10, 0.36, mat);
                scene.add(new Sphere(new Vec3(x, 0.56, z), 0.22, mat));
                scene.add(new Sphere(new Vec3(x, 0.74, z), 0.09, mat));
                break;
            case ChessPiece.QUEEN:
                disc(scene, x, z, 0.20, 0.10, 0.44, mat);
                scene.add(new Sphere(new Vec3(x, 0.62, z), 0.24, mat));
                scene.add(new Sphere(new Vec3(x, 0.84, z), 0.11, mat));
                break;
            case ChessPiece.KING:
                disc(scene, x, z, 0.22, 0.10, 0.48, mat);
                scene.add(new Sphere(new Vec3(x, 0.66, z), 0.25, mat));
                scene.add(new Cylinder(x, 0.82, z, 0.05, 0.22, mat));
                break;
            default:
                break;
        }
    }

    public static Scene buildChess(ChessEngine engine, AreaLight light) {
        Scene scene = new Scene(light);
        addBoard(scene);
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                int p = engine.pieceAt(r, c);
                if (p != ChessPiece.EMPTY) {
                    chessPiece(scene, r, c, p,
                            ChessPiece.sideOf(p) == Side.WHITE
                                    ? whiteMaterial() : blackMaterial());
                }
            }
        }
        return scene;
    }

    public static Scene buildChess(ChessEngine engine) {
        return buildChess(engine, defaultLight());
    }
}
