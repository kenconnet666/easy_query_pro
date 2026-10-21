package org.dpdns.zerodep.easyquery.pro.dsl.mysql.jdbc;

import com.easy.query.core.basic.jdbc.types.EasyJdbcTypeHandlerManager;
import com.easy.query.core.basic.jdbc.types.EasyParameter;
import com.easy.query.core.basic.jdbc.types.handler.FloatTypeHandler;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;
import com.easy.query.core.exception.EasyQueryInvalidOperationException;

import java.sql.SQLException;

/**
 * 在默认 Float 绑定处保留全部有效位；应用随后注册的处理器仍按原 replace 规则生效。
 * 本类不修饰结果 SQL，MySQL FLOAT 文本结果本身的精度限制仍由连接协议决定。
 */
public class MySqlJdbcTypeHandlerManager extends EasyJdbcTypeHandlerManager {
    private final JdbcTypeHandler real = new Real();

    public MySqlJdbcTypeHandlerManager() {
        appendHandler(Float.class, real, true);
        appendHandler(float.class, real, true);
    }

    @Override
    public JdbcTypeHandler getHandlerByHandlerClass(Class<?> type) {
        try {
            // 应用可能注册了默认类的新实例；先尊重上游的精确类查找，再补兼容别名。
            return super.getHandlerByHandlerClass(type);
        } catch (EasyQueryInvalidOperationException missing) {
            if (type == FloatTypeHandler.class) return real;
            throw missing;
        }
    }

    private static final class Real extends FloatTypeHandler {
        @Override
        public void setParameter(EasyParameter parameter) throws SQLException {
            // Float.toString(MAX_VALUE) 向上舍入后会被 MySQL 文本协议判为越界。
            // binary32 到 binary64 是精确提升；沿 JDBC setDouble，不拼 SQL 或自行序列化参数。
            parameter.getPs().setDouble(parameter.getIndex(), ((Float) parameter.getValue()).doubleValue());
        }
    }
}
