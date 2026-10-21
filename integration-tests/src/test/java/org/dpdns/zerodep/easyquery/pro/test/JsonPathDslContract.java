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
import java.util.Objects;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.configuration.dialect.SQLKeyword;
import com.easy.query.core.proxy.core.draft.Draft3;
import com.easy.query.core.basic.api.select.Query;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.TestProductProxy;
import com.easy.query.core.proxy.core.draft.Draft2;

import static org.junit.jupiter.api.Assertions.*;

final class JsonPathDslContract {
    private JsonPathDslContract() {
    }

    private static final class Case {
        private final JsonPath path;
        private final String text;
        private final boolean exists;
        private final Boolean bool;

        private Case(JsonPath path, String text, boolean exists, Boolean bool) {
            this.path = path;
            this.text = text;
            this.exists = exists;
            this.bool = bool;
        }

        public JsonPath path() {
            return path;
        }

        public String text() {
            return text;
        }

        public boolean exists() {
            return exists;
        }

        public Boolean bool() {
            return bool;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Case)) return false;
            Case other = (Case) o;
            return Objects.equals(path, other.path)
                    && Objects.equals(text, other.text)
                    && exists == other.exists
                    && Objects.equals(bool, other.bool);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, text, exists, bool);
        }

        @Override
        public String toString() {
            return "Case[path=" + path + ", text=" + text + ", exists=" + exists + ", bool=" + bool + "]";
        }
    }

    static void verify(DataSource dataSource, DatabaseConfiguration database, Consumer<ServiceCollection> extensions) {
        DefaultEasyEntityQuery query = TestClients.configured(dataSource, database, extensions);
        QueryRuntimeContext runtime = query.getRuntimeContext();
        SQLKeyword quote = runtime.getQueryConfiguration().getDialect();
        SqlDialect dialect = runtime.getService(SqlDialect.class);
        String documentInput;
        switch (dialect) {
            case HANA: documentInput = "TO_NCLOB(?)"; break;
            case MYSQL: documentInput = "CAST(CONVERT(? USING utf8mb4) AS JSON)"; break;
            case POSTGRESQL: documentInput = "CAST(? AS JSON)"; break;
            default: throw new IllegalStateException("Unhandled value: " + dialect);
        }
        String source = "SELECT 1 AS " + quote.getQuoteName("id") + "," + documentInput
                + " AS " + quote.getQuoteName("name") + (dialect == SqlDialect.HANA ? " FROM DUMMY" : "");
        String doc = "{\"object\":{\"message\":\"中文\", \"enabled\":true}, \"items\":[{\"name\":\"first\"},null,3,[],{}],\n" +
                " \"nothing\":null, \"boolean\":false, \"looks\":\"true\", \"text\":\"null\", \"0\":\"rootZero\",\n" +
                " \"a.b\":{\"q\\\"x\":\"quote\", \"O'Reilly\":\"apostrophe\", \"{0}\":\"braces\", \"a\\\\b\":\"slash\", \"\\n\":\"line\", \"\":\"empty\"}}\n";
        JsonPath root = JsonPath.root();
        List<JsonPathDslContract.Case> cases = Arrays.asList(new Case(root.key("object").key("message"), "中文", true, null),
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
        for (JsonPathDslContract.Case expected : cases) {
            Draft3<String, Boolean, Boolean> row = query.queryable(source, TestProduct.class, Arrays.asList(doc)).select(p -> {
                ProDsl sql = ProDsl.of(p);
                return Select.DRAFT.of(sql.jsonTextAt(p.name(), expected.path()), sql.jsonExists(p.name(), expected.path()), sql.jsonBooleanAt(p.name(), expected.path()));
            }).singleNotNull();
            assertEquals(expected.text(), row.getValue1(), expected.path().toString());
            assertEquals(expected.exists(), row.getValue2(), expected.path().toString());
            assertEquals(expected.bool(), row.getValue3(), expected.path().toString());
        }
        List<String> keys = Arrays.asList("q\"x", "O'Reilly", "{0}", "a\\b", "\n", "");
        List<String> values = Arrays.asList("quote", "apostrophe", "braces", "slash", "line", "empty");
        for (int i = 0; i < keys.size(); i++) {
            JsonPath path = root.key("a.b").key(keys.get(i));
            Query<String> selected = query.queryable(source, TestProduct.class, Arrays.asList(doc)).selectColumn(p -> ProDsl.of(p).jsonTextAt(p.name(), path));
            assertEquals(values.get(i), selected.singleNotNull(), keys.get(i));
            assertEquals(selected.toSQL(), selected.cloneQueryable().toSQL());
        }
        assertEquals("null", query.queryable(source, TestProduct.class, Arrays.asList(doc)).selectColumn(p -> ProDsl.of(p).jsonAt(p.name(), root.key("nothing"))).singleNotNull());
        assertEquals("[]", query.queryable(source, TestProduct.class, Arrays.asList(doc)).selectColumn(p -> ProDsl.of(p).jsonAt(p.name(), root.key("items").index(3)).asText()).singleNotNull());
        assertEquals("{}", query.queryable(source, TestProduct.class, Arrays.asList(doc)).selectColumn(p -> ProDsl.of(p).jsonAt(p.name(), root.key("items").index(4)).asText()).singleNotNull());
        EntityQueryable<TestProductProxy, TestProduct> filtered = query.queryable(source, TestProduct.class, Arrays.asList(doc)).where(p -> ProDsl.of(p).jsonExists(p.name(), root.key("nothing")).eq(true));
        assertEquals(1, filtered.toList().size());
        assertEquals(1, filtered.cloneQueryable().toList().size());
        assertEquals("中文", query.queryable("SELECT 1 AS " + quote.getQuoteName("id") + (dialect == SqlDialect.HANA ? " FROM DUMMY" : ""), TestProduct.class)
                .selectColumn(p -> ProDsl.of(p).jsonTextAt(p.expression().constant(doc), root.key("object").key("message"))).singleNotNull());
        for (String scalar : Arrays.asList(null, "null", "\"text\"", "true", "false", "123", "[]", "{}")) {
            Draft2<Boolean, Boolean> row = query.queryable(source, TestProduct.class, Arrays.asList(scalar)).select(p -> {
                ProDsl sql = ProDsl.of(p);
                return Select.DRAFT.of(sql.jsonExists(p.name(), root), sql.jsonExists(p.name(), root.index(0)));
            }).singleNotNull();
            assertEquals(scalar != null, row.getValue1());
            assertFalse(row.getValue2(), String.valueOf(scalar));
        }
        for (String invalid : Arrays.asList("", "invalid json", "1,2")) {
            assertThrows(RuntimeException.class, () -> query.queryable(source, TestProduct.class, Arrays.asList(invalid))
                    .selectColumn(p -> ProDsl.of(p).jsonExists(p.name(), root)).toList());
        }
    }
}
