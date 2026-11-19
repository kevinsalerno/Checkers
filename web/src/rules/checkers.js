'use strict';

/**
 * American / English draughts rules — JS port of src/CheckersEngine.java.
 *
 * Men move one diagonal step forward only; kings move one diagonal step in
 * any direction (non-flying jumps over exactly one adjacent enemy piece).
 * Captures are mandatory; multi-jump chains must be completed. Crowning on
 * the far row ends the turn at once. BLACK moves first.
 * Draws: threefold repetition, and 80 ply without a capture or crowning
 * (the 40-move rule). Loss: no pieces, or no legal move.
 * Playable squares are the dark squares ((row + col) odd).
 * Coordinates: row 0 = rank 8, col 0 = file a.
 */

const EMPTY = 0;
const WHITE_MAN = 1;
const WHITE_KING = 2;
const BLACK_MAN = 3;
const BLACK_KING = 4;

const WHITE = 'white';
const BLACK = 'black';

const DRAW_PLY_LIMIT = 80;

const IN_PROGRESS = 'IN_PROGRESS';
const WHITE_WINS = 'WHITE_WINS';
const BLACK_WINS = 'BLACK_WINS';
const DRAW = 'DRAW';

function opponent(side) {
  return side === WHITE ? BLACK : WHITE;
}

function sideOf(piece) {
  if (piece === WHITE_MAN || piece === WHITE_KING) return WHITE;
  if (piece === BLACK_MAN || piece === BLACK_KING) return BLACK;
  return null;
}

function isKing(piece) {
  return piece === WHITE_KING || piece === BLACK_KING;
}

function inBounds(row, col) {
  return row >= 0 && row < 8 && col >= 0 && col < 8;
}

function checkSquare(row, col) {
  if (!inBounds(row, col)) {
    throw new Error(`Square out of bounds: ${row},${col}`);
  }
}

function squareName(row, col) {
  checkSquare(row, col);
  return String.fromCharCode('a'.charCodeAt(0) + col) + (8 - row);
}

function parseSquare(text) {
  if (typeof text !== 'string' || text.length !== 2) {
    throw new Error(`Bad square: ${text}`);
  }
  const file = text[0].toLowerCase();
  const rank = text[1];
  if (file < 'a' || file > 'h' || rank < '1' || rank > '8') {
    throw new Error(`Bad square: ${text}`);
  }
  return [8 - (rank.charCodeAt(0) - '0'.charCodeAt(0)), file.charCodeAt(0) - 'a'.charCodeAt(0)];
}

/** Directions a piece may step/jump in: [dRow, dCol]. */
function directions(piece) {
  if (isKing(piece)) return [[-1, -1], [-1, 1], [1, -1], [1, 1]];
  const fwd = sideOf(piece) === WHITE ? -1 : 1;
  return [[fwd, -1], [fwd, 1]];
}

class CheckersEngine {
  constructor(setup = true) {
    this.board = Array.from({ length: 8 }, () => new Array(8).fill(EMPTY));
    this.side = BLACK;
    this.chainRow = -1;
    this.chainCol = -1;
    this.nonCapturePly = 0;
    this.ply = 0;
    this.counts = Object.create(null);
    this.outcome = IN_PROGRESS;
    this.reason = '';
    if (setup) this._setup();
  }

  _setup() {
    for (let r = 0; r < 8; r++) {
      for (let c = 0; c < 8; c++) {
        if ((r + c) % 2 === 1) {
          if (r < 3) this.board[r][c] = BLACK_MAN;
          else if (r > 4) this.board[r][c] = WHITE_MAN;
        }
      }
    }
    this.side = BLACK;
    this.chainRow = -1;
    this.chainCol = -1;
    this.nonCapturePly = 0;
    this.ply = 0;
    this.outcome = IN_PROGRESS;
    this.reason = '';
    this.counts = Object.create(null);
    this._recordPosition();
    this._updateOutcome();
  }

  copy() {
    const e = new CheckersEngine(false);
    for (let r = 0; r < 8; r++) e.board[r] = this.board[r].slice();
    e.side = this.side;
    e.chainRow = this.chainRow;
    e.chainCol = this.chainCol;
    e.nonCapturePly = this.nonCapturePly;
    e.ply = this.ply;
    e.counts = Object.assign(Object.create(null), this.counts);
    e.outcome = this.outcome;
    e.reason = this.reason;
    return e;
  }

