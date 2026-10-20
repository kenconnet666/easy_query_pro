package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.basic.extension.conversion.ColumnReader;
import com.easy.query.core.basic.extension.conversion.ColumnReaderImpl;
import com.easy.query.core.basic.extension.conversion.DefaultValueConverter;
import com.easy.query.core.basic.jdbc.executor.impl.def.EntityResultColumnMetadata;
import com.easy.query.core.basic.jdbc.executor.internal.enumerable.DraftResult;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.executor.internal.props.BasicJdbcProperty;
import com.easy.query.core.basic.jdbc.types.handler.JdbcTypeHandler;
import com.easy.query.core.enums.EntityMetadataTypeEnum;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.segment.ColumnSegment;
import com.easy.query.core.expression.segment.SQLEntityAliasSegment;
import com.easy.query.core.expression.sql.builder.AnonymousEntityTableExpressionBuilder;
import com.easy.query.core.expression.sql.builder.EntityExpressionBuilder;
import com.easy.query.core.expression.sql.builder.EntityQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.EntityTableExpressionBuilder;
import com.easy.query.core.expression.sql.builder.SQLAnonymousUnionEntityQueryExpressionBuilder;
import com.easy.query.core.metadata.ColumnMetadata;
import com.easy.query.core.metadata.EntityMetadata;
import com.easy.query.core.proxy.PropTypeColumn;
import com.easy.query.core.proxy.extension.functions.type.IgnoreValueConverterTypeExpression;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Resolves readers from their source relation before output aliases can overwrite the current query's reader map.
 */
final class ColumnReaders {
    private ColumnReaders() {
    }

    static ColumnReader source(PropTypeColumn<?> source) {
        if (source instanceof IgnoreValueConverterTypeExpression || source.getTable() == null || source.getValue() == null)
            return null;
        if (source instanceof MappedValueExpression<?> mapped) return mapped.columnReader();
        return source(source.getEntitySQLContext().getEntityExpressionBuilder(), source.getTable(), source.getValue());
    }

    static void freezeSources(EntityExpressionBuilder query) {
        tables(query).forEach(table -> {
            if (table instanceof ProExpressionBuilderFactory.Relation relation) relation.readers();
        });
    }

    static boolean usesSourceReaders(EntityMetadata result) {
        return DraftResult.class.isAssignableFrom(result.getEntityClass())
                || result.getEntityMetadataType() == EntityMetadataTypeEnum.BASIC_TYPE;
    }

    static void setAutomatic(EntityExpressionBuilder query, String property, ColumnReader reader) {
        if (property == null) return;
        var registered = query.getExpressionContext().getResultValueConverterMap(reader != null);
        if (registered == null) return;
        var current = registered.get(property);
        // Upstream valueConvert registers before the selector runs. Only replace our own or inherited readers.
        if (current != null && !(current instanceof BoundReader) && !isInherited(query, current)) return;
        if (reader == null) registered.remove(property);
        else registered.put(property, reader instanceof BoundReader ? reader : new BoundReader(reader, reader));
    }

    private static boolean isInherited(EntityExpressionBuilder query, ColumnReader reader) {
        if (tables(query).anyMatch(table -> table instanceof ProExpressionBuilderFactory.Relation relation
                && relation.readers().containsValue(reader))) return true;
        return query.getExpressionContext().hasDeclareExpressions()
                && query.getExpressionContext().getDeclareExpressions().stream().anyMatch(declaration ->
                declaration instanceof ProExpressionBuilderFactory.Cte cte && cte.readers.containsValue(reader));
    }

    static ColumnReader source(EntityExpressionBuilder query, TableAvailable table, String property) {
        if (table == null || property == null) return null;
        if (query != null) {
            var owner = tables(query).filter(candidate -> candidate.getEntityTable().equals(table)).findFirst().orElse(null);
            String columnName = table.getColumnName(property);
            if (owner instanceof ProExpressionBuilderFactory.Relation relation) {
                var reader = relation.readers().get(columnName);
                if (reader != null) return reader;
            } else if (owner instanceof AnonymousEntityTableExpressionBuilder anonymous) {
                var reader = snapshot(anonymous.getEntityQueryExpressionBuilder(), table.getEntityMetadata()).get(columnName);
                if (reader != null) return reader;
            } else if (owner != null && query.getExpressionContext().hasDeclareExpressions()) {
                var reference = owner.toExpression();
                String name = reference.getTableNameAs() == null ? table.getTableName() : reference.getTableNameAs().apply(table.getTableName());
                for (var declared : query.getExpressionContext().getDeclareExpressions()) {
                    if (declared instanceof ProExpressionBuilderFactory.Cte cte && cte.getCteTableName().equals(name)
                            && cte.cteTableClass() == table.getEntityClass()) {
                        var reader = cte.readers.get(columnName);
                        if (reader != null) return reader;
                    }
                }
            }
        }
        var column = table.getEntityMetadata().getColumnOrNull(property);
        return column == null ? null : column(column);
    }

