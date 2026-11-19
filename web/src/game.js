'use strict';

/**
 * Game orchestration shared by the REST API and the WebSocket layer:
 * hydrate engines from stored rows, apply validated moves, build the
 * public state view, and finalize finished games (match history + ELO).
 */

const checkers = require('./rules/checkers');
const chess = require('./rules/chess');
const db = require('./db');
const elo = require('./elo');

const GAME_TYPES = ['checkers', 'chess'];

/** Seat of the creator: the side that moves first in each game. */
function creatorSeat(gameType) {
  return gameType === 'chess' ? 'white' : 'black';
}

function engineFor(gameType) {
  return gameType === 'chess' ? chess : checkers;
}

function newEngineState(gameType) {
  const E = engineFor(gameType).CheckersEngine || engineFor(gameType).ChessEngine;
  return new E().serialize();
}

function hydrate(row) {
  const E = engineFor(row.game_type);
  return row.game_type === 'chess'
    ? chess.ChessEngine.deserialize(JSON.parse(row.state))
    : checkers.CheckersEngine.deserialize(JSON.parse(row.state));
}

function moveList(row) {
  try {
    const m = JSON.parse(row.moves || '[]');
    return Array.isArray(m) ? m : [];
  } catch {
    return [];
  }
}

/** Which engine side ('white'/'black' or 'w'/'b') is to move, normalized. */
function sideToMoveOf(gameType, engine) {
  if (gameType === 'chess') return engine.sideToMove() === 'w' ? 'white' : 'black';
  return engine.sideToMove();
}

/** User id holding a seat, or null. Creator holds the creator seat. */
function seatHolder(row, seat) {
  const creator = creatorSeat(row.game_type);
  if (seat === creator) return row.owner_id;
  return row.opponent_id;
}

/**
 * Can this user move `seat` now? Solo games (no opponent yet): the owner
 * may move either side (pass-and-play practice). Once an opponent joins,
 * each side is locked to its player.
 */
function canMoveSeat(row, userId, seat) {
  if (row.opponent_id == null) return row.owner_id === userId;
  return seatHolder(row, seat) === userId;
}

/**
 * Apply a {from, to, promotion} move. Returns {engine, notation}.
 * Throws with status 400 on illegal input, 409 on wrong turn / finished.
 */
function applyPlayerMove(row, userId, { from, to, promotion }) {
  const gameType = row.game_type;
  const engine = hydrate(row);
  if (engine.isGameOver()) {
    const err = new Error('Game is already finished');
    err.status = 409;
    throw err;
  }
  const side = sideToMoveOf(gameType, engine);
  if (!canMoveSeat(row, userId, side)) {
    const err = new Error(`Not your move: ${side} is to play`);
    err.status = 409;
    throw err;
  }
  let notation;
  try {
    if (gameType === 'chess') {
      const promo = promotion ? String(promotion).toLowerCase() : '';
      const text = `${from}${to}${promo}`;
      const move = engine.parseCoordinate(text);
      engine.applyMove(move);
      notation = chess.moveToString(move);
    } else {
      if (promotion) {
        const err = new Error('Checkers moves take no promotion piece');
        err.status = 400;
        throw err;
      }
      const step = engine.parseSingleStep(`${from}-${to}`);
      engine.applyStep(step);
      notation = checkers.CheckersEngine.notationFor(step);
    }
  } catch (err) {
    if (err.status == null) {
      const wrap = new Error(`Illegal move: ${err.message}`);
      wrap.status = 400;
      throw wrap;
    }
    throw err;
  }
  return { engine, notation };
}

/**
 * Client-safe snapshot: full board, turn, outcome and players, but no
 * password hashes or internal repetition tables beyond the state blob.
 */
