import test from "node:test";
import assert from "node:assert/strict";
import {
  aggregate,
  call,
  caseWhen,
  coalesce,
  column,
  createDatabase,
  hanaDialect,
  mysqlDialect,
  nativeCast,
  nullIf,
  postgresDialect,
  sqlFragment,
  table,
  types,
  valueOf,
} from "../../src/index.js";

const sales = table("sales", {
  team: column(types.text()).nullable(),
  amount: column(types.decimal(19, 4)).nullable(),
  units: column(types.integer()),
});
for (const dialect of [postgresDialect, mysqlDialect, hanaDialect]) {
  test(
    dialect.name + " grouped conditional aggregates preserve binding order and NULL decoding",
    () => {
      const db = createDatabase({ dialect });
      const query = db
        .from({ s: sales })
        .where(({ s }) => s.units.gt(0))
        .groupBy(({ s }) => ({ team: s.team }))
        .having(({ s }) => aggregate.sum(s.amount).gt("10.0000"))
        .select(({ s }, _e, g) => ({
          team: g.team,
          total: aggregate.sum(s.amount).filter(s.units.gt(1)),
          count: aggregate.count().filter(s.units.gt(2)),
        }));
      const compiled = query.compile();
      assert.deepEqual(compiled.parameters, [1, 2, 0, "10.0000"]);
      assert.match(
        compiled.sql,
        /SUM\(CASE WHEN .* THEN CAST\(.* AS DECIMAL\(38,4\)\) ELSE NULL END\)/,
      );
      assert.match(compiled.sql, /COUNT\(CASE WHEN .* THEN 1 ELSE NULL END\)/);
      assert.deepEqual(compiled.decode({ _eq_0: null, _eq_1: null, _eq_2: "0" }), {
        team: null,
        total: null,
        count: 0n,
      });
    },
  );
}

test("grouping validates SELECT, HAVING and ORDER BY and allows transformations of grouped columns", () => {
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ s: sales });
  assert.throws(
    () => base.select(({ s }) => ({ team: s.team, total: aggregate.sum(s.amount) })).compile(),
    /ungrouped column/,
  );
  assert.throws(
    () =>
      base
        .groupBy(({ s }) => ({ team: s.team }))
        .having(({ s }) => s.units.gt(1))
        .select(({ s }) => ({ count: aggregate.count() }))
        .compile(),
    /ungrouped column/,
  );
  assert.throws(
    () =>
      base
        .groupBy(({ s }) => ({ team: s.team }))
        .orderBy(({ s }) => s.units.asc())
        .select(() => ({ count: aggregate.count() }))
        .compile(),
    /ungrouped column/,
  );
  assert.doesNotThrow(() =>
    base
      .groupBy(({ s }) => ({ team: s.team }))
      .select(({ s }) => ({ upper: s.team.upper(), count: aggregate.count() }))
      .compile(),
  );
  assert.throws(
    () =>
      base
        .groupBy(({ s }) => ({ team: s.team.upper() }))
        .select(({ s }) => ({ team: s.team, count: aggregate.count() }))
        .compile(),
    /ungrouped column/,
  );
  assert.doesNotThrow(() =>
    base
      .groupBy(({ s }) => ({ team: s.team.upper() }))
      .select((_s, _e, g) => ({ team: g.team, count: aggregate.count() }))
      .compile(),
  );
  assert.throws(
    () =>
      base
        .groupBy(({ s }) => ({ shifted: s.units.add(1) }))
        .select((_s, _e, g) => ({ shifted: g.shifted }))
        .compile(),
    /Parameterized GROUP BY/,
  );
  assert.throws(
    () =>
      base
        .orderBy(({ s }) => aggregate.sum(s.amount).asc())
        .select(({ s }) => ({ team: s.team }))
        .compile(),
    /ungrouped column/,
  );
});

