package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.jdbc.executor.internal.props.BasicJdbcProperty;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.parameter.EasyConstSQLParameter;
import com.easy.query.core.basic.jdbc.types.EasyParameter;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayType;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayTypeHandler;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.jdbc.PgArray;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PostgresArrayMappingIT {
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void typedArraysRoundTripThroughEasyQueryInTextAndBinaryModes(int preparationThreshold) throws SQLException {
        var source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(preparationThreshold);
        var query = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        PgSqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
        var samples = List.of(
                PgSqlTypes.array(String.class).value(Arrays.asList("", "NULL", "O'Reilly \\ \" {} , 中😀", null)),
                PgSqlTypes.array(Integer.class).value(Arrays.asList(Integer.MIN_VALUE, null, Integer.MAX_VALUE)),
                PgSqlTypes.array(Long.class).value(Arrays.asList(Long.MIN_VALUE, null, Long.MAX_VALUE)),
                PgSqlTypes.array(BigDecimal.class).value(Arrays.asList(new BigDecimal("12345678901234567890123456789.123456789"), null)),
                PgSqlTypes.array(Boolean.class).value(Arrays.asList(true, false, null)),
                PgSqlTypes.array(UUID.class).value(Arrays.asList(UUID.fromString("33333333-4444-5555-6666-777777777777"), null)),
                PgSqlTypes.array(LocalDate.class).value(Arrays.asList(LocalDate.of(2024, 2, 29), LocalDate.of(1500, 2, 28), LocalDate.of(0, 1, 1), LocalDate.MAX, null)),
                PgSqlTypes.array(LocalDateTime.class).value(Arrays.asList(LocalDateTime.of(2024, 3, 31, 2, 30, 0, 123456000), LocalDateTime.of(0, 1, 1, 0, 0), LocalDateTime.MAX, null)),
                PgSqlTypes.array(Integer.class).value(List.of()));
        for (var sample : samples) {
            String sql = "SELECT CAST(? AS " + sample.type().sqlName() + ")";
            assertEquals(sample, query.getEasyQueryClient().sqlQuery(sql, PgArrayValue.class, List.of(sample)).get(0));
        }
        assertNull(query.getEasyQueryClient().sqlQuery("SELECT NULL::integer[]", PgArrayValue.class).get(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nondefaultBoundsAndMultipleDimensionsAreRejectedBeforeDataCanBeMisrepresented(int threshold) throws SQLException {
        var source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(threshold);
        var handler = new PgArrayTypeHandler();
        try (var connection = source.getConnection()) {
            for (String sql : List.of("SELECT '[0:1]={1,2}'::integer[]", "SELECT '[-5:-4]={1,2}'::integer[]", "SELECT ARRAY[[1,2],[3,4]]")) {
                try (var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    var array = (PgArray) rows.getArray(1);
                    if (threshold == -1) assertTrue(array.isBinary(), "The binary boundary must be exercised");
                    SQLException error = assertThrows(SQLException.class, () -> handler.getValue(new BasicJdbcProperty(0, PgArrayValue.class), result(array)));
                    assertTrue(error.getMessage().contains("lower bound") || error.getMessage().contains("one-dimensional"), error.getMessage());
                    assertNull(array.toBytes());
                    assertNull(array.toString(), "Rejected arrays must be freed");
                }
            }
        }
    }

    @Test
    void freeingBoundArraysDoesNotBreakStatementReuseOrBatches() throws SQLException {
        var source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(-1);
        var type = PgSqlTypes.array(Integer.class);
        try (var connection = source.getConnection(); var create = connection.createStatement()) {
            create.execute("CREATE TEMP TABLE eqp_array_batch (data integer[]) ON COMMIT PRESERVE ROWS");
            try (var statement = connection.prepareStatement("INSERT INTO eqp_array_batch VALUES (?)")) {
                var handler = new PgArrayTypeHandler();
                for (var values : List.of(Arrays.asList(1, null), List.of(2), List.<Integer>of())) {
                    var parameter = new EasyParameter(statement, List.of(new EasyConstSQLParameter(null, null, type.value(values))));
                    parameter.setIndex(0);
                    handler.setParameter(parameter);
                    statement.addBatch();
                }
                assertArrayEquals(new int[]{1, 1, 1}, statement.executeBatch());
            }
            try (var rows = create.executeQuery("SELECT sum(cardinality(data)), count(*) FROM eqp_array_batch")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
                assertEquals(3, rows.getInt(2));
            }
        }
    }

    private static StreamResultSet result(PgArray array) {
        return (StreamResultSet) Proxy.newProxyInstance(StreamResultSet.class.getClassLoader(), new Class<?>[]{StreamResultSet.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getObject")) return array;
                    throw new AssertionError("Unexpected result operation: " + method.getName());
                });
    }
}
