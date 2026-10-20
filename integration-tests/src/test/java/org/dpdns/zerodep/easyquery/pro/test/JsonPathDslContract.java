package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.bootstrapper.DatabaseConfiguration;
import com.easy.query.core.inject.ServiceCollection;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.SqlDialect;
import org.dpdns.zerodep.easyquery.pro.dsl.json.JsonPath;
import org.dpdns.zerodep.easyquery.pro.test.entity.TestProduct;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

final class JsonPathDslContract {
    private JsonPathDslContract() {
    }

    private record Case(JsonPath path, String text, boolean exists, Boolean bool) {
    }

    static void verify(DataSource dataSource, DatabaseConfiguration database, Consumer<ServiceCollection> extensions) {
        var query = TestClients.configured(dataSource, database, extensions);
        var runtime = query.getRuntimeContext();
        var quote = runtime.getQueryConfiguration().getDialect();
        var dialect = runtime.getService(SqlDialect.class);
        String documentInput = switch (dialect) {
            case HANA -> "TO_NCLOB(?)";
            case MYSQL -> "CAST(CONVERT(? USING utf8mb4) AS JSON)";
            case POSTGRESQL -> "CAST(? AS JSON)";
        };
        String source = "SELECT 1 AS " + quote.getQuoteName("id") + "," + documentInput
                + " AS " + quote.getQuoteName("name") + (dialect == SqlDialect.HANA ? " FROM DUMMY" : "");
        String doc = """
                {"object":{"message":"中文", "enabled":true}, "items":[{"name":"first"},null,3,[],{}],
                 "nothing":null, "boolean":false, "looks":"true", "text":"null", "0":"rootZero",
                 "a.b":{"q\\\"x":"quote", "O'Reilly":"apostrophe", "{0}":"braces", "a\\\\b":"slash", "\\n":"line", "":"empty"}}
                """;
        var root = JsonPath.root();
        var cases = List.of(
                new Case(root.key("object").key("message"), "中文", true, null),
                new Case(root.key("object").key("enabled"), "true", true, true),
                new Case(root.key("boolean"), "false", true, false),
                new Case(root.key("looks"), "true", true, null),
                new Case(root.key("text"), "null", true, null),
                new Case(root.key("nothing"), null, true, null),
                new Case(root.key("items").index(0).key("name"), "first", true, null),
                new Case(root.key("items").index(1), null, true, null),
                new Case(root.key("items").index(2), "3", true, null),
                new Case(root.key("items").index(9), null, false, null),
                new Case(root.key("items").key("0"), null, false, null),
                new Case(root.key("object").index(0), null, false, null),
                new Case(root.key("nothing").key("child"), null, false, null),
                new Case(root.key("text").index(0), null, false, null),
                new Case(root.index(0), null, false, null),
                new Case(root.key("0"), "rootZero", true, null));
        for (var expected : cases) {
            var row = query.queryable(source, TestProduct.class, List.of(doc)).select(p -> {
                var sql = ProDsl.of(p);
                return Select.DRAFT.of(sql.jsonTextAt(p.name(), expected.path()), sql.jsonExists(p.name(), expected.path()), sql.jsonBooleanAt(p.name(), expected.path()));
            }).singleNotNull();
            assertEquals(expected.text(), row.getValue1(), expected.path().toString());
            assertEquals(expected.exists(), row.getValue2(), expected.path().toString());
            assertEquals(expected.bool(), row.getValue3(), expected.path().toString());
        }
        var keys = List.of("q\"x", "O'Reilly", "{0}", "a\\b", "\n", "");
        var values = List.of("quote", "apostrophe", "braces", "slash", "line", "empty");
        for (int i = 0; i < keys.size(); i++) {
            var path = root.key("a.b").key(keys.get(i));
            var selected = query.queryable(source, TestProduct.class, List.of(doc)).selectColumn(p -> ProDsl.of(p).jsonTextAt(p.name(), path));
            assertEquals(values.get(i), selected.singleNotNull(), keys.get(i));
            assertEquals(selected.toSQL(), selected.cloneQueryable().toSQL());
        }
        assertEquals("null", query.queryable(source, TestProduct.class, List.of(doc)).selectColumn(p -> ProDsl.of(p).jsonAt(p.name(), root.key("nothing"))).singleNotNull());
        assertEquals("[]", query.queryable(source, TestProduct.class, List.of(doc)).selectColumn(p -> ProDsl.of(p).jsonAt(p.name(), root.key("items").index(3)).asText()).singleNotNull());
        assertEquals("{}", query.queryable(source, TestProduct.class, List.of(doc)).selectColumn(p -> ProDsl.of(p).jsonAt(p.name(), root.key("items").index(4)).asText()).singleNotNull());
        var filtered = query.queryable(source, TestProduct.class, List.of(doc)).where(p -> ProDsl.of(p).jsonExists(p.name(), root.key("nothing")).eq(true));
        assertEquals(1, filtered.toList().size());
        assertEquals(1, filtered.cloneQueryable().toList().size());
        assertEquals("中文", query.queryable("SELECT 1 AS " + quote.getQuoteName("id") + (dialect == SqlDialect.HANA ? " FROM DUMMY" : ""), TestProduct.class)
                .selectColumn(p -> ProDsl.of(p).jsonTextAt(p.expression().constant(doc), root.key("object").key("message"))).singleNotNull());
        for (String scalar : Arrays.asList(null, "null", "\"text\"", "true", "false", "123", "[]", "{}")) {
            var row = query.queryable(source, TestProduct.class, Arrays.asList(scalar)).select(p -> {
                var sql = ProDsl.of(p);
                return Select.DRAFT.of(sql.jsonExists(p.name(), root), sql.jsonExists(p.name(), root.index(0)));
            }).singleNotNull();
            assertEquals(scalar != null, row.getValue1());
            assertFalse(row.getValue2(), String.valueOf(scalar));
        }
        for (String invalid : List.of("", "invalid json", "1,2")) {
            assertThrows(RuntimeException.class, () -> query.queryable(source, TestProduct.class, List.of(invalid))
                    .selectColumn(p -> ProDsl.of(p).jsonExists(p.name(), root)).toList());
        }
    }
}
