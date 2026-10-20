package org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search;

import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.executor.internal.props.JdbcProperty;
import com.easy.query.core.basic.jdbc.types.EasyParameter;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;
import org.postgresql.util.PGobject;

import java.sql.SQLException;

/**
 * 两种编码值共用绑定/读取协议，具体 handler 类名保持不同，显式 @Column(typeHandler=...) 不会选错类型。
 * 参数以 PGobject 携带实际 OID，编码文本不会作为 SQL 片段拼接。
 */
public final class PgTextSearchTypeHandlers {
    private PgTextSearchTypeHandlers() {
    }

    public static final class Vector extends Handler<PgTsVector> {
        public Vector() {
            super(PgTextSearchType.VECTOR);
        }
    }

    public static final class Query extends Handler<PgTsQuery> {
        public Query() {
            super(PgTextSearchType.QUERY);
        }
    }

    private abstract static class Handler<T> implements JdbcTypeHandler {
        private final PgTextSearchType<T> type;

        private Handler(PgTextSearchType<T> type) {
            this.type = type;
        }

        @Override
        public Object getValue(JdbcProperty property, StreamResultSet result) throws SQLException {
            int index = property.getJdbcIndex();
            if (!type.sqlName().equals(result.getMetaData().getColumnTypeName(index)))
                throw new SQLException("Expected PostgreSQL " + type.sqlName() + " for " + property.getPropertyType().getTypeName());
            // 内存归并缓存的是无类型 JDBC 对象；其 getString 会强转 String，不能用于 PGobject。
            Object value = result.getObject(index);
            if (value == null) {
                if (result.wasNull()) return null;
                throw unreadableValue();
            }
            String encoded;
            if (value instanceof PGobject nativeValue) {
                if (!type.sqlName().equals(nativeValue.getType()))
                    throw new SQLException("Expected PostgreSQL " + type.sqlName() + " object, found " + nativeValue.getType());
                encoded = nativeValue.getValue();
            } else if (value instanceof String text) {
                encoded = text;
            } else {
                throw new SQLException("Unsupported JDBC value for PostgreSQL " + type.sqlName() + ": " + value.getClass().getName());
            }
            // pgJDBC 对未注册 binary codec 的非 NULL PGobject 可返回 null 文本。
            if (encoded == null) throw unreadableValue();
            return type.decode(encoded);
        }

        private SQLException unreadableValue() {
            return new SQLException("Cannot decode non-null PostgreSQL " + type.sqlName()
                    + "; leave this type in text transfer mode instead of forcing binaryTransferEnable");
        }

        @Override
        public void setParameter(EasyParameter parameter) throws SQLException {
            T value = type.javaType().cast(parameter.getValue());
            var encoded = new PGobject();
            encoded.setType(type.sqlName());
            encoded.setValue(value == null ? null : type.encode(value));
            parameter.getPs().setObject(parameter.getIndex(), encoded);
        }
    }
}
