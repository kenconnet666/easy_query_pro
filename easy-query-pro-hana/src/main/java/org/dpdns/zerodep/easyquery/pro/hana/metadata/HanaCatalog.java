package org.dpdns.zerodep.easyquery.pro.hana.metadata;

import com.easy.query.core.exception.EasyQuerySQLCommandException;
import com.easy.query.core.expression.lambda.SQLResultSetFunc;
import org.dpdns.zerodep.easyquery.pro.hana.config.HanaSQLKeyword;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaRelationMetadata.Column;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaRelationMetadata.ForeignKey;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaRelationMetadata.Index;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaRelationMetadata.IndexColumn;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaRelationMetadata.Kind;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaRelationMetadata.Nullability;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaRelationMetadata.ReferenceColumn;
import org.dpdns.zerodep.easyquery.pro.hana.metadata.HanaRelationMetadata.Relation;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.dpdns.zerodep.easyquery.pro.hana.internal.ImmutableCollections;
import java.sql.Statement;
import java.sql.PreparedStatement;

/**
 * 只读目录访问。schema/name 接收原始标识符，由本层引用；null schema 在当前连接解析。
 * describe 在同一借用连接上读取关系、列、索引和外键，但没有额外创建目录事务一致性快照。
 * 权限过滤和对象不存在严格区分，不能把元数据缺失当作可以安全覆盖对象的依据。
 */
public final class HanaCatalog {
    private final DataSource dataSource;
    private final HanaSQLKeyword keyword = new HanaSQLKeyword();

