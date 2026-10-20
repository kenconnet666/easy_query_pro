package org.dpdns.zerodep.easyquery.pro.dsl.pgsql;

import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.core.expression.sql.expression.factory.ExpressionFactory;
import com.easy.query.pgsql.config.PgSQLSQLKeyword;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.ExtensionInstaller;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.NullSafeComparison;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.TemporalTruncation;
import org.dpdns.zerodep.easyquery.pro.dsl.internal.JsonPathAccess;
import com.easy.query.core.configuration.QueryConfiguration;
import com.easy.query.core.basic.jdbc.types.JdbcTypeHandlerManager;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayTypeHandler;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayValue;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.internal.PgScalarMetadataManager;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgIntervalTypeHandler;
import com.easy.query.core.metadata.EntityMetadataManager;
import com.easy.query.core.sql.JdbcSQLExecutor;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.jdbc.PgJdbcSQLExecutor;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.jdbc.PgJdbcTypeHandlerManager;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.PgJsonColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.json.PgJsonbColumnConverter;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTextSearchTypeHandlers;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsQuery;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsVector;

public final class PgSqlExtensions {
    private PgSqlExtensions() {
    }

    public static void configure(ServiceCollection services) {
        ExtensionInstaller.install(services, SqlDialect.POSTGRESQL, PgSQLSQLKeyword.class,
                new NullSafeComparison("({0} IS NOT DISTINCT FROM {1})", "({0} IS DISTINCT FROM {1})"));
        services.addService(TemporalTruncation.class, new PgSqlTemporalTruncation());
        services.addService(JsonPathAccess.class, new PgSqlJsonPathAccess());
        services.addService(EntityMetadataManager.class, PgScalarMetadataManager.class);
        // 时间映射必须先于实体元数据缓存安装；应用自己的服务应在本方法之后覆写。
        services.addService(JdbcTypeHandlerManager.class, PgJdbcTypeHandlerManager.class);
        services.addService(JdbcSQLExecutor.class, PgJdbcSQLExecutor.class);
    }

    public static void configureMappings(QueryConfiguration configuration) {
        configuration.applyColumnValueSQLConverter(new PgJsonColumnConverter());
        configuration.applyColumnValueSQLConverter(new PgJsonbColumnConverter());
    }

    /**
     * 显式安装全文等查询级功能需要的根上下文；普通 configure 保留已有 ExpressionFactory。
     * 自定义工厂应在本方法之后注册，并继承 PgSqlProExpressionFactory 或保留同等传播。
     */
    public static void configureQueryScope(ServiceCollection services) {
        services.addService(ExpressionFactory.class, PgSqlProExpressionFactory.class);
    }

    public static void configureTypeHandlers(JdbcTypeHandlerManager handlers) {
        handlers.appendHandler(PgArrayValue.class, new PgArrayTypeHandler(), false);
        var interval = new PgIntervalTypeHandler();
        handlers.appendHandler(PgInterval.class, interval, false);
        handlers.appendHandler(PgInterval.Finite.class, interval, false);
        handlers.appendHandler(PgInterval.Infinity.class, interval, false);
        handlers.appendHandler(PgTsVector.class, new PgTextSearchTypeHandlers.Vector(), false);
        handlers.appendHandler(PgTsQuery.class, new PgTextSearchTypeHandlers.Query(), false);
    }
}
