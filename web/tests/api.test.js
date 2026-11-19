'use strict';

/**
 * Full-stack API tests: auth, game CRUD, validated moves, save/resume,
 * match history + ELO, resignation, static frontend, and live WebSocket
 * play between two clients. Boots the real server on an ephemeral port
 * with an in-memory database. No browser needed.
 */

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const crypto = require('node:crypto');
const db = require('../src/db');
const { createApp } = require('../src/server');

let base;
let app;
let dbo;

before(async () => {
  dbo = db.openDb(':memory:');
  app = createApp(dbo);
  await new Promise((resolve) => app.server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${app.server.address().port}`;
});

after(async () => {
  for (const c of [...openSockets]) {
    try { c.destroy(); } catch { /* gone */ }
  }
  app.close();
  await new Promise((resolve) => app.server.close(resolve));
});

const openSockets = new Set();

async function api(method, path, token, body) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`${base}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let data = null;
  try {
    data = await res.json();
  } catch { /* empty */ }
  return { status: res.status, data };
}

/** Minimal WebSocket client (masked text frames, ping-aware). */
class WsClient {
  constructor(socket, port, token) {
    this.socket = socket;
    this.buf = Buffer.alloc(0);
    this.queue = [];
    this.waiters = [];
    this.attached = false;
    socket.on('close', () => this._failAll(new Error('socket closed')));
    socket.on('error', () => {});
    this.port = port;
    this.token = token;
  }

  /** Attach the frame parser once the HTTP handshake is complete. */
  _attach(leftover) {
    this.attached = true;
    this.socket.on('data', (chunk) => this._push(chunk));
    if (leftover && leftover.length > 0) this._push(leftover);
  }

  static connect(port, token) {
    return new Promise((resolve, reject) => {
      const socket = net.connect(port, '127.0.0.1', () => {
        const key = crypto.randomBytes(16).toString('base64');
        socket.write(
          'GET /ws?token=' + encodeURIComponent(token) + ' HTTP/1.1\r\n' +
          'Host: 127.0.0.1\r\n' +
          'Upgrade: websocket\r\n' +
          'Connection: Upgrade\r\n' +
          `Sec-WebSocket-Key: ${key}\r\n` +
          'Sec-WebSocket-Version: 13\r\n\r\n');
      });
      openSockets.add(socket);
      socket.on('close', () => openSockets.delete(socket));
      const client = new WsClient(socket, port, token);
      let header = Buffer.alloc(0);
      const onData = (chunk) => {
        header = Buffer.concat([header, chunk]);
        const end = header.indexOf('\r\n\r\n');
        if (end < 0) return;
        const status = header.subarray(0, end).toString();
        socket.off('data', onData);
        if (!status.includes('101')) {
          reject(new Error(`WS handshake failed: ${status.split('\r\n')[0]}`));
          socket.destroy();
          return;
        }
        client._attach(header.subarray(end + 4));
        resolve(client);
      };
      socket.on('data', onData);
      socket.on('error', reject);
    });
  }

  _push(chunk) {
    this.buf = Buffer.concat([this.buf, chunk]);
    for (;;) {
      if (this.buf.length < 2) return;
      const opcode = this.buf[0] & 0x0f;
      let len = this.buf[1] & 0x7f;
      let off = 2;
      if (len === 126) {
        if (this.buf.length < 4) return;
        len = this.buf.readUInt16BE(2);
        off = 4;
      } else if (len === 127) {
        if (this.buf.length < 10) return;
        len = Number(this.buf.readBigUInt64BE(2));
        off = 10;
      }
      if (this.buf.length < off + len) return;
      const payload = this.buf.subarray(off, off + len);
      this.buf = this.buf.subarray(off + len);
      if (opcode === 0x9) {
        this._pong(payload);
        continue;
      }
      if (opcode === 0x8) {
        this.socket.destroy();
        continue;
      }
      if (opcode !== 0x1) continue;
      const text = payload.toString('utf8');
      if (this.waiters.length > 0) this.waiters.shift()(text);
      else this.queue.push(text);
    }
  }

  _pong(payload) {
    const h = Buffer.alloc(2);
    h[0] = 0x8a;
    h[1] = 0;
    this.socket.write(h);
  }

  _failAll(err) {
    while (this.waiters.length > 0) this.waiters.shift()(null);
  }

  send(obj) {
    const payload = Buffer.from(JSON.stringify(obj));
    const mask = crypto.randomBytes(4);
    let header;
    if (payload.length < 126) {
      header = Buffer.alloc(2);
      header[1] = 0x80 | payload.length;
    } else {
      header = Buffer.alloc(4);
      header[1] = 0x80 | 126;
      header.writeUInt16BE(payload.length, 2);
    }
    header[0] = 0x81;
    const masked = Buffer.alloc(payload.length);
    for (let i = 0; i < payload.length; i++) masked[i] = payload[i] ^ mask[i % 4];
    this.socket.write(Buffer.concat([header, mask, masked]));
  }

  next(timeoutMs = 3000) {
    if (this.queue.length > 0) return Promise.resolve(JSON.parse(this.queue.shift()));
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((w) => w !== onMsg);
        reject(new Error('timed out waiting for WS message'));
      }, timeoutMs);
      const onMsg = (text) => {
        clearTimeout(timer);
        resolve(text === null ? null : JSON.parse(text));
      };
      this.waiters.push(onMsg);
    });
  }

  close() {
    this.socket.destroy();
  }
}

