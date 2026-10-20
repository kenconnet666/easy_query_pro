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
import com.easy.query.core.proxy.extension.functions.type.BooleanTypeExpression;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.ShardedProductProxy;
import org.dpdns.zerodep.easyquery.pro.dsl.type.CommonTypes;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

final class ShardingDslContract {
    private ShardingDslContract() {
    }

    static void verify(DataSource source, DatabaseConfiguration database, Consumer<ServiceCollection> extensions, List<String> tables,
                       Consumer<DefaultEasyEntityQuery> nativeChecks) {
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
        } finally {
            client.getRuntimeContext().getService(ShardingExecutorService.class).getExecutorService().shutdown();
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
