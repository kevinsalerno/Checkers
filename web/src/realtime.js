'use strict';

/**
 * Minimal RFC 6455 WebSocket server over node:http Upgrade (no deps).
 * Supports text messages (JSON), ping/pong, close and fragmented frames.
 */

const crypto = require('node:crypto');

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

function acceptKey(key) {
  return crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
}

/** Encode an unmasked server text frame. */
function encodeText(text) {
  const payload = Buffer.from(text);
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.alloc(2);
    header[0] = 0x81;
    header[1] = len;
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x81;
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81;
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([header, payload]);
}

function encodePong(data) {
  const h = Buffer.alloc(2);
  h[0] = 0x8a;
  h[1] = data.length & 0x7f;
  return Buffer.concat([h, data]);
}

/**
 * Incremental client-frame parser. Calls hooks.onText(string),
 * hooks.onPing(buffer), hooks.onClose(). Throws on protocol errors.
 */
class FrameParser {
  constructor(hooks) {
    this.hooks = hooks;
    this.buf = Buffer.alloc(0);
    this.fragments = [];
    this.fragOpcode = 0;
  }

  push(chunk) {
    this.buf = Buffer.concat([this.buf, chunk]);
    for (;;) {
      if (this.buf.length < 2) return;
      const fin = (this.buf[0] & 0x80) !== 0;
      const opcode = this.buf[0] & 0x0f;
      const masked = (this.buf[1] & 0x80) !== 0;
      let len = this.buf[1] & 0x7f;
      let off = 2;
      if (len === 126) {
        if (this.buf.length < 4) return;
        len = this.buf.readUInt16BE(2);
        off = 4;
      } else if (len === 127) {
        if (this.buf.length < 10) return;
        const big = this.buf.readBigUInt64BE(2);
        if (big > BigInt(8 * 1024 * 1024)) throw new Error('frame too large');
        len = Number(big);
        off = 10;
      }
      const maskLen = masked ? 4 : 0;
      if (this.buf.length < off + maskLen + len) return;
      let payload = this.buf.subarray(off + maskLen, off + maskLen + len);
      if (masked) {
        const mask = this.buf.subarray(off, off + 4);
        const out = Buffer.alloc(len);
        for (let i = 0; i < len; i++) out[i] = payload[i] ^ mask[i % 4];
        payload = out;
      }
      this.buf = this.buf.subarray(off + maskLen + len);

      if (opcode === 0x8) {
        this.hooks.onClose();
        return;
      }
      if (opcode === 0x9) {
        this.hooks.onPing(payload);
        continue;
      }
      if (opcode === 0xa) continue; // pong
      if (opcode === 0x0) {
        // continuation
        if (this.fragOpcode === 0) throw new Error('stray continuation');
        this.fragments.push(payload);
        if (fin) this._emitFragments();
        continue;
      }
      if (opcode === 0x1 || opcode === 0x2) {
        if (this.fragOpcode !== 0) throw new Error('interleaved fragment');
        if (opcode === 0x2) throw new Error('binary frames not supported');
        if (fin) {
          this.hooks.onText(payload.toString('utf8'));
        } else {
          this.fragOpcode = opcode;
          this.fragments = [payload];
        }
        continue;
      }
      throw new Error(`unsupported opcode ${opcode}`);
    }
  }

  _emitFragments() {
    const text = Buffer.concat(this.fragments).toString('utf8');
    this.fragments = [];
    this.fragOpcode = 0;
    this.hooks.onText(text);
  }
}

class Connection {
  constructor(socket, user, hub) {
    this.socket = socket;
    this.user = user;
    this.hub = hub;
    this.rooms = new Set();
    this.alive = true;
    this.parser = new FrameParser({
      onText: (text) => this.hub.handleMessage(this, text),
      onPing: (data) => this._write(encodePong(data)),
      onClose: () => this.close(),
    });
    socket.on('data', (chunk) => {
      try {
        this.parser.push(chunk);
      } catch (err) {
        this.close();
      }
    });
    socket.on('error', () => this.close());
    socket.on('close', () => this.hub.detach(this));
  }

  send(obj) {
    this._write(encodeText(JSON.stringify(obj)));
  }