let tokenA;
let tokenB;

test('auth: register, duplicate, weak password, login, bad login', async () => {
  let r = await api('POST', '/api/register', null, { username: 'alice', password: 'secret1' });
  assert.equal(r.status, 201);
  assert.equal(r.data.user.username, 'alice');
  assert.equal(r.data.user.elo, 1200);
  assert.ok(r.data.token);
  tokenA = r.data.token;

  r = await api('POST', '/api/register', null, { username: 'alice', password: 'secret1' });
  assert.equal(r.status, 409);

  r = await api('POST', '/api/register', null, { username: 'bob', password: 'short' });
  assert.equal(r.status, 400);

  r = await api('POST', '/api/register', null, { username: 'x', password: 'secret12' });
  assert.equal(r.status, 400);

  r = await api('POST', '/api/register', null, { username: 'bob', password: 'secret22' });
  assert.equal(r.status, 201);
  tokenB = r.data.token;

  r = await api('POST', '/api/login', null, { username: 'alice', password: 'secret1' });
  assert.equal(r.status, 200);
  assert.equal(r.data.user.username, 'alice');

  r = await api('POST', '/api/login', null, { username: 'alice', password: 'wrongpw' });
  assert.equal(r.status, 401);

  r = await api('GET', '/api/me', 'bogus-token');
  assert.equal(r.status, 401);

  r = await api('GET', '/api/me', tokenA);
  assert.equal(r.status, 200);
  assert.equal(r.data.user.username, 'alice');
});

test('games: create validates game_type; solo moves; illegal rejected', async () => {
  let r = await api('POST', '/api/games', tokenA, {});
  assert.equal(r.status, 400);

  r = await api('POST', '/api/games', tokenA, { game_type: 'go' });
  assert.equal(r.status, 400);

  r = await api('POST', '/api/games', tokenA, { game_type: 'chess' });
  assert.equal(r.status, 201);
  const id = r.data.game.id;
  assert.equal(r.data.game.game_type, 'chess');
  assert.equal(r.data.game.white, 'alice');
  assert.equal(r.data.game.side_to_move, 'white');
  assert.equal(r.data.game.moves_count, 0);

  // Solo practice: the owner may move either side.
  r = await api('POST', `/api/games/${id}/move`, tokenA, { from: 'e2', to: 'e5' });
  assert.equal(r.status, 400); // not a pawn move

  r = await api('POST', `/api/games/${id}/move`, tokenA, { from: 'e2', to: 'e4' });
  assert.equal(r.status, 200);
  assert.equal(r.data.game.side_to_move, 'black');
  assert.deepEqual(r.data.game.moves, ['e2e4']);

  r = await api('GET', `/api/games/${id}/legal?from=e7`, tokenA);
  assert.equal(r.status, 200);
  const targets = r.data.targets.map((t) => t.to).sort();
  assert.deepEqual(targets, ['e5', 'e6']);

  r = await api('POST', `/api/games/${id}/move`, tokenA, { from: 'e7', to: 'e5' });
  assert.equal(r.status, 200);

  // Game shows up in the active list with its move history.
  r = await api('GET', '/api/games', tokenA);
  assert.equal(r.status, 200);
  assert.equal(r.data.games.filter((g) => g.id === id).length, 1);

  // Clean up so later tests start from an empty board list.
  r = await api('DELETE', `/api/games/${id}`, tokenA);
  assert.equal(r.status, 204);
  r = await api('GET', `/api/games/${id}`, tokenA);
  assert.equal(r.status, 404);
});