  clearBoard() {
    for (let r = 0; r < 8; r++) this.board[r].fill(EMPTY);
    this.side = BLACK;
    this.chainRow = -1;
    this.chainCol = -1;
    this.nonCapturePly = 0;
    this.ply = 0;
    this.outcome = IN_PROGRESS;
    this.reason = '';
    this.counts = Object.create(null);
    this._recordPosition();
    this._updateOutcome();
  }

  setPiece(row, col, piece) {
    checkSquare(row, col);
    this.board[row][col] = piece;
    this.counts = Object.create(null);
    this._recordPosition();
    this._updateOutcome();
  }

  setSideToMove(side) {
    if (side !== WHITE && side !== BLACK) throw new Error(`Bad side: ${side}`);
    this.side = side;
    this.counts = Object.create(null);
    this._recordPosition();
    this._updateOutcome();
  }

  pieceAt(row, col) {
    checkSquare(row, col);
    return this.board[row][col];
  }

  sideToMove() { return this.side; }
  isChainActive() { return this.chainRow >= 0; }
  chainPos() { return this.isChainActive() ? [this.chainRow, this.chainCol] : null; }
  nonCapturePlyCount() { return this.nonCapturePly; }
  plyCount() { return this.ply; }
  result() { return this.outcome; }
  resultReason() { return this.reason; }
  isGameOver() { return this.outcome !== IN_PROGRESS; }

  countPieces(side) {
    let n = 0;
    for (let r = 0; r < 8; r++) {
      for (let c = 0; c < 8; c++) {
        if (sideOf(this.board[r][c]) === side) n++;
      }
    }
    return n;
  }

  _jumpsFrom(row, col, out) {
    const piece = this.board[row][col];
    for (const [dr, dc] of directions(piece)) {
      const midR = row + dr, midC = col + dc;
      const landR = row + 2 * dr, landC = col + 2 * dc;
      if (!inBounds(landR, landC)) continue;
      const mid = this.board[midR][midC];
      if (mid !== EMPTY && sideOf(mid) === opponent(sideOf(piece))
          && this.board[landR][landC] === EMPTY) {
        out.push({ fromRow: row, fromCol: col, toRow: landR, toCol: landC,
          capRow: midR, capCol: midC });
      }
    }
  }

  _quietsFrom(row, col, out) {
    const piece = this.board[row][col];
    for (const [dr, dc] of directions(piece)) {
      const r = row + dr, c = col + dc;
      if (inBounds(r, c) && this.board[r][c] === EMPTY) {
        out.push({ fromRow: row, fromCol: col, toRow: r, toCol: c,
          capRow: -1, capCol: -1 });
      }
    }
  }

  /** All legal steps from one square, honouring mandatory captures. */
  legalMovesFor(row, col) {
    checkSquare(row, col);
    const result = [];
    if (this.isGameOver() || sideOf(this.board[row][col]) !== this.side) return result;
    if (this.isChainActive() && (row !== this.chainRow || col !== this.chainCol)) return result;
    if (this.isChainActive()) {
      this._jumpsFrom(row, col, result);
      return result;
    }
    const jumps = [];
    for (let r = 0; r < 8; r++) {
      for (let c = 0; c < 8; c++) {
        if (sideOf(this.board[r][c]) === this.side) this._jumpsFrom(r, c, jumps);
      }
    }
    if (jumps.length > 0) {
      this._jumpsFrom(row, col, result);
      return result;
    }
    this._quietsFrom(row, col, result);
    return result;
  }

  /** All legal steps for the side to move (chain-restricted if mid-chain). */
  legalMoves() {
    const result = [];
    if (this.isGameOver()) return result;
    if (this.isChainActive()) {
      this._jumpsFrom(this.chainRow, this.chainCol, result);
      return result;
    }
    const jumps = [];
    for (let r = 0; r < 8; r++) {
      for (let c = 0; c < 8; c++) {
        if (sideOf(this.board[r][c]) === this.side) this._jumpsFrom(r, c, jumps);
      }
    }
    if (jumps.length > 0) return jumps;
    for (let r = 0; r < 8; r++) {
      for (let c = 0; c < 8; c++) {
        if (sideOf(this.board[r][c]) === this.side) this._quietsFrom(r, c, result);
      }
    }
    return result;
  }

