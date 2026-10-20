import type { AnyDataType, DataType, SqlKind } from "./data-type.js";
import { recordEntries, type NoSymbolKeys } from "../internal/record.js";

export interface ColumnOptions {
  readonly name?: string | undefined;
  readonly primaryKey?: boolean | undefined;
  readonly generated?: boolean | undefined;
  readonly hasDefault?: boolean | undefined;
}
type WithOption<O, K extends keyof ColumnOptions> = Omit<O, K> & Record<K, true>;
type EmptyColumnOptions = { [K in keyof ColumnOptions]?: never };

/** 选项类型直接来自同一份运行时 metadata，可省略与禁止写入不再共用一个布尔泛型。 */
export class Column<
  T,
  K extends SqlKind = SqlKind,
  const O extends ColumnOptions = EmptyColumnOptions,
> {
  readonly options: O;
  constructor(
    readonly type: DataType<T, K>,
    ...options: [options: O] | ({} extends O ? [] : never)
  ) {
    this.options = Object.freeze({ ...options[0] }) as O;
    if (new.target === Column) Object.freeze(this);
  }
  nullable(): Column<T | null, K, O> {
    return new Column(this.type.nullable(), this.options);
  }
  named(name: string): Column<T, K, Omit<O, "name"> & { name: string }> {
    validateName(name);
    return new Column(this.type, { ...this.options, name });
  }
  primaryKey(): Column<T, K, WithOption<O, "primaryKey">> {
    return new Column(this.type, { ...this.options, primaryKey: true });
  }
  /** 数据库生成且只读；允许调用方覆写的 identity/default 应声明 defaulted。 */
  generated(): Column<T, K, WithOption<O, "generated">> {
    return new Column(this.type, { ...this.options, generated: true });
  }
  /** 标记数据库已有默认值，省略写入时交给数据库处理；不偷偷增加应用端默认值。 */
  defaulted(): Column<T, K, WithOption<O, "hasDefault">> {
    return new Column(this.type, { ...this.options, hasDefault: true });
  }
}
export function column<T, K extends SqlKind>(type: DataType<T, K>): Column<T, K> {
  return new Column(type);
}
export type AnyColumn = Column<unknown, SqlKind, ColumnOptions>;
export type Columns = Readonly<Record<string, AnyColumn>>;
export type RowOf<C extends Columns> = {
  [P in keyof C]: C[P] extends Column<infer T, SqlKind, ColumnOptions> ? T : never;
};
export type WritableKeys<C extends Columns> = {
  [P in keyof C]: "generated" extends keyof C[P]["options"]
    ? true extends C[P]["options"]["generated"]
      ? never
      : P
    : P;
}[keyof C];
type OptionalKeys<C extends Columns> = {
  [P in keyof C]: null extends RowOf<C>[P]
    ? P
    : C[P]["options"] extends { hasDefault: true }
      ? P
      : never;
}[keyof C];
type ReadOnlyInputs<C extends Columns> = { [P in Exclude<keyof C, WritableKeys<C>>]?: never };
export type InsertOf<C extends Columns> = Pick<
  RowOf<C>,
  Exclude<WritableKeys<C>, OptionalKeys<C>>
> & {
  [P in Extract<WritableKeys<C>, OptionalKeys<C>>]?: RowOf<C>[P] | undefined;
} & ReadOnlyInputs<C>;
export type UpdateOf<C extends Columns> = {
  [P in WritableKeys<C>]?: RowOf<C>[P] | undefined;
} & ReadOnlyInputs<C>;
export interface Field {
  readonly columnName: string;
  readonly type: AnyDataType;
}
export class Table<C extends Columns = Columns> {
  readonly fields: Readonly<Record<string, Field>>;
  readonly columns: C;
  constructor(
    readonly name: string,
    columns: C & NoSymbolKeys<C>,
    readonly schema?: string,
  ) {
    validateName(name);
    if (schema !== undefined) validateName(schema);
    const fields: Record<string, Field> = Object.create(null) as Record<string, Field>;
    const entries = recordEntries(columns, "Table columns");
    for (const [key, value] of entries) {
      validateName(key);
      fields[key] = Object.freeze({ columnName: value.options.name ?? key, type: value.type });
    }
    if (!Object.keys(fields).length) throw new TypeError("A table requires columns");
    this.fields = Object.freeze(fields);
    this.columns = Object.freeze(Object.fromEntries(entries)) as C;
    if (new.target === Table) Object.freeze(this);
  }
}
export function table<const C extends Columns>(
  name: string,
  columns: C & NoSymbolKeys<C>,
  options?: { schema?: string },
): Table<C> {
  return new Table(name, columns, options?.schema);
}
export function validateName(name: string): void {
  if (!name.length || name.includes("\0"))
    throw new TypeError("SQL identifiers must be nonempty and cannot contain NUL");
}
