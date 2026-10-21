package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.junit.jupiter.api.Test;

import org.postgresql.ds.PGSimpleDataSource;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PostgresJsonPathIT {
    @Test
    void jsonbScalarRootsDoNotAcquireArrayIndexSemanticsInPortablePaths() {
        PGSimpleDataSource dataSource = PostgresTestDatabase.dataSource();
        DefaultEasyEntityQuery query = TestClients.configured(dataSource, new PgSQLDatabaseConfiguration(), PgSqlExtensions::configure);
        String source = "SELECT CAST(? AS JSONB) AS name";
        for (String doc : Arrays.asList("1", "null", "\"text\"", "{}", "[]", "[null]")) {
            boolean expected = doc.equals("[null]");
            Boolean exists = query.queryable(source, TestProduct.class, Arrays.asList(doc))
                    .selectColumn(p -> ProDsl.of(p).jsonExists(p.name(), JsonPath.root().index(0))).singleNotNull();
            assertEquals(expected, exists, doc);
        }
        assertEquals("zero", query.queryable(source, TestProduct.class, Arrays.asList("{\"0\":\"zero\"}"))
                .selectColumn(p -> ProDsl.of(p).jsonTextAt(p.name(), JsonPath.root().key("0"))).singleNotNull());
        assertFalse(query.queryable(source, TestProduct.class, Arrays.asList("[\"zero\"]"))
                .selectColumn(p -> ProDsl.of(p).jsonExists(p.name(), JsonPath.root().key("0"))).singleNotNull());
    }
}
