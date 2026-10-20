package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.api.proxy.entity.select.impl.EasyEntityQueryable;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.func.def.enums.OrderByModeEnum;
import com.easy.query.core.proxy.sql.GroupKeys;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HanaQueryCompositionIT {
    private DefaultEasyEntityQuery query;

    @BeforeEach
    void createClient() throws SQLException {
        query = HanaTestDatabase.client();
    }

    @Test
    void derivedJoinsRetainAliasesAndOuterNullRows() {
        var left = rows().leftJoin(rows().where(p -> p.id().le(2)), (p, other) -> p.id().eq(other.id()))
                .orderBy((p, other) -> p.id().asc())
                .select((p, other) -> Select.DRAFT.of(p.id(), other.id()));
        assertEquals(left.toSQL(), left.cloneQueryable().toSQL());
        var leftRows = left.toList();
        assertEquals(3, leftRows.size());
        assertEquals(1, leftRows.get(0).getValue2());
        assertEquals(2, leftRows.get(1).getValue2());
        assertNull(leftRows.get(2).getValue2());
        var rightRows = rows().where(p -> p.id().le(2)).rightJoin(rows(), (p, other) -> p.id().eq(other.id()))
                .orderBy((p, other) -> other.id().asc()).select((p, other) -> Select.DRAFT.of(p.id(), other.id())).toList();
        assertNull(rightRows.get(2).getValue1());
        assertEquals(3, rightRows.get(2).getValue2());
        assertEquals(2L, rows().innerJoin(rows().where(p -> p.id().le(2)), (p, other) -> p.id().eq(other.id())).count());
    }

    @Test
    void correlatedScalarAndExistsQueriesKeepOuterTableReferences() {
        var selection = rows().where(p -> p.expression().exists(rows().where(other -> {
                    other.id().eq(p.id());
                    other.name().eq("A");
                }))).orderBy(p -> p.id().asc())
                .select(p -> Select.DRAFT.of(p.id(), p.expression().subQueryColumn(rows()
                        .where(other -> other.name().eq(p.name())).selectColumn(other -> other.amount().max()))));
        assertEquals(selection.toSQL(), selection.cloneQueryable().toSQL());
        var result = assertDoesNotThrow(() -> {
            return selection.toList();
        }, selection.toSQL());
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getValue1());
        assertEquals(0, result.get(0).getValue2().compareTo(new java.math.BigDecimal("20")));
        assertEquals(0, result.get(1).getValue2().compareTo(new java.math.BigDecimal("20")));
    }

    @Test
    void scalarFactoryOverloadsKeepCorrelationBoundValuesAndNullResults() {
        for (boolean selector : List.of(false, true)) {
            var selected = rows().select(p -> {
                var inner = rows().where(other -> {
                    other.name().eq(p.name());
                    other.amount().gt(new BigDecimal("15"));
                }).selectColumn(other -> other.amount().max());
                return Select.DRAFT.of(p.id(), new NumberTypeExpressionImpl<BigDecimal>(
                        p.getEntitySQLContext(), p.getTable(), null,
                        fx -> selector ? fx.subQueryValue(args -> args.subQuery(inner)) : fx.subQueryValue(inner),
                        BigDecimal.class));
            }).orderBy(p -> p.value1().asc());
            var sql = selected.toSQLResult();
            assertEquals(List.of(new BigDecimal("15")), sql.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
            assertEquals(sql.getSQL(), selected.cloneQueryable().toSQL());
            var values = selected.cloneQueryable().toList();
            assertEquals(0, new BigDecimal("20").compareTo(values.get(0).getValue2()));
            assertEquals(0, new BigDecimal("20").compareTo(values.get(1).getValue2()));
            assertNull(values.get(2).getValue2());
            assertEquals(3L, selected.cloneQueryable().count());
        }
    }

    @Test
    void existsFactoryOverloadsComposeWithNotCountAndPaging() {
        for (boolean selector : List.of(false, true)) {
            var matching = factoryExists("A", false, selector).orderBy(p -> p.id().asc());
            assertEquals(List.of("A"), matching.toSQLResult().getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
            assertEquals(matching.toSQL(), matching.cloneQueryable().toSQL());
            assertEquals(List.of(1, 2), matching.cloneQueryable().toList().stream().map(TestProduct::getId).toList());
            var page = matching.cloneQueryable().toPageResult(2, 1);
            assertEquals(2L, page.getTotal());
            assertEquals(2, page.getData().get(0).getId());
            assertEquals(3, factoryExists("A", true, selector).singleNotNull().getId());
            assertEquals(0L, factoryExists("missing", false, selector).count());
            assertEquals(3L, factoryExists("missing", true, selector).count());
        }
    }

    @Test
    void unionDistinctAndUnionAllSupportOuterOrderingAndPagination() {
        var first = rows().where(p -> p.id().le(2));
        var second = rows().where(p -> p.id().ge(2));
        var distinct = first.cloneQueryable().union(second.cloneQueryable());
        assertEquals(3L, distinct.count());
        assertEquals(List.of(1, 2, 3), distinct.orderBy(p -> p.id().asc()).selectColumn(p -> p.id()).toList());
        var all = first.unionAll(second).orderBy(p -> p.id().asc());
        assertEquals(4L, all.count());
        assertEquals(List.of(2, 2), all.limit(1, 2).selectColumn(p -> p.id()).toList());
    }

    @Test
    void cteCanBeReferencedTwiceWithoutChangingParameterOrder() {
        var cte = rows().where(p -> p.name().eq("A")).toCteAs("eqp_selected");
        // 3.2.17 的 clone 共享可变 Proxy；自连接必须同时创建独立 Proxy 和 CTE 表引用。
        var second = new EasyEntityQueryable<>(TestProductProxy.createTable(), cte.getClientQueryable().toCteAs("eqp_selected"));
        var selection = cte.cloneQueryable().leftJoin(second, (p, other) -> p.id().eq(other.id()))
                .orderBy((p, other) -> p.id().asc()).select((p, other) -> Select.DRAFT.of(p.id(), other.name()));
        var first = selection.toSQLResult();
        var copy = selection.cloneQueryable().toSQLResult();
        assertEquals(first.getSQL(), copy.getSQL());
        assertEquals(List.of("A"), first.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(first.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList(),
                copy.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        var result = assertDoesNotThrow(() -> {
            return selection.toList();
        }, first.getSQL());
        assertEquals(2, result.size());
        assertEquals("A", result.get(1).getValue2());
    }

    @Test
    void paginationCountGroupedCountAndExplicitNullOrderingCompose() {
        var page = rows().orderBy(p -> p.id().asc()).toPageResult(2, 2);
        assertEquals(3L, page.getTotal());
        assertEquals(List.of(3), page.getData().stream().map(TestProduct::getId).toList());
        assertEquals(2L, rows().groupBy(p -> GroupKeys.of(p.name())).select(g -> Select.DRAFT.of(g.key1())).count());
        assertEquals(2L, rows().selectColumn(p -> p.name()).distinct().count());
        var beyond = rows().orderBy(p -> p.id().asc()).toPageResult(9, 2);
        assertEquals(3L, beyond.getTotal());
        assertTrue(beyond.getData().isEmpty());
        assertEquals(List.of(3, 1, 2), rows().orderBy(p -> p.amount().asc(true, OrderByModeEnum.NULLS_FIRST))
                .selectColumn(p -> p.id()).toList());
        assertEquals(List.of(2, 1, 3), rows().orderBy(p -> p.amount().desc(true, OrderByModeEnum.NULLS_LAST))
                .selectColumn(p -> p.id()).toList());
    }

    private EntityQueryable<TestProductProxy, TestProduct> factoryExists(String name, boolean negate, boolean selector) {
        var source = rows();
        var inner = rows().where(row -> {
            row.id().eq(source.get1Proxy().id());
            row.name().eq(name);
        }).selectColumn(row -> row.id());
        var functions = query.getRuntimeContext().fx();
        var exists = selector ? functions.exists(args -> args.subQuery(inner)) : functions.exists(inner);
        source.getClientQueryable().where(filter -> filter.getSQLNative().sqlNativeSegment(
                negate ? "NOT ({0})" : "{0}", context -> context.sqlFunction(exists)));
        return source;
    }

    private EntityQueryable<TestProductProxy, TestProduct> rows() {
        return query.queryable("SELECT 1 AS \"id\",'A' AS \"name\",CAST(10 AS DECIMAL(19,4)) AS \"amount\" FROM DUMMY"
                + " UNION ALL SELECT 2, 'A', 20 FROM DUMMY UNION ALL SELECT 3, 'B', NULL FROM DUMMY", TestProduct.class);
    }
}
