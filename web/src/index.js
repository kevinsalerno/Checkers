'use strict';

/**
 * Entry point: open (or create) the SQLite file and serve synchronously
 * in the foreground. Run with `npm start`, then open http://localhost:3000
 * in a browser. Stop with Ctrl-C.
 */

const path = require('node:path');
const db = require('./db');
const { createApp } = require('./server');

const PORT = Number(process.env.PORT || 3000);
const DB_FILE = process.env.HUB_DB || path.join(__dirname, '..', 'data', 'hub.sqlite');

const handle = db.openDb(DB_FILE);
const { server } = createApp(handle);

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Checkers & Chess Hub listening on http://localhost:${PORT}`);
  console.log(`Database: ${DB_FILE}`);
});

function shutdown() {
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1000).unref();
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
