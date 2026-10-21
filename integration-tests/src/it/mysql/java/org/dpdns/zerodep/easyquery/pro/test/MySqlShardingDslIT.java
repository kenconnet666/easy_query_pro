package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;

import java.time.Duration;
import java.time.LocalDateTime;
import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.util.Arrays;
import com.zaxxer.hikari.HikariDataSource;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.core.draft.proxy.Draft5Proxy;
import com.easy.query.core.proxy.core.draft.Draft5;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.temporal.MySqlDurationExpression;

import static org.junit.jupiter.api.Assertions.*;

class MySqlShardingDslIT {
    private static final String COLUMNS = "id INTEGER PRIMARY KEY, name VARCHAR(200), amount DECIMAL(19,4), created DATETIME(6)";

    @Test
    void rowLocalExpressionsCanBeEvaluatedBeforeShardMerge() throws SQLException {
        MysqlDataSource source = MySqlTestDatabase.dataSource();
        try (MySqlTestDatabase.Table first = MySqlTestDatabase.createTable(source, "eqp_shard_a_", COLUMNS);
             MySqlTestDatabase.Table second = MySqlTestDatabase.createTable(source, "eqp_shard_b_", COLUMNS)) {
            ShardingDslContract.verify(source, new MySQLDatabaseConfiguration(), MySqlExtensions::configure, Arrays.asList(first.name(), second.name()), MySqlShardingDslIT::verifyNativeViews);
        }
    }

    @Test
    void independentPoolsCanUseDifferentPreparedStatementModes() throws SQLException {
        MysqlDataSource firstSource = MySqlTestDatabase.dataSource();
        firstSource.setUseServerPrepStmts(false);
        MysqlDataSource secondSource = MySqlTestDatabase.dataSource();
        secondSource.setUseServerPrepStmts(true);
        try (MySqlTestDatabase.Table first = MySqlTestDatabase.createTable(firstSource, "eqp_multisource_a_", COLUMNS);
             MySqlTestDatabase.Table second = MySqlTestDatabase.createTable(secondSource, "eqp_multisource_b_", COLUMNS);
             HikariDataSource primary = ShardingDslContract.pool(firstSource, null);
             HikariDataSource secondary = ShardingDslContract.pool(secondSource, null)) {
            ShardingDslContract.verify(Arrays.asList(new ShardingDslContract.Shard("primary", primary, first.name()),
                            new ShardingDslContract.Shard("secondary", secondary, second.name())),
                    new MySQLDatabaseConfiguration(), MySqlExtensions::configure, MySqlShardingDslIT::verifyNativeViews);
            assertEquals(0, primary.getHikariPoolMXBean().getActiveConnections());
            assertEquals(0, secondary.getHikariPoolMXBean().getActiveConnections());
        }
    }

    private static void verifyNativeViews(DefaultEasyEntityQuery query) {
        ShardingDslContract.verifyJsonPredicate(query, product -> MySqlDsl.of(product).nativeCast(product.name(), MySqlTypes.json())
                .setPath(JsonPath.root().key("added"), JsonInput.encoded("true")).removePath(JsonPath.root().key("code"))
                .containsJson(JsonInput.encoded("true"), JsonPath.root().key("added")));
        MySqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
        Duration duration = Duration.ofHours(27).plusNanos(1000);
        EntityQueryable<Draft5Proxy<Integer, Duration, Duration, BigDecimal, LocalDateTime>, Draft5<Integer, Duration, Duration, BigDecimal, LocalDateTime>> times = query.queryable(ShardedProduct.class).orderBy(p -> p.id().asc()).select(p -> {
            MySqlDurationExpression<Duration> span = MySqlDsl.of(p).value(duration, MySqlTypes.durationTime(6));
            return Select.DRAFT.of(p.id(), span, span.plus(span).negated(), span.toSeconds(), span.addToDateTime(p.created()));
        });
        for (Draft5<Integer, Duration, Duration, BigDecimal, LocalDateTime> row : times.toList()) {
            assertEquals(duration, row.getValue2());
            assertEquals(duration.multipliedBy(-2), row.getValue3());
            NativeDecimalContract.equalValue(new BigDecimal("97200.000001"), row.getValue4());
            assertEquals(LocalDateTime.of(2024, 1, 30, 0, 0).plusDays(row.getValue1()).plus(duration), row.getValue5());
        }
        assertEquals(2, times.cloneQueryable().toList().size());
    }
}
