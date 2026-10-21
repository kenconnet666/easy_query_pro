package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.enums.SQLExecuteStrategyEnum;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.PgArrayRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.Statement;
import java.util.Collections;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.core.draft.proxy.Draft6Proxy;
import com.easy.query.core.proxy.core.draft.Draft6;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayValue;
import java.util.stream.Collectors;
import com.easy.query.core.basic.jdbc.tx.Transaction;
import com.easy.query.core.proxy.core.draft.proxy.Draft3Proxy;
import com.easy.query.core.proxy.core.draft.Draft3;
import java.sql.Connection;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PgArrayRecordProxy;
import com.easy.query.core.common.ToSQLResult;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import com.easy.query.core.proxy.core.draft.proxy.Draft2Proxy;
import com.easy.query.core.proxy.core.draft.Draft2;

import static org.junit.jupiter.api.Assertions.*;

class PostgresArrayDslIT {
    private PostgresTestDatabase.Schema schema;
    private DefaultEasyEntityQuery query;

    @BeforeEach
    void createFixture() throws SQLException {
        PGSimpleDataSource source = PostgresTestDatabase.dataSource();
        schema = PostgresTestDatabase.createSchema(source, "eqp_array_");
        try (Statement statement = schema.connection().createStatement()) {
            statement.execute("CREATE TABLE " + schema.qualified("eqp_pg_array") + " (id integer PRIMARY KEY, numbers "
                    + PgSqlTypes.array(Integer.class).ddlType(SqlDialect.POSTGRESQL) + ", labels "
                    + PgSqlTypes.array(String.class).ddlType(SqlDialect.POSTGRESQL) + ")");
        }
        query = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        PgSqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
        query.getRuntimeContext().getQueryConfiguration().applyValueConverter(new PgArrayRecord.LabelsConverter());
        PgArrayRecord present = new PgArrayRecord();
        present.setId(1);
        present.setNumbers(PgSqlTypes.array(Integer.class).value(Arrays.asList(1, null, 2)));
        present.setLabels(Arrays.asList("a", "中😀", "NULL", null));
        PgArrayRecord empty = new PgArrayRecord();
        empty.setId(2);
        empty.setNumbers(PgSqlTypes.array(Integer.class).value(Collections.emptyList()));
        empty.setLabels(Collections.emptyList());
        PgArrayRecord missing = new PgArrayRecord();
        missing.setId(3);
        assertEquals(3L, query.insertable(Arrays.asList(present, empty, missing)).setSQLStrategy(SQLExecuteStrategyEnum.ALL_COLUMNS).executeRows());
    }

    @AfterEach
    void removeFixture() throws SQLException {
        if (schema != null) schema.close();
    }

    @Test
    void nativePredicatesKeepNullAndEmptyArraySemantics() {
        EntityQueryable<Draft6Proxy<Boolean, Boolean, Boolean, Boolean, Boolean, Boolean>, Draft6<Boolean, Boolean, Boolean, Boolean, Boolean, Boolean>> selected = query.queryable(PgArrayRecord.class).orderBy(p -> p.id().asc()).select(p -> {
            PgArrayExpression<Integer, PgArrayValue<Integer>> a = PgSqlDsl.of(p).view(p.numbers(), PgSqlTypes.array(Integer.class));
            return Select.DRAFT.of(a.anyEqValue(1), a.anyEqValue(9), a.containsAllValues(Arrays.asList(1, 1)),
                    a.overlapsValues(Arrays.asList(2, 4)), a.containsAllValues(Collections.emptyList()), a.anyEq(p.id()));
        });
        List<Draft6<Boolean, Boolean, Boolean, Boolean, Boolean, Boolean>> rows = selected.toList();
        assertEquals(Arrays.asList(true, false, null), rows.stream().map(r -> r.getValue1()).collect(Collectors.toList()));
        assertEquals(Arrays.asList(null, false, null), rows.stream().map(r -> r.getValue2()).collect(Collectors.toList()));
        assertEquals(Arrays.asList(true, false, null), rows.stream().map(r -> r.getValue3()).collect(Collectors.toList()));
        assertEquals(Arrays.asList(true, false, null), rows.stream().map(r -> r.getValue4()).collect(Collectors.toList()));
        assertEquals(Arrays.asList(true, true, null), rows.stream().map(r -> r.getValue5()).collect(Collectors.toList()));
        assertEquals(Arrays.asList(true, false, null), rows.stream().map(r -> r.getValue6()).collect(Collectors.toList()));
        assertEquals(selected.toSQL(), selected.cloneQueryable().toSQL());
        assertEquals(1L, query.queryable(PgArrayRecord.class).where(p -> PgSqlDsl.of(p).view(p.labels(), PgSqlTypes.array(String.class)).anyEqValue("中😀").eq(true)).count());
        assertTrue(query.queryable(PgArrayRecord.class).where(p -> p.id().eq(1)).selectColumn(p -> PgSqlDsl.of(p)
                .value(Arrays.asList(1, 2), PgSqlTypes.array(Integer.class)).containedByValues(Arrays.asList(1, 2, 3))).singleNotNull());
    }

