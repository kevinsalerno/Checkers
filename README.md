# Checkers & Chess Suite — High-Fidelity Software Ray Tracing

Pure Java SE 8. Swing / AWT / Graphics2D for the UI and display buffer,
`javax.sound.sampled` for synthesised sound effects. Zero third-party
dependencies, no build tools: one `javac` invocation builds everything.

## Build, test, run

```sh
./build.sh                     # compile + run all headless tests
javac --release 8 -d bin src/*.java
java -cp bin Main              # Swing UI (2D board + ray-traced 3D view)
```

Tests use no framework (keeping the zero-dependency constraint):

```sh
javac --release 8 -cp bin -d bin-test test/*.java
java -Djava.awt.headless=true -cp bin:bin-test RunAllTests
```

Headless modes (no display needed):

```sh
java -cp bin HeadlessSimulation chess e4 e5 Nf3 Nc6 Bc4 Bc5 O-O
java -cp bin HeadlessSimulation checkers b6-a5 c3-b4 a5xc3 b2xd4
java -cp bin HeadlessSimulation --file moves.txt
java -cp bin Main --render out.png chess   # ray-traced PNG of the start position
```

`moves.txt` format: first non-comment line is `CHECKERS` or `CHESS`,
then whitespace- or comma-separated moves; `#` lines are comments.
The harness prints `RESULT:` (`IN_PROGRESS`, `WHITE_WINS`,
`BLACK_WINS`, `DRAW`) plus the reason and final position. Exit code 0
means every move was legal, 2 means a move was rejected, 1 means
bad usage.

## What was fixed

`Board.move` used to leave the moved piece's coordinates stale
(`setPositionX(tempX)` with the old value) and counted illegal moves
as turns; `BoardObject.getAvailableMoves` scanned orthogonally. Both
are fixed, and the rules now live in decoupled engines so they can be
tested headlessly: `Board` is a thin facade over `CheckersEngine`.

## Rules implemented

**Checkers** (`CheckersEngine`, American/English draughts): men move
diagonally forward, kings move diagonally in any direction, kings are
non-flying. Jumps capture, captures are mandatory, multi-jump chains
must be completed (a chain notation such as `c3xe5xg7` must be
complete, and an incomplete chain is rejected without mutating the
position), and reaching the far row crowns the piece and ends the
turn. Draws: threefold repetition and the 40-move rule (80 ply
without a capture or crowning). Loss: no pieces, or no legal move.
Black moves first.

**Chess** (`ChessEngine`): full move generation with king-safety
filtering (pins and discovered checks fall out of the same test),
castling (including through-check and in-check bans, and rights lost
by king/rook moves and rook captures), en passant (one move only),
promotion to any piece, check, checkmate, stalemate, the fifty-move
rule, threefold repetition and insufficient material. Move input
accepts coordinate notation (`e2e4`, `e2-e4`, `e7e8q`) and SAN
(`Nf3`, `exd5`, `O-O`, `e8=Q`, disambiguation). Positions round-trip
through FEN (`toFen`/`loadFen`). Move generation is validated by
perft: start position 20 / 400 / 8902 at depths 1–3 and Kiwipete
97862 at depth 3.

## Ray tracer (`RayTracer`, `Scene`, `Camera`, `AreaLight`)

A custom Whitted-style tracer rendering into a `BufferedImage`:

- **Area lights, soft shadows.** Each shading point traces shadow rays
  to deterministic golden-spiral sample points on a circular light;
  the lit fraction is the penumbra, so shadow edges soften with light
  size instead of the old hard-shadow look.
- **Materials.** Lambert diffuse, Blinn–Phong specular, procedural
  wood grain and marble (no textures), and recursive reflections with
  a Schlick Fresnel term (up to 3 bounces), so pieces read as
  polished wood/marble with a gold crown on checkers kings.
- **Anti-aliasing.** Stratified jittered samples per pixel (2×2 in
  the balanced preset, up to 3×3), deterministic per pixel so
  parallel and serial renders are bit-identical.
- **Performance.** Rows are split across a `ForkJoinPool`.
  `RayTracedPanel` renders a half-resolution preview while the camera
  or light is dragged and a high-quality pass 180 ms after resizing
  stops / on mouse release — playable while interacting, resolving
  to full quality when still. Renders are cancellable by generation;
  stale generations are discarded, never displayed.
- **Interaction.** Drag orbits the camera, right-drag (or Ctrl-drag)
  moves the light, the wheel zooms; the light sliders and Reset
  button do the same numerically. Clicking a square in the 3D view
  ray-casts onto the board plane and plays through the same
  controller as the 2D view, with legal-move markers projected onto
  the image.

## UI, timers, sound, multiplayer

- **Guidance.** Movable pieces get green rings, the selected piece a
  gold ring, quiet destinations green dots and captures red rings —
  in both the 2D and 3D views.
- **Timers.** A per-player countdown (configurable 1–60 minutes);
  flag fall wins the game for the opponent.
- **Sound.** Synthesised PCM tones/noise via `javax.sound.sampled`
  (move, capture, check, win/draw, select, illegal); no audio files.
  Sound never blocks gameplay and is silent headlessly.
- **Multiplayer.** Direct peer-to-peer over TCP sockets
  (`PeerConnection`: `MOVE`/`NEW`/`RESIGN`/`CHAT` line protocol, host
  takes the first-moving side). See the honest note below.

### Note: WebRTC

A real WebRTC stack (ICE, DTLS-SRTP, SCTP) has no pure Java SE 8
implementation and cannot be built from the JDK alone without
external libraries, which this task forbids. The deliverable
therefore uses direct socket peer-to-peer — two peers connect
directly and exchange the same move messages a WebRTC data channel
would carry, with no gameplay relay server. Play across networks by
hosting on a reachable address/port (LAN address or forwarded port).
This is documented here and in `PeerConnection` rather than claiming
a WebRTC stack that does not exist.

## Layout

- `src/` — engines (`CheckersEngine`, `ChessEngine`, `GameController`),
  legacy facade (`Board`, `BoardObject`), ray tracer, Swing UI,
  `PeerConnection`, `SoundEffects`, `HeadlessSimulation`, `Main`.
- `test/` — committed headless tests (`EngineTests`,
  `RayTracerTests`, `SimulationTests`, `PeerTests`, `RunAllTests`).
- `bin/` — compiled output (Eclipse layout, as in the original repo).

## Verification checklist

- [x] Rules engine handles mandatory jumps, crowning and all chess
      rules headlessly — 106 engine/controller checks, including
      perft, mates, castling through check, en passant expiry,
      promotion, pins, stalemate, fifty-move, repetition and
      insufficient material.
- [x] Ray tracer produces soft shadows and reflections — tested by
      penumbra width vs. light radius, reflection pixel diffs,
      determinism, and parallel/serial identity; PNGs rendered and
      inspected (see `java Main --render`).
- [x] Light and camera move in real time — preview/high-quality
      progressive rendering with cancellable generations; exercised
      headlessly through `RayTracedPanel` plus the panel smoke probe.
- [x] Single `javac` invocation, zero third-party dependencies —
      `javac --release 8 -d bin src/*.java`.
- [x] Headless simulation parses moves and computes terminal states
      — 24 harness checks plus CLI exit-code checks for checkmate,
      stalemate-via-FEN, draws and illegal-move rejection.
