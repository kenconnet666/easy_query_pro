import { compileSelect, type CompiledQuery } from "../compiler/select.js";
import { Expression, and, type Predicate } from "../expression/expression.js";
import { freezeNode } from "../expression/node.js";
import type { Session } from "../execution/session.js";
import type { Columns, Column, Table } from "../schema/table.js";
import { validateName } from "../schema/table.js";
import type { SqlKind } from "../schema/data-type.js";
import {
  DerivedSource,
  type Order,
  type Relation,
  type ResultOf,
  type Selection,
  type SelectNode,
  type Source,
} from "./model.js";

export type Sources = Readonly<Record<string, Source>>;
type SourceExpressions<S> =
  S extends Table<infer C>
    ? { [P in keyof C]: C[P] extends Column<infer T, infer K, boolean> ? Expression<T, K> : never }
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

function bindSources<R extends Sources>(
  sources: R,
  owner: symbol,
  join: Relation["join"],
): { scope: BoundSources<R>; relations: Relation[] } {
  const scope: Record<string, Record<string, Expression<unknown, SqlKind>>> = Object.create(
    null,
  ) as Scope;
  const relations: Relation[] = [];
  for (const [alias, source] of Object.entries(sources)) {
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
        node: freezeNode({ kind: "column", relation: identity, name: field.columnName }),
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

export class Query<S extends Scope, P extends Selection | never, E> {
  constructor(
    readonly scope: S,
    readonly extensions: E,
    private readonly session: Session,
    private readonly state: SelectNode,
  ) {}

  static from<R extends Sources, E>(
    sources: R,
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
        owner: session.owner,
        relations: Object.freeze(bound.relations),
        projection: Object.freeze([]),
        ordering: Object.freeze([]),
        offset: 0,
        distinct: false,
      }),
    );
  }

  private change(state: SelectNode): Query<S, P, E> {
    return new Query(this.scope, this.extensions, this.session, Object.freeze(state));
  }

  where(predicate: (scope: S, extensions: E) => Predicate): Query<S, P, E> {
    const next = predicate(this.scope, this.extensions);
    const combined = this.state.where
      ? and(new Expression({ node: this.state.where, type: next.type }), next).node
      : next.node;
    return this.change({ ...this.state, where: combined });
  }

  select<const R extends Selection>(selection: (scope: S, extensions: E) => R): Query<S, R, E> {
    const values = selection(this.scope, this.extensions);
    const projection = Object.entries(values).map(([key, expression], index) => {
      validateName(key);
      if (!(expression instanceof Expression))
        throw new TypeError("Selections require typed expressions");
      return Object.freeze({
        key,
        sqlAlias: "_eq_" + index,
        node: expression.node,
        type: expression.type,
      });
    });
    if (!projection.length) throw new TypeError("A selection cannot be empty");
    return new Query(
      this.scope,
      this.extensions,
      this.session,
      Object.freeze({ ...this.state, projection: Object.freeze(projection) }),
    );
  }

  innerJoin<const R extends Sources>(
    sources: R & (Extract<keyof R, keyof S> extends never ? unknown : never),
    on: (scope: Merge<S, BoundSources<R>>, extensions: E) => Predicate,
  ): Query<Merge<S, BoundSources<R>>, P, E> {
    return this.join<R, false>(sources, on, false);
  }

  leftJoin<const R extends Sources>(
    sources: R & (Extract<keyof R, keyof S> extends never ? unknown : never),
    on: (scope: Merge<S, BoundSources<R>>, extensions: E) => Predicate,
  ): Query<Merge<S, BoundSources<R, true>>, P, E> {
    return this.join<R, true>(sources, on, true);
  }

  private join<R extends Sources, N extends boolean>(
    sources: R,
    on: (scope: Merge<S, BoundSources<R>>, extensions: E) => Predicate,
    left: N,
  ): Query<Merge<S, BoundSources<R, N>>, P, E> {
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
    );
  }

  orderBy(order: (scope: S, extensions: E) => Order | readonly Order[]): Query<S, P, E> {
    const selected = order(this.scope, this.extensions);
    const ordering = Array.isArray(selected) ? selected : [selected as Order];
    return this.change({
      ...this.state,
      ordering: Object.freeze([...this.state.ordering, ...ordering]),
    });
  }

  limit(limit: number, offset = 0): Query<S, P, E> {
    if (!Number.isSafeInteger(limit) || limit < 0 || !Number.isSafeInteger(offset) || offset < 0)
      throw new RangeError("Limit and offset must be nonnegative safe integers");
    return this.change({ ...this.state, limit, offset });
  }

  distinct(): Query<S, P, E> {
    return this.change({ ...this.state, distinct: true });
  }

  as(this: [P] extends [never] ? never : Query<S, P, E>): DerivedSource<P> {
    if (!this.state.projection.length)
      throw new TypeError("Select before creating a derived source");
    return new DerivedSource(this.state);
  }
  compile(this: [P] extends [never] ? never : Query<S, P, E>): CompiledQuery<ResultOf<P>> {
    return compileSelect(this.state, this.session.dialect);
  }
  async execute(this: [P] extends [never] ? never : Query<S, P, E>): Promise<ResultOf<P>[]> {
    return this.session.execute(compileSelect<ResultOf<P>>(this.state, this.session.dialect));
  }
  async first(
    this: [P] extends [never] ? never : Query<S, P, E>,
  ): Promise<ResultOf<P> | undefined> {
    const result = await this.session.execute(
      compileSelect<ResultOf<P>>(
        { ...this.state, limit: Math.min(this.state.limit ?? 1, 1) },
        this.session.dialect,
      ),
    );
    return result[0];
  }
  async count(this: [P] extends [never] ? never : Query<S, P, E>): Promise<bigint> {
    const rows = await this.session.execute(
      compileSelect<bigint>(this.state, this.session.dialect, true),
    );
    return rows[0] ?? 0n;
  }
}
