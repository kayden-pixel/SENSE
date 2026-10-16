// The shared API receives the Durable Object database explicitly.
export class DatabaseSync {
  constructor() { throw Error("Durable storage must be supplied by the worker"); }
}

export function durableDatabase(storage) {
  const sql = storage.sql;
  sql.exec("CREATE TABLE IF NOT EXISTS sense_schema (id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
  sql.exec("INSERT OR IGNORE INTO sense_schema VALUES(1,0)");
  return {
    exec(statement) {
      if (statement.startsWith("PRAGMA foreign_keys=")) return;
      const script = statement
        .replace(/^BEGIN;\s*/, "")
        .replace(/COMMIT;\s*$/, "")
        .replace(/PRAGMA user_version=(\d+)/g, "UPDATE sense_schema SET version=$1 WHERE id=1");
      storage.transactionSync(() => sql.exec(script));
    },
    prepare(statement) {
      if (statement === "PRAGMA user_version")
        return { get: () => ({ user_version: sql.exec("SELECT version FROM sense_schema WHERE id=1").one().version }) };
      return {
        all: (...args) => sql.exec(statement, ...args).toArray(),
        get: (...args) => sql.exec(statement, ...args).toArray()[0],
        run: (...args) => {
          sql.exec(statement, ...args).toArray();
          return { changes: sql.exec("SELECT changes() AS n").one().n };
        },
      };
    },
    close() {},
  };
}
