package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;

import java.time.LocalDateTime;
import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import org.postgresql.ds.PGSimpleDataSource;
import java.sql.Statement;
import java.util.Arrays;
import com.zaxxer.hikari.HikariDataSource;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.core.draft.proxy.Draft6Proxy;
import com.easy.query.core.proxy.core.draft.Draft6;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.array.PgArrayValue;
import com.easy.query.core.proxy.core.draft.proxy.Draft4Proxy;
import com.easy.query.core.proxy.core.draft.Draft4;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgIntervalExpression;
import com.easy.query.core.basic.api.select.Query;

import static org.junit.jupiter.api.Assertions.*;

class PostgresShardingDslIT {
    private static final String COLUMNS = " (id INTEGER PRIMARY KEY, name VARCHAR(200), amount DECIMAL(19,4), created TIMESTAMP(6))";

    @Test
    void rowLocalExpressionsCanBeEvaluatedBeforeShardMerge() throws SQLException {
        PGSimpleDataSource source = PostgresTestDatabase.dataSource();
        try (PostgresTestDatabase.Schema schema = PostgresTestDatabase.createSchema(source, "eqp_shard_"); Statement ddl = schema.connection().createStatement()) {
            List<String> tables = Arrays.asList("first_shard", "second_shard");
            for (String table : tables)
                ddl.execute("CREATE TABLE " + schema.qualified(table) + COLUMNS);
            ShardingDslContract.verify(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure, tables, PostgresShardingDslIT::verifyNativeViews);
        }
    }

    @Test
    void independentSourcesKeepIdenticalTableNamesInSeparateSchemas() throws SQLException {
        PGSimpleDataSource firstSource = PostgresTestDatabase.dataSource();
        PGSimpleDataSource secondSource = PostgresTestDatabase.dataSource();
        try (PostgresTestDatabase.Schema first = PostgresTestDatabase.createSchema(firstSource, "eqp_multisource_a_");
             PostgresTestDatabase.Schema second = PostgresTestDatabase.createSchema(secondSource, "eqp_multisource_b_");
             HikariDataSource primary = ShardingDslContract.pool(firstSource, null);
             HikariDataSource secondary = ShardingDslContract.pool(secondSource, null)) {
            for (PostgresTestDatabase.Schema schema : Arrays.asList(first, second)) {
                try (Statement ddl = schema.connection().createStatement()) {
                    ddl.execute("CREATE TABLE " + schema.qualified("product") + COLUMNS);
                }
            }
            ShardingDslContract.verify(Arrays.asList(new ShardingDslContract.Shard("primary", primary, "product"),
                            new ShardingDslContract.Shard("secondary", secondary, "product")),
                    new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure, PostgresShardingDslIT::verifyNativeViews);
            assertEquals(0, primary.getHikariPoolMXBean().getActiveConnections());
            assertEquals(0, secondary.getHikariPoolMXBean().getActiveConnections());
        }
    }

    private static void verifyNativeViews(DefaultEasyEntityQuery query) {
        ShardingDslContract.verifyJsonPredicate(query, product -> PgSqlDsl.of(product).nativeCast(product.name(), PgSqlTypes.json()).toJsonb()
                .setPath(Arrays.asList("added"), JsonInput.encoded("true"), true).removeKey("code").hasKey("added"));
        PgSqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
        EntityQueryable<Draft6Proxy<Integer, Integer, Boolean, Boolean, Integer, Integer>, Draft6<Integer, Integer, Boolean, Boolean, Integer, Integer>> arrays = query.queryable(ShardedProduct.class).orderBy(p -> p.id().asc()).select(p -> {
            PgArrayExpression<Integer, PgArrayValue<Integer>> values = PgSqlDsl.of(p).value(Arrays.asList(1, 2), PgSqlTypes.array(Integer.class));
            return Select.DRAFT.of(p.id(), values.cardinality(), values.anyEq(p.id()), values.containsAllValues(Arrays.asList(2)), values.elementAtSqlIndex(1), values.dimensionLength(1));
        });
        for (Draft6<Integer, Integer, Boolean, Boolean, Integer, Integer> row : arrays.toList()) {
            assertEquals(2, row.getValue2());
            assertTrue(row.getValue3());
            assertTrue(row.getValue4());
            assertEquals(1, row.getValue5());
            assertEquals(2, row.getValue6());
        }
        assertEquals(2, arrays.cloneQueryable().toList().size());
        EntityQueryable<Draft4Proxy<Integer, PgInterval, PgInterval, LocalDateTime>, Draft4<Integer, PgInterval, PgInterval, LocalDateTime>> calendar = query.queryable(ShardedProduct.class).orderBy(p -> p.id().asc()).select(p -> {
            PgIntervalExpression<PgInterval> span = PgSqlDsl.of(p).value(PgInterval.of(1, 0, 0), PgSqlTypes.interval());
            return Select.DRAFT.of(p.id(), span, span.plus(span).negated(), span.addToDateTime(p.created()));
        });
        for (Draft4<Integer, PgInterval, PgInterval, LocalDateTime> row : calendar.toList()) {
            assertEquals(PgInterval.of(1, 0, 0), row.getValue2());
            assertEquals(PgInterval.of(-2, 0, 0), row.getValue3());
            assertEquals(LocalDateTime.of(2024, 1, 30, 0, 0).plusDays(row.getValue1()).plusMonths(1), row.getValue4());
        }
        assertEquals(2, calendar.cloneQueryable().toList().size());
        Query<Boolean> aggregateElement = query.queryable(ShardedProduct.class).selectColumn(p ->
                PgSqlDsl.of(p).value(Arrays.asList(BigDecimal.ONE), PgSqlTypes.array(BigDecimal.class)).anyEq(p.amount().sumBigDecimal()));
        assertTrue(ShardingDslContract.hasShardGuard(assertThrows(RuntimeException.class, aggregateElement::toList)));
    }
}
