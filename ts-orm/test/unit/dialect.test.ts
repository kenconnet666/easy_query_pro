import assert from "node:assert/strict";
import test from "node:test";
import {
  call,
  aggregate,
  column,
  createDatabase,
  grouping,
  nativeCast,
  postgresDialect,
  table,
  types,
  type Dialect,
  type FunctionDefinition,
  type SqlNode,
} from "../../src/index.js";

test("a stateful dialect keeps its method receiver through nested query compilation", () => {
  class StatefulDialect implements Dialect {
    readonly name = "postgres";
    readonly maxParameters = 50;
    private readonly base = postgresDialect;
    readonly visited = new Set<string>();
    quote(name: string): string {
      this.visited.add("quote");
      return this.base.quote(name);
    }
    parameter(...args: Parameters<Dialect["parameter"]>): string {
      this.visited.add("parameter");
      return this.base.parameter(...args);
    }
    cast(...args: Parameters<Dialect["cast"]>): string {
      this.visited.add("cast");
      return this.base.cast(...args);
    }
    pagination(...args: Parameters<Dialect["pagination"]>): string {
      this.visited.add("pagination");
      return this.base.pagination(...args);
    }
  }
  const dialect = new StatefulDialect();
  const db = createDatabase({ dialect });
  const entries = table("entries", { id: column(types.integer()), name: column(types.text(20)) });
  const source = db
    .from({ e: entries })
    .where(({ e }) => e.id.gt(1))
    .select(({ e }) => ({ id: nativeCast(e.id, types.bigint()), name: e.name }))
    .asCte("eligible");
  const compiled = db
    .from({ r: source })
    .rollupBy(({ r }) => ({ name: r.name }))
    .orderBy((_, __, keys) => grouping(keys.name).asc())
    .select((_, __, keys) => ({ name: keys.name, count: aggregate.count() }))
    .limit(10)
    .compile();
  assert.match(compiled.sql, /^WITH "eligible" AS/);
  assert.match(compiled.sql, /ROLLUP/);
  assert.deepEqual(compiled.parameters, [1]);
  assert.deepEqual([...dialect.visited].sort(), ["cast", "pagination", "parameter", "quote"]);
});

test("function definitions retain class getters and renderer method receivers", () => {
  class Renderers {
    #closing = ")";
    postgres(args: readonly SqlNode[]): SqlNode {
      return { kind: "fragment", parts: ["ABS(", args[0]!, this.#closing] };
    }
  }
  class Definition implements FunctionDefinition {
    readonly render = new Renderers();
    nameReads = 0;
    phaseReads = 0;
    get name() {
      this.nameReads++;
      return "class-abs";
    }
    get phase(): "row" {
      this.phaseReads++;
      return "row";
    }
  }
  const definition = new Definition();
  const db = createDatabase({ dialect: postgresDialect });
  const base = db.from({ x: table("sample", { id: column(types.integer()) }) });
  const expression = call(definition, types.integer(), [base.scope.x.id]);
  assert.match(
    base
      .where(() => expression.gt(0))
      .select(() => ({ expression }))
      .compile().sql,
    /ABS\("x"\."id"\)/,
  );
  assert.equal(definition.nameReads, 1);
  assert.equal(definition.phaseReads, 1);
  const own = {
    closing: ")",
    postgres(args: readonly SqlNode[]): SqlNode {
      return { kind: "fragment", parts: ["ABS(", args[0]!, this.closing] };
    },
  };
  assert.match(
    base
      .select(() => ({
        expression: call({ name: "own-method", phase: "row", render: own }, types.integer(), [
          base.scope.x.id,
        ]),
      }))
      .compile().sql,
    /ABS\(/,
  );
  const aggregateDefinition: FunctionDefinition = {
    get name() {
      return "declared-aggregate";
    },
    get phase(): "aggregate" {
      return "aggregate";
    },
    render: new Renderers(),
  };
  const aggregateExpression = call(aggregateDefinition, types.integer(), [base.scope.x.id]);
  assert.throws(
    () =>
      base
        .where(() => aggregateExpression.gt(0))
        .select(({ x }) => ({ id: x.id }))
        .compile(),
    /same-level aggregate/,
  );
});
