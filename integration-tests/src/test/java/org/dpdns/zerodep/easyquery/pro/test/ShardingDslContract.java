package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.jdbc.executor.EntityExpressionExecutor;
import com.easy.query.core.basic.jdbc.executor.ShardingEntityExpressionExecutor;
import com.easy.query.core.basic.thread.ShardingExecutorService;
import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonInput;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ShardedProductProxy;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

final class ShardingDslContract {
    private ShardingDslContract() {
    }

    static void verify(DataSource source, DatabaseConfiguration database, Consumer<ServiceCollection> extensions, List<String> tables) {
        var client = EasyQueryBootstrapper.defaultBuilderConfiguration().setDefaultDataSource(source)
                .optionConfigure(options -> {
                    options.setDefaultDataSourceMergePoolSize(2);
                    options.setMaxShardingQueryLimit(2);
                }).useDatabaseConfigure(database).customConfigure(extensions)
                .replaceService(EntityExpressionExecutor.class, ShardingEntityExpressionExecutor.class).build();
        client.getRuntimeContext().getQueryConfiguration().applyShardingInitializer(new ShardedProduct.Tables(tables));
        client.getRuntimeContext().getTableRouteManager().addRoute(new ShardedProduct.Route(tables));
        var query = new DefaultEasyEntityQuery(client);
        try {
            query.insertable(List.of(new ShardedProduct(1, "{\"code\":\"one\"}", new BigDecimal("1.2345")),
                    new ShardedProduct(2, "{\"code\":\"two\"}", new BigDecimal("2.3456")))).executeRows();
            assertEquals(2, query.queryable(ShardedProduct.class).count());
            assertEquals(2, query.queryable(ShardedProduct.class).selectColumn(p -> ProDsl.of(p).nativeCast(p.amount(), CommonTypes.decimal(19, 2))).toList().size());
            var selected = query.queryable(ShardedProduct.class).orderBy(p -> p.id().asc()).select(p -> {
                var d = ProDsl.of(p);
                return Select.DRAFT.of(p.id(), d.nativeCast(d.nativeCast(p.amount(), CommonTypes.decimal(19, 2)), CommonTypes.decimal(19, 1)),
                        d.jsonTextAt(p.name(), JsonPath.root().key("code")), d.nullSafeEqualsValue(p.name(), null), d.nullIfValue(p.amount(), BigDecimal.ZERO));
            });
            var rows = selected.toList();
            assertEquals(2, rows.size());
            assertEquals("one", rows.get(0).getValue3());
            assertEquals("two", rows.get(1).getValue3());
            assertFalse(rows.get(0).getValue4());
            NativeDecimalContract.equalValue(new BigDecimal("1.2"), rows.get(0).getValue2());
            NativeDecimalContract.equalValue(new BigDecimal("1.2345"), rows.get(0).getValue5());
            assertEquals(2, selected.cloneQueryable().toList().size());
            assertEquals(2, selected.cloneQueryable().count());
            verifyLegacyAliases(query);
            verifyNativeViews(query);
            assertEquals("two", selected.cloneQueryable().limit(1, 1).singleNotNull().getValue3());
            assertEquals(1, query.queryable(ShardedProduct.class).where(p -> ProDsl.of(p).jsonTextAt(p.name(), JsonPath.root().key("code")).eq("two")).toList().size());
            String sum = client.getRuntimeContext().getService(SqlDialect.class) == SqlDialect.HANA ? "3.57" : "3.58";
            NativeDecimalContract.equalValue(new BigDecimal(sum), query.queryable(ShardedProduct.class).select(p -> Select.DRAFT.of(
                    ProDsl.of(p).nativeCast(p.amount(), CommonTypes.decimal(19, 2)).sumBigDecimal())).singleNotNull().getValue1());
            var unsafe = query.queryable(ShardedProduct.class).selectColumn(p -> ProDsl.of(p).nativeCast(p.amount().sumBigDecimal(), CommonTypes.decimal(19, 1)));
            var failure = assertThrows(RuntimeException.class, unsafe::toList);
            assertTrue(hasShardGuard(failure), failure.toString());
            var hiddenAggregate = query.queryable(ShardedProduct.class).selectColumn(p -> ProDsl.of(p).nativeCast(p.amount().sumBigDecimal().add(1), CommonTypes.decimal(19, 1)));
            assertTrue(hasShardGuard(assertThrows(RuntimeException.class, hiddenAggregate::toList)));
        } finally {
            client.getRuntimeContext().getService(ShardingExecutorService.class).getExecutorService().shutdown();
        }
    }

