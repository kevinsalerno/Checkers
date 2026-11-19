'use strict';

/* Checkers & Chess Hub browser client: auth, dashboard, boards, live play. */

const $ = (id) => document.getElementById(id);
const store = window.localStorage;

const api = {
  get token() { return store.getItem('hub-token'); },
  set token(t) { if (t) store.setItem('hub-token', t); else store.removeItem('hub-token'); },

  async req(method, path, body) {
    const headers = { 'Content-Type': 'application/json' };
    if (this.token) headers.Authorization = `Bearer ${this.token}`;
    const res = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (res.status === 204) return null;
    let data = null;
    try {
      data = await res.json();
    } catch { /* non-JSON */ }
    if (!res.ok) {
      throw new Error((data && data.error) || `Request failed (${res.status})`);
    }
    return data;
  },

  get(path) { return this.req('GET', path); },
  post(path, body) { return this.req('POST', path, body || {}); },
  put(path, body) { return this.req('PUT', path, body || {}); },
  del(path) { return this.req('DELETE', path); },
};

function show(viewId) {
  for (const v of ['view-auth', 'view-dash', 'view-game']) {
    $(v).classList.toggle('hidden', v !== viewId);
  }
  window.scrollTo(0, 0);
}

// ---------- Auth ----------

async function doAuth(mode) {
  $('auth-error').textContent = '';
  const username = $('auth-username').value.trim();
  const password = $('auth-password').value;
  try {
    const data = await api.req('POST', `/api/${mode}`, { username, password });
    api.token = data.token;
    await openDashboard();
  } catch (err) {
    $('auth-error').textContent = err.message;
  }
}

function signOut() {
  api.token = null;
  closeSocket();
  currentGame = null;
  $('auth-password').value = '';
  $('topbar-user').classList.add('hidden');
  show('view-auth');
}

// ---------- Dashboard ----------

function fmtWhen(iso) {
  try {
    return new Date(`${iso}Z`.replace(/ZZ$/, 'Z')).toLocaleString();
  } catch {
    return iso;
  }
}

async function openDashboard() {
  closeSocket();
  currentGame = null;
  try {
    const { user } = await api.get('/api/me');
    $('topbar-user').classList.remove('hidden');
    $('topbar-name').textContent = user.username;
    $('topbar-elo').textContent = user.elo;
    $('dash-name').textContent = user.username;
    $('dash-elo').textContent = user.elo;
    $('dash-record').textContent =
      `${user.games} games: ${user.wins} won, ${user.losses} lost, ${user.draws} drawn`;
    $('dash-error').textContent = '';
    show('view-dash');
    await Promise.all([loadActive(user), loadHistory(user)]);
  } catch (err) {
    api.token = null;
    $('topbar-user').classList.add('hidden');
    $('auth-error').textContent = err.message;
    show('view-auth');
  }
}

async function loadActive(user) {
  const { games } = await api.get('/api/games');
  const ul = $('active-list');
  ul.innerHTML = '';
  if (games.length === 0) {
    const li = document.createElement('li');
    li.className = 'empty';
    li.textContent = 'No active games — start one above.';
    ul.appendChild(li);
    return;
  }
  for (const g of games) {
    const li = document.createElement('li');
    const foe = g.has_opponent
      ? `vs ${user.username === g.white ? g.black : g.white}`
      : 'practice (both sides you)';
    const span = document.createElement('span');
    span.innerHTML = `<strong>#${g.id}</strong> · ${g.game_type} · ${foe} · ` +
      `${g.side_to_move} to move · ${g.moves_count} moves · ` +
      `<span class="muted">saved ${fmtWhen(g.updated_at)}</span>`;
    const btns = document.createElement('span');
    btns.className = 'row';
    const resume = document.createElement('button');
    resume.className = 'btn small primary';
    resume.textContent = 'Resume';
    resume.addEventListener('click', () => openGame(g.id));
    const del = document.createElement('button');
    del.className = 'btn small danger';
    del.textContent = 'Delete';
    del.title = 'Delete this abandoned game';
    del.addEventListener('click', async () => {
      if (!window.confirm(`Delete ${g.game_type} game #${g.id}?`)) return;
      try {
        await api.del(`/api/games/${g.id}`);
        await openDashboard();
      } catch (err) {
        $('dash-error').textContent = err.message;
      }
    });
    btns.append(resume, del);
    li.append(span, btns);
    ul.appendChild(li);
  }
}

