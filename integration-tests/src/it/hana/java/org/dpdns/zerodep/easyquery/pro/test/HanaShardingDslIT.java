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

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.List;

class HanaShardingDslIT {
    @Test
    void rowLocalExpressionsCanBeEvaluatedBeforeShardMerge() throws SQLException {
        String columns = "\"id\" INTEGER PRIMARY KEY, \"name\" NVARCHAR(200), \"amount\" DECIMAL(19,4), \"created\" TIMESTAMP";
        try (var first = HanaTestDatabase.createTable("SHARD_A", columns); var second = HanaTestDatabase.createTable("SHARD_B", columns);
             var schema = HanaTestDatabase.createSchema("SHARD_JSON"); var ddl = schema.connection().createStatement()) {
            ddl.execute(HanaJsonSupport.createDocumentFunctionSql(schema.name()));
            ShardingDslContract.verify(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(new HanaOptions(5000, schema.name())),
                    HanaExtensions::configure, List.of(first.name(), second.name()), HanaShardingDslIT::verifyNativeViews);
        }
    }

    private static void verifyNativeViews(DefaultEasyEntityQuery query) {
        ShardingDslContract.verifyJsonPredicate(query, product -> HanaDsl.of(product).nativeCast(product.name(), HanaTypes.jsonNvarchar(200)).existsAt(JsonPath.root().key("code")));
        var lobs = query.queryable(ShardedProduct.class).orderBy(p -> p.id().asc()).select(p -> {
            var text = HanaDsl.of(p).nativeCast(p.name(), HanaTypes.nclob());
            var data = HanaDsl.of(p).value(new byte[]{0, 1, (byte) 255}, HanaTypes.blob());
            var json = HanaDsl.of(p).nativeCast(p.name(), HanaTypes.jsonNclob());
            return Select.DRAFT.of(p.id(), text, text.asText(), text.sliceUtf16(1, 1), data.sliceBytes(2, 2), json.textAt(JsonPath.root().key("code")));
        });
        for (var row : lobs.toList()) {
            assertEquals(row.getValue2(), row.getValue3());
            assertEquals("{", row.getValue4());
            assertArrayEquals(new byte[]{1, (byte) 255}, row.getValue5());
            assertTrue(List.of("one", "two").contains(row.getValue6()));
        }
        assertEquals(2, lobs.cloneQueryable().toList().size());
        assertThrows(IllegalArgumentException.class, () -> lobs.cloneQueryable().distinct().toList());
    }
}
