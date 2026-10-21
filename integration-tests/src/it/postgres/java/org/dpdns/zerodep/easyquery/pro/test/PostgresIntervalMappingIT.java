package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.interval.PgInterval;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;
import java.util.List;
import org.postgresql.ds.PGSimpleDataSource;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PostgresIntervalMappingIT {
    @ParameterizedTest
    @CsvSource({"postgres,0,false", "postgres,-1,false", "postgres_verbose,0,false", "postgres_verbose,-1,false",
            "sql_standard,0,false", "sql_standard,-1,false", "iso_8601,0,false", "iso_8601,-1,false",
            "postgres,-1,true", "postgres_verbose,-1,true", "sql_standard,-1,true", "iso_8601,-1,true"})
    void monthsDaysAndMicrosecondsRoundTripInEveryOutputStyle(String style, int threshold, boolean forceBinary) throws SQLException {
        PGSimpleDataSource source = PostgresTestDatabase.dataSource();
        source.setOptions("-c intervalstyle=" + style);
        source.setPrepareThreshold(threshold);
        if (forceBinary) source.setBinaryTransferEnable("interval");
        DefaultEasyEntityQuery query = TestClients.configured(source, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        PgSqlExtensions.configureTypeHandlers(query.getRuntimeContext().getJdbcTypeHandlerManager());
        List<PgInterval> values = Arrays.asList(PgInterval.of(0, 0, 0), PgInterval.of(-14, 3, -14_706_123_456L), PgInterval.of(0, -3, -14_706_123_456L),
                PgInterval.of(-14, 3, 14_706_123_456L), PgInterval.of(14, -3, -1),
                PgInterval.of(Integer.MAX_VALUE, 0, 0), PgInterval.of(Integer.MIN_VALUE, 0, 0),
                PgInterval.of(0, Integer.MAX_VALUE, 0), PgInterval.of(0, Integer.MIN_VALUE, 0),
                PgInterval.of(0, 0, Long.MAX_VALUE), PgInterval.of(0, 0, Long.MIN_VALUE),
                PgInterval.of(-1, 0, 0), PgInterval.positiveInfinity(), PgInterval.negativeInfinity());
        for (PgInterval value : values) {
            assertEquals(value, query.getEasyQueryClient().sqlQuery("SELECT CAST(? AS interval)", PgInterval.class, Arrays.asList(value)).get(0), style + " / " + value);
        }
        assertNull(query.getEasyQueryClient().sqlQuery("SELECT NULL::interval", PgInterval.class).get(0));
        assertEquals(style, query.getEasyQueryClient().sqlQuery("SHOW intervalstyle", String.class).get(0));
    }
}
