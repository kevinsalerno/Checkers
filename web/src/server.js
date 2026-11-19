'use strict';

/**
 * REST API + static frontend (stdlib node:http, no framework).
 *
 * Auth: POST /api/register, POST /api/login, GET /api/me
 * Games: GET/POST /api/games, GET /api/games/:id, PUT /api/games/:id,
 *   DELETE /api/games/:id, POST /api/games/:id/join,
 *   POST /api/games/:id/move, GET /api/games/:id/legal,
 *   POST /api/games/:id/resign
 * History: GET /api/history
 */

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const db = require('./db');
const auth = require('./auth');
const game = require('./game');
const elo = require('./elo');
const { Hub } = require('./realtime');

const USER_RE = /^[a-zA-Z0-9_-]{3,20}$/;
const MAX_BODY = 1024 * 1024;

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(Object.assign(new Error('Body too large'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8');
      if (!text) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(text));
      } catch {
        reject(Object.assign(new Error('Body must be JSON'), { status: 400 }));
      }
    });
    req.on('error', reject);
  });
}

/** Authenticated user or throws 401. */
function requireUser(dbHandle, req) {
  const payload = auth.verifyJwt(auth.bearerToken(req));
  if (!payload || payload.uid == null) {
    throw Object.assign(new Error('Not signed in'), { status: 401 });
  }
  const row = db.findUserById(dbHandle, payload.uid);
  if (!row) throw Object.assign(new Error('Account no longer exists'), { status: 401 });
  return db.publicUser(row);
}

function getGameForParticipant(dbHandle, user, id) {
  const row = Number.isInteger(id) ? db.getGame(dbHandle, id) : null;
  if (!row) throw Object.assign(new Error('Game not found'), { status: 404 });
  if (row.owner_id !== user.id && row.opponent_id !== user.id) {
    throw Object.assign(new Error('Not your game'), { status: 403 });
  }
  return row;
}

function checkUsername(name) {
  if (typeof name !== 'string' || !USER_RE.test(name.trim())) {
    throw Object.assign(
      new Error('Username must be 3-20 letters, digits, _ or -'), { status: 400 });
  }
  return name.trim();
}

const PUBLIC_DIR = path.join(__dirname, '..', 'public');
const STATIC_FILES = {
  '/': { file: 'index.html', type: 'text/html; charset=utf-8' },
  '/index.html': { file: 'index.html', type: 'text/html; charset=utf-8' },
  '/styles.css': { file: 'styles.css', type: 'text/css; charset=utf-8' },
  '/app.js': { file: 'app.js', type: 'text/javascript; charset=utf-8' },
};

function serveStatic(req, res) {
  const entry = STATIC_FILES[req.url.split('?')[0]];
  if (!entry || req.method !== 'GET') return false;
  let body;
  try {
    body = fs.readFileSync(path.join(PUBLIC_DIR, entry.file));
  } catch {
    return false;
  }
  res.writeHead(200, {
    'Content-Type': entry.type,
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
  });
  res.end(body);
  return true;
}

function withUsernames(dbHandle, matches) {
  return matches.map((m) => {
    const name = (id) => (id == null ? null
      : (db.findUserById(dbHandle, id) || {}).username || null);
    return {
      id: m.id,
      game_type: m.game_type,
      white: name(m.white_id),
      black: name(m.black_id),
      winner: name(m.winner_id),
      result: m.result,
      reason: m.reason,
      moves_count: m.moves_count,
      moves: JSON.parse(m.moves || '[]'),
      played_at: m.played_at,
    };
  });
}

