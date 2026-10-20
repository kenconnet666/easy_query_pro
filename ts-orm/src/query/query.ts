import { createProjection } from "../compiler/projection.js";
import { compileSelect, type CompiledQuery } from "../compiler/select.js";
import { Expression, and, type Predicate } from "../expression/expression.js";
import { freezeNode } from "../expression/node.js";
import { recordEntries, type NoSymbolKeys } from "../internal/record.js";
import { setSource, type CompatibleSelection, type SetSelection } from "./set-operation.js";
import type { Session } from "../execution/session.js";
import type { Columns, Column, ColumnOptions, Table } from "../schema/table.js";
import { validateName } from "../schema/table.js";
import type { SqlKind } from "../schema/data-type.js";
import {
  DerivedSource,
  snapshotOrders,
  type Order,
  type Relation,
  type ResultOf,
  type Selection,
  type SelectNode,
  type Source,
} from "./model.js";

export type Sources = Readonly<Record<string, Source>>;

export interface Page<T> {
  readonly pageNumber: number;
  readonly pageSize: number;
  readonly total: bigint;
  readonly totalPages: bigint;
  readonly rows: T[];
}
type SourceExpressions<S> =
  S extends Table<infer C>
    ? {
        [P in keyof C]: C[P] extends Column<infer T, infer K, ColumnOptions>
          ? Expression<T, K>
          : never;
      }
    : S extends DerivedSource<infer P>
      ? { [Key in keyof P]: P[Key] extends Expression<infer T, infer K> ? Expression<T, K> : never }
      : never;
type NullableColumns<C> = {
  [P in keyof C]: C[P] extends Expression<infer T, infer K> ? Expression<T | null, K> : never;
};
export type BoundSources<R extends Sources, Nullable extends boolean = false> = {
  [P in keyof R]: Nullable extends true
    ? NullableColumns<SourceExpressions<R[P]>>
    : SourceExpressions<R[P]>;
};
export type Scope = Readonly<
  Record<string, Readonly<Record<string, Expression<unknown, SqlKind>>>>
>;
type Merge<A, B> = A & B;

export function bindSources<R extends Sources>(
  sources: R,
  owner: symbol,
  join: Relation["join"],
): { scope: BoundSources<R>; relations: Relation[] } {
  const scope: Record<string, Record<string, Expression<unknown, SqlKind>>> = Object.create(
    null,
  ) as Scope;
  const relations: Relation[] = [];
  for (const [alias, source] of recordEntries(sources, "Query sources")) {
    validateName(alias);
    if (source instanceof DerivedSource && source.query.owner !== owner)
      throw new TypeError("A derived source cannot cross database clients");
    const identity = Object.freeze({ id: Symbol(alias), owner, alias });
    const columns: Record<string, Expression<unknown, SqlKind>> = Object.create(null) as Record<
      string,
      Expression<unknown, SqlKind>
    >;
    for (const [key, field] of Object.entries(source.fields)) {
      columns[key] = new Expression({
        node: freezeNode({
          kind: "column",
          relation: identity,
          name: field.columnName,
          numericOrigin: field.numericOrigin,
        }),
        type: field.type,
      });
    }
    scope[alias] = Object.freeze(columns);
    relations.push(
      Object.freeze({
        ...identity,
        source,
        join: relations.length && join === "FROM" ? "CROSS" : join,
      }),
    );
  }
  if (!relations.length) throw new TypeError("A query needs at least one source");
  // 每个运行时字段来自相同 schema/投影描述，动态遍历只在这个边界恢复对应映射类型。
  return { scope: Object.freeze(scope) as BoundSources<R>, relations };
}

export class Query<
  S extends Scope,
  P extends Selection | never,
  E,
  G extends Selection = Record<never, never>,
