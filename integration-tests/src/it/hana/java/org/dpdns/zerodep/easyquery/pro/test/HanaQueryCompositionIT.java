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
import com.easy.query.core.proxy.core.draft.proxy.Draft2Proxy;
import com.easy.query.core.proxy.core.draft.Draft2;
import java.util.Arrays;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.core.common.ToSQLResult;
import java.util.stream.Collectors;
import com.easy.query.core.api.pagination.EasyPageResult;
import com.easy.query.core.func.SQLFunc;
import com.easy.query.core.func.SQLFunction;

import static org.junit.jupiter.api.Assertions.*;

class HanaQueryCompositionIT {
    private DefaultEasyEntityQuery query;

    @BeforeEach
    void createClient() throws SQLException {
        query = HanaTestDatabase.client();
    }

    @Test
    void derivedJoinsRetainAliasesAndOuterNullRows() {
        EntityQueryable<Draft2Proxy<Integer, Integer>, Draft2<Integer, Integer>> left = rows().leftJoin(rows().where(p -> p.id().le(2)), (p, other) -> p.id().eq(other.id()))
                .orderBy((p, other) -> p.id().asc())
                .select((p, other) -> Select.DRAFT.of(p.id(), other.id()));
        assertEquals(left.toSQL(), left.cloneQueryable().toSQL());
        List<Draft2<Integer, Integer>> leftRows = left.toList();
        assertEquals(3, leftRows.size());
        assertEquals(1, leftRows.get(0).getValue2());
        assertEquals(2, leftRows.get(1).getValue2());
        assertNull(leftRows.get(2).getValue2());
        List<Draft2<Integer, Integer>> rightRows = rows().where(p -> p.id().le(2)).rightJoin(rows(), (p, other) -> p.id().eq(other.id()))
                .orderBy((p, other) -> other.id().asc()).select((p, other) -> Select.DRAFT.of(p.id(), other.id())).toList();
        assertNull(rightRows.get(2).getValue1());
        assertEquals(3, rightRows.get(2).getValue2());
        assertEquals(2L, rows().innerJoin(rows().where(p -> p.id().le(2)), (p, other) -> p.id().eq(other.id())).count());
    }