function publicState(dbHandle, row, engine = null) {
  const eng = engine || hydrate(row);
  const gameType = row.game_type;
  const moves = moveList(row);
  const owner = db.findUserById(dbHandle, row.owner_id);
  const opp = row.opponent_id == null
    ? null : db.findUserById(dbHandle, row.opponent_id);
  const creator = creatorSeat(gameType);
  const names = creator === 'white'
    ? { white: owner && owner.username, black: opp && opp.username }
    : { white: opp && opp.username, black: owner && owner.username };
  const view = {
    id: row.id,
    game_type: gameType,
    status: row.status,
    board: eng.board.map((r) => r.slice()),
    // Full engine snapshot (position, turn, clocks, repetition counts,
    // checkers chain). No secrets inside — the client sends this blob
    // back verbatim for Save & Quit, and resumes from it on any device.
    state: eng.serialize(),
    side_to_move: sideToMoveOf(gameType, eng),
    outcome: eng.result(),
    reason: eng.resultReason(),
    moves,
    moves_count: moves.length,
    white: names.white || null,
    black: names.black || null,
    has_opponent: row.opponent_id != null,
    created_at: row.created_at,
    updated_at: row.updated_at,
  };
  if (gameType === 'chess') {
    view.fen = eng.toFen();
    view.in_check = eng.inCheck();
    view.halfmove = eng.halfmoveClock();
  } else {
    view.chain = eng.chainPos();
  }
  return view;
}

/** Legal target squares from one square, as ["e2","e4"] pairs + full moves. */
function legalTargets(row, fromSquare) {
  const gameType = row.game_type;
  const engine = hydrate(row);
  const parse = gameType === 'chess' ? chess.parseSquare : checkers.parseSquare;
  const name = gameType === 'chess' ? chess.squareName : checkers.squareName;
  const [fr, fc] = parse(fromSquare);
  return engine.legalMovesFor(fr, fc).map((m) => ({
    from: name(m.fromRow, m.fromCol),
    to: name(m.toRow, m.toCol),
    promotion: m.promotion || undefined,
  }));
}

/**
 * Persist an engine + appended notation, then finalize when terminal:
 * write match history, update ELOs (two distinct humans only) and
 * remove the finished game from the active table.
 * Returns {row|null, finished} — row is null once archived.
 */
function persistAndMaybeFinish(dbHandle, row, engine, notation) {
  const moves = moveList(row);
  if (notation) moves.push(notation);
  db.saveGameState(dbHandle, row.id, JSON.stringify(engine.serialize()),
    JSON.stringify(moves));
  const fresh = db.getGame(dbHandle, row.id);
  if (!engine.isGameOver()) return { row: fresh, finished: null };

  const gameType = row.game_type;
  const outcome = engine.result();
  const creator = creatorSeat(gameType);
  const whiteId = creator === 'white' ? row.owner_id : row.opponent_id;
  const blackId = creator === 'black' ? row.owner_id : row.opponent_id;
  let winnerId = null;
  if (outcome === 'WHITE_WINS') winnerId = whiteId;
  else if (outcome === 'BLACK_WINS') winnerId = blackId;

  const distinct = whiteId != null && blackId != null && whiteId !== blackId;
  if (distinct) {
    const w = db.findUserById(dbHandle, whiteId);
    const b = db.findUserById(dbHandle, blackId);
    const scoreW = winnerId == null ? 0.5 : winnerId === whiteId ? 1 : 0;
    const [newW, newB] = elo.updateRatings(w.elo, b.elo, scoreW);
    db.applyElo(dbHandle, whiteId, newW, scoreW === 1 ? 'win' : scoreW === 0 ? 'loss' : 'draw');
    db.applyElo(dbHandle, blackId, newB, scoreW === 0 ? 'win' : scoreW === 1 ? 'loss' : 'draw');
  }
  const matchId = db.recordMatch(dbHandle, {
    game_type: gameType,
    white_id: whiteId,
    black_id: blackId,
    winner_id: winnerId,
    result: outcome,
    reason: engine.resultReason(),
    moves_count: moves.length,
    moves: JSON.stringify(moves),
  });
  db.deleteGame(dbHandle, row.id);
  return {
    row: null,
    finished: {
      match_id: matchId,
      outcome,
      reason: engine.resultReason(),
      winner: winnerId == null ? null
        : (db.findUserById(dbHandle, winnerId) || {}).username || null,
      elo_updated: distinct,
    },
  };
}

module.exports = {
  GAME_TYPES,
  creatorSeat,
  newEngineState,
  hydrate,
  moveList,
  sideToMoveOf,
  seatHolder,
  canMoveSeat,
  applyPlayerMove,
  publicState,
  legalTargets,
  persistAndMaybeFinish,
};