test('games: save (PUT) round-trips the board state for resume', async () => {
  let r = await api('POST', '/api/games', tokenA, { game_type: 'checkers' });
  const id = r.data.game.id;
  assert.equal(r.data.game.side_to_move, 'black');

  await api('POST', `/api/games/${id}/move`, tokenA, { from: 'b6', to: 'a5' });

  // Save & Quit: send the snapshot back verbatim; resume reads it back.
  const before = (await api('GET', `/api/games/${id}`, tokenA)).data.game;
  r = await api('PUT', `/api/games/${id}`, tokenA,
    { state: before.state, moves: before.moves });
  assert.equal(r.status, 200);
  assert.equal(r.data.saved, true);

  const afterResume = (await api('GET', `/api/games/${id}`, tokenA)).data.game;
  assert.deepEqual(afterResume.board, before.board);
  assert.equal(afterResume.side_to_move, 'white');
  assert.deepEqual(afterResume.moves, ['b6-a5']);

  // Corrupt snapshots are refused without touching the record.
  r = await api('PUT', `/api/games/${id}`, tokenA, { state: { game: 'checkers' } });
  assert.equal(r.status, 400);
  r = await api('PUT', `/api/games/${id}`, tokenA, { state: { game: 'chess' } });
  assert.equal(r.status, 400);

  // A stranger cannot see or edit the game.
  r = await api('GET', `/api/games/${id}`, tokenB);
  assert.equal(r.status, 403);
  r = await api('PUT', `/api/games/${id}`, tokenB, { state: before.state });
  assert.equal(r.status, 403);

  await api('DELETE', `/api/games/${id}`, tokenA);
});

test('multiplayer: join locks seats; fool\'s mate records history + ELO', async () => {
  let r = await api('POST', '/api/games', tokenA, { game_type: 'chess' });
  const id = r.data.game.id;

  // The owner cannot "join" their own game; a stranger can.
  r = await api('POST', `/api/games/${id}/join`, tokenA);
  assert.equal(r.status, 409);
  r = await api('POST', `/api/games/${id}/join`, tokenB);
  assert.equal(r.status, 200);
  assert.equal(r.data.game.white, 'alice');
  assert.equal(r.data.game.black, 'bob');

  // Seats are locked now: white (alice) cannot play black's moves.
  r = await api('POST', `/api/games/${id}/move`, tokenA, { from: 'f2', to: 'f3' });
  assert.equal(r.status, 200);
  r = await api('POST', `/api/games/${id}/move`, tokenA, { from: 'e7', to: 'e5' });
  assert.equal(r.status, 409);

  // Fool's mate: 1.f3 e5 2.g4 Qh4# — black (bob) wins.
  await api('POST', `/api/games/${id}/move`, tokenB, { from: 'e7', to: 'e5' });
  await api('POST', `/api/games/${id}/move`, tokenA, { from: 'g2', to: 'g4' });
  r = await api('POST', `/api/games/${id}/move`, tokenB, { from: 'd8', to: 'h4' });
  assert.equal(r.status, 200);
  assert.equal(r.data.finished.outcome, 'BLACK_WINS');
  assert.equal(r.data.finished.reason, 'Checkmate');
  assert.equal(r.data.finished.winner, 'bob');
  assert.equal(r.data.finished.elo_updated, true);
  assert.equal(r.data.game.archived, true);

  // Finished games leave the active table...
  r = await api('GET', `/api/games/${id}`, tokenA);
  assert.equal(r.status, 404);
  r = await api('GET', '/api/games', tokenA);
  assert.equal(r.data.games.filter((g) => g.id === id).length, 0);

  // ...and land in both players' match history with ELO applied.
  const ha = (await api('GET', '/api/history', tokenA)).data.matches;
  assert.equal(ha.length, 1);
  assert.equal(ha[0].game_type, 'chess');
  assert.equal(ha[0].white, 'alice');
  assert.equal(ha[0].black, 'bob');
  assert.equal(ha[0].winner, 'bob');
  assert.equal(ha[0].result, 'BLACK_WINS');
  assert.equal(ha[0].moves_count, 4);
  const hb = (await api('GET', '/api/history', tokenB)).data.matches;
  assert.equal(hb.length, 1);

  const alice = (await api('GET', '/api/me', tokenA)).data.user;
  const bob = (await api('GET', '/api/me', tokenB)).data.user;
  assert.ok(alice.elo < 1200 && bob.elo > 1200);
  assert.equal(alice.losses, 1);
  assert.equal(bob.wins, 1);
});

