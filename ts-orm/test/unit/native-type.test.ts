import test from "node:test";
import assert from "node:assert/strict";
import {
  aggregate,
  call,
  caseWhen,
  coalesce,
  column,
  createDatabase,
  DataType,
  Expression,
  hanaDialect,
  mysqlDialect,
  nativeCast,
  nullIf,
  postgresDialect,
  postgresTypes,
  sql,
  table,
  types,
  valueOf,
  windowFunctions,
  windowSpec,
  type NativeStorage,
} from "../../src/index.js";

const uuid = "A0EEBC99-9C0B-4EF8-BB6D-6BB9BD380A11";
const records = table("records", {
  id: column(postgresTypes.uuid()),
  parent: column(postgresTypes.uuid()).nullable(),
});

test("UUID storage is reusable, distinct from text, and validated even in column-only queries", () => {
  const type = postgresTypes.uuid();
  assert.ok(type.hasSameStorage(postgresTypes.uuid()));
  assert.equal(type.encode(uuid), uuid.toLowerCase());
  assert.equal(type.decode(uuid.toLowerCase()), uuid.toLowerCase());
  assert.throws(() => type.encode("invalid uuid"), /UUID/);
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ r: records });
  assert.throws(() => base.scope.r.id.eq(valueOf(uuid, types.text()) as never), /compatible/);
  assert.match(
    base
      .where(({ r }) => r.id.eq(uuid))
      .select(({ r }) => ({ id: r.id }))
      .compile().sql,
    /CAST\(\$1 AS UUID\)/,
  );
  for (const dialect of [mysqlDialect, hanaDialect]) {
    const foreign = createDatabase({ dialect }).from({ r: records });
    assert.throws(
      () => foreign.select(({ r }) => ({ id: r.id })).compile(),
      /pg:uuid does not support/,
    );
    assert.throws(
      () =>
        foreign
          .where(({ r }) => r.id.isNull())
          .select(() => ({ x: valueOf(1, types.integer()) }))
          .compile(),
      /pg:uuid does not support/,
    );
  }
});

test("native storage and business codecs survive outer joins, conditions, CTE and UNION", () => {
  const business = postgresTypes.uuid().map({
    identity: Symbol("document-id"),
    to: (value: { id: string }) => value.id,
    from: (id) => ({ id }),
  });
  const docs = table("docs", { id: column(business), parent: column(business).nullable() });
  const db = createDatabase({ dialect: postgresDialect });
  const first = db
    .from({ d: docs })
    .leftJoin({ p: docs }, ({ d, p }) => d.parent.eq(p.id))
    .select(({ p }) => ({ id: p.id }));
  const constants = db.from({ d: docs }).select(() => ({ id: valueOf({ id: uuid }, business) }));
  const selected = db
    .from({ u: first.unionAll(constants).asCte("all_ids") })
    .select(({ u }) => ({ id: coalesce(u.id, valueOf({ id: uuid }, business)) }));
  assert.deepEqual(selected.compile().decode({ _eq_0: uuid }), { id: { id: uuid.toLowerCase() } });
  assert.match(selected.compile().sql, /AS UUID/);
  assert.deepEqual(first.compile().decode({ _eq_0: null }), { id: null });
});

test("native definitions snapshot rendering and require real storage and codec compatibility", () => {
  const before: [string, string] = ["CAST(", " AS UUID)"];
  const config = { parameter: before, cast: before, equality: true, ordering: true };
  const definition = { identity: Symbol("application-native"), dialects: { postgres: config } };
  const native = new DataType<string, "app:id">(
    { kind: "app:id", native: definition },
    types.text().codec,
  );
  before[1] = " AS TEXT)";
  config.equality = false;
  const incompatible = new DataType<string, "app:id">(
    { kind: "app:id", native: definition },
    types.text().codec,
  );
  assert.equal(native.storage.native?.dialects.postgres?.parameter[1], " AS UUID)");
  assert.ok(!native.hasSameStorage(incompatible));
  assert.throws(() => valueOf(uuid, native).eq(valueOf(uuid, incompatible)), /compatible/);
  assert.throws(
    () =>
      caseWhen(valueOf(true, types.boolean()), valueOf(uuid, native), valueOf(uuid, incompatible)),
    /matching SQL storage/,
  );
  assert.throws(() => new DataType({ kind: "app:id" }, types.text().codec), /storage definition/);
  assert.throws(
    () => new DataType({ kind: "text", length: 20, native: definition }, types.text().codec),
    /namespaced/,
  );
  const a = types
    .text()
    .map({ identity: Symbol("a"), to: (text: string) => text, from: (text) => text });
  const b = types
    .text()
    .map({ identity: Symbol("b"), to: (text: string) => text, from: (text) => text });
  assert.throws(() => valueOf("a", a).eq(valueOf("b", b)), /codec/);
  assert.doesNotThrow(() => valueOf("short", types.text(20)).eq(valueOf("long", types.text(255))));
  assert.doesNotThrow(() =>
    valueOf("1", types.decimal(38, 4)).eq(valueOf("1", types.decimal(19, 4))),
  );
});

