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

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.List;

class MySqlShardingDslIT {
    @Test
    void rowLocalExpressionsCanBeEvaluatedBeforeShardMerge() throws SQLException {
        var source = MySqlTestDatabase.dataSource();
        String columns = "id INTEGER PRIMARY KEY, name VARCHAR(200), amount DECIMAL(19,4), created DATETIME(6)";
        try (var first = MySqlTestDatabase.createTable(source, "eqp_shard_a_", columns);
             var second = MySqlTestDatabase.createTable(source, "eqp_shard_b_", columns)) {
            ShardingDslContract.verify(source, new MySQLDatabaseConfiguration(), MySqlExtensions::configure, List.of(first.name(), second.name()), MySqlShardingDslIT::verifyNativeViews);
        }
    }

    private static void verifyNativeViews(DefaultEasyEntityQuery query) {
        ShardingDslContract.verifyJsonPredicate(query, product -> MySqlDsl.of(product).nativeCast(product.name(), MySqlTypes.json())
                .setPath(JsonPath.root().key("added"), JsonInput.encoded("true")).removePath(JsonPath.root().key("code"))
                .containsJson(JsonInput.encoded("true"), JsonPath.root().key("added")));
        MySqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
        var duration = Duration.ofHours(27).plusNanos(1000);
        var times = query.queryable(ShardedProduct.class).orderBy(p -> p.id().asc()).select(p -> {
            var span = MySqlDsl.of(p).value(duration, MySqlTypes.durationTime(6));
            return Select.DRAFT.of(p.id(), span, span.plus(span).negated(), span.toSeconds(), span.addToDateTime(p.created()));
        });
        for (var row : times.toList()) {
            assertEquals(duration, row.getValue2());
            assertEquals(duration.multipliedBy(-2), row.getValue3());
            NativeDecimalContract.equalValue(new BigDecimal("97200.000001"), row.getValue4());
            assertEquals(LocalDateTime.of(2024, 1, 30, 0, 0).plusDays(row.getValue1()).plus(duration), row.getValue5());
        }
        assertEquals(2, times.cloneQueryable().toList().size());
    }
}
