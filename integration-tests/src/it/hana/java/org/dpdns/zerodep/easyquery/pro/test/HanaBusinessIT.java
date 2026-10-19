package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.api.database.CodeFirstCommandTxArg;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.easy.query.core.enums.EasyBehaviorEnum;
import com.easy.query.core.basic.extension.interceptor.PredicateFilterInterceptor;
import com.easy.query.core.basic.extension.interceptor.EntityInterceptor;
import com.easy.query.core.expression.parser.core.base.WherePredicate;
import com.easy.query.core.expression.sql.builder.LambdaEntityExpressionBuilder;
import com.easy.query.core.expression.sql.builder.EntityInsertExpressionBuilder;
import com.easy.query.core.expression.sql.builder.EntityUpdateExpressionBuilder;
import org.jetbrains.annotations.NotNull;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.BusinessAccount;
import org.dpdns.zerodep.easyquery.pro.test.entity.BusinessOrder;
import org.dpdns.zerodep.easyquery.pro.test.entity.UnkeyedOrder;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HanaBusinessIT {
    @Test
    void navigationUpdatesUseOneAtomicMergeAndPreserveUpstreamInnerJoinSemantics() throws SQLException {
        try (var fixture = fixture()) {
            var query = fixture.query();
            var update = query.updatable(BusinessOrder.class).withVersion(1L).setColumns(order -> order.name().set(order.account().name()))
                    .where(order -> order.id().in(List.of(10, 11)));
            String sql = update.toSQL();
            assertTrue(sql.startsWith("MERGE INTO"), sql);
            assertEquals(sql, update.toSQL());
            assertTrue(sql.contains("INNER JOIN"), sql);
            assertEquals(1L, update.executeRows(), sql);
            assertEquals("alpha", query.queryable(BusinessOrder.class).where(order -> order.id().eq(10)).singleNotNull().getName());
            assertEquals("orphan", query.queryable(BusinessOrder.class).where(order -> order.id().eq(11)).singleNotNull().getName());
            assertEquals("third", query.queryable(BusinessOrder.class).where(order -> order.id().eq(12)).singleNotNull().getName());
        }
    }

    @Test
    void ambiguousNavigationUpdatesFailAtomicallyInsteadOfChoosingAnArbitraryMatch() throws SQLException {
        try (var fixture = fixture()) {
            var query = fixture.query();
            var update = query.updatable(BusinessOrder.class).withVersion(1L).setColumns(order -> order.name().set(order.tenantAccount().name()))
                    .where(order -> order.id().eq(10));
            var failure = assertThrows(RuntimeException.class, update::executeRows);
            boolean duplicateMatch = false;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 689) duplicateMatch = true;
            }
            assertTrue(duplicateMatch, failure.toString());
            assertEquals("first", query.queryable(BusinessOrder.class).where(order -> order.id().eq(10)).singleNotNull().getName());
        }
    }

    @Test
    void joinedDeletesKeepMatchedAndUnmatchedNavigationSemantics() throws SQLException {
        try (var fixture = fixture()) {
            var query = fixture.query();
            assertEquals(1L, query.deletable(BusinessOrder.class).allowDeleteStatement(true).withVersion(1L).where(order -> order.account().name().eq("alpha")).executeRows());
            var orphanDelete = query.deletable(BusinessOrder.class).allowDeleteStatement(true).withVersion(1L).where(order -> order.account().name().isNull());
            String deleteSql = orphanDelete.toSQL();
            assertTrue(deleteSql.contains("INNER JOIN"), deleteSql);
            assertEquals(0L, orphanDelete.executeRows(), deleteSql);
            assertEquals(List.of(11, 12, 13), query.queryable(BusinessOrder.class).orderBy(order -> order.id().asc()).selectColumn(order -> order.id()).toList());
        }
    }

    @Test
    void groupedNavigationPredicatesComposeWithLogicalDeleteAndBoundSetValues() throws SQLException {
        try (var fixture = fixture()) {
            var query = fixture.query();
            var update = query.updatable(BusinessAccount.class)
                    .withVersion(1L)
                    .configure(context -> context.getBehavior().addBehavior(EasyBehaviorEnum.ALL_SUB_QUERY_GROUP_JOIN))
                    .setColumns(account -> account.name().set("renamed '中文"))
                    .where(account -> {
                        account.orders().any(order -> order.name().eq("first"));
                        account.name().eq("alpha");
                    });
            assertTrue(update.toSQL().startsWith("MERGE INTO"), update.toSQL());
            assertEquals(1L, update.executeRows());
            assertEquals("renamed '中文", query.queryable(BusinessAccount.class).where(account -> account.id().eq(1)).singleNotNull().getName());
            assertEquals(1L, query.deletable(BusinessAccount.class)
                    .withVersion(2L)
                    .configure(context -> context.getBehavior().addBehavior(EasyBehaviorEnum.ALL_SUB_QUERY_GROUP_JOIN))
                    .where(account -> account.orders().any(order -> order.name().eq("first"))).executeRows());
            assertEquals(2L, query.queryable(BusinessAccount.class).count());
            assertEquals(Boolean.TRUE, query.getEasyQueryClient().sqlQuery("SELECT \"deleted\" FROM " + fixture.schema().qualified("eqp_account") + " WHERE \"id\"=1", Boolean.class).get(0));
            assertEquals(4L, query.queryable(BusinessOrder.class).count());
        }
    }

    @Test
    void groupedLeftJoinsCanSelectAndDeleteRootsWithNoMatchingChildren() throws SQLException {
        try (var fixture = fixture()) {
            var query = fixture.query();
            query.insertable(account(4, 10, "without orders")).executeRows();
            var delete = query.deletable(BusinessAccount.class).allowDeleteStatement(true).useLogicDelete(false).withVersion(1L)
                    .configure(context -> context.getBehavior().addBehavior(EasyBehaviorEnum.ALL_SUB_QUERY_GROUP_JOIN))
                    .where(account -> account.orders().count().eq(0L));
            String sql = delete.toSQL();
            assertTrue(sql.contains("LEFT JOIN"), sql);
            assertEquals(1L, delete.executeRows(), sql);
            assertEquals(3L, query.queryable(BusinessAccount.class).count());
            assertEquals(4L, query.queryable(BusinessOrder.class).count());
        }
    }

    @Test
    void compositeKeysKeepJoinedUpdatesInsideTheSelectedTenant() throws SQLException {
        try (var fixture = fixture()) {
            var query = fixture.query();
            query.insertable(order(10, 20, 3, "same id other tenant")).executeRows();
            assertEquals(1L, query.updatable(BusinessOrder.class).withVersion(1L)
                    .setColumns(order -> order.name().set(order.account().name()))
                    .where(order -> {
                        order.id().eq(10);
                        order.tenantId().eq(10);
                    }).executeRows());
            assertEquals("same id other tenant", query.queryable(BusinessOrder.class).where(order -> {
                order.id().eq(10);
                order.tenantId().eq(20);
            }).singleNotNull().getName());
        }
    }

    @Test
    void joinedDeleteDoesNotRequireKeysAndGeneratedAliasesDoNotShadowUserAliases() throws SQLException {
        try (var fixture = fixture()) {
            var query = fixture.query();
            query.getEasyQueryClient().getDatabaseCodeFirst().createTableCommand(List.of(UnkeyedOrder.class)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            var row = new UnkeyedOrder();
            row.setAccountId(1);
            row.setName("without primary key");
            query.insertable(row).executeRows();
            var update = query.updatable(UnkeyedOrder.class).withVersion(1L).setColumns(order -> order.name().set(order.account().name()))
                    .where(order -> order.name().eq(row.getName()));
            var unsupported = assertThrows(UnsupportedOperationException.class, update::toSQL);
            assertTrue(unsupported.getMessage().contains("primary keys"));
            var delete = query.deletable(UnkeyedOrder.class).allowDeleteStatement(true).withVersion(1L).where(order -> {
                order.getTable().asAlias("eqp_delete");
                order.account().name().eq("alpha");
            });
            assertTrue(delete.toSQL().contains("SYS.DUMMY eqp_delete1"), delete.toSQL());
            assertEquals(1L, delete.executeRows());
            assertEquals(0L, query.queryable(UnkeyedOrder.class).count());
            var renamed = query.updatable(BusinessOrder.class).withVersion(1L).setColumns(order -> {
                order.getTable().asAlias("eqp_update");
                order.name().set(order.account().name());
            }).where(order -> order.id().eq(10));
            assertTrue(renamed.toSQL().contains(") eqp_update1 ON"), renamed.toSQL());
            assertEquals(1L, renamed.executeRows());
        }
    }

    @Test
    void optimisticVersionsRejectStaleEntitiesAndRollbackRestoresTheStoredState() throws SQLException {
        try (var fixture = fixture()) {
            var query = fixture.query();
            var first = query.queryable(BusinessAccount.class).where(account -> account.id().eq(1)).singleNotNull();
            var stale = query.queryable(BusinessAccount.class).where(account -> account.id().eq(1)).singleNotNull();
            first.setName("winner");
            assertEquals(1L, query.updatable(first).executeRows());
            stale.setName("stale");
            assertEquals(0L, query.updatable(stale).executeRows());
            var saved = query.queryable(BusinessAccount.class).where(account -> account.id().eq(1)).singleNotNull();
            assertEquals("winner", saved.getName());
            assertEquals(2L, saved.getVersion());
            try (var transaction = query.getEasyQueryClient().beginTransaction()) {
                saved.setName("rolled back");
                assertEquals(1L, query.updatable(saved).executeRows());
                transaction.rollback();
            }
            var restored = query.queryable(BusinessAccount.class).where(account -> account.id().eq(1)).singleNotNull();
            assertEquals("winner", restored.getName());
            assertEquals(2L, restored.getVersion());
        }
    }

    @Test
    void tenantAndEntityInterceptorsApplyToNavigationIncludesAndWritesPerClient() throws SQLException {
        try (var fixture = fixture()) {
            fixture.query().insertable(order(14, 10, 3, "cross tenant reference")).executeRows();
            var tenant10 = client(fixture.schema().name());
            var tenant20 = client(fixture.schema().name());
            tenant10.getEasyQueryClient().getRuntimeContext().getQueryConfiguration().applyInterceptor(new TenantInterceptor(10));
            tenant20.getEasyQueryClient().getRuntimeContext().getQueryConfiguration().applyInterceptor(new TenantInterceptor(20));
            assertEquals(2L, tenant10.queryable(BusinessAccount.class).count());
            assertEquals(1L, tenant20.queryable(BusinessAccount.class).count());
            var orders = tenant10.queryable(BusinessOrder.class).include(order -> order.account()).orderBy(order -> order.id().asc()).toList();
            assertEquals(List.of(10, 11, 13, 14), orders.stream().map(BusinessOrder::getId).toList());
            assertEquals("alpha", orders.get(0).getAccount().getName());
            assertNull(orders.get(1).getAccount());
            assertNull(orders.get(3).getAccount());
            assertEquals(0L, tenant10.queryable(BusinessOrder.class).where(order -> order.account().name().eq("gamma")).count());
            var accounts = tenant10.queryable(BusinessAccount.class).include(account -> account.orders()).orderBy(account -> account.id().asc()).toList();
            assertEquals(List.of(10), accounts.get(0).getOrders().stream().map(BusinessOrder::getId).toList());
            assertEquals(List.of(13), accounts.get(1).getOrders().stream().map(BusinessOrder::getId).toList());
            var added = order(20, 10, 1, "inserted");
            added.setTenantId(null);
            assertEquals(1L, tenant10.insertable(added).executeRows());
            assertEquals(10, added.getTenantId());
            added.setName("updated");
            assertEquals(1L, tenant10.updatable(added).executeRows());
            assertEquals("checked:updated", tenant10.queryable(BusinessOrder.class).where(order -> order.id().eq(20)).singleNotNull().getName());
            assertEquals(0L, tenant10.deletable(BusinessOrder.class).allowDeleteStatement(true).where(order -> order.id().eq(12)).executeRows());
            assertEquals(1L, tenant20.queryable(BusinessOrder.class).where(order -> order.id().eq(12)).count());
        }
    }

    private static Fixture fixture() throws SQLException {
        var schema = HanaTestDatabase.createSchema("BUSINESS");
        try {
            var query = client(schema.name());
            query.getEasyQueryClient().getDatabaseCodeFirst().createTableCommand(List.of(BusinessAccount.class, BusinessOrder.class)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            query.insertable(List.of(account(1, 10, "alpha"), account(2, 10, "beta"), account(3, 20, "gamma"))).executeRows();
            query.insertable(List.of(order(10, 10, 1, "first"), order(11, 10, 99, "orphan"), order(12, 20, 3, "third"), order(13, 10, 2, "second"))).executeRows();
            return new Fixture(schema, query);
        } catch (SQLException | RuntimeException failure) {
            try {
                schema.close();
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static DefaultEasyEntityQuery client(String schema) throws SQLException {
        return new DefaultEasyEntityQuery(EasyQueryBootstrapper.defaultBuilderConfiguration().setDefaultDataSource(HanaTestDatabase.dataSource())
                .useDatabaseConfigure(new HanaDatabaseConfiguration()).optionConfigure(options -> options.setDefaultSchema(schema)).build());
    }

    private record TenantInterceptor(int tenant) implements PredicateFilterInterceptor, EntityInterceptor {
        @Override
        public String name() {
            return "test-tenant";
        }

        @Override
        public boolean apply(@NotNull Class<?> type) {
            return type == BusinessAccount.class || type == BusinessOrder.class;
        }

        @Override
        public void configure(Class<?> type, LambdaEntityExpressionBuilder builder, WherePredicate<Object> predicate) {
            predicate.eq("tenantId", tenant);
        }

        @Override
        public void configureInsert(Class<?> type, EntityInsertExpressionBuilder builder, Object entity) {
            if (entity instanceof BusinessOrder order) order.setTenantId(tenant);
            if (entity instanceof BusinessAccount account) account.setTenantId(tenant);
        }

        @Override
        public void configureUpdate(Class<?> type, EntityUpdateExpressionBuilder builder, Object entity) {
            if (entity instanceof BusinessOrder order) order.setName("checked:" + order.getName());
        }
    }

    private static BusinessAccount account(int id, int tenant, String name) {
        var account = new BusinessAccount();
        account.setId(id);
        account.setTenantId(tenant);
        account.setName(name);
        account.setVersion(1L);
        account.setDeleted(false);
        return account;
    }

    private static BusinessOrder order(int id, int tenant, int account, String name) {
        var order = new BusinessOrder();
        order.setId(id);
        order.setTenantId(tenant);
        order.setAccountId(account);
        order.setName(name);
        return order;
    }

    private record Fixture(HanaTestDatabase.TestSchema schema, DefaultEasyEntityQuery query) implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            schema.close();
        }
    }
}
