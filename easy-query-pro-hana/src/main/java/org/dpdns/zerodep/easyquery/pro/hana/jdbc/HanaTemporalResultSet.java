package org.dpdns.zerodep.easyquery.pro.hana.jdbc;

import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.JdbcShardingStreamResultSet;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.ShardingStreamResultSet;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * 在 JDBC 结果进入上游分片排序/内存合并之前，保留无时区 DATE/TIME/TIMESTAMP 的日历字段。
 *
 * <p>结果以 java.time 值传递，避免先受 JVM 默认时区规范化。UTC Calendar 只是稳定提取字段的手段，
 * 不表示业务无时区时间具有 UTC 含义；Instant 的时间点映射由独立 handler 约定。
 *
 * <p>raw 只用于带 Calendar 的读取，游标前进和关闭仍由 source 负责，不能重复管理底层资源。
 */
class HanaTemporalResultSet implements StreamResultSet {
    private final StreamResultSet source;
    private final ResultSet raw;
    private final int[] types;

    private HanaTemporalResultSet(StreamResultSet source, ResultSet raw, int[] types) {
        this.source = source;
        this.raw = raw;
        this.types = types;
    }

    /**
     * 仅包装包含时间列的结果，并保留上游 JDBC 分片结果的标识接口。
     */
    static StreamResultSet wrap(StreamResultSet source, ResultSet raw) throws SQLException {
        var metadata = source.getMetaData();
        int[] types = new int[metadata.getColumnCount()];
        boolean temporal = false;
        for (int i = 0; i < types.length; i++) {
            types[i] = metadata.getColumnType(i + 1);
            temporal |= temporal(types[i]);
        }
        if (!temporal) return source;
        return source instanceof JdbcShardingStreamResultSet ? new Sharding(source, raw, types) : new HanaTemporalResultSet(source, raw, types);
    }

    private static final class Sharding extends HanaTemporalResultSet implements JdbcShardingStreamResultSet, ShardingStreamResultSet {
        private Sharding(StreamResultSet source, ResultSet raw, int[] types) {
            super(source, raw, types);
        }
    }

    private static boolean temporal(int type) {
        return type == Types.DATE || type == Types.TIME || type == Types.TIMESTAMP;
    }

    @Override
    public Object getObject(int column) throws SQLException {
        int type = types[column - 1];
        if (!temporal(type)) return source.getObject(column);
        var calendar = HanaTemporalHandlers.utcCalendar();
        var result = raw;
        if (type == Types.TIME) {
            var time = result.getTime(column, calendar);
            return time == null ? null : Instant.ofEpochMilli(time.getTime()).atOffset(ZoneOffset.UTC).toLocalTime();
        }
        var timestamp = result.getTimestamp(column, calendar);
        if (timestamp == null) return null;
        var value = HanaTemporalHandlers.fromTimestamp(timestamp, calendar);
        return type == Types.DATE ? value.toLocalDate() : value;
    }

    @Override
    public <T> T getObject(int column, Class<T> type) throws SQLException {
        if (temporal(types[column - 1])) {
            if (type == String.class) return type.cast(getString(column));
            if (type == Timestamp.class) return type.cast(getTimestamp(column));
            if (type == Date.class) return type.cast(getDate(column));
            if (type == Time.class) return type.cast(getTime(column));
            Object value = getObject(column);
            if (value == null || type.isInstance(value)) return type.cast(value);
        }
        return source.getObject(column, type);
    }

    @Override
    public String getString(int column) throws SQLException {
        if (!temporal(types[column - 1])) return source.getString(column);
        Object value = getObject(column);
        return value == null ? null : value.toString();
    }

    @Override
    public ResultSet getResultSet() {
        return source.getResultSet();
    }

    @Override
    public boolean hasElement() {
        return source.hasElement();
    }

    @Override
    public boolean skipFirst() {
        return source.skipFirst();
    }

    @Override
    public boolean next() throws SQLException {
        return source.next();
    }

    @Override
    public boolean wasNull() throws SQLException {
        return source.wasNull();
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return source.getMetaData();
    }

    @Override
    public SQLXML getSQLXML(int column) throws SQLException {
        return source.getSQLXML(column);
    }

    @Override
    public Timestamp getTimestamp(int column) throws SQLException {
        if (!temporal(types[column - 1])) return source.getTimestamp(column);
        var value = localDateTime(column);
        if (value == null) return null;
        var timestamp = Timestamp.valueOf(value);
        if (!timestamp.toLocalDateTime().equals(value)) throw legacyDateLoss(column);
        return timestamp;
    }

    @Override
    public Time getTime(int column) throws SQLException {
        if (!temporal(types[column - 1])) return source.getTime(column);
        var value = localDateTime(column);
        if (value == null) return null;
        var time = Time.valueOf(value.toLocalTime());
        if (!time.toLocalTime().equals(value.toLocalTime().withNano(0))) throw legacyDateLoss(column);
        return time;
    }

    @Override
    public Date getDate(int column) throws SQLException {
        if (!temporal(types[column - 1])) return source.getDate(column);
        var value = localDateTime(column);
        if (value == null) return null;
        var date = Date.valueOf(value.toLocalDate());
        if (!date.toLocalDate().equals(value.toLocalDate())) throw legacyDateLoss(column);
        return date;
    }

    private LocalDateTime localDateTime(int column) throws SQLException {
        Object value = getObject(column);
        if (value == null) return null;
        if (value instanceof LocalDate date) return date.atStartOfDay();
        if (value instanceof LocalTime time) return time.atDate(LocalDate.of(1970, 1, 1));
        return (LocalDateTime) value;
    }

    /**
     * 旧 java.sql 日期类型会经过 JVM 时区；显式请求这些类型时，若转换改变日历字段必须报错，
     * 不能静默把不存在的本地日期/时间规范化为另一值。
     */
    private static SQLException legacyDateLoss(int column) {
        return new SQLException("HANA temporal value at column " + column + " cannot be represented by java.sql temporal types in the JVM timezone; use a java.time projection", "22008");
    }

    @Override
    public short getShort(int column) throws SQLException {
        return source.getShort(column);
    }

    @Override
    public long getLong(int column) throws SQLException {
        return source.getLong(column);
    }

    @Override
    public int getInt(int column) throws SQLException {
        return source.getInt(column);
    }

    @Override
    public float getFloat(int column) throws SQLException {
        return source.getFloat(column);
    }

    @Override
    public double getDouble(int column) throws SQLException {
        return source.getDouble(column);
    }

    @Override
    public Clob getClob(int column) throws SQLException {
        return source.getClob(column);
    }

    @Override
    public byte getByte(int column) throws SQLException {
        return source.getByte(column);
    }

    @Override
    public byte[] getBytes(int column) throws SQLException {
        return source.getBytes(column);
    }

    @Override
    public boolean getBoolean(int column) throws SQLException {
        return source.getBoolean(column);
    }

    @Override
    public Blob getBlob(int column) throws SQLException {
        return source.getBlob(column);
    }

    @Override
    public BigDecimal getBigDecimal(int column) throws SQLException {
        return source.getBigDecimal(column);
    }

    @Override
    public void close() throws SQLException {
        source.close();
    }
}
