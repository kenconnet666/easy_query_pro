package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.SQLColumn;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaHierarchy;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaHierarchySpec;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaHierarchyEdgeProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaHierarchyRowProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaHierarchyNavigationRowProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.CodecRecord;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.HanaHierarchyEdge;
import java.util.Arrays;
import org.dpdns.zerodep.easyquery.pro.test.entity.HanaHierarchyRow;
import com.easy.query.core.common.ToSQLResult;
import java.util.stream.Collectors;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import java.util.Collections;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ShardedProductProxy;
import com.easy.query.api.proxy.entity.select.EntityQueryable2;
import com.easy.query.core.configuration.QueryConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.CodecRecordProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.HanaHierarchyNavigationRow;

import static org.junit.jupiter.api.Assertions.*;

class HanaHierarchyDslTest {
    static Stream<DecimalDslTest.Backend> hana() {
        return DecimalDslTest.backends().filter(backend -> backend.dialect() == SqlDialect.HANA);
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void hierarchyRetainsSourceRootAndOuterParameterMetadata(DecimalDslTest.Backend backend) {
        EntityQueryable<HanaHierarchyEdgeProxy, HanaHierarchyEdge> source = backend.client().queryable(HanaHierarchyEdgeProxy.createTable()).where(p -> p.name().eq("source"));
        if (backend.dialect() != SqlDialect.HANA) {
            assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId())));
            return;
        }
        HanaHierarchy<Integer> hierarchy = HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()).roots(Arrays.asList(7)).siblingsAsc(p.sortOrder()));
        source.where(p -> p.id().eq(99));
        EntityQueryable<HanaHierarchyRowProxy, HanaHierarchyRow> query = hierarchy.query(HanaHierarchyRowProxy::createTable).where(p -> p.level().le(3));
        ToSQLResult result = query.cloneQueryable().toSQLResult();
        assertTrue(result.getSQL().contains("HIERARCHY(SOURCE"), result.getSQL());
        assertTrue(result.getSQL().contains("START WHERE \"NODE_ID\" IN (?)"), result.getSQL());
        assertTrue(result.getSQL().contains("SIBLING ORDER BY \"sort_order\" ASC,\"NODE_ID\" ASC"), result.getSQL());
        List<SQLParameter> parameters = result.getSqlContext().getParameters();
        assertEquals(Arrays.asList("source", 7, 3), parameters.stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals("name", parameters.get(0).getPropertyNameOrNull());
        assertEquals("id", parameters.get(1).getPropertyNameOrNull());
        assertNotNull(parameters.get(1).getColumnMetadata());
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
    }

    @ParameterizedTest
    @MethodSource("hana")
    void invalidPoliciesForeignColumnsAndReusedOutputProxyAreRejected(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = backend.client();
        EntityQueryable<HanaHierarchyEdgeProxy, HanaHierarchyEdge> source = client.queryable(HanaHierarchyEdgeProxy.createTable());
        HanaHierarchyEdgeProxy foreign = client.queryable(HanaHierarchyEdgeProxy.createTable()).get1Proxy();
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), foreign.parentId())));
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()).siblingsAsc(foreign.name())));
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()).depth(-1)));
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()).roots(Collections.emptyList())));
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()).roots(java.util.Arrays.asList((Integer) null))));
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId())
                .depth(1).orphans(HanaHierarchySpec.Orphans.ROOT)));
        HanaHierarchy<Integer> hierarchy = HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()));
        HanaHierarchyRowProxy output = HanaHierarchyRowProxy.createTable();
        hierarchy.query(() -> output);
        assertThrows(IllegalArgumentException.class, () -> hierarchy.query(() -> output));
    }

    @ParameterizedTest
    @MethodSource("hana")
    void shardMergingCannotBeAddedAfterCloningTheHierarchy(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = backend.client();
        EntityQueryable<HanaHierarchyEdgeProxy, HanaHierarchyEdge> source = client.queryable(HanaHierarchyEdgeProxy.createTable());
        HanaHierarchy<Integer> hierarchy = HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()));
        EntityQueryable<HanaHierarchyRowProxy, HanaHierarchyRow> copy = hierarchy.query(HanaHierarchyRowProxy::createTable).cloneQueryable();
        copy.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, copy::toSQL);
        client.getRuntimeContext().getQueryConfiguration().applyShardingInitializer(
                new ShardedProduct.Tables(Collections.singletonMap("default", Arrays.asList("eqp_hierarchy_shard_0", "eqp_hierarchy_shard_1"))));
        EntityQueryable<ShardedProductProxy, ShardedProduct> sharded = client.queryable(ShardedProduct.class);
        assertTrue(sharded.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().isSharding());
        EntityQueryable2<HanaHierarchyRowProxy, HanaHierarchyRow, ShardedProductProxy, ShardedProduct> joined = hierarchy.query(HanaHierarchyRowProxy::createTable).cloneQueryable()
                .leftJoin(sharded, (node, product) -> node.id().eq(product.id()));
        assertThrows(UnsupportedOperationException.class, joined::toSQL);
        EntityQueryable2<HanaHierarchyRowProxy, HanaHierarchyRow, ShardedProductProxy, ShardedProduct> cteJoined = hierarchy.query(HanaHierarchyRowProxy::createTable).toCteAs("hierarchy_before_sharding")
                .innerJoin(sharded, (node, product) -> node.id().eq(product.id()));
        assertThrows(UnsupportedOperationException.class, cteJoined::toSQL);
    }

    @ParameterizedTest
    @MethodSource("hana")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void businessKeyValidationUsesMetadataInsteadOfAptObjectCarriers(DecimalDslTest.Backend backend) {
        DefaultEasyEntityQuery client = backend.client();
        QueryConfiguration configuration = client.getRuntimeContext().getQueryConfiguration();
        configuration.applyValueConverter(new CodecRecord.IdConverter());
        configuration.applyValueConverter(new CodecRecord.LabelConverter("ENC:"));
        configuration.applyColumnValueSQLConverter(new CodecRecord.CentsConverter());
        EntityQueryable<CodecRecordProxy, CodecRecord> source = client.queryable(CodecRecord.class);
        // 3.2.17 APT 为两个不同的 record 都生成 Object.class，raw 调用不能因此混用不同业务键。
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source,
                p -> HanaHierarchySpec.of((SQLColumn) p.id(), (SQLColumn) p.label())));
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source,
                p -> ((HanaHierarchySpec) HanaHierarchySpec.of(p.label(), p.document())).roots(Arrays.asList("wrong Java type"))));
    }

    @ParameterizedTest
    @MethodSource("hana")
    void navigationComposesTheGeneratorAndBindsSignedDistances(DecimalDslTest.Backend backend) {
        EntityQueryable<HanaHierarchyEdgeProxy, HanaHierarchyEdge> source = backend.client().queryable(HanaHierarchyEdgeProxy.createTable()).where(p -> p.id().le(10));
        HanaHierarchy<Integer> hierarchy = HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()).roots(Arrays.asList(1)));
        HanaHierarchy<Integer> navigation = hierarchy.ancestorsOf(Arrays.asList(4)).distanceBetween(-2, -1);
        EntityQueryable<HanaHierarchyNavigationRowProxy, HanaHierarchyNavigationRow> query = navigation.query(HanaHierarchyNavigationRowProxy::createTable).where(p -> p.id().gt(0));
        ToSQLResult result = query.cloneQueryable().toSQLResult();
        assertTrue(result.getSQL().contains("HIERARCHY_ANCESTORS(SOURCE HIERARCHY("), result.getSQL());
        assertTrue(result.getSQL().contains("DISTANCE FROM ? TO ?"), result.getSQL());
        assertEquals(Arrays.asList(10, 1, 4, -2, -1, 0), result.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
        assertThrows(IllegalStateException.class, () -> hierarchy.distanceBetween(0, 1));
        assertThrows(IllegalArgumentException.class, () -> hierarchy.descendantsOf(Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> hierarchy.ancestorsOf(java.util.Arrays.asList((Integer) null)));
        assertThrows(IllegalStateException.class, () -> navigation.descendantsOf(Arrays.asList(1)));
        assertFalse(hierarchy.query(HanaHierarchyRowProxy::createTable).toSQL().contains("HIERARCHY_ANCESTORS("));
    }
}
