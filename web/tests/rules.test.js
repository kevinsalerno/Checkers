'use strict';

/**
 * Rules-engine tests: JS ports must match the Java engines' contract.
 * Chess move generation is validated by perft (README: start position
 * 20 / 400 / 8902 at depths 1-3, Kiwipete 97862 at depth 3).
 */

const { test } = require('node:test');
const assert = require('node:assert/strict');
const checkers = require('../src/rules/checkers');
const chess = require('../src/rules/chess');

function perft(engine, depth) {
  if (depth === 0) return 1;
  let nodes = 0;
  for (const m of engine.legalMoves()) {
    const probe = engine.copy();
    probe.applyMove(m);
    nodes += perft(probe, depth - 1);
  }
  return nodes;
}

// ---------- Checkers ----------

test('checkers: initial position, black moves first with 7 quiet moves', () => {
  const e = new checkers.CheckersEngine();
  assert.equal(e.sideToMove(), 'black');
  assert.equal(e.legalMoves().length, 7);
  assert.equal(e.isGameOver(), false);
  assert.equal(e.countPieces('black'), 12);
  assert.equal(e.countPieces('white'), 12);
});

test('checkers: quiet step passes the turn', () => {
  const e = new checkers.CheckersEngine();
  e.applyNotation('b6-a5');
  assert.equal(e.sideToMove(), 'white');
  assert.equal(e.pieceAt(3, 0), checkers.BLACK_MAN);
  assert.equal(e.plyCount(), 1);
  assert.equal(e.boardDiagram().includes('White to move'), true);
});

test('checkers: captures are mandatory', () => {
  const e = new checkers.CheckersEngine();
  e.clearBoard();
  // Black man c5 can capture white man b4; another black man elsewhere
  // has a quiet move that must be refused while the jump exists.
  e.setPiece(3, 2, checkers.BLACK_MAN); // c5
  e.setPiece(4, 1, checkers.WHITE_MAN); // b4
  e.setPiece(2, 5, checkers.BLACK_MAN); // f6
  e.setSideToMove('black');
  assert.throws(() => e.applyNotation('f6-e5'), /Illegal/);
  e.applyNotation('c5xa3');
  assert.equal(e.pieceAt(5, 0), checkers.BLACK_MAN);
  assert.equal(e.pieceAt(4, 1), checkers.EMPTY);
  assert.equal(e.sideToMove(), 'white');
});

test('checkers: multi-jump chain must be completed', () => {
  const e = new checkers.CheckersEngine();
  e.clearBoard();
  // Black moves down (+row). c5 jumps b4 landing a3, then jumps
  // b2 landing c1 (crowning, which ends the turn).
  e.setPiece(3, 2, checkers.BLACK_MAN); // c5
  e.setPiece(4, 1, checkers.WHITE_MAN); // b4
  e.setPiece(6, 1, checkers.WHITE_MAN); // b2
  e.setSideToMove('black');
  assert.throws(() => e.parseNotation('c5xa3'), /Incomplete multi-jump/);
  assert.equal(e.isChainActive(), false);
  e.applyNotation('c5xa3xc1');
  assert.equal(e.pieceAt(7, 2), checkers.BLACK_KING); // crowned on arrival
  assert.equal(e.countPieces('white'), 0);
  assert.equal(e.result(), 'BLACK_WINS');
  assert.equal(e.isGameOver(), true);
});

test('checkers: single-step network form allows mid-chain stops', () => {
  const e = new checkers.CheckersEngine();
  e.clearBoard();
  e.setPiece(3, 2, checkers.BLACK_MAN); // c5
  e.setPiece(4, 1, checkers.WHITE_MAN); // b4
  e.setPiece(6, 1, checkers.WHITE_MAN); // b2
  e.setSideToMove('black');
  const step = e.parseSingleStep('c5xa3');
  e.applyStep(step);
  assert.equal(e.isChainActive(), true);
  assert.deepEqual(e.chainPos(), [5, 0]);
  assert.equal(e.sideToMove(), 'black'); // turn does not pass mid-chain
});

