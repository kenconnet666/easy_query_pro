package org.dpdns.zerodep.easyquery.pro.test;

import org.postgresql.ds.PGSimpleDataSource;

final class PostgresTestDatabase {
    private PostgresTestDatabase() {
    }

    static PGSimpleDataSource dataSource() {
        var source = new PGSimpleDataSource();
        source.setURL(TestEnvironment.required("EQP_PG_JDBC_URL"));
        source.setUser(TestEnvironment.required("EQP_PG_USER"));
        source.setPassword(TestEnvironment.required("EQP_PG_PASSWORD"));
        source.setConnectTimeout(10);
        source.setSocketTimeout(30);
        return source;
    }
}
