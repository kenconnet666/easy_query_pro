import { Expression } from "../expression/expression.js";
import { recordEntries } from "../internal/record.js";
import type { ProjectionSlot, Selection } from "../query/model.js";
import { validateName } from "../schema/table.js";

/** SELECT 与 RETURNING 共享列顺序及 decoder，不为写入结果维护另一套名字映射。 */
export function createProjection(values: Selection): readonly ProjectionSlot[] {
  const projection = recordEntries(values, "Query projection").map(([key, expression], index) => {
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
  return Object.freeze(projection);
}

export function projectionDecoder<T>(
  projection: readonly ProjectionSlot[],
): (row: Readonly<Record<string, unknown>>) => T {
  return (row) => {
    const result: Record<string, unknown> = {};
    for (const slot of projection) {
      let value: unknown;
      try {
        value = slot.type.decode(row[slot.sqlAlias]);
      } catch (cause) {
        throw new TypeError(`Cannot decode projection "${slot.key}" as ${slot.type.storage.kind}`, {
          cause,
        });
      }
      Object.defineProperty(result, slot.key, {
        value,
        enumerable: true,
        writable: true,
        configurable: true,
      });
    }
    return result as T;
  };
}
