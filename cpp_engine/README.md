# Checkers & Chess Suite — C++ Engine (`cpp_engine/`)

High-performance C++17 port of the pure-Java software ray tracer suite.
The 3D ray tracing is entirely custom CPU code (`std::thread` workers,
zero hardware acceleration); SDL2 is used only as a minimal hardware
abstraction layer for window creation, pixel-buffer blitting, input and
PCM audio output.

## Layout

- `src/` — rules engines, CPU ray tracer, app/UI, audio, net, CLI
  - `side.hpp`, `chess_piece.*`, `chess_move.*`, `checkers_move.*`
  - `checkers_engine.*` — American/English draughts: mandatory jumps,
    multi-jump chains, crowning, 40-move rule, threefold repetition
  - `chess_engine_moves.cpp`, `chess_engine_rules.cpp`, `chess_engine.hpp` —
    castling, en passant, promotion, check/checkmate, stalemate,
    fifty-move rule, repetition, insufficient material, SAN + coordinate
    parsing, FEN round-trip
  - `game_controller.*` — selection model, clocks, promotion flow,
    remote-move path; headless-safe (no UI dependency)
  - `headless.*` — file/argv simulation harness, no graphics context
  - `vec3.hpp`, `ray.hpp`, `hit.hpp`, `material.hpp`, `primitive.hpp`,
    `area_light.*`, `scene.*`, `camera.hpp` — tracer math (header-heavy)
  - `ray_tracer.hpp` — Whitted-style shading: area-light soft shadows,
    Blinn–Phong specular, Schlick Fresnel reflections, deterministic
    per-pixel sampling (bit-compatible hash with the Java port)
  - `graphics_settings.*` — resolution scale, max bounces, hard/soft
    shadows, samples-per-pixel, presets, `config.ini` load/save
  - `thread_pool.hpp`, `renderer.hpp` — row-chunked `std::thread` pool,
    cancellable renders, parallel/serial bit-identical output
  - `framebuffer.hpp`, `font.cpp` — software pixel buffer, bilinear
    upscale, 5×7 bitmap font
  - `ui_overlay.*` — immediate-mode buttons, steppers, toggles,
    text fields; no retained widgets, no GPU
  - `app_core.cpp`, `app_screens.cpp`, `app_game.cpp`, `app.hpp` —
    menu, settings, net setup, 2D board + 3D views, timers, promotion
    dialog, background HQ/preview render worker
  - `audio.*` — synthesised PCM effects (no audio files); silent
    without an SDL backend
  - `net.*` — TCP peer-to-peer (POSIX/Winsock), `MOVE`/`NEW`/`RESIGN`/
    `CHAT` line protocol; host takes the first-moving side
  - `platform_sdl.cpp` — window, blit, input, audio (only file that
    touches SDL); `platform_stub.cpp` — headless fallback
  - `main.cpp` — CLI: GUI, `--simulate`, `--file`, `--render`, `--shot`,
    `--smoke-gui`
- `tests/` — committed headless tests, no framework (`run_tests`)
- `examples/` — sample moves files; `config.ini` — default graphics
  settings; `shots/` — regenerable UI captures (git-ignored)

## Build, test, run

```sh
cd cpp_engine
make            # build + run all headless tests + CLI smoke checks
make build      # just build ./checkers and ./run_tests
make test       # run ./run_tests + headless CLI smoke checks
./run_tests     # EngineTests + SimulationTests + TracerTests + PeerTests
./checkers                                # SDL2 window UI
./checkers --simulate chess e4 e5 Nf3     # headless, exit 0/2/1
./checkers --file examples/fools_mate.txt
./checkers --render out.ppm chess high    # headless PPM render
./checkers --shot checkers3d shot.ppm     # headless UI capture
./checkers --smoke-gui 5                  # SDL window self-test
```