async function routeApi(dbHandle, hub, req, res, url) {
  const p = url.pathname;
  const method = req.method;

  // --- registration / login ---
  if (p === '/api/register' && method === 'POST') {
    const body = await readJson(req);
    const username = checkUsername(body.username);
    if (db.findUserByName(dbHandle, username)) {
      throw Object.assign(new Error('Username is taken'), { status: 409 });
    }
    // Hash first so password errors (400) never become "taken" (409) below.
    const passHash = auth.hashPassword(body.password);
    let row;
    try {
      row = db.createUser(dbHandle, username, passHash);
    } catch (err) {
      // Lost a registration race: the name was taken between check and insert.
      throw Object.assign(new Error('Username is taken'), { status: 409 });
    }
    const user = db.publicUser(row);
    return sendJson(res, 201, {
      user, token: auth.signJwt({ uid: user.id, username: user.username }),
    });
  }
  if (p === '/api/login' && method === 'POST') {
    const body = await readJson(req);
    const username = typeof body.username === 'string' ? body.username.trim() : '';
    const row = db.findUserByName(dbHandle, username);
    if (!row || !auth.verifyPassword(body.password || '', row.pass_hash)) {
      throw Object.assign(new Error('Wrong username or password'), { status: 401 });
    }
    const user = db.publicUser(row);
    return sendJson(res, 200, {
      user, token: auth.signJwt({ uid: user.id, username: user.username }),
    });
  }
  if (p === '/api/me' && method === 'GET') {
    return sendJson(res, 200, { user: requireUser(dbHandle, req) });
  }

  // --- match history (Read) ---
  if (p === '/api/history' && method === 'GET') {
    const user = requireUser(dbHandle, req);
    const limit = Math.min(
      Math.max(parseInt(url.searchParams.get('limit') || '50', 10) || 50, 1), 200);
    return sendJson(res, 200, {
      matches: withUsernames(dbHandle, db.listHistoryForUser(dbHandle, user.id, limit)),
    });
  }

  // --- active games: list + create ---
  if (p === '/api/games' && method === 'GET') {
    const user = requireUser(dbHandle, req);
    const rows = db.listGamesForUser(dbHandle, user.id);
    return sendJson(res, 200, {
      games: rows.map((r) => game.publicState(dbHandle, r)),
    });
  }
  if (p === '/api/games' && method === 'POST') {
    const user = requireUser(dbHandle, req);
    const body = await readJson(req);
    if (!game.GAME_TYPES.includes(body.game_type)) {
      throw Object.assign(
        new Error(`game_type must be one of: ${game.GAME_TYPES.join(', ')}`), { status: 400 });
    }
    const row = db.createGame(dbHandle, user.id, body.game_type,
      JSON.stringify(game.newEngineState(body.game_type)));
    return sendJson(res, 201, { game: game.publicState(dbHandle, row) });
  }

  // --- single game sub-routes ---
  const m = p.match(/^\/api\/games\/(\d+)(\/[a-z]+)?$/);
  if (m) {
    const id = Number(m[1]);
    const sub = m[2] || '';
    const user = requireUser(dbHandle, req);

    // Joining is open to any signed-in user: it is what MAKES them a
    // participant. All other sub-routes require participation.
    if (sub === '/join' && method === 'POST') {
      const target = db.getGame(dbHandle, id);
      if (!target) throw Object.assign(new Error('Game not found'), { status: 404 });
      if (target.owner_id === user.id) {
        throw Object.assign(new Error('You already play in this game'), { status: 409 });
      }
      if (target.opponent_id != null && target.opponent_id !== user.id) {
        throw Object.assign(new Error('Game already has an opponent'), { status: 409 });
      }
      const joined = db.setOpponent(dbHandle, id, user.id);
      const joinedState = game.publicState(dbHandle, joined);
      hub.broadcast(id, { t: 'state', game: joinedState });
      return sendJson(res, 200, { game: joinedState });
    }

    const row = getGameForParticipant(dbHandle, user, id);

    if ((sub === '' || sub === '/state') && method === 'GET') {
      return sendJson(res, 200, { game: game.publicState(dbHandle, row) });
    }

    if (sub === '/legal' && method === 'GET') {
      const from = url.searchParams.get('from') || '';
      try {
        return sendJson(res, 200, { from, targets: game.legalTargets(row, from) });
      } catch (err) {
        throw Object.assign(new Error(`Bad square: ${from}`), { status: 400 });
      }
    }

    if (sub === '/move' && method === 'POST') {
      const body = await readJson(req);
      if (typeof body.from !== 'string' || typeof body.to !== 'string') {
        throw Object.assign(new Error('Move needs {from, to} squares'), { status: 400 });
      }
      const { engine, notation } = game.applyPlayerMove(row, user.id, body);
      const { row: updated, finished } =
        game.persistAndMaybeFinish(dbHandle, row, engine, notation);
      const state = finished
        ? { ...game.publicState(dbHandle, { ...row, state: JSON.stringify(engine.serialize()),
            moves: JSON.stringify([...game.moveList(row), notation]) }, engine),
            archived: true }
        : game.publicState(dbHandle, updated, engine);
      hub.broadcast(row.id, { t: 'state', game: state });
      if (finished) hub.broadcast(row.id, { t: 'finished', game: row.id, finished });
      return sendJson(res, 200, { game: state, finished });
    }

    // Save & Quit support: replace the stored position of a solo game.
    // (Games with an opponent progress only through validated moves.)
    if (sub === '' && method === 'PUT') {
      if (row.owner_id !== user.id) {
        throw Object.assign(new Error('Only the owner can save this game'), { status: 403 });
      }
      if (row.opponent_id != null) {
        throw Object.assign(
          new Error('Two-player games save automatically after each move'), { status: 409 });
      }
      const body = await readJson(req);
      if (body == null || typeof body.state !== 'object') {
        throw Object.assign(new Error('PUT needs a {state} engine snapshot'), { status: 400 });
      }
      if (body.state.game !== row.game_type) {
        throw Object.assign(new Error('State does not match this game'), { status: 400 });
      }
      let engine;
      try {
        engine = game.hydrate({ game_type: row.game_type,
          state: JSON.stringify(body.state) });
      } catch (err) {
        throw Object.assign(new Error(`Corrupt state: ${err.message}`), { status: 400 });
      }
      if (Array.isArray(body.moves)) {
        db.saveGameState(dbHandle, row.id, JSON.stringify(engine.serialize()),
          JSON.stringify(body.moves.map(String)));
      } else {
        const keep = db.getGame(dbHandle, row.id);
        db.saveGameState(dbHandle, row.id, JSON.stringify(engine.serialize()), keep.moves);
      }
      const updated = db.getGame(dbHandle, row.id);
      const state = game.publicState(dbHandle, updated, engine);
      hub.broadcast(row.id, { t: 'state', game: state });
      return sendJson(res, 200, { game: state, saved: true });
    }

    if (sub === '/resign' && method === 'POST') {
      const creator = game.creatorSeat(row.game_type);
      const whiteId = creator === 'white' ? row.owner_id : row.opponent_id;
      const blackId = creator === 'black' ? row.owner_id : row.opponent_id;
      // The resigner loses their own seat; solo players lose the side to move.
      let loserSeat;
      if (row.opponent_id == null) {
        loserSeat = game.sideToMoveOf(row.game_type, game.hydrate(row));
      } else if (user.id === whiteId) {
        loserSeat = 'white';
      } else {
        loserSeat = 'black';
      }
      const outcome = loserSeat === 'white' ? 'BLACK_WINS' : 'WHITE_WINS';
      const winnerId = loserSeat === 'white' ? blackId : whiteId;
      const moves = game.moveList(row);
      const distinct = whiteId != null && blackId != null && whiteId !== blackId;
      if (distinct) {
        const w = db.findUserById(dbHandle, whiteId);
        const b = db.findUserById(dbHandle, blackId);
        const scoreW = outcome === 'WHITE_WINS' ? 1 : 0;
        const [newW, newB] = elo.updateRatings(w.elo, b.elo, scoreW);
        db.applyElo(dbHandle, whiteId, newW, scoreW === 1 ? 'win' : 'loss');
        db.applyElo(dbHandle, blackId, newB, scoreW === 0 ? 'win' : 'loss');
      }
      const matchId = db.recordMatch(dbHandle, {
        game_type: row.game_type,
        white_id: whiteId,
        black_id: blackId,
        winner_id: winnerId,
        result: outcome,
        reason: 'Resignation',
        moves_count: moves.length,
        moves: JSON.stringify(moves),
      });
      db.deleteGame(dbHandle, row.id);
      const finished = {
        match_id: matchId,
        outcome,
        reason: 'Resignation',
        winner: winnerId == null ? null
          : (db.findUserById(dbHandle, winnerId) || {}).username || null,
        elo_updated: distinct,
      };
      hub.broadcast(row.id, { t: 'finished', game: row.id, finished });
      return sendJson(res, 200, { game: null, finished });
    }

    if (sub === '' && method === 'DELETE') {
      db.deleteGame(dbHandle, row.id);
      hub.broadcast(row.id, { t: 'deleted', game: row.id });
      res.writeHead(204);
      res.end();
      return;
    }
  }
  return false; // not an API route
}

