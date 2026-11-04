// Minimal D1 stand-in over node:sqlite for unit tests.
import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync } from "node:fs";

class Stmt {
  constructor(db, sql, params = []) { this.db = db; this.sql = sql; this.params = params; }
  bind(...p) { return new Stmt(this.db, this.sql, p); }
  _s() { return this.db.prepare(this.sql); }
  async first() { return this._s().get(...this.params) ?? null; }
  async all() { return { results: this._s().all(...this.params), success: true }; }
  async run() { const r = this._s().run(...this.params); return { success: true, meta: { changes: r.changes } }; }
  _runSync() { return this._s().run(...this.params); }
}

export function makeD1(migrationsDir) {
  const db = new DatabaseSync(":memory:");
  db.exec("PRAGMA foreign_keys = ON");
  for (const f of readdirSync(migrationsDir).sort()) db.exec(readFileSync(`${migrationsDir}/${f}`, "utf8"));
  return {
    raw: db,
    prepare: (sql) => new Stmt(db, sql),
    async batch(stmts) {
      db.exec("BEGIN");
      try { const r = stmts.map((s) => s._runSync()); db.exec("COMMIT"); return r; }
      catch (e) { db.exec("ROLLBACK"); throw e; }
    },
  };
}
