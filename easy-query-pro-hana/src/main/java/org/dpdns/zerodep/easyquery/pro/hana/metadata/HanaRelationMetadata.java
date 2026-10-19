package org.dpdns.zerodep.easyquery.pro.hana.metadata;

import java.util.List;

/**
 * 数据库原始标识符与类型信息；不把存储类型隐式推断成 Java 属性类型。
 */
public record HanaRelationMetadata(Relation relation, List<Column> columns, List<Index> indexes,
                                   List<ForeignKey> foreignKeys) {
    public HanaRelationMetadata {
        columns = List.copyOf(columns);
        indexes = List.copyOf(indexes);
        foreignKeys = List.copyOf(foreignKeys);
    }

    public enum Kind {TABLE, VIEW}

    public enum Nullability {NULLABLE, NOT_NULL, UNKNOWN}

    public record Relation(String schema, String name, Kind kind, String storageType, String viewType,
                           String comment, boolean temporary) {
    }

    public record Column(String name, int position, String type, Long length, Integer scale, Nullability nullability,
                         String defaultValue, String generationType, String generationExpression, String comment) {
    }

    public record IndexColumn(String name, int position, String order) {
    }

    public record Index(String name, String type, String constraint, List<IndexColumn> columns) {
        public Index {
            columns = List.copyOf(columns);
        }

        public boolean primary() {
            return "PRIMARY KEY".equals(constraint);
        }

        public boolean unique() {
            return primary() || "UNIQUE".equals(constraint);
        }
    }

    public record ReferenceColumn(String name, String referencedColumn, int position) {
    }

    public record ForeignKey(String name, String referencedSchema, String referencedTable, String updateRule,
                             String deleteRule, boolean enforced, boolean validated, List<ReferenceColumn> columns) {
        public ForeignKey {
            columns = List.copyOf(columns);
        }
    }

    public List<String> primaryKeyColumns() {
        return indexes.stream().filter(Index::primary).flatMap(index -> index.columns().stream()).map(IndexColumn::name).toList();
    }
}
