/**
 * Minimal assertion helpers for the committed headless tests.
 * No test framework is used, keeping the zero-dependency constraint.
 */
public final class TestUtil {
    private static int checks = 0;
    private static int failures = 0;

    private TestUtil() {
    }

    public static void reset() {
        checks = 0;
        failures = 0;
    }

    public static void check(boolean condition, String name) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }

    public static void eq(Object expected, Object actual, String name) {
        checks++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name + " expected <" + expected
                    + "> but was <" + actual + ">");
        }
    }

    public static void eq(int expected, int actual, String name) {
        eq(Integer.valueOf(expected), Integer.valueOf(actual), name);
    }

    public static void eq(long expected, long actual, String name) {
        eq(Long.valueOf(expected), Long.valueOf(actual), name);
    }

    /** Asserts that {@code body} throws IllegalArgumentException. */
    public static void rejects(Runnable body, String name) {
        checks++;
        try {
            body.run();
            failures++;
            System.out.println("FAIL: " + name + " (accepted, expected rejection)");
        } catch (IllegalArgumentException expected) {
            // Expected.
        } catch (Exception e) {
            failures++;
            System.out.println("FAIL: " + name + " (wrong exception: " + e + ")");
        }
    }

    public static int report(String suite) {
        System.out.println(suite + ": " + (checks - failures) + "/" + checks
                + " checks passed");
        return failures;
    }

    public static int failures() {
        return failures;
    }
}
