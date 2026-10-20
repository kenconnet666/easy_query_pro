import { compileWrite, type CompiledWrite } from "../compiler/write.js";
import { createProjection } from "../compiler/projection.js";
import { Expression, and, valueOf, type Predicate } from "../expression/expression.js";
import type { Node, RelationIdentity } from "../expression/node.js";
import type { Session } from "../execution/session.js";
import { recordEntries, type NoSymbolKeys } from "../internal/record.js";
import { types } from "../schema/data-type.js";
import {
  Table,
  type Column,
  type ColumnOptions,
  type Columns,
  type InsertOf,
  type UpdateOf,
  type WritableKeys,
} from "../schema/table.js";
import { bindSources, type BoundSources } from "./query.js";
import type { ProjectionSlot, ResultOf, Selection } from "./model.js";

type Fields<C extends Columns> = BoundSources<{ target: Table<C> }>["target"];
type WritableValue<C> =
  C extends Column<infer T, infer K, ColumnOptions> ? T | Expression<T, K> : never;
export type InsertValues<C extends Columns> = {
  [P in keyof InsertOf<C>]: P extends WritableKeys<C>
    ? WritableValue<C[P & keyof C]> | (undefined extends InsertOf<C>[P] ? undefined : never)
    : never;
};
export type UpdateValues<C extends Columns> = {
  [P in keyof UpdateOf<C>]: P extends WritableKeys<C>
    ? WritableValue<C[P & keyof C]> | undefined
    : never;
};
export interface WriteResult<R> {
  readonly affectedRows: bigint;
  readonly rows: R[];
}
export interface Assignment {
  readonly key: string;
  readonly column: string;
  readonly value: Node;
}
export type WriteOperation = "insert" | "update" | "delete";
export interface WriteState {
  readonly operation: WriteOperation;
  readonly owner: symbol;
  readonly target: Table<Columns>;
  readonly relation: RelationIdentity;
  readonly rows?: readonly (readonly Assignment[])[];
  readonly assignments: readonly Assignment[];
  readonly where?: Node;
  readonly allRows: boolean;
  readonly projection: readonly ProjectionSlot[];
}

/** 语句失败保留阶段与已完成计数，不推算数据库生成键，也不自动重试已执行的写入。 */
export class WriteExecutionError extends Error {
  constructor(
    readonly statementIndex: number,
    readonly completedStatements: number,
    readonly affectedRows: bigint,
    cause: unknown,
  ) {
    super(
      `Write statement ${statementIndex + 1} failed after ${completedStatements} completed statements`,
      { cause },
    );
    this.name = "WriteExecutionError";
  }
}

function assignments(
  target: Table<Columns>,
  input: Readonly<Record<string, unknown>>,
  insert: boolean,
): readonly Assignment[] {
  const provided = new Map(recordEntries(input, "Write values"));
  for (const key of provided.keys()) {
    const column = target.columns[key];
    if (!column) throw new TypeError(`Unknown write column: ${key}`);
    if (column.options.generated) throw new TypeError(`Generated column is read-only: ${key}`);
  }
  const assigned: Assignment[] = [];
  const physicalNames = new Set<string>();
  for (const [key, column] of Object.entries(target.columns)) {
    const value = provided.get(key);
    if (value === undefined) {
      if (
        insert &&
        !column.options.generated &&
        !column.options.hasDefault &&
        !column.type.acceptsNull
      )
        throw new TypeError(`Required insert column is missing: ${key}`);
      continue;
    }
    if (
      value instanceof Expression &&
      (!column.type.hasSameStorage(value.type) ||
        !column.type.hasSameCodec(value.type) ||
        (value.type.acceptsNull && !column.type.acceptsNull))
    )
      throw new TypeError(`Write expression has an incompatible type or codec: ${key}`);
    const name = column.options.name ?? key;
    if (physicalNames.has(name))
      throw new TypeError(`Multiple write fields target the same column: ${name}`);
    physicalNames.add(name);
    assigned.push(
      Object.freeze({
        key,
        column: name,
        value: value instanceof Expression ? value.node : valueOf(value, column.type).node,
      }),
    );
  }
  return Object.freeze(assigned);
}

/**
 * 写入 builder 与 SELECT 使用同一 metadata/Node/Session。值在构造时编码成快照，
 * compile 在借连接之前验证整个计划，execute 保持语句顺序并只借用一条连接。
 */
export class WriteQuery<
  C extends Columns,
  E,
  O extends WriteOperation,
  P extends Selection | never = never,
