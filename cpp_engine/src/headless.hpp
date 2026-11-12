// Headless verification harness. Consumes an arbitrary sequence of
// moves in coordinate or (for chess) algebraic notation and simulates
// a complete game without a display, reporting the terminal state.
//
// Never touches the graphics context: engine headers only.
//
// Moves-file format: the first non-comment line names the game
// (CHECKERS or CHESS); the rest is whitespace- or comma-separated moves.
// Lines starting with '#' or '//' are comments.
//
// Output format (stdout):
//   GAME: CHECKERS|CHESS
//   MOVES_APPLIED: <n>
//   RESULT: <IN_PROGRESS|WHITE_WINS|BLACK_WINS|DRAW> [(reason)]
//   [ERROR: <message>]
//   <board diagram>
//   [FEN: <fen>]            (chess only)
//
// Exit codes: 0 = every move was legal (the game may still be in
// progress); 2 = an illegal move was rejected; 1 = bad usage.
#pragma once

#include <string>
#include <vector>

namespace engine {

struct SimResult {
    std::string gameType;
    int movesApplied = 0;
    std::string outcome;
    std::string reason;
    std::string position;
    std::string error;
    bool success = false;

    std::string toString() const;
};

// Simulates `moves` from the starting position. Throws
// std::invalid_argument on an unknown game type.
SimResult simulate(const std::string& gameType, const std::vector<std::string>& moves);
// Reads game type + moves from a file (see above). Throws on I/O or
// format errors.
SimResult simulateFile(const std::string& path);

}  // namespace engine