    @Test
    void elementAccessUsesSqlIndexesAndReturnsScalarTypes() {
        List<Draft6<Integer, Integer, Integer, Integer, Integer, Integer>> selected = query.queryable(PgArrayRecord.class).orderBy(p -> p.id().asc()).select(p -> {
            PgArrayExpression<Integer, PgArrayValue<Integer>> a = PgSqlDsl.of(p).view(p.numbers(), PgSqlTypes.array(Integer.class));
            return Select.DRAFT.of(a.cardinality(), a.dimensionLength(1), a.dimensionLength(2),
                    a.elementAtSqlIndex(1), a.elementAtSqlIndex(2), a.elementAtSqlIndex(0));
        }).toList();
        assertEquals(Arrays.asList(3, 0, null), selected.stream().map(r -> r.getValue1()).collect(Collectors.toList()));
        assertEquals(Arrays.asList(3, null, null), selected.stream().map(r -> r.getValue2()).collect(Collectors.toList()));
        assertTrue(selected.stream().allMatch(r -> r.getValue3() == null && r.getValue5() == null && r.getValue6() == null));
        assertEquals(Arrays.asList(1, null, null), selected.stream().map(r -> r.getValue4()).collect(Collectors.toList()));
        assertEquals("中😀", query.queryable(PgArrayRecord.class).where(p -> p.id().eq(1)).selectColumn(p ->
                PgSqlDsl.of(p).view(p.labels(), PgSqlTypes.array(String.class)).elementAtSqlIndex(2)).singleNotNull());
    }

    @Test
    void fullValuesAndBusinessListsSurviveProjectionUpdateAndRollback() {
        PgArrayRecord original = query.queryable(PgArrayRecord.class).where(p -> p.id().eq(1)).singleNotNull();
        assertEquals(Arrays.asList("a", "中😀", "NULL", null), original.getLabels());
        assertEquals(original.getNumbers(), query.queryable(PgArrayRecord.class).where(p -> p.id().eq(1)).selectColumn(p ->
                PgSqlDsl.of(p).view(p.numbers(), PgSqlTypes.array(Integer.class))).singleNotNull());
        assertEquals(original.getLabels(), query.queryable(PgArrayRecord.class).where(p -> p.id().eq(1)).selectColumn(p ->
                PgSqlDsl.of(p).view(p.labels(), PgSqlTypes.array(String.class))).singleNotNull());
        assertEquals(PgSqlTypes.array(Long.class).value(Arrays.asList(1L, null, 2L)),
                query.queryable(PgArrayRecord.class).where(p -> p.id().eq(1)).selectColumn(p ->
                        PgSqlDsl.of(p).nativeCast(p.numbers(), PgSqlTypes.array(Long.class))).singleNotNull());
        try (Transaction transaction = query.getEasyQueryClient().beginTransaction()) {
            assertEquals(1L, query.updatable(PgArrayRecord.class).setColumns(p -> {
                p.numbers().set(PgSqlDsl.of(p).value(Arrays.asList(4, 5), PgSqlTypes.array(Integer.class)));
                p.labels().set(Arrays.asList("changed", null));
            }).where(p -> p.id().eq(1)).executeRows());
            PgArrayRecord changed = query.queryable(PgArrayRecord.class).where(p -> p.id().eq(1)).singleNotNull();
            assertEquals(Arrays.asList(4, 5), changed.getNumbers().elements());
            assertEquals(Arrays.asList("changed", null), changed.getLabels());
            transaction.rollback();
        }
        assertEquals(original.getNumbers(), query.queryable(PgArrayRecord.class).where(p -> p.id().eq(1)).singleNotNull().getNumbers());
        assertEquals(PgSqlTypes.array(Integer.class).value(Collections.emptyList()), query.queryable(PgArrayRecord.class).where(p -> p.id().eq(1))
                .selectColumn(p -> PgSqlDsl.of(p).value(Collections.<Integer>emptyList(), PgSqlTypes.array(Integer.class))).singleNotNull());
        assertNull(query.queryable(PgArrayRecord.class).where(p -> p.id().eq(1))
                .selectColumn(p -> PgSqlDsl.of(p).value(null, PgSqlTypes.array(Integer.class))).toList().get(0));
    }

