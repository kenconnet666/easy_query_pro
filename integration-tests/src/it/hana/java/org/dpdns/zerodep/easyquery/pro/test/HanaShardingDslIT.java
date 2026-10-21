package org.dpdns.zerodep.easyquery.pro.test;

import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;
import org.dpdns.zerodep.easyquery.pro.hana.json.HanaJsonSupport;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.test.entity.ShardedProduct;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaTypes;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import com.zaxxer.hikari.HikariDataSource;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.proxy.core.draft.proxy.Draft6Proxy;
import com.easy.query.core.proxy.core.draft.Draft6;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.lob.HanaTextLobExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.lob.HanaBinaryLobExpression;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.json.HanaJsonTextExpression;

import static org.junit.jupiter.api.Assertions.*;

class HanaShardingDslIT {
    private static final String COLUMNS = "\"id\" INTEGER PRIMARY KEY, \"name\" NVARCHAR(200), \"amount\" DECIMAL(19,4), \"created\" TIMESTAMP";

    @Test
    void rowLocalExpressionsCanBeEvaluatedBeforeShardMerge() throws SQLException {
        try (HanaTestDatabase.TestTable first = HanaTestDatabase.createTable("SHARD_A", COLUMNS); HanaTestDatabase.TestTable second = HanaTestDatabase.createTable("SHARD_B", COLUMNS);
             HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("SHARD_JSON"); Statement ddl = schema.connection().createStatement()) {
            ddl.execute(HanaJsonSupport.createDocumentFunctionSql(schema.name()));
            ShardingDslContract.verify(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(new HanaOptions(5000, schema.name())),
                    HanaExtensions::configure, Arrays.asList(first.name(), second.name()), HanaShardingDslIT::verifyNativeViews);
        }
    }

    @Test
    void independentSourcesKeepIdenticalTableNamesInSeparateSchemas() throws SQLException {
        try (HanaTestDatabase.TestSchema first = HanaTestDatabase.createSchema("MULTISOURCE_A"); HanaTestDatabase.TestSchema second = HanaTestDatabase.createSchema("MULTISOURCE_B");
             HikariDataSource primary = ShardingDslContract.pool(HanaTestDatabase.dataSource(), first.name());
             HikariDataSource secondary = ShardingDslContract.pool(HanaTestDatabase.dataSource(), second.name())) {
            for (HanaTestDatabase.TestSchema schema : Arrays.asList(first, second)) {
                try (Statement ddl = schema.connection().createStatement()) {
                    ddl.execute("CREATE COLUMN TABLE " + schema.qualified("product") + " (" + COLUMNS + ")");
                }
            }
            try (Statement ddl = first.connection().createStatement()) {
                ddl.execute(HanaJsonSupport.createDocumentFunctionSql(first.name()));
            }
            ShardingDslContract.verify(Arrays.asList(new ShardingDslContract.Shard("primary", primary, "product"),
                            new ShardingDslContract.Shard("secondary", secondary, "product")),
                    new HanaDatabaseConfiguration(new HanaOptions(5000, first.name())), HanaExtensions::configure, HanaShardingDslIT::verifyNativeViews);
            assertEquals(0, primary.getHikariPoolMXBean().getActiveConnections());
            assertEquals(0, secondary.getHikariPoolMXBean().getActiveConnections());
        }
    }

    private static void verifyNativeViews(DefaultEasyEntityQuery query) {
        ShardingDslContract.verifyJsonPredicate(query, product -> HanaDsl.of(product).nativeCast(product.name(), HanaTypes.jsonNvarchar(200)).existsAt(JsonPath.root().key("code")));
        EntityQueryable<Draft6Proxy<Integer, String, String, String, byte[], String>, Draft6<Integer, String, String, String, byte[], String>> lobs = query.queryable(ShardedProduct.class).orderBy(p -> p.id().asc()).select(p -> {
            HanaTextLobExpression<String> text = HanaDsl.of(p).nativeCast(p.name(), HanaTypes.nclob());
            HanaBinaryLobExpression<byte[]> data = HanaDsl.of(p).value(new byte[]{0, 1, (byte) 255}, HanaTypes.blob());
            HanaJsonTextExpression<String> json = HanaDsl.of(p).nativeCast(p.name(), HanaTypes.jsonNclob());
            return Select.DRAFT.of(p.id(), text, text.asText(), text.sliceUtf16(1, 1), data.sliceBytes(2, 2), json.textAt(JsonPath.root().key("code")));
        });
        for (Draft6<Integer, String, String, String, byte[], String> row : lobs.toList()) {
            assertEquals(row.getValue2(), row.getValue3());
            assertEquals("{", row.getValue4());
            assertArrayEquals(new byte[]{1, (byte) 255}, row.getValue5());
            assertTrue(Arrays.asList("one", "two").contains(row.getValue6()));
        }
        assertEquals(2, lobs.cloneQueryable().toList().size());
        assertThrows(IllegalArgumentException.class, () -> lobs.cloneQueryable().distinct().toList());
    }
}