async function loadHistory() {
  const { matches } = await api.get('/api/history?limit=50');
  const tb = $('history-body');
  tb.innerHTML = '';
  if (matches.length === 0) {
    const tr = document.createElement('tr');
    const td = document.createElement('td');
    td.colSpan = 7;
    td.className = 'muted';
    td.textContent = 'No finished games yet — complete a game to record it here.';
    tr.appendChild(td);
    tb.appendChild(tr);
    return;
  }
  for (const m of matches) {
    const tr = document.createElement('tr');
    const cells = [m.game_type, m.white || '—', m.black || '—', m.winner || 'draw',
      String(m.moves_count), m.reason, fmtWhen(m.played_at)];
    for (const c of cells) {
      const td = document.createElement('td');
      td.textContent = c;
      tr.appendChild(td);
    }
    tb.appendChild(tr);
  }
}

// ---------- Game view ----------

let currentGame = null; // public state of the open game
let selected = null; // {square, targets:[{to,promotion?}]}
let socket = null;
let myName = null;

const CHESS_GLYPHS = {
  1: '\u2659', 2: '\u2658', 3: '\u2657', 4: '\u2656', 5: '\u2655', 6: '\u2654',
  '-1': '\u265F', '-2': '\u265E', '-3': '\u265D', '-4': '\u265C', '-5': '\u265B', '-6': '\u265A',
};

function sqName(r, c) {
  return `${'abcdefgh'[c]}${8 - r}`;
}

function mySide() {
  if (!currentGame || !myName) return null;
  const whiteMine = currentGame.white === myName;
  const blackMine = currentGame.black === myName;
  if (whiteMine && blackMine) return 'both'; // solo practice
  if (!currentGame.has_opponent && (whiteMine || blackMine)) return 'both';
  if (whiteMine) return 'white';
  if (blackMine) return 'black';
  return null;
}

async function openGame(id) {
  $('game-error').textContent = '';
  selected = null;
  try {
    const { user } = await api.get('/api/me');
    myName = user.username;
    const { game } = await api.get(`/api/games/${id}`);
    currentGame = game;
    show('view-game');
    renderGame();
    connectSocket(id);
  } catch (err) {
    $('dash-error').textContent = err.message;
    await openDashboard();
  }
}

function statusText() {
  const g = currentGame;
  if (!g) return '';
  if (g.outcome !== 'IN_PROGRESS') {
    const who = g.outcome === 'DRAW' ? "Draw" : `${g.outcome === 'WHITE_WINS' ? 'White' : 'Black'} wins`;
    return `${who} — ${g.reason}`;
  }
  const side = mySide();
  let extra = '';
  if (g.game_type === 'checkers' && g.chain) {
    extra = ` — continue the jump with ${sqName(g.chain[0], g.chain[1])}`;
  }
  if (side === 'both' || side === g.side_to_move) {
    return `Your move (${g.side_to_move})${extra}`;
  }
  return `${g.side_to_move} to move — waiting for opponent${extra}`;
}

function renderGame() {
  const g = currentGame;
  if (!g) return;
  const cap = g.game_type === 'checkers' ? 'Checkers' : 'Chess';
  $('game-title').textContent = `${cap} #${g.id}`;
  $('game-sub').textContent =
    `${g.white || '?'} (White) vs ${g.black || '?'} (Black)` +
    (g.has_opponent ? '' : ' · practice game — share the ID to invite an opponent');
  $('game-status').textContent = statusText();
  $('move-count').textContent = `(${g.moves_count})`;

  const board = $('board');
  board.innerHTML = '';
  board.classList.toggle('checkers', g.game_type === 'checkers');
  for (let r = 0; r < 8; r++) {
    for (let c = 0; c < 8; c++) {
      const d = document.createElement('div');
      const sq = sqName(r, c);
      d.className = `sq ${((r + c) % 2 === 0) ? 'light' : 'dark'}`;
      d.dataset.square = sq;
      paintSquare(d, g, r, c);
      if (selected && selected.square === sq) d.classList.add('sel');
      if (selected) {
        const t = selected.targets.find((x) => x.to === sq);
        if (t) d.classList.add(isCapture(g, selected.square, sq) ? 'cap-target' : 'target');
      }
      d.addEventListener('click', () => onSquare(r, c));
      board.appendChild(d);
    }
  }

  const ml = $('move-list');
  ml.innerHTML = '';
  if (g.game_type === 'chess') {
    for (let i = 0; i < g.moves.length; i += 2) {
      const li = document.createElement('li');
      li.textContent = `${i / 2 + 1}. ${g.moves[i]}${g.moves[i + 1] ? ` ${g.moves[i + 1]}` : ''}`;
      ml.appendChild(li);
    }
  } else {
    for (const mv of g.moves) {
      const li = document.createElement('li');
      li.textContent = mv;
      ml.appendChild(li);
    }
  }
  ml.scrollTop = ml.scrollHeight;
}

