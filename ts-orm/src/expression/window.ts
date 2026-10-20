import { Expression, valueOf, type AnyExpression } from "./expression.js";
import { freezeNode, type AggregateNode, type Node } from "./node.js";
import { snapshotOrders, type Order } from "../query/model.js";
import { types, type DataType, type SqlKind } from "../schema/data-type.js";

type CurrentRow = Readonly<{ kind: "current" }>;
type OffsetBoundary = Readonly<{
  kind: "offset";
  direction: "preceding" | "following";
  count: number;
}>;
type Unbounded<D extends "preceding" | "following"> = Readonly<{ kind: "unbounded"; direction: D }>;
export type FrameStart = CurrentRow | OffsetBoundary | Unbounded<"preceding">;
export type FrameEnd = CurrentRow | OffsetBoundary | Unbounded<"following">;
export type RowBoundary = FrameStart | FrameEnd;
export interface RowsFrame {
  readonly start: FrameStart;
  readonly end: FrameEnd;
}
export interface WindowDefinition {
  readonly partitions: readonly Node[];
  readonly ordering: readonly Order[];
  readonly frame?: RowsFrame;
}
export interface WindowFunctionNode {
  readonly kind: "window-function";
  readonly name:
    | "ROW_NUMBER"
    | "RANK"
    | "DENSE_RANK"
    | "LAG"
    | "LEAD"
    | "FIRST_VALUE"
    | "LAST_VALUE"
    | "NTH_VALUE";
  readonly operand?: Node;
  readonly offset?: number;
  readonly fallback?: Node;
}

/** 此处是项目共同范围，覆盖 PG 的 int4 参数；不把它描述为所有数据库的引擎上限。 */
function offset(value: number, minimum = 0): number {
  if (!Number.isInteger(value) || value < minimum || value > 2_147_483_647)
    throw new RangeError(`Window offset must be an integer in ${minimum}..2147483647`);
  return value;
}
const current: CurrentRow = Object.freeze({ kind: "current" });
export const frame = Object.freeze({
  currentRow: current,
  unboundedPreceding: Object.freeze({
    kind: "unbounded" as const,
    direction: "preceding" as const,
  }),
  unboundedFollowing: Object.freeze({
    kind: "unbounded" as const,
    direction: "following" as const,
  }),
  preceding(count: number): CurrentRow | OffsetBoundary {
    return offset(count) === 0
      ? current
      : Object.freeze({ kind: "offset", direction: "preceding", count });
  },
  following(count: number): CurrentRow | OffsetBoundary {
    return offset(count) === 0
      ? current
      : Object.freeze({ kind: "offset", direction: "following", count });
  },
});

function boundaryPosition(boundary: RowBoundary): number {
  if (boundary.kind === "current") return 0;
  if (boundary.direction !== "preceding" && boundary.direction !== "following")
    throw new TypeError("Invalid ROWS direction");
  if (boundary.kind === "unbounded")
    return boundary.direction === "preceding" ? -Infinity : Infinity;
  if (boundary.kind !== "offset") throw new TypeError("Invalid ROWS boundary");
  return offset(boundary.count) * (boundary.direction === "preceding" ? -1 : 1);
}
function rowsFrame(start: FrameStart, end: FrameEnd): RowsFrame {
  const first = boundaryPosition(start);
  const last = boundaryPosition(end);
  if (first === Infinity || last === -Infinity || first > last)
    throw new RangeError("Portable ROWS start/end ordering must not reverse");
  return Object.freeze({
    start: first === 0 ? current : Object.freeze({ ...start }),
    end: last === 0 ? current : Object.freeze({ ...end }),
  });
}

/** 没有声明 frame 就保留数据库默认 peer 窗口，不隐式增加 ROWS 或常量分区。 */
export class WindowSpec {
  readonly definition: WindowDefinition;
  constructor(definition: WindowDefinition = { partitions: [], ordering: [] }) {
    this.definition = Object.freeze({
      partitions: Object.freeze([...definition.partitions]),
      ordering: snapshotOrders(definition.ordering),
      ...(definition.frame
        ? { frame: rowsFrame(definition.frame.start, definition.frame.end) }
        : {}),
    });
    if (new.target === WindowSpec) Object.freeze(this);
  }
  partitionBy(...expressions: readonly AnyExpression[]): WindowSpec {
    return new WindowSpec({
      ...this.definition,
      partitions: [
        ...this.definition.partitions,
        ...expressions.map((expression) => expression.node),
      ],
    });
  }
  orderBy(...orders: readonly Order[]): WindowSpec {
    return new WindowSpec({
      ...this.definition,
      ordering: [...this.definition.ordering, ...orders],
    });
  }
  rowsBetween(start: FrameStart, end: FrameEnd): WindowSpec {
    return new WindowSpec({ ...this.definition, frame: rowsFrame(start, end) });
  }
}
export function windowSpec(): WindowSpec {
  return new WindowSpec();
}

export function windowExpression<T, K extends SqlKind>(
  input: AggregateNode | WindowFunctionNode,
  type: DataType<T, K>,
  spec: WindowSpec,
): Expression<T, K> {
  if (!(spec instanceof WindowSpec)) throw new TypeError("OVER requires a WindowSpec");
  const definition = spec.definition;
  validateWindow(input, definition);
  return new Expression({
    node: freezeNode({ kind: "window", input: Object.freeze({ ...input }), spec: definition }),
    type,
  });
}