test("aggregate and window boundaries remain visible through conditional wrappers", () => {
  assert.throws(() => aggregate.count(null as never), /typed expression/);
  assert.throws(() => aggregate.sum(valueOf("1", types.text()) as never), /unsupported SQL type/);
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ s: sales });
  const nested = aggregate.sum(aggregate.sum(base.scope.s.amount));
  assert.throws(() => base.select(() => ({ value: nested })).compile(), /same-level aggregate/);
  const hidden = caseWhen(
    aggregate.count().gt(0n),
    valueOf(1, types.integer()),
    valueOf(0, types.integer()),
  );
  assert.throws(
    () =>
      base
        .where(() => hidden.gt(0))
        .select(({ s }) => ({ units: s.units }))
        .compile(),
    /same-level aggregate/,
  );
  assert.throws(
    () =>
      base
        .select(({ s }) => ({ value: aggregate.sum(s.amount).filter(aggregate.count().gt(0n)) }))
        .compile(),
    /same-level aggregate/,
  );
  const window = call(
    {
      name: "test-window",
      phase: "window",
      render: { postgres: () => sqlFragment`ROW_NUMBER() OVER ()` },
    },
    types.bigint(),
    [],
  );
  assert.throws(
    () =>
      base
        .having(() => window.gt(0n))
        .select(() => ({ value: aggregate.count() }))
        .compile(),
    /same-level window/,
  );
});

test("numeric origins survive derived queries, CTEs and UNION without narrowing accumulators", () => {
  const db = createDatabase({ dialect: mysqlDialect });
  const base = db.from({ s: sales });
  assert.throws(() => aggregate.sum(base.scope.s.units.add(1)), /nativeCast/);
  const shifted = base.select(({ s }) => ({ value: s.units.add(1) }));
  for (const source of [shifted.as(), shifted.asCte("shifted")]) {
    const outer = db.from({ d: source });
    assert.throws(() => aggregate.sum(outer.scope.d.value), /nativeCast/);
  }
  const plain = base.select(({ s }) => ({ value: s.units }));
  assert.throws(
    () => aggregate.sum(db.from({ d: plain.unionAll(shifted) }).scope.d.value),
    /nativeCast/,
  );
  const daily = base
    .groupBy(({ s }) => ({ team: s.team }))
    .select(({ s }) => ({ total: aggregate.sum(s.amount) }))
    .asCte("daily");
  const outer = db
    .from({ d: daily })
    .select(({ d }) => ({ total: aggregate.sum(d.total) }))
    .compile();
  assert.equal((outer.sql.match(/AS DECIMAL\(38,4\)/g) ?? []).length, 1);
  assert.doesNotThrow(() =>
    aggregate.sum(nativeCast(base.scope.s.units.add(1), types.decimal(38, 0))),
  );
});

test("conditional expressions preserve compatible codecs and their precise NULL rules", () => {
  const type = types.text().map({
    identity: Symbol("label"),
    to: (value: { text: string }) => value.text,
    from: (text) => ({ text }),
  });
  const missing = valueOf(null, type.nullable());
  const present = valueOf({ text: "present" }, type);
  assert.equal(caseWhen(valueOf(false, types.boolean()), present, missing).type.decode(null), null);
  assert.throws(() => coalesce(missing, present).type.decode(null), /Unexpected SQL NULL/);
  assert.deepEqual(coalesce(missing, present).type.decode("present"), { text: "present" });
  assert.equal(nullIf(present, present).type.decode(null), null);
  assert.throws(
    () => coalesce(valueOf("x", types.text(20)), valueOf("y", types.text(30))),
    /matching SQL storage/,
  );
});

test("portable DECIMAL readers enforce their numeric domain without using number", () => {
  const widest = types.decimal(38, 0);
  assert.equal(widest.decode("9".repeat(38)), "9".repeat(38));
  assert.throws(() => widest.decode("1" + "0".repeat(38)), /precision/);
  assert.throws(() => types.decimal(38, 31), /scale/);
  assert.throws(() => widest.decode("NaN"), /plain decimal/);
  assert.throws(() => types.decimal(19, 4).decode("1.23456"), /scale/);
  assert.equal(types.decimal(19, 4).decode("1.230000"), "1.230000");
});

test("named grouping keys evaluate caller-owned getters only once", () => {
  let evaluated = 0;
  const db = createDatabase({ dialect: postgresDialect });
  const grouped = db.from({ s: sales }).groupBy(({ s }) => ({
    get team() {
      evaluated++;
      return s.team.upper();
    },
  }));
  assert.doesNotThrow(() =>
    grouped.select((_s, _e, g) => ({ team: g.team, count: aggregate.count() })).compile(),
  );
  assert.equal(evaluated, 1);
});