> {
  constructor(
    readonly scope: S,
    readonly extensions: E,
    private readonly session: Session,
    private readonly state: SelectNode,
    readonly groupKeys: G,
  ) {}

  static from<R extends Sources, E>(
    sources: R & NoSymbolKeys<R>,
    session: Session,
    extensions: E,
  ): Query<BoundSources<R>, never, E> {
    session.assertActive();
    const bound = bindSources(sources, session.owner, "FROM");
    return new Query(
      bound.scope,
      extensions,
      session,
      Object.freeze({
        kind: "select",
        owner: session.owner,
        relations: Object.freeze(bound.relations),
        projection: Object.freeze([]),
        ordering: Object.freeze([]),
        grouping: Object.freeze([]),
        offset: 0,
        distinct: false,
      }),
      Object.freeze({}),
    );
  }

  private change(state: SelectNode): Query<S, P, E, G> {
    return new Query(
      this.scope,
      this.extensions,
      this.session,
      Object.freeze(state),
      this.groupKeys,
    );
  }

  where(predicate: (scope: S, extensions: E) => Predicate): Query<S, P, E, G> {
    const next = predicate(this.scope, this.extensions);
    const combined = this.state.where
      ? and(new Expression({ node: this.state.where, type: next.type }), next).node
      : next.node;
    return this.change({ ...this.state, where: combined });
  }

  /** 命名键保留表达式身份，SELECT/HAVING/ORDER 可直接复用计算键，不按 SQL 文本猜相等。 */
  groupBy<const K extends Selection>(
    keys: (scope: S, extensions: E) => K & NoSymbolKeys<K>,
  ): Query<S, P, E, K> {
    const selected = Object.freeze(
      Object.fromEntries(recordEntries(keys(this.scope, this.extensions), "Group keys")),
    ) as K;
    const slots = createProjection(selected);
    return new Query(
      this.scope,
      this.extensions,
      this.session,
      Object.freeze({ ...this.state, grouping: Object.freeze(slots.map((slot) => slot.node)) }),
      selected,
    );
  }

  having(predicate: (scope: S, extensions: E, groups: G) => Predicate): Query<S, P, E, G> {
    const next = predicate(this.scope, this.extensions, this.groupKeys);
    const condition = this.state.having
      ? and(new Expression({ node: this.state.having, type: next.type }), next).node
      : next.node;
    return this.change({ ...this.state, having: condition });
  }

  select<const R extends Selection>(
    selection: (scope: S, extensions: E, groups: G) => R & NoSymbolKeys<R>,
  ): Query<S, R, E, G> {
    const values = selection(this.scope, this.extensions, this.groupKeys);
    const projection = createProjection(values);
    return new Query(
      this.scope,
      this.extensions,
      this.session,
      Object.freeze({ ...this.state, projection: Object.freeze(projection) }),
      this.groupKeys,
    );
  }

  innerJoin<const R extends Sources>(
    sources: R & NoSymbolKeys<R> & (Extract<keyof R, keyof S> extends never ? unknown : never),
    on: (scope: Merge<S, BoundSources<R>>, extensions: E) => Predicate,
  ): Query<Merge<S, BoundSources<R>>, P, E, G> {
    return this.join<R, false>(sources, on, false);
  }

  leftJoin<const R extends Sources>(
    sources: R & NoSymbolKeys<R> & (Extract<keyof R, keyof S> extends never ? unknown : never),
    on: (scope: Merge<S, BoundSources<R>>, extensions: E) => Predicate,
  ): Query<Merge<S, BoundSources<R, true>>, P, E, G> {
    return this.join<R, true>(sources, on, true);
  }

  private join<R extends Sources, N extends boolean>(
    sources: R,
    on: (scope: Merge<S, BoundSources<R>>, extensions: E) => Predicate,
    left: N,
  ): Query<Merge<S, BoundSources<R, N>>, P, E, G> {
    if (Object.keys(sources).length !== 1)
      throw new TypeError("Join one explicitly named source at a time");
    for (const alias of Object.keys(sources))
      if (Object.hasOwn(this.scope, alias))
        throw new TypeError("Duplicate relation alias: " + alias);
    const bound = bindSources(sources, this.session.owner, left ? "LEFT" : "INNER");
    const joinScope = Object.freeze({ ...this.scope, ...bound.scope }) as Merge<S, BoundSources<R>>;
    const predicate = on(joinScope, this.extensions);
    const relation = Object.freeze({ ...bound.relations[0]!, on: predicate.node });
    const finalScope: Record<string, Readonly<Record<string, Expression<unknown, SqlKind>>>> = {
      ...joinScope,
    };
    if (left) {
      // ON 使用来源原类型，外连接完成后才使右侧列可空；原表和其他关系引用完全不变。
      for (const alias of Object.keys(bound.scope)) {
        finalScope[alias] = Object.freeze(
          Object.fromEntries(
            Object.entries(bound.scope[alias]!).map(([key, value]) => [key, value.nullable()]),
          ),
        );
      }
    }
    return new Query(
      Object.freeze(finalScope) as Merge<S, BoundSources<R, N>>,
      this.extensions,
      this.session,
      Object.freeze({
        ...this.state,
        relations: Object.freeze([...this.state.relations, relation]),
      }),
      this.groupKeys,
    );
  }

  orderBy(
    order: (scope: S, extensions: E, groups: G) => Order | readonly Order[],
  ): Query<S, P, E, G> {
    const selected = order(this.scope, this.extensions, this.groupKeys);
    const ordering = Array.isArray(selected) ? selected : [selected as Order];
    return this.change({
      ...this.state,
      ordering: Object.freeze([...this.state.ordering, ...snapshotOrders(ordering)]),
    });
  }

  limit(limit: number, offset = 0): Query<S, P, E, G> {
    if (!Number.isSafeInteger(limit) || limit < 0 || !Number.isSafeInteger(offset) || offset < 0)
      throw new RangeError("Limit and offset must be nonnegative safe integers");
    return this.change({ ...this.state, limit, offset });
  }

  distinct(): Query<S, P, E, G> {
    return this.change({ ...this.state, distinct: true });
  }

  as(this: [P] extends [never] ? never : Query<S, P, E, G>): DerivedSource<P> {
    if (!this.state.projection.length)
      throw new TypeError("Select before creating a derived source");
    return new DerivedSource(this.state);
  }

  asCte(this: [P] extends [never] ? never : Query<S, P, E, G>, name: string): DerivedSource<P> {
    return this.as().asCte(name);
  }

  /** 集合运算产生新关系，后续过滤/排序显式从结果列开始，不泄漏左分支的表作用域。 */
  union<OS extends Scope, Q extends Selection, OE, OG extends Selection>(
    this: [P] extends [never] ? never : Query<S, P, E, G>,
    other: Query<OS, Q, OE, OG> & CompatibleSelection<P, Q>,
  ): DerivedSource<SetSelection<P, Q>> {
    return setSource<P, Q>(this.state, other.state, false);
  }

  unionAll<OS extends Scope, Q extends Selection, OE, OG extends Selection>(
    this: [P] extends [never] ? never : Query<S, P, E, G>,
    other: Query<OS, Q, OE, OG> & CompatibleSelection<P, Q>,
  ): DerivedSource<SetSelection<P, Q>> {
    return setSource<P, Q>(this.state, other.state, true);
  }
  compile(this: [P] extends [never] ? never : Query<S, P, E, G>): CompiledQuery<ResultOf<P>> {
    return compileSelect(this.state, this.session.dialect);
  }
  async execute(this: [P] extends [never] ? never : Query<S, P, E, G>): Promise<ResultOf<P>[]> {
    return this.session.execute(compileSelect<ResultOf<P>>(this.state, this.session.dialect));
  }
  async first(
    this: [P] extends [never] ? never : Query<S, P, E, G>,
  ): Promise<ResultOf<P> | undefined> {
    const result = await this.session.execute(
      compileSelect<ResultOf<P>>(
        { ...this.state, limit: Math.min(this.state.limit ?? 1, 1) },
        this.session.dialect,
      ),
    );
    return result[0];
  }
  async count(this: [P] extends [never] ? never : Query<S, P, E, G>): Promise<bigint> {
    const rows = await this.session.execute(
      compileSelect<bigint>(this.state, this.session.dialect, true),
    );
    return rows[0] ?? 0n;
  }

  /**
   * COUNT 与当前页在同一租约中顺序读取，保留完整投影、DISTINCT 和报表查询边界。
   * 同连接不等于同快照；需要稳定视图时，在显式 REPEATABLE READ / SERIALIZABLE 事务里调用。
   */
  async page(
    this: [P] extends [never] ? never : Query<S, P, E, G>,
    pageNumber: number,
    pageSize: number,
  ): Promise<Page<ResultOf<P>>> {
    if (
      !Number.isSafeInteger(pageNumber) ||
      pageNumber < 1 ||
      !Number.isSafeInteger(pageSize) ||
      pageSize < 1
    )
      throw new RangeError("Page number and size must be positive safe integers");
    const offset = (BigInt(pageNumber) - 1n) * BigInt(pageSize);
    if (offset > BigInt(Number.MAX_SAFE_INTEGER))
      throw new RangeError("Page offset exceeds the safe integer range");
    if (!this.state.ordering.length)
      throw new TypeError("Pagination requires an explicit order; include a unique tie-breaker");
    if (this.state.limit !== undefined || this.state.offset !== 0)
      throw new TypeError(
        "Paginate an unlimited query; derive an already limited query explicitly",
      );
    // 两个计划都先编译，第二条的方言/作用域错误不能发生在 COUNT 已经访问数据库之后。
    const count = compileSelect<bigint>(this.state, this.session.dialect, true);
    const data = compileSelect<ResultOf<P>>(
      { ...this.state, limit: pageSize, offset: Number(offset) },
      this.session.dialect,
    );
    return this.session.withConnection(async (connection) => {
      const counted = await connection.execute(count.sql, count.parameters);
      if (counted.rows.length !== 1)
        throw new Error("Pagination COUNT must return exactly one row");
      const total = count.decode(counted.rows[0]!);
      if (total < 0n) throw new RangeError("Pagination COUNT cannot be negative");
      // 回调若提前结束，已经在途的 COUNT 不能让失效的 tx 再启动下一条 SQL。
      this.session.assertActive();
      const result = await connection.execute(data.sql, data.parameters);
      return {
        pageNumber,
        pageSize,
        total,
        totalPages: (total + BigInt(pageSize) - 1n) / BigInt(pageSize),
        rows: result.rows.map((row) => data.decode(row)),
      };
    });
  }
}
