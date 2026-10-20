package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.junit.jupiter.api.Test;

class MySqlQueryResourceIT {
    @Test
    void streamingFailuresAndConcurrentReadersReleaseTheirOwnResources() throws Exception {
        var source = MySqlTestDatabase.dataSource();
        source.setUseCursorFetch(true);
        source.setUseServerPrepStmts(true);
        source.setEmulateUnsupportedPstmts(false);
        try (var table = MySqlTestDatabase.createTable(source, "eqp_resources_", "id INTEGER PRIMARY KEY,name VARCHAR(100),amount DECIMAL(19,4)")) {
            QueryResourceContract.seed(table.connection(), "`" + table.name() + "`");
            QueryResourceContract.verify(source, new MySQLDatabaseConfiguration(), MySqlExtensions::configure, table.name(), null);
        }
    }
}
