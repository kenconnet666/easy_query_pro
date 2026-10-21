package org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal;

import com.easy.query.core.basic.jdbc.executor.internal.props.JdbcProperty;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.types.EasyParameter;
import com.easy.query.core.basic.jdbc.types.handler.ObjectTypeHandler;
import com.mysql.cj.jdbc.ServerPreparedStatement;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;

/**
 * 复用 Connector/J 的 Duration 读取，并按预处理模式选择正确的 TIME 参数表示。
 * 服务端 prepare 保留二进制 TIME 类型信息；客户端模式使用规范时长文本。
 * 通过 JDBC Wrapper 判断真实 statement 类型以兼容连接池，不另借连接或改变原事务。
 */
public final class MySqlDurationTypeHandler extends ObjectTypeHandler {
    private static final MySqlDurationType NATIVE_CAPACITY = new MySqlDurationType(6);

    @Override
    public Object getValue(JdbcProperty property, StreamResultSet result) throws SQLException {
        return result.getObject(property.getJdbcIndex(), Duration.class);
    }

    @Override
    public void setParameter(EasyParameter parameter) throws SQLException {
        Duration value = NATIVE_CAPACITY.prepareValue((Duration) parameter.getValue());
        // 保留服务端的 TIME 参数类型，连接池包装通过 JDBC Wrapper 暴露判断能力。
        if (value == null || parameter.getPs().isWrapperFor(ServerPreparedStatement.class)) {
            super.setParameter(parameter);
            return;
        }
        // 客户端编码器可能给仅含秒的 Duration 留下尾部 S，严格 INSERT 会拒绝，故采用规范时长文本。
        Duration magnitude = value.abs();
        String time = String.format(Locale.ROOT, "%s%02d:%02d:%02d.%06d", value.isNegative() ? "-" : "",
                magnitude.toHours(), magnitude.toMinutes() % 60, magnitude.getSeconds() % 60, magnitude.getNano() / 1000);
        parameter.getPs().setString(parameter.getIndex(), time);
    }
}
