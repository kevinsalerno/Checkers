'use strict';

/**
 * Passwords (scrypt) + session tokens (signed HS256 JWT-style).
 * stdlib only: node:crypto.
 */

const crypto = require('node:crypto');

const JWT_SECRET = process.env.HUB_JWT_SECRET || 'dev-only-checkers-chess-secret';
const TOKEN_TTL_S = 7 * 24 * 3600;

function base64url(input) {
  return Buffer.from(input).toString('base64url');
}

function signJwt(payload, ttlS = TOKEN_TTL_S) {
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = base64url(JSON.stringify({
    ...payload,
    iat: Math.floor(Date.now() / 1000),
    exp: Math.floor(Date.now() / 1000) + ttlS,
  }));
  const sig = crypto.createHmac('sha256', JWT_SECRET)
    .update(`${header}.${body}`).digest('base64url');
  return `${header}.${body}.${sig}`;
}

function verifyJwt(token) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [header, body, sig] = parts;
  const want = crypto.createHmac('sha256', JWT_SECRET)
    .update(`${header}.${body}`).digest('base64url');
  const a = Buffer.from(sig);
  const b = Buffer.from(want);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  let payload;
  try {
    payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
  } catch {
    return null;
  }
  if (typeof payload.exp !== 'number' || payload.exp * 1000 < Date.now()) return null;
  return payload;
}

/** scrypt hash, self-describing "s1$<salt-b64>$<hash-b64>" string. */
function hashPassword(password) {
  if (typeof password !== 'string' || password.length < 6) {
    throw Object.assign(new Error('Password must be at least 6 characters'), { status: 400 });
  }
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(password, salt, 32);
  return `s1$${salt.toString('base64')}$${hash.toString('base64')}`;
}

function verifyPassword(password, stored) {
  try {
    const [tag, saltB64, hashB64] = String(stored).split('$');
    if (tag !== 's1') return false;
    const salt = Buffer.from(saltB64, 'base64');
    const want = Buffer.from(hashB64, 'base64');
    const got = crypto.scryptSync(String(password), salt, 32);
    return got.length === want.length && crypto.timingSafeEqual(got, want);
  } catch {
    return false;
  }
}

function bearerToken(req) {
  const h = req.headers.authorization || '';
  const m = h.match(/^Bearer\s+(.+)$/i);
  return m ? m[1].trim() : null;
}

module.exports = {
  signJwt, verifyJwt, hashPassword, verifyPassword, bearerToken, TOKEN_TTL_S,
};
