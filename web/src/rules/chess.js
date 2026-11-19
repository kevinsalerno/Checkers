'use strict';

/**
 * Full chess move generation — JS port of src/ChessEngine.java.
 *
 * King-safety filtering (pins and discovered checks fall out of the same
 * test), castling (through-check and in-check bans, rights lost by
 * king/rook moves and rook captures), en passant (one move only),
 * promotion to any piece, check, checkmate, stalemate, the fifty-move
 * rule, threefold repetition and insufficient material.
 * Row 0 = rank 8, col 0 = file a. Positive pieces = White.
 */

const EMPTY = 0;
const PAWN = 1;
const KNIGHT = 2;
const BISHOP = 3;
const ROOK = 4;
const QUEEN = 5;
const KING = 6;

const WHITE = 'w';
const BLACK = 'b';

const IN_PROGRESS = 'IN_PROGRESS';
const WHITE_WINS = 'WHITE_WINS';
const BLACK_WINS = 'BLACK_WINS';
const DRAW = 'DRAW';

const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

function opponent(side) {
  return side === WHITE ? BLACK : WHITE;
}

function pieceOf(type, side) {
  return side === WHITE ? type : -type;
}

function typeOf(piece) {
  return Math.abs(piece);
}

function sideOfPiece(piece) {
  if (piece > 0) return WHITE;
  if (piece < 0) return BLACK;
  return null;
}

function letter(type) {
  switch (type) {
    case PAWN: return 'P';
    case KNIGHT: return 'N';
    case BISHOP: return 'B';
    case ROOK: return 'R';
    case QUEEN: return 'Q';
    case KING: return 'K';
    default: return '?';
  }
}

function typeFromLetter(c) {
  switch (String(c).toUpperCase()) {
    case 'P': return PAWN;
    case 'N': return KNIGHT;
    case 'B': return BISHOP;
    case 'R': return ROOK;
    case 'Q': return QUEEN;
    case 'K': return KING;
    default: throw new Error(`Bad piece letter: ${c}`);
  }
}

function fenLetter(piece) {
  const c = letter(typeOf(piece));
  return piece > 0 ? c : c.toLowerCase();
}

function inBounds(row, col) {
  return row >= 0 && row < 8 && col >= 0 && col < 8;
}

