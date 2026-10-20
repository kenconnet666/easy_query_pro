import {
  column,
  createDatabase,
  Expression,
  postgresDialect,
  table,
  types,
  type InsertOf,
  type RowOf,
} from "../../src/index.js";

const users = table("users", {
  id: column(types.integer()).generated(),
  name: column(types.text()),
  departmentId: column(types.integer()).nullable(),
});
const departments = table("departments", {
  id: column(types.integer()),
  name: column(types.text()),
});
const db = createDatabase({
  dialect: postgresDialect,
  extensions: {
    report: () => ({ twice: (value: Expression<number, "integer">) => value.add(value) }),
  },
});
const base = db.from({ u: users });
const joined = base.leftJoin({ d: departments }, ({ u, d }) => u.departmentId.eq(d.id));
const selected = joined.select(({ u, d }, extensions) => ({
  id: extensions.report.twice(u.id),
  name: u.name,
  department: d.name,
}));
type Actual = Awaited<ReturnType<typeof selected.execute>>[number];
declare const result: Actual;
const id: number = result.id;
const name: string = result.name;
const department: string | null = result.department;
// @ts-expect-error LEFT JOIN makes the right column nullable.
const wrongDepartment: string = result.department;
// @ts-expect-error Numeric expressions do not accept strings.
base.scope.u.id.add("bad");
// @ts-expect-error Text values cannot use numeric operators.
base.scope.u.name.add("bad");
// @ts-expect-error SQL NULL comparison must use isNull.
base.scope.u.departmentId.eq(null);
// @ts-expect-error A query without an explicit projection is not executable.
base.execute();
// @ts-expect-error Relation aliases must be unique.
base.leftJoin({ u: departments }, ({ u }) => u.id.eq(1));
// @ts-expect-error Missing extension namespaces are not globally augmented.
createDatabase({ dialect: postgresDialect }).extensions.report;

type Insert = InsertOf<typeof users.columns>;
const insertion: Insert = { name: "A" };
// @ts-expect-error A required column cannot be omitted.
const incomplete: Insert = {};
type Row = RowOf<typeof users.columns>;
const row: Row = { id: 1, name: "A", departmentId: null };
// @ts-expect-error Row values remain typed.
const invalidRow: Row = { id: "1", name: "A", departmentId: null };

const derived = db.from({ r: selected.as() }).select(({ r }, extensions) => {
  const exact: string | null = r.department.type.decode(null);
  return { value: extensions.report.twice(r.id), department: r.department };
});
declare const derivedRow: Awaited<ReturnType<typeof derived.execute>>[number];
const exactDerived: { value: number; department: string | null } = derivedRow;
const nullableLabel = types
  .text()
  .nullable()
  .map({
    identity: Symbol("label"),
    to: (value: { text: string }) => value.text,
    from: (value) => ({ text: value }),
  });
const nullableValue: { text: string } | null = nullableLabel.decode(null);
// @ts-expect-error Mapping must keep SQL nullability.
const notNullable: { text: string } = nullableLabel.decode(null);
class BusinessText extends Expression<string, "text"> {
  custom() {
    return this.upper();
  }
}
const special = base.select(({ u }) => ({
  text: u.name.mount((binding) => new BusinessText(binding)),
}));
const rebound = db.from({ d: special.as() });
// @ts-expect-error A derived column is a new base expression and must be explicitly remounted.
rebound.scope.d.text.custom();
void [id, name, department, insertion, row, exactDerived];