function paintSquare(d, g, r, c) {
  const p = g.board[r][c];
  if (p === 0) return;
  if (g.game_type === 'chess') {
    const s = document.createElement('span');
    s.className = p > 0 ? 'w' : 'b';
    s.textContent = CHESS_GLYPHS[String(p)];
    d.appendChild(s);
    return;
  }
  const disc = document.createElement('div');
  disc.className = 'disc ' + (
    p === 1 ? 'wm' : p === 2 ? 'wk' : p === 3 ? 'bm' : 'bk');
  d.appendChild(disc);
}

function pieceSide(g, r, c) {
  const p = g.board[r][c];
  if (p === 0) return null;
  if (g.game_type === 'chess') return p > 0 ? 'white' : 'black';
  return (p === 1 || p === 2) ? 'white' : 'black';
}

function isCapture(g, fromSq, toSq) {
  const f = {
    r: 8 - Number(fromSq[1]),
    c: 'abcdefgh'.indexOf(fromSq[0]),
  };
  const t = {
    r: 8 - Number(toSq[1]),
    c: 'abcdefgh'.indexOf(toSq[0]),
  };
  if (g.game_type === 'checkers') return Math.abs(f.r - t.r) === 2;
  if (fromSq === toSq) return false;
  // En passant lands on an empty square but still captures.
  if (g.game_type === 'chess' && g.state && Array.isArray(g.state.ep)) {
    const [er, ec] = g.state.ep;
    if (er === t.r && ec === t.c && g.board[t.r][t.c] === 0) return true;
  }
  return g.board[t.r][t.c] !== 0;
}

async function onSquare(r, c) {
  const g = currentGame;
  if (!g || g.outcome !== 'IN_PROGRESS') return;
  const sq = sqName(r, c);
  $('game-error').textContent = '';

  if (selected) {
    const hit = selected.targets.find((x) => x.to === sq);
    if (hit) {
      // Pawn reaching the last rank without a chosen piece: ask first.
      if (g.game_type === 'chess' && needsPromotionChoice(g, selected.square, sq)) {
        askPromotion(selected.square, sq);
        return;
      }
      await sendMove(selected.square, sq, hit.promotion || null);
      return;
    }
  }
  if (pieceSide(g, r, c) !== g.side_to_move) {
    selected = null;
    renderGame();
    return;
  }
  try {
    const { targets } = await api.get(`/api/games/${g.id}/legal?from=${sq}`);
    selected = targets.length > 0 ? { square: sq, targets } : null;
  } catch {
    selected = null;
  }
  renderGame();
}

function needsPromotionChoice(g, fromSq, toSq) {
  const fr = 8 - Number(fromSq[1]);
  const fc = 'abcdefgh'.indexOf(fromSq[0]);
  const tr = 8 - Number(toSq[1]);
  const p = g.board[fr][fc];
  if (g.game_type !== 'chess') return false;
  if (Math.abs(p) !== 1) return false; // not a pawn
  return tr === 0 || tr === 7;
}

function askPromotion(fromSq, toSq) {
  const modal = $('promo-modal');
  const box = $('promo-choices');
  box.innerHTML = '';
  const glyphs = { q: '\u2655', r: '\u2656', b: '\u2657', n: '\u2658' };
  for (const [letter, glyph] of Object.entries(glyphs)) {
    const btn = document.createElement('button');
    btn.className = 'btn';
    btn.textContent = glyph;
    btn.title = letter.toUpperCase();
    btn.addEventListener('click', async () => {
      modal.classList.add('hidden');
      await sendMove(fromSq, toSq, letter);
    });
    box.appendChild(btn);
  }
  modal.classList.remove('hidden');
}

async function sendMove(from, to, promotion) {
  const g = currentGame;
  selected = null;
  const payload = { t: 'move', game: g.id, from, to };
  if (promotion) payload.promotion = promotion;
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(payload));
    return; // the server broadcasts the new state back
  }
  try {
    const data = await api.post(`/api/games/${g.id}/move`, { from, to, promotion });
    currentGame = data.game;
    renderGame();
  } catch (err) {
    $('game-error').textContent = err.message;
    renderGame();
  }
}

// ---------- Live socket ----------

function setLive(on) {
  $('live-dot').classList.toggle('off', !on);
  $('live-dot').textContent = on ? 'live' : 'offline';
}

function closeSocket() {
  if (socket) {
    try {
      socket.close();
    } catch { /* already closed */ }
    socket = null;
  }
  setLive(false);
}