  static _sameStep(a, b) {
    return a.fromRow === b.fromRow && a.fromCol === b.fromCol
      && a.toRow === b.toRow && a.toCol === b.toCol;
  }

  isLegalStep(fromRow, fromCol, toRow, toCol) {
    const probe = { fromRow, fromCol, toRow, toCol };
    return this.legalMoves().some((m) => CheckersEngine._sameStep(m, probe));
  }

  /**
   * Applies one legal step given as {fromRow,fromCol,toRow,toCol}.
   * Throws otherwise and leaves the position unchanged.
   */
  applyStep(step) {
    const legal = this.legalMoves().find((m) => CheckersEngine._sameStep(m, step));
    if (!step || !legal) {
      throw new Error(`Illegal checkers move: ${JSON.stringify(step)}`);
    }
    const move = legal;
    let piece = this.board[move.fromRow][move.fromCol];
    const isJump = move.capRow >= 0;
    this.board[move.fromRow][move.fromCol] = EMPTY;
    if (isJump) this.board[move.capRow][move.capCol] = EMPTY;
    let crowned = false;
    if (!isKing(piece)) {
      if (piece === WHITE_MAN && move.toRow === 0) { piece = WHITE_KING; crowned = true; }
      else if (piece === BLACK_MAN && move.toRow === 7) { piece = BLACK_KING; crowned = true; }
    }
    this.board[move.toRow][move.toCol] = piece;
    this.ply++;
    if (isJump || crowned) this.nonCapturePly = 0;
    else this.nonCapturePly++;
    // Chain continuation: same piece, same turn, unless crowned.
    this.chainRow = -1;
    this.chainCol = -1;
    if (isJump && !crowned) {
      const further = [];
      this._jumpsFrom(move.toRow, move.toCol, further);
      if (further.length > 0) {
        this.chainRow = move.toRow;
        this.chainCol = move.toCol;
      }
    }
    if (!this.isChainActive()) this.side = opponent(this.side);
    this._recordPosition();
    this._updateOutcome();
    return move;
  }

  /**
   * Applies a coordinate notation such as "a3-b4", "a3xb4", "a3b4" or a
   * chain "a3xc5xe7". The whole notation is validated before anything is
   * applied; a chain notation must be complete.
   */
  applyNotation(notation) {
    const steps = this.parseNotation(notation);
    for (const step of steps) this.applyStep(step);
    return steps;
  }

  /** Parses notation against a copy, so failures mutate nothing. */
  parseNotation(notation) {
    if (notation == null) throw new Error('Null checkers notation');
    const cleaned = String(notation).trim().replace(/[\s,]+/g, '');
    if (!cleaned) throw new Error('Empty checkers notation');
    const parts = cleaned.split(/[-xX]/);
    if (parts.length < 2) throw new Error(`Bad checkers notation: ${notation}`);
    const squares = parts.map(parseSquare);
    const probe = this.copy();
    const steps = [];
    for (let i = 0; i + 1 < squares.length; i++) {
      const [fr, fc] = squares[i];
      const [tr, tc] = squares[i + 1];
      const found = probe.legalMoves().find(
        (m) => m.fromRow === fr && m.fromCol === fc && m.toRow === tr && m.toCol === tc);
      if (!found) {
        throw new Error(`Illegal checkers step ${squareName(fr, fc)} to ` +
          `${squareName(tr, tc)} in notation: ${notation}`);
      }
      steps.push({ fromRow: fr, fromCol: fc, toRow: tr, toCol: tc,
        capRow: found.capRow, capCol: found.capCol });
      probe.applyStep(found);
    }
    if (probe.isChainActive()) {
      throw new Error(`Incomplete multi-jump in notation: ${notation}`);
    }
    return steps;
  }

  /**
   * Parses one step ("a3-b4" / "a3xb4") against the current position,
   * without requiring a multi-jump chain to be complete. Used for
   * single-step network moves; parseNotation is the complete-turn form.
   */
  parseSingleStep(notation) {
    if (notation == null) throw new Error('Null checkers notation');
    const cleaned = String(notation).trim().replace(/[\s,]+/g, '');
    const parts = cleaned.split(/[-xX]/);
    if (parts.length !== 2) throw new Error(`Bad checkers step: ${notation}`);
    const [fr, fc] = parseSquare(parts[0]);
    const [tr, tc] = parseSquare(parts[1]);
    const found = this.legalMoves().find(
      (m) => m.fromRow === fr && m.fromCol === fc && m.toRow === tr && m.toCol === tc);
    if (!found) throw new Error(`Illegal checkers step in: ${notation}`);
    return { fromRow: fr, fromCol: fc, toRow: tr, toCol: tc,
      capRow: found.capRow, capCol: found.capCol };
  }

