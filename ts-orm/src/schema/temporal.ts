/**
 * 共同日期域使用字段值，不经过 Date 或进程时区；非法闰日不能被 JS 自动滚入下个月。
 * 年份范围取三库 DATE/DATETIME 的交集，数据库更宽范围留给显式原生类型。
 */
export function date(value: unknown): string {
  if (typeof value !== "string") throw new TypeError("DATE requires an ISO YYYY-MM-DD string");
  const fields = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!fields)
    throw new TypeError(
      "DATE requires an ISO YYYY-MM-DD string; configure PostgreSQL DateStyle as ISO",
    );
  const year = Number(fields[1]);
  const month = Number(fields[2]);
  const day = Number(fields[3]);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (year < 1000 || year > 9999 || month < 1 || month > 12 || day < 1 || day > days[month - 1]!)
    throw new RangeError("DATE is outside the portable Gregorian range 1000-01-01..9999-12-31");
  return value;
}

/** 结果固定保留六位小数秒；驱动补齐至 7/9 位时，超出微秒部分必须全零才能无损读取。 */
function localDateTime(value: unknown, reading: boolean): string {
  if (typeof value !== "string") throw new TypeError("Local date-time requires a string, not Date");
  const fields = /^(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?$/.exec(value);
  if (!fields)
    throw new TypeError(
      "Local date-time requires ISO fields without a timezone; configure PostgreSQL DateStyle as ISO",
    );
  date(fields[1]);
  if (Number(fields[2]) > 23 || Number(fields[3]) > 59 || Number(fields[4]) > 59)
    throw new RangeError("Local date-time has invalid clock fields");
  const fractional = fields[5] ?? "";
  if (fractional.length > 6 && (!reading || /[1-9]/.test(fractional.slice(6))))
    throw new RangeError("Local date-time cannot discard precision beyond microseconds");
  return `${fields[1]} ${fields[2]}:${fields[3]}:${fields[4]}.${fractional.slice(0, 6).padEnd(6, "0")}`;
}

export function encodeLocalDateTime(value: unknown): string {
  return localDateTime(value, false);
}
export function decodeLocalDateTime(value: unknown): string {
  return localDateTime(value, true);
}