    @Test
    void correlatedScalarAndExistsQueriesKeepOuterTableReferences() {
        EntityQueryable<Draft2Proxy<Integer, BigDecimal>, Draft2<Integer, BigDecimal>> selection = rows().where(p -> p.expression().exists(rows().where(other -> {
                    other.id().eq(p.id());
                    other.name().eq("A");
                }))).orderBy(p -> p.id().asc())
                .select(p -> Select.DRAFT.of(p.id(), p.expression().subQueryColumn(rows()
                        .where(other -> other.name().eq(p.name())).selectColumn(other -> other.amount().max()))));
        assertEquals(selection.toSQL(), selection.cloneQueryable().toSQL());
        List<Draft2<Integer, BigDecimal>> result = assertDoesNotThrow(() -> {
            return selection.toList();
        }, selection.toSQL());
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getValue1());
        assertEquals(0, result.get(0).getValue2().compareTo(new java.math.BigDecimal("20")));
        assertEquals(0, result.get(1).getValue2().compareTo(new java.math.BigDecimal("20")));
    }

    @Test
    void scalarFactoryOverloadsKeepCorrelationBoundValuesAndNullResults() {
        for (boolean selector : Arrays.asList(false, true)) {
            EntityQueryable<Draft2Proxy<Integer, BigDecimal>, Draft2<Integer, BigDecimal>> selected = rows().select(p -> {
                Query<BigDecimal> inner = rows().where(other -> {
                    other.name().eq(p.name());
                    other.amount().gt(new BigDecimal("15"));
                }).selectColumn(other -> other.amount().max());
                return Select.DRAFT.of(p.id(), new NumberTypeExpressionImpl<BigDecimal>(
                        p.getEntitySQLContext(), p.getTable(), null,
                        fx -> selector ? fx.subQueryValue(args -> args.subQuery(inner)) : fx.subQueryValue(inner),
                        BigDecimal.class));
            }).orderBy(p -> p.value1().asc());
            ToSQLResult sql = selected.toSQLResult();
            assertEquals(Arrays.asList(new BigDecimal("15")), sql.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
            assertEquals(sql.getSQL(), selected.cloneQueryable().toSQL());
            List<Draft2<Integer, BigDecimal>> values = selected.cloneQueryable().toList();
            assertEquals(0, new BigDecimal("20").compareTo(values.get(0).getValue2()));
            assertEquals(0, new BigDecimal("20").compareTo(values.get(1).getValue2()));
            assertNull(values.get(2).getValue2());
            assertEquals(3L, selected.cloneQueryable().count());
        }
    }

    @Test
    void existsFactoryOverloadsComposeWithNotCountAndPaging() {
        for (boolean selector : Arrays.asList(false, true)) {
            EntityQueryable<TestProductProxy, TestProduct> matching = factoryExists("A", false, selector).orderBy(p -> p.id().asc());
            assertEquals(Arrays.asList("A"), matching.toSQLResult().getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
            assertEquals(matching.toSQL(), matching.cloneQueryable().toSQL());
            assertEquals(Arrays.asList(1, 2), matching.cloneQueryable().toList().stream().map(TestProduct::getId).collect(Collectors.toList()));
            EasyPageResult<TestProduct> page = matching.cloneQueryable().toPageResult(2, 1);
            assertEquals(2L, page.getTotal());
            assertEquals(2, page.getData().get(0).getId());
            assertEquals(3, factoryExists("A", true, selector).singleNotNull().getId());
            assertEquals(0L, factoryExists("missing", false, selector).count());
            assertEquals(3L, factoryExists("missing", true, selector).count());
        }
    }

    @Test
    void unionDistinctAndUnionAllSupportOuterOrderingAndPagination() {
        EntityQueryable<TestProductProxy, TestProduct> first = rows().where(p -> p.id().le(2));
        EntityQueryable<TestProductProxy, TestProduct> second = rows().where(p -> p.id().ge(2));
        EntityQueryable<TestProductProxy, TestProduct> distinct = first.cloneQueryable().union(second.cloneQueryable());
        assertEquals(3L, distinct.count());
        assertEquals(Arrays.asList(1, 2, 3), distinct.orderBy(p -> p.id().asc()).selectColumn(p -> p.id()).toList());
        EntityQueryable<TestProductProxy, TestProduct> all = first.unionAll(second).orderBy(p -> p.id().asc());
        assertEquals(4L, all.count());
        assertEquals(Arrays.asList(2, 2), all.limit(1, 2).selectColumn(p -> p.id()).toList());
    }

    @Test
    void cteCanBeReferencedTwiceWithoutChangingParameterOrder() {
        EntityQueryable<TestProductProxy, TestProduct> cte = rows().where(p -> p.name().eq("A")).toCteAs("eqp_selected");
        // 3.2.17 的 clone 共享可变 Proxy；自连接必须同时创建独立 Proxy 和 CTE 表引用。
        EasyEntityQueryable<TestProductProxy, TestProduct> second = new EasyEntityQueryable<>(TestProductProxy.createTable(), cte.getClientQueryable().toCteAs("eqp_selected"));
        EntityQueryable<Draft2Proxy<Integer, String>, Draft2<Integer, String>> selection = cte.cloneQueryable().leftJoin(second, (p, other) -> p.id().eq(other.id()))
                .orderBy((p, other) -> p.id().asc()).select((p, other) -> Select.DRAFT.of(p.id(), other.name()));
        ToSQLResult first = selection.toSQLResult();
        ToSQLResult copy = selection.cloneQueryable().toSQLResult();
        assertEquals(first.getSQL(), copy.getSQL());
        assertEquals(Arrays.asList("A"), first.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        assertEquals(first.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()),
                copy.getSqlContext().getParameters().stream().map(SQLParameter::getValue).collect(Collectors.toList()));
        List<Draft2<Integer, String>> result = assertDoesNotThrow(() -> {
            return selection.toList();
        }, first.getSQL());
        assertEquals(2, result.size());
        assertEquals("A", result.get(1).getValue2());
    }

    @Test
    void paginationCountGroupedCountAndExplicitNullOrderingCompose() {
        EasyPageResult<TestProduct> page = rows().orderBy(p -> p.id().asc()).toPageResult(2, 2);
        assertEquals(3L, page.getTotal());
        assertEquals(Arrays.asList(3), page.getData().stream().map(TestProduct::getId).collect(Collectors.toList()));
        assertEquals(2L, rows().groupBy(p -> GroupKeys.of(p.name())).select(g -> Select.DRAFT.of(g.key1())).count());
        assertEquals(2L, rows().selectColumn(p -> p.name()).distinct().count());
        EasyPageResult<TestProduct> beyond = rows().orderBy(p -> p.id().asc()).toPageResult(9, 2);
        assertEquals(3L, beyond.getTotal());
        assertTrue(beyond.getData().isEmpty());
        assertEquals(Arrays.asList(3, 1, 2), rows().orderBy(p -> p.amount().asc(true, OrderByModeEnum.NULLS_FIRST))
                .selectColumn(p -> p.id()).toList());
        assertEquals(Arrays.asList(2, 1, 3), rows().orderBy(p -> p.amount().desc(true, OrderByModeEnum.NULLS_LAST))
                .selectColumn(p -> p.id()).toList());
    }

    private EntityQueryable<TestProductProxy, TestProduct> factoryExists(String name, boolean negate, boolean selector) {
        EntityQueryable<TestProductProxy, TestProduct> source = rows();
        Query<Integer> inner = rows().where(row -> {
            row.id().eq(source.get1Proxy().id());
            row.name().eq(name);
        }).selectColumn(row -> row.id());
        SQLFunc functions = query.getRuntimeContext().fx();
        SQLFunction exists = selector ? functions.exists(args -> args.subQuery(inner)) : functions.exists(inner);
        source.getClientQueryable().where(filter -> filter.getSQLNative().sqlNativeSegment(
                negate ? "NOT ({0})" : "{0}", context -> context.sqlFunction(exists)));
        return source;
    }

    private EntityQueryable<TestProductProxy, TestProduct> rows() {
        return query.queryable("SELECT 1 AS \"id\",'A' AS \"name\",CAST(10 AS DECIMAL(19,4)) AS \"amount\" FROM DUMMY"
                + " UNION ALL SELECT 2, 'A', 20 FROM DUMMY UNION ALL SELECT 3, 'B', NULL FROM DUMMY", TestProduct.class);
    }
}
