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

import static org.junit.jupiter.api.Assertions.*;

class HanaCodeFirstIT {
    @Test
    void nativeNumericAndCharacterTypesProduceAnIdempotentSecondPlan() throws SQLException {
        var client = HanaTestDatabase.client().getEasyQueryClient();
        var context = client.getRuntimeContext();
        try (var schema = HanaTestDatabase.createSchema("DDL_NATIVE_TYPES")) {
            var data = table(schema.name(), "native_types", column("tiny", "TINYINT"), column("small", "SMALLDECIMAL"),
                    column("floating", "DECIMAL"), column("fixed", "DECIMAL(38,38)"), column("alpha", "ALPHANUM(6)"),
                    column("short", "SHORTTEXT(20)"), column("ascii", "VARCHAR(20)"), column("unicode", "NVARCHAR(20)"));
            client.getDatabaseCodeFirst().createTableCommandByMigrationData(List.of(data)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            var actual = context.getService(HanaCatalog.class).describe(schema.name(), "native_types").orElseThrow();
            assertEquals(List.of("TINYINT", "SMALLDECIMAL", "DECIMAL", "DECIMAL", "ALPHANUM", "SHORTTEXT", "VARCHAR", "NVARCHAR"),
                    actual.columns().stream().map(HanaRelationMetadata.Column::type).toList());
            assertNull(actual.columns().get(2).scale(), "Floating decimal must not acquire a fixed scale");
            assertEquals(List.of(), context.getMigrationsSQLGenerator().generateMigrationSQL(new MigrationContext(List.of(data))));
            try (var indexQuery = schema.connection().prepareStatement("SELECT COUNT(*) FROM SYS.FULLTEXT_INDEXES WHERE SCHEMA_NAME=? AND TABLE_NAME=?")) {
                indexQuery.setString(1, schema.name());
                indexQuery.setString(2, "native_types");
                try (var indexes = indexQuery.executeQuery()) {
                    assertTrue(indexes.next());
                    assertEquals(1, indexes.getInt(1), "SHORTTEXT's generated fulltext index is retained by an empty second plan");
                }
            }
        }
    }

    @Test
    void createsRelatedTablesRegardlessOfInputOrderAndProducesAnEmptySecondPlan() throws SQLException {
        var client = HanaTestDatabase.client().getEasyQueryClient();
        var context = client.getRuntimeContext();
        var generator = context.getMigrationsSQLGenerator();
        var codeFirst = client.getDatabaseCodeFirst();
        var catalog = context.getService(HanaCatalog.class);
        try (var schema = HanaTestDatabase.createSchema("DDL_CREATE"); var other = HanaTestDatabase.createSchema("DDL_PARENT")) {
            var parent = generator.parseEntity(HanaMigrationTest.Types.class);
            parent.setSchema(other.name());
            parent.setTableName("order");
            var index = new IndexMigrationData();
            index.setIndexName("uq_label");
            index.setUnique(true);
            var field = new IndexMigrationData.EntityField();
            field.setColumnName("label");
            field.setAsc(true);
            index.setFields(List.of(field));
            parent.setIndexes(List.of(index));

            var child = table(schema.name(), "child", column("parentId", "BIGINT"));
            var foreign = new HanaForeignKeyMigrationData();
            foreign.setName("fk_parent");
            foreign.setTargetSchema(other.name());
            foreign.setTargetTable("order");
            foreign.setSelfColumn(new String[]{"parentId"});
            foreign.setTargetColumn(new String[]{"id"});
            foreign.setAction("ON DELETE CASCADE");
            child.setForeignKeys(List.of(foreign));
            var preview = new AtomicReference<String>();
            codeFirst.createTableCommandByMigrationData(List.of(child, parent)).executeWithTransaction(arg -> {
                preview.set(arg.getSQL());
                arg.commit();
            });
            assertTrue(preview.get().indexOf("ADD CONSTRAINT") > preview.get().indexOf("CREATE COLUMN TABLE " + other.qualified("order")));
            var actual = catalog.describe(other.name(), "order").orElseThrow();
            assertEquals("owner's;中文", actual.relation().comment());
            assertEquals("COLUMN", actual.relation().storageType());
            assertTrue(actual.columns().get(0).generationType().contains("IDENTITY"), actual.columns().get(0).toString());
            assertEquals("O'Reilly;中文", actual.columns().get(3).defaultValue());
            assertEquals(other.name(), catalog.describe(schema.name(), "child").orElseThrow().foreignKeys().get(0).referencedSchema());
            assertEquals("HXE", context.getService(DatabaseMigrationProvider.class).getDatabaseName());
            assertEquals(List.of(), generator.generateMigrationSQL(new MigrationContext(List.of(child, parent))));
            try (var statement = schema.connection().createStatement()) {
                statement.execute("INSERT INTO " + other.qualified("order") + " (\"signed\") VALUES (-128)");
                statement.execute("INSERT INTO " + schema.qualified("child") + " VALUES (1)");
                statement.execute("DELETE FROM " + other.qualified("order") + " WHERE \"id\"=1");
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + schema.qualified("child"))) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
            }
        }
    }

    @Test
    void appliesRenameWidenDefaultNullabilityAndCommentChangesWithoutLosingRows() throws SQLException {
        var client = HanaTestDatabase.client().getEasyQueryClient();
        var context = client.getRuntimeContext();
        var codeFirst = client.getDatabaseCodeFirst();
        var catalog = context.getService(HanaCatalog.class);
        try (var schema = HanaTestDatabase.createSchema("DDL_ALTER"); var statement = schema.connection().createStatement()) {
            var initial = table(schema.name(), "old.table", column("old.name", "NVARCHAR(20)"));
            codeFirst.createTableCommandByMigrationData(List.of(initial)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            statement.execute("INSERT INTO " + schema.qualified("old.table") + " VALUES ('keep')");
            var label = column("new.name", "NVARCHAR(40)");
            label.setOldColumnName("old.name");
            label.setNotNull(true);
            label.setDefValue("'O''Reilly;中文'");
            label.setComment("updated's;说明");
            var next = table(schema.name(), "new.table", label, column("extra", "INTEGER"));
            next.setOldTableName("old.table");
            next.setComment("table's;说明");
            codeFirst.syncTableCommandByMigrationData(List.of(next)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            assertTrue(catalog.find(schema.name(), "old.table").isEmpty());
            var actual = catalog.describe(schema.name(), "new.table").orElseThrow();
            assertEquals(40L, actual.columns().get(0).length());
            assertEquals(HanaRelationMetadata.Nullability.NOT_NULL, actual.columns().get(0).nullability());
            assertEquals("O'Reilly;中文", actual.columns().get(0).defaultValue());
            assertEquals(List.of(), context.getMigrationsSQLGenerator().generateMigrationSQL(new MigrationContext(List.of(next))));
            try (var rows = statement.executeQuery("SELECT \"new.name\" FROM " + schema.qualified("new.table"))) {
                assertTrue(rows.next());
                assertEquals("keep", rows.getString(1));
            }
            label.setDefValue(null);
            label.setNotNull(false);
            label.setComment(null);
            next.setComment(null);
            codeFirst.syncTableCommandByMigrationData(List.of(next)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            assertEquals(List.of(), context.getMigrationsSQLGenerator().generateMigrationSQL(new MigrationContext(List.of(next))));
            label.setDbType("NVARCHAR(2)");
            assertThrows(IllegalArgumentException.class, () -> codeFirst.syncTableCommandByMigrationData(List.of(next)));
        }
    }

    @Test
    void rollsBackWholePlansAndRestoresPoolStateForFailureRollbackAndCommit() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("DDL_ROLLBACK"); var pool = HanaTestDatabase.pool(1)) {
            var client = EasyQueryBootstrapper.defaultBuilderConfiguration().setDefaultDataSource(pool)
                    .useDatabaseConfigure(new HanaDatabaseConfiguration()).build();
            var codeFirst = client.getDatabaseCodeFirst();
            var catalog = client.getRuntimeContext().getService(HanaCatalog.class);
            var data = table(schema.name(), "rolledBack", column("id", "INTEGER"));
            codeFirst.createTableCommandByMigrationData(List.of(data)).executeWithTransaction(arg -> assertTrue(arg.getSQL().contains("CREATE COLUMN TABLE")));
            assertTrue(catalog.find(schema.name(), data.getTableName()).isEmpty());
            assertPoolState(pool);
            try (var statement = schema.connection().createStatement()) {
                statement.execute("CREATE COLUMN TABLE " + schema.qualified("callback") + " (\"id\" INTEGER)");
            }
            codeFirst.createTableCommandByMigrationData(List.of(data)).executeWithTransaction(arg -> {
                client.sqlExecute("INSERT INTO " + schema.qualified("callback") + " VALUES (1)");
                arg.rollback();
            });
            assertEquals(0L, client.sqlQuery("SELECT COUNT(*) FROM " + schema.qualified("callback"), Long.class).get(0));
            assertTrue(catalog.find(schema.name(), data.getTableName()).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> codeFirst.createTableCommandByMigrationData(List.of(data))
                    .executeWithTransaction(arg -> {
                        throw new IllegalArgumentException("preview failed");
                    }));
            assertPoolState(pool);
            var commands = client.getRuntimeContext().getMigrationsSQLGenerator().generateCreateTableMigrationSQL(new MigrationContext(List.of(data)));
            var failurePlan = new java.util.ArrayList<>(commands);
            failurePlan.add(new DefaultMigrationCommand("CREATE COLUMN TABLE " + schema.qualified("bad") + " (\"a\" INVALID_TYPE)"));
            assertThrows(RuntimeException.class, () -> codeFirst.createCodeFirstCommand(failurePlan).executeWithTransaction(CodeFirstCommandTxArg::commit));
            assertTrue(catalog.find(schema.name(), data.getTableName()).isEmpty());
            assertPoolState(pool);
            codeFirst.createTableCommandByMigrationData(List.of(data)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            assertTrue(catalog.find(schema.name(), data.getTableName()).isPresent());
            assertPoolState(pool);
        }
    }

    @Test
    void nonAdminCanMigrateItsGrantedSchemaAndPermissionErrorsDoNotBecomeMissingTables() throws SQLException {
        try (var schema = HanaTestDatabase.createSchema("DDL_APP"); var denied = HanaTestDatabase.createSchema("DDL_DENIED");
             var user = HanaTestDatabase.createUser("DDL_USER"); var statement = schema.connection().createStatement()) {
            statement.execute("GRANT CREATE ANY ON SCHEMA \"" + schema.name() + "\" TO " + user.name());
            statement.execute("CREATE COLUMN TABLE " + denied.qualified("private_table") + " (\"id\" INTEGER)");
            var client = EasyQueryBootstrapper.defaultBuilderConfiguration().setDefaultDataSource(user.dataSource())
                    .useDatabaseConfigure(new HanaDatabaseConfiguration()).build();
            var data = table(schema.name(), "app_table", column("id", "INTEGER"));
            var codeFirst = client.getDatabaseCodeFirst();
            codeFirst.syncTableCommandByMigrationData(List.of(data)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            assertTrue(codeFirst.tableExistsByMigrationData(data));
            assertTrue(client.getRuntimeContext().getMigrationsSQLGenerator().generateMigrationSQL(new MigrationContext(List.of(data))).isEmpty());
            var unavailable = table(denied.name(), "private_table", column("id", "INTEGER"));
            assertThrows(RuntimeException.class, () -> codeFirst.syncTableCommandByMigrationData(List.of(unavailable)));
            codeFirst.dropTableCommandByMigrationData(List.of(data)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            assertFalse(codeFirst.tableExistsByMigrationData(data));
        }
    }

    @Test
    void preservesQuotedColumnsAndRejectsNativeAlterLimitationBeforeChangingAnything() throws SQLException {
        var client = HanaTestDatabase.client().getEasyQueryClient();
        var codeFirst = client.getDatabaseCodeFirst();
        try (var schema = HanaTestDatabase.createSchema("DDL_QUOTED")) {
            var column = column("odd\"name", "NVARCHAR(20)");
            var data = table(schema.name(), "quoted", column);
            codeFirst.createTableCommandByMigrationData(List.of(data)).executeWithTransaction(CodeFirstCommandTxArg::commit);
            assertTrue(client.getRuntimeContext().getMigrationsSQLGenerator().generateMigrationSQL(new MigrationContext(List.of(data))).isEmpty());
            column.setDbType("NVARCHAR(40)");
            var failure = assertThrows(IllegalArgumentException.class, () -> codeFirst.syncTableCommandByMigrationData(List.of(data)));
            assertTrue(failure.getMessage().contains("double quote"));
            assertEquals(20L, client.getRuntimeContext().getService(HanaCatalog.class).describe(schema.name(), "quoted").orElseThrow().columns().get(0).length());
        }
    }

    @Test
    void ambientDdlModeIsRequiredAndNeverCommitsExistingDml() throws SQLException {
        var client = HanaTestDatabase.client().getEasyQueryClient();
        var context = client.getRuntimeContext();
        var codeFirst = client.getDatabaseCodeFirst();
        try (var schema = HanaTestDatabase.createSchema("DDL_AMBIENT"); var statement = schema.connection().createStatement()) {
            statement.execute("CREATE COLUMN TABLE " + schema.qualified("business") + " (\"id\" INTEGER)");
            var data = table(schema.name(), "migration", column("id", "INTEGER"));
            var command = codeFirst.createTableCommandByMigrationData(List.of(data));
            try (var transaction = client.beginTransaction()) {
                client.sqlExecute("INSERT INTO " + schema.qualified("business") + " VALUES (1)");
                assertThrows(RuntimeException.class, () -> command.executeWithEnvTransaction(arg -> fail("Unsafe ambient DDL must be rejected before callback")));
                transaction.rollback();
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + schema.qualified("business"))) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            try (var transaction = client.beginTransaction()) {
                var easy = context.getConnectionManager().getEasyConnections(1, context.getDataSourceManager().getDefaultDataSourceName(), ConnectionStrategyEnum.ShareConnection).get(0);
                try (var nativeStatement = easy.getConnection().createStatement()) {
                    nativeStatement.execute("SET TRANSACTION AUTOCOMMIT DDL OFF");
                }
                command.executeWithEnvTransaction(arg -> assertTrue(arg.getSQL().contains("CREATE COLUMN TABLE")));
                transaction.rollback();
            }
            assertTrue(context.getService(HanaCatalog.class).find(schema.name(), "migration").isEmpty());
        }
    }

    private static void assertPoolState(com.zaxxer.hikari.HikariDataSource pool) throws SQLException {
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
        try (var connection = pool.getConnection()) {
            assertTrue(connection.getAutoCommit());
            assertTrue(connection.unwrap(ConnectionSapDB.class).isDDLAutoCommit());
        }
    }

    private static TableMigrationData table(String schema, String name, ColumnMigrationData... columns) {
        var table = new TableMigrationData();
        table.setSchema(schema);
        table.setTableName(name);
        table.setColumns(List.of(columns));
        return table;
    }

    private static ColumnMigrationData column(String name, String type) {
        var column = new ColumnMigrationData();
        column.setName(name);
        column.setDbType(type);
        return column;
    }
}
