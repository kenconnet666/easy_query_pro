import type { AnyDataType, DataType, SqlKind } from "./data-type.js";
import { recordEntries, type NoSymbolKeys } from "../internal/record.js";

export class Column<T, K extends SqlKind = SqlKind, Optional extends boolean = false> {
  declare readonly optionalInsert: Optional;
  readonly options: Readonly<{
    name?: string;
    primaryKey?: boolean;
    generated?: boolean;
    hasDefault?: boolean;
  }>;
  constructor(
    readonly type: DataType<T, K>,
    options: Readonly<{
      name?: string;
      primaryKey?: boolean;
      generated?: boolean;
      hasDefault?: boolean;
    }> = {},
  ) {
    this.options = Object.freeze({ ...options });
    if (new.target === Column) Object.freeze(this);
  }
  nullable(): Column<T | null, K, true> {
    return new Column(this.type.nullable(), this.options);
  }
  named(name: string): Column<T, K, Optional> {
    validateName(name);
    return new Column(this.type, { ...this.options, name });
  }
  primaryKey(): Column<T, K, Optional> {
    return new Column(this.type, { ...this.options, primaryKey: true });
  }
  generated(): Column<T, K, true> {
    return new Column(this.type, { ...this.options, generated: true });
  }
  /** 标记数据库已有默认值，省略写入时交给数据库处理；不偷偷增加应用端默认值。 */
  defaulted(): Column<T, K, true> {
    return new Column(this.type, { ...this.options, hasDefault: true });
  }
}
export function column<T, K extends SqlKind>(type: DataType<T, K>): Column<T, K> {
  return new Column(type);
}
export type AnyColumn = Column<unknown, SqlKind, boolean>;
export type Columns = Readonly<Record<string, AnyColumn>>;
export type RowOf<C extends Columns> = {
  [P in keyof C]: C[P] extends Column<infer T, SqlKind, boolean> ? T : never;
};
type OptionalKeys<C extends Columns> = {
  [P in keyof C]: C[P] extends Column<unknown, SqlKind, true> ? P : never;
}[keyof C];
export type InsertOf<C extends Columns> = Omit<RowOf<C>, OptionalKeys<C>> &
  Partial<Pick<RowOf<C>, OptionalKeys<C>>>;
export type UpdateOf<C extends Columns> = Partial<RowOf<C>>;
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