Requirements: a C++17 compiler, GNU make, pthreads. SDL2 is **optional**
and auto-detected (`pkg-config sdl2`, `sdl2-config`, Homebrew/macOS
prefixes). Without it, `checkers` prints a pointer to the headless
modes and `run_tests` builds fully. A minimal `CMakeLists.txt` is
provided for IDE/Windows users (Windows uses Winsock via the same
`net.*` sources).

`moves.txt` format: first non-comment line is `CHECKERS` or `CHESS`,
then whitespace- or comma-separated moves; `#` and `//` lines are
comments. Output is `GAME:`, `MOVES_APPLIED:`, `RESULT:` (`IN_PROGRESS`,
`WHITE_WINS`, `BLACK_WINS`, `DRAW`) plus reason, optional `ERROR:`, the
board diagram and (chess) `FEN:`. Exit code 0 means every move was
legal, 2 means a move was rejected, 1 means bad usage.

## Graphics settings (scalable intensity)

In-game **Setup** screen or `config.ini` (`[graphics]`):

| Setting | Range | Effect |
|---|---|---|
| `preset` | fast/balanced/high/ultra | one-click bundle |
| `resolution_scale` | 0.25–1.0 | internal buffer size, bilinear-upscaled |
| `max_bounces` | 0–5 | reflection depth (`0` = matte) |
| `shadow_quality` | hard/soft | 1 ray vs N rays to the area light |
| `shadow_samples` | 1–64 | soft-shadow rays per shading point |
| `samples_per_pixel` | 1/4/9 | 1×1, 2×2, 3×3 anti-aliasing grid |

Measured on an Apple M4, 640×480 checkers start, full frame:
`fast` 0.11 s, `balanced` 0.66 s, `high` 1.34 s, `ultra` 4.49 s.
The interactive view renders a capped preview (1 spp, ≤50% scale,
≤1 bounce) while the camera/light moves and resolves to full quality
0.18 s after interaction stops, so orbiting stays fluid while stills
converge to high fidelity. Settings apply live; **Save** writes
`config.ini`. The app never writes `config.ini` implicitly.

## Controls

- 2D/3D toggle (button or keys `2`/`3`), `N` new game, `Esc` back/cancel.
- 3D: left-drag orbits, wheel zooms, right-drag moves the light;
  a plain click picks a square through the same controller as 2D.
- Move assistance: movable pieces get green rings, the selected piece a
  gold ring, quiet destinations green dots, captures red rings — in both
  views. Per-player countdown (1–60 min) flags on time; sounds for move,
  capture, check, win/draw, select and illegal.

## Multiplayer

Host takes the first-moving side (Black in checkers, White in chess);
the guest takes the other. Moves are sent per step (a checkers chain
arrives step by step via the same single-step path the UI uses).
`NEW` restarts, `RESIGN`/`CHAT` are supported. Play across networks with
a reachable host address/port.

Note on the task's WebRTC wording: a real WebRTC stack (ICE, DTLS-SRTP,
SCTP) cannot be built from the C++ standard library alone without
external libraries, so this port — like the Java original — uses direct
socket peer-to-peer carrying the same move messages a WebRTC data
channel would, with no relay server.

## Verification

- `EngineTests` (97), `SimulationTests` (25), `TracerTests` (46),
  `PeerTests` (loopback TCP, 7): 175/175 pass, no display needed
  (`make test`). `run_tests` links no SDL/graphics at all.
- Cross-checked against the Java port: `hash01` integer stream
  bit-identical; FEN, `toRgb`, perft(1–3) = 20/400/8902, Kiwipete
  perft(3) = 97862 exact; a full 96×72 BALANCED render of the checkers
  start is pixel-identical in all 6912 pixels.
- `checkers --shot ...` captures every screen headlessly (see `shots/`
  regeneration); `--smoke-gui` exercises the real SDL window loop.
- `ray_tracer.hpp` documents the CPU-only constraint; the only SDL
  calls in the tree live in `platform_sdl.cpp`, and `run_tests` does not
  link SDL.
