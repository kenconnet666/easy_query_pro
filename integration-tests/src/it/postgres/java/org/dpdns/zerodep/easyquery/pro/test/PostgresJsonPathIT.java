package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PostgresJsonPathIT {
    @Test
    void jsonbScalarRootsDoNotAcquireArrayIndexSemanticsInPortablePaths() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(TestEnvironment.required("EQP_PG_JDBC_URL"));
        dataSource.setUser(TestEnvironment.required("EQP_PG_USER"));
        dataSource.setPassword(TestEnvironment.required("EQP_PG_PASSWORD"));
        dataSource.setConnectTimeout(10);
        dataSource.setSocketTimeout(30);
        var query = TestClients.configured(dataSource, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        String source = "SELECT CAST(? AS JSONB) AS name";
        for (String doc : List.of("1", "null", "\"text\"", "{}", "[]", "[null]")) {
            boolean expected = doc.equals("[null]");
            var exists = query.queryable(source, TestProduct.class, List.of(doc))
                    .selectColumn(p -> ProDsl.of(p).jsonExists(p.name(), JsonPath.root().index(0))).singleNotNull();
            assertEquals(expected, exists, doc);
        }
        assertEquals("zero", query.queryable(source, TestProduct.class, List.of("{\"0\":\"zero\"}"))
                .selectColumn(p -> ProDsl.of(p).jsonTextAt(p.name(), JsonPath.root().key("0"))).singleNotNull());
        assertFalse(query.queryable(source, TestProduct.class, List.of("[\"zero\"]"))
                .selectColumn(p -> ProDsl.of(p).jsonExists(p.name(), JsonPath.root().key("0"))).singleNotNull());
    }
}