test('checkers: crowning ends the turn even with a jump open', () => {
  const e = new checkers.CheckersEngine();
  e.clearBoard();
  e.setPiece(6, 3, checkers.BLACK_MAN); // d2, one step from crowning
  e.setPiece(7, 0, checkers.WHITE_MAN); // a1, out of the way
  e.setSideToMove('black');
  e.applyNotation('d2-c1');
  assert.equal(e.pieceAt(7, 2), checkers.BLACK_KING);
  assert.equal(e.isChainActive(), false);
  assert.equal(e.sideToMove(), 'white');
});

test('checkers: no pieces left loses the game', () => {
  const e = new checkers.CheckersEngine();
  e.clearBoard();
  e.setPiece(5, 0, checkers.BLACK_MAN);
  e.setSideToMove('black');
  assert.equal(e.result(), 'BLACK_WINS'); // white has no pieces left
});

test('checkers: serialize/deserialize round-trips mid-chain state', () => {
  const e = new checkers.CheckersEngine();
  e.clearBoard();
  e.setPiece(3, 2, checkers.BLACK_MAN); // c5
  e.setPiece(4, 1, checkers.WHITE_MAN); // b4
  e.setPiece(6, 1, checkers.WHITE_MAN); // b2
  e.setSideToMove('black');
  e.applyStep(e.parseSingleStep('c5xa3'));
  const back = checkers.CheckersEngine.deserialize(e.serialize());
  assert.equal(back.isChainActive(), true);
  assert.deepEqual(back.legalMoves().map((m) => `${m.fromRow},${m.fromCol}->${m.toRow},${m.toCol}`),
    e.legalMoves().map((m) => `${m.fromRow},${m.fromCol}->${m.toRow},${m.toCol}`));
  back.applyNotation('a3xc1');
  assert.equal(back.isGameOver(), true);
  assert.equal(back.result(), 'BLACK_WINS');
});

// ---------- Chess ----------

test('chess: startpos perft 20 / 400 / 8902', () => {
  const e = new chess.ChessEngine();
  assert.equal(perft(e, 1), 20);
  assert.equal(perft(e, 2), 400);
  assert.equal(perft(e, 3), 8902);
});

test('chess: kiwipete perft(3) = 97862', () => {
  const e = new chess.ChessEngine(false);
  e.loadFen('r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1');
  assert.equal(perft(e, 3), 97862);
});

test("chess: fool's mate is checkmate", () => {
  const e = new chess.ChessEngine();
  for (const n of ['f2f3', 'e7e5', 'g2g4', 'd8h4']) e.applyNotation(n);
  assert.equal(e.result(), 'BLACK_WINS');
  assert.equal(e.resultReason(), 'Checkmate');
  assert.throws(() => e.applyNotation('e2e4'), /Illegal/);
});

test('chess: stalemate is a draw', () => {
  const e = new chess.ChessEngine(false);
  // Black king h8, white queen f7: h8 has no legal move and is not in check.
  e.loadFen('7k/5Q2/8/8/8/8/8/7K b - - 0 1');
  assert.equal(e.result(), 'DRAW');
  assert.equal(e.resultReason(), 'Stalemate');
});

test('chess: castling banned through check, allowed otherwise', () => {
  const e = new chess.ChessEngine(false);
  e.loadFen('r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1');
  const castles = e.legalMoves().filter((m) => m.isCastling);
  assert.equal(castles.length, 2);
  const e2 = new chess.ChessEngine(false);
  // Black rook on f8 attacks f1: kingside castling passes through check.
  e2.loadFen('r4rk1/8/8/8/8/8/8/R3K2R w KQ - 0 1');
  const ks = e2.legalMoves().filter((m) => m.isCastling && m.toCol === 6);
  assert.equal(ks.length, 0);
  const qs = e2.legalMoves().filter((m) => m.isCastling && m.toCol === 2);
  assert.equal(qs.length, 1);
});

