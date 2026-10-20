import test from "node:test";
import assert from "node:assert/strict";
import {
  column,
  createDatabase,
  postgresDialect,
  table,
  types,
  valueOf,
  type Driver,
  type DriverConnection,
  type Database,
  type TransactionOptions,
} from "../../src/index.js";

const rows = table("rows", { id: column(types.integer()) });
function fixture(failures: { execute?: Error; rollback?: Error; release?: Error } = {}) {
  const events: string[] = [];
  const connection: DriverConnection = {
    async execute() {
      events.push("execute");
      if (failures.execute) throw failures.execute;
      return { rows: [{ _eq_0: 1 }], affectedRows: 0n };
    },
    async begin() {
      events.push("begin");
    },
    async commit() {
      events.push("commit");
    },
    async rollback() {
      events.push("rollback");
      if (failures.rollback) throw failures.rollback;
    },
    async release() {
      events.push("release");
      if (failures.release) throw failures.release;
    },
  };
  const driver: Driver = {
    async acquire() {
      events.push("acquire");
      return connection;
    },
  };
  return { events, driver };
}

test("successful transaction owns one connection and invalidates escaped client", async () => {
  const { driver, events } = fixture();
  const db = createDatabase({ dialect: postgresDialect, driver });
  let escaped: Database | undefined;
  const result = await db.transaction(async (tx) => {
    escaped = tx;
    return tx
      .from({ r: rows })
      .select(({ r }) => ({ id: r.id }))
      .execute();
  });
  assert.deepEqual(result, [{ id: 1 }]);
  assert.deepEqual(events, ["acquire", "begin", "execute", "commit", "release"]);
  assert.throws(() => escaped!.from({ r: rows }), /already ended/);
});

test("rollback and release errors preserve the original query failure", async () => {
  const original = new Error("query failed");
  const rollback = new Error("rollback failed");
  const release = new Error("release failed");
  const { driver, events } = fixture({ execute: original, rollback, release });
  const db = createDatabase({ dialect: postgresDialect, driver });
  await assert.rejects(
    db.transaction(async (tx) => {
      await tx
        .from({ r: rows })
        .select(({ r }) => ({ id: r.id }))
        .execute();
    }),
    (error: unknown) =>
      error instanceof AggregateError &&
      error.errors[0] === original &&
      error.errors[1] === rollback &&
      error.errors[2] === release,
  );
  assert.deepEqual(events, ["acquire", "begin", "execute", "rollback", "release"]);
});

test("ordinary queries release their acquired connection on mapping success or failure", async () => {
  const { driver, events } = fixture();
  const db = createDatabase({ dialect: postgresDialect, driver });
  assert.deepEqual(
    await db
      .from({ r: rows })
      .select(({ r }) => ({ id: r.id }))
      .execute(),
    [{ id: 1 }],
  );
  assert.deepEqual(events, ["acquire", "execute", "release"]);
  await assert.rejects(
    db
      .from({ r: rows })
      .select(() => ({ text: valueOf("text", types.text()) }))
      .execute(),
    /Cannot decode projection/,
  );
  assert.deepEqual(events, ["acquire", "execute", "release", "acquire", "execute", "release"]);
});

test("nested transactions are rejected before borrowing another connection", async () => {
  const { driver, events } = fixture();
  const db = createDatabase({ dialect: postgresDialect, driver });
  await db.transaction(async (tx) => {
    await assert.rejects(
      tx.transaction(async () => 1),
      /Nested/,
    );
  });
  assert.deepEqual(events, ["acquire", "begin", "commit", "release"]);
});

test("escaped transactions stop accepting queries before COMMIT finishes", async () => {
  let entered!: () => void;
  let finish!: () => void;
  const committing = new Promise<void>((resolve) => {
    entered = resolve;
  });
  const pending = new Promise<void>((resolve) => {
    finish = resolve;
  });
  const base = fixture();
  const connection = await base.driver.acquire();
  connection.commit = async () => {
    entered();
    await pending;
  };
  const db = createDatabase({
    dialect: postgresDialect,
    driver: {
      async acquire() {
        return connection;
      },
    },
  });
  let escaped!: Database;
  const completed = db.transaction(async (tx) => {
    escaped = tx;
  });
  await committing;
  try {
    assert.throws(() => escaped.from({ r: rows }), /already ended/);
  } finally {
    finish();
    await completed;
  }
});

test("transaction options validate before acquisition and snapshot once for custom drivers", async () => {
  const base = fixture();
  const options = { isolationLevel: "repeatableRead" as const, readOnly: true };
  let received: TransactionOptions | undefined;
  const driver: Driver = {
    transactionCapabilities: { isolationLevels: ["repeatableRead"], readOnly: true },
    async acquire() {
      options.readOnly = false;
      const connection = await base.driver.acquire();
      connection.begin = async (selected) => {
        received = selected;
      };
      return connection;
    },
  };
  const db = createDatabase({ dialect: postgresDialect, driver });
  await db.transaction(async () => {}, options);
  assert.deepEqual(received, { isolationLevel: "repeatableRead", readOnly: true });
  assert.ok(Object.isFrozen(received));
  base.events.length = 0;
  for (const invalid of [
    { isolationLevel: "bad" },
    { readOnly: "true" },
    { extra: 1 },
    { isolationLevel: "serializable" },
  ])
    await assert.rejects(
      db.transaction(
        async () => assert.fail("callback must not run"),
        invalid as TransactionOptions,
      ),
      TypeError,
    );
  await assert.rejects(
    createDatabase({ dialect: postgresDialect, driver: base.driver }).transaction(async () => {}, {
      readOnly: true,
    }),
    /does not support/,
  );
  assert.deepEqual(base.events, []);
});

test("failed transaction setup never invokes the callback and still cleans the lease", async () => {
  const base = fixture();
  const driver: Driver = {
    async acquire() {
      const connection = await base.driver.acquire();
      connection.begin = async () => {
        base.events.push("begin");
        throw new Error("setup failed");
      };
      return connection;
    },
  };
  await assert.rejects(
    createDatabase({ dialect: postgresDialect, driver }).transaction(async () =>
      assert.fail("callback must not run"),
    ),
    /setup failed/,
  );
  assert.deepEqual(base.events, ["acquire", "begin", "rollback", "release"]);
});
