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
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.api.select.ClientQueryable;
import java.util.Arrays;
import com.easy.query.core.common.ToSQLResult;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MySqlQueryContextTest {
    @Test
    void clonedCteKeepsTheOuterShardFlagAndOriginalParameters() {
        DefaultEasyEntityQuery query = TestClients.offline(new MySQLDatabaseConfiguration(), services -> {
            MySqlExtensions.configure(services);
            MySqlExtensions.configureQueryScope(services);
        });
        ArrayList<Boolean> observed = new ArrayList<Boolean>();
        ClientQueryable<TestProduct> source = query.getEasyQueryClient().queryable(TestProduct.class).where(p -> p.eq("id", 7));
        source.select(columns -> {
            columns.columnAll();
            columns.getSQLNative().sqlNativeSegment("{0}", context -> context.sql(sql -> {
                observed.add(ProQuerySQLContext.isSharding(sql));
                return "1";
            }));
        });
        source.cloneQueryable().toSQL();
        assertEquals(Arrays.asList(false), observed);
        observed.clear();
        ClientQueryable<TestProduct> copy = source.toCteAs("context_source").cloneQueryable().where(p -> p.eq("name", "outer"));
        copy.getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertFalse(source.getSQLEntityExpressionBuilder().getExpressionContext().isSharding());
        ToSQLResult result = copy.toSQLResult();
        assertEquals(Arrays.asList(true), observed);
        assertEquals(Arrays.asList(7, "outer"), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertTrue(result.getSQL().startsWith("WITH "), result.getSQL());
        assertEquals(result.getSQL(), copy.cloneQueryable().toSQL());
    }

    @Test
    void anApplicationFactoryRegisteredAfterProRemainsInstalledThroughCloneAndUnion() {
        MySqlQueryContextTest.ApplicationFactory factory = new ApplicationFactory();
        DefaultEasyEntityQuery query = TestClients.offline(new MySQLDatabaseConfiguration(), services -> {
            MySqlExtensions.configure(services);
            MySqlExtensions.configureQueryScope(services);
            services.addService(ExpressionFactory.class, factory);
        });
        assertSame(factory, query.getRuntimeContext().getExpressionFactory());
        ClientQueryable<TestProduct> first = query.getEasyQueryClient().queryable(TestProduct.class).where(p -> p.eq("id", 1));
        ClientQueryable<TestProduct> second = query.getEasyQueryClient().queryable(TestProduct.class).where(p -> p.eq("id", 2));
        ClientQueryable<TestProduct> combined = first.unionAll(second).toCteAs("application_union");
        assertEquals(combined.toSQL(), combined.cloneQueryable().toSQL());
        assertTrue(factory.queries >= 4);
    }

    @Test
    void ordinaryInstallationPreservesAnExistingApplicationFactory() {
        MySQLExpressionFactory application = new MySQLExpressionFactory();
        DefaultEasyEntityQuery query = TestClients.offline(new MySQLDatabaseConfiguration(), services -> {
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