function createApp(dbHandle) {
  const hub = new Hub({
    verifyToken: (token) => {
      const payload = auth.verifyJwt(token);
      if (!payload || payload.uid == null) return null;
      const rowUser = db.findUserById(dbHandle, payload.uid);
      return rowUser ? db.publicUser(rowUser) : null;
    },
    onJoin: (user, gameId) => {
      const row = getGameForParticipant(dbHandle, user, gameId);
      return game.publicState(dbHandle, row);
    },
    onMove: (user, gameId, msg) => {
      const row = getGameForParticipant(dbHandle, user, gameId);
      if (typeof msg.from !== 'string' || typeof msg.to !== 'string') {
        throw new Error('Move needs {from, to} squares');
      }
      const { engine, notation } = game.applyPlayerMove(row, user.id, msg);
      const { row: updated, finished } =
        game.persistAndMaybeFinish(dbHandle, row, engine, notation);
      const state = finished
        ? { ...game.publicState(dbHandle, { ...row, state: JSON.stringify(engine.serialize()),
            moves: JSON.stringify([...game.moveList(row), notation]) }, engine),
            archived: true }
        : game.publicState(dbHandle, updated, engine);
      return { state, finished };
    },
  });

  const server = http.createServer(async (req, res) => {
    let url;
    try {
      url = new URL(req.url, 'http://localhost');
    } catch {
      return sendJson(res, 400, { error: 'Bad request URL' });
    }
    try {
      if (url.pathname === '/ws') {
        return sendJson(res, 426, { error: 'Use a WebSocket client for /ws' });
      }
      if (url.pathname.startsWith('/api/')) {
        const handled = await routeApi(dbHandle, hub, req, res, url);
        if (!handled) return sendJson(res, 404, { error: 'Unknown API route' });
        return;
      }
      if (!serveStatic(req, res)) {
        return sendJson(res, 404, { error: 'Not found' });
      }
    } catch (err) {
      if (res.headersSent) {
        try { res.end(); } catch { /* closed */ }
        return;
      }
      const status = err.status || 500;
      if (status === 500) console.error(err);
      sendJson(res, status, { error: err.message || 'Internal error' });
    }
  });

  server.on('upgrade', (req, socket, head) => hub.handleUpgrade(req, socket, head));

  const heartbeat = setInterval(() => {
    for (const conn of [...hub.conns]) {
      if (!conn.alive) continue;
      conn.ping();
    }
  }, 30000);
  if (typeof heartbeat.unref === 'function') heartbeat.unref();

  return { server, hub, close: () => { clearInterval(heartbeat); server.close(); } };
}

module.exports = { createApp, sendJson };