test('multiplayer: resign ends the game and records the loss', async () => {
  let r = await api('POST', '/api/games', tokenB, { game_type: 'checkers' });
  const id = r.data.game.id;
  assert.equal(r.data.game.black, 'bob'); // creator holds the first-moving seat
  await api('POST', `/api/games/${id}/join`, tokenA);
  r = await api('POST', `/api/games/${id}/resign`, tokenA);
  assert.equal(r.status, 200);
  assert.equal(r.data.finished.outcome, 'BLACK_WINS');
  assert.equal(r.data.finished.reason, 'Resignation');
  assert.equal(r.data.finished.winner, 'bob');
  const h = (await api('GET', '/api/history', tokenB)).data.matches;
  assert.ok(h.some((m) => m.reason === 'Resignation' && m.winner === 'bob'));
});

test('websocket: two clients join, moves broadcast, errors reported', async () => {
  const port = app.server.address().port;
  let r = await api('POST', '/api/games', tokenA, { game_type: 'chess' });
  const id = r.data.game.id;
  await api('POST', `/api/games/${id}/join`, tokenB);

  const alice = await WsClient.connect(port, tokenA);
  const bob = await WsClient.connect(port, tokenB);

  alice.send({ t: 'join', game: id });
  let msg = await alice.next();
  assert.equal(msg.t, 'state');
  assert.equal(msg.game.id, id);

  bob.send({ t: 'join', game: id });
  msg = await bob.next();
  assert.equal(msg.t, 'state');
  // Bob's arrival is announced to the room; drain it before the moves.
  msg = await alice.next();
  assert.equal(msg.t, 'presence');
  assert.deepEqual(msg.players.sort(), ['alice', 'bob']);

  // Alice (white) moves over the socket; both clients get the new board.
  alice.send({ t: 'move', game: id, from: 'e2', to: 'e4' });
  const [toAlice, toBob] = await Promise.all([alice.next(), bob.next()]);
  assert.equal(toAlice.t, 'state');
  assert.equal(toBob.t, 'state');
  assert.deepEqual(toBob.game.moves, ['e2e4']);
  assert.equal(toBob.game.side_to_move, 'black');

  // Illegal move over the socket: only the sender gets an error.
  alice.send({ t: 'move', game: id, from: 'e2', to: 'e4' });
  msg = await alice.next();
  assert.equal(msg.t, 'error');

  // Black replies; bob's move reaches alice live.
  bob.send({ t: 'move', game: id, from: 'e7', to: 'e5' });
  msg = await alice.next();
  assert.equal(msg.t, 'state');
  assert.deepEqual(msg.game.moves, ['e2e4', 'e7e5']);
  await bob.next(); // bob's own broadcast copy

  alice.close();
  bob.close();
  await api('DELETE', `/api/games/${id}`, tokenA);
});

test('websocket: bad token is refused at the handshake', async () => {
  const port = app.server.address().port;
  await assert.rejects(WsClient.connect(port, 'nope'), /handshake failed/);
});

test('frontend: static files are served', async () => {
  const page = await fetch(`${base}/`);
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-type'), /html/);
  const html = await page.text();
  assert.match(html, /Checkers &amp; Chess Hub/);
  assert.match(html, /Save &amp; Quit/);

  const js = await fetch(`${base}/app.js`);
  assert.equal(js.status, 200);
  assert.match(js.headers.get('content-type'), /javascript/);

  const css = await fetch(`${base}/styles.css`);
  assert.equal(css.status, 200);

  const missing = await fetch(`${base}/nope`);
  assert.equal(missing.status, 404);
});


