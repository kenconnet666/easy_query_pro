package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.sql.GroupKeys;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestEvent;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestEventProxy;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("java.util.TimeZone.default")
class PostgresDateBinIT {
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void fixedBinsKeepNegativeBoundariesNullsAndLocalClockFields(int prepareThreshold) {
        var previousZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        try {
            var query = query(prepareThreshold);
            var source = rows(query, Arrays.asList(
                    LocalDateTime.parse("2024-01-01T12:34:56.123456"),
                    LocalDateTime.parse("2024-01-01T12:32:30"),
                    LocalDateTime.parse("1969-12-31T23:59:59.999999"),
                    LocalDateTime.parse("1969-12-31T23:47:30"),
                    null,
                    LocalDateTime.parse("2024-03-10T02:30:00.123456"),
                    LocalDateTime.parse("2024-11-03T01:30:00.123456")));
            var origin = LocalDateTime.of(1970, 1, 1, 0, 2, 30);
            var selected = source.select(p -> Select.DRAFT.of(p.delta(),
                    PgSqlDsl.of(p).dateBin(p.occurred(), Duration.ofMinutes(15), origin)));
            var actual = selected.cloneQueryable().orderBy(p -> p.value1().asc()).toList();
            assertEquals(Arrays.asList(
                            LocalDateTime.parse("2024-01-01T12:32:30"),
                            LocalDateTime.parse("2024-01-01T12:32:30"),
                            LocalDateTime.parse("1969-12-31T23:47:30"),
                            LocalDateTime.parse("1969-12-31T23:47:30"),
                            null,
                            LocalDateTime.parse("2024-03-10T02:17:30"),
                            LocalDateTime.parse("2024-11-03T01:17:30")),
                    actual.stream().map(row -> row.getValue2()).toList());
            assertEquals(selected.cloneQueryable().toSQL(), selected.cloneQueryable().toSQL());

            // 先把含绑定参数的函数固定为列，避免 SELECT/GROUP BY 出现不同参数位置的等价表达式。
            var bins = selected.cloneQueryable().toCteAs("fixed_bins");
            var grouped = bins.groupBy(p -> GroupKeys.of(p.value2()))
                    .select(g -> Select.DRAFT.of(g.key1(), g.count()));
            assertEquals(5L, grouped.cloneQueryable().count());
            assertEquals(7L, grouped.cloneQueryable().toList().stream().mapToLong(row -> row.getValue2()).sum());
            var visible = grouped.cloneQueryable().where(p -> p.value1().isNotNull()).orderBy(p -> p.value1().asc());
            var page = visible.cloneQueryable().toPageResult(2, 2);
            assertEquals(4L, page.getTotal());
            assertEquals(List.of(LocalDateTime.parse("2024-03-10T02:17:30"), LocalDateTime.parse("2024-11-03T01:17:30")),
                    page.getData().stream().map(row -> row.getValue1()).toList());
        } finally {
            TimeZone.setDefault(previousZone);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void microsecondBinsFloorBeforeOriginWithoutRounding(int prepareThreshold) {
        var query = query(prepareThreshold);
        var origin = LocalDateTime.parse("2024-01-01T00:00:00.000001");
        var result = rows(query, List.of(origin.plusNanos(1_000), origin.minusNanos(1_000),
                origin.plusNanos(2_000), origin.plusNanos(3_000)))
                .select(p -> Select.DRAFT.of(p.delta(), PgSqlDsl.of(p).dateBin(p.occurred(), Duration.ofNanos(2_000), origin)))
                .orderBy(p -> p.value1().asc()).toList();
        assertEquals(List.of(origin, LocalDateTime.parse("2023-12-31T23:59:59.999999"),
                        origin.plusNanos(2_000), origin.plusNanos(2_000)),
                result.stream().map(row -> row.getValue2()).toList());
    }

    private static DefaultEasyEntityQuery query(int prepareThreshold) {
        var source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(prepareThreshold);
        source.setOptions("-c TimeZone=Asia/Tokyo");
        var query = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        PgSqlExtensions.configureTypeHandlers(query.getEasyQueryClient().getRuntimeContext().getJdbcTypeHandlerManager());
        return query;
    }

    private static EntityQueryable<TestEventProxy, TestEvent> rows(DefaultEasyEntityQuery query, List<LocalDateTime> times) {
        String sql = IntStream.range(0, times.size()).mapToObj(index ->
                "SELECT CAST(? AS TIMESTAMP) AS occurred," + index + " AS delta").collect(java.util.stream.Collectors.joining(" UNION ALL "));
        return query.queryable(sql, TestEvent.class, new ArrayList<>(times));
    }
}
