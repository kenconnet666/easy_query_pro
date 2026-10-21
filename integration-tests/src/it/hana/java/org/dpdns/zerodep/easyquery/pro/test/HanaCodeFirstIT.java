package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.basic.api.database.CodeFirstCommandTxArg;
import com.easy.query.core.bootstrapper.EasyQueryBootstrapper;
import com.easy.query.core.enums.conn.ConnectionStrategyEnum;
import com.easy.query.core.migration.DatabaseMigrationProvider;
import com.easy.query.core.migration.MigrationContext;
import com.easy.query.core.migration.commands.DefaultMigrationCommand;
import com.easy.query.core.migration.data.ColumnMigrationData;
import com.easy.query.core.migration.data.IndexMigrationData;
import com.easy.query.core.migration.data.TableMigrationData;
import com.sap.db.jdbc.ConnectionSapDB;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaCatalog;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaRelationMetadata;
import org.dpdns.zerodep.easyquery.pro.hana.migration.HanaForeignKeyMigrationData;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.easy.query.core.api.client.EasyQueryClient;
import com.easy.query.core.context.QueryRuntimeContext;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.Collections;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import com.easy.query.core.migration.MigrationsSQLGenerator;
import com.easy.query.core.basic.api.database.DatabaseCodeFirst;
import java.sql.Statement;
import com.zaxxer.hikari.HikariDataSource;
import com.easy.query.core.migration.MigrationCommand;
import java.util.ArrayList;
import com.easy.query.core.basic.api.database.CodeFirstCommand;
import com.easy.query.core.basic.jdbc.tx.Transaction;
import com.easy.query.core.basic.jdbc.conn.EasyConnection;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

class HanaCodeFirstIT {
    @Test
    void nativeNumericAndCharacterTypesProduceAnIdempotentSecondPlan() throws SQLException {
        EasyQueryClient client = HanaTestDatabase.client().getEasyQueryClient();
        QueryRuntimeContext context = client.getRuntimeContext();
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("DDL_NATIVE_TYPES")) {
            TableMigrationData data = table(schema.name(), "native_types", column("tiny", "TINYINT"), column("small", "SMALLDECIMAL"),
                    column("floating", "DECIMAL"), column("fixed", "DECIMAL(38,38)"), column("alpha", "ALPHANUM(6)"),
                    column("short", "SHORTTEXT(20)"), column("ascii", "VARCHAR(20)"), column("unicode", "NVARCHAR(20)"));
            client.getDatabaseCodeFirst().createTableCommandByMigrationData(Arrays.asList(data)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            HanaRelationMetadata actual = context.getService(HanaCatalog.class).describe(schema.name(), "native_types").get();
            assertEquals(Arrays.asList("TINYINT", "SMALLDECIMAL", "DECIMAL", "DECIMAL", "ALPHANUM", "SHORTTEXT", "VARCHAR", "NVARCHAR"),
                    actual.columns().stream().map(HanaRelationMetadata.Column::type).collect(Collectors.toList()));
            assertNull(actual.columns().get(2).scale(), "Floating decimal must not acquire a fixed scale");
            assertEquals(Collections.emptyList(), context.getMigrationsSQLGenerator().generateMigrationSQL(new MigrationContext(Arrays.asList(data))));
            try (PreparedStatement indexQuery = schema.connection().prepareStatement("SELECT COUNT(*) FROM SYS.FULLTEXT_INDEXES WHERE SCHEMA_NAME=? AND TABLE_NAME=?")) {
                indexQuery.setString(1, schema.name());
                indexQuery.setString(2, "native_types");
                try (ResultSet indexes = indexQuery.executeQuery()) {
                    assertTrue(indexes.next());
                    assertEquals(1, indexes.getInt(1), "SHORTTEXT's generated fulltext index is retained by an empty second plan");
                }
            }
        }
    }

