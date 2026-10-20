package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.expression.sql.expression.factory.ExpressionFactory;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import com.easy.query.mysql.expression.MySQLExpressionFactory;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.query.MySqlSearch;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.query.MySqlSearchSpec;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchDocumentProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchHitProxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MySqlSearchDslTest {
    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void searchBindsEveryOccurrenceAndPreservesTheSourceSnapshot(DecimalDslTest.Backend backend) {
        var client = TestClients.offline(backend.database(), services -> {
            backend.extensions().accept(services);
            if (backend.dialect() == SqlDialect.MYSQL) MySqlExtensions.configureQueryScope(services);
        });
        var source = client.queryable(SearchDocumentProxy.createTable()).where(p -> p.id().gt(2));
        if (backend.dialect() != SqlDialect.MYSQL) {
            assertThrows(IllegalArgumentException.class, () -> MySqlSearch.from(source, p -> MySqlSearchSpec.naturalLanguage("coffee", p.title())));
            return;
        }
        var search = MySqlSearch.from(source, p -> MySqlSearchSpec.booleanMode("O'Reilly", p.title(), p.body()));
        source.where(p -> p.id().eq(99));
        var query = search.query(SearchHitProxy::createTable).where(p -> p.score().ge(0.2));
        var result = query.cloneQueryable().toSQLResult();
        assertTrue(result.getSQL().contains("IN BOOLEAN MODE"), result.getSQL());
        assertFalse(result.getSQL().contains("O'Reilly"), result.getSQL());
        assertEquals(List.of("O'Reilly", 2, "O'Reilly", 0.2), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
    }

    @Test
    void queryScopeMustBeInstalledAndPreservedByAReplacementFactory() {
        for (boolean replaceLater : List.of(false, true)) {
            var client = TestClients.offline(new MySQLDatabaseConfiguration(), services -> {
                MySqlExtensions.configure(services);
                if (replaceLater) {
                    MySqlExtensions.configureQueryScope(services);
                    services.addService(ExpressionFactory.class, new MySQLExpressionFactory());
                }
            });
            var result = MySqlSearch.from(client.queryable(SearchDocumentProxy.createTable()), p -> MySqlSearchSpec.naturalLanguage("coffee", p.title()))
                    .query(SearchHitProxy::createTable);
            var failure = assertThrows(IllegalStateException.class, result::toSQL);
            assertTrue(failure.getMessage().contains("configureQueryScope"));
        }
    }

    @Test
    void invalidColumnsInputsAndOutputFactoriesFailBeforeJdbc() {
        var client = TestClients.offline(new MySQLDatabaseConfiguration(), services -> {
            MySqlExtensions.configure(services);
            MySqlExtensions.configureQueryScope(services);
        });
        var source = client.queryable(SearchDocumentProxy.createTable());
        var p = source.get1Proxy();
        var foreign = client.queryable(SearchDocumentProxy.createTable()).get1Proxy();
        assertThrows(IllegalArgumentException.class, () -> MySqlSearch.from(source, row -> MySqlSearchSpec.naturalLanguage("coffee", foreign.title())));
        assertThrows(IllegalArgumentException.class, () -> MySqlSearch.from(source, row -> MySqlSearchSpec.naturalLanguage("coffee", row.title(), row.title())));
        for (String query : List.of("", " ", "\0", "\uD800"))
            assertThrows(IllegalArgumentException.class, () -> MySqlSearchSpec.booleanMode(query, p.title()));
        assertThrows(NullPointerException.class, () -> MySqlSearchSpec.naturalLanguage(null, p.title()));
        var projected = source.cloneQueryable().select(row -> Select.DRAFT.of(row.title()));
        assertThrows(IllegalArgumentException.class, () -> MySqlSearch.from(projected, row -> MySqlSearchSpec.naturalLanguage("coffee", row.value1())));
        assertThrows(IllegalArgumentException.class, () -> MySqlSearch.from(source.cloneQueryable().toCteAs("not_physical").cloneQueryable(),
                row -> MySqlSearchSpec.naturalLanguage("coffee", row.title())));
        var search = MySqlSearch.from(client.queryable(SearchDocumentProxy.createTable()), row -> MySqlSearchSpec.naturalLanguage("coffee", row.title()));
        assertThrows(IllegalArgumentException.class, () -> search.query(SearchDocumentProxy::createTable));
        var output = SearchHitProxy.createTable();
        search.query(() -> output);
        assertThrows(IllegalArgumentException.class, () -> search.query(() -> output));
    }

    @Test
    void cloningAndCteJoinsCannotHideLateShardMerging() {
        var client = TestClients.offline(new MySQLDatabaseConfiguration(), services -> {
            MySqlExtensions.configure(services);
            MySqlExtensions.configureQueryScope(services);
        });
        var search = MySqlSearch.from(client.queryable(SearchDocumentProxy.createTable()), p -> MySqlSearchSpec.naturalLanguage("coffee", p.title()));
        var copy = search.query(SearchHitProxy::createTable).cloneQueryable();
        copy.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, copy::toSQL);
        client.getRuntimeContext().getQueryConfiguration().applyShardingInitializer(
                new ShardedProduct.Tables(Map.of("default", List.of("eqp_search_shard_0", "eqp_search_shard_1"))));
        var joined = search.query(SearchHitProxy::createTable).toCteAs("search_before_sharding")
                .innerJoin(client.queryable(ShardedProduct.class), (hit, product) -> hit.id().eq(product.id()));
        assertThrows(UnsupportedOperationException.class, joined::toSQL);
    }
}
