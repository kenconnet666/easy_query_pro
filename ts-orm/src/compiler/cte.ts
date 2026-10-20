import { DerivedSource, type QueryNode, type Selection } from "../query/model.js";
import { Table } from "../schema/table.js";

/**
 * 只收集依赖，不渲染表达式或绑定参数。定义按依赖后序排在统一 WITH 中，
 * 使 HANA/计数包装也不依赖嵌套 WITH 的支持。相同定义多次引用只声明一次。
 */
export function collectCtes(root: QueryNode): readonly DerivedSource<Selection>[] {
  const visited = new Set<QueryNode>();
  const visiting = new Set<QueryNode>();
  const definitions: DerivedSource<Selection>[] = [];
  const names = new Map<string, DerivedSource<Selection>>();
  const tables = new Set<string>();
  function walk(query: QueryNode): void {
    if (query.owner !== root.owner) throw new TypeError("A source cannot cross database clients");
    if (visiting.has(query)) throw new TypeError("Recursive CTEs are not supported");
    if (visited.has(query)) return;
    visiting.add(query);
    if (query.kind === "set") {
      walk(query.left);
      walk(query.right);
    } else {
      for (const { source } of query.relations) {
        if (source instanceof Table) {
          if (source.schema === undefined) tables.add(source.name.toLowerCase());
          continue;
        }
        if (source.cteName === undefined) {
          walk(source.query);
          continue;
        }
        const key = source.cteName.toLowerCase();
        const previous = names.get(key);
        if (previous && previous !== source)
          throw new TypeError(`CTE name refers to different definitions: ${source.cteName}`);
        if (!previous) {
          names.set(key, source);
          walk(source.query);
          definitions.push(source);
        } else if (visiting.has(source.query)) {
          throw new TypeError("Recursive CTEs are not supported");
        }
      }
    }
    visiting.delete(query);
    visited.add(query);
  }
  walk(root);
  // MySQL 的 CTE 名字可遮蔽实体表，引用符也不会消除该歧义；共同层在执行前拒绝。
  for (const name of names.keys()) {
    if (tables.has(name)) throw new TypeError(`CTE name shadows a referenced table: ${name}`);
  }
  return definitions;
}
