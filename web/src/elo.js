'use strict';

/** Standard ELO update, K-factor 32. */

const K = 32;

function expected(scoreA, scoreB) {
  return 1 / (1 + Math.pow(10, (scoreB - scoreA) / 400));
}

/**
 * New ratings after a game. `scoreA` is 1 (A wins), 0.5 (draw) or 0.
 * Returns [newA, newB] rounded to integers.
 */
function updateRatings(eloA, eloB, scoreA) {
  const newA = Math.round(eloA + K * (scoreA - expected(eloA, eloB)));
  const newB = Math.round(eloB + K * ((1 - scoreA) - expected(eloB, eloA)));
  return [newA, newB];
}

module.exports = { K, expected, updateRatings };
