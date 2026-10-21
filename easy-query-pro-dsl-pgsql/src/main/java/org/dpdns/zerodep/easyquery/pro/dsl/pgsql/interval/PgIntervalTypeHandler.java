package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval;

import com.easy.query.core.basic.jdbc.executor.internal.props.JdbcProperty;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.types.EasyParameter;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;
import org.postgresql.util.PGobject;

import java.sql.SQLException;

/**
 * 精确读取服务器 interval 表示，支持已验证的文本样式及强制二进制传输。
 * codec 面向服务器输出，不承担解析任意用户 interval 文本的职责；绑定仍使用 pgJDBC 的 interval 类型。
 */
public final class PgIntervalTypeHandler implements JdbcTypeHandler {
    @Override
    public Object getValue(JdbcProperty property, StreamResultSet result) throws SQLException {
        // PGInterval 会丢失部分 sql_standard 字段，也不能可靠解析超过 Integer.MAX_VALUE 小时的合法值。
        String text = result.getString(property.getJdbcIndex());
        if (text != null) return PgIntervalCodec.decode(text);
        if (result.wasNull()) return null;
        // 强制二进制传输时 getString 可能为空而 SQL 值并非 NULL，需再读取精确二进制分量。
        return PgIntervalCodec.decodeBinary(result.getBytes(property.getJdbcIndex()));
    }

    @Override
    public void setParameter(EasyParameter parameter) throws SQLException {
        PGobject value = new PGobject();
        value.setType("interval");
        value.setValue(PgIntervalCodec.encode((PgInterval) parameter.getValue()));
        parameter.getPs().setObject(parameter.getIndex(), value);
    }
}