function connectSocket(gameId) {
  closeSocket();
  if (!api.token) return;
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
  const url = `${proto}://${window.location.host}/ws?token=${encodeURIComponent(api.token)}`;
  let ws;
  try {
    ws = new WebSocket(url);
  } catch {
    return; // REST fallback still works
  }
  socket = ws;
  ws.addEventListener('open', () => {
    setLive(true);
    ws.send(JSON.stringify({ t: 'join', game: gameId }));
  });
  ws.addEventListener('message', (ev) => {
    let msg;
    try {
      msg = JSON.parse(ev.data);
    } catch {
      return;
    }
    if (msg.t === 'state' && msg.game && msg.game.id === gameId) {
      currentGame = msg.game;
      selected = null;
      $('game-error').textContent = '';
      renderGame();
    } else if (msg.t === 'finished' && msg.game === gameId) {
      currentGame = null;
      api.get(`/api/games/${gameId}`).catch(() => null); // archived; ignore
      $('game-status').textContent =
        `${msg.finished.outcome === 'DRAW' ? 'Draw' : `${msg.finished.winner || '?'} wins`} — ` +
        `${msg.finished.reason}`;
      $('game-error').textContent = '';
      window.alert(`Game over: ${$('game-status').textContent}`);
    } else if (msg.t === 'deleted' && msg.game === gameId) {
      openDashboard();
    } else if (msg.t === 'error') {
      $('game-error').textContent = msg.message;
    }
  });
  ws.addEventListener('close', () => {
    if (socket === ws) {
      socket = null;
      setLive(false);
    }
  });
  ws.addEventListener('error', () => {
    if (socket === ws) {
      socket = null;
      setLive(false);
    }
  });
}

// ---------- Save & Quit / resign ----------

async function saveAndQuit() {
  const g = currentGame;
  if (!g) {
    await openDashboard();
    return;
  }
  $('game-error').textContent = '';
  // Send the full board snapshot to the backend, then leave for the
  // dashboard. Resume later from any device via the active-games list.
  try {
    await api.put(`/api/games/${g.id}`, { state: g.state, moves: g.moves });
    $('game-status').textContent = 'Saved. See you soon!';
  } catch (err) {
    // Two-player games persist automatically after every move.
    $('game-status').textContent = err.message.includes('automatically')
      ? 'Two-player games save automatically after every move.'
      : `Could not save: ${err.message}`;
  }
  closeSocket();
  currentGame = null;
  setTimeout(openDashboard, 450);
}

async function resign() {
  const g = currentGame;
  if (!g) return;
  if (!window.confirm(`Resign ${g.game_type} game #${g.id}?`)) return;
  try {
    const { finished } = await api.post(`/api/games/${g.id}/resign`);
    currentGame = null;
    window.alert(`Game over: ${finished.winner || 'draw'} wins — ${finished.reason}`);
    await openDashboard();
  } catch (err) {
    $('game-error').textContent = err.message;
  }
}

// ---------- Wiring ----------

async function newGame(gameType) {
  $('dash-error').textContent = '';
  try {
    const { game } = await api.post('/api/games', { game_type: gameType });
    await openGame(game.id);
  } catch (err) {
    $('dash-error').textContent = err.message;
  }
}

async function joinGame() {
  $('dash-error').textContent = '';
  const id = Number($('join-id').value);
  if (!Number.isInteger(id) || id <= 0) {
    $('dash-error').textContent = 'Enter a numeric game ID.';
    return;
  }
  try {
    await api.post(`/api/games/${id}/join`);
    $('join-id').value = '';
    await openGame(id);
  } catch (err) {
    $('dash-error').textContent = err.message;
  }
}

function wire() {
  $('btn-login').addEventListener('click', () => doAuth('login'));
  $('btn-register').addEventListener('click', () => doAuth('register'));
  $('auth-password').addEventListener('keydown', (ev) => {
    if (ev.key === 'Enter') doAuth('login');
  });
  $('btn-signout').addEventListener('click', signOut);
  $('btn-new-checkers').addEventListener('click', () => newGame('checkers'));
  $('btn-new-chess').addEventListener('click', () => newGame('chess'));
  $('btn-join').addEventListener('click', joinGame);
  $('btn-save-quit').addEventListener('click', saveAndQuit);
  $('btn-resign').addEventListener('click', resign);
  $('btn-back').addEventListener('click', () => {
    closeSocket();
    currentGame = null;
    openDashboard();
  });
}

wire();
if (api.token) {
  openDashboard();
} else {
  show('view-auth');
}