> {
  private constructor(
    readonly fields: Fields<C>,
    readonly extensions: E,
    private readonly session: Session,
    private readonly state: WriteState,
  ) {}
  static create<C extends Columns, E, O extends WriteOperation>(
    operation: O,
    target: Table<C>,
    session: Session,
    extensions: E,
  ): WriteQuery<C, E, O> {
    session.assertActive();
    if (!(target instanceof Table)) throw new TypeError("Writes require a physical table");
    const bound = bindSources({ target }, session.owner, "FROM");
    return new WriteQuery(
      bound.scope.target,
      extensions,
      session,
      Object.freeze({
        operation,
        owner: session.owner,
        target,
        relation: bound.relations[0]!,
        assignments: Object.freeze([]),
        allRows: false,
        projection: Object.freeze([]),
      }),
    );
  }
  private change<Q extends Selection | never = P>(state: WriteState): WriteQuery<C, E, O, Q> {
    return new WriteQuery(this.fields, this.extensions, this.session, Object.freeze(state));
  }
  values(
    this: O extends "insert" ? WriteQuery<C, E, O, P> : never,
    input: InsertValues<C> | readonly InsertValues<C>[],
  ): WriteQuery<C, E, O, P> {
    if (this.state.operation !== "insert") throw new TypeError("values() requires INSERT");
    const rows: readonly InsertValues<C>[] = Array.isArray(input)
      ? input
      : [input as InsertValues<C>];
    return this.change({
      ...this.state,
      rows: Object.freeze([
        ...(this.state.rows ?? []),
        ...rows.map((row) => assignments(this.state.target, row, true)),
      ]),
    });
  }
  set(
    this: O extends "update" ? WriteQuery<C, E, O, P> : never,
    input: UpdateValues<C> | ((fields: Fields<C>, extensions: E) => UpdateValues<C>),
  ): WriteQuery<C, E, O, P> {
    if (this.state.operation !== "update") throw new TypeError("set() requires UPDATE");
    const provided = typeof input === "function" ? input(this.fields, this.extensions) : input;
    const next = assignments(this.state.target, provided, false);
    if (!next.length) throw new TypeError("UPDATE requires at least one assigned column");
    const merged = new Map(this.state.assignments.map((value) => [value.key, value]));
    for (const value of next) merged.set(value.key, value);
    return this.change({ ...this.state, assignments: Object.freeze([...merged.values()]) });
  }
  where(
    this: O extends "insert" ? never : WriteQuery<C, E, O, P>,
    predicate: (fields: Fields<C>, extensions: E) => Predicate,
  ): WriteQuery<C, E, O, P> {
    if (this.state.operation === "insert") throw new TypeError("INSERT VALUES cannot have WHERE");
    const next = predicate(this.fields, this.extensions);
    const condition = this.state.where
      ? and(new Expression({ node: this.state.where, type: types.boolean().nullable() }), next).node
      : next.node;
    return this.change({ ...this.state, where: condition });
  }
  /** 整表写入必须有显式入口；默认不把遗漏 where 当作整表操作。 */
  allRows(this: O extends "insert" ? never : WriteQuery<C, E, O, P>): WriteQuery<C, E, O, P> {
    if (this.state.operation === "insert")
      throw new TypeError("allRows() requires UPDATE or DELETE");
    return this.change({ ...this.state, allRows: true });
  }
  returning<const Q extends Selection>(
    selection: (fields: Fields<C>, extensions: E) => Q & NoSymbolKeys<Q>,
  ): WriteQuery<C, E, O, Q> {
    return this.change<Q>({
      ...this.state,
      projection: createProjection(selection(this.fields, this.extensions)),
    });
  }
  compile(): readonly CompiledWrite<ResultOf<P>>[] {
    return compileWrite(this.state, this.session.dialect);
  }
  async execute(): Promise<WriteResult<ResultOf<P>>> {
    this.session.assertActive();
    const plan = this.compile();
    if (!plan.length) return { affectedRows: 0n, rows: [] };
    return this.session.withConnection(async (connection) => {
      let affectedRows = 0n;
      const rows: ResultOf<P>[] = [];
      for (let i = 0; i < plan.length; i++) {
        const statement = plan[i]!;
        let executed = false;
        try {
          this.session.assertActive();
          const result = await connection.execute(statement.sql, statement.parameters);
          affectedRows += result.affectedRows;
          executed = true;
          if (statement.returnsRows)
            for (const row of result.rows) rows.push(statement.decode(row));
        } catch (cause) {
          throw new WriteExecutionError(i, i + (executed ? 1 : 0), affectedRows, cause);
        }
      }
      return { affectedRows, rows };
    });
  }
}
