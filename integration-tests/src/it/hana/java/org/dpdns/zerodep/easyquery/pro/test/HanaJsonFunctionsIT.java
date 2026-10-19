package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.impl.NumberTypeExpressionImpl;
import com.easy.query.core.proxy.extension.functions.type.NumberTypeExpression;
import com.easy.query.core.proxy.extension.functions.type.impl.StringTypeExpressionImpl;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaOptions;
import org.dpdns.zerodep.easyquery.pro.hana.json.HanaJsonSupport;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HanaJsonFunctionsIT {
    private DefaultEasyEntityQuery query;

    @BeforeEach
    void createClient() throws SQLException {
        query = client(HanaOptions.DEFAULT);
    }

    @Test
    void existingTypedGettersPreserveJsonKindsAndNulls() {
        String json = """
                {"message":"中文", "number":42, "amount":123.45, "enabled":true,
                 "empty":null, "object":{"child":"nested"}, "items":["first", 7, null, {"child":"array"}]}
                """;
        assertEquals("中文", source(query, json).selectColumn(p -> p.name().asJSONObject().getString("message")).toList().get(0));
        assertEquals(42, source(query, json).selectColumn(p -> p.name().asJSONObject().getInteger("number")).toList().get(0));
        assertEquals(0, new BigDecimal("123.45").compareTo(source(query, json)
                .selectColumn(p -> p.name().asJSONObject().getBigDecimal("amount")).toList().get(0)));
        assertEquals(true, source(query, json).selectColumn(p -> p.name().asJSONObject().getBoolean("enabled")).toList().get(0));
        assertNull(source(query, json).selectColumn(p -> p.name().asJSONObject().getInteger("empty")).toList().get(0));
        assertNull(source(query, json).selectColumn(p -> p.name().asJSONObject().getString("absent")).toList().get(0));
        assertEquals("null", source(query, json).selectColumn(p -> p.name().asJSONObject().getJSONElement("empty")).toList().get(0));
        assertEquals("\"中文\"", source(query, json).selectColumn(p -> p.name().asJSONObject().getJSONElement("message")).toList().get(0));
        assertEquals("nested", source(query, json).selectColumn(p -> p.name().asJSONObject().getJSONObject("object").getString("child")).toList().get(0));
        assertEquals("first", source(query, json).selectColumn(p -> p.name().asJSONObject().getJSONArray("items").getString(0)).toList().get(0));
        assertEquals(7, source(query, json).selectColumn(p -> p.name().asJSONObject().getJSONArray("items").getInteger(1)).toList().get(0));
        assertEquals("array", source(query, json).selectColumn(p -> p.name().asJSONObject().getJSONArray("items").getJSONObject(3).getString("child")).toList().get(0));
    }

    @Test
    void membershipDistinguishesJsonNullFromMissingKeys() {
        assertEquals(1, source(query, "{\"empty\":null}").where(p -> p.name().asJSONObject().containsKey("empty")).toList().size());
        assertTrue(source(query, "{\"empty\":null}").where(p -> p.name().asJSONObject().containsKey("missing")).toList().isEmpty());
        assertTrue(source(query, null).where(p -> p.name().asJSONObject().containsKey("missing")).toList().isEmpty());
        assertThrows(RuntimeException.class, () -> source(query, "invalid json").where(p -> p.name().asJSONObject().containsKey("missing")).toList());
    }

    @Test
    void literalKeysAreEscapedWithoutBecomingSqlOrTemplateSyntax() {
        String json = """
                {"中":"unicode", "a.b":"dot", "q\\\"x":"quote", "a\\\\b":"slash", "O'Reilly":"apostrophe", "{0}":"braces"}
                """;
        var keys = List.of("中", "a.b", "q\"x", "a\\b", "O'Reilly", "{0}");
        var values = List.of("unicode", "dot", "quote", "slash", "apostrophe", "braces");
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            var selected = source(query, json).selectColumn(p -> new StringTypeExpressionImpl<String>(
                    p.getEntitySQLContext(), p.getTable(), null, fx -> fx.jsonObjectField(args -> {
                        PropTypeColumn.columnFuncSelector(args, p.name());
                        args.format(key);
                    }), String.class));
            assertEquals(values.get(i), selected.toList().get(0), key);
            assertEquals(selected.toSQL(), selected.cloneQueryable().toSQL());
        }
    }

    @Test
    void arrayLengthHasExplicitDeploymentAndNullEmptyInvalidInputBehavior() throws SQLException {
        assertThrows(IllegalStateException.class, () -> arrayLength(query, "[]"));
        String schema = HanaTestDatabase.uniqueName("JSON_SUPPORT") + "{0}";
        try (var connection = HanaTestDatabase.connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
            try {
                statement.execute(HanaJsonSupport.createArrayLengthFunctionSql(schema));
                var supported = client(new HanaOptions(5000, schema));
                assertEquals(3, arrayLength(supported, "[1,null,3]"));
                assertEquals(0, arrayLength(supported, "[]"));
                assertEquals(0, arrayLength(supported, " \t\r\n[ ]"));
                assertEquals(4, arrayLength(supported, "[[1,2],{\"key\":\"a,b\"},\"]\",null]"));
                assertNull(arrayLength(supported, null));
                assertThrows(RuntimeException.class, () -> arrayLength(supported, "invalid json"));
                assertThrows(RuntimeException.class, () -> arrayLength(supported, "[1,"));
                assertThrows(RuntimeException.class, () -> arrayLength(supported, "{}"));
                var rows = supported.queryable("SELECT 1 AS \"id\",'[1,2]' AS \"name\" FROM DUMMY"
                        + " UNION ALL SELECT 2,'[]' FROM DUMMY UNION ALL SELECT 3,'[1]' FROM DUMMY", TestProduct.class)
                        .where(p -> length(p.name()).gt(0))
                        .orderBy(p -> length(p.name()).desc())
                        .selectColumn(p -> length(p.name()));
                assertEquals(List.of(2, 1), rows.toList());
                assertEquals(rows.toSQL(), rows.cloneQueryable().toSQL());
            } finally {
                statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
        }
        assertThrows(IllegalArgumentException.class, () -> source(query, "[]")
                .selectColumn(p -> p.name().asJSONArray().getString(-1)).toSQL());
    }

    @Test
    void outputCapacityIsExplicitAndIsolatedPerClient() throws SQLException {
        String text = "中".repeat(6000);
        String json = "{\"message\":\"" + text + "\"}";
        var larger = client(new HanaOptions(10000));
        assertEquals(text, source(larger, json).selectColumn(p -> p.name().asJSONObject().getString("message")).toList().get(0));
        assertEquals(1, source(larger, json).where(p -> p.name().asJSONObject().containsKey("message")).toList().size());
        assertThrows(RuntimeException.class, () -> source(query, json).selectColumn(p -> p.name().asJSONObject().getString("message")).toList());
        assertEquals("ok", source(query, "{\"message\":\"ok\"}").selectColumn(p -> p.name().asJSONObject().getString("message")).toList().get(0));
    }

    private Integer arrayLength(DefaultEasyEntityQuery client, String json) {
        return source(client, json).selectColumn(p -> length(p.name())).toList().get(0);
    }

    private static NumberTypeExpression<Integer> length(PropTypeColumn<?> document) {
        return new NumberTypeExpressionImpl<>(document.getEntitySQLContext(), document.getTable(), null,
                fx -> fx.jsonArrayLength(args -> PropTypeColumn.columnFuncSelector(args, document)), Integer.class);
    }

    private static DefaultEasyEntityQuery client(HanaOptions options) throws SQLException {
        return new DefaultEasyEntityQuery(EasyQueryBootstrapper.defaultBuilderConfiguration()
                .setDefaultDataSource(HanaTestDatabase.dataSource())
                .useDatabaseConfigure(new HanaDatabaseConfiguration(options)).build());
    }

    private static EntityQueryable<TestProductProxy, TestProduct> source(DefaultEasyEntityQuery client, String json) {
        return client.queryable("SELECT 1 AS \"id\",TO_NCLOB(?) AS \"name\",CAST(1 AS DECIMAL(19,4)) AS \"amount\" FROM DUMMY",
                TestProduct.class, Arrays.asList(json));
    }
}
