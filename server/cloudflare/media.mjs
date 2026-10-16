import { Readable } from "node:stream";

// Keep clips in durable SQLite chunks; no paid R2 subscription or ephemeral disk.
export function durableMedia(storage, capacity = 800 * 1024 * 1024) {
  const sql = storage.sql;
  sql.exec("CREATE TABLE IF NOT EXISTS media_uploads(id TEXT PRIMARY KEY,size INTEGER NOT NULL,created INTEGER NOT NULL)");
  sql.exec("CREATE TABLE IF NOT EXISTS media_chunks(id TEXT NOT NULL,part INTEGER NOT NULL,data BLOB NOT NULL,PRIMARY KEY(id,part))");
  const remove = (id) => storage.transactionSync(() => {
    sql.exec("DELETE FROM media_chunks WHERE id=?", id);
    sql.exec("DELETE FROM media_uploads WHERE id=?", id);
  });
  return {
    async write(id, source, size) {
      storage.transactionSync(() => {
        const used = sql.exec("SELECT COALESCE(SUM(size),0) AS n FROM media_uploads").one().n;
        if (used + size > capacity) throw Object.assign(Error("Shared clip storage is full"), { status: 507 });
        sql.exec("INSERT INTO media_uploads VALUES(?,?,?)", id, size, Date.now());
      });
      let part = 0;
      let count = 0;
      try {
        for await (const bytes of source) {
          count += bytes.length;
          if (count > size) throw Object.assign(Error("Clip size mismatch"), { status: 400 });
          for (let at = 0; at < bytes.length; at += 64 * 1024)
            sql.exec("INSERT INTO media_chunks VALUES(?,?,?)", id, part++, bytes.subarray(at, at + 64 * 1024));
        }
        if (count !== size) throw Object.assign(Error("Clip size mismatch"), { status: 400 });
      } catch (error) {
        remove(id);
        throw error;
      }
    },
    read(id) {
      return Readable.from((async function* () {
        let part = 0;
        while (true) {
          const row = sql.exec("SELECT data FROM media_chunks WHERE id=? AND part=?", id, part++).toArray()[0];
          if (!row) return;
          yield Buffer.from(row.data);
        }
      })());
    },
    remove,
    cleanup() {
      // Abandoned transfers never become downloadable. Expired sessions retain
      // their shared clips for a day, then release this free account's capacity.
      const stale = sql.exec("SELECT u.id FROM media_uploads u LEFT JOIN clips c ON c.id=u.id LEFT JOIN sessions s ON s.id=c.session WHERE (c.id IS NULL AND u.created<?) OR s.expires<?", Date.now() - 3600000, Date.now() - 86400000).toArray();
      for (const row of stale) storage.transactionSync(() => {
        remove(row.id);
        sql.exec("DELETE FROM clips WHERE id=?", row.id);
      });
    },
  };
}
