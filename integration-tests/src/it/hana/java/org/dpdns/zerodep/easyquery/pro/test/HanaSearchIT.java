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
import java.sql.Statement;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.api.client.EasyQueryClient;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.SearchHit;
import java.util.Arrays;
import java.util.stream.Collectors;
import com.easy.query.core.api.pagination.EasyPageResult;
import com.easy.query.core.proxy.core.draft.proxy.Draft4Proxy;
import com.easy.query.core.proxy.core.draft.Draft4;
import org.dpdns.zerodep.easyquery.pro.test.entity.SearchDocument;
import com.easy.query.core.proxy.core.draft.proxy.Draft3Proxy;
import com.easy.query.core.proxy.core.draft.Draft3;
import com.easy.query.core.basic.jdbc.tx.Transaction;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class HanaSearchIT {
    @Test
    void selectOnlyUserCanSearchAndReportOnGrantedPhysicalTables() throws SQLException {
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("FULLTEXT_PERMISSIONS");
             HanaTestDatabase.TestUser user = HanaTestDatabase.createUser("FULLTEXT_READER")) {
            createDocuments(schema);
            try (Statement owner = schema.connection().createStatement()) {
                owner.execute("CREATE COLUMN TABLE " + schema.qualified("ungranted") + " (\"id\" INTEGER)");
                owner.execute("GRANT SELECT ON " + schema.qualified("eqp_search_document") + " TO " + user.name());
            }
            // 索引与目录检查只由 fixture owner 完成；业务 client 不授予 CREATE ANY/CATALOG READ。
            DefaultEasyEntityQuery query = TestClients.configured(user.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
            EasyQueryClient client = query.getEasyQueryClient();
            assertEquals(user.name(), client.sqlQuery("SELECT CURRENT_USER FROM DUMMY", String.class).get(0));
            HanaSearch search = HanaSearch.from(query.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name()),
                    p -> HanaSearchSpec.exact("coffee", p.title()));
            EntityQueryable<SearchHitProxy, SearchHit> hits = search.query(SearchHitProxy::createTable);
            List<SearchHit> rows = hits.cloneQueryable().orderBy(p -> p.id().asc()).toList();
            assertEquals(Arrays.asList(1, 6), rows.stream().map(row -> row.getId()).collect(Collectors.toList()));
            rows.forEach(row -> assertTrue(Double.isFinite(row.getScore())));
            assertEquals(2L, hits.cloneQueryable().count());
            EasyPageResult<SearchHit> page = hits.cloneQueryable().orderBy(p -> p.id().asc()).toPageResult(2, 1);
            assertEquals(2L, page.getTotal());
            assertEquals(6, page.getData().get(0).getId());

            EntityQueryable<Draft4Proxy<String, Long, Number, Long>, Draft4<String, Long, Number, Long>> report = search.query(SearchHitProxy::createTable).groupBy(p -> GroupKeys.of(p.title()))
                    .select(g -> Select.DRAFT.of(g.key1(), g.count()))
                    .select(p -> {
                        ProDsl pro = ProDsl.of(p);
                        return Select.DRAFT.of(p.value1(), p.value2(), pro.over(p.value2().asLong().sum(), pro.window()),
                                pro.rowNumber(pro.window().orderBy(p.value1())));
                    }).toCteAs("granted_search_report");
            report.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
            Draft4<String, Long, Number, Long> tea = report.cloneQueryable().where(p -> p.value1().eq("coffee tea")).singleNotNull();
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
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("FULLTEXT_DSL")) {
            createDocuments(schema);
            DefaultEasyEntityQuery client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
            for (String term : Arrays.asList("coffee", "O'Reilly", "中文", "coffee OR tea", "\"coffee beans\"", "unmatchedzz")) {
                EntityQueryable<SearchDocumentProxy, SearchDocument> source = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name());
                List<SearchHit> result = HanaSearch.from(source, p -> HanaSearchSpec.exact(term, p.title()))
                        .query(SearchHitProxy::createTable).orderBy(p -> p.id().asc()).toList();
                assertEquals(search(schema, "\"title\"", term, "EXACT", null),
                        result.stream().map(row -> new Hit(row.getId(), row.getScore())).collect(Collectors.toList()), term);
            }
            for (double threshold : new double[]{0, 0.7, 1}) {
                EntityQueryable<SearchDocumentProxy, SearchDocument> source = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name());
                List<SearchHit> result = HanaSearch.from(source, p -> HanaSearchSpec.fuzzy("cofee", threshold, p.title()))
                        .query(SearchHitProxy::createTable).orderBy(p -> p.id().asc()).toList();
                assertEquals(search(schema, "\"title\"", "cofee", "FUZZY(?)", threshold),
                        result.stream().map(row -> new Hit(row.getId(), row.getScore())).collect(Collectors.toList()));
            }
            EntityQueryable<SearchDocumentProxy, SearchDocument> source = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name());
            HanaSearch search = HanaSearch.from(source, p -> HanaSearchSpec.exact("coffee", p.title()));
            source.where(p -> p.id().lt(0));
            EntityQueryable<SearchHitProxy, SearchHit> hits = search.query(SearchHitProxy::createTable);
            hits.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
            assertEquals(2L, hits.cloneQueryable().count());
            assertEquals(2, hits.cloneQueryable().where(p -> p.score().ge(0.0)).toList().size());
            SearchHit first = hits.cloneQueryable().where(p -> p.id().eq(1)).singleNotNull();
            assertEquals(search(schema, "\"title\"", "coffee", "EXACT", null).get(0).score(), first.getScore());
            EasyPageResult<SearchHit> page = hits.cloneQueryable().orderBy(p -> p.id().asc()).toPageResult(2, 1);
            assertEquals(2L, page.getTotal());
            assertEquals(6, page.getData().get(0).getId());
            assertEquals(2L, hits.cloneQueryable().toCteAs("search_hits").count());
            // 上游会把无其他操作的右表 queryable 折叠为实体 JOIN；显式投影保留其 asSchema。
            EntityQueryable<SearchDocumentProxy, SearchDocument> right = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name()).select(p -> p);
            assertEquals(2, hits.cloneQueryable()
                    .leftJoin(right, (hit, document) -> hit.id().eq(document.id()))
                    .select((hit, document) -> hit).toList().size());
            EntityQueryable<Draft3Proxy<Integer, Double, Double>, Draft3<Integer, Double, Double>> joinedSearches = search.query(SearchHitProxy::createTable)
                    .innerJoin(search.query(SearchHitProxy::createTable), (left, other) -> left.id().eq(other.id()))
                    .select((left, other) -> Select.DRAFT.of(left.id(), left.score(), other.score()));
            SQLException unsupported = HanaTestDatabase.databaseCause(assertThrows(RuntimeException.class, joinedSearches::toList));
            assertEquals(7, unsupported.getErrorCode());
            assertTrue(unsupported.getMessage().contains("score()"));
            EntityQueryable<SearchHitProxy, SearchHit> union = search.query(SearchHitProxy::createTable).unionAll(search.query(SearchHitProxy::createTable));
            assertEquals(4L, union.cloneQueryable().count());
            assertEquals(Arrays.asList(1, 1, 6, 6), union.orderBy(p -> p.id().asc()).toList().stream().map(row -> row.getId()).collect(Collectors.toList()));
            EntityQueryable<SearchDocumentProxy, SearchDocument> constrained = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name());
            constrained.getClientQueryable().where(where -> {
                where.eq("id", 3);
                where.or();
                where.eq("id", 1);
            });
            assertEquals(2L, constrained.cloneQueryable().count());
            assertEquals(Arrays.asList(1), HanaSearch.from(constrained, p -> HanaSearchSpec.exact("coffee", p.title()))
                    .query(SearchHitProxy::createTable).toList().stream().map(row -> row.getId()).collect(Collectors.toList()));
        }
    }

    @Test
    void resultReadersAreIsolatedAndQueriesReuseTheOriginalTransaction() throws SQLException {
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("FULLTEXT_MAPPING")) {
            createDocuments(schema);
            DefaultEasyEntityQuery client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
            EntityQueryable<SearchDocumentProxy, SearchDocument> source = client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name());
            source.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getResultValueConverterMap(true)
                    .put("title", (index, result) -> "SOURCE:" + result.getString(index + 1));
            assertEquals("SOURCE:coffee beans", source.cloneQueryable().where(p -> p.id().eq(1)).singleNotNull().getTitle());
            HanaSearch search = HanaSearch.from(source, p -> HanaSearchSpec.exact("coffee", p.title()));
            assertEquals("coffee beans", search.query(SearchHitProxy::createTable).where(p -> p.id().eq(1)).singleNotNull().getTitle());
            HanaSearch transactional = HanaSearch.from(client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name()),
                    p -> HanaSearchSpec.exact("transactioncoffee", p.title()));
            try (Transaction transaction = client.getEasyQueryClient().beginTransaction()) {
                assertEquals(1L, client.getEasyQueryClient().sqlExecute("INSERT INTO " + schema.qualified("eqp_search_document") + " VALUES(?,?,?,?)",
                        Arrays.asList(99, "transactioncoffee", "same client transaction", "unindexed")));
                assertEquals(99, transactional.query(SearchHitProxy::createTable).singleNotNull().getId());
                transaction.rollback();
            }
            assertTrue(transactional.query(SearchHitProxy::createTable).toList().isEmpty());
        }
    }

    @Test
    void multiColumnAndConjoinedClausesUseOneCombinedScore() throws SQLException {
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("FULLTEXT_MULTI")) {
            createDocuments(schema);
            DefaultEasyEntityQuery client = TestClients.configured(HanaTestDatabase.dataSource(), new HanaDatabaseConfiguration(), HanaExtensions::configure);
            SearchHit multi = HanaSearch.from(client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name()),
                    p -> HanaSearchSpec.exact("Arabica", p.title(), p.body())).query(SearchHitProxy::createTable).singleNotNull();
            assertEquals(1, multi.getId());
            assertEquals(search(schema, "(\"title\",\"body\")", "Arabica", "EXACT", null).get(0).score(), multi.getScore());
            SearchHit combined = HanaSearch.from(client.queryable(SearchDocumentProxy.createTable()).asSchema(ignored -> schema.name()),
                            p -> HanaSearchSpec.exact("coffee", p.title()).and(HanaSearchSpec.exact("Colombia", p.body())))
                    .query(SearchHitProxy::createTable).singleNotNull();
            assertEquals(1, combined.getId());
            try (PreparedStatement statement = schema.connection().prepareStatement("SELECT SCORE() FROM " + schema.qualified("eqp_search_document")
                    + " WHERE CONTAINS(\"title\",?,EXACT,FULLTEXT(ON)) AND CONTAINS(\"body\",?,EXACT,FULLTEXT(ON))")) {
                statement.setString(1, "coffee");
                statement.setString(2, "Colombia");
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(result.getDouble(1), combined.getScore());
                    assertFalse(result.next());
                }
            }
        }
    }

    @Test
    void fullTextIndexSupportsBoundSearchAndFuzzyThreshold() throws SQLException {
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("FULLTEXT")) {
            createDocuments(schema);
            List<HanaSearchIT.Hit> exact = search(schema, "\"title\"", "coffee", "EXACT", null);
            assertEquals(Arrays.asList(1, 6), exact.stream().map(Hit::id).collect(Collectors.toList()));
            List<HanaSearchIT.Hit> fuzzy = search(schema, "\"title\"", "cofee", "FUZZY(?)", 0.7);
            assertTrue(fuzzy.stream().anyMatch(hit -> hit.id() == 1));
            assertTrue(fuzzy.stream().anyMatch(hit -> hit.id() == 2));
            assertFalse(fuzzy.stream().anyMatch(hit -> hit.id() == 4));
            assertEquals(Arrays.asList(1), search(schema, "(\"title\",\"body\")", "Arabica", "EXACT", null).stream().map(Hit::id).collect(Collectors.toList()));
            assertThrows(SQLException.class, () -> search(schema, "\"unindexed\"", "coffee", "EXACT", null));
        }
    }

    private static void createDocuments(HanaTestDatabase.TestSchema schema) throws SQLException {
        String table = schema.qualified("eqp_search_document");
        try (Statement statement = schema.connection().createStatement()) {
            statement.execute("CREATE COLUMN TABLE " + table
                    + " (\"id\" INTEGER PRIMARY KEY,\"title\" NVARCHAR(200),\"body\" NVARCHAR(1000),\"unindexed\" NVARCHAR(200))");
            for (String column : Arrays.asList("title", "body")) {
                statement.execute("CREATE FULLTEXT INDEX " + schema.qualified(column + "_ft") + " ON " + table
                        + "(\"" + column + "\") FUZZY SEARCH INDEX ON SYNC FAST PREPROCESS ON TEXT ANALYSIS OFF");
            }
        }
        try (PreparedStatement statement = schema.connection().prepareStatement("SELECT COUNT(*) FROM SYS.FULLTEXT_INDEXES WHERE SCHEMA_NAME=? AND TABLE_NAME=?")) {
            statement.setString(1, schema.name());
            statement.setString(2, "eqp_search_document");
            try (ResultSet result = statement.executeQuery()) {
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
        try (PreparedStatement insert = schema.connection().prepareStatement("INSERT INTO " + table + " VALUES(?,?,?,?)")) {
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
        ArrayList<HanaSearchIT.Hit> hits = new ArrayList<Hit>();
        try (PreparedStatement statement = schema.connection().prepareStatement("SELECT \"id\",SCORE() FROM "
                + schema.qualified("eqp_search_document") + " WHERE CONTAINS(" + columns + ",?," + mode + ",FULLTEXT(ON)) ORDER BY \"id\"")) {
            statement.setString(1, searchQuery);
            if (threshold != null) statement.setDouble(2, threshold);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) hits.add(new Hit(result.getInt(1), result.getDouble(2)));
            }
        }
        return hits;
    }

    private static final class Hit {
        private final int id;
        private final double score;

        private Hit(int id, double score) {
            this.id = id;
            this.score = score;
        }

        public int id() {
            return id;
        }

        public double score() {
            return score;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Hit)) return false;
            Hit other = (Hit) o;
            return id == other.id
                    && Double.compare(score, other.score) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, score);
        }

        @Override
        public String toString() {
            return "Hit[id=" + id + ", score=" + score + "]";
        }
    }

}
