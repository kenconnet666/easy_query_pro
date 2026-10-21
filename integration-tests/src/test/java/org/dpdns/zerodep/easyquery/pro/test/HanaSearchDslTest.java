package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.entity.select.impl.EasyEntityQueryable;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaSearch;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaSearchSpec;
import org.dpdns.zerodep.easyquery.pro.test.entity.SearchDocument;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchHitProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchDocumentProxy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.SearchHit;
import com.easy.query.core.common.ToSQLResult;
import java.util.Arrays;
import java.util.stream.Collectors;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.proxy.core.draft.proxy.Draft1Proxy;
import com.easy.query.core.proxy.core.draft.Draft1;
import java.util.Collections;
import com.easy.query.api.proxy.entity.select.EntityQueryable2;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ShardedProductProxy;

import static org.junit.jupiter.api.Assertions.*;

class HanaSearchDslTest {
    static Stream<DecimalDslTest.Backend> hana() {
        return DecimalDslTest.backends().filter(backend -> backend.dialect() == SqlDialect.HANA);
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void searchFreezesTheSourceAndBindsTextThresholdAndOuterParameters(DecimalDslTest.Backend backend) {
        EntityQueryable<SearchDocumentProxy, SearchDocument> source = backend.client().queryable(SearchDocumentProxy.createTable()).where(p -> p.id().gt(2));
        if (backend.dialect() != SqlDialect.HANA) {
            assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(source, p -> HanaSearchSpec.exact("coffee", p.title())));
            return;
        }
        HanaSearch search = HanaSearch.from(source, p -> HanaSearchSpec.fuzzy("O'Reilly", 0.75, p.title()));
        source.where(p -> p.id().eq(99));
        EntityQueryable<SearchHitProxy, SearchHit> query = search.query(SearchHitProxy::createTable).where(p -> p.score().ge(0.2));
        ToSQLResult result = query.cloneQueryable().toSQLResult();
        assertTrue(result.getSQL().contains("SCORE() AS \"SEARCH_SCORE\""), result.getSQL());
        assertTrue(result.getSQL().contains("FUZZY(?),FULLTEXT(ON)"), result.getSQL());
        assertFalse(result.getSQL().contains("O'Reilly"), result.getSQL());
        assertEquals(Arrays.asList(2, "O'Reilly", 0.75, 0.2), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
    }

    @ParameterizedTest
    @MethodSource("hana")
    void onlyPhysicalTextColumnsAndValidSearchSpecificationsAreAccepted(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery query = backend.client();
        EntityQueryable<SearchDocumentProxy, SearchDocument> source = query.queryable(SearchDocumentProxy.createTable());
        SearchDocumentProxy p = source.get1Proxy();
        SearchDocumentProxy other = query.queryable(SearchDocumentProxy.createTable()).get1Proxy();
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(source, row -> HanaSearchSpec.exact("coffee", other.title())));
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(source, row -> HanaSearchSpec.exact("coffee", row.title(), row.title())));
        for (String text : Arrays.asList("", "  ", "\u0000", "\uD800"))
            assertThrows(IllegalArgumentException.class, () -> HanaSearchSpec.exact(text, p.title()));
        assertThrows(NullPointerException.class, () -> HanaSearchSpec.exact(null, p.title()));
        for (double threshold : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1})
            assertThrows(IllegalArgumentException.class, () -> HanaSearchSpec.fuzzy("coffee", threshold, p.title()));
        assertThrows(IllegalArgumentException.class, () -> HanaSearchSpec.exact("coffee", p.title(), p.body())
                .and(HanaSearchSpec.exact("tea", p.title(), p.body())));
        HanaSearchSpec original = HanaSearchSpec.exact("coffee", p.title());
        HanaSearchSpec combined = original.and(HanaSearchSpec.exact("Arabica", p.body()));
        assertEquals(1, occurrences(HanaSearch.from(source, row -> original).query(SearchHitProxy::createTable).toSQL(), "CONTAINS("));
        assertEquals(2, occurrences(HanaSearch.from(source, row -> combined).query(SearchHitProxy::createTable).toSQL(), "CONTAINS("));
    }

    @ParameterizedTest
    @MethodSource("hana")
    void projectionsDerivedTablesAndReusedOutputProxiesAreRejected(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = backend.client();
        EntityQueryable<SearchDocumentProxy, SearchDocument> source = client.queryable(SearchDocumentProxy.createTable());
        EntityQueryable<Draft1Proxy<String>, Draft1<String>> projected = source.cloneQueryable().select(p -> Select.DRAFT.of(p.title()));
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(projected, p -> HanaSearchSpec.exact("coffee", p.value1())));
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(source.cloneQueryable().distinct(), p -> HanaSearchSpec.exact("coffee", p.title())));
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(source.cloneQueryable().toCteAs("not_physical"), p -> HanaSearchSpec.exact("coffee", p.title())));
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(source.cloneQueryable().toCteAs("not_physical_clone").cloneQueryable(), p -> HanaSearchSpec.exact("coffee", p.title())));
        EasyEntityQueryable<SearchDocumentProxy, SearchDocument> raw = new EasyEntityQueryable<>(SearchDocumentProxy.createTable(), client.getEasyQueryClient().queryable("SELECT 1", SearchDocument.class));
        assertThrows(IllegalArgumentException.class, () -> HanaSearch.from(raw, p -> HanaSearchSpec.exact("coffee", p.title())));
        HanaSearch search = HanaSearch.from(client.queryable(SearchDocumentProxy.createTable()), p -> HanaSearchSpec.exact("coffee", p.title()));
        assertThrows(IllegalArgumentException.class, () -> search.query(SearchDocumentProxy::createTable));
        SearchHitProxy output = SearchHitProxy.createTable();
        search.query(() -> output);
        assertThrows(IllegalArgumentException.class, () -> search.query(() -> output));
    }

    @ParameterizedTest
    @MethodSource("hana")
    void lateShardMergingIsRejectedThroughCloneAndCteJoin(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = backend.client();
        HanaSearch search = HanaSearch.from(client.queryable(SearchDocumentProxy.createTable()), p -> HanaSearchSpec.exact("coffee", p.title()));
        EntityQueryable<SearchHitProxy, SearchHit> copy = search.query(SearchHitProxy::createTable).cloneQueryable();
        copy.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, copy::toSQL);
        client.getRuntimeContext().getQueryConfiguration().applyShardingInitializer(
                new ShardedProduct.Tables(Collections.singletonMap("default", Arrays.asList("eqp_search_shard_0", "eqp_search_shard_1"))));
        EntityQueryable2<SearchHitProxy, SearchHit, ShardedProductProxy, ShardedProduct> joined = search.query(SearchHitProxy::createTable).toCteAs("search_before_sharding")
                .innerJoin(client.queryable(ShardedProduct.class), (hit, product) -> hit.id().eq(product.id()));
        assertThrows(UnsupportedOperationException.class, joined::toSQL);
    }

    private static int occurrences(String text, String part) {
        return (text.length() - text.replace(part, "").length()) / part.length();
    }
}