export function validateWindow(
  input: AggregateNode | WindowFunctionNode,
  definition: WindowDefinition,
): void {
  if (definition.frame) rowsFrame(definition.frame.start, definition.frame.end);
  snapshotOrders(definition.ordering);
  if (definition.frame && !definition.ordering.length)
    throw new TypeError("A ROWS frame requires ORDER BY");
  if (input.kind === "aggregate") {
    if (input.distinct) throw new TypeError("Portable aggregate windows do not support DISTINCT");
  } else {
    if (
      ![
        "ROW_NUMBER",
        "RANK",
        "DENSE_RANK",
        "LAG",
        "LEAD",
        "FIRST_VALUE",
        "LAST_VALUE",
        "NTH_VALUE",
      ].includes(input.name)
    )
      throw new TypeError("Unknown window function");
    if (
      ["ROW_NUMBER", "RANK", "DENSE_RANK"].includes(input.name) &&
      (input.operand || input.offset !== undefined || input.fallback)
    )
      throw new TypeError("Ranking functions do not accept value arguments");
    if (
      ["FIRST_VALUE", "LAST_VALUE"].includes(input.name) &&
      (input.offset !== undefined || input.fallback)
    )
      throw new TypeError("FIRST_VALUE/LAST_VALUE do not accept offsets or defaults");
    if (input.name === "NTH_VALUE" && input.fallback)
      throw new TypeError("NTH_VALUE does not accept a default");
    if (input.fallback && input.offset === undefined)
      throw new TypeError("A window default requires an explicit offset");
    if (
      ["LAG", "LEAD", "FIRST_VALUE", "LAST_VALUE", "NTH_VALUE"].includes(input.name) &&
      !input.operand
    )
      throw new TypeError(`${input.name} requires an operand`);
    if (input.offset !== undefined) offset(input.offset, input.name === "NTH_VALUE" ? 1 : 0);
    if (input.name === "NTH_VALUE" && input.offset === undefined)
      throw new TypeError("NTH_VALUE requires a position");
    if (input.name !== "ROW_NUMBER" && !definition.ordering.length)
      throw new TypeError(`${input.name} requires ORDER BY`);
    if (
      definition.frame &&
      ["ROW_NUMBER", "RANK", "DENSE_RANK", "LAG", "LEAD"].includes(input.name)
    )
      throw new TypeError(
        `${input.name} does not accept a frame; some databases silently ignore it`,
      );
  }
}

/** 未调用 over 的窗口函数不是 Expression，不能被误投影成缺少 OVER 的 SQL。 */
export class WindowFunction<T, K extends SqlKind> {
  constructor(
    private readonly input: WindowFunctionNode,
    private readonly type: DataType<T, K>,
  ) {
    this.input = Object.freeze({ ...input });
  }
  over(spec: WindowSpec): Expression<T, K> {
    return windowExpression(this.input, this.type, spec);
  }
}
function valueWindow<T, K extends SqlKind>(
  name: WindowFunctionNode["name"],
  value: Expression<T, K>,
  position?: number,
  fallback?: T | null | Expression<T | null, K>,
): WindowFunction<T | null, K> {
  let defaultNode: Node | undefined;
  if (fallback !== undefined) {
    if (fallback instanceof Expression) {
      if (!value.type.hasSameStorage(fallback.type) || !value.type.hasSameCodec(fallback.type))
        throw new TypeError("Window default requires matching SQL storage and codec identity");
      defaultNode = fallback.node;
    } else defaultNode = valueOf(fallback, value.type.nullable()).node;
  }
  return new WindowFunction(
    Object.freeze({
      kind: "window-function",
      name,
      operand: value.node,
      ...(position === undefined ? {} : { offset: offset(position, name === "NTH_VALUE" ? 1 : 0) }),
      ...(defaultNode ? { fallback: defaultNode } : {}),
    }),
    value.type.nullable(),
  );
}
export const windowFunctions = {
  rowNumber(): WindowFunction<bigint, "bigint"> {
    return new WindowFunction({ kind: "window-function", name: "ROW_NUMBER" }, types.bigint());
  },
  rank(): WindowFunction<bigint, "bigint"> {
    return new WindowFunction({ kind: "window-function", name: "RANK" }, types.bigint());
  },
  denseRank(): WindowFunction<bigint, "bigint"> {
    return new WindowFunction({ kind: "window-function", name: "DENSE_RANK" }, types.bigint());
  },
  lag<T, K extends SqlKind>(
    value: Expression<T, K>,
    position = 1,
    fallback?: NoInfer<T | null | Expression<T | null, K>>,
  ): WindowFunction<T | null, K> {
    return valueWindow("LAG", value, position, fallback);
  },
  lead<T, K extends SqlKind>(
    value: Expression<T, K>,
    position = 1,
    fallback?: NoInfer<T | null | Expression<T | null, K>>,
  ): WindowFunction<T | null, K> {
    return valueWindow("LEAD", value, position, fallback);
  },
  firstValue<T, K extends SqlKind>(value: Expression<T, K>): WindowFunction<T | null, K> {
    return valueWindow("FIRST_VALUE", value);
  },
  lastValue<T, K extends SqlKind>(value: Expression<T, K>): WindowFunction<T | null, K> {
    return valueWindow("LAST_VALUE", value);
  },
  nthValue<T, K extends SqlKind>(
    value: Expression<T, K>,
    position: number,
  ): WindowFunction<T | null, K> {
    return valueWindow("NTH_VALUE", value, position);
  },
};
