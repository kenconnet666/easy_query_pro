package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.proxy.sql.Select;
import com.easy.query.core.enums.sharding.ConnectionModeEnum;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsQuery;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsVector;
import org.dpdns.zerodep.easyquery.pro.test.entity.PgSearchDocument;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PostgresTextSearchTypeIT {
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nativeValuesBindWithTheirOidAndReadServerNormalization(int prepareThreshold) throws SQLException {
        var source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(prepareThreshold);
        try (var schema = PostgresTestDatabase.createSchema(source, "eqp_pg_search_type_")) {
            try (var statement = schema.connection().createStatement()) {
                statement.execute("CREATE TABLE " + schema.qualified("eqp_search_document")
                        + " (id INTEGER PRIMARY KEY,title TEXT,body TEXT,unindexed TEXT,document "
                        + PgSqlTypes.tsvector().ddlType(SqlDialect.POSTGRESQL) + ",condition "
                        + PgSqlTypes.tsquery().ddlType(SqlDialect.POSTGRESQL) + ")");
            }
            var query = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
            PgSqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
            var input = row(1, new PgTsVector("fat:2B,4C a:1A cat:5D"), new PgTsQuery("fat&rat"));
            assertEquals(3L, query.insertable(List.of(input, row(2, new PgTsVector(""), new PgTsQuery("")), row(3, null, null))).executeRows());
            var expectedVector = new PgTsVector("'a':1A 'cat':5 'fat':2B,4C");
            var expectedQuery = new PgTsQuery("'fat' & 'rat'");
            var actual = query.queryable(PgSearchDocument.class).orderBy(p -> p.id().asc()).toList();
            assertEquals(Arrays.asList(expectedVector, new PgTsVector(""), null), actual.stream().map(PgSearchDocument::getDocument).toList());
            assertEquals(Arrays.asList(expectedQuery, new PgTsQuery(""), null), actual.stream().map(PgSearchDocument::getCondition).toList());
            assertNotEquals(input.getDocument(), actual.get(0).getDocument(), "Java encoded equality does not normalize lexemes");
            assertEquals(expectedVector, query.getEasyQueryClient().sqlQuery(
                    "SELECT document FROM eqp_search_document WHERE id=1", PgTsVector.class).get(0));
            assertEquals(expectedQuery, query.queryable(PgSearchDocument.class).where(p -> p.id().eq(1)).selectColumn(p -> p.condition()).singleNotNull());
            var projected = query.queryable(PgSearchDocument.class).select(p -> Select.DRAFT.of(p.id(), p.document(), p.condition()))
                    .toCteAs("typed_search_values").orderBy(p -> p.value1().asc());
            assertEquals(projected.toSQL(), projected.cloneQueryable().toSQL());
            assertEquals(Arrays.asList(expectedVector, new PgTsVector(""), null), projected.cloneQueryable().toList().stream().map(row -> row.getValue2()).toList());
            assertEquals(1, query.queryable(PgSearchDocument.class).where(p -> {
                p.document().eq(expectedVector);
                p.condition().eq(expectedQuery);
            }).singleNotNull().getId());

            var constants = query.queryable(PgSearchDocument.class).where(p -> p.id().eq(1)).select(p -> Select.DRAFT.of(
                    PgSqlDsl.of(p).value(new PgTsVector("'coffee':1"), PgSqlTypes.tsvector()),
                    PgSqlDsl.of(p).value(new PgTsQuery("'coffee'"), PgSqlTypes.tsquery()),
                    PgSqlDsl.of(p).nativeCast(p.title(), PgSqlTypes.tsvector()),
                    PgSqlDsl.of(p).value(null, PgSqlTypes.tsvector()))).singleNotNull();
            assertEquals(new PgTsVector("'coffee':1"), constants.getValue1());
            assertEquals(new PgTsQuery("'coffee'"), constants.getValue2());
            assertEquals(new PgTsVector("'a' 'b'"), constants.getValue3());
            assertNull(constants.getValue4());
            var quotedVector = new PgTsVector("'O''Reilly':1 '中文':2");
            var quotedQuery = new PgTsQuery("'O''Reilly' | '中文'");
            assertEquals(quotedVector, query.getEasyQueryClient().sqlQuery(
                    "SELECT CAST(? AS tsvector)", PgTsVector.class, List.of(quotedVector)).get(0));
            assertEquals(quotedQuery, query.getEasyQueryClient().sqlQuery(
                    "SELECT CAST(? AS tsquery)", PgTsQuery.class, List.of(quotedQuery)).get(0));
            assertEquals(Arrays.asList((PgTsVector) null), query.queryable(PgSearchDocument.class).where(p -> p.id().eq(1))
                    .selectColumn(p -> PgSqlDsl.of(p).value(null, PgSqlTypes.tsvector())).toList());
            assertEquals(1L, query.updatable(PgSearchDocument.class)
                    .setColumns(p -> p.document().set(new PgTsVector("'changed':1"))).where(p -> p.id().eq(2)).executeRows());
            assertEquals(new PgTsVector("'changed':1"), query.queryable(PgSearchDocument.class).where(p -> p.id().eq(2)).singleNotNull().getDocument());

            var invalid = assertThrows(RuntimeException.class, () -> query.getEasyQueryClient().sqlQuery(
                    "SELECT CAST(? AS tsquery)", PgTsQuery.class, List.of(new PgTsQuery("'coffee' &"))));
            assertTrue(hasState(invalid, "42601"), invalid.toString());
            var wrongType = assertThrows(RuntimeException.class, () -> query.getEasyQueryClient().sqlQuery("SELECT 'coffee'::text", PgTsVector.class));
            assertTrue(hasMessage(wrongType, "Expected PostgreSQL tsvector"), wrongType.toString());
        }
    }

    @Test
    void forcedUnsupportedBinaryTransferNeverBecomesASilentSqlNull() {
        var source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(-1);
        source.setBinaryTransferEnable("tsvector,tsquery");
        var query = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure).getEasyQueryClient();
        PgSqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
        var vector = assertThrows(RuntimeException.class, () -> query.sqlQuery("SELECT 'coffee:1'::tsvector", PgTsVector.class));
        var condition = assertThrows(RuntimeException.class, () -> query.sqlQuery("SELECT 'coffee'::tsquery", PgTsQuery.class));
        assertTrue(hasMessage(vector, "binaryTransferEnable"), vector.toString());
        assertTrue(hasMessage(condition, "binaryTransferEnable"), condition.toString());
        assertEquals(Arrays.asList((PgTsVector) null), query.sqlQuery("SELECT NULL::tsvector", PgTsVector.class));
        assertEquals(Arrays.asList((PgTsQuery) null), query.sqlQuery("SELECT NULL::tsquery", PgTsQuery.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void connectionLimitedShardCachingKeepsNativeDriverValuesReadable(int prepareThreshold) throws SQLException {
        var source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(prepareThreshold);
        try (var schema = PostgresTestDatabase.createSchema(source, "eqp_pg_search_shard_"); var statement = schema.connection().createStatement()) {
            var tables = List.of("first_shard", "second_shard");
            for (String table : tables)
                statement.execute("CREATE TABLE " + schema.qualified(table)
                        + " (id INTEGER PRIMARY KEY,name VARCHAR(200),amount DECIMAL(19,4),created TIMESTAMP(6))");
            ShardingDslContract.verify(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure, tables, query -> {
                PgSqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
                var vector = new PgTsVector("'coffee':1");
                var condition = new PgTsQuery("'coffee'");
                var rows = query.queryable(ShardedProduct.class);
                // 两分表拆成两个执行组才进入物化分支；只有模式而仍单组时，上游会走直接执行捷径。
                rows.getClientQueryable().useShardingConfigure(1, ConnectionModeEnum.CONNECTION_STRICTLY);
                var result = rows.orderBy(p -> p.id().asc()).select(p -> Select.DRAFT.of(p.id(),
                        PgSqlDsl.of(p).value(vector, PgSqlTypes.tsvector()), PgSqlDsl.of(p).value(condition, PgSqlTypes.tsquery()))).toList();
                assertEquals(2, result.size());
                assertEquals(List.of(1, 2), result.stream().map(row -> row.getValue1()).toList());
                assertEquals(List.of(vector, vector), result.stream().map(row -> row.getValue2()).toList());
                assertEquals(List.of(condition, condition), result.stream().map(row -> row.getValue3()).toList());
            });
        }
    }

    private static PgSearchDocument row(int id, PgTsVector vector, PgTsQuery query) {
        var row = new PgSearchDocument();
        row.setId(id);
        row.setTitle("b a");
        row.setDocument(vector);
        row.setCondition(query);
        return row;
    }

    private static boolean hasState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) return true;
        return false;
    }

    private static boolean hasMessage(Throwable failure, String text) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException && cause.getMessage() != null && cause.getMessage().contains(text))
                return true;
        return false;
    }
}
