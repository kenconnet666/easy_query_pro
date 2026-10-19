package org.dpdns.zerodep.easyquery.pro.test;

import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.annotation.ForeignKey;
import com.easy.query.core.annotation.Navigate;
import com.easy.query.core.enums.RelationTypeEnum;
import com.easy.query.core.migration.MigrationContext;
import org.dpdns.zerodep.easyquery.pro.hana.migration.HanaForeignKeyMigrationData;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaDatabaseConfiguration;
import org.dpdns.zerodep.easyquery.pro.test.entity.SequenceProduct;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.io.File;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class HanaMigrationTest {
    @Test
    void parsesNativeTypesAndRendersDdlWithoutOpeningJdbc() {
        var context = TestClients.offline(new HanaDatabaseConfiguration()).getEasyQueryClient().getRuntimeContext();
        var generator = context.getMigrationsSQLGenerator();
        var table = generator.parseEntity(Types.class);
        var commands = generator.generateCreateTableMigrationSQL(new MigrationContext(List.of(table)));
        String sql = commands.get(0).toSQL();
        assertTrue(sql.contains("CREATE COLUMN TABLE \"ddl_types\""), sql);
        assertTrue(sql.contains("\"signed\" SMALLINT"), sql);
        assertTrue(sql.contains("\"enabled\" BOOLEAN DEFAULT FALSE NOT NULL"), sql);
        assertTrue(sql.contains("\"label\" NVARCHAR(31)"), sql);
        assertTrue(sql.contains("\"amount\" DECIMAL(19,0)"), sql);
        assertTrue(sql.contains("\"created\" TIMESTAMP"), sql);
        assertTrue(sql.contains("IS 'owner''s;中文'"), sql);
        assertFalse(sql.contains("AUTO_INCREMENT"));
    }

    @Test
    void sequenceGeneratedColumnsDoNotBecomeIdentityColumns() {
        var context = TestClients.offline(new HanaDatabaseConfiguration()).getEasyQueryClient().getRuntimeContext();
        context.getQueryConfiguration().applyGeneratedKeySQLColumnGenerator(new SequenceProduct.ProductSequence("sequence"));
        var table = context.getMigrationsSQLGenerator().parseEntity(SequenceProduct.class);
        assertFalse(table.getColumns().get(0).isGeneratedKey());
        assertTrue(table.getColumns().get(0).isPrimary());
    }

    @Test
    void rejectsTemporalPrecisionAnnotationsInsteadOfEmittingInvalidDdl() {
        var generator = TestClients.offline(new HanaDatabaseConfiguration()).getEasyQueryClient().getRuntimeContext().getMigrationsSQLGenerator();
        assertThrows(IllegalArgumentException.class, () -> generator.parseEntity(InvalidTimestamp.class));
    }

    @Test
    void retainsForeignKeyActionsAndTargetSchemasFromEntityAnnotations() {
        var generator = TestClients.offline(new HanaDatabaseConfiguration()).getEasyQueryClient().getRuntimeContext().getMigrationsSQLGenerator();
        var table = generator.parseEntity(Child.class);
        var key = assertInstanceOf(HanaForeignKeyMigrationData.class, table.getForeignKeys().get(0));
        assertEquals("parent.schema", key.getTargetSchema());
        assertEquals("ON DELETE CASCADE", key.getAction());
        String sql = generator.generateCreateTableMigrationSQL(new MigrationContext(List.of(table))).get(1).toSQL();
        assertTrue(sql.contains("REFERENCES \"parent.schema\".\"parent.table\" (\"id\") ON DELETE CASCADE"), sql);
    }

    @Test
    void optionalSapDriverIsNotRequiredForOfflineConfigurationOrDdlRendering() throws Exception {
        var paths = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .filter(path -> !new File(path).getName().startsWith("ngdbc-")).toList();
        var urls = new java.net.URL[paths.size()];
        for (int i = 0; i < paths.size(); i++) urls[i] = new File(paths.get(i)).toURI().toURL();
        try (var isolated = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName("com.sap.db.jdbc.Driver", false, isolated));
            isolated.loadClass(DriverlessProbe.class.getName()).getMethod("verify").invoke(null);
        }
    }

    public static final class DriverlessProbe {
        public static void verify() {
            var context = TestClients.offline(new HanaDatabaseConfiguration()).getEasyQueryClient().getRuntimeContext();
            var table = context.getMigrationsSQLGenerator().parseEntity(Types.class);
            var commands = context.getMigrationsSQLGenerator().generateCreateTableMigrationSQL(new MigrationContext(List.of(table)));
            context.getDatabaseCodeFirst().createCodeFirstCommand(commands);
            if (!commands.get(0).toSQL().startsWith("CREATE COLUMN TABLE"))
                throw new AssertionError("Missing offline DDL");
        }
    }

    @Table(value = "ddl_types", comment = "owner's;中文")
    public static class Types {
        @Column(primaryKey = true, generatedKey = true)
        private Long id;
        private Byte signed;
        private boolean enabled;
        @Column(length = 31, dbDefault = "'O''Reilly;中文'", comment = "label's;中文")
        private String label;
        @Column(length = 19, scale = 0)
        private BigDecimal amount;
        private LocalDateTime created;

        public Long getId() {
            return id;
        }

        public void setId(Long value) {
            id = value;
        }

        public Byte getSigned() {
            return signed;
        }

        public void setSigned(Byte value) {
            signed = value;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean value) {
            enabled = value;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String value) {
            label = value;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal value) {
            amount = value;
        }

        public LocalDateTime getCreated() {
            return created;
        }

        public void setCreated(LocalDateTime value) {
            created = value;
        }
    }

    @Table("invalid_timestamp")
    public static class InvalidTimestamp {
        @Column(length = 3)
        private LocalDateTime timestamp;

        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(LocalDateTime value) {
            timestamp = value;
        }
    }

    @Table(value = "parent.table", schema = "parent.schema")
    public static class Parent {
        @Column(primaryKey = true)
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long value) {
            id = value;
        }
    }

    @Table(value = "child", schema = "child.schema")
    public static class Child {
        private Long parentId;
        @ForeignKey(action = "ON DELETE CASCADE")
        @Navigate(value = RelationTypeEnum.ManyToOne, selfProperty = "parentId")
        private Parent parent;

        public Long getParentId() {
            return parentId;
        }

        public void setParentId(Long value) {
            parentId = value;
        }

        public Parent getParent() {
            return parent;
        }

        public void setParent(Parent value) {
            parent = value;
        }
    }
}
