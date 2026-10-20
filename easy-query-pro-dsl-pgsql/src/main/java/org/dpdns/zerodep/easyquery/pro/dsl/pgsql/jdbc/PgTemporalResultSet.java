package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.jdbc;

import com.easy.query.core.basic.jdbc.executor.internal.merge.result.JdbcShardingStreamResultSet;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.ShardingStreamResultSet;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;

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
import java.time.LocalDateTime;

/**
 * 分片 timestamp 字段在进入上游内存结果集之前变成 LocalDateTime，避免先缓存已经丢失 DST gap 的 Timestamp。
 * 直接复用公开 typed getObject；游标、资源关闭和非目标类型均委托 source，保留原有分片标记。
 */
class PgTemporalResultSet implements StreamResultSet {
    private final StreamResultSet source;
    private final boolean[] localTimestamps;

    private PgTemporalResultSet(StreamResultSet source, boolean[] localTimestamps) {
        this.source = source;
        this.localTimestamps = localTimestamps;
    }

    static StreamResultSet wrap(StreamResultSet source) throws SQLException {
        var metadata = source.getMetaData();
        var timestamps = new boolean[metadata.getColumnCount()];
        boolean present = false;
        for (int i = 0; i < timestamps.length; i++) {
            timestamps[i] = isLocalTimestamp(metadata, i + 1);
            present |= timestamps[i];
        }
        if (!present) return source;
        return source instanceof JdbcShardingStreamResultSet
                ? new Sharding(source, timestamps) : new PgTemporalResultSet(source, timestamps);
    }

    static boolean isLocalTimestamp(ResultSetMetaData metadata, int column) throws SQLException {
        // pgJDBC 的 timestamptz 也可能报告 Types.TIMESTAMP，不能只看 JDBC 大类。
        return metadata.getColumnType(column) == Types.TIMESTAMP
                && "timestamp".equalsIgnoreCase(metadata.getColumnTypeName(column));
    }

    private static final class Sharding extends PgTemporalResultSet implements JdbcShardingStreamResultSet, ShardingStreamResultSet {
        private Sharding(StreamResultSet source, boolean[] timestamps) {
            super(source, timestamps);
        }
    }

    @Override
    public Object getObject(int column) throws SQLException {
        return localTimestamps[column - 1] ? source.getObject(column, LocalDateTime.class) : source.getObject(column);
    }

    @Override
    public <T> T getObject(int column, Class<T> type) throws SQLException {
        if (localTimestamps[column - 1] && (type == LocalDateTime.class || type == Object.class))
            return type.cast(source.getObject(column, LocalDateTime.class));
        return source.getObject(column, type);
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
        return source.getTimestamp(column);
    }

    @Override
    public Time getTime(int column) throws SQLException {
        return source.getTime(column);
    }

    @Override
    public String getString(int column) throws SQLException {
        return source.getString(column);
    }

    @Override
    public Date getDate(int column) throws SQLException {
        return source.getDate(column);
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
