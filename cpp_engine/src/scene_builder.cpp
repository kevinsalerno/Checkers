#include "scene_builder.hpp"

namespace rt {

namespace {

void addBoard(BuiltScene& out) {
    // Table.
    out.scene.add(std::make_unique<BoundedPlane>(BoundedPlane::horizontal(
        -0.35, 16,
        out.keep(Material::wood(Vec3(0.23, 0.13, 0.07), Vec3(0.13, 0.07, 0.04))))));
    // Frame slightly below the playing surface.
    out.scene.add(std::make_unique<BoundedPlane>(BoundedPlane::horizontal(
        -0.06, 4.55,
        out.keep(Material::wood(Vec3(0.42, 0.26, 0.13), Vec3(0.28, 0.16, 0.08))))));
    // Playing surface: checker pattern from the material itself.
    Material boardMat(Vec3(0.72, 0.58, 0.40), Vec3(0.22, 0.14, 0.09), Material::Pattern::Checker,
                      0.35, 55, 0.22);
    out.scene.add(std::make_unique<BoundedPlane>(
        BoundedPlane::horizontal(0, 4, out.keep(boardMat))));
}

Material whiteMaterial() {
    return Material::marble(Vec3(0.82, 0.80, 0.74), Vec3(0.55, 0.53, 0.48));
}

Material blackMaterial() {
    return Material(Vec3(0.10, 0.07, 0.06), Vec3(0.16, 0.11, 0.08), Material::Pattern::Wood,
                    0.8, 110, 0.45);
}

void disc(BuiltScene& out, double x, double z, double r, double y, double h,
          const Material* mat) {
    out.scene.add(std::make_unique<Cylinder>(x, y, z, r, h, mat));
}

void checkerPiece(BuiltScene& out, int row, int col, bool white, bool king) {
    Vec3 c = squareCenter(row, col);
    const Material* mat = out.keep(white ? whiteMaterial() : blackMaterial());
    disc(out, c.x, c.z, 0.42, 0, 0.13, mat);
    disc(out, c.x, c.z, 0.36, 0.13, 0.09, mat);
    out.scene.add(std::make_unique<Sphere>(Vec3(c.x, 0.24, c.z), 0.27, mat));
    if (king) {
        // Crown: a smaller raised disc and a golden knob.
        const Material* gold =
            out.keep(Material::polished(Vec3(0.85, 0.62, 0.22), 0.6));
        disc(out, c.x, c.z, 0.24, 0.38, 0.08, gold);
        out.scene.add(std::make_unique<Sphere>(Vec3(c.x, 0.52, c.z), 0.13, gold));
    }
}

}  // namespace

BuiltScene buildCheckersScene(const engine::CheckersEngine& eng, const AreaLight& light) {
    BuiltScene out(light);
    addBoard(out);
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) {
            int p = eng.pieceAt(r, c);
            if (p != engine::CheckersEngine::Empty)
                checkerPiece(out, r, c, engine::CheckersEngine::sideOf(p) == engine::Side::White,
                             engine::CheckersEngine::isKing(p));
        }
    return out;
}

namespace {

void chessPiece(BuiltScene& out, int row, int col, int piece, const Material* mat) {
    Vec3 c = squareCenter(row, col);
    double x = c.x, z = c.z;
    int type = engine::ChessPiece::typeOf(piece);
    engine::Side side = engine::ChessPiece::sideOf(piece);
    // Every piece stands on a round base.
    disc(out, x, z, 0.36, 0, 0.10, mat);
    switch (type) {
        case engine::ChessPiece::Pawn:
            disc(out, x, z, 0.17, 0.10, 0.26, mat);
            out.scene.add(std::make_unique<Sphere>(Vec3(x, 0.50, z), 0.21, mat));
            break;
        case engine::ChessPiece::Rook:
            disc(out, x, z, 0.26, 0.10, 0.42, mat);
            disc(out, x, z, 0.33, 0.52, 0.12, mat);
            break;
        case engine::ChessPiece::Knight: {
            disc(out, x, z, 0.22, 0.10, 0.34, mat);
            out.scene.add(std::make_unique<Sphere>(Vec3(x, 0.52, z), 0.22, mat));
            // Muzzle pointing toward the enemy side.
            double fwd = (side == engine::Side::White) ? -0.20 : 0.20;
            out.scene.add(std::make_unique<Sphere>(Vec3(x, 0.46, z + fwd), 0.13, mat));
            break;
        }
        case engine::ChessPiece::Bishop:
            disc(out, x, z, 0.18, 0.10, 0.36, mat);
            out.scene.add(std::make_unique<Sphere>(Vec3(x, 0.56, z), 0.22, mat));
            out.scene.add(std::make_unique<Sphere>(Vec3(x, 0.74, z), 0.09, mat));
            break;
        case engine::ChessPiece::Queen:
            disc(out, x, z, 0.20, 0.10, 0.44, mat);
            out.scene.add(std::make_unique<Sphere>(Vec3(x, 0.62, z), 0.24, mat));
            out.scene.add(std::make_unique<Sphere>(Vec3(x, 0.84, z), 0.11, mat));
            break;
        case engine::ChessPiece::King:
            disc(out, x, z, 0.22, 0.10, 0.48, mat);
            out.scene.add(std::make_unique<Sphere>(Vec3(x, 0.66, z), 0.25, mat));
            out.scene.add(std::make_unique<Cylinder>(x, 0.82, z, 0.05, 0.22, mat));
            break;
        default:
            break;
    }
}

}  // namespace

BuiltScene buildChessScene(const engine::ChessEngine& eng, const AreaLight& light) {
    BuiltScene out(light);
    addBoard(out);
    for (int r = 0; r < 8; r++)
        for (int c = 0; c < 8; c++) {
            int p = eng.pieceAt(r, c);
            if (p != engine::ChessPiece::Empty)
                chessPiece(out, r, c, p,
                           out.keep(engine::ChessPiece::sideOf(p) == engine::Side::White
                                        ? whiteMaterial()
                                        : blackMaterial()));
        }
    return out;
}

}  // namespace rt
