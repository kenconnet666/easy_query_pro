import test from "node:test";
import assert from "node:assert/strict";
import {
  aggregate,
  column,
  createDatabase,
  hanaDialect,
  table,
  types,
  valueOf,
} from "../../src/index.js";

test("portable dates validate Gregorian fields without timezone normalization", () => {
  for (const value of ["1000-01-01", "2000-02-29", "2024-02-29", "9999-12-31"])
    assert.equal(types.date().encode(value), value);
  for (const value of [
    "0000-00-00",
    "0999-12-31",
    "10000-01-01",
    "1900-02-29",
    "2023-02-29",
    "2024-04-31",
    "2024-13-01",
    "01/02/2024",
    "2024-01-01Z",
  ])
    assert.throws(() => types.date().encode(value));
  assert.throws(() => types.date().encode(new Date() as never), /string/);
});

test("local date-time preserves nonexistent DST fields and microseconds", () => {
  const type = types.localDateTime();
  assert.equal(type.encode("2024-03-10T02:30:00.123456"), "2024-03-10 02:30:00.123456");
  assert.equal(type.encode("2024-11-03 01:30:00"), "2024-11-03 01:30:00.000000");
  assert.equal(type.decode("2024-03-10 02:30:00.1234560"), "2024-03-10 02:30:00.123456");
  assert.equal(type.decode("2024-03-10T02:30:00.123456000"), "2024-03-10 02:30:00.123456");
  assert.equal(type.decode("2024-03-10 02:30:00.1"), "2024-03-10 02:30:00.100000");
  for (const value of [
    "2024-03-10 02:30:00.1234560",
    "2024-03-10 24:00:00",
    "2024-01-01 00:60:00",
    "2024-01-01 00:00:60",
    "2024-03-10T02:30:00Z",
    "2024-03-10 02:30:00+08:00",
  ])
    assert.throws(() => type.encode(value));
  assert.throws(() => type.decode("2024-03-10 02:30:00.1234567"), /discard precision/);
  assert.throws(() => type.decode("2024-03-10 02:30:00.123456001"), /discard precision/);
});

test("HANA binds temporal fields with explicit formats while aggregates retain temporal readers", () => {
  const db = createDatabase({ dialect: hanaDialect });
  const rows = table("events", {
    day: column(types.date()),
    occurred: column(types.localDateTime()),
  });
  const query = db
    .from({ e: rows })
    .where(({ e }) => e.day.eq("2024-03-10"))
    .select(({ e }) => ({
      first: aggregate.min(e.occurred),
      last: aggregate.max(e.day),
    }))
    .compile();
  assert.match(query.sql, /TO_DATE\(\?, 'YYYY-MM-DD'\)/);
  assert.deepEqual(query.decode({ _eq_0: null, _eq_1: null }), { first: null, last: null });
  assert.equal(
    valueOf("2024-03-10 02:30:00.123456", types.localDateTime()).type.storage.kind,
    "localDateTime",
  );
});
