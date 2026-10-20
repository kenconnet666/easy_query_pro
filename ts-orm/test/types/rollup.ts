import {
  aggregate,
  column,
  createDatabase,
  grouping,
  postgresDialect,
  table,
  types,
} from "../../src/index.js";
const sales = table("sales", {
  country: column(types.text()),
  city: column(types.text()),
  amount: column(types.decimal(19, 4)),
});
const base = createDatabase({ dialect: postgresDialect }).from({ s: sales });
const rolled = base
  .select(({ s }) => ({ country: s.country }))
  .rollupBy(({ s }) => ({ country: s.country, city: s.city }));
// @ts-expect-error Old selections do not retain their non-null result type through ROLLUP.
rolled.execute();
const report = rolled.select(({ s }, _, keys) => ({
  country: keys.country,
  city: s.city,
  flag: grouping(keys.country),
  count: aggregate.count(),
}));
declare const row: Awaited<ReturnType<typeof report.execute>>[number];
const country: string | null = row.country;
const city: string | null = row.city;
const flag: number = row.flag;
// @ts-expect-error A subtotal can replace even a NOT NULL source key with NULL.
const nonNullCountry: string = row.country;
// @ts-expect-error The entire new query scope must reflect possible subtotal NULL values.
const nonNullCity: string = row.city;
void [country, city, flag];
