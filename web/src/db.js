'use strict';

/**
 * SQLite persistence (node:sqlite, built into Node 22) — no native addons.
 *
 * Tables:
 *   users        credentials + ELO rating + record
 *   active_games serialized in-progress games (one row per game)
 *   matches      completed games: winner/loser, total moves, reason
 */

const { DatabaseSync } = require('node:sqlite');

const SCHEMA = `
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  pass_hash TEXT NOT NULL,
  elo INTEGER NOT NULL DEFAULT 1200,
  wins INTEGER NOT NULL DEFAULT 0,
  losses INTEGER NOT NULL DEFAULT 0,
  draws INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS active_games (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_id INTEGER NOT NULL REFERENCES users(id),
  opponent_id INTEGER REFERENCES users(id),
  game_type TEXT NOT NULL CHECK (game_type IN ('checkers', 'chess')),
  state TEXT NOT NULL,
  moves TEXT NOT NULL DEFAULT '[]',
  status TEXT NOT NULL DEFAULT 'active',
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS matches (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  game_type TEXT NOT NULL,
  white_id INTEGER REFERENCES users(id),
  black_id INTEGER REFERENCES users(id),
  winner_id INTEGER REFERENCES users(id),
  result TEXT NOT NULL,
  reason TEXT NOT NULL DEFAULT '',
  moves_count INTEGER NOT NULL DEFAULT 0,
  moves TEXT NOT NULL DEFAULT '[]',
  played_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_games_owner ON active_games(owner_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_games_opponent ON active_games(opponent_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_matches_w ON matches(white_id, played_at);
CREATE INDEX IF NOT EXISTS idx_matches_b ON matches(black_id, played_at);
`;

function openDb(path) {
  const db = new DatabaseSync(path);
  db.exec(SCHEMA);
  return db;
}

function publicUser(row) {
  if (!row) return null;
  return {
    id: row.id,
    username: row.username,
    elo: row.elo,
    wins: row.wins,
    losses: row.losses,
    draws: row.draws,
    games: row.wins + row.losses + row.draws,
    created_at: row.created_at,
  };
}

// --- users ---

function findUserByName(db, username) {
  return db.prepare('SELECT * FROM users WHERE username = ?').get(username) || null;
}

function findUserById(db, id) {
  return db.prepare('SELECT * FROM users WHERE id = ?').get(id) || null;
}

function createUser(db, username, passHash) {
  const r = db.prepare(
    'INSERT INTO users (username, pass_hash) VALUES (?, ?)').run(username, passHash);
  return findUserById(db, Number(r.lastInsertRowid));
}

function applyElo(db, userId, elo, outcome) {
  const col = outcome === 'win' ? 'wins' : outcome === 'loss' ? 'losses' : 'draws';
  db.prepare(`UPDATE users SET elo = ?, ${col} = ${col} + 1 WHERE id = ?`)
    .run(elo, userId);
  return findUserById(db, userId);
}

// --- active games ---

function createGame(db, ownerId, gameType, stateJson) {
  const r = db.prepare(
    `INSERT INTO active_games (owner_id, game_type, state)
     VALUES (?, ?, ?)`).run(ownerId, gameType, stateJson);
  return getGame(db, Number(r.lastInsertRowid));
}

function getGame(db, id) {
  return db.prepare('SELECT * FROM active_games WHERE id = ?').get(id) || null;
}

function listGamesForUser(db, userId) {
  return db.prepare(
    `SELECT * FROM active_games
     WHERE owner_id = ? OR opponent_id = ?
     ORDER BY updated_at DESC, id DESC`).all(userId, userId);
}

function saveGameState(db, id, stateJson, movesJson) {
  db.prepare(
    `UPDATE active_games SET state = ?, moves = ?, updated_at = datetime('now')
     WHERE id = ?`).run(stateJson, movesJson, id);
  return getGame(db, id);
}

function setOpponent(db, id, opponentId) {
  db.prepare(
    `UPDATE active_games SET opponent_id = ?, updated_at = datetime('now')
     WHERE id = ?`).run(opponentId, id);
  return getGame(db, id);
}

function deleteGame(db, id) {
  const r = db.prepare('DELETE FROM active_games WHERE id = ?').run(id);
  return r.changes > 0;
}

// --- match history ---

function recordMatch(db, entry) {
  const r = db.prepare(
    `INSERT INTO matches
       (game_type, white_id, black_id, winner_id, result, reason, moves_count, moves)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`).run(
    entry.game_type, entry.white_id, entry.black_id, entry.winner_id,
    entry.result, entry.reason || '', entry.moves_count, entry.moves);
  return Number(r.lastInsertRowid);
}

function listHistoryForUser(db, userId, limit = 50) {
  return db.prepare(
    `SELECT * FROM matches
     WHERE white_id = ? OR black_id = ?
     ORDER BY played_at DESC, id DESC LIMIT ?`).all(userId, userId, limit);
}

module.exports = {
  openDb,
  publicUser,
  findUserByName,
  findUserById,
  createUser,
  applyElo,
  createGame,
  getGame,
  listGamesForUser,
  saveGameState,
  setOpponent,
  deleteGame,
  recordMatch,
  listHistoryForUser,
};
