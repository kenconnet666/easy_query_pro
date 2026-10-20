package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.jdbc.executor.EntityExpressionExecutor;
import com.easy.query.core.basic.jdbc.executor.ShardingEntityExpressionExecutor;
import com.easy.query.core.basic.thread.ShardingExecutorService;
import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.configuration.EasyQueryShardingOption;
import com.easy.query.core.configuration.ShardingDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ShardedProductProxy;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

final class ShardingDslContract {
    private ShardingDslContract() {
    }

    static void verify(DataSource source, DatabaseConfiguration database, Consumer<ServiceCollection> extensions, List<String> tables,
                       Consumer<DefaultEasyEntityQuery> nativeChecks) {
        verify(tables.stream().map(table -> new Shard("primary", source, table)).toList(), database, extensions, nativeChecks);
    }

    record Shard(String sourceName, DataSource source, String table) {
    }

    static HikariDataSource pool(DataSource source, String schema) {
        var config = new HikariConfig();
        config.setDataSource(source);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10000);
        if (schema != null) config.setSchema(schema);
        return new HikariDataSource(config);
    }

    static void verify(List<Shard> shards, DatabaseConfiguration database, Consumer<ServiceCollection> extensions,
                       Consumer<DefaultEasyEntityQuery> nativeChecks) {
        var sources = new LinkedHashMap<String, CountedSource>();
        var actualTables = new LinkedHashMap<String, Collection<String>>();
        for (var shard : shards) {
            sources.computeIfAbsent(shard.sourceName(), name -> CountedSource.wrap(shard.source()));
            actualTables.computeIfAbsent(shard.sourceName(), name -> new ArrayList<>()).add(shard.table());
        }
        var primary = shards.get(0);
        var secondary = new LinkedHashSet<ShardingDataSource>();
        sources.forEach((name, source) -> {
            if (!name.equals(primary.sourceName())) secondary.add(new ShardingDataSource(name, source.dataSource(), 2));
        });
        var client = EasyQueryBootstrapper.defaultBuilderConfiguration().setDefaultDataSource(sources.get(primary.sourceName()).dataSource())
                .optionConfigure(options -> {
                    options.setDefaultDataSourceName(primary.sourceName());
                    options.setDefaultDataSourceMergePoolSize(2);
                    options.setMaxShardingQueryLimit(2);
                    options.setExecutorCorePoolSize(1);
                    options.setExecutorMaximumPoolSize(2);
                    options.setShardingOption(new EasyQueryShardingOption(secondary));
                }).useDatabaseConfigure(database).customConfigure(extensions)
                .replaceService(EntityExpressionExecutor.class, ShardingEntityExpressionExecutor.class).build();
        client.getRuntimeContext().getQueryConfiguration().applyShardingInitializer(new ShardedProduct.Tables(actualTables));
        client.getRuntimeContext().getTableRouteManager().addRoute(new ShardedProduct.Route(shards.stream().map(Shard::table).toList()));
        client.getRuntimeContext().getDataSourceRouteManager().addRoute(new ShardedProduct.SourceRoute(List.copyOf(sources.keySet())));
        var query = new DefaultEasyEntityQuery(client);
        try {
            query.insertable(List.of(new ShardedProduct(1, "{\"code\":\"one\"}", new BigDecimal("1.2345")),
                    new ShardedProduct(2, "{\"code\":\"two\"}", new BigDecimal("2.3456")))).executeRows();
            verifySourceRouting(query, sources);
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
            nativeChecks.accept(query);
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
            verifyRoutedRollback(query);
        } finally {
            client.getRuntimeContext().getService(ShardingExecutorService.class).getExecutorService().shutdown();
        }
    }

    private static void verifySourceRouting(DefaultEasyEntityQuery query, LinkedHashMap<String, CountedSource> sources) {
        var ordered = List.copyOf(sources.values());
        for (int id : new int[]{1, 2}) {
            sources.values().forEach(source -> source.borrows().set(0));
            assertEquals(id, query.queryable(ShardedProduct.class).where(p -> p.id().eq(id)).singleNotNull().getId());
            for (int i = 0; i < ordered.size(); i++)
                assertEquals(i == id % ordered.size(), ordered.get(i).borrows().get() > 0);
        }
    }

    private static void verifyRoutedRollback(DefaultEasyEntityQuery query) {
        try (var transaction = query.getEasyQueryClient().beginTransaction()) {
            assertEquals(1, query.updatable(ShardedProduct.class).setColumns(p -> p.amount().set(
                    ProDsl.of(p).value(new BigDecimal("9.99"), CommonTypes.decimal(19, 2)))).where(p -> p.id().eq(1)).executeRows());
            NativeDecimalContract.equalValue(new BigDecimal("9.99"), query.queryable(ShardedProduct.class).where(p -> p.id().eq(1)).singleNotNull().getAmount());
            transaction.rollback();
        }
        NativeDecimalContract.equalValue(new BigDecimal("1.2345"), query.queryable(ShardedProduct.class).where(p -> p.id().eq(1)).singleNotNull().getAmount());
        try (var transaction = query.getEasyQueryClient().beginTransaction()) {
            assertThrows(RuntimeException.class, () -> query.insertable(new ShardedProduct(1, "duplicate", BigDecimal.ONE)).executeRows());
            transaction.rollback();
        }
        assertEquals(2, query.queryable(ShardedProduct.class).count());
        NativeDecimalContract.equalValue(new BigDecimal("2.3456"), query.queryable(ShardedProduct.class).where(p -> p.id().eq(2)).singleNotNull().getAmount());
    }

    private record CountedSource(DataSource dataSource, AtomicInteger borrows) {
        private static CountedSource wrap(DataSource delegate) {
            var count = new AtomicInteger();
            var source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) count.incrementAndGet();
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
            return new CountedSource(source, count);
        }
    }

    static boolean hasShardGuard(Throwable failure) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (current instanceof UnsupportedOperationException && current.getMessage().contains("before shard merging"))
                return true;
        }
        return false;
    }

    static void verifyJsonPredicate(DefaultEasyEntityQuery query, Function<ShardedProductProxy, BooleanTypeExpression<Boolean>> predicate) {
        var selected = query.queryable(ShardedProduct.class).orderBy(p -> p.id().asc())
                .select(p -> Select.DRAFT.of(p.id(), predicate.apply(p)));
        for (var row : selected.toList()) assertTrue(row.getValue2());
        for (var row : selected.cloneQueryable().toList()) assertTrue(row.getValue2());
        assertEquals(2, query.queryable(ShardedProduct.class).where(p -> predicate.apply(p).eq(true)).count());
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
