import test from "node:test";
import assert from "node:assert/strict";
import {
  column,
  createDatabase,
  DataType,
  hanaDialect,
  mysqlDialect,
  pgArray,
  postgresDialect,
  postgresTypes,
  table,
  types,
  valueOf,
} from "../../src/index.js";

test("array codecs distinguish empty arrays, SQL NULL, NULL elements and quoted text", () => {
  const nullable = postgresTypes.array(types.text(100).nullable());
  const texts = ["NULL", "", "a,b", "{x}", 'a"b', "a\\b", "  spaced  ", "中文😀"];
  const wire = "{NULL," + texts.map((value) => JSON.stringify(value)).join(",") + "}";
  assert.deepEqual(nullable.decode(wire), [null, ...texts]);
  assert.deepEqual(nullable.decode("{}"), []);
  assert.ok(Object.isFrozen(nullable.decode("{}")));
  assert.throws(() => nullable.decode(null), /Unexpected SQL NULL/);
  assert.equal(nullable.nullable().decode(null), null);
  assert.throws(
    () => postgresTypes.array(types.text()).decode("{NULL}"),
    /array element at index 0/,
  );
  for (const value of [[undefined], new Array(1), [[1]]])
    assert.throws(
      () => postgresTypes.array(types.integer()).encode(value as never),
      /array element at index 0/,
    );
});

test("wire preflight rejects malformed input before invoking the trusted server parser", () => {
  const type = postgresTypes.array(types.text());
  for (const value of [
    '{"unterminated}',
    '{"tail\\}',
    '{"escaped\\"}',
    '{"a"x}',
    "{,}",
    "{a,}",
    "{a,,b}",
    "{{1,2}}",
    "[0:1]={1,2}",
    "{1",
    "not an array",
  ])
    assert.throws(() => type.decode(value));
  assert.deepEqual(type.decode('{"{x},y"}'), ["{x},y"]);
});

test("non-ASCII whitespace is array data rather than PostgreSQL delimiter whitespace", () => {
  assert.deepEqual(postgresTypes.array(types.text()).decode("{\u00a0,\u2003,\ufeff}"), [
    "\u00a0",
    "\u2003",
    "\ufeff",
  ]);
});

test("array elements retain precise codecs and input snapshots", () => {
  assert.deepEqual(
    postgresTypes.array(types.bigint()).decode("{9007199254740993,-9007199254740993}"),
    [9007199254740993n, -9007199254740993n],
  );
  assert.deepEqual(
    postgresTypes.array(types.decimal(19, 4)).decode("{123456789012345.1234,0.0001}"),
    ["123456789012345.1234", "0.0001"],
  );
  assert.deepEqual(
    postgresTypes.array(types.localDateTime()).decode('{"2024-03-10 02:30:00.123456"}'),
    ["2024-03-10 02:30:00.123456"],
  );
  const uuid = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA";
  const id = postgresTypes.uuid().map({
    identity: Symbol("id"),
    to: (value: { value: string }) => value.value,
    from: (value) => ({ value }),
  });
  const ids = postgresTypes.array(id);
  assert.deepEqual(ids.encode([{ value: uuid }]), [uuid.toLowerCase()]);
  assert.deepEqual(ids.decode("{" + uuid + "}"), [{ value: uuid.toLowerCase() }]);
  const values = [1, 2];
  const captured = valueOf(values, postgresTypes.array(types.integer()));
  values.push(3);
  const db = createDatabase({ dialect: postgresDialect });
  const q = db
    .from({ t: table("t", { id: column(types.integer()) }) })
    .select(() => ({ values: captured }));
  assert.deepEqual(q.compile().parameters, [[1, 2]]);
  assert.ok(Object.isFrozen(q.compile().parameters[0]));
});

test("array operations keep matching element contracts and explicit mount boundaries", () => {
  const arrays = postgresTypes.array(types.integer().nullable());
  const expression = valueOf([1, null], arrays).mount(pgArray);
  assert.throws(
    () => expression.contains(valueOf([1], postgresTypes.array(types.integer()))),
    /element codecs/,
  );
  const text10 = valueOf(["a"], postgresTypes.array(types.text(10))).mount(pgArray);
  assert.throws(
    () => text10.overlaps(valueOf(["a"], postgresTypes.array(types.text(20)))),
    /matching storage/,
  );
  const mapped = arrays.map({
    identity: Symbol("whole-array"),
    to: (value: readonly (number | null)[]) => value,
    from: (value) => value,
  });
  assert.throws(() => valueOf([1], mapped).mount(pgArray), /map element types/);
  const uuid = postgresTypes.uuid();
  const fake = new DataType<string, "pg:uuid">(
    { ...uuid.storage, native: { ...uuid.storage.native!, identity: Symbol("other") } },
    uuid.codec,
  );
  assert.throws(() => postgresTypes.array(fake), /built-in UUID storage/);
  const db = createDatabase({ dialect: postgresDialect });
  const source = db.from({ t: table("t", { id: column(types.integer()) }) });
  assert.deepEqual(source.select(() => ({ match: expression.anyEquals(7) })).compile().parameters, [
    [7],
    [1, null],
  ]);
  for (const dialect of [mysqlDialect, hanaDialect])
    assert.throws(
      () =>
        createDatabase({ dialect })
          .from({ t: table("t", { id: column(types.integer()) }) })
          .select(() => ({ size: expression.cardinality() }))
          .compile(),
      /does not support/,
    );
});
