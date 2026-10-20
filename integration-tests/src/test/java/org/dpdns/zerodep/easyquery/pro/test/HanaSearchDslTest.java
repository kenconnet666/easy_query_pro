package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.entity.select.impl.EasyEntityQueryable;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaSearch;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaSearchSpec;
import org.dpdns.zerodep.easyquery.pro.test.entity.SearchDocument;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaSearchRowProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchDocumentProxy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HanaSearchDslTest {
    static Stream<DecimalDslTest.Backend> hana() {
        return DecimalDslTest.backends().filter(backend -> backend.dialect() == SqlDialect.HANA);
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void searchFreezesTheSourceAndBindsTextThresholdAndOuterParameters(DecimalDslTest.Backend backend) {
        var source = backend.client().queryable(SearchDocumentProxy.createTable()).where(p -> p.id().gt(2));
        if (backend.dialect() != SqlDialect.HANA) {
            assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(source, p -> HanaSearchSpec.exact("coffee", p.title())));
            return;
        }
        var search = HanaSearch.from(source, p -> HanaSearchSpec.fuzzy("O'Reilly", 0.75, p.title()));
        source.where(p -> p.id().eq(99));
        var query = search.query(HanaSearchRowProxy::createTable).where(p -> p.score().ge(0.2));
        var result = query.cloneQueryable().toSQLResult();
        assertTrue(result.getSQL().contains("SCORE() AS \"SEARCH_SCORE\""), result.getSQL());
        assertTrue(result.getSQL().contains("FUZZY(?),FULLTEXT(ON)"), result.getSQL());
        assertFalse(result.getSQL().contains("O'Reilly"), result.getSQL());
        assertEquals(List.of(2, "O'Reilly", 0.75, 0.2), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
    }

    @ParameterizedTest
    @MethodSource("hana")
    void onlyPhysicalTextColumnsAndValidSearchSpecificationsAreAccepted(DecimalDslTest.Backend backend) {
        var query = backend.client();
        var source = query.queryable(SearchDocumentProxy.createTable());
        var p = source.get1Proxy();
        var other = query.queryable(SearchDocumentProxy.createTable()).get1Proxy();
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(source, row -> HanaSearchSpec.exact("coffee", other.title())));
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(source, row -> HanaSearchSpec.exact("coffee", row.title(), row.title())));
        for (String text : List.of("", "  ", "\u0000", "\uD800"))
            assertThrows(IllegalArgumentException.class, () -> HanaSearchSpec.exact(text, p.title()));
        assertThrows(NullPointerException.class, () -> HanaSearchSpec.exact(null, p.title()));
        for (double threshold : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1})
            assertThrows(IllegalArgumentException.class, () -> HanaSearchSpec.fuzzy("coffee", threshold, p.title()));
        assertThrows(IllegalArgumentException.class, () -> HanaSearchSpec.exact("coffee", p.title(), p.body())
                .and(HanaSearchSpec.exact("tea", p.title(), p.body())));
        var original = HanaSearchSpec.exact("coffee", p.title());
        var combined = original.and(HanaSearchSpec.exact("Arabica", p.body()));
        assertEquals(1, occurrences(HanaSearch.from(source, row -> original).query(HanaSearchRowProxy::createTable).toSQL(), "CONTAINS("));
        assertEquals(2, occurrences(HanaSearch.from(source, row -> combined).query(HanaSearchRowProxy::createTable).toSQL(), "CONTAINS("));
    }

    @ParameterizedTest
    @MethodSource("hana")
    void projectionsDerivedTablesAndReusedOutputProxiesAreRejected(DecimalDslTest.Backend backend) {
        var client = backend.client();
        var source = client.queryable(SearchDocumentProxy.createTable());
        var projected = source.cloneQueryable().select(p -> Select.DRAFT.of(p.title()));
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(projected, p -> HanaSearchSpec.exact("coffee", p.value1())));
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(source.cloneQueryable().distinct(), p -> HanaSearchSpec.exact("coffee", p.title())));
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(source.cloneQueryable().toCteAs("not_physical"), p -> HanaSearchSpec.exact("coffee", p.title())));
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(source.cloneQueryable().toCteAs("not_physical_clone").cloneQueryable(), p -> HanaSearchSpec.exact("coffee", p.title())));
        var raw = new EasyEntityQueryable<>(SearchDocumentProxy.createTable(), client.getEasyQueryClient().queryable("SELECT 1", SearchDocument.class));
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(raw, p -> HanaSearchSpec.exact("coffee", p.title())));
        var search = HanaSearch.from(client.queryable(SearchDocumentProxy.createTable()), p -> HanaSearchSpec.exact("coffee", p.title()));
        assertThrows(IllegalArgumentException.class, () -> search.query(SearchDocumentProxy::createTable));
        var output = HanaSearchRowProxy.createTable();
        search.query(() -> output);
        assertThrows(IllegalArgumentException.class, () -> search.query(() -> output));
    }

    @ParameterizedTest
    @MethodSource("hana")
    void lateShardMergingIsRejectedThroughCloneAndCteJoin(DecimalDslTest.Backend backend) {
        var client = backend.client();
        var search = HanaSearch.from(client.queryable(SearchDocumentProxy.createTable()), p -> HanaSearchSpec.exact("coffee", p.title()));
        var copy = search.query(HanaSearchRowProxy::createTable).cloneQueryable();
        copy.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, copy::toSQL);
        client.getRuntimeContext().getQueryConfiguration().applyShardingInitializer(
                new ShardedProduct.Tables(Map.of("default", List.of("eqp_search_shard_0", "eqp_search_shard_1"))));
        var joined = search.query(HanaSearchRowProxy::createTable).toCteAs("search_before_sharding")
                .innerJoin(client.queryable(ShardedProduct.class), (hit, product) -> hit.id().eq(product.id()));
        assertThrows(UnsupportedOperationException.class, joined::toSQL);
    }

    private static int occurrences(String text, String part) {
        return (text.length() - text.replace(part, "").length()) / part.length();
    }
}
