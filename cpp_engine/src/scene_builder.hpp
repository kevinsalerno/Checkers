// Builds ray-traceable scenes from the rules engines. World mapping:
// square (row, col) is centred at (col - 3.5, 0, row - 3.5), Y up.
// The board occupies [-4, 4] in X and Z; each square is 1 unit.
#pragma once

#include <memory>
#include <vector>

#include "camera.hpp"
#include "checkers_engine.hpp"
#include "chess_engine.hpp"
#include "material.hpp"
#include "primitive.hpp"
#include "scene.hpp"

namespace rt {

inline Vec3 squareCenter(int row, int col) { return {col - 3.5, 0, row - 3.5}; }

inline AreaLight defaultLight() {
    return {Vec3(3.0, 6.5, 2.0), 1.7, Vec3(1.0, 0.96, 0.88), 1.15};
}

// A scene plus the materials its primitives point at. Beweglich but not
// copyable (mirrors the underlying primitive ownership).
struct BuiltScene {
    Scene scene;
    std::vector<std::unique_ptr<Material>> materials;

    explicit BuiltScene(const AreaLight& light) : scene(light) {}

    const Material* keep(const Material& m) {
        materials.push_back(std::make_unique<Material>(m));
        return materials.back().get();
    }
};

BuiltScene buildCheckersScene(const engine::CheckersEngine& eng, const AreaLight& light);
inline BuiltScene buildCheckersScene(const engine::CheckersEngine& eng) {
    return buildCheckersScene(eng, defaultLight());
}
BuiltScene buildChessScene(const engine::ChessEngine& eng, const AreaLight& light);
inline BuiltScene buildChessScene(const engine::ChessEngine& eng) {
    return buildChessScene(eng, defaultLight());
}

}  // namespace rt