    @Test
    void derivedCteColumnsRetainBusinessListsAndTypedArrays() {
        EntityQueryable<Draft3Proxy<Integer, List<String>, PgArrayValue<Integer>>, Draft3<Integer, List<String>, PgArrayValue<Integer>>> inner = query.queryable(PgArrayRecord.class).where(p -> p.id().eq(1)).select(p -> Select.DRAFT.of(p.id(),
                PgSqlDsl.of(p).view(p.labels(), PgSqlTypes.array(String.class)),
                PgSqlDsl.of(p).view(p.numbers(), PgSqlTypes.array(Integer.class)))).toCteAs("eqp_array_readers");
        EntityQueryable<Draft3Proxy<PgArrayValue<Integer>, List<String>, Integer>, Draft3<PgArrayValue<Integer>, List<String>, Integer>> projected = inner.cloneQueryable().select(p -> Select.DRAFT.of(
                PgSqlDsl.of(p).view(p.value3(), PgSqlTypes.array(Integer.class)),
                PgSqlDsl.of(p).view(p.value2(), PgSqlTypes.array(String.class)), p.value1()));
        Draft3<PgArrayValue<Integer>, List<String>, Integer> row = projected.singleNotNull();
        assertEquals(PgSqlTypes.array(Integer.class).value(Arrays.asList(1, null, 2)), row.getValue1());
        assertEquals(Arrays.asList("a", "中😀", "NULL", null), row.getValue2());
        assertEquals(1, row.getValue3());
        assertEquals(row.getValue2(), projected.cloneQueryable().singleNotNull().getValue2());
        assertEquals(row.getValue2(), inner.singleNotNull().getValue2());
    }

    @Test
    void nativeArrayOperatorsRemainEligibleForGinIndexes() throws SQLException {
        Connection owner = schema.connection();
        try (Statement statement = owner.createStatement()) {
            statement.execute("CREATE INDEX eqp_array_gin ON " + schema.qualified("eqp_pg_array") + " USING GIN(numbers)");
        }
        List<EntityQueryable<PgArrayRecordProxy, PgArrayRecord>> candidates = Arrays.asList(query.queryable(PgArrayRecord.class).where(p -> PgSqlDsl.of(p).view(p.numbers(), PgSqlTypes.array(Integer.class)).containsAllValues(Arrays.asList(1)).eq(true)),
                query.queryable(PgArrayRecord.class).where(p -> PgSqlDsl.of(p).view(p.numbers(), PgSqlTypes.array(Integer.class)).overlapsValues(Arrays.asList(2, 3)).eq(true)));
        owner.setSchema(schema.name());
        owner.setAutoCommit(false);
        try (Statement settings = owner.createStatement()) {
            settings.execute("SET LOCAL enable_seqscan = off");
            for (EntityQueryable<PgArrayRecordProxy, PgArrayRecord> candidate : candidates) {
                ToSQLResult rendered = candidate.toSQLResult();
                try (PreparedStatement explain = owner.prepareStatement("EXPLAIN (COSTS FALSE) " + rendered.getSQL())) {
                    com.easy.query.core.util.EasyJdbcExecutorUtil.setPreparedStatement(explain, rendered.getSqlContext().getParameters(), query.getRuntimeContext().getJdbcTypeHandlerManager());
                    try (ResultSet result = explain.executeQuery()) {
                        StringBuilder plan = new StringBuilder();
                        while (result.next()) plan.append(result.getString(1)).append('\n');
                        assertTrue(plan.toString().contains("eqp_array_gin"), plan.toString());
                    }
                }
            }
        } finally {
            owner.rollback();
            owner.setAutoCommit(true);
        }
    }

    @Test
    void wholeArrayAndBusinessListRetainTheirReadersInDraftProjections() {
        EntityQueryable<Draft2Proxy<List<String>, PgArrayValue<Integer>>, Draft2<List<String>, PgArrayValue<Integer>>> selected = query.queryable(PgArrayRecord.class).where(p -> p.id().eq(1)).select(p -> Select.DRAFT.of(
                PgSqlDsl.of(p).view(p.labels(), PgSqlTypes.array(String.class)),
                PgSqlDsl.of(p).view(p.numbers(), PgSqlTypes.array(Integer.class))));
        Draft2<List<String>, PgArrayValue<Integer>> row = selected.singleNotNull();
        assertEquals(Arrays.asList("a", "中😀", "NULL", null), row.getValue1());
        assertEquals(PgSqlTypes.array(Integer.class).value(Arrays.asList(1, null, 2)), row.getValue2());
        assertEquals(row.getValue1(), selected.cloneQueryable().singleNotNull().getValue1());
    }
}
