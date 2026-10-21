package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.enums.EasyBehaviorEnum;
import com.easy.query.core.proxy.sql.Select;
import com.easy.query.mysql.config.MySQLDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.MySqlExtensions;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.query.MySqlSearch;
import org.dpdns.zerodep.easyquery.pro.dsl.mysql.query.MySqlSearchSpec;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchDocumentProxy;
import org.dpdns.zerodep.easyquery.pro.test.entity.proxy.SearchHitProxy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.easy.query.api.proxy.client.DefaultEasyEntityQuery;
import java.util.Arrays;
import com.easy.query.api.proxy.entity.select.EntityQueryable;
import org.dpdns.zerodep.easyquery.pro.test.entity.SearchDocument;
import org.dpdns.zerodep.easyquery.pro.test.entity.SearchHit;
import java.util.stream.Collectors;
import com.easy.query.core.api.pagination.EasyPageResult;
import com.easy.query.core.proxy.core.draft.Draft3;
import com.easy.query.core.basic.jdbc.tx.Transaction;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class MySqlSearchIT {
    private static final String COLUMNS = "id INTEGER PRIMARY KEY,title TEXT,body TEXT,unindexed TEXT,"
            + "FULLTEXT title_ft(title),FULLTEXT text_ft(title,body)";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void typedModesKeepNativeScoresAndComposeThroughTheOriginalQueryApi(boolean serverPrepared) throws SQLException {
        MysqlDataSource dataSource = MySqlTestDatabase.dataSource();
        dataSource.setUseServerPrepStmts(serverPrepared);
        dataSource.setEmulateUnsupportedPstmts(false);
        try (MySqlTestDatabase.Table table = MySqlTestDatabase.createTable(dataSource, "eqp_search_", COLUMNS)) {
            createDocuments(table);
            DefaultEasyEntityQuery client = TestClients.configured(dataSource, new MySQLDatabaseConfiguration(), services -> {
                MySqlExtensions.configure(services);
                MySqlExtensions.configureQueryScope(services);
            });
            for (boolean booleanMode : Arrays.asList(false, true)) {
                List<String> terms = booleanMode ? Arrays.asList("+coffee -tea", "\"coffee beans\"", "coff*", "~coffee", "+coffee ~tea", "-coffee")
                        : Arrays.asList("coffee", "O'Reilly", "中文", "unmatchedzz");
                for (String term : terms) {
                    EntityQueryable<SearchDocumentProxy, SearchDocument> source = client.queryable(SearchDocumentProxy.createTable()).asTable(table.name());
                    List<SearchHit> result = MySqlSearch.from(source, p -> booleanMode ? MySqlSearchSpec.booleanMode(term, p.title())
                                    : MySqlSearchSpec.naturalLanguage(term, p.title()))
                            .query(SearchHitProxy::createTable).orderBy(p -> p.id().asc()).toList();
                    assertEquals(search(table, "title", term, booleanMode),
                            result.stream().map(row -> new Hit(row.getId(), row.getScore())).collect(Collectors.toList()), term);
                }
            }
            EntityQueryable<SearchDocumentProxy, SearchDocument> source = client.queryable(SearchDocumentProxy.createTable()).asTable(table.name());
            MySqlSearch definition = MySqlSearch.from(source, p -> MySqlSearchSpec.naturalLanguage("coffee", p.title()));
            source.where(p -> p.id().lt(0));
            EntityQueryable<SearchHitProxy, SearchHit> hits = definition.query(SearchHitProxy::createTable);
            hits.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getBehavior().addBehavior(EasyBehaviorEnum.SMART_PREDICATE);
            assertEquals(2L, hits.cloneQueryable().count());
            assertEquals(search(table, "title", "coffee", false).get(0).score(),
                    hits.cloneQueryable().where(p -> p.id().eq(1)).singleNotNull().getScore());
            EasyPageResult<SearchHit> page = hits.cloneQueryable().orderBy(p -> p.id().asc()).toPageResult(2, 1);
            assertEquals(2L, page.getTotal());
            assertEquals(6, page.getData().get(0).getId());
            assertEquals(2L, hits.cloneQueryable().toCteAs("search_hits").count());
            List<Draft3<Integer, Double, Double>> joined = definition.query(SearchHitProxy::createTable)
                    .innerJoin(definition.query(SearchHitProxy::createTable), (left, right) -> left.id().eq(right.id()))
                    .select((left, right) -> Select.DRAFT.of(left.id(), left.score(), right.score())).toList();
            assertEquals(2, joined.size());
            joined.forEach(row -> assertEquals(row.getValue2(), row.getValue3()));
            EntityQueryable<SearchHitProxy, SearchHit> union = definition.query(SearchHitProxy::createTable).unionAll(definition.query(SearchHitProxy::createTable));
            assertEquals(4L, union.cloneQueryable().count());
            assertEquals(Arrays.asList(1, 1, 6, 6), union.orderBy(p -> p.id().asc()).toList().stream().map(row -> row.getId()).collect(Collectors.toList()));
            EntityQueryable<SearchDocumentProxy, SearchDocument> constrained = client.queryable(SearchDocumentProxy.createTable()).asTable(table.name());
            constrained.getClientQueryable().where(p -> {
                p.eq("id", 3);
                p.or();
                p.eq("id", 1);
            });
            assertEquals(2L, constrained.cloneQueryable().count());
            assertEquals(Arrays.asList(1), MySqlSearch.from(constrained, p -> MySqlSearchSpec.naturalLanguage("coffee", p.title()))
                    .query(SearchHitProxy::createTable).toList().stream().map(row -> row.getId()).collect(Collectors.toList()));
            SearchHit multi = MySqlSearch.from(client.queryable(SearchDocumentProxy.createTable()).asTable(table.name()),
                    p -> MySqlSearchSpec.naturalLanguage("Arabica", p.title(), p.body())).query(SearchHitProxy::createTable).singleNotNull();
            assertEquals(search(table, "title,body", "Arabica", false).get(0).score(), multi.getScore());

            EntityQueryable<SearchDocumentProxy, SearchDocument> readers = client.queryable(SearchDocumentProxy.createTable()).asTable(table.name());
            readers.getClientQueryable().getSQLEntityExpressionBuilder().getExpressionContext().getResultValueConverterMap(true)
                    .put("title", (index, result) -> "SOURCE:" + result.getString(index + 1));
            assertEquals("SOURCE:coffee beans", readers.cloneQueryable().where(p -> p.id().eq(1)).singleNotNull().getTitle());
            assertEquals("coffee beans", MySqlSearch.from(readers, p -> MySqlSearchSpec.naturalLanguage("coffee", p.title()))
                    .query(SearchHitProxy::createTable).where(p -> p.id().eq(1)).singleNotNull().getTitle());
            MySqlSearch transactional = MySqlSearch.from(client.queryable(SearchDocumentProxy.createTable()).asTable(table.name()),
                    p -> MySqlSearchSpec.naturalLanguage("transactioncoffee", p.title()));
            try (Transaction transaction = client.getEasyQueryClient().beginTransaction()) {
                assertEquals(1L, client.getEasyQueryClient().sqlExecute("INSERT INTO `" + table.name() + "` VALUES(?,?,?,?)",
                        Arrays.asList(99, "transactioncoffee", "commit visibility", "unindexed")));
                assertTrue(transactional.query(SearchHitProxy::createTable).toList().isEmpty());
                transaction.commit();
            }
            assertEquals(99, transactional.query(SearchHitProxy::createTable).singleNotNull().getId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeModesBindQueriesAndIndexChangesBecomeVisibleAtCommit(boolean serverPrepared) throws SQLException {
        MysqlDataSource source = MySqlTestDatabase.dataSource();
        source.setUseServerPrepStmts(serverPrepared);
        source.setEmulateUnsupportedPstmts(false);
        try (MySqlTestDatabase.Table table = MySqlTestDatabase.createTable(source, "eqp_search_", COLUMNS)) {
            createDocuments(table);
            assertEquals(Arrays.asList(1, 6), search(table, "title", "coffee", false).stream().map(Hit::id).collect(Collectors.toList()));
            assertEquals(Arrays.asList(1), search(table, "title", "+coffee -tea", true).stream().map(Hit::id).collect(Collectors.toList()));
            assertEquals(Arrays.asList(1), search(table, "title,body", "Arabica", false).stream().map(Hit::id).collect(Collectors.toList()));
            assertThrows(SQLException.class, () -> search(table, "unindexed", "coffee", false));

            Connection connection = table.connection();
            connection.setAutoCommit(false);
            insert(connection, table.name(), 99, "transactioncoffee", "commit visibility");
            assertTrue(search(table, "title", "transactioncoffee", false).isEmpty());
            connection.commit();
            assertEquals(Arrays.asList(99), search(table, "title", "transactioncoffee", false).stream().map(Hit::id).collect(Collectors.toList()));
            insert(connection, table.name(), 100, "rollbackcoffee", "rollback visibility");
            connection.rollback();
            assertTrue(search(table, "title", "rollbackcoffee", false).isEmpty());
            connection.setAutoCommit(true);
        }
    }

    private static void createDocuments(MySqlTestDatabase.Table table) throws SQLException {
        try (PreparedStatement statement = table.connection().prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
            statement.setString(1, table.name());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("InnoDB", result.getString(1));
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
        for (int i = 0; i < documents.length; i++)
            insert(table.connection(), table.name(), i + 1, documents[i][0], documents[i][1]);
    }

    private static void insert(Connection connection, String table, int id, String title, String body) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO `" + table + "` VALUES(?,?,?,?)")) {
            statement.setInt(1, id);
            statement.setString(2, title);
            statement.setString(3, body);
            statement.setString(4, title);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static List<Hit> search(MySqlTestDatabase.Table table, String columns, String searchQuery, boolean booleanMode) throws SQLException {
        String match = "MATCH(" + columns + ") AGAINST(? IN " + (booleanMode ? "BOOLEAN" : "NATURAL LANGUAGE") + " MODE)";
        ArrayList<MySqlSearchIT.Hit> hits = new ArrayList<Hit>();
        try (PreparedStatement statement = table.connection().prepareStatement("SELECT id," + match + " FROM `" + table.name() + "` WHERE " + match + " ORDER BY id")) {
            statement.setString(1, searchQuery);
            statement.setString(2, searchQuery);
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
