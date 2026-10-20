/** SQL 的字段和别名是字符串；symbol 不会进入 Object.entries，不能承诺输出不存在的字段。 */
export type NoSymbolKeys<T> = { [K in Extract<keyof T, symbol>]: never };

/** 在 schema、投影和来源共同的对象边界验证一次，内部继续使用普通条目遍历。 */
export function recordEntries<T>(
  record: Readonly<Record<string, T>>,
  context: string,
): [string, T][] {
  for (const key of Reflect.ownKeys(record)) {
    if (typeof key !== "string" || !Object.prototype.propertyIsEnumerable.call(record, key))
      throw new TypeError(`${context} requires enumerable string keys`);
  }
  return Object.entries(record);
}