function checkSquare(row, col) {
  if (!inBounds(row, col)) throw new Error(`Square out of bounds: ${row},${col}`);
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

function moveToString(m) {
  let s = squareName(m.fromRow, m.fromCol) + squareName(m.toRow, m.toCol);
  if (m.promotion !== EMPTY) s += letter(m.promotion).toLowerCase();
  return s;
}

function sameMove(a, b) {
  return a.fromRow === b.fromRow && a.fromCol === b.fromCol
    && a.toRow === b.toRow && a.toCol === b.toCol
    && a.promotion === b.promotion;
}

function stepMove(fr, fc, tr, tc, promotion = EMPTY, isEnPassant = false, isCastling = false) {
  return { fromRow: fr, fromCol: fc, toRow: tr, toCol: tc,
    promotion, isEnPassant, isCastling };
}

class ChessEngine {
  constructor(setup = true) {
    this.board = Array.from({ length: 8 }, () => new Array(8).fill(EMPTY));
    this.side = WHITE;
    this.wk = true; this.wq = true; this.bk = true; this.bq = true;
    this.epRow = -1; this.epCol = -1;
    this.halfmove = 0;
    this.fullmove = 1;
    this.counts = Object.create(null);
    this.outcome = IN_PROGRESS;
    this.reason = '';
    if (setup) this.loadFen(START_FEN);
  }

  copy() {
    const e = new ChessEngine(false);
    for (let r = 0; r < 8; r++) e.board[r] = this.board[r].slice();
    e.side = this.side;
    e.wk = this.wk; e.wq = this.wq; e.bk = this.bk; e.bq = this.bq;
    e.epRow = this.epRow; e.epCol = this.epCol;
    e.halfmove = this.halfmove;
    e.fullmove = this.fullmove;
    e.counts = Object.assign(Object.create(null), this.counts);
    e.outcome = this.outcome;
    e.reason = this.reason;
    return e;
  }

  pieceAt(row, col) {
    checkSquare(row, col);
    return this.board[row][col];
  }

  sideToMove() { return this.side; }
  halfmoveClock() { return this.halfmove; }
  fullmoveNumber() { return this.fullmove; }
  result() { return this.outcome; }
  resultReason() { return this.reason; }
  isGameOver() { return this.outcome !== IN_PROGRESS; }
  canCastleKingside(side) { return side === WHITE ? this.wk : this.bk; }
  canCastleQueenside(side) { return side === WHITE ? this.wq : this.bq; }

  clearBoard() {
    for (let r = 0; r < 8; r++) this.board[r].fill(EMPTY);
    this.side = WHITE;
    this.wk = this.wq = this.bk = this.bq = false;
    this.epRow = -1; this.epCol = -1;
    this.halfmove = 0;
    this.fullmove = 1;
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

  setCastlingRights(wk, wq, bk, bq) {
    this.wk = !!wk; this.wq = !!wq; this.bk = !!bk; this.bq = !!bq;
    this.counts = Object.create(null);
    this._recordPosition();
    this._updateOutcome();
  }

  setEnPassant(row, col) {
    if (row < 0 || col < 0) {
      this.epRow = -1; this.epCol = -1;
    } else {
      checkSquare(row, col);
      this.epRow = row; this.epCol = col;
    }
    this.counts = Object.create(null);
    this._recordPosition();
    this._updateOutcome();
  }

  inCheck(side = this.side) {
    for (let r = 0; r < 8; r++) {
      for (let c = 0; c < 8; c++) {
        if (this.board[r][c] === pieceOf(KING, side)) {
          return this.isSquareAttacked(r, c, opponent(side));
        }
      }
    }
    return false;
  }

  isSquareAttacked(row, col, by) {
    const pawnDir = by === WHITE ? -1 : 1;
    const pawnRow = row - pawnDir;
    for (const dc of [-1, 1]) {
      const c = col + dc;
      if (inBounds(pawnRow, c) && this.board[pawnRow][c] === pieceOf(PAWN, by)) return true;
    }
    for (const [dr, dc] of [[-2, -1], [-2, 1], [-1, -2], [-1, 2],
        [1, -2], [1, 2], [2, -1], [2, 1]]) {
      const r = row + dr, c = col + dc;
      if (inBounds(r, c) && this.board[r][c] === pieceOf(KNIGHT, by)) return true;
    }
    for (let dr = -1; dr <= 1; dr++) {
      for (let dc = -1; dc <= 1; dc++) {
        if (dr === 0 && dc === 0) continue;
        const r = row + dr, c = col + dc;
        if (inBounds(r, c) && this.board[r][c] === pieceOf(KING, by)) return true;
      }
    }
    for (const d of [[-1, 0], [1, 0], [0, -1], [0, 1]]) {
      let r = row + d[0], c = col + d[1];
      while (inBounds(r, c)) {
        const p = this.board[r][c];
        if (p !== EMPTY) {
          const t = typeOf(p);
          if (sideOfPiece(p) === by && (t === ROOK || t === QUEEN)) return true;
          break;
        }
        r += d[0]; c += d[1];
      }
    }
    for (const d of [[-1, -1], [-1, 1], [1, -1], [1, 1]]) {
      let r = row + d[0], c = col + d[1];
      while (inBounds(r, c)) {
        const p = this.board[r][c];
        if (p !== EMPTY) {
          const t = typeOf(p);
          if (sideOfPiece(p) === by && (t === BISHOP || t === QUEEN)) return true;
          break;
        }
        r += d[0]; c += d[1];
      }
    }
    return false;
  }

  _addSliderMoves(row, col, dirs, out) {
    const piece = this.board[row][col];
    const side = sideOfPiece(piece);
    for (const [dr, dc] of dirs) {
      let r = row + dr, c = col + dc;
      while (inBounds(r, c)) {
        const target = this.board[r][c];
        if (target === EMPTY) {
          out.push(stepMove(row, col, r, c));
        } else {
          if (sideOfPiece(target) !== side) out.push(stepMove(row, col, r, c));
          break;
        }
        r += dr; c += dc;
      }
    }
  }

  _addStepMoves(row, col, offsets, out) {
    const piece = this.board[row][col];
    const side = sideOfPiece(piece);
    for (const [dr, dc] of offsets) {
      const r = row + dr, c = col + dc;
      if (!inBounds(r, c)) continue;
      const target = this.board[r][c];
      if (target === EMPTY || sideOfPiece(target) !== side) {
        out.push(stepMove(row, col, r, c));
      }
    }
  }

  _addPawnMoves(row, col, out) {
    const piece = this.board[row][col];
    const side = sideOfPiece(piece);
    const dir = side === WHITE ? -1 : 1;
    const startRow = side === WHITE ? 6 : 1;
    const promoRow = side === WHITE ? 0 : 7;
    const one = row + dir;
    if (inBounds(one, col) && this.board[one][col] === EMPTY) {
      this._addPawnAdvance(row, col, one, col, promoRow, out);
      const two = row + 2 * dir;
      if (row === startRow && this.board[two][col] === EMPTY) {
        out.push(stepMove(row, col, two, col));
      }
    }
    for (const dc of [-1, 1]) {
      const c = col + dc;
      if (!inBounds(one, c)) continue;
      const target = this.board[one][c];
      if (target !== EMPTY && sideOfPiece(target) !== side) {
        this._addPawnAdvance(row, col, one, c, promoRow, out);
      } else if (one === this.epRow && c === this.epCol && target === EMPTY) {
        out.push(stepMove(row, col, one, c, EMPTY, true, false));
      }
    }
  }

  _addPawnAdvance(fromRow, fromCol, toRow, toCol, promoRow, out) {
    if (toRow === promoRow) {
      for (const promo of [QUEEN, ROOK, BISHOP, KNIGHT]) {
        out.push(stepMove(fromRow, fromCol, toRow, toCol, promo, false, false));
      }
    } else {
      out.push(stepMove(fromRow, fromCol, toRow, toCol));
    }
  }

  _addCastlingMoves(row, col, out) {
    const side = this.side;
    const backRow = side === WHITE ? 7 : 0;
    if (row !== backRow || col !== 4) return;
    if (this.inCheck(side)) return;
    const enemy = opponent(side);
    const ks = side === WHITE ? this.wk : this.bk;
    const qs = side === WHITE ? this.wq : this.bq;
    if (ks && this.board[backRow][5] === EMPTY
        && this.board[backRow][6] === EMPTY
        && this.board[backRow][7] === pieceOf(ROOK, side)
        && !this.isSquareAttacked(backRow, 5, enemy)
        && !this.isSquareAttacked(backRow, 6, enemy)) {
      out.push(stepMove(row, col, backRow, 6, EMPTY, false, true));
    }
    if (qs && this.board[backRow][3] === EMPTY
        && this.board[backRow][2] === EMPTY
        && this.board[backRow][1] === EMPTY
        && this.board[backRow][0] === pieceOf(ROOK, side)
        && !this.isSquareAttacked(backRow, 3, enemy)
        && !this.isSquareAttacked(backRow, 2, enemy)) {
      out.push(stepMove(row, col, backRow, 2, EMPTY, false, true));
    }
  }

  /** Pseudo-legal moves (king safety not yet checked). */
  pseudoLegalMoves() {
    const out = [];
    for (let r = 0; r < 8; r++) {
      for (let c = 0; c < 8; c++) {
        const piece = this.board[r][c];
        if (piece === EMPTY || sideOfPiece(piece) !== this.side) continue;
        switch (typeOf(piece)) {
          case PAWN: this._addPawnMoves(r, c, out); break;
          case KNIGHT:
            this._addStepMoves(r, c, [[-2, -1], [-2, 1], [-1, -2], [-1, 2],
              [1, -2], [1, 2], [2, -1], [2, 1]], out);
            break;
          case BISHOP:
            this._addSliderMoves(r, c, [[-1, -1], [-1, 1], [1, -1], [1, 1]], out);
            break;
          case ROOK:
            this._addSliderMoves(r, c, [[-1, 0], [1, 0], [0, -1], [0, 1]], out);
            break;
          case QUEEN:
            this._addSliderMoves(r, c, [[-1, 0], [1, 0], [0, -1], [0, 1],
              [-1, -1], [-1, 1], [1, -1], [1, 1]], out);
            break;
          case KING:
            this._addStepMoves(r, c, [[-1, -1], [-1, 0], [-1, 1], [0, -1],
              [0, 1], [1, -1], [1, 0], [1, 1]], out);
            this._addCastlingMoves(r, c, out);
            break;
          default: break;
        }
      }
    }
    return out;
  }

  /** Fully legal moves for the side to move. */
  legalMoves() {
    const result = [];
    if (this.isGameOver()) return result;
    for (const m of this.pseudoLegalMoves()) {
      const probe = this.copy();
      probe._applyUnchecked(m);
      if (!probe.inCheck(this.side)) result.push(m);
    }
    return result;
  }

  legalMovesFor(row, col) {
    const result = [];
    for (const m of this.legalMoves()) {
      if (m.fromRow === row && m.fromCol === col) result.push(m);
    }
    return result;
  }

  isLegal(move) {
    return this.legalMoves().some((m) => sameMove(m, move));
  }

  /** Is this move a capture (including en passant)? */
  isCapture(move) {
    return move.isEnPassant || this.board[move.toRow][move.toCol] !== EMPTY;
  }

  /** Applies a move without legality or history bookkeeping. */
  _applyUnchecked(move) {
    const piece = this.board[move.fromRow][move.fromCol];
    let captured = this.board[move.toRow][move.toCol];
    const mover = this.side;
    this.board[move.fromRow][move.fromCol] = EMPTY;
    if (move.isEnPassant) {
      this.board[move.fromRow][move.toCol] = EMPTY;
      captured = pieceOf(PAWN, opponent(mover));
    }
    if (move.isCastling) {
      const backRow = mover === WHITE ? 7 : 0;
      if (move.toCol === 6) {
        this.board[backRow][5] = this.board[backRow][7];
        this.board[backRow][7] = EMPTY;
      } else {
        this.board[backRow][3] = this.board[backRow][0];
        this.board[backRow][0] = EMPTY;
      }
    }
    let placed = piece;
    if (move.promotion !== EMPTY) placed = pieceOf(move.promotion, mover);
    this.board[move.toRow][move.toCol] = placed;
    if (typeOf(piece) === KING) {
      if (mover === WHITE) { this.wk = false; this.wq = false; }
      else { this.bk = false; this.bq = false; }
    }
    this._clearRookRight(move.fromRow, move.fromCol);
    this._clearRookRight(move.toRow, move.toCol);
    this.epRow = -1;
    this.epCol = -1;
    if (typeOf(piece) === PAWN && Math.abs(move.toRow - move.fromRow) === 2) {
      this.epRow = (move.fromRow + move.toRow) / 2;
      this.epCol = move.fromCol;
    }
    if (typeOf(piece) === PAWN || captured !== EMPTY) this.halfmove = 0;
    else this.halfmove++;
    if (mover === BLACK) this.fullmove++;
    this.side = opponent(mover);
  }

  _clearRookRight(row, col) {
    if (row === 7 && col === 0) this.wq = false;
    else if (row === 7 && col === 7) this.wk = false;
    else if (row === 0 && col === 0) this.bq = false;
    else if (row === 0 && col === 7) this.bk = false;
  }

  /**
   * Applies one legal move. Throws otherwise and leaves the
   * position unchanged.
   */
  applyMove(move) {
    const legal = this.legalMoves().find((m) => sameMove(m, move));
    if (!legal) throw new Error(`Illegal chess move: ${JSON.stringify(move)}`);
    this._applyUnchecked(legal);
    this._recordPosition();
    this._updateOutcome();
    return legal;
  }

  _positionKey() {
    let sb = '';
    for (let r = 0; r < 8; r++) {
      for (let c = 0; c < 8; c++) sb += `${this.board[r][c]},`;
    }
    sb += this.side;
    sb += this.wk ? 'K' : '-';
    sb += this.wq ? 'Q' : '-';
    sb += this.bk ? 'k' : '-';
    sb += this.bq ? 'q' : '-';
    // En passant counts toward repetition only when a capture onto it
    // is actually possible.
    if (this.epRow >= 0 && this._canCaptureEnPassant()) {
      sb += squareName(this.epRow, this.epCol);
    }
    return sb;
  }

  _canCaptureEnPassant() {
    const enemy = this.side;
    const pawn = pieceOf(PAWN, enemy);
    for (const dc of [-1, 1]) {
      const c = this.epCol + dc;
      if (inBounds(this.epRow, c) && this.board[this.epRow][c] === pawn) return true;
    }
    return false;
  }

  _recordPosition() {
    const key = this._positionKey();
    this.counts[key] = (this.counts[key] || 0) + 1;
  }

  insufficientMaterial() {
    let hasPawnRookQueen = false;
    let minorCount = 0;
    let bishopColour = -1;
    let bishopsSameColour = true;
    for (let r = 0; r < 8; r++) {
      for (let c = 0; c < 8; c++) {
        const t = typeOf(this.board[r][c]);
        if (t === PAWN || t === ROOK || t === QUEEN) {
          hasPawnRookQueen = true;
        } else if (t === KNIGHT || t === BISHOP) {
          minorCount++;
          if (t === BISHOP) {
            const colour = (r + c) % 2;
            if (bishopColour < 0) bishopColour = colour;
            else if (bishopColour !== colour) bishopsSameColour = false;
          } else {
            bishopsSameColour = false;
          }
        }
      }
    }
    if (hasPawnRookQueen) return false;
    if (minorCount <= 1) return true;
    return minorCount === 2 && bishopsSameColour;
  }

  _updateOutcome() {
    this.outcome = IN_PROGRESS;
    this.reason = '';
    if (this.legalMoves().length === 0) {
      if (this.inCheck()) {
        this.outcome = this.side === WHITE ? BLACK_WINS : WHITE_WINS;
        this.reason = 'Checkmate';
      } else {
        this.outcome = DRAW;
        this.reason = 'Stalemate';
      }
      return;
    }
    if (this.halfmove >= 100) {
      this.outcome = DRAW;
      this.reason = 'Fifty-move rule';
      return;
    }
    const reps = this.counts[this._positionKey()] || 0;
    if (reps >= 3) {
      this.outcome = DRAW;
      this.reason = 'Threefold repetition';
      return;
    }
    if (this.insufficientMaterial()) {
      this.outcome = DRAW;
      this.reason = 'Insufficient mating material';
    }
  }

  /**
   * Parses coordinate notation: "e2e4", "e2-e4", "e7e8q", "e7e8=Q".
   * A bare promotion ("e7e8") defaults to queen.
   */
  parseCoordinate(text) {
    if (text == null) throw new Error('Null chess move');
    const m = String(text).trim().match(/^([a-h][1-8])[-\s]?([a-h][1-8])(?:[=\s]?([qrnbQRNB]))?$/);
    if (!m) throw new Error(`Bad coordinate move: ${text}`);
    const [fr, fc] = parseSquare(m[1]);
    const [tr, tc] = parseSquare(m[2]);
    let promo = EMPTY;
    if (m[3] != null) promo = typeFromLetter(m[3]);
    for (const legal of this.legalMoves()) {
      if (legal.fromRow === fr && legal.fromCol === fc
          && legal.toRow === tr && legal.toCol === tc
          && (promo === EMPTY || legal.promotion === promo)) {
        if (promo === EMPTY && legal.promotion !== EMPTY) {
          return stepMove(fr, fc, tr, tc, QUEEN, legal.isEnPassant, legal.isCastling);
        }
        return legal;
      }
    }
    throw new Error(`Illegal chess move: ${text}`);
  }

  /** Parses then applies one coordinate-notation move. */
  applyNotation(notation) {
    const move = this.parseCoordinate(notation);
    return this.applyMove(move);
  }

  toFen() {
    const rows = [];
    for (let r = 0; r < 8; r++) {
      let row = '';
      let empty = 0;
      for (let c = 0; c < 8; c++) {
        const p = this.board[r][c];
        if (p === EMPTY) {
          empty++;
        } else {
          if (empty > 0) { row += empty; empty = 0; }
          row += fenLetter(p);
        }
      }
      if (empty > 0) row += empty;
      rows.push(row);
    }
    let castle = '';
    if (this.wk) castle += 'K';
    if (this.wq) castle += 'Q';
    if (this.bk) castle += 'k';
    if (this.bq) castle += 'q';
    if (!castle) castle = '-';
    const ep = this.epRow >= 0 ? squareName(this.epRow, this.epCol) : '-';
    return `${rows.join('/')} ${this.side} ${castle} ${ep} ${this.halfmove} ${this.fullmove}`;
  }

  loadFen(fen) {
    if (typeof fen !== 'string') throw new Error(`Bad FEN: ${fen}`);
    const parts = fen.trim().split(/\s+/);
    if (parts.length < 2) throw new Error(`Bad FEN: ${fen}`);
    const ranks = parts[0].split('/');
    if (ranks.length !== 8) throw new Error(`Bad FEN: ${fen}`);
    const board = Array.from({ length: 8 }, () => new Array(8).fill(EMPTY));
    for (let r = 0; r < 8; r++) {
      let c = 0;
      for (const ch of ranks[r]) {
        if (ch >= '1' && ch <= '8') {
          c += ch.charCodeAt(0) - '0'.charCodeAt(0);
        } else {
          if (c >= 8) throw new Error(`Bad FEN: ${fen}`);
          const up = ch.toUpperCase();
          board[r][c] = ch === up ? typeFromLetter(up) : -typeFromLetter(up);
          c++;
        }
      }
      if (c !== 8) throw new Error(`Bad FEN: ${fen}`);
    }
    const side = parts[1];
    if (side !== WHITE && side !== BLACK) throw new Error(`Bad FEN: ${fen}`);
    this.board = board;
    this.side = side;
    const castle = parts[2] || '-';
    this.wk = castle.includes('K');
    this.wq = castle.includes('Q');
    this.bk = castle.includes('k');
    this.bq = castle.includes('q');
    const ep = parts[3] || '-';
    if (ep === '-') {
      this.epRow = -1; this.epCol = -1;
    } else {
      const [er, ec] = parseSquare(ep);
      this.epRow = er; this.epCol = ec;
    }
    this.halfmove = parts.length > 4 ? parseInt(parts[4], 10) || 0 : 0;
    this.fullmove = parts.length > 5 ? parseInt(parts[5], 10) || 1 : 1;
    this.outcome = IN_PROGRESS;
    this.reason = '';
    this.counts = Object.create(null);
    this._recordPosition();
    this._updateOutcome();
    return this;
  }

  boardDiagram() {
    let sb = '';
    for (let r = 0; r < 8; r++) {
      sb += `${8 - r} `;
      for (let c = 0; c < 8; c++) {
        const p = this.board[r][c];
        sb += (p === EMPTY ? '.' : fenLetter(p)) + ' ';
      }
      sb += '\n';
    }
    sb += '  a b c d e f g h\n';
    sb += (this.side === WHITE ? 'White' : 'Black') + ' to move';
    return sb;
  }

  serialize() {
    return { game: 'chess', board: this.board.map((row) => row.slice()),
      side: this.side, castle: [this.wk, this.wq, this.bk, this.bq],
      ep: [this.epRow, this.epCol], halfmove: this.halfmove, fullmove: this.fullmove,
      counts: Object.assign({}, this.counts), outcome: this.outcome, reason: this.reason };
  }

  static deserialize(data) {
    const e = new ChessEngine(false);
    e.board = data.board.map((row) => row.slice());
    e.side = data.side;
    [e.wk, e.wq, e.bk, e.bq] = data.castle;
    [e.epRow, e.epCol] = data.ep;
    e.halfmove = data.halfmove;
    e.fullmove = data.fullmove;
    e.counts = Object.assign(Object.create(null), data.counts);
    e.outcome = data.outcome;
    e.reason = data.reason;
    return e;
  }
}

module.exports = {
  ChessEngine,
  EMPTY, PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING,
  WHITE, BLACK, START_FEN,
  IN_PROGRESS, WHITE_WINS, BLACK_WINS, DRAW,
  opponent, pieceOf, typeOf, sideOfPiece, letter, typeFromLetter,
  inBounds, squareName, parseSquare, moveToString, stepMove,
};