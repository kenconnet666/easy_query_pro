package org.dpdns.zerodep.easyquery.pro.dsl.mysql;

import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.core.configuration.QueryConfiguration;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;
import com.easy.query.core.expression.sql.expression.factory.ExpressionFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.jdbc.ExactBigIntegerTypeHandler;

import java.math.BigInteger;

import com.easy.query.mysql.config.MySQLSQLKeyword;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExtensionInstaller;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.NullSafeComparison;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.JsonPathAccess;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.json.MySqlJsonColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.MySqlDurationTypeHandler;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.MySqlDurationMetadataManager;
import com.easy.query.core.metadata.EntityMetadataManager;

import java.time.Duration;

public final class MySqlExtensions {
    private MySqlExtensions() {
    }

    public static void configure(ServiceCollection services) {
        ExtensionInstaller.install(services, SqlDialect.MYSQL, MySQLSQLKeyword.class,
                new NullSafeComparison("({0} <=> {1})", "(NOT ({0} <=> {1}))"));
        services.addService(TemporalTruncation.class, new MySqlTemporalTruncation());
        services.addService(JsonPathAccess.class, new MySqlJsonPathAccess());
        services.addService(EntityMetadataManager.class, MySqlDurationMetadataManager.class);
    }

    /**
     * 显式安装依赖完整查询来源的渲染上下文，供全文等操作使用。
     *
     * <p>普通 configure 不改用户原有 ExpressionFactory。需要本能力时先调用 configure，再调用本方法；
     * 自定义工厂应在之后注册，并继承 MySqlProExpressionFactory 或保留同等根上下文传播。
     */
    public static void configureQueryScope(ServiceCollection services) {
        services.addService(ExpressionFactory.class, MySqlProExpressionFactory.class);
    }

    public static void configureMappings(QueryConfiguration configuration) {
        configuration.applyColumnValueSQLConverter(new MySqlJsonColumnConverter());
    }

    public static void configureTypeHandlers(JdbcTypeHandlerManager handlers) {
        handlers.appendHandler(BigInteger.class, new ExactBigIntegerTypeHandler(), false);
        handlers.appendHandler(Duration.class, new MySqlDurationTypeHandler(), false);
    }
}
