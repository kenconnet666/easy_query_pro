import assert from "node:assert/strict";
import test from "node:test";
import {
  column,
  createDatabase,
  hanaDialect,
  jsonNull,
  postgresDialect,
  table,
  types,
  valueOf,
  type JsonNull,
} from "../../src/index.js";

test("JSON null does not collapse into a SQL NULL result", () => {
  const json = types.json((value) => value).nullable();
  assert.notEqual(json.decode("null"), json.decode(null));
});

test("JSON parameters reject values that stringify silently drops or changes", () => {
  const json = types.json((value) => value);
  for (const value of [
    { omitted: undefined },
    { fn: () => 1 },
    [undefined],
    Array(1),
    NaN,
    Infinity,
    new Date(0),
    new Map([["x", 1]]),
  ])
    assert.throws(() => json.encode(value), TypeError);
});

test("JSON decoding does not accept a driver-parsed object with already lost precision", () => {
  assert.throws(() => types.json((value) => value).decode({ value: 9007199254740992 }), /text/);
});

test("top-level JSON null has a reversible distinct value and nested null stays ordinary", () => {
  const json = types.json((value) => value);
  assert.equal(json.decode("null"), jsonNull);
  assert.equal(json.encode(jsonNull), "null");
  // @ts-expect-error Unknown validators also exclude SQL NULL from a non-null JSON document.
  assert.throws(() => json.encode(null), /does not allow NULL/);
  const optional = json.nullable();
  assert.equal(optional.encode(null), null);
  assert.equal(optional.decode(null), null);
  assert.equal(optional.encode(jsonNull), "null");
  assert.deepEqual(json.decode('{"values":[null,"null"]}'), { values: [null, "null"] });
  assert.equal(json.encode({ values: [null, "null"] }), '{"values":[null,"null"]}');
  assert.throws(() => json.encode({ nested: jsonNull }), /Unsupported JSON/);
});

test("JSON validation runs in the JSON value domain and mapping preserves SQL NULL", () => {
  const json = types.json((value) => {
    if (value !== null && typeof value !== "string")
      throw new TypeError("Expected a JSON string or null");
    return value;
  });
  const mapped = json
    .map<{ value: string | JsonNull }>({
      identity: Symbol("wrapped-json"),
      to: (x) => x.value,
      from: (value) => ({ value }),
    })
    .nullable();
  assert.deepEqual(mapped.decode("null"), { value: jsonNull });
  assert.equal(mapped.encode({ value: jsonNull }), "null");
  assert.equal(mapped.encode({ value: "hello" }), '"hello"');
  assert.equal(mapped.decode(null), null);
  assert.throws(() => json.decode("{}"), /Expected a JSON string/);
});

test("JSON snapshots reject accessors, additional array keys and cycles without executing hooks", () => {
  const json = types.json((value) => value);
  let calls = 0;
  const getter = {
    get value() {
      calls++;
      return 1;
    },
  };
  const cyclic: { self?: unknown } = {};
  cyclic.self = cyclic;
  const named = Object.assign([1], { extra: 2 });
  const symbol = { [Symbol("secret")]: 1 };
  for (const value of [
    getter,
    cyclic,
    named,
    symbol,
    {
      toJSON() {
        calls++;
        return {};
      },
    },
  ])
    assert.throws(() => json.encode(value), TypeError);
  assert.equal(calls, 0);
  const shared = { value: 1 };
  assert.equal(json.encode([shared, shared]), '[{"value":1},{"value":1}]');
  const proto = JSON.parse('{"__proto__":{"polluted":true}}') as Record<string, unknown>;
  assert.equal(json.encode(proto), '{"__proto__":{"polluted":true}}');
  assert.equal(Object.hasOwn(Object.prototype, "polluted"), false);
});

test("JSON numbers outside the supported JS domain fail before business validation", () => {
  let calls = 0;
  const json = types.json((value) => {
    calls++;
    return value;
  });
  for (const source of ['{"n":9007199254740993}', "1e400", "-9007199254740993"])
    assert.throws(() => json.decode(source), /JSON number/);
  assert.equal(calls, 0);
  assert.throws(() => json.encode({ value: 9007199254740992 }), /safe integer/);
  assert.deepEqual(json.decode('{"n":9007199254740991,"fraction":0.125}'), {
    n: 9007199254740991,
    fraction: 0.125,
  });
});

test("raw JSON text preserves numeric lexemes and keeps JSON string/null/SQL NULL distinct", () => {
  const text = types.jsonText().nullable();
  for (const source of [
    ' {"n":9007199254740993,"decimal":0.1234567890123456789,"large":1e400} ',
    "null",
    '"null"',
  ]) {
    assert.equal(text.encode(source), source);
    assert.equal(text.decode(source), source);
  }
  assert.equal(text.decode(null), null);
  assert.equal(text.encode(null), null);
  for (const source of ["", "{", "undefined", "NaN", "[1,]", '{"x":1} trailing'])
    assert.throws(() => text.encode(source), /Invalid JSON/);
  assert.throws(() => text.decode({ n: 1 }), /raw driver text/);
});

test("JSON documents and raw text use different result codecs on the same SQL storage", () => {
  const raw = types.jsonText();
  const parsed = types.json((value) => value);
  assert.equal(raw.hasSameStorage(parsed), true);
  assert.equal(raw.hasSameCodec(parsed), false);
  const entries = table("entries", {
    raw: column(raw).nullable(),
    parsed: column(parsed).nullable(),
  });
  const base = createDatabase({ dialect: postgresDialect }).from({ e: entries });
  const rawQuery = base.select(({ e }) => ({ value: e.raw }));
  const documentQuery = base.select(({ e }) => ({ value: e.parsed }));
  assert.throws(() => {
    // @ts-expect-error Deliberately bypass incompatible result types to verify the runtime codec boundary.
    rawQuery.unionAll(documentQuery);
  }, /codec/);
  assert.deepEqual(
    base
      .select(() => ({ a: valueOf(jsonNull, parsed), b: valueOf(null, raw.nullable()) }))
      .compile().parameters,
    ["null", null],
  );
  const hana = createDatabase({ dialect: hanaDialect }).from({ e: entries });
  assert.match(hana.select(({ e }) => ({ missing: e.raw.isNull() })).compile().sql, /IS NULL/);
});
