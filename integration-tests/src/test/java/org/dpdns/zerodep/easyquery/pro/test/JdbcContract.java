package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.basic.jdbc.tx.Transaction;
import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcContract {
    private JdbcContract() {
    }

    static void verify(DataSource dataSource, DatabaseConfiguration configuration) throws SQLException {
        verify(dataSource, configuration, "VARCHAR(255)");
    }

    static void verify(DataSource dataSource, DatabaseConfiguration configuration, String textType) throws SQLException {
        assertEquals(17, Runtime.version().feature(), "Tests must run on the Java 17 toolchain");
        var client = EasyQueryBootstrapper.defaultBuilderConfiguration()
                .setDefaultDataSource(dataSource)
                .useDatabaseConfigure(configuration)
                .build();
        var dialect = client.getRuntimeContext().getQueryConfiguration().getDialect();
        String table = dialect.getQuoteName("eqp_product");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " ("
                    + dialect.getQuoteName("id") + " INTEGER PRIMARY KEY, "
                    + dialect.getQuoteName("name") + " " + textType + " NOT NULL, "
                    + dialect.getQuoteName("amount") + " DECIMAL(19, 4) NOT NULL)");
            try {
                var query = new DefaultEasyEntityQuery(client);
                var product = new TestProduct(1, "O'Reilly 中文", new BigDecimal("123.4500"));
                assertEquals(1L, query.insertable(product).executeRows());

                var selection = query.queryable(TestProduct.class)
                        .where(p -> p.name().eq(product.getName()))
                        .orderBy(p -> p.id().asc())
                        .limit(0, 1);
                assertFalse(selection.toSQL().contains(product.getName()), "Values must remain bound parameters");
                var rows = selection.toList();
                assertEquals(1, rows.size());
                assertEquals(product.getName(), rows.get(0).getName());
                assertEquals(product.getAmount(), rows.get(0).getAmount());

                product.setAmount(new BigDecimal("987.6500"));
                assertEquals(1L, query.updatable(product).executeRows());
                assertEquals(product.getAmount(), query.queryable(TestProduct.class).toList().get(0).getAmount());

                try (Transaction transaction = client.beginTransaction()) {
                    query.insertable(new TestProduct(2, "rolled back", BigDecimal.ONE)).executeRows();
                    transaction.rollback();
                }
                assertEquals(1L, query.queryable(TestProduct.class).count());
                try (Transaction transaction = client.beginTransaction()) {
                    query.insertable(new TestProduct(3, "committed", BigDecimal.TEN)).executeRows();
                    transaction.commit();
                }
                assertEquals(2L, query.queryable(TestProduct.class).count());
                assertEquals(1L, query.deletable(product).allowDeleteStatement(true).executeRows());
                assertTrue(query.queryable(TestProduct.class).where(p -> p.id().eq(1)).toList().isEmpty());
            } finally {
                statement.execute("DROP TABLE " + table);
            }
        }
    }
}
