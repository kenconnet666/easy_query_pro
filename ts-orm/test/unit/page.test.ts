import test from "node:test";
import assert from "node:assert/strict";
import {
  column,
  createDatabase,
  postgresDialect,
  table,
  types,
  type Driver,
  type DriverResult,
} from "../../src/index.js";

const label = types.text().map({
  identity: Symbol("page-label"),
  to: (value: { text: string }) => value.text,
  from: (text) => ({ text }),
});
const records = table("records", { id: column(types.integer()), label: column(label) });

function fixture(results: readonly (DriverResult | Error)[]) {
  const events: string[] = [];
  const statements: string[] = [];
  const bindings: (readonly unknown[])[] = [];
  const driver: Driver = {
    async acquire() {
      events.push("acquire");
      return {
        async execute(sql, parameters) {
          events.push("execute");
          statements.push(sql);
          bindings.push(parameters);
          const result = results[statements.length - 1];
          if (result instanceof Error) throw result;
          if (!result) throw new Error("Unexpected query");
          return result;
        },
        async begin() {
          events.push("begin");
        },
        async commit() {
          events.push("commit");
        },
        async rollback() {
          events.push("rollback");
        },
        async release() {
          events.push("release");
        },
      };
    },
  };
  const db = createDatabase({ dialect: postgresDialect, driver });
  return { db, events, statements, bindings };
}
const count = (total: string): DriverResult => ({ rows: [{ _eq_count: total }], affectedRows: 0n });

test("pagination owns one lease, preserves the full report, bigint total and output codec", async () => {
  const { db, events, statements, bindings } = fixture([
    count("9007199254740993"),
    { rows: [{ _eq_0: 3, _eq_1: "third" }], affectedRows: 0n },
  ]);
  const source = db
    .from({ r: records })
    .where(({ r }) => r.id.gt(1))
    .select(({ r }) => ({ id: r.id, label: r.label }))
    .asCte("eligible");
  const query = db
    .from({ e: source })
    .select(({ e }) => ({ id: e.id, label: e.label }))
    .distinct()
    .orderBy(({ e }) => e.id.asc());
  const original = query.compile();
  const page = await query.page(2, 2);
  assert.deepEqual(page, {
    pageNumber: 2,
    pageSize: 2,
    total: 9007199254740993n,
    totalPages: 4503599627370497n,
    rows: [{ id: 3, label: { text: "third" } }],
  });
  assert.deepEqual(events, ["acquire", "execute", "execute", "release"]);
  assert.match(statements[0]!, /^WITH .*SELECT COUNT\(\*\).*SELECT DISTINCT/);
  assert.match(statements[1]!, /LIMIT 2 OFFSET 2$/);
  assert.deepEqual(bindings, [[1], [1]]);
  assert.equal(query.compile().sql, original.sql);
  assert.deepEqual(query.compile().parameters, original.parameters);
});

test("pagination validates bounds, ordering and existing limits before acquisition", async () => {
  const { db, events } = fixture([]);
  const unordered = db.from({ r: records }).select(({ r }) => ({ id: r.id }));
  const query = unordered.orderBy(({ r }) => r.id.asc());
  for (const [number, size] of [
    [0, 1],
    [1, 0],
    [1.5, 2],
    [1, NaN],
    [Number.MAX_SAFE_INTEGER, 2],
  ])
    await assert.rejects(query.page(number!, size!), RangeError);
  await assert.rejects(unordered.page(1, 1), /explicit order/);
  await assert.rejects(query.limit(5).page(1, 1), /unlimited/);
  assert.deepEqual(events, []);
});

test("pagination stops on count failure, and releases on second query or decoding failure", async () => {
  for (const results of [
    [new Error("count failure")],
    [count("-1")],
    [count("1"), new Error("data failure")],
    [count("1"), { rows: [{ _eq_0: "unsafe" }], affectedRows: 0n }],
  ]) {
    const { db, events } = fixture(results);
    await assert.rejects(
      db
        .from({ r: records })
        .select(({ r }) => ({ id: r.id }))
        .orderBy(({ r }) => r.id.asc())
        .page(1, 1),
    );
    assert.equal(events[0], "acquire");
    assert.equal(events.at(-1), "release");
    assert.equal(events.filter((event) => event === "execute").length, results.length);
  }
});

test("empty and out-of-range pages retain metadata and reuse the active transaction lease", async () => {
  for (const total of ["0", "2"]) {
    const { db, events } = fixture([count(total), { rows: [], affectedRows: 0n }]);
    const page = await db.transaction((tx) =>
      tx
        .from({ r: records })
        .select(({ r }) => ({ id: r.id }))
        .orderBy(({ r }) => r.id.asc())
        .page(3, 2),
    );
    assert.equal(page.totalPages, total === "0" ? 0n : 1n);
    assert.deepEqual(page.rows, []);
    assert.deepEqual(events, ["acquire", "begin", "execute", "execute", "commit", "release"]);
  }
});

test("an in-flight COUNT cannot start page data after its transaction callback ends", async () => {
  let counted!: (value: DriverResult) => void;
  let entered!: () => void;
  let finish!: () => void;
  const pendingCount = new Promise<DriverResult>((resolve) => {
    counted = resolve;
  });
  const committing = new Promise<void>((resolve) => {
    entered = resolve;
  });
  const pendingCommit = new Promise<void>((resolve) => {
    finish = resolve;
  });
  let queries = 0;
  const db = createDatabase({
    dialect: postgresDialect,
    driver: {
      async acquire() {
        return {
          async execute() {
            queries++;
            return pendingCount;
          },
          async begin() {},
          async commit() {
            entered();
            await pendingCommit;
          },
          async rollback() {},
          async release() {},
        };
      },
    },
  });
  let rejected!: Promise<void>;
  const completed = db.transaction(async (tx) => {
    rejected = assert.rejects(
      tx
        .from({ r: records })
        .select(({ r }) => ({ id: r.id }))
        .orderBy(({ r }) => r.id.asc())
        .page(1, 1),
      /already ended/,
    );
  });
  await committing;
  try {
    counted(count("1"));
    await rejected;
    assert.equal(queries, 1);
  } finally {
    finish();
    await completed;
  }
});
