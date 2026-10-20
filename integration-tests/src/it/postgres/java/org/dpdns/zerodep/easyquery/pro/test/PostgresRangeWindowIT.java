package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostgresRangeWindowIT {
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rangeOffsetsBindWithBothPreparationModes(int threshold) {
        var source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(threshold);
        var query = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        PgSqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
        RangeWindowContract.verify(query);
    }
}
