// Headless tests for the simulation harness (no display needed).
#include <cstdio>
#include <string>
#include <vector>

#include "test_framework.hpp"
#include "../src/headless.hpp"

namespace {

using engine::SimResult;
using engine::simulate;
using engine::simulateFile;
using testutil::check;
using testutil::eqInt;
using testutil::eqStr;
using testutil::rejects;

void chessTerminalStates() {
    SimResult fools = simulate("chess", {"f3", "e5", "g4", "Qh4#"});
    eqStr("BLACK_WINS", fools.outcome, "simulation: fool's mate outcome");
    eqInt(4, fools.movesApplied, "simulation: fool's mate move count");
    check(fools.success, "simulation: fool's mate succeeds");
    SimResult scholars = simulate("chess", {"e4", "e5", "Bc4", "Nc6", "Qh5", "Nf6", "Qxf7#"});
    eqStr("WHITE_WINS", scholars.outcome, "simulation: scholar's mate outcome");
    SimResult rep = simulate("chess", {"Nf3", "Nf6", "Ng1", "Ng8", "Nf3", "Nf6", "Ng1", "Ng8"});
    eqStr("DRAW", rep.outcome, "simulation: repetition draw detected");
    check(rep.reason.find("repetition") != std::string::npos, "simulation: repetition reason");
    SimResult coord = simulate("chess", {"f2f3", "e7e5", "g2g4", "d8h4"});
    eqStr("BLACK_WINS", coord.outcome, "simulation: coordinate mate outcome");
}

void chessIllegalAndInProgress() {
    SimResult illegal = simulate("chess", {"e4", "e5", "e2e4"});
    check(!illegal.success, "simulation: illegal move fails the run");
    eqInt(2, illegal.movesApplied, "simulation: only legal moves are applied");
    check(illegal.error.find("e2e4") != std::string::npos,
          "simulation: error names the rejected move");
    eqStr("IN_PROGRESS", illegal.outcome, "simulation: failed run keeps prior outcome");
    SimResult partial = simulate("chess", {"e4", "e5", "Nf3"});
    eqStr("IN_PROGRESS", partial.outcome, "simulation: partial game is in progress");
    eqInt(3, partial.movesApplied, "simulation: partial game move count");
    check(partial.position.find("FEN:") != std::string::npos,
          "simulation: chess result carries FEN");
    rejects([] { simulate("draughts", {"e4"}); }, "simulation: unknown game type rejected");
}

void checkersSimulation() {
    SimResult game = simulate("checkers", {"b6-a5", "c3-b4", "a5xc3", "e1-f2"});
    check(!game.success, "simulation: mandatory capture enforced in harness");
    eqInt(3, game.movesApplied, "simulation: quiet move in capture position rejected");
    SimResult ok = simulate("checkers", {"b6-a5", "c3-b4", "a5xc3", "b2xd4"});
    check(ok.success, "simulation: capture chain in harness succeeds");
    eqStr("IN_PROGRESS", ok.outcome, "simulation: checkers game in progress");
    eqInt(4, ok.movesApplied, "simulation: checkers move count");
    SimResult backward = simulate("checkers", {"b6-a5", "a3-b4", "a5-b6"});
    check(!backward.success, "simulation: backward man move rejected in harness");
    eqInt(2, backward.movesApplied, "simulation: backward move stops the run at move 3");
}

void fileSimulation() {
    const char* path = "/tmp/cpp_simtest_moves.txt";
    {
        FILE* f = std::fopen(path, "w");
        check(f != nullptr, "simulation: temp file created");
        if (!f) return;
        std::fputs("# comment line\nCHESS\nf3, e5\ng4 Qh4#\n", f);
        std::fclose(f);
    }
    try {
        SimResult r = simulateFile(path);
        eqStr("BLACK_WINS", r.outcome, "simulation: file-based fool's mate");
        eqInt(4, r.movesApplied, "simulation: file move count");
    } catch (const std::exception& e) {
        check(false, std::string("simulation: file simulation threw ") + e.what());
    }
    std::remove(path);
}

}  // namespace

int runSimulationTests() {
    testutil::reset();
    chessTerminalStates();
    chessIllegalAndInProgress();
    checkersSimulation();
    fileSimulation();
    return testutil::report("SimulationTests");
}