test("readable native types do not silently promise comparison, grouping or ordering", () => {
  const storage: NativeStorage = {
    identity: Symbol("opaque"),
    dialects: { postgres: { parameter: ["CAST(", " AS XML)"] } },
  };
  const opaque = new DataType<string, "app:xml">(
    { kind: "app:xml", native: storage },
    types.text().codec,
  );
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ o: table("opaque", { value: column(opaque) }) });
  const selected = base.select(({ o }) => ({ value: o.value }));
  const custom = new Expression({ node: sql`'<a/>'`, type: opaque });
  const disguised = call(
    { name: "disguised-native", phase: "row", render: { postgres: () => custom.node } },
    types.text(),
    [],
  );
  assert.throws(
    () =>
      base
        .where(() => disguised.eq("<a/>"))
        .select(({ o }) => ({ value: o.value }))
        .compile(),
    /cannot change SQL storage/,
  );
  assert.throws(
    () => new Expression({ node: custom.node, type: types.text() }),
    /cannot change SQL storage/,
  );
  assert.throws(
    () =>
      new Expression({
        node: { kind: "parameter", type: opaque, value: "<a/>" },
        type: types.text(),
      }),
    /cannot change SQL storage/,
  );
  assert.throws(
    () =>
      new Expression({
        node: { kind: "coalesce", left: custom.node, right: custom.node },
        type: types.text(),
      }),
    /cannot change SQL storage/,
  );
  const expansion = call(
    { name: "native-expansion", phase: "row", render: { mysql: () => custom.node } },
    types.text(),
    [],
  );
  assert.throws(
    () =>
      createDatabase({ dialect: mysqlDialect })
        .from({ r: table("r", { id: column(types.integer()) }) })
        .select(() => ({ value: expansion }))
        .compile(),
    /app:xml does not support/,
  );
  assert.throws(
    () =>
      base
        .where(() => custom.eq("<a/>"))
        .select(({ o }) => ({ value: o.value }))
        .compile(),
    /equality/,
  );
  assert.throws(
    () =>
      createDatabase({ dialect: mysqlDialect })
        .insert(table("opaque", { value: column(opaque) }))
        .values({ value: custom })
        .compile(),
    /app:xml does not support/,
  );
  assert.doesNotThrow(() => selected.compile());
  assert.throws(() => selected.distinct().compile(), /equality/);
  assert.throws(
    () =>
      base
        .where(({ o }) => o.value.eq("<a/>"))
        .select(({ o }) => ({ value: o.value }))
        .compile(),
    /equality/,
  );
  assert.throws(
    () =>
      base
        .orderBy(({ o }) => o.value.asc())
        .select(({ o }) => ({ value: o.value }))
        .compile(),
    /ordering/,
  );
  assert.throws(
    () =>
      base
        .groupBy(({ o }) => ({ value: o.value }))
        .select((_s, _e, g) => g)
        .compile(),
    /equality/,
  );
  assert.throws(
    () =>
      db
        .from({ u: selected.union(selected) })
        .select(({ u }) => ({ value: u.value }))
        .compile(),
    /equality/,
  );
  assert.throws(
    () => base.select(({ o }) => ({ value: aggregate.min(o.value as never) })).compile(),
    /unsupported SQL type/,
  );
  assert.throws(
    () =>
      base
        .select(({ o }) => ({
          value: windowFunctions.rowNumber().over(windowSpec().partitionBy(o.value)),
        }))
        .compile(),
    /equality/,
  );
  assert.throws(
    () => base.select(({ o }) => ({ value: nativeCast(o.value, opaque) })).compile(),
    /CAST/,
  );
  assert.throws(
    () => base.select(({ o }) => ({ value: nullIf(o.value, valueOf("<a/>", opaque)) })).compile(),
    /equality/,
  );
  assert.doesNotThrow(() =>
    base
      .select(({ o }) => ({ value: coalesce(o.value.nullable(), valueOf("<a/>", opaque)) }))
      .compile(),
  );
  assert.doesNotThrow(() =>
    base
      .select(({ o }) => ({ value: caseWhen(o.value.isNull(), valueOf("<a/>", opaque), o.value) }))
      .compile(),
  );
  assert.doesNotThrow(() =>
    base
      .select(({ o }) => ({ value: o.value.isNull() }))
      .distinct()
      .compile(),
  );
  const converted = nativeCast(base.scope.o.value, types.text());
  assert.doesNotThrow(() =>
    base
      .orderBy(() => converted.asc())
      .select(() => ({ value: nullIf(converted, valueOf("<a/>", types.text())) }))
      .compile(),
  );
});

test("native CAST conservatively preserves a database-generated NULL for non-null input", () => {
  const db = createDatabase({ dialect: mysqlDialect });
  const cast = nativeCast(valueOf("not-a-number", types.text()), types.integer());
  assert.equal(cast.type.acceptsNull, true);
  assert.equal(cast.type.decode(null), null);
  assert.match(
    db
      .from({ r: table("r", { id: column(types.integer()) }) })
      .select(() => ({ cast }))
      .compile().sql,
    /CAST/,
  );
});
