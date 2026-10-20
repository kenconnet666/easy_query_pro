import test from "node:test";
import assert from "node:assert/strict";
import {
  call,
  column,
  createDatabase,
  Expression,
  hanaDialect,
  mysqlDialect,
  postgresDialect,
  sqlFragment,
  table,
  types,
  valueOf,
} from "../../src/index.js";
import type { SqlNode } from "../../src/index.js";

const users = table("user", {
  id: column(types.integer()),
  name: column(types.text()),
  managerId: column(types.integer()).nullable(),
});
const departments = table("department", {
  id: column(types.integer()),
  name: column(types.text()),
});

test("base queries branch without sharing WHERE mutation", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ u: users });
  const first = base.where(({ u }) => u.id.gt(1)).select(({ u }) => ({ id: u.id }));
  const second = base.where(({ u }) => u.id.gt(2)).select(({ u }) => ({ id: u.id }));
  assert.deepEqual(first.compile().parameters, [1]);
  assert.deepEqual(second.compile().parameters, [2]);
  assert.deepEqual(base.select(({ u }) => ({ id: u.id })).compile().parameters, []);
  assert.equal(first.compile().sql, first.compile().sql);
});

for (const dialect of [postgresDialect, mysqlDialect, hanaDialect]) {
  test(dialect.name + " quotes aliases and binds values in actual SQL order", () => {
    const db = createDatabase({ dialect });
    const inner = db
      .from({ u: users })
      .where(({ u }) => u.id.gt(7))
      .select(({ u }) => ({ id: u.id, name: u.name }));
    const outer = db
      .from({ r: inner.as() })
      .where(({ r }) => r.id.lt(99))
      .select(({ r }) => ({ shifted: valueOf(10, types.integer()).add(r.id), label: r.name }));
    const compiled = outer.compile();
    assert.deepEqual(compiled.parameters, [10, 7, 99]);
    assert.equal((compiled.sql.match(/CAST\(/g) ?? []).length, 3);
    assert.equal(compiled.sql.includes("'99'"), false);
    assert.deepEqual(compiled.decode({ _eq_0: "12", _eq_1: "O'Reilly 中文" }), {
      shifted: 12,
      label: "O'Reilly 中文",
    });
  });
}

test("LEFT JOIN keeps nullable projection fields without changing original schema", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const query = db
    .from({ u: users })
    .leftJoin({ d: departments }, ({ u, d }) => u.managerId.eq(d.id))
    .select(({ u, d }) => ({ id: u.id, department: d.name }));
  assert.deepEqual(query.compile().decode({ _eq_0: 1, _eq_1: null }), { id: 1, department: null });
  assert.equal(departments.columns.name.type.acceptsNull, false);
  assert.match(query.compile().sql, /"u"\."managerId" = "d"\."id"/);
});

test("self joins bind two independent relation identities", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const query = db
    .from({ person: users })
    .leftJoin({ manager: users }, ({ person, manager }) => person.managerId.eq(manager.id))
    .select(({ person, manager }) => ({ person: person.name, manager: manager.name }));
  assert.match(query.compile().sql, /"person"\."managerId" = "manager"\."id"/);
});

test("derived projection reorder preserves custom decoding and parameter encoding", () => {
  const wrapped = types.text().map({
    identity: Symbol("label"),
    to: (value: { label: string }) => value.label,
    from: (value) => ({ label: value }),
  });
  const records = table("record", { id: column(types.integer()), label: column(wrapped) });
  const db = createDatabase({ dialect: postgresDialect });
  const inner = db
    .from({ r: records })
    .where(({ r }) => r.label.eq({ label: "wrapped" }))
    .select(({ r }) => ({ id: r.id, label: r.label }));
  const outer = db
    .from({ x: inner.as() })
    .select(({ x }) => ({ label: x.label, id: x.id }))
    .compile();
  assert.deepEqual(outer.parameters, ["wrapped"]);
  assert.deepEqual(outer.decode({ _eq_0: "decoded", _eq_1: 2 }), {
    label: { label: "decoded" },
    id: 2,
  });
});

test("foreign client and invisible relation references are rejected", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const first = db.from({ u: users });
  const another = db.from({ u: users }).select(() => ({ bad: first.scope.u.id }));
  assert.throws(() => another.compile(), /outside this query scope/);
  const foreign = createDatabase({ dialect: postgresDialect });
  assert.throws(
    () => foreign.from({ r: first.select(({ u }) => ({ id: u.id })).as() }),
    /cross database/,
  );
});

