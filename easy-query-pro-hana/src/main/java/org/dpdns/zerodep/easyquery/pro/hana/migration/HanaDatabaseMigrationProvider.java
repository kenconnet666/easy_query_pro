package org.dpdns.zerodep.easyquery.pro.hana.migration;

import com.easy.query.core.basic.api.database.Credentials;
import com.easy.query.core.basic.api.database.TableInfo;
import com.easy.query.core.configuration.dialect.SQLKeyword;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.exception.EasyQuerySQLCommandException;
import com.easy.query.core.migration.AbstractDatabaseMigrationProvider;
import com.easy.query.core.migration.MigrationCommand;
import com.easy.query.core.migration.MigrationEntityParser;
import com.easy.query.core.migration.data.ColumnMigrationData;
import com.easy.query.core.migration.data.ForeignKeyMigrationData;
import com.easy.query.core.migration.data.IndexMigrationData;
import com.easy.query.core.migration.data.TableMigrationData;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaCatalog;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaRelationMetadata;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaRelationMetadata.Kind;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.dpdns.zerodep.easyquery.pro.hana.migration.HanaMigrationSql.*;

/**
 * HANA 的目录读取和 DDL 翻译，仍使用上游的迁移数据与 parser 扩展点。
 */
public final class HanaDatabaseMigrationProvider extends AbstractDatabaseMigrationProvider {
    private final HanaCatalog catalog;

    public HanaDatabaseMigrationProvider(DataSource source, SQLKeyword keyword, MigrationEntityParser parser, HanaCatalog catalog) {
        super(source, keyword, parser);
        this.catalog = catalog;
    }

