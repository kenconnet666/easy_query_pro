package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.executor.internal.props.JdbcProperty;
import com.easy.query.core.basic.jdbc.types.EasyParameter;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.DateTimeException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 不经 JVM 默认时区转换无时区的 SQL 日期；Instant 单独约定为 UTC。
 */
final class HanaTemporalHandlers {
    private HanaTemporalHandlers() {
    }

    static final class Date extends Temporal {
        @Override
        Object parse(String text) {
            return dateTime(text).toLocalDate();
        }

        @Override
        LocalDateTime local(Object value) {
            return ((LocalDate) value).atStartOfDay();
        }
    }

    static final class Time extends Temporal {
        @Override
        Object parse(String text) {
            return LocalTime.parse(text);
        }

        @Override
        LocalDateTime local(Object value) {
            var time = (LocalTime) value;
            return time.atDate(LocalDate.of(1970, 1, 1));
        }
    }

    static final class DateTime extends Temporal {
        @Override
        Object parse(String text) {
            return dateTime(text);
        }

        @Override
        LocalDateTime local(Object value) {
            return (LocalDateTime) value;
        }
    }

    static final class UtcInstant extends Temporal {
        @Override
        Object parse(String text) {
            return dateTime(text).toInstant(ZoneOffset.UTC);
        }

        @Override
        LocalDateTime local(Object value) {
            return LocalDateTime.ofInstant((Instant) value, ZoneOffset.UTC);
        }
    }

    private abstract static class Temporal implements JdbcTypeHandler {
        abstract Object parse(String text);

        abstract LocalDateTime local(Object value);

        @Override
        public Object getValue(JdbcProperty property, StreamResultSet result) throws SQLException {
            // 直接查询返回 ISO 文本；上游内存聚合会返回在 JDBC 边界保存的 java.time 值。
            Object value = result.getObject(property.getJdbcIndex(), String.class);
            if (value == null) return null;
            String text = value.toString();
            try {
                return parse(text);
            } catch (DateTimeParseException failure) {
                throw new SQLException("Invalid HANA temporal value at column " + property.getJdbcIndex(), "22007", failure);
            }
        }

        @Override
        public void setParameter(EasyParameter parameter) throws SQLException {
            var value = parameter.getValue();
            if (value == null) {
                parameter.getPs().setTimestamp(parameter.getIndex(), null);
                return;
            }
            var local = local(value);
            if (local.getYear() < 1 || local.getYear() > 9999)
                throw new SQLException("HANA temporal year must be between 1 and 9999", "22008");
            if (value instanceof LocalTime && local.getNano() != 0)
                throw new SQLException("HANA TIME stores whole seconds", "22008");
            if (local.getNano() % 100 != 0)
                throw new SQLException("HANA TIMESTAMP precision is 100 nanoseconds", "22008");
            var calendar = utcCalendar();
            calendar.clear();
            calendar.set(local.getYear(), local.getMonthValue() - 1, local.getDayOfMonth(), local.getHour(), local.getMinute(), local.getSecond());
            Timestamp timestamp;
            try {
                timestamp = new Timestamp(calendar.getTimeInMillis());
            } catch (IllegalArgumentException failure) {
                throw new SQLException("Date does not exist in the HANA calendar: " + local.toLocalDate(), "22008", failure);
            }
            timestamp.setNanos(local.getNano());
            parameter.getPs().setTimestamp(parameter.getIndex(), timestamp, calendar);
        }
    }

    static GregorianCalendar utcCalendar() {
        var calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        // HANA SQL 同样使用 1582 年切历；禁止 lenient 模式把缺失的 10 天归一化成别的日期。
        calendar.setLenient(false);
        return calendar;
    }

    static LocalDateTime fromTimestamp(Timestamp timestamp, Calendar calendar) throws SQLException {
        if (timestamp == null) return null;
        calendar.setTimeInMillis(timestamp.getTime());
        try {
            return LocalDateTime.of(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH),
                    calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND), timestamp.getNanos());
        } catch (DateTimeException failure) {
            throw new SQLException("HANA calendar date cannot be represented by java.time (for example, a Julian-only leap day)", "22008", failure);
        }
    }

    private static LocalDateTime dateTime(String text) {
        return text.length() == 10 ? LocalDate.parse(text).atStartOfDay() : LocalDateTime.parse(text.replace(' ', 'T'));
    }
}
