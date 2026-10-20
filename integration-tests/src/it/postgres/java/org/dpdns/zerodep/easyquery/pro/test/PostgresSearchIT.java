package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import com.easy.query.core.enums.EasyBehaviorEnum;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.pgsql.config.PgSQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.PgSqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.query.PgSearch;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.query.PgSearchSpec;
import org.dpdns.zerodep.easyquery.pro.dsl.pgsql.search.PgTsQuery;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.PgSearchDocumentProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchHitProxy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.dpdns.zerodep.easyquery.pro.dsl.pgsql.query.PgSearchSpec.Normalization.*;
import static org.dpdns.zerodep.easyquery.pro.dsl.pgsql.query.PgSearchSpec.Ranking.*;
import static org.junit.jupiter.api.Assertions.*;

class PostgresSearchIT {
    private static final String ENGLISH = "pg_catalog.english";
    private static final String PLAIN = "plainto_tsquery(CAST(? AS regconfig),CAST(? AS text))";
    private static final String WEB = "websearch_to_tsquery(CAST(? AS regconfig),CAST(? AS text))";

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void nativeInputsAndRankNormalizationKeepPostgresSemantics(int prepareThreshold) throws SQLException {
        var source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(prepareThreshold);
        try (var schema = PostgresTestDatabase.createSchema(source, "eqp_pg_search_query_")) {
            createDocuments(schema);
            var client = client(source);
            var ranking = List.of(new Rank(FREQUENCY, "ts_rank", 0),
                    new Rank(FREQUENCY, "ts_rank", 34, DOCUMENT_LENGTH, RANK_OVER_RANK_PLUS_ONE),
                    new Rank(COVER_DENSITY, "ts_rank_cd", 5, LOG_DOCUMENT_LENGTH, MEAN_EXTENT_DISTANCE),
                    new Rank(COVER_DENSITY, "ts_rank_cd", 56, UNIQUE_WORDS, LOG_UNIQUE_WORDS, RANK_OVER_RANK_PLUS_ONE));
            for (var rank : ranking) {
                for (String text : List.of("coffee", "O'Reilly", "中文", "the and", "' OR 1=1 --", "unmatchedzz")) {
                    assertSearch(client, schema, p -> PgSearchSpec.plain(ENGLISH, text, p.document()),
                            PLAIN, List.of(ENGLISH, text), rank);
                }
                for (String text : List.of("\"coffee beans\"", "coffee OR tea", "coffee -tea", "\"\"\" )( dummy <->")) {
                    assertSearch(client, schema, p -> PgSearchSpec.web(ENGLISH, text, p.document()),
                            WEB, List.of(ENGLISH, text), rank);
                }
                for (String encoded : List.of("'coff':*", "'coffee' <-> 'bean'", "!'tea'", "")) {
                    assertSearch(client, schema, p -> PgSearchSpec.query(new PgTsQuery(encoded), p.document()),
                            "CAST(? AS tsquery)", List.of(encoded), rank);
                }
            }
            assertEquals(List.of(1, 6), definition(client, "coffee").query(SearchHitProxy::createTable)
                    .orderBy(p -> p.id().asc()).toList().stream().map(row -> row.getId()).toList());
            assertTrue(definition(client, "the and").query(SearchHitProxy::createTable).toList().isEmpty());
            assertEquals(7, definition(client, "running").query(SearchHitProxy::createTable).singleNotNull().getId());
            var mismatchedDictionary = PgSearch.from(client.queryable(PgSearchDocumentProxy.createTable()),
                    p -> PgSearchSpec.plain("pg_catalog.simple", "running", p.document()));
            assertTrue(mismatchedDictionary.query(SearchHitProxy::createTable).toList().isEmpty(),
                    "A stored English vector cannot restore words removed by stemming using another query dictionary");
            var invalidConfiguration = PgSearch.from(client.queryable(PgSearchDocumentProxy.createTable()),
                    p -> PgSearchSpec.plain("eqp_missing_dictionary", "coffee", p.document()));
            assertTrue(hasState(assertThrows(RuntimeException.class, () -> invalidConfiguration.query(SearchHitProxy::createTable).toList()), "42704"));
            var invalidQuery = PgSearch.from(client.queryable(PgSearchDocumentProxy.createTable()),
                    p -> PgSearchSpec.query(new PgTsQuery("'coffee' &"), p.document()));
            assertTrue(hasState(assertThrows(RuntimeException.class, () -> invalidQuery.query(SearchHitProxy::createTable).toList()), "42601"));
            assertGinPlan(schema, client);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void capturedSearchKeepsQueryCompositionReadersAndTransactionalIndexChanges(int prepareThreshold) throws SQLException {
        var source = PostgresTestDatabase.dataSource();
        source.setPrepareThreshold(prepareThreshold);
        try (var schema = PostgresTestDatabase.createSchema(source, "eqp_pg_search_query_")) {
            createDocuments(schema);
            var client = client(source);
            var rows = client.queryable(PgSearchDocumentProxy.createTable());
            var definition = PgSearch.from(rows, p -> PgSearchSpec.plain(ENGLISH, "coffee", p.document()));
            rows.where(p -> p.id().lt(0));
            var hits = definition.query(SearchHitProxy::createTable);
            hits.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
            assertEquals(2L, hits.cloneQueryable().count());
            var first = nativeSearch(schema, PLAIN, List.of(ENGLISH, "coffee"), "ts_rank", 0).get(0);
            assertEquals(first.score(), hits.cloneQueryable().where(p -> p.id().eq(1)).singleNotNull().getScore());
            var scoreOrdered = hits.cloneQueryable().orderBy(p -> {
                p.score().desc();
                p.id().asc();
            }).toList();
            assertEquals(List.of(1, 6), scoreOrdered.stream().map(row -> row.getId()).toList());
            var page = hits.cloneQueryable().toCteAs("search_hits").orderBy(p -> p.id().asc()).toPageResult(2, 1);
            assertEquals(2L, page.getTotal());
            assertEquals(6, page.getData().get(0).getId());
            var joined = definition.query(SearchHitProxy::createTable)
                    .innerJoin(definition.query(SearchHitProxy::createTable), (left, right) -> left.id().eq(right.id()))
                    .select((left, right) -> Select.DRAFT.of(left.id(), left.score(), right.score())).toList();
            assertEquals(2, joined.size());
            joined.forEach(row -> assertEquals(row.getValue2(), row.getValue3()));
            var union = definition.query(SearchHitProxy::createTable).unionAll(definition.query(SearchHitProxy::createTable));
            assertEquals(4L, union.cloneQueryable().count());
            assertEquals(List.of(1, 1, 6, 6), union.orderBy(p -> p.id().asc()).toList().stream().map(row -> row.getId()).toList());
            var constrained = client.queryable(PgSearchDocumentProxy.createTable());
            constrained.getClientQueryable().where(p -> {
                p.eq("id", 3);
                p.or();
                p.eq("id", 1);
            });
            assertEquals(2L, constrained.cloneQueryable().count());
            assertEquals(1, PgSearch.from(constrained, p -> PgSearchSpec.plain(ENGLISH, "coffee", p.document()))
                    .query(SearchHitProxy::createTable).singleNotNull().getId());
            var readers = client.queryable(PgSearchDocumentProxy.createTable());
            readers.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getResultValueConverterMap(true)
                    .put("title", (index, result) -> "SOURCE:" + result.getString(index + 1));
            assertEquals("SOURCE:coffee beans", readers.cloneQueryable().where(p -> p.id().eq(1)).singleNotNull().getTitle());
            assertEquals("coffee beans", PgSearch.from(readers, p -> PgSearchSpec.plain(ENGLISH, "coffee", p.document()))
                    .query(SearchHitProxy::createTable).where(p -> p.id().eq(1)).singleNotNull().getTitle());

            var transactional = definition(client, "transactioncoffee");
            try (var transaction = client.getEasyQueryClient().beginTransaction()) {
                insert(client, 99, "transactioncoffee", "rollback visibility");
                assertEquals(99, transactional.query(SearchHitProxy::createTable).singleNotNull().getId());
                transaction.rollback();
            }
            assertTrue(transactional.query(SearchHitProxy::createTable).toList().isEmpty());
            try (var transaction = client.getEasyQueryClient().beginTransaction()) {
                insert(client, 99, "transactioncoffee", "commit visibility");
                transaction.commit();
            }
            assertEquals(99, transactional.query(SearchHitProxy::createTable).singleNotNull().getId());
            assertEquals(1L, client.getEasyQueryClient().sqlExecute("UPDATE eqp_search_document SET title=?,body=? WHERE id=?",
                    List.of("replacementcoffee", "updated vector", 99)));
            assertTrue(transactional.query(SearchHitProxy::createTable).toList().isEmpty());
            assertEquals(99, definition(client, "replacementcoffee").query(SearchHitProxy::createTable).singleNotNull().getId());
        }
    }

    private static DefaultEasyEntityQuery client(javax.sql.DataSource source) {
        var client = TestClients.configured(source, new PgSQLDatabaseConfiguration(), services -> {
            PgSqlExtensions.configure(services);
            PgSqlExtensions.configureQueryScope(services);
        });
        PgSqlExtensions.configureTypeHandlers(client.getRuntimeContext().getJdbcTypeHandlerManager());
        return client;
    }

    private static PgSearch definition(DefaultEasyEntityQuery client, String text) {
        return PgSearch.from(client.queryable(PgSearchDocumentProxy.createTable()), p -> PgSearchSpec.plain(ENGLISH, text, p.document()));
    }

    private static void assertSearch(DefaultEasyEntityQuery client, PostgresTestDatabase.Schema schema,
                                     Function<PgSearchDocumentProxy, PgSearchSpec> input, String querySql, List<?> parameters, Rank rank) throws SQLException {
        var actual = PgSearch.from(client.queryable(PgSearchDocumentProxy.createTable()), p -> input.apply(p).rankedBy(rank.ranking(), rank.normalization()))
                .query(SearchHitProxy::createTable).orderBy(p -> p.id().asc()).toList();
        assertEquals(nativeSearch(schema, querySql, parameters, rank.function(), rank.mask()),
                actual.stream().map(row -> new Hit(row.getId(), row.getScore())).toList(), parameters.toString());
    }

    private static List<Hit> nativeSearch(PostgresTestDatabase.Schema schema, String querySql, List<?> parameters,
                                          String rankFunction, int normalization) throws SQLException {
        // 独立原生查询只构造一次 tsquery，与 DSL 每个 SQL 出现位置独立绑定的路径对照。
        String sql = "WITH input AS (SELECT " + querySql + " AS q) SELECT d.id,CAST(" + rankFunction
                + "(d.document,input.q,?) AS double precision) FROM " + schema.qualified("eqp_search_document")
                + " d CROSS JOIN input WHERE d.document @@ input.q ORDER BY d.id";
        var hits = new ArrayList<Hit>();
        try (var statement = schema.connection().prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
            statement.setInt(parameters.size() + 1, normalization);
            try (var result = statement.executeQuery()) {
                while (result.next()) hits.add(new Hit(result.getInt(1), result.getDouble(2)));
            }
        }
        return hits;
    }

    private static void createDocuments(PostgresTestDatabase.Schema schema) throws SQLException {
        try (var statement = schema.connection().createStatement()) {
            statement.execute("CREATE TABLE " + schema.qualified("eqp_search_document")
                    + " (id INTEGER PRIMARY KEY,title TEXT,body TEXT,unindexed TEXT,condition tsquery,"
                    + "document tsvector GENERATED ALWAYS AS (CASE WHEN title IS NULL AND body IS NULL THEN NULL ELSE "
                    + "setweight(to_tsvector('pg_catalog.english',coalesce(title,'')),'A') || "
                    + "setweight(to_tsvector('pg_catalog.english',coalesce(body,'')),'D') END) STORED)");
            statement.execute("CREATE INDEX document_search_idx ON " + schema.qualified("eqp_search_document") + " USING GIN(document)");
        }
        String[][] documents = {
                {"coffee beans", "Arabica coffee from Colombia"},
                {"cofee beans", "Roasted cofee"},
                {"green tea", "Jasmine tea"},
                {null, null},
                {"O'Reilly 中文", "SQL ' OR 1=1 -- sample"},
                {"coffee tea", "coffee green"},
                {"running runners", "running run"}
        };
        try (var statement = schema.connection().prepareStatement("INSERT INTO " + schema.qualified("eqp_search_document")
                + "(id,title,body,unindexed) VALUES(?,?,?,?)")) {
            for (int i = 0; i < documents.length; i++) {
                statement.setInt(1, i + 1);
                statement.setString(2, documents[i][0]);
                statement.setString(3, documents[i][1]);
                statement.setString(4, documents[i][0]);
                assertEquals(1, statement.executeUpdate());
            }
        }
    }

    private static void assertGinPlan(PostgresTestDatabase.Schema schema, DefaultEasyEntityQuery client) throws SQLException {
        var connection = schema.connection();
        var sql = definition(client, "coffee").query(SearchHitProxy::createTable).toSQLResult();
        connection.setAutoCommit(false);
        try {
            // 小表通常合理地选择顺序扫描；这里只在独立事务内证明受控谓词具备使用 GIN 的资格。
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL enable_seqscan=off");
            }
            // owner 早于 source.setCurrentSchema 建立，局部设置路径后解释真正的 DSL SQL。
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL search_path TO \"" + schema.name() + "\"");
            }
            try (var statement = connection.prepareStatement("EXPLAIN (COSTS OFF) " + sql.getSQL())) {
                var parameters = sql.getSqlContext().getParameters();
                for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i).getValue());
                var plan = new StringBuilder();
                try (var result = statement.executeQuery()) {
                    while (result.next()) plan.append(result.getString(1)).append('\n');
                }
                assertTrue(plan.toString().contains("document_search_idx"), plan.toString());
            }
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private static void insert(DefaultEasyEntityQuery client, int id, String title, String body) {
        assertEquals(1L, client.getEasyQueryClient().sqlExecute(
                "INSERT INTO eqp_search_document(id,title,body,unindexed) VALUES(?,?,?,?)", List.of(id, title, body, title)));
    }

    private static boolean hasState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) return true;
        return false;
    }

    private record Hit(int id, double score) {
    }

    private record Rank(PgSearchSpec.Ranking ranking, String function, int mask,
                        PgSearchSpec.Normalization... normalization) {
    }
}