    @Override
    public String getDatabaseName() {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT CURRENT_DATABASE() FROM DUMMY")) {
            rows.next();
            return rows.getString(1);
        } catch (SQLException failure) {
            throw new EasyQuerySQLCommandException("Unable to resolve HANA tenant database", failure);
        }
    }

    @Override
    public void createDatabaseIfNotExists(Function<DataSource, Credentials> credentials) {
        throw new UnsupportedOperationException("HANA tenant provisioning is an administrative operation; connect to an existing tenant and schema");
    }

    @Override
    public String databaseExistSQL(String database) {
        throw new UnsupportedOperationException("HANA tenant provisioning is not part of Code First");
    }

    @Override
    public String createDatabaseSQL(String database) {
        throw new UnsupportedOperationException("HANA tenant provisioning is not part of Code First");
    }

    @Override
    protected String tableQuerySql() {
        throw new UnsupportedOperationException("HANA table discovery uses HanaCatalog");
    }

    @Override
    public List<TableInfo> getTableInfos() {
        return catalog.relations(null).stream().filter(relation -> relation.kind() == Kind.TABLE)
                .map(relation -> new TableInfo(relation.schema(), relation.name())).toList();
    }

    @Override
    public boolean tableExists(String schema, String name) {
        if (name == null || name.isBlank()) return false;
        var relation = catalog.find(raw(schema), raw(name));
        relation.ifPresent(found -> requireTable(found.kind(), qualified(schema, name)));
        return relation.isPresent();
    }

    @Override
    public MigrationCommand createTable(TableMigrationData table) {
        if (table.getColumns().isEmpty())
            throw new IllegalArgumentException("HANA table must have columns: " + table.getTableName());
        var names = new HashSet<String>();
        long identities = table.getColumns().stream().filter(ColumnMigrationData::isGeneratedKey).count();
        if (identities > 1)
            throw new IllegalArgumentException("HANA table can have only one identity column: " + table.getTableName());
        var definitions = new ArrayList<String>();
        for (var column : table.getColumns()) {
            if (!names.add(raw(column.getName())))
                throw new IllegalArgumentException("Duplicate HANA column: " + column.getName());
            definitions.add(column(column, true));
        }
        var primary = table.getColumns().stream().filter(ColumnMigrationData::isPrimary).map(ColumnMigrationData::getName).toArray(String[]::new);
        if (primary.length > 0) definitions.add("PRIMARY KEY (" + columns(primary) + ")");
        var statements = new ArrayList<String>();
        statements.add("CREATE COLUMN TABLE " + table(table) + " (" + String.join(", ", definitions) + ")");
        if (table.getComment() != null) statements.add(tableComment(table));
        for (var column : table.getColumns())
            if (column.getComment() != null) statements.add(columnComment(table, column));
        return new HanaMigrationCommand(statements);
    }

    @Override
    public MigrationCommand renameTable(TableMigrationData table) {
        return new HanaMigrationCommand("RENAME TABLE " + qualified(table.getSchema(), table.getOldTableName()) + " TO " + table(table));
    }

    @Override
    public MigrationCommand dropTable(TableMigrationData table) {
        return new HanaMigrationCommand("DROP TABLE " + table(table) + " RESTRICT");
    }

    @Override
    protected MigrationCommand renameColumn(TableMigrationData table, String oldName, ColumnMigrationData column) {
        return new HanaMigrationCommand("RENAME COLUMN " + table(table) + "." + name(oldName) + " TO " + name(column.getName()));
    }

    @Override
    protected MigrationCommand addColumn(TableMigrationData table, ColumnMigrationData column) {
        var sql = new ArrayList<String>();
        sql.add("ALTER TABLE " + table(table) + " ADD (" + column(column, true) + ")");
        if (column.getComment() != null) sql.add(columnComment(table, column));
        return new HanaMigrationCommand(sql);
    }

    @Override
    protected MigrationCommand createIndex(TableMigrationData table, IndexMigrationData index) {
        if (index.getFields().isEmpty())
            throw new IllegalArgumentException("HANA index must have columns: " + index.getIndexName());
        if (index.getComment() != null && !index.getComment().isBlank()) {
            throw new UnsupportedOperationException("HANA index comments have no equivalent catalog property: " + index.getIndexName());
        }
        String fields = index.getFields().stream().map(field -> name(field.getColumnName()) + (field.isAsc() ? " ASC" : " DESC"))
                .collect(Collectors.joining(", "));
        return new HanaMigrationCommand("CREATE " + (index.isUnique() ? "UNIQUE " : "") + "INDEX "
                + qualified(table.getSchema(), index.getIndexName()) + " ON " + table(table) + " (" + fields + ")");
    }

    @Override
    protected @NotNull MigrationCommand createTableForeignKey(TableMigrationData table, ForeignKeyMigrationData key) {
        if (key.getSelfColumn().length != key.getTargetColumn().length) {
            throw new IllegalArgumentException("HANA foreign key column counts differ: " + key.getName());
        }
        return new HanaMigrationCommand("ALTER TABLE " + table(table) + " ADD CONSTRAINT " + name(key.getName())
                + " FOREIGN KEY (" + columns(key.getSelfColumn()) + ") REFERENCES "
                + qualified(targetSchema(table, key), key.getTargetTable()) + " (" + columns(key.getTargetColumn()) + ")" + actionSql(key.getAction()));
    }

    @Override
    public List<MigrationCommand> syncTable(TableMigrationData table, boolean oldTable) {
        var existing = existing(table, oldTable);
        var result = new ArrayList<MigrationCommand>();
        var desiredPrimary = table.getColumns().stream().filter(ColumnMigrationData::isPrimary).map(column -> raw(column.getName())).toList();
        var renames = renames(table, existing);
        var currentPrimary = existing.primaryKeyColumns().stream().map(column -> renames.getOrDefault(column, column)).toList();
        if (!desiredPrimary.equals(currentPrimary)) throw manual(table, "primary key definition changes");
        for (var column : table.getColumns()) {
            var current = existing.columns().stream().filter(item -> item.name().equals(raw(column.getName()))).findFirst();
            if (current.isEmpty() && raw(column.getOldColumnName()) != null) {
                current = existing.columns().stream().filter(item -> item.name().equals(raw(column.getOldColumnName()))).findFirst();
                if (current.isPresent()) result.add(renameColumn(table, column.getOldColumnName(), column));
            }
            if (current.isEmpty()) result.add(addColumn(table, column));
            else syncColumn(table, column, current.get(), result);
        }
        if (!Objects.equals(emptyComment(table.getComment()), emptyComment(existing.relation().comment()))) {
            result.add(new HanaMigrationCommand(tableComment(table)));
        }
        return result;
    }

    private void syncColumn(TableMigrationData table, ColumnMigrationData desired, HanaRelationMetadata.Column current, List<MigrationCommand> result) {
        boolean identity = current.generationType() != null && current.generationType().contains("IDENTITY");
        if (identity != desired.isGeneratedKey() || current.generationExpression() != null && !current.generationExpression().isBlank()) {
            throw manual(table, "generated column " + desired.getName());
        }
        var desiredType = HanaColumnType.parse(desired.getDbType());
        var currentType = HanaColumnType.from(current);
        if (!desiredType.widens(currentType))
            throw manual(table, "type narrowing/conversion for " + desired.getName() + " from " + currentType.sql() + " to " + desiredType.sql());
        boolean notNull = desired.isNotNull() || desired.isPrimary();
        if (current.nullability() == HanaRelationMetadata.Nullability.UNKNOWN)
            throw manual(table, "unknown nullability for " + desired.getName());
        boolean changed = !desiredType.equals(currentType) || notNull != (current.nullability() == HanaRelationMetadata.Nullability.NOT_NULL)
                || !identity && !defaultMatches(desiredType, desired.getDefValue(), current.defaultValue());
        if (changed) {
            // revision 88 在 ALTER 的内部重建 SQL 中未正确转义列名里的双引号，原生 hdbsql 可复现。
            if (raw(desired.getName()).contains("\""))
                throw manual(table, "ALTER of a column containing a double quote (HANA revision 88 limitation)");
            result.add(new HanaMigrationCommand("ALTER TABLE " + table(table) + " ALTER (" + column(desired, false) + ")"));
        }
        if (!Objects.equals(emptyComment(desired.getComment()), emptyComment(current.comment()))) {
            result.add(new HanaMigrationCommand(columnComment(table, desired)));
        }
    }

    @Override
    public List<MigrationCommand> syncTableIndex(TableMigrationData table, boolean oldTable) {
        var existing = existing(table, oldTable);
        var renames = renames(table, existing);
        var result = new ArrayList<MigrationCommand>();
        for (var index : table.getIndexes()) {
            var current = existing.indexes().stream().filter(item -> item.name().equals(raw(index.getIndexName()))).findFirst();
            if (current.isEmpty()) result.add(createIndex(table, index));
            else {
                var desired = index.getFields().stream().map(field -> raw(field.getColumnName())).toList();
                var columns = current.get().columns().stream().map(column -> renames.getOrDefault(column.name(), column.name())).toList();
                if (index.isUnique() != current.get().unique() || !desired.equals(columns))
                    throw manual(table, "index definition " + index.getIndexName());
                for (int i = 0; i < index.getFields().size(); i++) {
                    String order = current.get().columns().get(i).order();
                    if (!(index.getFields().get(i).isAsc() ? "TRUE" : "FALSE").equals(order)) {
                        throw manual(table, "index order " + index.getIndexName());
                    }
                }
            }
        }
        return result;
    }

    @Override
    public List<MigrationCommand> syncTableForeignKey(TableMigrationData table, QueryRuntimeContext context, boolean oldTable) {
        var existing = existing(table, oldTable);
        var result = new ArrayList<MigrationCommand>();
        for (var key : table.getForeignKeys()) {
            var current = existing.foreignKeys().stream().filter(item -> item.name().equals(raw(key.getName()))).findFirst();
            if (current.isEmpty()) result.add(createTableForeignKey(table, key));
            else {
                var value = current.get();
                var actions = actions(key.getAction());
                String schema = raw(targetSchema(table, key));
                if (schema == null) schema = catalog.currentSchema();
                if (!schema.equals(value.referencedSchema()) || !raw(key.getTargetTable()).equals(value.referencedTable())
                        || !java.util.Arrays.stream(key.getSelfColumn()).map(HanaMigrationSql::raw).toList().equals(value.columns().stream().map(HanaRelationMetadata.ReferenceColumn::name).toList())
                        || !java.util.Arrays.stream(key.getTargetColumn()).map(HanaMigrationSql::raw).toList().equals(value.columns().stream().map(HanaRelationMetadata.ReferenceColumn::referencedColumn).toList())
                        || !actions.getOrDefault("UPDATE", "RESTRICT").equals(value.updateRule())
                        || !actions.getOrDefault("DELETE", "RESTRICT").equals(value.deleteRule()) || !value.enforced()) {
                    throw manual(table, "foreign key definition " + key.getName());
                }
            }
        }
        return result;
    }

    private HanaRelationMetadata existing(TableMigrationData table, boolean oldTable) {
        String name = oldTable ? table.getOldTableName() : table.getTableName();
        var metadata = catalog.describe(raw(table.getSchema()), raw(name)).orElseThrow(() -> new IllegalStateException("HANA table disappeared while planning migration: " + name));
        requireTable(metadata.relation().kind(), name);
        return metadata;
    }

    private static Map<String, String> renames(TableMigrationData table, HanaRelationMetadata existing) {
        var names = existing.columns().stream().map(HanaRelationMetadata.Column::name).collect(Collectors.toSet());
        return table.getColumns().stream().filter(column -> names.contains(raw(column.getOldColumnName())) && !names.contains(raw(column.getName())))
                .collect(Collectors.toMap(column -> raw(column.getOldColumnName()), column -> raw(column.getName())));
    }

    private static void requireTable(Kind kind, String name) {
        if (kind != Kind.TABLE)
            throw new IllegalArgumentException("HANA Code First requires a base table, found " + kind + ": " + name);
    }


    private static String emptyComment(String comment) {
        return comment == null || comment.isEmpty() ? null : comment;
    }

    private static String tableComment(TableMigrationData table) {
        return "COMMENT ON TABLE " + table(table) + " IS " + literal(emptyComment(table.getComment()));
    }

    private static String columnComment(TableMigrationData table, ColumnMigrationData column) {
        return "COMMENT ON COLUMN " + table(table) + "." + name(column.getName()) + " IS " + literal(emptyComment(column.getComment()));
    }

    private static IllegalArgumentException manual(TableMigrationData table, String change) {
        return new IllegalArgumentException("HANA migration requires an explicit reviewed command for " + change + " on " + table(table));
    }
}