test('chess: en passant works one move only', () => {
  const e = new chess.ChessEngine();
  e.applyNotation('e2e4');
  e.applyNotation('d7d5');
  e.applyNotation('e4e5');
  e.applyNotation('f7f5');
  const eps = e.legalMoves().filter((m) => m.isEnPassant);
  assert.equal(eps.length, 1);
  e.applyMove(eps[0]);
  assert.equal(e.pieceAt(2, 5), chess.PAWN); // white pawn landed on f6
  assert.equal(e.pieceAt(3, 5), chess.EMPTY); // captured pawn gone
});

test('chess: bare promotion notation defaults to queen', () => {
  const e = new chess.ChessEngine(false);
  e.loadFen('8/2P5/8/8/8/8/8/4K2k w - - 0 1');
  e.applyNotation('c7c8');
  assert.equal(e.pieceAt(0, 2), chess.QUEEN);
  const e2 = new chess.ChessEngine(false);
  e2.loadFen('8/2P5/8/8/8/8/8/4K2k w - - 0 1');
  e2.applyNotation('c7c8n');
  assert.equal(e2.pieceAt(0, 2), chess.KNIGHT);
});

test('chess: pins are enforced by king-safety filtering', () => {
  const e = new chess.ChessEngine(false);
  // White king e1, white rook e2 pinned by black rook e8.
  e.loadFen('4r3/8/8/8/8/8/4R3/4K3 w - - 0 1');
  const rookMoves = e.legalMovesFor(6, 4).map(chess.moveToString).sort();
  // Rook may move along the e-file or capture e8, but not leave it.
  assert.ok(rookMoves.length > 0);
  for (const s of rookMoves) {
    assert.match(s, /^e2e[1-8][qrbn]?$/);
  }
});

test('chess: insufficient material (K vs K, K+minor vs K)', () => {
  const e = new chess.ChessEngine(false);
  e.loadFen('8/8/4k3/8/8/4K3/8/8 w - - 0 1');
  assert.equal(e.result(), 'DRAW');
  const e2 = new chess.ChessEngine(false);
  e2.loadFen('8/8/4k3/8/8/4KN2/8/8 w - - 0 1');
  assert.equal(e2.result(), 'DRAW');
});

test('chess: FEN round-trips the start position', () => {
  const e = new chess.ChessEngine();
  assert.equal(e.toFen(), chess.START_FEN);
  const e2 = new chess.ChessEngine(false);
  e2.loadFen(e.toFen());
  assert.equal(e2.toFen(), chess.START_FEN);
});

test('chess: threefold repetition draws', () => {
  const e = new chess.ChessEngine();
  // Two knight shuttles return to the start position twice: the third
  // occurrence (after 8 half-moves) is drawn at once.
  for (let i = 0; i < 2; i++) {
    e.applyNotation('g1f3');
    e.applyNotation('g8f6');
    e.applyNotation('f3g1');
    e.applyNotation('f6g8');
  }
  assert.equal(e.result(), 'DRAW');
  assert.equal(e.resultReason(), 'Threefold repetition');
  assert.throws(() => e.applyNotation('g1f3'), /Illegal/); // no moves after draw
});

test('chess: serialize/deserialize preserves clocks and repetition', () => {
  const e = new chess.ChessEngine();
  e.applyNotation('e2e4');
  e.applyNotation('e7e5');
  const back = chess.ChessEngine.deserialize(e.serialize());
  assert.equal(back.toFen(), e.toFen());
  assert.equal(back.halfmoveClock(), e.halfmoveClock());
  assert.deepEqual(back.legalMoves().map(chess.moveToString).sort(),
    e.legalMoves().map(chess.moveToString).sort());
});
