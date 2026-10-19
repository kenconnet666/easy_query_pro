package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.SequenceProduct;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HanaSequenceIT {
    @Test
    void sequenceKeysUseTheExistingGeneratorRegistryAndRespectTransactions() throws SQLException {
        String table = HanaTestDatabase.uniqueName("SEQUENCE_TABLE");
        String sequence = HanaTestDatabase.uniqueName("SEQUENCE");
        var dataSource = HanaTestDatabase.dataSource();
        var client = EasyQueryBootstrapper.defaultBuilderConfiguration()
                .setDefaultDataSource(dataSource)
                .useDatabaseConfigure(new HanaDatabaseConfiguration()).build();
        client.getRuntimeContext().getQueryConfiguration()
                .applyGeneratedKeySQLColumnGenerator(new SequenceProduct.ProductSequence(sequence));
        var query = new DefaultEasyEntityQuery(client);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SEQUENCE \"" + sequence + "\" START WITH 100 INCREMENT BY 7");
            try {
                statement.execute("CREATE COLUMN TABLE \"" + table + "\" (\"id\" BIGINT PRIMARY KEY, \"name\" NVARCHAR(255) NOT NULL)");
                try {
                    var first = new SequenceProduct("first");
                    var second = new SequenceProduct("second");
                    assertEquals(2L, query.insertable(List.of(first, second)).asTable(t -> table).batch(true).executeRows(true));
                    assertEquals(100L, first.getId());
                    assertEquals(107L, second.getId());
                    assertEquals(List.of(100L, 107L), query.queryable(SequenceProduct.class).asTable(t -> table)
                            .orderBy(p -> p.id().asc()).selectColumn(p -> p.id()).toList());

                    try (var transaction = client.beginTransaction()) {
                        var rolledBack = new SequenceProduct("rolled-back");
                        query.insertable(rolledBack).asTable(t -> table).executeRows(true);
                        assertNotNull(rolledBack.getId());
                        transaction.rollback();
                    }
                    assertEquals(2L, query.queryable(SequenceProduct.class).asTable(t -> table).count());

                    try (var transaction = client.beginTransaction()) {
                        var valid = new SequenceProduct("before-failure");
                        var invalid = new SequenceProduct();
                        assertThrows(RuntimeException.class, () -> query.insertable(List.of(valid, invalid))
                                .asTable(t -> table).batch(true).executeRows(true));
                        transaction.rollback();
                    }
                    assertEquals(2L, query.queryable(SequenceProduct.class).asTable(t -> table).count());
                    var afterFailure = new SequenceProduct("after-failure");
                    assertEquals(1L, query.insertable(afterFailure).asTable(t -> table).executeRows(true));
                    assertNotNull(afterFailure.getId());
                    assertEquals(3L, query.queryable(SequenceProduct.class).asTable(t -> table).count());
                } finally {
                    statement.execute("DROP TABLE \"" + table + "\"");
                }
            } finally {
                statement.execute("DROP SEQUENCE \"" + sequence + "\"");
            }
        }
    }
}