    private static Stream<EntityTableExpressionBuilder> tables(EntityExpressionBuilder query) {
        return Stream.concat(query.getTables().stream(), query.getRelationTables().values().stream());
    }

    static Map<String, ColumnReader> snapshot(EntityQueryExpressionBuilder query, EntityMetadata result) {
        return snapshot(query, result, query.getExpressionContext().getResultValueConverterMap(false));
    }

    static Map<String, ColumnReader> snapshot(EntityQueryExpressionBuilder query, EntityMetadata result, Map<String, ColumnReader> registered) {
        if (query instanceof SQLAnonymousUnionEntityQueryExpressionBuilder union) {
            Map<String, ColumnReader> first = null;
            for (var branch : union.getEntityQueryExpressionBuilders()) {
                var current = snapshot(branch, result);
                if (first == null) first = current;
                else requireCompatibleUnion(first, current);
            }
            return first == null ? Map.of() : first;
        }
        var columns = query.getProjects().getSQLSegments();
        // An unprojected derived query renders SELECT *; its columns still belong to the inner relation.
        if (columns.isEmpty() && !query.hasGroup() && query.getTables().size() == 1) {
            var table = query.getTable(0);
            if (table instanceof ProExpressionBuilderFactory.Relation relation) return relation.readers();
            if (table instanceof AnonymousEntityTableExpressionBuilder anonymous)
                return snapshot(anonymous.getEntityQueryExpressionBuilder(), result);
        }
        var types = DraftResult.class.isAssignableFrom(result.getEntityClass()) ? query.getExpressionContext().getResultPropTypes() : null;
        var readers = new LinkedHashMap<String, ColumnReader>();
        for (int i = 0; i < columns.size(); i++) {
            var segment = columns.get(i);
            if (!(segment instanceof SQLEntityAliasSegment aliased)) continue;
            var column = segment instanceof ColumnSegment selected ? selected.getColumnMetadata() : null;
            String name = aliased.getAlias() != null ? aliased.getAlias() : column == null ? null : column.getName();
            if (name == null) continue;
            String property = result.getPropertyNameOrNull(name);
            if (property == null) property = name;
            ColumnReader reader = registered == null ? null : registered.get(property);
            if (reader == null && !usesSourceReaders(result) && result.getEntityMetadataType() == EntityMetadataTypeEnum.BEAN) {
                var destination = result.getColumnOrNull(property);
                if (destination != null) reader = column(destination);
            }
            if (reader == null && types != null && i < types.length && types[i] != null) {
                if (types[i] instanceof EntityResultColumnMetadata entity) reader = column(entity.getColumnMetadata());
                else {
                    Class<?> type = types[i].getPropertyType();
                    var handler = query.getRuntimeContext().getJdbcTypeHandlerManager().getHandler(type);
                    reader = new BoundReader(new BasicKey(type, handler), (index, rows) -> handler.getValue(new BasicJdbcProperty(index, type), rows));
                }
            }
            if (reader == null && column != null) reader = column(column);
            if (reader != null) readers.put(name, reader);
        }
        return Collections.unmodifiableMap(readers);
    }

    private static ColumnReader column(ColumnMetadata column) {
        Object key = !column.isEncryption() && column.getValueConverter() == DefaultValueConverter.INSTANCE
                ? new BasicKey(column.getPropertyType(), column.getJdbcTypeHandler()) : column;
        return new BoundReader(key, new ColumnReaderImpl(column.getEntityMetadata(), column, null));
    }

    private static void requireCompatibleUnion(Map<String, ColumnReader> left, Map<String, ColumnReader> right) {
        if (left.size() != right.size())
            throw new IllegalArgumentException("UNION branches have incompatible projected readers");
        var a = left.values().iterator();
        var b = right.values().iterator();
        while (a.hasNext()) {
            var x = readerKey(a.next());
            var y = readerKey(b.next());
            boolean compatible = x == y || x instanceof BasicKey && x.equals(y);
            if (!compatible)
                throw new IllegalArgumentException("UNION branches require compatible column mappings; project an explicit common scalar first");
        }
    }

    private static Object readerKey(ColumnReader reader) {
        return reader instanceof BoundReader bound ? bound.key : reader;
    }

    private record BasicKey(Class<?> type, JdbcTypeHandler handler) {
    }

    private record BoundReader(Object key, ColumnReader delegate) implements ColumnReader {
        @Override
        public Object read(int index, StreamResultSet rows) throws SQLException {
            return delegate.read(index, rows);
        }
    }
}
