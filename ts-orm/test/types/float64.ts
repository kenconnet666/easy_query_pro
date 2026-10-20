import {
  aggregate,
  column,
  createDatabase,
  postgresDialect,
  postgresTypes,
  table,
  types,
  type Expression,
} from "../../src/index.js";
const measurements = table("measurements", {
  id: column(types.integer()),
  value: column(types.float64()).nullable(),
  text: column(types.text()),
});
const db = createDatabase({ dialect: postgresDialect });
const base = db.from({ m: measurements });
const mean: Expression<number | null, "float64"> = aggregate.avgApprox(base.scope.m.value);
const minimum: Expression<number | null, "float64"> = aggregate.min(base.scope.m.value);
// @ts-expect-error AVG does not accept textual SQL storage.
aggregate.avgApprox(base.scope.m.text);
// @ts-expect-error Approximate values do not enter precise decimal SUM implicitly.
aggregate.sum(base.scope.m.value);
// @ts-expect-error A float value does not have text operations.
base.scope.m.value.upper();
// @ts-expect-error Equal JS carriers do not erase integer versus float storage.
base.scope.m.id.eq(base.scope.m.value);
// @ts-expect-error Floating array output requires a separate PG formatting contract.
postgresTypes.array(types.float64());
types.float64().encode(1.5);
// @ts-expect-error The parameter domain is number rather than numeric text.
types.float64().encode("1.5");
void [mean, minimum];
