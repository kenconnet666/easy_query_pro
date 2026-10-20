import type { AnyExpression, Expression } from "../expression/expression.js";
import type { Node, RelationIdentity } from "../expression/node.js";
import type { AnyDataType, SqlKind } from "../schema/data-type.js";
import type { Columns, Table } from "../schema/table.js";

export type Selection = Readonly<Record<string, AnyExpression>>;
export type ResultOf<S> = { [P in keyof S]: S[P] extends Expression<infer T, SqlKind> ? T : never };
export interface ProjectionSlot {
  readonly key: string;
  readonly sqlAlias: string;
  readonly node: Node;
  readonly type: AnyDataType;
}
export interface Order {
  readonly expression: Node;
  readonly direction: "ASC" | "DESC";
}
export interface SourceDefinition {
  readonly kind: "table" | "derived";
  readonly fields: Readonly<
    Record<string, { readonly columnName: string; readonly type: AnyDataType }>
  >;
}
export interface Relation extends RelationIdentity {
  readonly source: Table<Columns> | DerivedSource<Selection>;
  readonly join: "FROM" | "CROSS" | "INNER" | "LEFT";
  readonly on?: Node;
}
export interface SelectNode {
  readonly owner: symbol;
  readonly relations: readonly Relation[];
  readonly projection: readonly ProjectionSlot[];
  readonly where?: Node;
  readonly ordering: readonly Order[];
  readonly limit?: number;
  readonly offset: number;
  readonly distinct: boolean;
}

/** 派生关系冻结输出接口，外层读者直接来自投影槽；不使用共享 alias→decoder Map。 */
export class DerivedSource<S extends Selection> {
  declare readonly selection: S;
  readonly fields: SourceDefinition["fields"];
  constructor(readonly query: SelectNode) {
    const fields: Record<string, { columnName: string; type: AnyDataType }> = Object.create(
      null,
    ) as Record<string, { columnName: string; type: AnyDataType }>;
    for (const slot of query.projection)
      fields[slot.key] = Object.freeze({ columnName: slot.sqlAlias, type: slot.type });
    this.fields = Object.freeze(fields);
    Object.freeze(this);
  }
}
export type Source = Table<Columns> | DerivedSource<Selection>;
