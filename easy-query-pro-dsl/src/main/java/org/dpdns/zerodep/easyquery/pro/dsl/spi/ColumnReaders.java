package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.basic.extension.conversion.ColumnReader;
import com.easy.query.core.basic.extension.conversion.ColumnReaderImpl;
import com.easy.query.core.basic.extension.conversion.DefaultValueConverter;
import com.easy.query.core.basic.jdbc.executor.impl.def.EntityResultColumnMetadata;
import com.easy.query.core.basic.jdbc.executor.impl.def.BasicResultColumnMetadata;
import com.easy.query.core.basic.jdbc.executor.ResultColumnMetadata;
import com.easy.query.core.basic.jdbc.executor.internal.enumerable.DraftResult;
import com.easy.query.core.basic.jdbc.executor.internal.merge.result.StreamResultSet;
import com.easy.query.core.basic.jdbc.executor.internal.props.BasicJdbcProperty;
import com.easy.query.core.basic.jdbc.executor.internal.props.JdbcProperty;
import com.easy.query.core.basic.jdbc.types.EasyParameter;
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
import com.easy.query.core.util.EasyClassUtil;
import com.easy.query.core.util.EasyJdbcExecutorUtil;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 按来源关系解析并传播投影列的读取契约，包括 JDBC handler、业务转换和解密行为。
 *
 * <p>派生表、CTE 和列重排会重复使用 value1/value2 等别名，必须按“源关系 + 输出列”寻找 reader，
 * 不能直接使用当前上下文的同名项。本类只保存读取规则，不保存某次查询的 Connection 或 ResultSet，
 * 实际读取仍使用上游执行器传入的当前结果行。
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

    /**
     * Draft/标量需携带源映射；普通 DTO 使用目标字段的 handler，保留 Integer 到 Long 等映射能力。
     */
    static boolean usesSourceReaders(EntityMetadata result) {
        return DraftResult.class.isAssignableFrom(result.getEntityClass())
                || result.getEntityMetadataType() == EntityMetadataTypeEnum.BASIC_TYPE;
    }

    /**
     * 安装自动读取规则，同时保留本次投影的新显式 valueConvert。
     * 上游在 selector 之前注册显式 reader，因此只替换 Pro 自动项和已属于源关系的继承项；
     * 新出现的不透明用户 reader 优先级更高，不能被自动推导覆盖或删除。
     */
    static void setAutomatic(EntityExpressionBuilder query, String property, ColumnReader reader) {
        if (property == null) return;
        var registered = query.getExpressionContext().getResultValueConverterMap(reader != null);
        if (registered == null) return;
        var current = registered.get(property);
        // 当前 map 可能已经混入外层回调注册的 reader，必须按所有权判断可否覆盖。
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

    /**
     * 固定关系的输出读取规则：显式 reader 优先，DTO 使用目标列，Draft 使用按位置记录的类型，
     * 最后才回到普通源列元数据。UNION 按输出位置检查兼容性，不能依赖各分支的别名一致。
     */
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
        // 未显式投影的单个派生关系会输出 SELECT *，其读取规则仍属于内层，不能得到一个空快照。
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

    /**
     * 窗口空 frame 可能生成 SQL NULL，原实体 primitive 的默认 0/false 不适用于这个结果。
     * 仅调整已知 handler 的 JDBC 可空读取，再保留原业务转换；不反射或猜测不透明 reader 的 NULL 契约。
     */
    static ColumnReader nullable(ColumnReader reader) {
        if (!(reader instanceof BoundReader bound)) return reader;
        if (bound.key instanceof BasicKey key && key.type.isPrimitive()) {
            Class<?> boxed = EasyClassUtil.getObjectTypeWhenPrimitive(key.type);
            return new BoundReader(new BasicKey(boxed, key.handler), (index, rows) -> key.handler.getValue(new BasicJdbcProperty(index, boxed), rows));
        }
        if (bound.key instanceof ColumnMetadata column && column.getPropertyType().isPrimitive()) {
            Class<?> boxed = EasyClassUtil.getObjectTypeWhenPrimitive(column.getPropertyType());
            return new BoundReader(new NullableColumnKey(column), (index, rows) -> {
                var metadata = new EntityResultColumnMetadata(index, column.getEntityMetadata(), column);
                var value = column.getJdbcTypeHandler().getValue(new BasicJdbcProperty(index, boxed), rows);
                return EasyJdbcExecutorUtil.fromValue(metadata, value);
            });
        }
        return reader;
    }

    static ResultColumnMetadata scalarMetadata(int index, ColumnReader reader) {
        // 原 reader 已完成解密/转换，Basic metadata 不能再挂同一转换器，避免二次转换。
        return new BasicResultColumnMetadata(Object.class, new ReaderHandler(reader), new BasicJdbcProperty(index, Object.class));
    }

    private record ReaderHandler(ColumnReader reader) implements JdbcTypeHandler {
        @Override
        public Object getValue(JdbcProperty property, StreamResultSet rows) throws SQLException {
            // JDBC 属性使用一基索引，ColumnReader 使用零基索引；只在桥接处转换一次。
            return reader.read(property.getJdbcIndex() - 1, rows);
        }

        @Override
        public void setParameter(EasyParameter parameter) {
            throw new UnsupportedOperationException("A projected reader is only used for result mapping");
        }
    }

    private static void requireCompatibleUnion(Map<String, ColumnReader> left, Map<String, ColumnReader> right) {
        if (left.size() != right.size())
            throw new IllegalArgumentException("UNION branches have incompatible projected readers");
        var a = left.values().iterator();
        var b = right.values().iterator();
        while (a.hasNext()) {
            var x = readerKey(a.next());
            var y = readerKey(b.next());
            boolean compatible = x == y || (x instanceof BasicKey || x instanceof NullableColumnKey) && x.equals(y);
            if (!compatible)
                throw new IllegalArgumentException("UNION branches require compatible column mappings; project an explicit common scalar first");
        }
    }

    private static Object readerKey(ColumnReader reader) {
        return reader instanceof BoundReader bound ? bound.key : reader;
    }

    /**
     * primitive 与包装类型的 NULL 行为不同，比较 UNION 契约时不能先装箱后视为等价。
     */
    private record BasicKey(Class<?> type, JdbcTypeHandler handler) {
    }

    private record NullableColumnKey(ColumnMetadata column) {
    }

    private record BoundReader(Object key, ColumnReader delegate) implements ColumnReader {
        @Override
        public Object read(int index, StreamResultSet rows) throws SQLException {
            return delegate.read(index, rows);
        }
    }
}