test("nested SQL wrappers cannot hide same-level aggregate predicates", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const aggregate = call(
    {
      name: "test-sum",
      phase: "aggregate",
      render: { postgres: (args) => sqlFragment`SUM(${args[0]!})` },
    },
    types.integer(),
    [valueOf(1, types.integer())],
  );
  const query = db
    .from({ u: users })
    .where(() => aggregate.add(1).gt(2))
    .select(({ u }) => ({ id: u.id }));
  assert.throws(() => query.compile(), /same-level aggregate/);
});

test("SQL NULL, undefined, unsafe bigint and precise decimal have explicit contracts", () => {
  assert.throws(() => types.integer().decode(null), /Unexpected SQL NULL/);
  assert.equal(types.integer().nullable().decode(null), null);
  assert.throws(() => valueOf(undefined as never, types.integer()), /undefined/);
  assert.throws(() => types.bigint().decode(Number.MAX_SAFE_INTEGER + 1), /Unsafe bigint/);
  assert.throws(() => types.decimal(6, 2).encode("10000"), /precision/);
  assert.equal(types.decimal(6, 2).encode("1.20"), "1.20");
  assert.throws(() => types.decimal().decode(1.2), /losslessly/);
});

test("unsupported native extension fails at compilation", () => {
  const db = createDatabase({ dialect: hanaDialect });
  const pgOnly = call(
    { name: "pg-only", phase: "row", render: { postgres: (args) => args[0]! } },
    types.integer(),
    [valueOf(1, types.integer())],
  );
  assert.throws(
    () =>
      db
        .from({ u: users })
        .select(() => ({ value: pgOnly }))
        .compile(),
    /does not support hana/,
  );
});

for (const dialect of [postgresDialect, mysqlDialect, hanaDialect]) {
  test(dialect.name + " extension operands bind in final order, including duplicates", () => {
    const db = createDatabase({ dialect });
    const reordered = call(
      {
        name: "reverse-repeat",
        phase: "row",
        render: {
          [dialect.name]: (args: readonly SqlNode[]) =>
            sqlFragment`CONCAT(${args[1]!}, ${args[0]!}, ${args[1]!})`,
        },
      },
      types.text(),
      [valueOf("A", types.text()), valueOf("B", types.text())],
    );
    const compiled = db
      .from({ u: users })
      .select(() => ({ value: reordered }))
      .compile();
    assert.deepEqual(compiled.parameters, ["B", "A", "B"]);
    assert.equal(
      (compiled.sql.match(dialect.name === "postgres" ? /\$\d+/g : /\?/g) ?? []).length,
      3,
    );
  });
}

test("nullable business mappings propagate SQL NULL without invoking the business constructor", () => {
  let decoded = 0;
  const mapped = types
    .text()
    .nullable()
    .map({
      identity: Symbol("nullable-label"),
      to: (value: { text: string }) => value.text,
      from: (value) => {
        decoded++;
        return { text: value };
      },
    });
  assert.equal(mapped.decode(null), null);
  assert.equal(decoded, 0);
  assert.deepEqual(mapped.decode("present"), { text: "present" });
});

test("derived columns can explicitly remount a business expression", () => {
  class Label extends Expression<string, "text"> {
    normalized() {
      return this.upper();
    }
  }
  const db = createDatabase({ dialect: postgresDialect });
  const selected = db
    .from({ u: users })
    .select(({ u }) => ({ label: u.name.mount((binding) => new Label(binding)) }));
  const outer = db
    .from({ d: selected.as() })
    .select(({ d }) => ({ label: d.label.mount((binding) => new Label(binding)).normalized() }));
  assert.match(outer.compile().sql, /UPPER\(/);
});

test("INTEGER has one signed 32-bit domain for parameters and results", () => {
  assert.equal(types.integer().encode(2147483647), 2147483647);
  assert.equal(types.integer().decode("-2147483648"), -2147483648);
  assert.throws(() => types.integer().encode(2147483648), /32-bit/);
  assert.throws(() => types.integer().decode("-2147483649"), /32-bit/);
});

test("adding namespaces preserves existing client extension instances", () => {
  let created = 0;
  const db = createDatabase({
    dialect: postgresDialect,
    extensions: { base: () => ({ id: ++created }) },
  });
  const extended = db.withExtensions({ report: () => ({ tag: "report" }) });
  assert.equal(extended.extensions.base, db.extensions.base);
  assert.equal(created, 1);
});
