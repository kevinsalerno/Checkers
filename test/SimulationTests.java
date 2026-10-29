import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Headless tests for the simulation harness (no display needed). */
public final class SimulationTests {
    private SimulationTests() {
    }

    public static int run() {
        TestUtil.reset();
        chessTerminalStates();
        chessIllegalAndInProgress();
        checkersSimulation();
        fileSimulation();
        return TestUtil.report("SimulationTests");
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    private static void chessTerminalStates() {
        HeadlessSimulation.Result fools = HeadlessSimulation.simulate("chess",
                Arrays.asList("f3", "e5", "g4", "Qh4#"));
        TestUtil.eq("BLACK_WINS", fools.outcome, "simulation: fool's mate outcome");
        TestUtil.eq(4, fools.movesApplied, "simulation: fool's mate move count");
        TestUtil.check(fools.success, "simulation: fool's mate succeeds");
        HeadlessSimulation.Result scholars = HeadlessSimulation.simulate("chess",
                Arrays.asList("e4", "e5", "Bc4", "Nc6", "Qh5", "Nf6", "Qxf7#"));
        TestUtil.eq("WHITE_WINS", scholars.outcome, "simulation: scholar's mate outcome");
        // Repetition draw from the start position.
        HeadlessSimulation.Result rep = HeadlessSimulation.simulate("chess",
                Arrays.asList("Nf3", "Nf6", "Ng1", "Ng8", "Nf3", "Nf6", "Ng1", "Ng8"));
        TestUtil.eq("DRAW", rep.outcome, "simulation: repetition draw detected");
        TestUtil.check(rep.reason.contains("repetition"), "simulation: repetition reason");
        // Coordinate notation reaches the same fool's mate.
        HeadlessSimulation.Result coord = HeadlessSimulation.simulate("chess",
                Arrays.asList("f2f3", "e7e5", "g2g4", "d8h4"));
        TestUtil.eq("BLACK_WINS", coord.outcome, "simulation: coordinate mate outcome");
    }

    private static void chessIllegalAndInProgress() {
        HeadlessSimulation.Result illegal = HeadlessSimulation.simulate("chess",
                Arrays.asList("e4", "e5", "e2e4"));
        TestUtil.check(!illegal.success, "simulation: illegal move fails the run");
        TestUtil.eq(2, illegal.movesApplied, "simulation: only legal moves are applied");
        TestUtil.check(illegal.error != null && illegal.error.contains("e2e4"),
                "simulation: error names the rejected move");
        TestUtil.eq("IN_PROGRESS", illegal.outcome, "simulation: failed run keeps prior outcome");
        HeadlessSimulation.Result partial = HeadlessSimulation.simulate("chess",
                Arrays.asList("e4", "e5", "Nf3"));
        TestUtil.eq("IN_PROGRESS", partial.outcome, "simulation: partial game is in progress");
        TestUtil.eq(3, partial.movesApplied, "simulation: partial game move count");
        TestUtil.check(partial.position.contains("FEN:"), "simulation: chess result carries FEN");
        final List<String> badGame = new ArrayList<String>();
        badGame.add("e4");
        TestUtil.rejects(new Runnable() {
            @Override
            public void run() {
                HeadlessSimulation.simulate("draughts", badGame);
            }
        }, "simulation: unknown game type rejected");
    }

    private static void checkersSimulation() {
        HeadlessSimulation.Result game = HeadlessSimulation.simulate("checkers",
                Arrays.asList("b6-a5", "c3-b4", "a5xc3", "e1-f2"));
        TestUtil.check(!game.success, "simulation: mandatory capture enforced in harness");
        TestUtil.eq(3, game.movesApplied, "simulation: quiet move in capture position rejected");
        HeadlessSimulation.Result ok = HeadlessSimulation.simulate("checkers",
                Arrays.asList("b6-a5", "c3-b4", "a5xc3", "b2xd4"));
        TestUtil.check(ok.success, "simulation: capture chain in harness succeeds");
        TestUtil.eq("IN_PROGRESS", ok.outcome, "simulation: checkers game in progress");
        TestUtil.eq(4, ok.movesApplied, "simulation: checkers move count");
        // The harness rejects a backward man move.
        HeadlessSimulation.Result backward = HeadlessSimulation.simulate("checkers",
                Arrays.asList("b6-a5", "a3-b4", "a5-b6"));
        TestUtil.check(!backward.success,
                "simulation: backward man move rejected in harness");
        TestUtil.eq(2, backward.movesApplied,
                "simulation: backward move stops the run at move 3");
    }

    private static void fileSimulation() {
        try {
            java.io.File tmp = java.io.File.createTempFile("simtest", ".txt");
            tmp.deleteOnExit();
            java.io.PrintWriter w = new java.io.PrintWriter(tmp, "UTF-8");
            w.println("# comment line");
            w.println("CHESS");
            w.println("f3, e5");
            w.println("g4 Qh4#");
            w.close();
            HeadlessSimulation.Result r = HeadlessSimulation.simulateFile(tmp.getAbsolutePath());
            TestUtil.eq("BLACK_WINS", r.outcome, "simulation: file-based fool's mate");
            TestUtil.eq(4, r.movesApplied, "simulation: file move count");
        } catch (Exception e) {
            TestUtil.check(false, "simulation: file simulation threw " + e);
        }
    }
}
