package org.dpdns.zerodep.easyquery.pro.hana.metadata;

import java.util.List;
import java.util.Objects;
import org.dpdns.zerodep.easyquery.pro.hana.internal.ImmutableCollections;

/**
 * 数据库原始标识符与类型信息；不把存储类型隐式推断成 Java 属性类型。
 */
public final class HanaRelationMetadata {
    private final Relation relation;
    private final List<Column> columns;
    private final List<Index> indexes;
    private final List<ForeignKey> foreignKeys;

    public HanaRelationMetadata(Relation relation, List<Column> columns, List<Index> indexes, List<ForeignKey> foreignKeys) {
        columns = ImmutableCollections.listCopyOf(columns);
        indexes = ImmutableCollections.listCopyOf(indexes);
        foreignKeys = ImmutableCollections.listCopyOf(foreignKeys);
        this.relation = relation;
        this.columns = columns;
        this.indexes = indexes;
        this.foreignKeys = foreignKeys;
    }

    public enum Kind {TABLE, VIEW}

    public enum Nullability {NULLABLE, NOT_NULL, UNKNOWN}

    public static final class Relation {
        private final String schema;
        private final String name;
        private final Kind kind;
        private final String storageType;
        private final String viewType;
        private final String comment;
        private final boolean temporary;

        public Relation(String schema, String name, Kind kind, String storageType, String viewType, String comment, boolean temporary) {
            this.schema = schema;
            this.name = name;
            this.kind = kind;
            this.storageType = storageType;
            this.viewType = viewType;
            this.comment = comment;
            this.temporary = temporary;
        }

        public String schema() {
            return schema;
        }

        public String name() {
            return name;
        }

        public Kind kind() {
            return kind;
        }

        public String storageType() {
            return storageType;
        }

        public String viewType() {
            return viewType;
        }

        public String comment() {
            return comment;
        }

