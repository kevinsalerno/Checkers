import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless verification harness. Consumes an arbitrary sequence of
 * moves in coordinate or (for chess) algebraic notation and simulates
 * a complete game without a display, reporting the terminal state.
 *
 * <p>Usage:</p>
 * <pre>
 *   java HeadlessSimulation checkers b6-a5 a3-b4 ...
 *   java HeadlessSimulation chess e4 e5 Nf3 Nc6 ...
 *   java HeadlessSimulation --file moves.txt
 * </pre>
 * <p>A {@code --file} file's first non-comment line names the game
 * ({@code CHECKERS} or {@code CHESS}); the rest is whitespace- or
 * comma-separated moves. Lines starting with {@code #} are comments.
 * Exit code 0 means every move was legal (the game may still be in
 * progress); 2 means an illegal move was rejected; 1 means bad usage.</p>
 */
public final class HeadlessSimulation {
    public static final class Result {
        public final String gameType;
        public final int movesApplied;
        public final String outcome;
        public final String reason;
        public final String position;
        public final String error;
        public final boolean success;

        Result(String gameType, int movesApplied, String outcome, String reason,
               String position, String error, boolean success) {
            this.gameType = gameType;
            this.movesApplied = movesApplied;
            this.outcome = outcome;
            this.reason = reason;
            this.position = position;
            this.error = error;
            this.success = success;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("GAME: ").append(gameType).append('\n');
            sb.append("MOVES_APPLIED: ").append(movesApplied).append('\n');
            sb.append("RESULT: ").append(outcome);
            if (reason != null && !reason.isEmpty()) {
                sb.append(" (").append(reason).append(')');
            }
            sb.append('\n');
            if (error != null) {
                sb.append("ERROR: ").append(error).append('\n');
            }
            sb.append(position).append('\n');
            return sb.toString();
        }
    }

    private HeadlessSimulation() {
    }

    /** Simulates {@code moves} from the starting position. */
    public static Result simulate(String gameType, List<String> moves) {
        boolean isCheckers;
        if ("checkers".equalsIgnoreCase(gameType)) {
            isCheckers = true;
        } else if ("chess".equalsIgnoreCase(gameType)) {
            isCheckers = false;
        } else {
            throw new IllegalArgumentException("Unknown game type: " + gameType);
        }
        CheckersEngine checkers = isCheckers ? new CheckersEngine() : null;
        ChessEngine chess = isCheckers ? null : new ChessEngine();
        int applied = 0;
        String error = null;
        for (String move : moves) {
            try {
                if (isCheckers) {
                    checkers.applyNotation(move);
                } else {
                    chess.applyNotation(move);
                }
                applied++;
            } catch (IllegalArgumentException e) {
                error = "Move " + (applied + 1) + " '" + move + "' rejected: "
                        + e.getMessage();
                break;
            }
            if ((isCheckers && checkers.isGameOver())
                    || (!isCheckers && chess.isGameOver())) {
                break;
            }
        }
        boolean success = error == null;
        if (isCheckers) {
            String outcome = outcomeName(checkers.outcome());
            return new Result("CHECKERS", applied, outcome,
                    checkers.outcomeReason(), checkers.boardDiagram(),
                    error, success);
        }
        String outcome = outcomeName(chess.outcome());
        return new Result("CHESS", applied, outcome, chess.outcomeReason(),
                chess.boardDiagram() + "\nFEN: " + chess.toFen(), error, success);
    }

    private static String outcomeName(CheckersEngine.Outcome o) {
        switch (o) {
            case WHITE_WINS: return "WHITE_WINS";
            case BLACK_WINS: return "BLACK_WINS";
            case DRAW: return "DRAW";
            default: return "IN_PROGRESS";
        }
    }

    private static String outcomeName(ChessEngine.Outcome o) {
        switch (o) {
            case WHITE_WINS: return "WHITE_WINS";
            case BLACK_WINS: return "BLACK_WINS";
            case DRAW: return "DRAW";
            default: return "IN_PROGRESS";
        }
    }

    /** Reads game type + moves from a file (see class docs). */
    public static Result simulateFile(String path) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(path));
        try {
            String gameType = null;
            List<String> moves = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")
                        || trimmed.startsWith("//")) {
                    continue;
                }
                if (gameType == null) {
                    gameType = trimmed.split("\\s+")[0];
                    String rest = trimmed.substring(gameType.length()).trim();
                    if (!rest.isEmpty()) {
                        addTokens(moves, rest);
                    }
                    continue;
                }
                addTokens(moves, trimmed);
            }
            if (gameType == null) {
                throw new IllegalArgumentException("Empty simulation file: " + path);
            }
            return simulate(gameType, moves);
        } finally {
            reader.close();
        }
    }

    private static void addTokens(List<String> moves, String text) {
        for (String token : text.split("[,\\s]+")) {
            if (!token.isEmpty()) {
                moves.add(token);
            }
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: java HeadlessSimulation <checkers|chess> <moves...>");
            System.err.println("   or: java HeadlessSimulation --file <moves.txt>");
            System.exit(1);
        }
        try {
            Result result;
            if ("--file".equals(args[0])) {
                if (args.length < 2) {
                    System.err.println("Usage: java HeadlessSimulation --file <moves.txt>");
                    System.exit(1);
                }
                result = simulateFile(args[1]);
            } else {
                List<String> moves = new ArrayList<String>();
                for (int i = 1; i < args.length; i++) {
                    addTokens(moves, args[i]);
                }
                result = simulate(args[0], moves);
            }
            System.out.print(result.toString());
            System.exit(result.success ? 0 : 2);
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }
}