  _write(buf) {
    if (this.alive && !this.socket.destroyed) {
      try {
        this.socket.write(buf);
      } catch {
        this.close();
      }
    }
  }

  ping() {
    if (!this.alive) return;
    const h = Buffer.alloc(2);
    h[0] = 0x89;
    h[1] = 0;
    this._write(h);
  }

  close() {
    this.alive = false;
    this.hub.detach(this);
    try {
      this.socket.destroy();
    } catch { /* already gone */ }
  }
}

/**
 * Game lobby hub. `handlers` supplies game-specific logic:
 *   verifyToken(token) -> user|null
 *   onJoin(user, gameId) -> state payload (throws {status,message})
 *   onMove(user, gameId, move) -> {state, finished} (throws likewise)
 */
class Hub {
  constructor(handlers) {
    this.handlers = handlers;
    this.rooms = new Map(); // gameId -> Set<Connection>
    this.conns = new Set();
  }

  handleUpgrade(req, socket, head) {
    let url;
    try {
      url = new URL(req.url, 'http://localhost');
    } catch {
      socket.destroy();
      return;
    }
    if (url.pathname !== '/ws') {
      socket.destroy();
      return;
    }
    const user = this.handlers.verifyToken(url.searchParams.get('token'));
    const key = req.headers['sec-websocket-key'];
    if (!user || !key) {
      socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n`);
    const conn = new Connection(socket, user, this);
    this.conns.add(conn);
    if (head && head.length > 0) {
      try {
        conn.parser.push(head);
      } catch {
        conn.close();
      }
    }
  }

  detach(conn) {
    this.conns.delete(conn);
    for (const gameId of conn.rooms) {
      const set = this.rooms.get(gameId);
      if (set) {
        set.delete(conn);
        if (set.size === 0) this.rooms.delete(gameId);
      }
    }
    conn.rooms.clear();
  }

  joinRoom(conn, gameId) {
    let set = this.rooms.get(gameId);
    if (!set) {
      set = new Set();
      this.rooms.set(gameId, set);
    }
    set.add(conn);
    conn.rooms.add(gameId);
  }

  leaveRoom(conn, gameId) {
    const set = this.rooms.get(gameId);
    if (set) {
      set.delete(conn);
      if (set.size === 0) this.rooms.delete(gameId);
    }
    conn.rooms.delete(gameId);
  }

  broadcast(gameId, obj, except = null) {
    const set = this.rooms.get(gameId);
    if (!set) return;
    for (const conn of [...set]) {
      if (conn !== except) conn.send(obj);
    }
  }

  handleMessage(conn, text) {
    let msg;
    try {
      msg = JSON.parse(text);
    } catch {
      conn.send({ t: 'error', message: 'Message must be JSON' });
      return;
    }
    const fail = (message) => conn.send({ t: 'error', message });
    try {
      if (msg.t === 'join' && msg.game != null) {
        const gameId = Number(msg.game);
        const state = this.handlers.onJoin(conn.user, gameId);
        this.joinRoom(conn, gameId);
        conn.send({ t: 'state', game: state });
        this.broadcast(gameId, { t: 'presence', game: gameId,
          players: this.roomUsers(gameId) }, conn);
      } else if (msg.t === 'leave' && msg.game != null) {
        const gameId = Number(msg.game);
        this.leaveRoom(conn, gameId);
        conn.send({ t: 'left', game: gameId });
      } else if (msg.t === 'move' && msg.game != null) {
        const gameId = Number(msg.game);
        const { state, finished } = this.handlers.onMove(conn.user, gameId, msg);
        this.joinRoom(conn, gameId); // moving implies watching
        this.broadcast(gameId, { t: 'state', game: state });
        if (finished) this.broadcast(gameId, { t: 'finished', game: gameId, finished });
      } else {
        fail('Unknown message. Use {t:"join"|"leave"|"move", game, ...}');
      }
    } catch (err) {
      fail(err.message || 'Request failed');
    }
  }

  roomUsers(gameId) {
    const set = this.rooms.get(gameId);
    if (!set) return [];
    const names = new Set();
    for (const conn of set) names.add(conn.user.username);
    return [...names];
  }
}

module.exports = { Hub, encodeText, acceptKey };