    public HanaCatalog(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public List<String> schemas() {
        return connected("list HANA schemas", connection -> query(connection,
                "SELECT SCHEMA_NAME FROM SYS.SCHEMAS ORDER BY SCHEMA_NAME", ImmutableCollections.listOf(), rows -> rows.getString(1)));
    }

    public String currentSchema() {
        return connected("resolve current HANA schema", connection -> schema(connection, null));
    }

    public List<Relation> relations(String schema) {
        return connected("list HANA relations", connection -> {
            String selected = schema(connection, schema);
            return relations(connection, selected, null);
        });
    }

    public Optional<Relation> find(String schema, String name) {
        keyword.quoteUnquotedName(name);
        return connected("find HANA relation " + name, connection -> find(connection, schema(connection, schema), name));
    }

    public Optional<HanaRelationMetadata> describe(String schema, String name) {
        keyword.quoteUnquotedName(name);
        return connected("describe HANA relation " + name, connection -> {
            String selected = schema(connection, schema);
            Optional<Relation> relation = find(connection, selected, name);
            if (!relation.isPresent()) return Optional.empty();
            Relation object = relation.get();
            return Optional.of(new HanaRelationMetadata(object, columns(connection, object),
                    object.kind() == Kind.TABLE ? indexes(connection, selected, name) : ImmutableCollections.listOf(),
                    object.kind() == Kind.TABLE ? foreignKeys(connection, selected, name) : ImmutableCollections.listOf()));
        });
    }

    /**
     * SYS 目录可能被权限过滤，查询为空不能直接当作对象不存在。
     * 用零行查询进一步探测；只有明确的对象不存在错误才返回 empty，权限及其他错误继续上抛，
     * 防止 Code First 在不可判定状态下误执行创建或覆盖操作。
     */
    private Optional<Relation> find(Connection connection, String schema, String name) throws SQLException {
        List<Relation> relations = relations(connection, schema, name);
        if (!relations.isEmpty()) return Optional.of(relations.get(0));
        // 目录受权限过滤。探测对象本身，不能把不可见或查询错误误当成“不存在”。
        String sql = "SELECT * FROM " + keyword.quoteUnquotedName(schema) + "." + keyword.quoteUnquotedName(name) + " WHERE 1=0";
        try (Statement statement = connection.createStatement(); ResultSet ignored = statement.executeQuery(sql)) {
            throw new SQLException("HANA object exists without visible table/view metadata: " + schema + "." + name, "42501");
        } catch (SQLException failure) {
            if (failure.getErrorCode() == 259) return Optional.empty();
            throw failure;
        }
    }

    private List<Relation> relations(Connection connection, String schema, String name) throws SQLException {
        String tableFilter = name == null ? "" : " AND TABLE_NAME=?";
        String viewFilter = name == null ? "" : " AND VIEW_NAME=?";
        String sql = "SELECT SCHEMA_NAME,TABLE_NAME AS OBJECT_NAME,'TABLE' AS KIND,TABLE_TYPE AS STORAGE_TYPE,"
                + "CAST(NULL AS NVARCHAR(32)) AS VIEW_TYPE,COMMENTS,IS_TEMPORARY FROM SYS.TABLES WHERE SCHEMA_NAME=?" + tableFilter
                + " UNION ALL SELECT SCHEMA_NAME,VIEW_NAME,'VIEW',NULL,VIEW_TYPE,COMMENTS,'FALSE' FROM SYS.VIEWS WHERE SCHEMA_NAME=?" + viewFilter
                + " ORDER BY OBJECT_NAME";
        List<String> parameters = name == null ? ImmutableCollections.listOf(schema, schema) : ImmutableCollections.listOf(schema, name, schema, name);
        return query(connection, sql, parameters, rows -> new Relation(rows.getString("SCHEMA_NAME"), rows.getString("OBJECT_NAME"),
                Kind.valueOf(rows.getString("KIND")), rows.getString("STORAGE_TYPE"), rows.getString("VIEW_TYPE"),
                rows.getString("COMMENTS"), "TRUE".equals(rows.getString("IS_TEMPORARY"))));
    }

    /**
     * 保留目录中的 NULL：浮点 DECIMAL 的 SCALE=NULL 不等同于定点 DECIMAL 的 scale=0；
     * LENGTH 或可空性未知时也不能擅自补成固定长度/NOT NULL。
     */
    private List<Column> columns(Connection connection, Relation relation) throws SQLException {
        boolean table = relation.kind() == Kind.TABLE;
        String sql = "SELECT COLUMN_NAME,POSITION,DATA_TYPE_NAME,LENGTH,SCALE,IS_NULLABLE,DEFAULT_VALUE,GENERATION_TYPE,GENERATED_ALWAYS_AS,COMMENTS"
                + " FROM SYS." + (table ? "TABLE_COLUMNS" : "VIEW_COLUMNS") + " WHERE SCHEMA_NAME=? AND "
                + (table ? "TABLE_NAME" : "VIEW_NAME") + "=? AND IS_HIDDEN='FALSE' ORDER BY POSITION";
        return query(connection, sql, ImmutableCollections.listOf(relation.schema(), relation.name()), rows -> {
            int scale = rows.getInt("SCALE");
            Integer nullableScale = rows.wasNull() ? null : scale;
            long length = rows.getLong("LENGTH");
            Long nullableLength = rows.wasNull() ? null : length;
            String nullable = rows.getString("IS_NULLABLE");
            Nullability nullability = "TRUE".equals(nullable) ? Nullability.NULLABLE : "FALSE".equals(nullable) ? Nullability.NOT_NULL : Nullability.UNKNOWN;
            return new Column(rows.getString("COLUMN_NAME"), rows.getInt("POSITION"), rows.getString("DATA_TYPE_NAME"),
                    nullableLength, nullableScale, nullability, rows.getString("DEFAULT_VALUE"),
                    rows.getString("GENERATION_TYPE"), rows.getString("GENERATED_ALWAYS_AS"), rows.getString("COMMENTS"));
        });
    }

    private List<Index> indexes(Connection connection, String schema, String table) throws SQLException {
        String sql = "SELECT i.INDEX_NAME,i.INDEX_TYPE,i.\"CONSTRAINT\",c.COLUMN_NAME,c.POSITION,c.ASCENDING_ORDER"
                + " FROM SYS.INDEXES i JOIN SYS.INDEX_COLUMNS c ON i.SCHEMA_NAME=c.SCHEMA_NAME AND i.TABLE_NAME=c.TABLE_NAME AND i.INDEX_NAME=c.INDEX_NAME"
                + " WHERE i.SCHEMA_NAME=? AND i.TABLE_NAME=? ORDER BY i.INDEX_NAME,c.POSITION";
        List<HanaCatalog.IndexRow> rows = query(connection, sql, ImmutableCollections.listOf(schema, table), result -> new IndexRow(result.getString("INDEX_NAME"),
                result.getString("INDEX_TYPE"), result.getString("CONSTRAINT"),
                new IndexColumn(result.getString("COLUMN_NAME"), result.getInt("POSITION"), result.getString("ASCENDING_ORDER"))));
        LinkedHashMap<String, List<HanaCatalog.IndexRow>> grouped = rows.stream().collect(Collectors.groupingBy(IndexRow::name, LinkedHashMap::new, Collectors.toList()));
        return grouped.values().stream().map(group -> {
            HanaCatalog.IndexRow first = group.get(0);
            return new Index(first.name(), first.type(), first.constraint(), group.stream().map(IndexRow::column).collect(ImmutableCollections.toList()));
        }).collect(ImmutableCollections.toList());
    }

    private List<ForeignKey> foreignKeys(Connection connection, String schema, String table) throws SQLException {
        String sql = "SELECT CONSTRAINT_NAME,REFERENCED_SCHEMA_NAME,REFERENCED_TABLE_NAME,UPDATE_RULE,DELETE_RULE,IS_ENFORCED,IS_VALIDATED,"
                + "COLUMN_NAME,REFERENCED_COLUMN_NAME,POSITION FROM SYS.REFERENTIAL_CONSTRAINTS WHERE SCHEMA_NAME=? AND TABLE_NAME=? ORDER BY CONSTRAINT_NAME,POSITION";
        List<HanaCatalog.ForeignKeyRow> rows = query(connection, sql, ImmutableCollections.listOf(schema, table), result -> new ForeignKeyRow(result.getString("CONSTRAINT_NAME"),
                result.getString("REFERENCED_SCHEMA_NAME"), result.getString("REFERENCED_TABLE_NAME"), result.getString("UPDATE_RULE"),
                result.getString("DELETE_RULE"), "TRUE".equals(result.getString("IS_ENFORCED")), "TRUE".equals(result.getString("IS_VALIDATED")),
                new ReferenceColumn(result.getString("COLUMN_NAME"), result.getString("REFERENCED_COLUMN_NAME"), result.getInt("POSITION"))));
        LinkedHashMap<String, List<HanaCatalog.ForeignKeyRow>> grouped = rows.stream().collect(Collectors.groupingBy(ForeignKeyRow::name, LinkedHashMap::new, Collectors.toList()));
        return grouped.values().stream().map(group -> {
            HanaCatalog.ForeignKeyRow first = group.get(0);
            return new ForeignKey(first.name(), first.schema(), first.table(), first.update(), first.delete(), first.enforced(), first.validated(),
                    group.stream().map(ForeignKeyRow::column).collect(ImmutableCollections.toList()));
        }).collect(ImmutableCollections.toList());
    }

    /**
     * 未显式给出 schema 时，在本次借用的同一个连接上解析当前 schema，不使用进程级缓存。
     */
    private String schema(Connection connection, String requested) throws SQLException {
        String schema = requested;
        if (schema == null)
            schema = query(connection, "SELECT CURRENT_SCHEMA FROM DUMMY", ImmutableCollections.listOf(), rows -> rows.getString(1)).get(0);
        keyword.quoteUnquotedName(schema);
        if (query(connection, "SELECT SCHEMA_NAME FROM SYS.SCHEMAS WHERE SCHEMA_NAME=?", ImmutableCollections.listOf(schema), rows -> rows.getString(1)).isEmpty()) {
            throw new SQLException("HANA schema is missing or not visible: " + schema, "42501");
        }
        return schema;
    }

    private <T> T connected(String operation, SQLResultSetFunc<Connection, T> action) {
        try (Connection connection = dataSource.getConnection()) {
            return action.apply(connection);
        } catch (SQLException failure) {
            throw new EasyQuerySQLCommandException("Unable to " + operation, failure);
        }
    }

    private static <T> List<T> query(Connection connection, String sql, List<String> parameters, SQLResultSetFunc<ResultSet, T> mapper) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) statement.setString(i + 1, parameters.get(i));
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<T> result = new ArrayList<T>();
                while (rows.next()) result.add(mapper.apply(rows));
                return ImmutableCollections.listCopyOf(result);
            }
        }
    }

    private static final class IndexRow {
        private final String name;
        private final String type;
        private final String constraint;
        private final IndexColumn column;

        private IndexRow(String name, String type, String constraint, IndexColumn column) {
            this.name = name;
            this.type = type;
            this.constraint = constraint;
            this.column = column;
        }

        public String name() {
            return name;
        }

        public String type() {
            return type;
        }

        public String constraint() {
            return constraint;
        }

        public IndexColumn column() {
            return column;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IndexRow)) return false;
            IndexRow other = (IndexRow) o;
            return Objects.equals(name, other.name)
                    && Objects.equals(type, other.type)
                    && Objects.equals(constraint, other.constraint)
                    && Objects.equals(column, other.column);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, type, constraint, column);
        }

        @Override
        public String toString() {
            return "IndexRow[name=" + name + ", type=" + type + ", constraint=" + constraint + ", column=" + column + "]";
        }
    }

    private static final class ForeignKeyRow {
        private final String name;
        private final String schema;
        private final String table;
        private final String update;
        private final String delete;
        private final boolean enforced;
        private final boolean validated;
        private final ReferenceColumn column;

        private ForeignKeyRow(String name, String schema, String table, String update, String delete, boolean enforced, boolean validated, ReferenceColumn column) {
            this.name = name;
            this.schema = schema;
            this.table = table;
            this.update = update;
            this.delete = delete;
            this.enforced = enforced;
            this.validated = validated;
            this.column = column;
        }

        public String name() {
            return name;
        }

        public String schema() {
            return schema;
        }

        public String table() {
            return table;
        }

        public String update() {
            return update;
        }

        public String delete() {
            return delete;
        }

        public boolean enforced() {
            return enforced;
        }

        public boolean validated() {
            return validated;
        }

        public ReferenceColumn column() {
            return column;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ForeignKeyRow)) return false;
            ForeignKeyRow other = (ForeignKeyRow) o;
            return Objects.equals(name, other.name)
                    && Objects.equals(schema, other.schema)
                    && Objects.equals(table, other.table)
                    && Objects.equals(update, other.update)
                    && Objects.equals(delete, other.delete)
                    && enforced == other.enforced
                    && validated == other.validated
                    && Objects.equals(column, other.column);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, schema, table, update, delete, enforced, validated, column);
        }

        @Override
        public String toString() {
            return "ForeignKeyRow[name=" + name + ", schema=" + schema + ", table=" + table + ", update=" + update + ", delete=" + delete + ", enforced=" + enforced + ", validated=" + validated + ", column=" + column + "]";
        }
    }
}
