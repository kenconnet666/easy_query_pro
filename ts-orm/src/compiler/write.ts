import type { Dialect } from "../dialects/dialect.js";
import { visitNode } from "../expression/node.js";
import type { Assignment, WriteState } from "../query/write.js";
import { projectionDecoder } from "./projection.js";
import { renderNode, requireRowExpression, type State } from "./expression.js";
import type { CompiledQuery } from "./select.js";

export interface CompiledWrite<T> extends CompiledQuery<T> {
  readonly returnsRows: boolean;
}

export function compileWrite<T>(write: WriteState, dialect: Dialect): readonly CompiledWrite<T>[] {
  if (write.projection.length && dialect.name !== "postgres")
    throw new TypeError(
      `${dialect.name} does not support native RETURNING; no follow-up SELECT is simulated`,
    );
  if (write.operation === "insert") {
    if (write.rows === undefined) throw new TypeError("Call values before compiling INSERT");
    const groups: (readonly Assignment[])[][] = [];
    // 只合并连续相同列形状，不能为了批量而把第 1/3 行移动到第 2 行之前。
    for (const row of write.rows) {
      if (!row.length && dialect.name === "hana")
        throw new TypeError("HANA INSERT requires at least one explicit column");
      const last = groups.at(-1);
      if (
        dialect.name !== "hana" &&
        row.length &&
        last &&
        last.length < 100 &&
        sameColumns(last[0]!, row)
      )
        last.push(row);
      else groups.push([row]);
    }
    return Object.freeze(groups.map((rows) => compileStatement<T>(write, dialect, rows)));
  }
  if (!write.where && !write.allRows)
    throw new TypeError("UPDATE/DELETE requires where() or explicit allRows()");
  if (write.operation === "update" && !write.assignments.length)
    throw new TypeError("UPDATE requires assigned columns");
  const assigned = new Set(write.assignments.map((item) => item.column));
  if (assigned.size !== write.assignments.length)
    throw new TypeError("Multiple write fields target the same physical column");
  for (const item of write.assignments)
    visitNode(item.value, (node) => {
      // MySQL 从左往右赋值，PG 使用旧行；不能让对象键顺序暗中决定跨库业务结果。
      if (
        node.kind === "column" &&
        node.relation.id === write.relation.id &&
        node.name !== item.column &&
        assigned.has(node.name)
      )
        throw new TypeError("An assignment cannot read another column changed by the same UPDATE");
    });
  return Object.freeze([compileStatement<T>(write, dialect)]);
}

function sameColumns(left: readonly Assignment[], right: readonly Assignment[]): boolean {
  return (
    left.length === right.length &&
    left.every((item, index) => item.column === right[index]!.column)
  );
}

function compileStatement<T>(
  write: WriteState,
  dialect: Dialect,
  rows?: readonly (readonly Assignment[])[],
): CompiledWrite<T> {
  const state: State = { dialect, owner: write.owner, parameters: [] };
  const scope = new Map([[write.relation.id, ""]]);
  const name =
    (write.target.schema === undefined ? "" : dialect.quote(write.target.schema) + ".") +
    dialect.quote(write.target.name);
  let sql: string;
  if (rows) {
    const first = rows[0]!;
    if (!first.length)
      sql =
        dialect.name === "postgres"
          ? `INSERT INTO ${name} DEFAULT VALUES`
          : `INSERT INTO ${name} () VALUES ()`;
    else
      sql =
        `INSERT INTO ${name} (${first.map((item) => dialect.quote(item.column)).join(", ")}) VALUES ` +
        rows
          .map(
            (row) =>
              "(" +
              row
                .map((item) => {
                  requireRowExpression(item.value, "INSERT VALUES");
                  return renderNode(item.value, new Map(), state);
                })
                .join(", ") +
              ")",
          )
          .join(", ");
  } else if (write.operation === "update") {
    sql =
      `UPDATE ${name} SET ` +
      write.assignments
        .map((item) => {
          requireRowExpression(item.value, "SET");
          return dialect.quote(item.column) + " = " + renderNode(item.value, scope, state);
        })
        .join(", ");
  } else sql = `DELETE FROM ${name}`;
  if (write.where) {
    requireRowExpression(write.where, "Write WHERE");
    sql += " WHERE " + renderNode(write.where, scope, state, "predicate");
  }
  if (write.projection.length)
    sql +=
      " RETURNING " +
      write.projection
        .map((slot) => {
          requireRowExpression(slot.node, "RETURNING");
          return renderNode(slot.node, scope, state) + " AS " + dialect.quote(slot.sqlAlias);
        })
        .join(", ");
  return Object.freeze({
    sql,
    parameters: Object.freeze(state.parameters),
    returnsRows: write.projection.length > 0,
    decode: projectionDecoder<T>(write.projection),
  });
}
