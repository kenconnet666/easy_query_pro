import type { AnyExpression, Expression } from "../expression/expression.js";
import type { Node, RelationIdentity, NumericOrigin } from "../expression/node.js";
import type { AnyDataType, SqlKind } from "../schema/data-type.js";
import type { Columns, Table } from "../schema/table.js";
import { validateName } from "../schema/table.js";

export type Selection = Readonly<Record<string, AnyExpression>>;
export type ResultOf<S> = { [P in keyof S]: S[P] extends Expression<infer T, SqlKind> ? T : never };
export interface ProjectionSlot {
  readonly key: string;
  readonly sqlAlias: string;
  readonly node: Node;
  readonly type: AnyDataType;
  readonly numericOrigin: NumericOrigin;
}
export interface Order {
  readonly expression: Node;
  readonly direction: "ASC" | "DESC";
  readonly nulls?: "FIRST" | "LAST";
}

/** 普通排序和窗口排序都持有自己的快照，外部 Order 对象不能反向修改查询。 */
export function snapshotOrders(orders: readonly Order[]): readonly Order[] {
  return Object.freeze(
    orders.map((order) => {
      if (order.direction !== "ASC" && order.direction !== "DESC")
        throw new TypeError("Order direction must be ASC or DESC");
      if (order.nulls !== undefined && order.nulls !== "FIRST" && order.nulls !== "LAST")
        throw new TypeError("NULL placement must be FIRST or LAST");
      return Object.freeze({ ...order });
    }),
  );
}
export interface SourceDefinition {
  readonly fields: Readonly<
    Record<
      string,
      {
        readonly columnName: string;
        readonly type: AnyDataType;
        readonly numericOrigin: NumericOrigin;
      }
    >
  >;
}
export interface Relation extends RelationIdentity {
  readonly source: Table<Columns> | DerivedSource<Selection>;
  readonly join: "FROM" | "CROSS" | "INNER" | "LEFT";
  readonly on?: Node;
}
export interface SelectNode {
  readonly kind: "select";
  readonly owner: symbol;
  readonly relations: readonly Relation[];
  readonly projection: readonly ProjectionSlot[];
  readonly where?: Node;
  readonly grouping: readonly Node[];
  readonly groupingMode: "plain" | "rollup";
  readonly having?: Node;
  readonly ordering: readonly Order[];
  readonly limit?: number;
  readonly offset: number;
  readonly distinct: boolean;
}

export interface SetNode {
  readonly kind: "set";
  readonly owner: symbol;
  readonly left: QueryNode;
  readonly right: QueryNode;
  readonly all: boolean;
  readonly projection: readonly ProjectionSlot[];
}
export type QueryNode = SelectNode | SetNode;

/** 派生关系冻结输出接口，外层读者直接来自投影槽；不使用共享 alias→decoder Map。 */
export class DerivedSource<S extends Selection> {
  declare readonly selection: S;
  readonly fields: SourceDefinition["fields"];
  constructor(
    readonly query: QueryNode,
    readonly cteName?: string,
  ) {
    if (cteName !== undefined) validateName(cteName);
    const fields: Record<
      string,
      { columnName: string; type: AnyDataType; numericOrigin: NumericOrigin }
    > = Object.create(null) as Record<
      string,
      { columnName: string; type: AnyDataType; numericOrigin: NumericOrigin }
    >;
    for (const slot of query.projection)
      fields[slot.key] = Object.freeze({
        columnName: slot.sqlAlias,
        type: slot.type,
        numericOrigin: slot.numericOrigin,
      });
    this.fields = Object.freeze(fields);
    Object.freeze(this);
  }

  /** CTE 定义可被多次引用；具体 FROM/JOIN 别名与表达式身份仍在每次使用时独立绑定。 */
  asCte(name: string): DerivedSource<S> {
    return new DerivedSource(this.query, name);
  }
}
export type Source = Table<Columns> | DerivedSource<Selection>;