    @Test
    void createsRelatedTablesRegardlessOfInputOrderAndProducesAnEmptySecondPlan() throws SQLException {
        EasyQueryClient client = HanaTestDatabase.client().getEasyQueryClient();
        QueryRuntimeContext context = client.getRuntimeContext();
        MigrationsSQLGenerator generator = context.getMigrationsSQLGenerator();
        DatabaseCodeFirst codeFirst = client.getDatabaseCodeFirst();
        HanaCatalog catalog = context.getService(HanaCatalog.class);
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("DDL_CREATE"); HanaTestDatabase.TestSchema other = HanaTestDatabase.createSchema("DDL_PARENT")) {
            TableMigrationData parent = generator.parseEntity(HanaMigrationTest.Types.class);
            parent.setSchema(other.name());
            parent.setTableName("order");
            IndexMigrationData index = new IndexMigrationData();
            index.setIndexName("uq_label");
            index.setUnique(true);
            IndexMigrationData.EntityField field = new IndexMigrationData.EntityField();
            field.setColumnName("label");
            field.setAsc(true);
            index.setFields(Arrays.asList(field));
            parent.setIndexes(Arrays.asList(index));

            TableMigrationData child = table(schema.name(), "child", column("parentId", "BIGINT"));
            HanaForeignKeyMigrationData foreign = new HanaForeignKeyMigrationData();
            foreign.setName("fk_parent");
            foreign.setTargetSchema(other.name());
            foreign.setTargetTable("order");
            foreign.setSelfColumn(new String[]{"parentId"});
            foreign.setTargetColumn(new String[]{"id"});
            foreign.setAction("ON DELETE CASCADE");
            child.setForeignKeys(Arrays.asList(foreign));
            AtomicReference<String> preview = new AtomicReference<String>();
            codeFirst.createTableCommandByMigrationData(Arrays.asList(child, parent)).executeWithTransaction(arg -> {
                preview.set(arg.getSQL());
                arg.commit();
            });
            assertTrue(preview.get().indexOf("ADD CONSTRAINT") > preview.get().indexOf("CREATE COLUMN TABLE " + other.qualified("order")));
            HanaRelationMetadata actual = catalog.describe(other.name(), "order").get();
            assertEquals("owner's;中文", actual.relation().comment());
            assertEquals("COLUMN", actual.relation().storageType());
            assertTrue(actual.columns().get(0).generationType().contains("IDENTITY"), actual.columns().get(0).toString());
            assertEquals("O'Reilly;中文", actual.columns().get(3).defaultValue());
            assertEquals(other.name(), catalog.describe(schema.name(), "child").get().foreignKeys().get(0).referencedSchema());
            assertEquals("HXE", context.getService(DatabaseMigrationProvider.class).getDatabaseName());
            assertEquals(Collections.emptyList(), generator.generateMigrationSQL(new MigrationContext(Arrays.asList(child, parent))));
            try (Statement statement = schema.connection().createStatement()) {
                statement.execute("INSERT INTO " + other.qualified("order") + " (\"signed\") VALUES (-128)");
                statement.execute("INSERT INTO " + schema.qualified("child") + " VALUES (1)");
                statement.execute("DELETE FROM " + other.qualified("order") + " WHERE \"id\"=1");
                try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + schema.qualified("child"))) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
        }
    }

    @Test
    void appliesRenameWidenDefaultNullabilityAndCommentChangesWithoutLosingRows() throws SQLException {
        EasyQueryClient client = HanaTestDatabase.client().getEasyQueryClient();
        QueryRuntimeContext context = client.getRuntimeContext();
        DatabaseCodeFirst codeFirst = client.getDatabaseCodeFirst();
        HanaCatalog catalog = context.getService(HanaCatalog.class);
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("DDL_ALTER"); Statement statement = schema.connection().createStatement()) {
            TableMigrationData initial = table(schema.name(), "old.table", column("old.name", "NVARCHAR(20)"));
            codeFirst.createTableCommandByMigrationData(Arrays.asList(initial)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            statement.execute("INSERT INTO " + schema.qualified("old.table") + " VALUES ('keep')");
            ColumnMigrationData label = column("new.name", "NVARCHAR(40)");
            label.setOldColumnName("old.name");
            label.setNotNull(true);
            label.setDefValue("'O''Reilly;中文'");
            label.setComment("updated's;说明");
            TableMigrationData next = table(schema.name(), "new.table", label, column("extra", "INTEGER"));
            next.setOldTableName("old.table");
            next.setComment("table's;说明");
            codeFirst.syncTableCommandByMigrationData(Arrays.asList(next)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            assertTrue(!catalog.find(schema.name(), "old.table").isPresent());
            HanaRelationMetadata actual = catalog.describe(schema.name(), "new.table").get();
            assertEquals(40L, actual.columns().get(0).length());
            assertEquals(HanaRelationMetadata.Nullability.NOT_NULL, actual.columns().get(0).nullability());
            assertEquals("O'Reilly;中文", actual.columns().get(0).defaultValue());
            assertEquals(Collections.emptyList(), context.getMigrationsSQLGenerator().generateMigrationSQL(new MigrationContext(Arrays.asList(next))));
            try (ResultSet rows = statement.executeQuery("SELECT \"new.name\" FROM " + schema.qualified("new.table"))) {
                assertTrue(rows.next());
                assertEquals("keep", rows.getString(1));
            }
            label.setDefValue(null);
            label.setNotNull(false);
            label.setComment(null);
            next.setComment(null);
            codeFirst.syncTableCommandByMigrationData(Arrays.asList(next)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            assertEquals(Collections.emptyList(), context.getMigrationsSQLGenerator().generateMigrationSQL(new MigrationContext(Arrays.asList(next))));
            label.setDbType("NVARCHAR(2)");
            assertThrows(IllegalArgumentException.class, () -> codeFirst.syncTableCommandByMigrationData(Arrays.asList(next)));
        }
    }

    @Test
    void rollsBackWholePlansAndRestoresPoolStateForFailureRollbackAndCommit() throws SQLException {
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("DDL_ROLLBACK"); HikariDataSource pool = HanaTestDatabase.pool(1)) {
            EasyQueryClient client = EasyQueryBootstrapper.defaultBuilderConfiguration().setDefaultDataSource(pool)
                    .useDatabaseConfigure(new HanaDatabaseConfiguration()).build();
            DatabaseCodeFirst codeFirst = client.getDatabaseCodeFirst();
            HanaCatalog catalog = client.getRuntimeContext().getService(HanaCatalog.class);
            TableMigrationData data = table(schema.name(), "rolledBack", column("id", "INTEGER"));
            codeFirst.createTableCommandByMigrationData(Arrays.asList(data)).executeWithTransaction(arg -> assertTrue(arg.getSQL().contains("CREATE COLUMN TABLE")));
            assertTrue(!catalog.find(schema.name(), data.getTableName()).isPresent());
            assertPoolState(pool);
            try (Statement statement = schema.connection().createStatement()) {
                statement.execute("CREATE COLUMN TABLE " + schema.qualified("callback") + " (\"id\" INTEGER)");
            }
            codeFirst.createTableCommandByMigrationData(Arrays.asList(data)).executeWithTransaction(arg -> {
                client.sqlExecute("INSERT INTO " + schema.qualified("callback") + " VALUES (1)");
                arg.rollback();
            });
            assertEquals(0L, client.sqlQuery("SELECT COUNT(*) FROM " + schema.qualified("callback"), Long.class).get(0));
            assertTrue(!catalog.find(schema.name(), data.getTableName()).isPresent());
            assertThrows(IllegalArgumentException.class, () -> codeFirst.createTableCommandByMigrationData(Arrays.asList(data))
                    .executeWithTransaction(arg -> {
                        throw new IllegalArgumentException("preview failed");
                    }));
            assertPoolState(pool);
            List<MigrationCommand> commands = client.getRuntimeContext().getMigrationsSQLGenerator().generateCreateTableMigrationSQL(new MigrationContext(Arrays.asList(data)));
            ArrayList<MigrationCommand> failurePlan = new java.util.ArrayList<>(commands);
            failurePlan.add(new DefaultMigrationCommand("CREATE COLUMN TABLE " + schema.qualified("bad") + " (\"a\" INVALID_TYPE)"));
            assertThrows(RuntimeException.class, () -> codeFirst.createCodeFirstCommand(failurePlan).executeWithTransaction(CodeFirstCommandTxArg::commit));
            assertTrue(!catalog.find(schema.name(), data.getTableName()).isPresent());
            assertPoolState(pool);
            codeFirst.createTableCommandByMigrationData(Arrays.asList(data)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            assertTrue(catalog.find(schema.name(), data.getTableName()).isPresent());
            assertPoolState(pool);
        }
    }

    @Test
    void nonAdminCanMigrateItsGrantedSchemaAndPermissionErrorsDoNotBecomeMissingTables() throws SQLException {
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("DDL_APP"); HanaTestDatabase.TestSchema denied = HanaTestDatabase.createSchema("DDL_DENIED");
             HanaTestDatabase.TestUser user = HanaTestDatabase.createUser("DDL_USER"); Statement statement = schema.connection().createStatement()) {
            statement.execute("GRANT CREATE ANY ON SCHEMA \"" + schema.name() + "\" TO " + user.name());
            statement.execute("CREATE COLUMN TABLE " + denied.qualified("private_table") + " (\"id\" INTEGER)");
            EasyQueryClient client = EasyQueryBootstrapper.defaultBuilderConfiguration().setDefaultDataSource(user.dataSource())
                    .useDatabaseConfigure(new HanaDatabaseConfiguration()).build();
            TableMigrationData data = table(schema.name(), "app_table", column("id", "INTEGER"));
            DatabaseCodeFirst codeFirst = client.getDatabaseCodeFirst();
            codeFirst.syncTableCommandByMigrationData(Arrays.asList(data)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            assertTrue(codeFirst.tableExistsByMigrationData(data));
            assertTrue(client.getRuntimeContext().getMigrationsSQLGenerator().generateMigrationSQL(new MigrationContext(Arrays.asList(data))).isEmpty());
            TableMigrationData unavailable = table(denied.name(), "private_table", column("id", "INTEGER"));
            assertThrows(RuntimeException.class, () -> codeFirst.syncTableCommandByMigrationData(Arrays.asList(unavailable)));
            codeFirst.dropTableCommandByMigrationData(Arrays.asList(data)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            assertFalse(codeFirst.tableExistsByMigrationData(data));
        }
    }

    @Test
    void preservesQuotedColumnsAndRejectsNativeAlterLimitationBeforeChangingAnything() throws SQLException {
        EasyQueryClient client = HanaTestDatabase.client().getEasyQueryClient();
        DatabaseCodeFirst codeFirst = client.getDatabaseCodeFirst();
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("DDL_QUOTED")) {
            ColumnMigrationData column = column("odd\"name", "NVARCHAR(20)");
            TableMigrationData data = table(schema.name(), "quoted", column);
            codeFirst.createTableCommandByMigrationData(Arrays.asList(data)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            assertTrue(client.getRuntimeContext().getMigrationsSQLGenerator().generateMigrationSQL(new MigrationContext(Arrays.asList(data))).isEmpty());
            column.setDbType("NVARCHAR(40)");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> codeFirst.syncTableCommandByMigrationData(Arrays.asList(data)));
            assertTrue(failure.getMessage().contains("double quote"));
            assertEquals(20L, client.getRuntimeContext().getService(HanaCatalog.class).describe(schema.name(), "quoted").get().columns().get(0).length());
        }
    }

    @Test
    void ambientDdlModeIsRequiredAndNeverCommitsExistingDml() throws SQLException {
        EasyQueryClient client = HanaTestDatabase.client().getEasyQueryClient();
        QueryRuntimeContext context = client.getRuntimeContext();
        DatabaseCodeFirst codeFirst = client.getDatabaseCodeFirst();
        try (HanaTestDatabase.TestSchema schema = HanaTestDatabase.createSchema("DDL_AMBIENT"); Statement statement = schema.connection().createStatement()) {
            statement.execute("CREATE COLUMN TABLE " + schema.qualified("business") + " (\"id\" INTEGER)");
            TableMigrationData data = table(schema.name(), "migration", column("id", "INTEGER"));
            CodeFirstCommand command = codeFirst.createTableCommandByMigrationData(Arrays.asList(data));
            try (Transaction transaction = client.beginTransaction()) {
                client.sqlExecute("INSERT INTO " + schema.qualified("business") + " VALUES (1)");
                assertThrows(RuntimeException.class, () -> command.executeWithEnvTransaction(arg -> fail("Unsafe ambient DDL must be rejected before callback")));
                transaction.rollback();
            }
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + schema.qualified("business"))) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            try (Transaction transaction = client.beginTransaction()) {
                EasyConnection easy = context.getConnectionManager().getEasyConnections(1, context.getDataSourceManager().getDefaultDataSourceName(), ConnectionStrategyEnum.ShareConnection).get(0);
                try (Statement nativeStatement = easy.getConnection().createStatement()) {
                    nativeStatement.execute("SET TRANSACTION AUTOCOMMIT DDL OFF");
                }
                command.executeWithEnvTransaction(arg -> assertTrue(arg.getSQL().contains("CREATE COLUMN TABLE")));
                transaction.rollback();
            }
            assertTrue(!context.getService(HanaCatalog.class).find(schema.name(), "migration").isPresent());
        }
    }

    private static void assertPoolState(com.zaxxer.hikari.HikariDataSource pool) throws SQLException {
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
        try (Connection connection = pool.getConnection()) {
            assertTrue(connection.getAutoCommit());
            assertTrue(connection.unwrap(ConnectionSapDB.class).isDDLAutoCommit());
        }
    }

    private static TableMigrationData table(String schema, String name, ColumnMigrationData... columns) {
        TableMigrationData table = new TableMigrationData();
        table.setSchema(schema);
        table.setTableName(name);
        table.setColumns(Arrays.asList(columns));
        return table;
    }

    private static ColumnMigrationData column(String name, String type) {
        ColumnMigrationData column = new ColumnMigrationData();
        column.setName(name);
        column.setDbType(type);
        return column;
    }
}
