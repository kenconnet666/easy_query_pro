package org.dpdns.zerodep.easyquery.pro.hana.migration;

import com.easy.query.core.metadata.ColumnMetadata;
import com.easy.query.core.annotation.ForeignKey;
import com.easy.query.core.context.QueryRuntimeContext;
import com.easy.query.core.migration.ColumnDbTypeResult;
import com.easy.query.core.migration.DefaultMigrationEntityParser;
import com.easy.query.core.migration.EntityMigrationMetadata;
import com.easy.query.core.migration.TableForeignKeyResult;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dpdns.zerodep.easyquery.pro.hana.internal.ImmutableCollections;
import com.easy.query.core.metadata.EntityMetadata;
import com.easy.query.core.metadata.NavigateMetadata;

/**
 * 保留上游注解、枚举和自定义 parser 契约，只替换 HANA 的类型与默认值。
 */
public class HanaMigrationEntityParser extends DefaultMigrationEntityParser {
    private static final Map<Class<?>, ColumnDbTypeResult> TYPES = types();

    private static Map<Class<?>, ColumnDbTypeResult> types() {
        HashMap<Class<?>, ColumnDbTypeResult> types = new HashMap<Class<?>, ColumnDbTypeResult>();
        add(types, boolean.class, Boolean.class, "BOOLEAN", "FALSE");
        // Java Byte 有符号，HANA TINYINT 无符号；默认映射必须能表示 -128。
        add(types, byte.class, Byte.class, "SMALLINT", "0");
        add(types, short.class, Short.class, "SMALLINT", "0");
        add(types, int.class, Integer.class, "INTEGER", "0");
        add(types, long.class, Long.class, "BIGINT", "0");
        add(types, float.class, Float.class, "REAL", "0");
        add(types, double.class, Double.class, "DOUBLE", "0");
        types.put(String.class, new ColumnDbTypeResult("NVARCHAR(255)", null));
        types.put(UUID.class, new ColumnDbTypeResult("NVARCHAR(36)", null));
        types.put(byte[].class, new ColumnDbTypeResult("VARBINARY(255)", null));
        types.put(BigDecimal.class, new ColumnDbTypeResult("DECIMAL(16,2)", null));
        types.put(LocalDate.class, new ColumnDbTypeResult("DATE", null));
        types.put(LocalTime.class, new ColumnDbTypeResult("TIME", null));
        types.put(LocalDateTime.class, new ColumnDbTypeResult("TIMESTAMP", null));
        types.put(Instant.class, new ColumnDbTypeResult("TIMESTAMP", null));
        types.put(java.sql.Date.class, new ColumnDbTypeResult("DATE", null));
        types.put(java.sql.Time.class, new ColumnDbTypeResult("TIME", null));
        types.put(java.sql.Timestamp.class, new ColumnDbTypeResult("TIMESTAMP", null));
        types.put(java.util.Date.class, new ColumnDbTypeResult("TIMESTAMP", null));
        return ImmutableCollections.mapCopyOf(types);
    }

    private static void add(Map<Class<?>, ColumnDbTypeResult> types, Class<?> primitive, Class<?> boxed, String sql, String defaultValue) {
        types.put(primitive, new ColumnDbTypeResult(sql, defaultValue));
        types.put(boxed, new ColumnDbTypeResult(sql, null));
    }

    @Override
    protected Map<Class<?>, ColumnDbTypeResult> getColumnTypeMap() {
        return TYPES;
    }

    @Override
    protected String replaceSqlTypeLength(String sql, int length, int scale) {
        HanaColumnType type = HanaColumnType.parse(sql);
        if (length < 0) return type.sql();
        String parameters = length == 0 ? "" : "(" + length + (type.name().equals("DECIMAL") ? "," + scale : "") + ")";
        if (!type.name().equals("DECIMAL") && scale != 0) {
            throw new IllegalArgumentException("HANA scale is only valid for DECIMAL: " + sql);
        }
        return HanaColumnType.parse(type.name() + parameters).sql();
    }

    @Override
    public @NotNull ColumnDbTypeResult getColumnDbType(EntityMigrationMetadata entity, ColumnMetadata column) {
        ColumnDbTypeResult result = super.getColumnDbType(entity, column);
        try {
            return new ColumnDbTypeResult(HanaColumnType.parse(result.columnType).sql(), result.defValue);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid HANA DDL type for " + entity.getEntityMetadata().getEntityClass().getName()
                    + "." + column.getPropertyName() + ": " + result.columnType, failure);
        }
    }

    @Override
    public @NotNull List<TableForeignKeyResult> getTableForeignKeys(EntityMigrationMetadata migration, QueryRuntimeContext context) {
        EntityMetadata entity = migration.getEntityMetadata();
        ArrayList<TableForeignKeyResult> result = new ArrayList<TableForeignKeyResult>();
        for (NavigateMetadata navigate : entity.getNavigateMetadatas()) {
            ForeignKey annotation = migration.getFieldByName(navigate.getPropertyName()).getAnnotation(ForeignKey.class);
            if (annotation == null) continue;
            EntityMetadata target = context.getEntityMetadataManager().getEntityMetadata(navigate.getNavigatePropertyType());
            String[] selfColumns = Arrays.stream(navigate.getSelfPropertiesOrPrimary()).map(property -> entity.getColumnNotNull(property).getName()).toArray(String[]::new);
            String[] targetColumns = Arrays.stream(navigate.getTargetPropertiesOrPrimary(context)).map(property -> target.getColumnNotNull(property).getName()).toArray(String[]::new);
            String name = entity.getTableName() + "_" + target.getTableName() + "_" + String.join("_", targetColumns) + "_fk";
            result.add(new SchemaForeignKey(name, annotation.action(), entity.getTableName(), target.getTableName(),
                    selfColumns, targetColumns, target.getSchemaOrNull()));
        }
        return result;
    }

    static final class SchemaForeignKey extends TableForeignKeyResult {
        final String targetSchema;

        SchemaForeignKey(String name, String action, String self, String target, String[] selfColumns, String[] targetColumns, String schema) {
            super(name, action, self, target, selfColumns, targetColumns);
            targetSchema = schema;
        }
    }
}
