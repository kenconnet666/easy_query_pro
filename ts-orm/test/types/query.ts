import {
  column,
  createDatabase,
  Expression,
  DataType,
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
// @ts-expect-error Page results also require an explicit typed projection.
base.page(1, 10);
declare const pageResult: Awaited<ReturnType<typeof selected.page>>;
const pageRows: { id: number; name: string; department: string | null }[] = pageResult.rows;
const pageTotal: bigint = pageResult.total;
// @ts-expect-error Only the declared common isolation levels are supported.
db.transaction(async () => 1, { isolationLevel: "snapshot" });
// @ts-expect-error Transaction options never take truthy strings.
db.transaction(async () => 1, { readOnly: "true" });
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

// @ts-expect-error A non-null value type cannot enable SQL NULL in the public constructor.
new DataType<string, "text">({ kind: "text", length: 20 }, types.text(20).codec, true);
const nullableCustom = new DataType<string | null, "text">(
  { kind: "text", length: 20 },
  types.text(20).codec,
  true,
);
const nullableCustomResult: string | null = nullableCustom.decode(null);
const symbolKey = Symbol("hidden");
// @ts-expect-error SQL projections cannot contain symbol keys that disappear during enumeration.
base.select(({ u }) => ({ id: u.id, [symbolKey]: u.name }));
// @ts-expect-error Column definitions must also have enumerable string keys.
table("bad_columns", { id: column(types.integer()), [symbolKey]: column(types.text()) });
// @ts-expect-error Sources use SQL aliases and cannot contain symbol keys.
db.from({ u: users, [symbolKey]: users });
// @ts-expect-error Extension namespaces must be preserved by runtime enumeration.
createDatabase({ dialect: postgresDialect, extensions: { [symbolKey]: () => ({}) } });
void nullableCustomResult;

const unionLeft = base.select(({ u }) => ({ id: u.id, name: u.name }));
const unionRight = base.select(({ u }) => ({ id: u.id.nullable(), name: u.name }));
const unionRows = db
  .from({ combined: unionLeft.unionAll(unionRight).asCte("combined_users") })
  .select(({ combined }) => ({ id: combined.id, name: combined.name }));
declare const unionRow: Awaited<ReturnType<typeof unionRows.execute>>[number];
const unionId: number | null = unionRow.id;
// @ts-expect-error The right branch can produce SQL NULL.
const nonNullUnionId: number = unionRow.id;
// @ts-expect-error Both branches must have the same selected keys.
unionLeft.union(base.select(({ u }) => ({ id: u.id })));
// @ts-expect-error Same keys with different value types are not compatible.
unionLeft.union(base.select(({ u }) => ({ id: u.name, name: u.name })));
// @ts-expect-error Unselected queries cannot become set-operation branches.
unionLeft.unionAll(base);
// @ts-expect-error Set results are new relations, not the left branch's old scope.
unionLeft.unionAll(unionRight).scope.u;
void unionId;
