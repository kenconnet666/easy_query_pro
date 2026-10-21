package org.dpdns.zerodep.easyquery.pro.hana.migration;

import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.inject.ServiceProvider;
import com.easy.query.core.metadata.EntityMetadataManager;
import com.easy.query.core.migration.DatabaseMigrationProvider;
import com.easy.query.core.migration.DefaultMigrationsSQLGenerator;
import com.easy.query.core.migration.MigrationCommand;
import com.easy.query.core.migration.MigrationContext;
import com.easy.query.core.migration.data.TableMigrationData;

import java.util.ArrayList;
import java.util.List;
import com.easy.query.core.metadata.EntityMetadata;
import com.easy.query.core.migration.data.ColumnMigrationData;
import com.easy.query.core.metadata.ColumnMetadata;
import com.easy.query.core.migration.TableForeignKeyResult;
import org.dpdns.zerodep.easyquery.pro.hana.internal.ImmutableCollections;

/**
 * 复用实体解析；修正重命名时的目录目标，以及多表创建的约束执行顺序。
 */
public final class HanaMigrationsSQLGenerator extends DefaultMigrationsSQLGenerator {
    private final EntityMetadataManager metadata;
    private final DatabaseMigrationProvider provider;
    private final ServiceProvider services;

    public HanaMigrationsSQLGenerator(EntityMetadataManager metadata, DatabaseMigrationProvider provider, ServiceProvider services) {
        super(metadata, provider, services);
        this.metadata = metadata;
        this.provider = provider;
        this.services = services;
    }

    @Override
    public TableMigrationData parseEntity(Class<?> entityClass) {
        TableMigrationData table = super.parseEntity(entityClass);
        EntityMetadata entity = metadata.getEntityMetadata(entityClass);
        for (ColumnMigrationData column : table.getColumns()) {
            ColumnMetadata source = entity.getColumns().stream().filter(item -> item.getName().equals(column.getName())).findFirst().get();
            if (source.getGeneratedSQLColumnGenerator() != null) column.setGeneratedKey(false);
        }
        QueryRuntimeContext context = services.getService(QueryRuntimeContext.class);
        List<TableForeignKeyResult> keys = provider.getMigrationEntityParser().getTableForeignKeys(provider.createEntityMigrationMetadata(entity), context);
        // 3.2.17 的默认 generator 没有复制 action，也没有目标 schema 字段。
        table.setForeignKeys(keys.stream().map(key -> {
            HanaForeignKeyMigrationData data = new HanaForeignKeyMigrationData();
            data.setName(key.name);
            data.setAction(key.action);
            data.setSelfTable(key.selfTable);
            data.setTargetTable(key.targetTable);
            data.setSelfColumn(key.selfColumn.clone());
            data.setTargetColumn(key.targetColumn.clone());
            if (key instanceof HanaMigrationEntityParser.SchemaForeignKey)
                data.setTargetSchema(((HanaMigrationEntityParser.SchemaForeignKey) key).targetSchema);
            return (com.easy.query.core.migration.data.ForeignKeyMigrationData) data;
        }).collect(ImmutableCollections.toList()));
        return table;
    }

    @Override
    public List<MigrationCommand> generateCreateTableMigrationSQL(MigrationContext context) {
        ArrayList<MigrationCommand> commands = new ArrayList<MigrationCommand>();
        ArrayList<MigrationCommand> keys = new ArrayList<MigrationCommand>();
        QueryRuntimeContext runtime = services.getService(QueryRuntimeContext.class);
        for (TableMigrationData table : context.getTableMigrationDataList()) {
            commands.add(provider.createTable(table));
            commands.addAll(provider.createTableIndex(table));
            keys.addAll(provider.createTableForeignKey(table, runtime));
        }
        commands.addAll(keys);
        return commands;
    }

    @Override
    public List<MigrationCommand> generateMigrationSQL(MigrationContext context) {
        ArrayList<MigrationCommand> commands = new ArrayList<MigrationCommand>();
        ArrayList<MigrationCommand> keys = new ArrayList<MigrationCommand>();
        QueryRuntimeContext runtime = services.getService(QueryRuntimeContext.class);
        for (TableMigrationData table : context.getTableMigrationDataList()) {
            boolean exists = provider.tableExists(table.getSchema(), table.getTableName());
            boolean oldExists = !exists && provider.tableExists(table.getSchema(), table.getOldTableName());
            if (!exists && !oldExists) {
                commands.add(provider.createTable(table));
                commands.addAll(provider.createTableIndex(table));
                keys.addAll(provider.createTableForeignKey(table, runtime));
                continue;
            }
            if (oldExists) commands.add(provider.renameTable(table));
            // 预览阶段尚未执行 RENAME；读取旧表，生成针对新名称的语句。
            commands.addAll(provider.syncTable(table, oldExists));
            commands.addAll(provider.syncTableIndex(table, oldExists));
            keys.addAll(provider.syncTableForeignKey(table, runtime, oldExists));
        }
        commands.addAll(keys);
        return commands;
    }
}
