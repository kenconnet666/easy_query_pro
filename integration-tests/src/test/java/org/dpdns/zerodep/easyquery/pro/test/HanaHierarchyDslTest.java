package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.proxy.SQLColumn;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaHierarchy;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaHierarchySpec;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaHierarchyEdgeProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaHierarchyRowProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.CodecRecord;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HanaHierarchyDslTest {
    static Stream<DecimalDslTest.Backend> hana() {
        return DecimalDslTest.backends().filter(backend -> backend.dialect() == SqlDialect.HANA);
    }

    @ParameterizedTest
    @MethodSource("org.dpdns.zerodep.easyquery.pro.test.DecimalDslTest#backends")
    void hierarchyRetainsSourceRootAndOuterParameterMetadata(DecimalDslTest.Backend backend) {
        var source = backend.client().queryable(HanaHierarchyEdgeProxy.createTable()).where(p -> p.name().eq("source"));
        if (backend.dialect() != SqlDialect.HANA) {
            assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId())));
            return;
        }
        var hierarchy = HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()).roots(List.of(7)).siblingsAsc(p.sortOrder()));
        source.where(p -> p.id().eq(99));
        var query = hierarchy.query(HanaHierarchyRowProxy::createTable).where(p -> p.level().le(3));
        var result = query.cloneQueryable().toSQLResult();
        assertTrue(result.getSQL().contains("HIERARCHY(SOURCE"), result.getSQL());
        assertTrue(result.getSQL().contains("START WHERE \"NODE_ID\" IN (?)"), result.getSQL());
        assertTrue(result.getSQL().contains("SIBLING ORDER BY \"sort_order\" ASC,\"NODE_ID\" ASC"), result.getSQL());
        var parameters = result.getSqlContext().getParameters();
        assertEquals(List.of("source", 7, 3), parameters.stream().map(SQLParameter::getValue).toList());
        assertEquals("name", parameters.get(0).getPropertyNameOrNull());
        assertEquals("id", parameters.get(1).getPropertyNameOrNull());
        assertNotNull(parameters.get(1).getColumnMetadata());
        assertEquals(result.getSQL(), query.cloneQueryable().toSQL());
    }

    @ParameterizedTest
    @MethodSource("hana")
    void invalidPoliciesForeignColumnsAndReusedOutputProxyAreRejected(DecimalDslTest.Backend backend) {
        var client = backend.client();
        var source = client.queryable(HanaHierarchyEdgeProxy.createTable());
        var foreign = client.queryable(HanaHierarchyEdgeProxy.createTable()).get1Proxy();
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), foreign.parentId())));
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()).siblingsAsc(foreign.name())));
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()).depth(-1)));
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()).roots(List.of())));
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()).roots(java.util.Arrays.asList((Integer) null))));
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId())
                .depth(1).orphans(HanaHierarchySpec.Orphans.ROOT)));
        var hierarchy = HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()));
        var output = HanaHierarchyRowProxy.createTable();
        hierarchy.query(() -> output);
        assertThrows(IllegalArgumentException.class, () -> hierarchy.query(() -> output));
    }

    @ParameterizedTest
    @MethodSource("hana")
    void shardMergingCannotBeAddedAfterCloningTheHierarchy(DecimalDslTest.Backend backend) {
        var client = backend.client();
        var source = client.queryable(HanaHierarchyEdgeProxy.createTable());
        var hierarchy = HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()));
        var copy = hierarchy.query(HanaHierarchyRowProxy::createTable).cloneQueryable();
        copy.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().useSharding();
        assertThrows(UnsupportedOperationException.class, copy::toSQL);
        client.getRuntimeContext().getQueryConfiguration().applyShardingInitializer(
                new ShardedProduct.Tables(Map.of("default", List.of("eqp_hierarchy_shard_0", "eqp_hierarchy_shard_1"))));
        var sharded = client.queryable(ShardedProduct.class);
        assertTrue(sharded.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().isSharding());
        var joined = hierarchy.query(HanaHierarchyRowProxy::createTable).cloneQueryable()
                .leftJoin(sharded, (node, product) -> node.id().eq(product.id()));
        assertThrows(UnsupportedOperationException.class, joined::toSQL);
        var cteJoined = hierarchy.query(HanaHierarchyRowProxy::createTable).toCteAs("hierarchy_before_sharding")
                .innerJoin(sharded, (node, product) -> node.id().eq(product.id()));
        assertThrows(UnsupportedOperationException.class, cteJoined::toSQL);
    }

    @ParameterizedTest
    @MethodSource("hana")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void businessKeyValidationUsesMetadataInsteadOfAptObjectCarriers(DecimalDslTest.Backend backend) {
        var client = backend.client();
        var configuration = client.getRuntimeContext().getQueryConfiguration();
        configuration.applyValueConverter(new CodecRecord.IdConverter());
        configuration.applyValueConverter(new CodecRecord.LabelConverter("ENC:"));
        configuration.applyColumnValueSQLConverter(new CodecRecord.CentsConverter());
        var source = client.queryable(CodecRecord.class);
        // 3.2.17 APT 为两个不同的 record 都生成 Object.class，raw 调用不能因此混用不同业务键。
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source,
                p -> HanaHierarchySpec.of((SQLColumn) p.id(), (SQLColumn) p.label())));
        assertThrows(IllegalArgumentException.class, () -> HanaHierarchy.from(source,
                p -> ((HanaHierarchySpec) HanaHierarchySpec.of(p.label(), p.document())).roots(List.of("wrong Java type"))));
    }
}
