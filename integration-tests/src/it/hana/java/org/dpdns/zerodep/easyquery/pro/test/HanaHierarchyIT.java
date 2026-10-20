package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.api.proxy.entity.select.impl.EasyEntityQueryable;
import com.easy.query.core.basic.jdbc.parameter.SQLParameter;
import com.easy.query.core.enums.EasyBehaviorEnum;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaHierarchy;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaHierarchySpec;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.HanaHierarchyEdge;
import org.dpdns.zerodep.easyquery.pro.test.entity.HanaHierarchyRow;
import org.dpdns.zerodep.easyquery.pro.test.entity.CodecRecord;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.CodecRecordProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaHierarchyEdgeProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaHierarchyRowProxy;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HanaHierarchyIT {
    private static final String EDGES = """
            SELECT 1 AS "id",CAST(NULL AS INTEGER) AS "parent_id",1 AS "sort_order",
                   'root' AS "name",CAST(10 AS DECIMAL(19,4)) AS "amount" FROM DUMMY
            UNION ALL SELECT 2,1,2,'later',20 FROM DUMMY
            UNION ALL SELECT 3,1,1,'earlier',30 FROM DUMMY
            UNION ALL SELECT 4,3,1,'leaf',40 FROM DUMMY
            UNION ALL SELECT 5,NULL,2,'second root',50 FROM DUMMY
            UNION ALL SELECT 6,5,1,'second child',60 FROM DUMMY
            """;

    @Test
    void forestsRootsDepthAndOuterPagingKeepTheirDifferentSemantics() throws SQLException {
        var hierarchy = HanaHierarchy.from(edges(""), p -> HanaHierarchySpec.of(p.id(), p.parentId()).siblingsAsc(p.sortOrder()));
        var rows = hierarchy.query(HanaHierarchyRowProxy::createTable).orderBy(p -> p.rank().asc()).toList();
        assertEquals(List.of(1, 3, 4, 2, 5, 6), rows.stream().map(HanaHierarchyRow::getId).toList());
        assertEquals(List.of(4L, 2L, 1L, 1L, 2L, 1L), rows.stream().map(HanaHierarchyRow::getTreeSize).toList());
        assertEquals(List.of(1, 2, 3, 2, 1, 2), rows.stream().map(HanaHierarchyRow::getLevel).toList());
        assertEquals(0L, rows.get(0).getParentRank());
        assertNull(rows.get(0).getParentId());
        // 展示只留根行，tree_size 仍来自完整树；不能下推此筛选再重新构树。
        var roots = hierarchy.query(HanaHierarchyRowProxy::createTable).where(p -> p.level().eq(1));
        roots.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
        var page = roots.cloneQueryable().orderBy(p -> p.rank().asc()).toPageResult(1, 1);
        assertEquals(2L, page.getTotal());
        assertEquals(4L, page.getData().get(0).getTreeSize());
        var cte = roots.cloneQueryable().toCteAs("hierarchy_roots");
        assertEquals(2L, cte.count());

        var start = HanaHierarchy.from(edges(""), p -> HanaHierarchySpec.of(p.id(), p.parentId()).roots(List.of(3)));
        assertEquals(List.of(3, 4), start.query(HanaHierarchyRowProxy::createTable).orderBy(p -> p.rank().asc())
                .toList().stream().map(HanaHierarchyRow::getId).toList());
        var limited = HanaHierarchy.from(edges(""), p -> HanaHierarchySpec.of(p.id(), p.parentId()).siblingsAsc(p.sortOrder()).depth(1));
        var truncated = limited.query(HanaHierarchyRowProxy::createTable).orderBy(p -> p.rank().asc()).toList();
        assertEquals(List.of(1, 3, 2, 5, 6), truncated.stream().map(HanaHierarchyRow::getId).toList());
        assertEquals(3L, truncated.get(0).getTreeSize());
    }

    @Test
    void orphanAndCyclePoliciesAreExecutedByHana() throws SQLException {
        String orphan = " UNION ALL SELECT 7,99,1,'orphan',70 FROM DUMMY";
        var ignored = HanaHierarchy.from(edges(orphan), p -> HanaHierarchySpec.of(p.id(), p.parentId()));
        assertEquals(6L, ignored.query(HanaHierarchyRowProxy::createTable).count());
        var adopted = HanaHierarchy.from(edges(orphan), p -> HanaHierarchySpec.of(p.id(), p.parentId()).orphans(HanaHierarchySpec.Orphans.ROOT));
        var last = adopted.query(HanaHierarchyRowProxy::createTable).where(p -> p.id().eq(7)).singleNotNull();
        assertEquals((short) 1, last.getOrphan());
        assertEquals(0L, last.getParentRank());
        assertEquals(99, last.getParentId());
        var rejected = HanaHierarchy.from(edges(orphan), p -> HanaHierarchySpec.of(p.id(), p.parentId()).orphans(HanaHierarchySpec.Orphans.ERROR));
        assertHierarchyError(() -> rejected.query(HanaHierarchyRowProxy::createTable).toList());

        var duplicate = HanaHierarchy.from(edges(" UNION ALL SELECT 2,5,1,'repeated',20 FROM DUMMY"),
                p -> HanaHierarchySpec.of(p.id(), p.parentId()));
        assertHierarchyError(() -> duplicate.query(HanaHierarchyRowProxy::createTable).toList());
        String cycleSql = """
                SELECT 1 AS "id",3 AS "parent_id",1 AS "sort_order",'cycle' AS "name",CAST(10 AS DECIMAL(19,4)) AS "amount" FROM DUMMY
                UNION ALL SELECT 2,1,1,'cycle',20 FROM DUMMY
                UNION ALL SELECT 3,2,1,'cycle',30 FROM DUMMY
                """;
        var cycle = source(cycleSql).where(p -> p.id().ge(1));
        var strict = HanaHierarchy.from(cycle, p -> HanaHierarchySpec.of(p.id(), p.parentId()).roots(List.of(1)).multiparent(HanaHierarchySpec.Multiparent.ALLOW));
        assertHierarchyError(() -> strict.query(HanaHierarchyRowProxy::createTable).toList());
        var breakup = HanaHierarchy.from(cycle, p -> HanaHierarchySpec.of(p.id(), p.parentId()).roots(List.of(1))
                .multiparent(HanaHierarchySpec.Multiparent.ALLOW).cycles(HanaHierarchySpec.Cycles.BREAKUP));
        var rows = breakup.query(HanaHierarchyRowProxy::createTable).orderBy(p -> p.rank().asc()).toList();
        assertEquals(List.of(1, 2, 3, 1), rows.stream().map(HanaHierarchyRow::getId).toList());
        assertEquals((short) 1, rows.get(3).getCycle());
    }

    @Test
    void sourceCteParametersAndLaterSourceChangesStayIsolated() throws SQLException {
        var source = edges("").where(p -> p.amount().ge(new java.math.BigDecimal("10"))).toCteAs("edge_source")
                .where(p -> p.id().le(6));
        var hierarchy = HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()).roots(List.of(1)));
        source.where(p -> p.id().eq(999));
        var query = hierarchy.query(HanaHierarchyRowProxy::createTable).where(p -> p.level().ge(2)).orderBy(p -> p.id().asc());
        var sql = query.cloneQueryable().toSQLResult();
        assertEquals(List.of(new java.math.BigDecimal("10"), 6, 1, 2),
                sql.getSqlContext().getParameters().stream().map(SQLParameter::getValue).toList());
        assertEquals(sql.getSQL(), query.cloneQueryable().toSQL());
        assertEquals(List.of(2, 3, 4), query.cloneQueryable().toList().stream().map(HanaHierarchyRow::getId).toList());
        assertEquals(3L, query.cloneQueryable().count());
    }

    @Test
    void outputDtoDoesNotInheritSourceTemporaryReaders() throws SQLException {
        var source = edges("");
        source.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getResultValueConverterMap(true)
                .put("name", (index, result) -> "SOURCE:" + result.getString(index + 1));
        assertEquals("SOURCE:root", source.cloneQueryable().where(p -> p.id().eq(1)).singleNotNull().getName());
        var hierarchy = HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.id(), p.parentId()));
        assertEquals("root", hierarchy.query(HanaHierarchyRowProxy::createTable).where(p -> p.id().eq(1)).singleNotNull().getName());
    }

    @Test
    void businessKeyRootsKeepTheSourceConverterAndJdbcHandler() throws SQLException {
        var client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure).getEasyQueryClient();
        var configuration = client.getRuntimeContext().getQueryConfiguration();
        configuration.applyValueConverter(new CodecRecord.IdConverter());
        configuration.applyValueConverter(new CodecRecord.LabelConverter("ENC:"));
        configuration.applyColumnValueSQLConverter(new CodecRecord.CentsConverter());
        String sql = """
                SELECT CAST(1 AS BIGINT) AS "id",CAST('ENC:root' AS NVARCHAR(255)) AS "label",
                       CAST(NULL AS NVARCHAR(255)) AS "document",CAST(0 AS DECIMAL(19,0)) AS "price",'plain' AS "plain" FROM DUMMY
                UNION ALL SELECT 2,'ENC:child','ENC:root',0,'plain' FROM DUMMY
                """;
        var source = new EasyEntityQueryable<>(CodecRecordProxy.createTable(), client.queryable(sql, CodecRecord.class))
                .where(p -> p.label().ne(new CodecRecord.Label("excluded"))).toCteAs("encoded_edges");
        var hierarchy = HanaHierarchy.from(source, p -> HanaHierarchySpec.of(p.label(), p.document())
                .roots(List.of(new CodecRecord.Label("root"))));
        var rows = hierarchy.query(CodecRecordProxy::createTable).orderBy(p -> p.id().asc()).toList();
        assertEquals(List.of(new CodecRecord.RowId(1), new CodecRecord.RowId(2)), rows.stream().map(CodecRecord::getId).toList());
        assertEquals(List.of(new CodecRecord.Label("root"), new CodecRecord.Label("child")), rows.stream().map(CodecRecord::getLabel).toList());
        assertNull(rows.get(0).getDocument());
        assertEquals(new CodecRecord.Label("root"), rows.get(1).getDocument());
    }

    private static void assertHierarchyError(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(RuntimeException.class, action);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            // 上游会先包装一层 errorCode=0 的 SQLException，必须继续找到真实的 HANA 诊断。
            if (cause instanceof SQLException sql && sql.getErrorCode() != 0) {
                assertTrue(sql.getMessage().contains("Hierarchy error"), failure.toString());
                return;
            }
        }
        fail("Expected a HANA hierarchy error with an SQLException cause", failure);
    }

    private static EntityQueryable<HanaHierarchyEdgeProxy, HanaHierarchyEdge> edges(String suffix) throws SQLException {
        return source(EDGES + suffix);
    }

    private static EntityQueryable<HanaHierarchyEdgeProxy, HanaHierarchyEdge> source(String sql) throws SQLException {
        var client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure).getEasyQueryClient();
        return new EasyEntityQueryable<>(HanaHierarchyEdgeProxy.createTable(), client.queryable(sql, HanaHierarchyEdge.class));
    }
}
