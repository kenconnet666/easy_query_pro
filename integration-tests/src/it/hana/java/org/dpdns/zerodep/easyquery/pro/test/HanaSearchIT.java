package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.enums.EasyBehaviorEnum;
import com.easy.query.core.proxy.sql.Select;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaSearch;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaSearchSpec;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.HanaSearchRowProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchDocumentProxy;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HanaSearchIT {
    @Test
    void typedSearchMatchesNativeScoresAndKeepsCompositionOutsideTheSearch() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("FULLTEXT_DSL")) {
            createDocuments(schema);
            var client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
            for (String term : List.of("coffee", "O'Reilly", "中文", "coffee OR tea", "\"coffee beans\"", "unmatchedzz")) {
                var source = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name());
                var result = HanaSearch.from(source, p -> HanaSearchSpec.exact(term, p.title()))
                        .query(HanaSearchRowProxy::createTable).orderBy(p -> p.id().asc()).toList();
                assertEquals(search(schema, "\"title\"", term, "EXACT", null),
                        result.stream().map(row -> new Hit(row.getId(), row.getScore())).toList(), term);
            }
            for (double threshold : new double[]{0, 0.7, 1}) {
                var source = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name());
                var result = HanaSearch.from(source, p -> HanaSearchSpec.fuzzy("cofee", threshold, p.title()))
                        .query(HanaSearchRowProxy::createTable).orderBy(p -> p.id().asc()).toList();
                assertEquals(search(schema, "\"title\"", "cofee", "FUZZY(?)", threshold),
                        result.stream().map(row -> new Hit(row.getId(), row.getScore())).toList());
            }
            var source = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name());
            var search = HanaSearch.from(source, p -> HanaSearchSpec.exact("coffee", p.title()));
            source.where(p -> p.id().lt(0));
            var hits = search.query(HanaSearchRowProxy::createTable);
            hits.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
            assertEquals(2L, hits.cloneQueryable().count());
            assertEquals(2, hits.cloneQueryable().where(p -> p.score().ge(0.0)).toList().size());
            var first = hits.cloneQueryable().where(p -> p.id().eq(1)).singleNotNull();
            assertEquals(search(schema, "\"title\"", "coffee", "EXACT", null).get(0).score(), first.getScore());
            var page = hits.cloneQueryable().orderBy(p -> p.id().asc()).toPageResult(2, 1);
            assertEquals(2L, page.getTotal());
            assertEquals(6, page.getData().get(0).getId());
            assertEquals(2L, hits.cloneQueryable().toCteAs("search_hits").count());
            // 上游会把无其他操作的右表 queryable 折叠为实体 JOIN；显式投影保留其 asSchema。
            var right = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name()).select(p -> p);
            assertEquals(2, hits.cloneQueryable()
                    .leftJoin(right, (hit, document) -> hit.id().eq(document.id()))
                    .select((hit, document) -> hit).toList().size());
            var joinedSearches = search.query(HanaSearchRowProxy::createTable)
                    .innerJoin(search.query(HanaSearchRowProxy::createTable), (left, other) -> left.id().eq(other.id()))
                    .select((left, other) -> Select.DRAFT.of(left.id(), left.score(), other.score()));
            var unsupported = databaseCause(assertThrows(RuntimeException.class, joinedSearches::toList));
            assertEquals(7, unsupported.getErrorCode());
            assertTrue(unsupported.getMessage().contains("score()"));
            var union = search.query(HanaSearchRowProxy::createTable).unionAll(search.query(HanaSearchRowProxy::createTable));
            assertEquals(4L, union.cloneQueryable().count());
            assertEquals(List.of(1, 1, 6, 6), union.orderBy(p -> p.id().asc()).toList().stream().map(row -> row.getId()).toList());
            var constrained = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name());
            constrained.getClientQueryable().where(where -> {
                where.eq("id", 3);
                where.or();
                where.eq("id", 1);
            });
            assertEquals(2L, constrained.cloneQueryable().count());
            assertEquals(List.of(1), HanaSearch.from(constrained, p -> HanaSearchSpec.exact("coffee", p.title()))
                    .query(HanaSearchRowProxy::createTable).toList().stream().map(row -> row.getId()).toList());
        }
    }

    @Test
    void resultReadersAreIsolatedAndQueriesReuseTheOriginalTransaction() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("FULLTEXT_MAPPING")) {
            createDocuments(schema);
            var client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
            var source = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name());
            source.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getResultValueConverterMap(true)
                    .put("title", (index, result) -> "SOURCE:" + result.getString(index + 1));
            assertEquals("SOURCE:coffee beans", source.cloneQueryable().where(p -> p.id().eq(1)).singleNotNull().getTitle());
            var search = HanaSearch.from(source, p -> HanaSearchSpec.exact("coffee", p.title()));
            assertEquals("coffee beans", search.query(HanaSearchRowProxy::createTable).where(p -> p.id().eq(1)).singleNotNull().getTitle());
            var transactional = HanaSearch.from(client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name()),
                    p -> HanaSearchSpec.exact("transactioncoffee", p.title()));
            try (var transaction = client.getEasyQueryClient().beginTransaction()) {
                assertEquals(1L, client.getEasyQueryClient().sqlExecute("INSERT INTO " + schema.qualified("eqp_search_document") + " VALUES(?,?,?,?)",
                        List.of(99, "transactioncoffee", "same client transaction", "unindexed")));
                assertEquals(99, transactional.query(HanaSearchRowProxy::createTable).singleNotNull().getId());
                transaction.rollback();
            }
            assertTrue(transactional.query(HanaSearchRowProxy::createTable).toList().isEmpty());
        }
    }

    @Test
    void multiColumnAndConjoinedClausesUseOneCombinedScore() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("FULLTEXT_MULTI")) {
            createDocuments(schema);
            var client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
            var multi = HanaSearch.from(client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name()),
                    p -> HanaSearchSpec.exact("Arabica", p.title(), p.body())).query(HanaSearchRowProxy::createTable).singleNotNull();
            assertEquals(1, multi.getId());
            assertEquals(search(schema, "(\"title\",\"body\")", "Arabica", "EXACT", null).get(0).score(), multi.getScore());
            var combined = HanaSearch.from(client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name()),
                            p -> HanaSearchSpec.exact("coffee", p.title()).and(HanaSearchSpec.exact("Colombia", p.body())))
                    .query(HanaSearchRowProxy::createTable).singleNotNull();
            assertEquals(1, combined.getId());
            try (var statement = schema.connection().prepareStatement("SELECT SCORE() FROM " + schema.qualified("eqp_search_document")
                    + " WHERE CONTAINS(\"title\",?,EXACT,FULLTEXT(ON)) AND CONTAINS(\"body\",?,EXACT,FULLTEXT(ON))")) {
                statement.setString(1, "coffee");
                statement.setString(2, "Colombia");
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(result.getDouble(1), combined.getScore());
                    assertFalse(result.next());
                }
            }
        }
    }

    @Test
    void fullTextIndexSupportsBoundSearchAndFuzzyThreshold() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("FULLTEXT")) {
            createDocuments(schema);
            var exact = search(schema, "\"title\"", "coffee", "EXACT", null);
            assertEquals(List.of(1, 6), exact.stream().map(Hit::id).toList());
            var fuzzy = search(schema, "\"title\"", "cofee", "FUZZY(?)", 0.7);
            assertTrue(fuzzy.stream().anyMatch(hit -> hit.id() == 1));
            assertTrue(fuzzy.stream().anyMatch(hit -> hit.id() == 2));
            assertFalse(fuzzy.stream().anyMatch(hit -> hit.id() == 4));
            assertEquals(List.of(1), search(schema, "(\"title\",\"body\")", "Arabica", "EXACT", null).stream().map(Hit::id).toList());
            assertThrows(SQLException.class, () -> search(schema, "\"unindexed\"", "coffee", "EXACT", null));
        }
    }

    private static void createDocuments(HanaTestDatabase.TestSchema schema) throws SQLException {
        String table = schema.qualified("eqp_search_document");
        try (var statement = schema.connection().createStatement()) {
            statement.execute("CREATE COLUMN TABLE " + table
                    + " (\"id\" INTEGER PRIMARY KEY,\"title\" NVARCHAR(200),\"body\" NVARCHAR(1000),\"unindexed\" NVARCHAR(200))");
            for (String column : List.of("title", "body")) {
                statement.execute("CREATE FULLTEXT INDEX " + schema.qualified(column + "_ft") + " ON " + table
                        + "(\"" + column + "\") FUZZY SEARCH INDEX ON SYNC FAST PREPROCESS ON TEXT ANALYSIS OFF");
            }
        }
        try (var statement = schema.connection().prepareStatement("SELECT COUNT(*) FROM SYS.FULLTEXT_INDEXES WHERE SCHEMA_NAME=? AND TABLE_NAME=?")) {
            statement.setString(1, schema.name());
            statement.setString(2, "eqp_search_document");
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
        }
        String[][] documents = {
                {"coffee beans", "Arabica coffee from Colombia"},
                {"cofee beans", "Roasted cofee"},
                {"green tea", "Jasmine tea"},
                {null, null},
                {"O'Reilly 中文", "SQL ' OR 1=1 -- sample"},
                {"coffee tea", "coffee green"}
        };
        try (var insert = schema.connection().prepareStatement("INSERT INTO " + table + " VALUES(?,?,?,?)")) {
            for (int i = 0; i < documents.length; i++) {
                insert.setInt(1, i + 1);
                insert.setString(2, documents[i][0]);
                insert.setString(3, documents[i][1]);
                insert.setString(4, documents[i][0]);
                insert.addBatch();
            }
            assertEquals(documents.length, insert.executeBatch().length);
        }
    }

    private static List<Hit> search(HanaTestDatabase.TestSchema schema, String columns, String searchQuery, String mode, Double threshold) throws SQLException {
        var hits = new ArrayList<Hit>();
        try (var statement = schema.connection().prepareStatement("SELECT \"id\",SCORE() FROM "
                + schema.qualified("eqp_search_document") + " WHERE CONTAINS(" + columns + ",?," + mode + ",FULLTEXT(ON)) ORDER BY \"id\"")) {
            statement.setString(1, searchQuery);
            if (threshold != null) statement.setDouble(2, threshold);
            try (var result = statement.executeQuery()) {
                while (result.next()) hits.add(new Hit(result.getInt(1), result.getDouble(2)));
            }
        }
        return hits;
    }

    private record Hit(int id, double score) {
    }

    private static SQLException databaseCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() != 0) return sql;
        }
        throw new AssertionError("Expected a database diagnostic", failure);
    }
}
