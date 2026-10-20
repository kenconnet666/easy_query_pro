package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.expression.sql.expression.EntityQuerySQLExpression;
import com.easy.query.core.expression.sql.expression.factory.ExpressionFactory;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.mysql.expression.MySQLExpressionFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlProExpressionFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.spi.ProQuerySQLContext;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MySqlQueryContextTest {
    @Test
    void clonedCteKeepsTheOuterShardFlagAndOriginalParameters() {
        var query = TestClients.offline(new MySQLDatabaseConfiguration(), services -> {
            MySqlExtensions.configure(services);
            MySqlExtensions.configureQueryScope(services);
        });
        var observed = new ArrayList<Boolean>();
        var source = query.getEasyQueryClient().queryable(TestProduct.class).where(p -> p.eq("id", 7));
        source.select(columns -> {
            columns.columnAll();
            columns.getSQLNative().sqlNativeSegment("{0}", context -> context.sql(sql -> {
                observed.add(ProQuerySQLContext.isSharding(sql));
                return "1";
            }));
        });
        source.cloneQueryable().toSQL();
        assertEquals(List.of(false), observed);
        observed.clear();
        var copy = source.toCteAs("context_source").cloneQueryable().where(p -> p.eq("name", "outer"));
        copy.getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertFalse(source.getSQLEntityExpressionBuilder().getExpressionContext().isSharding());
        var result = copy.toSQLResult();
        assertEquals(List.of(true), observed);
        assertEquals(List.of(7, "outer"), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertTrue(result.getSQL().startsWith("WITH "), result.getSQL());
        assertEquals(result.getSQL(), copy.cloneQueryable().toSQL());
    }

    @Test
    void anApplicationFactoryRegisteredAfterProRemainsInstalledThroughCloneAndUnion() {
        var factory = new ApplicationFactory();
        var query = TestClients.offline(new MySQLDatabaseConfiguration(), services -> {
            MySqlExtensions.configure(services);
            MySqlExtensions.configureQueryScope(services);
            services.addService(ExpressionFactory.class, factory);
        });
        assertSame(factory, query.getRuntimeContext().getExpressionFactory());
        var first = query.getEasyQueryClient().queryable(TestProduct.class).where(p -> p.eq("id", 1));
        var second = query.getEasyQueryClient().queryable(TestProduct.class).where(p -> p.eq("id", 2));
        var combined = first.unionAll(second).toCteAs("application_union");
        assertEquals(combined.toSQL(), combined.cloneQueryable().toSQL());
        assertTrue(factory.queries >= 4);
    }

    @Test
    void ordinaryInstallationPreservesAnExistingApplicationFactory() {
        var application = new MySQLExpressionFactory();
        var query = TestClients.offline(new MySQLDatabaseConfiguration(), services -> {
            services.addService(ExpressionFactory.class, application);
            MySqlExtensions.configure(services);
        });
        assertSame(application, query.getRuntimeContext().getExpressionFactory());
    }

    public static class ApplicationFactory extends MySqlProExpressionFactory {
        private int queries;

        @Override
        public EntityQuerySQLExpression createEasyQuerySQLExpression(EntitySQLExpressionMetadata metadata) {
            queries++;
            return super.createEasyQuerySQLExpression(metadata);
        }
    }
}