  _positionKey() {
    let sb = '';
    for (let r = 0; r < 8; r++) {
      for (let c = 0; c < 8; c++) {
        sb += String.fromCharCode('0'.charCodeAt(0) + this.board[r][c]);
      }
    }
    sb += this.side === WHITE ? 'w' : 'b';
    sb += `${this.chainRow},${this.chainCol}`;
    return sb;
  }

  _recordPosition() {
    const key = this._positionKey();
    this.counts[key] = (this.counts[key] || 0) + 1;
  }

  _updateOutcome() {
    this.outcome = IN_PROGRESS;
    this.reason = '';
    if (this.countPieces(WHITE) === 0) {
      this.outcome = BLACK_WINS;
      this.reason = 'White has no pieces left';
      return;
    }
    if (this.countPieces(BLACK) === 0) {
      this.outcome = WHITE_WINS;
      this.reason = 'Black has no pieces left';
      return;
    }
    const reps = this.counts[this._positionKey()] || 0;
    if (reps >= 3) {
      this.outcome = DRAW;
      this.reason = 'Threefold repetition';
      return;
    }
    if (this.nonCapturePly >= DRAW_PLY_LIMIT) {
      this.outcome = DRAW;
      this.reason = `40-move rule: ${DRAW_PLY_LIMIT} ply without a capture or crowning`;
      return;
    }
    if (!this.isChainActive() && this.legalMoves().length === 0) {
      this.outcome = this.side === WHITE ? BLACK_WINS : WHITE_WINS;
      this.reason = `${this.side === WHITE ? 'White' : 'Black'} has no legal move`;
    }
  }

  boardDiagram() {
    let sb = '';
    for (let r = 0; r < 8; r++) {
      sb += `${8 - r} `;
      for (let c = 0; c < 8; c++) {
        let ch;
        switch (this.board[r][c]) {
          case WHITE_MAN: ch = 'w'; break;
          case WHITE_KING: ch = 'W'; break;
          case BLACK_MAN: ch = 'b'; break;
          case BLACK_KING: ch = 'B'; break;
          default: ch = ((r + c) % 2 === 1) ? '.' : ' ';
        }
        sb += `${ch} `;
      }
      sb += '\n';
    }
    sb += '  a b c d e f g h\n';
    sb += this.side === WHITE ? 'White' : 'Black';
    sb += ' to move';
    if (this.isChainActive()) {
      sb += `, chain at ${squareName(this.chainRow, this.chainCol)}`;
    }
    return sb;
  }

  serialize() {
    return { game: 'checkers', board: this.board.map((row) => row.slice()),
      side: this.side, chainRow: this.chainRow, chainCol: this.chainCol,
      nonCapturePly: this.nonCapturePly, plyCount: this.ply,
      counts: Object.assign({}, this.counts), outcome: this.outcome, reason: this.reason };
  }

  static deserialize(data) {
    const e = new CheckersEngine(false);
    e.board = data.board.map((row) => row.slice());
    e.side = data.side;
    e.chainRow = data.chainRow;
    e.chainCol = data.chainCol;
    e.nonCapturePly = data.nonCapturePly;
    e.ply = data.plyCount;
    e.counts = Object.assign(Object.create(null), data.counts);
    e.outcome = data.outcome;
    e.reason = data.reason;
    return e;
  }

  /** Step to compact notation ("c3xe5" for jumps, "b6-a5" for quiets). */
  static notationFor(step) {
    const sep = step.capRow >= 0 ? 'x' : '-';
    return squareName(step.fromRow, step.fromCol) + sep + squareName(step.toRow, step.toCol);
  }
}

module.exports = {
  CheckersEngine,
  EMPTY, WHITE_MAN, WHITE_KING, BLACK_MAN, BLACK_KING,
  WHITE, BLACK, DRAW_PLY_LIMIT,
  IN_PROGRESS, WHITE_WINS, BLACK_WINS, DRAW,
  opponent, sideOf, isKing, inBounds, squareName, parseSquare,
};