    private static boolean hasShardGuard(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (current instanceof UnsupportedOperationException && current.getMessage().contains("before shard merging"))
                return true;
        }
        return false;
    }

    private static void verifyNativeViews(DefaultEasyEntityQuery query) {
        var dialect = query.getRuntimeContext().getService(SqlDialect.class);
        var predicates = query.queryable(ShardedProduct.class).orderBy(p -> p.id().asc())
                .select(p -> Select.DRAFT.of(p.id(), nativeJsonPredicate(p, dialect)));
        for (var row : predicates.toList()) assertTrue(row.getValue2());
        for (var row : predicates.cloneQueryable().toList()) assertTrue(row.getValue2());
        assertEquals(2, query.queryable(ShardedProduct.class).where(p -> nativeJsonPredicate(p, dialect).eq(true)).count());
        if (dialect == SqlDialect.MYSQL) {
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
        if (dialect == SqlDialect.HANA) {
            var lobs = query.queryable(ShardedProduct.class).orderBy(p -> p.id().asc()).select(p -> {
                var text = HanaDsl.of(p).nativeCast(p.name(), HanaTypes.nclob());
                var data = HanaDsl.of(p).value(new byte[]{0, 1, (byte) 255}, HanaTypes.blob());
                var json = HanaDsl.of(p).nativeCast(p.name(), HanaTypes.jsonNclob());
                return Select.DRAFT.of(p.id(), text, text.asText(), text.sliceUtf16(1, 1), data.sliceBytes(2, 2), json.textAt(JsonPath.root().key("code")));
            });
            for (var row : lobs.toList()) {
                assertEquals(row.getValue2(), row.getValue3());
                assertEquals("{", row.getValue4());
                assertArrayEquals(new byte[]{1, (byte) 255}, row.getValue5());
                assertTrue(List.of("one", "two").contains(row.getValue6()));
            }
            assertEquals(2, lobs.cloneQueryable().toList().size());
            assertThrows(IllegalArgumentException.class, () -> lobs.cloneQueryable().distinct().toList());
        }
        if (dialect == SqlDialect.POSTGRESQL) {
            PgSqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
            var arrays = query.queryable(ShardedProduct.class).orderBy(p -> p.id().asc()).select(p -> {
                var values = PgSqlDsl.of(p).value(List.of(1, 2), PgSqlTypes.array(Integer.class));
                return Select.DRAFT.of(p.id(), values.cardinality(), values.anyEq(p.id()), values.containsAllValues(List.of(2)), values.elementAtSqlIndex(1), values.dimensionLength(1));
            });
            for (var row : arrays.toList()) {
                assertEquals(2, row.getValue2());
                assertTrue(row.getValue3());
                assertTrue(row.getValue4());
                assertEquals(1, row.getValue5());
                assertEquals(2, row.getValue6());
            }
            assertEquals(2, arrays.cloneQueryable().toList().size());
            var calendar = query.queryable(ShardedProduct.class).orderBy(p -> p.id().asc()).select(p -> {
                var span = PgSqlDsl.of(p).value(PgInterval.of(1, 0, 0), PgSqlTypes.interval());
                return Select.DRAFT.of(p.id(), span, span.plus(span).negated(), span.addToDateTime(p.created()));
            });
            for (var row : calendar.toList()) {
                assertEquals(PgInterval.of(1, 0, 0), row.getValue2());
                assertEquals(PgInterval.of(-2, 0, 0), row.getValue3());
                assertEquals(LocalDateTime.of(2024, 1, 30, 0, 0).plusDays(row.getValue1()).plusMonths(1), row.getValue4());
            }
            assertEquals(2, calendar.cloneQueryable().toList().size());
            var aggregateElement = query.queryable(ShardedProduct.class).selectColumn(p ->
                    PgSqlDsl.of(p).value(List.of(BigDecimal.ONE), PgSqlTypes.array(BigDecimal.class)).anyEq(p.amount().sumBigDecimal()));
            assertTrue(hasShardGuard(assertThrows(RuntimeException.class, aggregateElement::toList)));
        }
    }

    private static BooleanTypeExpression<Boolean> nativeJsonPredicate(ShardedProductProxy product, SqlDialect dialect) {
        return switch (dialect) {
            case MYSQL -> MySqlDsl.of(product).nativeCast(product.name(), MySqlTypes.json())
                    .setPath(JsonPath.root().key("added"), JsonInput.encoded("true")).removePath(JsonPath.root().key("code"))
                    .containsJson(JsonInput.encoded("true"), JsonPath.root().key("added"));
            case POSTGRESQL -> PgSqlDsl.of(product).nativeCast(product.name(), PgSqlTypes.json()).toJsonb()
                    .setPath(List.of("added"), JsonInput.encoded("true"), true).removeKey("code").hasKey("added");
            case HANA ->
                    HanaDsl.of(product).nativeCast(product.name(), HanaTypes.jsonNvarchar(200)).existsAt(JsonPath.root().key("code"));
        };
    }

    @SuppressWarnings("deprecation") // Preserve the still-public 3.2.17 DTO, Map and basic-result selection contracts.
    private static void verifyLegacyAliases(DefaultEasyEntityQuery query) {
        var aliased = query.queryable(ShardedProduct.class).where(p -> p.id().eq(1)).select(BigDecimal.class, p ->
                ProDsl.of(p).nativeCast(p.amount(), CommonTypes.decimal(19, 1)).as("computed"));
        NativeDecimalContract.equalValue(new BigDecimal("1.2"), aliased.singleNotNull());
        var dto = query.queryable(ShardedProduct.class).where(p -> p.id().eq(1)).select(TotalView.class, p ->
                ProDsl.of(p).nativeCast(p.amount(), CommonTypes.decimal(19, 1)).as("total")).singleNotNull();
        NativeDecimalContract.equalValue(new BigDecimal("1.2"), dto.getTotal());
        var map = query.queryable(ShardedProduct.class).where(p -> p.id().eq(1)).select(Map.class, p ->
                ProDsl.of(p).nativeCast(p.amount(), CommonTypes.decimal(19, 1)).as("computed")).singleNotNull();
        NativeDecimalContract.equalValue(new BigDecimal("1.2"), (BigDecimal) map.get("computed"));
    }

    public static class TotalView {
        @Column("gross_amount")
        private BigDecimal total;

        public BigDecimal getTotal() {
            return total;
        }

        public void setTotal(BigDecimal total) {
            this.total = total;
        }
    }
}
