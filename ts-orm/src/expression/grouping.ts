import { Expression, type AnyExpression } from "./expression.js";
import { freezeNode } from "./node.js";
import { types } from "../schema/data-type.js";

/** 0 表示实际分组值（也可能是真实 NULL），1 表示此键在当前小计层被汇总。 */
export function grouping(key: AnyExpression): Expression<number, "integer"> {
  if (!(key instanceof Expression)) throw new TypeError("GROUPING requires a typed grouping key");
  return new Expression({
    node: freezeNode({ kind: "grouping", operand: key.node }),
    type: types.integer(),
  });
}
