package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.enums.EasyBehaviorEnum;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.core.proxy.sql.GroupKeys;
import org.dpdns.zerodep.easyquery.pro.dsl.ProDsl;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.HanaExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaSearch;
import org.dpdns.zerodep.easyquery.pro.dsl.hana.query.HanaSearchSpec;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchHitProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchDocumentProxy;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HanaSearchIT {
    @Test
    void selectOnlyUserCanSearchAndReportOnGrantedPhysicalTables() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("FULLTEXT_PERMISSIONS");
             var user = HanaTestDatabase.createUser("FULLTEXT_READER")) {
            createDocuments(schema);
            try (var owner = schema.connection().createStatement()) {
                owner.execute("CREATE COLUMN TABLE " + schema.qualified("ungranted") + " (\"id\" INTEGER)");
                owner.execute("GRANT SELECT ON " + schema.qualified("eqp_search_document") + " TO " + user.name());
            }
            // 索引与目录检查只由 fixture owner 完成；业务 client 不授予 CREATE ANY/CATALOG READ。
            var query = TestClients.configured(user.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
            var client = query.getEasyQueryClient();
            assertEquals(user.name(), client.sqlQuery("SELECT CURRENT_USER FROM DUMMY", String.class).get(0));
            var search = HanaSearch.from(query.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name()),
                    p -> HanaSearchSpec.exact("coffee", p.title()));
            var hits = search.query(SearchHitProxy::createTable);
            var rows = hits.cloneQueryable().orderBy(p -> p.id().asc()).toList();
            assertEquals(List.of(1, 6), rows.stream().map(row -> row.getId()).toList());
            rows.forEach(row -> assertTrue(Double.isFinite(row.getScore())));
            assertEquals(2L, hits.cloneQueryable().count());
            var page = hits.cloneQueryable().orderBy(p -> p.id().asc()).toPageResult(2, 1);
            assertEquals(2L, page.getTotal());
            assertEquals(6, page.getData().get(0).getId());

            var report = search.query(SearchHitProxy::createTable).groupBy(p -> GroupKeys.of(p.title()))
                    .select(g -> Select.DRAFT.of(g.key1(), g.count()))
                    .select(p -> {
                        var pro = ProDsl.of(p);
                        return Select.DRAFT.of(p.value1(), p.value2(), pro.over(p.value2().asLong().sum(), pro.window()),
                                pro.rowNumber(pro.window().orderBy(p.value1())));
                    }).toCteAs("granted_search_report");
            report.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
            var tea = report.cloneQueryable().where(p -> p.value1().eq("coffee tea")).singleNotNull();
            assertEquals(1L, tea.getValue2());
            assertEquals(2L, tea.getValue3());
            assertEquals(2L, tea.getValue4());
            assertEquals(2L, report.cloneQueryable().count());
            assertEquals("coffee tea", report.cloneQueryable().orderBy(p -> p.value1().asc()).toPageResult(2, 1)
                    .getData().get(0).getValue1());
            assertEquals(258, HanaTestDatabase.databaseCause(assertThrows(RuntimeException.class,
                    () -> client.sqlQuery("SELECT COUNT(*) FROM " + schema.qualified("ungranted"), Long.class))).getErrorCode());
            assertEquals(258, HanaTestDatabase.databaseCause(assertThrows(RuntimeException.class,
                    () -> client.sqlExecute("UPDATE " + schema.qualified("eqp_search_document") + " SET \"title\"=\"title\" WHERE \"id\"=-1"))).getErrorCode());
        }
    }

    @Test
    void typedSearchMatchesNativeScoresAndKeepsCompositionOutsideTheSearch() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("FULLTEXT_DSL")) {
            createDocuments(schema);
            var client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
            for (String term : List.of("coffee", "O'Reilly", "中文", "coffee OR tea", "\"coffee beans\"", "unmatchedzz")) {
                var source = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name());
                var result = HanaSearch.from(source, p -> HanaSearchSpec.exact(term, p.title()))
                        .query(SearchHitProxy::createTable).orderBy(p -> p.id().asc()).toList();
                assertEquals(search(schema, "\"title\"", term, "EXACT", null),
                        result.stream().map(row -> new Hit(row.getId(), row.getScore())).toList(), term);
            }
            for (double threshold : new double[]{0, 0.7, 1}) {
                var source = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name());
                var result = HanaSearch.from(source, p -> HanaSearchSpec.fuzzy("cofee", threshold, p.title()))
                        .query(SearchHitProxy::createTable).orderBy(p -> p.id().asc()).toList();
                assertEquals(search(schema, "\"title\"", "cofee", "FUZZY(?)", threshold),
                        result.stream().map(row -> new Hit(row.getId(), row.getScore())).toList());
            }
            var source = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name());
            var search = HanaSearch.from(source, p -> HanaSearchSpec.exact("coffee", p.title()));
            source.where(p -> p.id().lt(0));
            var hits = search.query(SearchHitProxy::createTable);
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
            var joinedSearches = search.query(SearchHitProxy::createTable)
                    .innerJoin(search.query(SearchHitProxy::createTable), (left, other) -> left.id().eq(other.id()))
                    .select((left, other) -> Select.DRAFT.of(left.id(), left.score(), other.score()));
            var unsupported = HanaTestDatabase.databaseCause(assertThrows(RuntimeException.class, joinedSearches::toList));
            assertEquals(7, unsupported.getErrorCode());
            assertTrue(unsupported.getMessage().contains("score()"));
            var union = search.query(SearchHitProxy::createTable).unionAll(search.query(SearchHitProxy::createTable));
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
                    .query(SearchHitProxy::createTable).toList().stream().map(row -> row.getId()).toList());
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
            assertEquals("coffee beans", search.query(SearchHitProxy::createTable).where(p -> p.id().eq(1)).singleNotNull().getTitle());
            var transactional = HanaSearch.from(client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name()),
                    p -> HanaSearchSpec.exact("transactioncoffee", p.title()));
            try (var transaction = client.getEasyQueryClient().beginTransaction()) {
                assertEquals(1L, client.getEasyQueryClient().sqlExecute("INSERT INTO " + schema.qualified("eqp_search_document") + " VALUES(?,?,?,?)",
                        List.of(99, "transactioncoffee", "same client transaction", "unindexed")));
                assertEquals(99, transactional.query(SearchHitProxy::createTable).singleNotNull().getId());
                transaction.rollback();
            }
            assertTrue(transactional.query(SearchHitProxy::createTable).toList().isEmpty());
        }
    }

    @Test
    void multiColumnAndConjoinedClausesUseOneCombinedScore() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("FULLTEXT_MULTI")) {
            createDocuments(schema);
            var client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
            var multi = HanaSearch.from(client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name()),
                    p -> HanaSearchSpec.exact("Arabica", p.title(), p.body())).query(SearchHitProxy::createTable).singleNotNull();
            assertEquals(1, multi.getId());
            assertEquals(search(schema, "(\"title\",\"body\")", "Arabica", "EXACT", null).get(0).score(), multi.getScore());
            var combined = HanaSearch.from(client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name()),
                            p -> HanaSearchSpec.exact("coffee", p.title()).and(HanaSearchSpec.exact("Colombia", p.body())))
                    .query(SearchHitProxy::createTable).singleNotNull();
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

}
