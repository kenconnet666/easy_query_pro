import type { Dialect } from "./dialects/dialect.js";
import { call, valueOf } from "./expression/expression.js";
import { sqlFragment } from "./expression/node.js";
import type { Node } from "./expression/node.js";
import { compileCommand } from "./compiler/select.js";
import type { DriverResult } from "./execution/driver.js";
import type { Driver } from "./execution/driver.js";
import { Session } from "./execution/session.js";
import type { TransactionOptions } from "./execution/transaction.js";
import { Query, type Sources, type BoundSources } from "./query/query.js";
import { recordEntries, type NoSymbolKeys } from "./internal/record.js";
import { WriteQuery } from "./query/write.js";
import type { Columns, Table } from "./schema/table.js";

export interface ExtensionContext {
  readonly dialect: Dialect["name"];
  readonly value: typeof valueOf;
  readonly call: typeof call;
  readonly sql: typeof sqlFragment;
}
export type ExtensionFactories = Readonly<Record<string, (context: ExtensionContext) => unknown>>;
export type ExtensionApis<R extends ExtensionFactories> = {
  readonly [P in keyof R]: ReturnType<R[P]>;
};

function instantiate<R extends ExtensionFactories>(
  session: Session,
  factories: R,
): ExtensionApis<R> {
  const context = Object.freeze({
    dialect: session.dialect.name,
    value: valueOf,
    call,
    sql: sqlFragment,
  });
  return Object.freeze(
    Object.fromEntries(
      recordEntries(factories, "Extension namespaces").map(([name, factory]) => [
        name,
        factory(context),
      ]),
    ),
  ) as ExtensionApis<R>;
}

/**
 * 扩展 registry 属于当前 client，查询变换与事务保留同一具体 API 类型。
 * 不修改全局 prototype，也不要求 module augmentation 污染所有数据库实例。
 */
export class Database<R extends ExtensionFactories = Record<never, never>> {
  readonly extensions: ExtensionApis<R>;
  constructor(
    private readonly session: Session,
    private readonly factories: R & NoSymbolKeys<R>,
    apis?: ExtensionApis<R>,
  ) {
    this.factories = Object.freeze(
      Object.fromEntries(recordEntries(factories, "Extension namespaces")),
    ) as R & NoSymbolKeys<R>;
    this.extensions = apis ?? instantiate(session, this.factories);
  }

  from<const S extends Sources>(
    sources: S & NoSymbolKeys<S>,
  ): Query<BoundSources<S>, never, ExtensionApis<R>> {
    return Query.from(sources, this.session, this.extensions);
  }

  insert<C extends Columns>(target: Table<C>): WriteQuery<C, ExtensionApis<R>, "insert"> {
    return WriteQuery.create("insert", target, this.session, this.extensions);
  }
  update<C extends Columns>(target: Table<C>): WriteQuery<C, ExtensionApis<R>, "update"> {
    return WriteQuery.create("update", target, this.session, this.extensions);
  }
  deleteFrom<C extends Columns>(target: Table<C>): WriteQuery<C, ExtensionApis<R>, "delete"> {
    return WriteQuery.create("delete", target, this.session, this.extensions);
  }

  /** 执行显式结构化 SQL，不声明其原始结果已经通过业务 decoder。事务内仍使用同一连接。 */
  async command(node: Node): Promise<DriverResult> {
    const compiled = compileCommand(node, this.session.dialect, this.session.owner);
    return this.session.raw(compiled.sql, compiled.parameters);
  }

  withExtensions<const X extends ExtensionFactories>(
    factories: X & NoSymbolKeys<X>,
  ): Database<R & X> {
    for (const key of Object.keys(factories))
      if (Object.hasOwn(this.factories, key))
        throw new TypeError("Duplicate extension namespace: " + key);
    const apis = Object.freeze({
      ...this.extensions,
      ...instantiate(this.session, factories),
    }) as ExtensionApis<R & X>;
    return new Database<R & X>(
      this.session,
      Object.freeze({ ...this.factories, ...factories }) as R & X & NoSymbolKeys<R & X>,
      apis,
    );
  }

  /** 业务 client 的扩展命名空间保持不变，连接和失败清理统一交给执行层。 */
  async transaction<T>(
    callback: (transaction: Database<R>) => Promise<T>,
    options?: TransactionOptions,
  ): Promise<T> {
    return this.session.transaction(
      (session) => callback(new Database(session, this.factories, this.extensions)),
      options,
    );
  }
}

export function createDatabase<const R extends ExtensionFactories = Record<never, never>>(options: {
  dialect: Dialect;
  driver?: Driver;
  extensions?: R & NoSymbolKeys<R>;
}): Database<R> {
  return new Database(
    new Session(options.dialect, Symbol("database"), options.driver),
    options.extensions ?? ({} as R & NoSymbolKeys<R>),
  );
}
