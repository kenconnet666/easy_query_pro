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

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.PgSearchDocument;
import org.dpdns.zerodep.easyquery.pro.test.entity.SearchHit;
import com.easy.query.core.common.ToSQLResult;
import java.util.Arrays;
import java.util.stream.Collectors;
import com.easy.query.core.proxy.columns.types.SQLAnyTypeColumn;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsVector;
import com.easy.query.core.proxy.core.draft.proxy.Draft1Proxy;
import com.easy.query.core.proxy.core.draft.Draft1;
import java.util.Collections;
import com.easy.query.api.proxy.entity.select.EntityQueryable2;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ShardedProductProxy;
import com.easy.query.core.basic.api.select.ClientQueryable;

import static org.junit.jupiter.api.Assertions.*;
import static org.dpdns.zerodep.easyquery.pro.dsl.pgsql.query.PgSearchSpec.Normalization.*;
import static org.dpdns.zerodep.easyquery.pro.dsl.pgsql.query.PgSearchSpec.Ranking.*;

class PgSearchDslTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void eachQueryOccurrenceKeepsItsBindingsAndTheSourceIsCaptured(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = TestClients.offline(backend.database(), services -> {
            backend.extensions().accept(services);
            if (backend.dialect() == SqlDialect.POSTGRESQL) PgSqlExtensions.configureQueryScope(services);
        });
        PgSqlExtensions.configureTypeHandlers(client.getRuntimeContext().getJdbcTypeHandlerManager());
        EntityQueryable<PgSearchDocumentProxy, PgSearchDocument> source = client.queryable(PgSearchDocumentProxy.createTable()).where(p -> p.id().gt(2));
        if (backend.dialect() != SqlDialect.POSTGRESQL) {
            assertThrows(IllegalArgumentException.class, () -> PgSearch.from(source,
                    p -> PgSearchSpec.plain("pg_catalog.english", "coffee", p.document())));
            return;
        }
        PgSearch search = PgSearch.from(source, p -> PgSearchSpec.web("pg_catalog.english", "O'Reilly", p.document())
                .rankedBy(COVER_DENSITY, DOCUMENT_LENGTH, RANK_OVER_RANK_PLUS_ONE));
        source.where(p -> p.id().eq(99));
        EntityQueryable<SearchHitProxy, SearchHit> result = search.query(SearchHitProxy::createTable).where(p -> p.score().ge(0.2));
        ToSQLResult sql = result.cloneQueryable().toSQLResult();
        assertTrue(sql.getSQL().contains("ts_rank_cd("), sql.getSQL());
        assertTrue(sql.getSQL().contains("AS double precision)"), sql.getSQL());
        assertFalse(sql.getSQL().contains("O'Reilly"), sql.getSQL());
        assertFalse(sql.getSQL().contains("pg_catalog.english"), sql.getSQL());
        assertEquals(Arrays.asList("pg_catalog.english", "O'Reilly", 34, 2, "pg_catalog.english", "O'Reilly", 0.2),
                sql.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(sql.getSQL(), result.cloneQueryable().toSQL());
        PgTsQuery encoded = new PgTsQuery("'coff':*");
        ToSQLResult direct = PgSearch.from(client.queryable(PgSearchDocumentProxy.createTable()), p -> PgSearchSpec.query(encoded, p.document()))
                .query(SearchHitProxy::createTable).toSQLResult();
        assertEquals(Arrays.asList(encoded, 0, encoded), direct.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
    }

    @Test
    void physicalColumnsConfigurationsAndRankOptionsAreCheckedBeforeJdbc() {
        DefaultEasyEntityQuery client = client();
        EntityQueryable<PgSearchDocumentProxy, PgSearchDocument> source = client.queryable(PgSearchDocumentProxy.createTable());
        SQLAnyTypeColumn<PgSearchDocumentProxy, PgTsVector> vector = source.get1Proxy().document();
        SQLAnyTypeColumn<PgSearchDocumentProxy, PgTsVector> foreign = client.queryable(PgSearchDocumentProxy.createTable()).get1Proxy().document();
        assertThrows(IllegalArgumentException.class, () -> PgSearch.from(source, p -> PgSearchSpec.plain("english", "coffee", foreign)));
        for (String invalid : Arrays.asList("", " ", "\0", "\uD800")) {
            assertThrows(IllegalArgumentException.class, () -> PgSearchSpec.plain(invalid, "coffee", vector));
            assertThrows(IllegalArgumentException.class, () -> PgSearchSpec.web("english", invalid, vector));
        }
        assertThrows(NullPointerException.class, () -> PgSearchSpec.plain(null, "coffee", vector));
        assertThrows(NullPointerException.class, () -> PgSearchSpec.query(null, vector));
        assertThrows(IllegalArgumentException.class, () -> PgSearchSpec.plain("english", "coffee", vector).rankedBy(FREQUENCY, MEAN_EXTENT_DISTANCE));
        assertDoesNotThrow(() -> PgSearchSpec.query(new PgTsQuery(""), vector));
        EntityQueryable<Draft1Proxy<PgTsVector>, Draft1<PgTsVector>> projected = source.cloneQueryable().select(p -> Select.DRAFT.of(p.document()));
        assertThrows(IllegalArgumentException.class, () -> PgSearch.from(projected, p -> PgSearchSpec.plain("english", "coffee", p.value1())));
        EntityQueryable<PgSearchDocumentProxy, PgSearchDocument> cte = client.queryable(PgSearchDocumentProxy.createTable()).toCteAs("projected_vector").cloneQueryable();
        assertThrows(IllegalArgumentException.class, () -> PgSearch.from(cte, p -> PgSearchSpec.plain("english", "coffee", p.document())));
        PgSearch definition = PgSearch.from(client.queryable(PgSearchDocumentProxy.createTable()), p -> PgSearchSpec.plain("english", "coffee", p.document()));
        assertThrows(IllegalArgumentException.class, () -> definition.query(PgSearchDocumentProxy::createTable));
        SearchHitProxy output = SearchHitProxy.createTable();
        definition.query(() -> output);
        assertThrows(IllegalArgumentException.class, () -> definition.query(() -> output));
    }

    @Test
    void queryScopeIsExplicitAndCannotBeDiscardedByALaterFactory() {
        PostgresSQLExpressionFactory application = new PostgresSQLExpressionFactory();
        DefaultEasyEntityQuery ordinary = TestClients.offline(new PgSQLDatabaseConfiguration(), services -> {
            services.addService(ExpressionFactory.class, application);
            PgSqlExtensions.configure(services);
        });
        assertSame(application, ordinary.getRuntimeContext().getExpressionFactory());
        for (boolean replacement : Arrays.asList(false, true)) {
            DefaultEasyEntityQuery query = TestClients.offline(new PgSQLDatabaseConfiguration(), services -> {
                PgSqlExtensions.configure(services);
                if (replacement) {
                    PgSqlExtensions.configureQueryScope(services);
                    services.addService(ExpressionFactory.class, new PostgresSQLExpressionFactory());
                }
            });
            PgSqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
            EntityQueryable<SearchHitProxy, SearchHit> hits = PgSearch.from(query.queryable(PgSearchDocumentProxy.createTable()), p -> PgSearchSpec.plain("english", "coffee", p.document()))
                    .query(SearchHitProxy::createTable);
            assertTrue(assertThrows(IllegalStateException.class, hits::toSQL).getMessage().contains("configureQueryScope"));
        }
    }

    @Test
    void lateShardFlagsSurviveCloneAndCteWhileApplicationFactoriesRemainInstalled() {
        PgSearchDslTest.ApplicationFactory factory = new ApplicationFactory();
        DefaultEasyEntityQuery client = TestClients.offline(new PgSQLDatabaseConfiguration(), services -> {
            PgSqlExtensions.configure(services);
            PgSqlExtensions.configureQueryScope(services);
            services.addService(ExpressionFactory.class, factory);
        });
        PgSqlExtensions.configureTypeHandlers(client.getRuntimeContext().getJdbcTypeHandlerManager());
        assertSame(factory, client.getRuntimeContext().getExpressionFactory());
        PgSearch search = PgSearch.from(client.queryable(PgSearchDocumentProxy.createTable()), p -> PgSearchSpec.plain("english", "coffee", p.document()));
        EntityQueryable<SearchHitProxy, SearchHit> copy = search.query(SearchHitProxy::createTable).cloneQueryable();
        copy.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, copy::toSQL);
        client.getRuntimeContext().getQueryConfiguration().applyShardingInitializer(
                new ShardedProduct.Tables(Collections.singletonMap("default", Arrays.asList("eqp_pg_search_shard_0", "eqp_pg_search_shard_1"))));
        EntityQueryable2<SearchHitProxy, SearchHit, ShardedProductProxy, ShardedProduct> joined = search.query(SearchHitProxy::createTable).toCteAs("search_before_sharding")
                .innerJoin(client.queryable(ShardedProduct.class), (hit, product) -> hit.id().eq(product.id()));
        assertThrows(UnsupportedOperationException.class, joined::toSQL);
        ClientQueryable<TestProduct> left = client.getEasyQueryClient().queryable(TestProduct.class).where(p -> p.eq("id", 1));
        ClientQueryable<TestProduct> right = client.getEasyQueryClient().queryable(TestProduct.class).where(p -> p.eq("id", 2));
        ClientQueryable<TestProduct> ordinary = left.unionAll(right).toCteAs("ordinary_union");
        assertEquals(ordinary.toSQL(), ordinary.cloneQueryable().toSQL());
        assertEquals(Arrays.asList(1, 2), ordinary.toSQLResult().getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertTrue(factory.queries >= 4);
    }

    private static com.easy.query.api.proxy.client.DefaultEasyEntityQuery client() {
        DefaultEasyEntityQuery client = TestClients.offline(new PgSQLDatabaseConfiguration(), services -> {
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
