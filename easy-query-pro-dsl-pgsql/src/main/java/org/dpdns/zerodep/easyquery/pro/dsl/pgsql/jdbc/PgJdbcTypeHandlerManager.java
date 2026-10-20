package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.jdbc;

import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.executor.internal.props.JdbcProperty;
import com.easy.query.core.basic.jdbc.types.EasyJdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.types.EasyParameter;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.LocalDateTimeTypeHandler;
import com.easy.query.pgsql.types.PgSQLUUIDTypeHandler;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 保留 PostgreSQL 原有 UUID 映射，并以 JDBC 4.2 传递无时区时间的日历字段。
 * 应用可在配置完成、首次实体元数据加载之前继续注册自己的 handler，既有 replace 规则不变。
 */
public class PgJdbcTypeHandlerManager extends EasyJdbcTypeHandlerManager {
    private final JdbcTypeHandler dateTime = new DateTime();

    public PgJdbcTypeHandlerManager() {
        appendHandler(UUID.class, PgSQLUUIDTypeHandler.INSTANCE, true);
        appendHandler(LocalDateTime.class, dateTime, true);
    }

    @Override
    public JdbcTypeHandler getHandlerByHandlerClass(Class<?> type) {
        // 上游按 handler.getClass 精确查找；替换默认值后仍保留实体显式引用旧类名的配置。
        return type == LocalDateTimeTypeHandler.class ? dateTime : super.getHandlerByHandlerClass(type);
    }

    private static final class DateTime extends LocalDateTimeTypeHandler {
        @Override
        public Object getValue(JdbcProperty property, StreamResultSet result) throws SQLException {
            int column = property.getJdbcIndex();
            if (PgTemporalResultSet.isLocalTimestamp(result.getMetaData(), column))
                return result.getObject(column, LocalDateTime.class);
            // 此修复只针对 timestamp without time zone；不顺带改变 DATE/timestamptz 的已有转换。
            return super.getValue(property, result);
        }

        @Override
        public void setParameter(EasyParameter parameter) throws SQLException {
            // Timestamp.valueOf 会按 JVM 时区把不存在的本地时间归一化，setObject 保留原字段。
            parameter.getPs().setObject(parameter.getIndex(), (LocalDateTime) parameter.getValue());
        }
    }
}
