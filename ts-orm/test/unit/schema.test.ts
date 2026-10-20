import test from "node:test";
import assert from "node:assert/strict";
import {
  column,
  Column,
  createDatabase,
  DataType,
  postgresDialect,
  table,
  Table,
  types,
} from "../../src/index.js";

test("custom data types validate their storage and allow subclass fields", () => {
  class TaggedType extends DataType<string, "text"> {
    readonly tag = "label";
  }
  const storage = { kind: "text" as const, length: 20 };
  const custom = new TaggedType(storage, types.text(20).codec);
  assert.equal(custom.tag, "label");
  assert.equal(custom.decode("value"), "value");
  storage.length = 1;
  assert.equal(custom.storage.length, 20);
  assert.throws(() => new DataType({ kind: "text" }, types.text().codec), /length/);
  assert.throws(
    () => new DataType({ kind: "decimal", precision: 39, scale: 0 }, types.decimal().codec),
    /precision/,
  );
  class TaggedColumn extends Column<string, "text"> {
    readonly tag = "column";
  }
  class TaggedTable extends Table {
    readonly tag = "table";
  }
  assert.equal(new TaggedColumn(custom).tag, "column");
  assert.equal(new TaggedTable("tagged", { name: column(custom) }).tag, "table");
});

test("SQL record boundaries reject symbol and non-enumerable fields from untyped callers", () => {
  const key = Symbol("hidden");
  const columns = { id: column(types.integer()), [key]: column(types.text()) };
  // 模拟 JS 调用者：运行时拒绝不能由 SQL 投影表达的键，不能只依赖 TypeScript。
  assert.throws(() => table("hidden", columns as never), /enumerable string keys/);
  const db = createDatabase({ dialect: postgresDialect });
  const users = table("users", { id: column(types.integer()) });
  assert.throws(() => db.from({ u: users, [key]: users } as never), /enumerable string keys/);
  const base = db.from({ u: users });
  assert.throws(
    () => base.select(({ u }) => ({ id: u.id, [key]: u.id }) as never),
    /enumerable string keys/,
  );
  const selection = { id: base.scope.u.id };
  Object.defineProperty(selection, "hidden", { value: base.scope.u.id });
  assert.throws(() => base.select(() => selection), /enumerable string keys/);
});

test("ordering snapshots caller-owned items instead of allowing old queries to change", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({
    u: table("users", { id: column(types.integer()), name: column(types.text()) }),
  });
  const ordering = { expression: base.scope.u.id.node, direction: "ASC" as const };
  const selected = base.orderBy(() => ordering).select(({ u }) => ({ id: u.id }));
  const before = selected.compile().sql;
  ordering.expression = base.scope.u.name.node;
  assert.equal(selected.compile().sql, before);
  assert.match(before, /ORDER BY "u"\."id" ASC/);
});