        public boolean temporary() {
            return temporary;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Relation)) return false;
            Relation other = (Relation) o;
            return Objects.equals(schema, other.schema)
                    && Objects.equals(name, other.name)
                    && Objects.equals(kind, other.kind)
                    && Objects.equals(storageType, other.storageType)
                    && Objects.equals(viewType, other.viewType)
                    && Objects.equals(comment, other.comment)
                    && temporary == other.temporary;
        }

        @Override
        public int hashCode() {
            return Objects.hash(schema, name, kind, storageType, viewType, comment, temporary);
        }

        @Override
        public String toString() {
            return "Relation[schema=" + schema + ", name=" + name + ", kind=" + kind + ", storageType=" + storageType + ", viewType=" + viewType + ", comment=" + comment + ", temporary=" + temporary + "]";
        }
    }

    public static final class Column {
        private final String name;
        private final int position;
        private final String type;
        private final Long length;
        private final Integer scale;
        private final Nullability nullability;
        private final String defaultValue;
        private final String generationType;
        private final String generationExpression;
        private final String comment;

        public Column(String name, int position, String type, Long length, Integer scale, Nullability nullability, String defaultValue, String generationType, String generationExpression, String comment) {
            this.name = name;
            this.position = position;
            this.type = type;
            this.length = length;
            this.scale = scale;
            this.nullability = nullability;
            this.defaultValue = defaultValue;
            this.generationType = generationType;
            this.generationExpression = generationExpression;
            this.comment = comment;
        }

        public String name() {
            return name;
        }

        public int position() {
            return position;
        }

        public String type() {
            return type;
        }

        public Long length() {
            return length;
        }

        public Integer scale() {
            return scale;
        }

        public Nullability nullability() {
            return nullability;
        }

        public String defaultValue() {
            return defaultValue;
        }

        public String generationType() {
            return generationType;
        }

        public String generationExpression() {
            return generationExpression;
        }

        public String comment() {
            return comment;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Column)) return false;
            Column other = (Column) o;
            return Objects.equals(name, other.name)
                    && position == other.position
                    && Objects.equals(type, other.type)
                    && Objects.equals(length, other.length)
                    && Objects.equals(scale, other.scale)
                    && Objects.equals(nullability, other.nullability)
                    && Objects.equals(defaultValue, other.defaultValue)
                    && Objects.equals(generationType, other.generationType)
                    && Objects.equals(generationExpression, other.generationExpression)
                    && Objects.equals(comment, other.comment);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, position, type, length, scale, nullability, defaultValue, generationType, generationExpression, comment);
        }

        @Override
        public String toString() {
            return "Column[name=" + name + ", position=" + position + ", type=" + type + ", length=" + length + ", scale=" + scale + ", nullability=" + nullability + ", defaultValue=" + defaultValue + ", generationType=" + generationType + ", generationExpression=" + generationExpression + ", comment=" + comment + "]";
        }
    }

    public static final class IndexColumn {
        private final String name;
        private final int position;
        private final String order;

        public IndexColumn(String name, int position, String order) {
            this.name = name;
            this.position = position;
            this.order = order;
        }

        public String name() {
            return name;
        }

        public int position() {
            return position;
        }

        public String order() {
            return order;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IndexColumn)) return false;
            IndexColumn other = (IndexColumn) o;
            return Objects.equals(name, other.name)
                    && position == other.position
                    && Objects.equals(order, other.order);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, position, order);
        }

        @Override
        public String toString() {
            return "IndexColumn[name=" + name + ", position=" + position + ", order=" + order + "]";
        }
    }

    public static final class Index {
        private final String name;
        private final String type;
        private final String constraint;
        private final List<IndexColumn> columns;

        public Index(String name, String type, String constraint, List<IndexColumn> columns) {
            columns = ImmutableCollections.listCopyOf(columns);
            this.name = name;
            this.type = type;
            this.constraint = constraint;
            this.columns = columns;
        }

        public boolean primary() {
            return "PRIMARY KEY".equals(constraint);
        }

        public boolean unique() {
            return primary() || "UNIQUE".equals(constraint);
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

        public List<IndexColumn> columns() {
            return columns;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Index)) return false;
            Index other = (Index) o;
            return Objects.equals(name, other.name)
                    && Objects.equals(type, other.type)
                    && Objects.equals(constraint, other.constraint)
                    && Objects.equals(columns, other.columns);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, type, constraint, columns);
        }

        @Override
        public String toString() {
            return "Index[name=" + name + ", type=" + type + ", constraint=" + constraint + ", columns=" + columns + "]";
        }
    }

    public static final class ReferenceColumn {
        private final String name;
        private final String referencedColumn;
        private final int position;

        public ReferenceColumn(String name, String referencedColumn, int position) {
            this.name = name;
            this.referencedColumn = referencedColumn;
            this.position = position;
        }

        public String name() {
            return name;
        }

        public String referencedColumn() {
            return referencedColumn;
        }

        public int position() {
            return position;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ReferenceColumn)) return false;
            ReferenceColumn other = (ReferenceColumn) o;
            return Objects.equals(name, other.name)
                    && Objects.equals(referencedColumn, other.referencedColumn)
                    && position == other.position;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, referencedColumn, position);
        }

        @Override
        public String toString() {
            return "ReferenceColumn[name=" + name + ", referencedColumn=" + referencedColumn + ", position=" + position + "]";
        }
    }

    public static final class ForeignKey {
        private final String name;
        private final String referencedSchema;
        private final String referencedTable;
        private final String updateRule;
        private final String deleteRule;
        private final boolean enforced;
        private final boolean validated;
        private final List<ReferenceColumn> columns;

        public ForeignKey(String name, String referencedSchema, String referencedTable, String updateRule, String deleteRule, boolean enforced, boolean validated, List<ReferenceColumn> columns) {
            columns = ImmutableCollections.listCopyOf(columns);
            this.name = name;
            this.referencedSchema = referencedSchema;
            this.referencedTable = referencedTable;
            this.updateRule = updateRule;
            this.deleteRule = deleteRule;
            this.enforced = enforced;
            this.validated = validated;
            this.columns = columns;
        }

        public String name() {
            return name;
        }

        public String referencedSchema() {
            return referencedSchema;
        }

        public String referencedTable() {
            return referencedTable;
        }

        public String updateRule() {
            return updateRule;
        }

        public String deleteRule() {
            return deleteRule;
        }

        public boolean enforced() {
            return enforced;
        }

        public boolean validated() {
            return validated;
        }

        public List<ReferenceColumn> columns() {
            return columns;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ForeignKey)) return false;
            ForeignKey other = (ForeignKey) o;
            return Objects.equals(name, other.name)
                    && Objects.equals(referencedSchema, other.referencedSchema)
                    && Objects.equals(referencedTable, other.referencedTable)
                    && Objects.equals(updateRule, other.updateRule)
                    && Objects.equals(deleteRule, other.deleteRule)
                    && enforced == other.enforced
                    && validated == other.validated
                    && Objects.equals(columns, other.columns);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, referencedSchema, referencedTable, updateRule, deleteRule, enforced, validated, columns);
        }

        @Override
        public String toString() {
            return "ForeignKey[name=" + name + ", referencedSchema=" + referencedSchema + ", referencedTable=" + referencedTable + ", updateRule=" + updateRule + ", deleteRule=" + deleteRule + ", enforced=" + enforced + ", validated=" + validated + ", columns=" + columns + "]";
        }
    }

    public List<String> primaryKeyColumns() {
        return indexes.stream().filter(Index::primary).flatMap(index -> index.columns().stream()).map(IndexColumn::name).collect(ImmutableCollections.toList());
    }

    public Relation relation() {
        return relation;
    }

    public List<Column> columns() {
        return columns;
    }

    public List<Index> indexes() {
        return indexes;
    }

    public List<ForeignKey> foreignKeys() {
        return foreignKeys;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HanaRelationMetadata)) return false;
        HanaRelationMetadata other = (HanaRelationMetadata) o;
        return Objects.equals(relation, other.relation)
                && Objects.equals(columns, other.columns)
                && Objects.equals(indexes, other.indexes)
                && Objects.equals(foreignKeys, other.foreignKeys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relation, columns, indexes, foreignKeys);
    }

    @Override
    public String toString() {
        return "HanaRelationMetadata[relation=" + relation + ", columns=" + columns + ", indexes=" + indexes + ", foreignKeys=" + foreignKeys + "]";
    }
}
