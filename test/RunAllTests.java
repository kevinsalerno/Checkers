/** Runs every committed headless test suite and exits non-zero on failure. */
public final class RunAllTests {
    private RunAllTests() {
    }

    public static void main(String[] args) {
        int failures = 0;
        failures += EngineTests.run();
        failures += RayTracerTests.run();
        failures += SimulationTests.run();
        failures += PeerTests.run();
        System.out.println("TOTAL FAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
