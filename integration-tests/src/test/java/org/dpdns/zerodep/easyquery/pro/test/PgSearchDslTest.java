package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.expression.sql.expression.EntityQuerySQLExpression;
import com.easy.query.core.expression.sql.expression.factory.ExpressionFactory;
import com.easy.query.core.expression.sql.expression.impl.EntitySQLExpressionMetadata;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import com.easy.query.pgsql.expression.PostgresSQLExpressionFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlProExpressionFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.query.PgSearch;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.query.PgSearchSpec;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsQuery;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PgSearchDocumentProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchHitProxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.dpdns.zerodep.easyquery.pro.dsl.pgsql.query.PgSearchSpec.Normalization.*;
import static org.dpdns.zerodep.easyquery.pro.dsl.pgsql.query.PgSearchSpec.Ranking.*;

class PgSearchDslTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void eachQueryOccurrenceKeepsItsBindingsAndTheSourceIsCaptured(DecimalDslTest.Backend backend) {
        var client = TestClients.offline(backend.database(), services -> {
            backend.extensions().accept(services);
            if (backend.dialect() == SqlDialect.POSTGRESQL) PgSqlExtensions.configureQueryScope(services);
        });
        PgSqlExtensions.configureTypeHandlers(client.getRuntimeContext().getJdbcTypeHandlerManager());
        var source = client.queryable(PgSearchDocumentProxy.createTable()).where(p -> p.id().gt(2));
        if (backend.dialect() != SqlDialect.POSTGRESQL) {
            assertThrows(IllegalArgumentException.class, () -> PgSearch.from(source,
                    p -> PgSearchSpec.plain("pg_catalog.english", "coffee", p.document())));
            return;
        }
        var search = PgSearch.from(source, p -> PgSearchSpec.web("pg_catalog.english", "O'Reilly", p.document())
                .rankedBy(COVER_DENSITY, DOCUMENT_LENGTH, RANK_OVER_RANK_PLUS_ONE));
        source.where(p -> p.id().eq(99));
        var result = search.query(SearchHitProxy::createTable).where(p -> p.score().ge(0.2));
        var sql = result.cloneQueryable().toSQLResult();
        assertTrue(sql.getSQL().contains("ts_rank_cd("), sql.getSQL());
        assertTrue(sql.getSQL().contains("AS double precision)"), sql.getSQL());
        assertFalse(sql.getSQL().contains("O'Reilly"), sql.getSQL());
        assertFalse(sql.getSQL().contains("pg_catalog.english"), sql.getSQL());
        assertEquals(List.of("pg_catalog.english", "O'Reilly", 34, 2, "pg_catalog.english", "O'Reilly", 0.2),
                sql.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(sql.getSQL(), result.cloneQueryable().toSQL());
        var encoded = new PgTsQuery("'coff':*");
        var direct = PgSearch.from(client.queryable(PgSearchDocumentProxy.createTable()), p -> PgSearchSpec.query(encoded, p.document()))
                .query(SearchHitProxy::createTable).toSQLResult();
        assertEquals(List.of(encoded, 0, encoded), direct.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
    }

    @Test
    void physicalColumnsConfigurationsAndRankOptionsAreCheckedBeforeJdbc() {
        var client = client();
        var source = client.queryable(PgSearchDocumentProxy.createTable());
        var vector = source.get1Proxy().document();
        var foreign = client.queryable(PgSearchDocumentProxy.createTable()).get1Proxy().document();
        assertThrows(IllegalArgumentException.class, () -> PgSearch.from(source, p -> PgSearchSpec.plain("english", "coffee", foreign)));
        for (String invalid : List.of("", " ", "\0", "\uD800")) {
            assertThrows(IllegalArgumentException.class, () -> PgSearchSpec.plain(invalid, "coffee", vector));
            assertThrows(IllegalArgumentException.class, () -> PgSearchSpec.web("english", invalid, vector));
        }
        assertThrows(NullPointerException.class, () -> PgSearchSpec.plain(null, "coffee", vector));
        assertThrows(NullPointerException.class, () -> PgSearchSpec.query(null, vector));
        assertThrows(IllegalArgumentException.class, () -> PgSearchSpec.plain("english", "coffee", vector).rankedBy(FREQUENCY, MEAN_EXTENT_DISTANCE));
        assertDoesNotThrow(() -> PgSearchSpec.query(new PgTsQuery(""), vector));
        var projected = source.cloneQueryable().select(p -> Select.DRAFT.of(p.document()));
        assertThrows(IllegalArgumentException.class, () -> PgSearch.from(projected, p -> PgSearchSpec.plain("english", "coffee", p.value1())));
        var cte = client.queryable(PgSearchDocumentProxy.createTable()).toCteAs("projected_vector").cloneQueryable();
        assertThrows(IllegalArgumentException.class, () -> PgSearch.from(cte, p -> PgSearchSpec.plain("english", "coffee", p.document())));
        var definition = PgSearch.from(client.queryable(PgSearchDocumentProxy.createTable()), p -> PgSearchSpec.plain("english", "coffee", p.document()));
        assertThrows(IllegalArgumentException.class, () -> definition.query(PgSearchDocumentProxy::createTable));
        var output = SearchHitProxy.createTable();
        definition.query(() -> output);
        assertThrows(IllegalArgumentException.class, () -> definition.query(() -> output));
    }

    @Test
    void queryScopeIsExplicitAndCannotBeDiscardedByALaterFactory() {
        var application = new PostgresSQLExpressionFactory();
        var ordinary = TestClients.offline(new PgSQLDatabaseConfiguration(), services -> {
            services.addService(ExpressionFactory.class, application);
            PgSqlExtensions.configure(services);
        });
        assertSame(application, ordinary.getRuntimeContext().getExpressionFactory());
        for (boolean replacement : List.of(false, true)) {
            var query = TestClients.offline(new PgSQLDatabaseConfiguration(), services -> {
                PgSqlExtensions.configure(services);
                if (replacement) {
                    PgSqlExtensions.configureQueryScope(services);
                    services.addService(ExpressionFactory.class, new PostgresSQLExpressionFactory());
                }
            });
            PgSqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
            var hits = PgSearch.from(query.queryable(PgSearchDocumentProxy.createTable()), p -> PgSearchSpec.plain("english", "coffee", p.document()))
                    .query(SearchHitProxy::createTable);
            assertTrue(assertThrows(IllegalStateException.class, hits::toSQL).getMessage().contains("configureQueryScope"));
        }
    }

    @Test
    void lateShardFlagsSurviveCloneAndCteWhileApplicationFactoriesRemainInstalled() {
        var factory = new ApplicationFactory();
        var client = TestClients.offline(new PgSQLDatabaseConfiguration(), services -> {
            PgSqlExtensions.configure(services);
            PgSqlExtensions.configureQueryScope(services);
            services.addService(ExpressionFactory.class, factory);
        });
        PgSqlExtensions.configureTypeHandlers(client.getRuntimeContext().getJdbcTypeHandlerManager());
        assertSame(factory, client.getRuntimeContext().getExpressionFactory());
        var search = PgSearch.from(client.queryable(PgSearchDocumentProxy.createTable()), p -> PgSearchSpec.plain("english", "coffee", p.document()));
        var copy = search.query(SearchHitProxy::createTable).cloneQueryable();
        copy.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, copy::toSQL);
        client.getRuntimeContext().getQueryConfiguration().applyShardingInitializer(
                new ShardedProduct.Tables(Map.of("default", List.of("eqp_pg_search_shard_0", "eqp_pg_search_shard_1"))));
        var joined = search.query(SearchHitProxy::createTable).toCteAs("search_before_sharding")
                .innerJoin(client.queryable(ShardedProduct.class), (hit, product) -> hit.id().eq(product.id()));
        assertThrows(UnsupportedOperationException.class, joined::toSQL);
        var left = client.getEasyQueryClient().queryable(TestProduct.class).where(p -> p.eq("id", 1));
        var right = client.getEasyQueryClient().queryable(TestProduct.class).where(p -> p.eq("id", 2));
        var ordinary = left.unionAll(right).toCteAs("ordinary_union");
        assertEquals(ordinary.toSQL(), ordinary.cloneQueryable().toSQL());
        assertEquals(List.of(1, 2), ordinary.toSQLResult().getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertTrue(factory.queries >= 4);
    }

    private static com.easy.query.api.proxy.client.DefaultEasyEntityQuery client() {
        var client = TestClients.offline(new PgSQLDatabaseConfiguration(), services -> {
            PgSqlExtensions.configure(services);
            PgSqlExtensions.configureQueryScope(services);
        });
        PgSqlExtensions.configureTypeHandlers(client.getRuntimeContext().getJdbcTypeHandlerManager());
        return client;
    }

    public static class ApplicationFactory extends PgSqlProExpressionFactory {
        private int queries;

        @Override
        public EntityQuerySQLExpression createEasyQuerySQLExpression(EntitySQLExpressionMetadata metadata) {
            queries++;
            return super.createEasyQuerySQLExpression(metadata);
        }
    }
}
