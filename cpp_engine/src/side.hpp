// Player side. WHITE moves toward row 0, BLACK moves toward row 7.
// Row 0 is the top of the board (rank 8), row 7 the bottom (rank 1).
#pragma once

namespace engine {

enum class Side { White, Black };

inline Side opponentOf(Side s) { return s == Side::White ? Side::Black : Side::White; }

inline const char* sideName(Side s) { return s == Side::White ? "White" : "Black"; }

}  // namespace engine
