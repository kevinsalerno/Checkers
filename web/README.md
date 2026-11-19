# Checkers & Chess Hub — full-stack web port

A complete end-to-end web application for the Checkers & Chess suite:
register and sign in, play Checkers (American/English draughts) and Chess
in the browser against a friend live or solo, save in-progress games and
resume them later from any device, and track ELO plus match history.

Rules are enforced server-side by JS ports of the desktop engines
(`src/CheckersEngine.java`, `src/ChessEngine.java`): mandatory captures
and complete multi-jump chains in Checkers (black moves first); full
chess move generation with pins, castling, en passant, promotion,
check/checkmate/stalemate, fifty-move rule, threefold repetition and
insufficient material.

## Layout

- `package.json` — `npm start` runs the server, `npm test` runs the suite.
  Zero dependencies: Node.js standard library only (`node:http` for the
  REST API + static files, hand-rolled RFC 6455 WebSockets, `node:sqlite`
  for persistence, `node:crypto` for scrypt passwords and signed tokens).
- `src/index.js` — entry point; serves **synchronously in the foreground**.
- `src/server.js` — REST API (`/api/...`) and static file serving.
- `src/realtime.js` — live multiplayer hub (`/ws`): join, validated
  moves, state broadcasts to the room.
- `src/db.js` — SQLite schema and queries (`users`, `active_games`,
  `matches`).
- `src/auth.js` — scrypt password hashes, HS256 signed session tokens.
- `src/game.js` — engine hydration, move validation, public state views,
  finishing (history + ELO).
- `src/elo.js` — standard ELO update, K-factor 32.
- `src/rules/checkers.js`, `src/rules/chess.js` — the rules engines.
- `public/` — browser client (`index.html`, `styles.css`, `app.js`):
  auth, dashboard (profile/ELO, past matches, active games), DOM boards
  with click-to-move and legal-move hints, promotion picker, Save & Quit.
- `tests/` — maintained suite, run with `npm test`:
  `rules.test.js` (20 engine tests incl. perft), `api.test.js` (8
  end-to-end tests: auth, CRUD, save/resume, history+ELO, live sockets).
- `data/` — SQLite file lives here at runtime (git-ignored).

## Build and run from a blank checkout

Requirements: Node.js 22.5+ (for the built-in `node:sqlite`), no browser
needed for the backend tests.

```sh
cd web
npm start
```

Then open **http://localhost:3000** in your own browser. The server runs
in the foreground; stop it with Ctrl-C. No install step is needed
(`npm install` is a harmless no-op — there is nothing to fetch).

> You will see one `ExperimentalWarning: SQLite is an experimental
> feature` line on startup. It is benign: the database API used here
> (`node:sqlite`) is stable in practice and the full test suite covers
> every query the app makes.

Use `PORT=4000 npm start` or `HUB_DB=/path/to/file.sqlite` to change the
port or database location. `HUB_JWT_SECRET` sets the token signing key
(default is a dev-only value).

## REST API (JSON; `Authorization: Bearer <token>` after login)

- `POST /api/register {username, password}` → 201 `{user, token}`
- `POST /api/login {username, password}` → 200 `{user, token}`
- `GET /api/me` → `{user}` (profile + ELO + W/L/D)
- `POST /api/games {game_type: "checkers"|"chess"}` → 201 `{game}` (create)
- `GET /api/games` → `{games}` (your active games)
- `GET /api/games/:id` → `{game}` (fetch/resume one record)
- `PUT /api/games/:id {state, moves}` → 200 `{game, saved:true}` (edit:
  Save & Quit for solo practice games; two-player games persist
  automatically after every validated move)
- `POST /api/games/:id/move {from, to, promotion?}` → 200
  `{game, finished}` (rules-validated move; `finished` set when the game
  ends, at which point the record is archived to match history)
- `GET /api/games/:id/legal?from=e2` → `{from, targets}` (move hints)
- `POST /api/games/:id/join` → join as the opponent (second seat)
- `POST /api/games/:id/resign` → end the game, loss recorded
- `DELETE /api/games/:id` → 204 (delete an abandoned game)
- `GET /api/history?limit=50` → `{matches}` (past matches for the user)

Squares are algebraic (`"e2"`); checkers chains are played one jump at a
time while the engine holds the turn mid-chain.

## Live multiplayer (`/ws?token=<token>`, WebSocket JSON frames)

- `{t:"join", game}` → server replies `{t:"state", game}` to you and
  `{t:"presence", players}` to the room.
- `{t:"move", game, from, to, promotion?}` → the backend validates the
  move against the rules engine (wrong turn or illegal move yields
  `{t:"error", message}` to the sender only) and broadcasts
  `{t:"state", game}` to the room, plus `{t:"finished", ...}` when the
  game ends.
- `{t:"leave", game}` → leave the room.

Moves sent over a dead socket fall back to `POST /api/games/:id/move`,
so play never loses a move to a dropped connection.

## Verification

`npm test` runs 28 tests with the built-in runner — no browser, no
background server (each run boots the app on an ephemeral port against
an in-memory database and shuts it down):

- 20 engine tests: checkers captures/chains/crowning/draws/serialization,
  chess perft 20/400/8902 (startpos) and 97862 (Kiwipete), mate,
  stalemate, castling, en passant, promotion, pins, material, FEN and
  repetition.
- 8 integration tests: the full user journey — register/login, create,
  move, save & resume, history, delete, join, seat-locking, resignation,
  ELO changes, and two live-socket clients playing each other.

Visual UI testing is left to the human reviewer: run `npm start`, open
http://localhost:3000, register two accounts (a second browser profile
or window for the opponent), start a game, and use the numeric game ID
to join from the other account.
