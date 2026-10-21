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
import com.easy.query.core.proxy.ProxyEntity;
import com.easy.query.core.proxy.core.draft.proxy.DraftProxy;
import com.easy.query.core.proxy.extension.functions.type.IgnoreValueConverterTypeExpression;
import com.easy.query.core.util.EasyClassUtil;
import com.easy.query.core.util.EasyJdbcExecutorUtil;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import com.easy.query.core.expression.sql.expression.EntityTableSQLExpression;
import com.easy.query.core.expression.sql.builder.ExpressionBuilder;
import java.util.List;
import com.easy.query.core.expression.segment.SQLSegment;
import java.util.Objects;
import java.util.Iterator;

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
        if (source instanceof MappedValueExpression<?>) return ((MappedValueExpression<?>) source).columnReader();
        return source(source.getEntitySQLContext().getEntityExpressionBuilder(), source.getTable(), source.getValue());
    }

    static void freezeSources(EntityExpressionBuilder query) {
        tables(query).forEach(table -> {
            if (table instanceof ProExpressionBuilderFactory.Relation) ((ProExpressionBuilderFactory.Relation) table).readers();
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
     * 这里只维护优先级，不解包其内部转换链，也不修正上游已绑定的来源元数据。
     * 裸 Draft.valueN().valueConvert 与经过 Pro 来源解析的整值表达式不是同一种读取保证。
     */
    static void setAutomatic(EntityExpressionBuilder query, String property, ColumnReader reader) {
        if (property == null) return;
        Map<String, ColumnReader> registered = query.getExpressionContext().getResultValueConverterMap(reader != null);
        if (registered == null) return;
        ColumnReader current = registered.get(property);
        // 当前 map 可能已经混入外层回调注册的 reader，必须按所有权判断可否覆盖。
        if (current != null && !(current instanceof BoundReader) && !isInherited(query, current)) return;
        if (reader == null) registered.remove(property);
        else registered.put(property, reader instanceof BoundReader ? reader : new BoundReader(reader, reader));
    }

    private static boolean isInherited(EntityExpressionBuilder query, ColumnReader reader) {
        if (tables(query).anyMatch(table -> table instanceof ProExpressionBuilderFactory.Relation
                && ((ProExpressionBuilderFactory.Relation) table).readers().containsValue(reader))) return true;
        return query.getExpressionContext().hasDeclareExpressions()
                && query.getExpressionContext().getDeclareExpressions().stream().anyMatch(declaration ->
                declaration instanceof ProExpressionBuilderFactory.Cte && ((ProExpressionBuilderFactory.Cte) declaration).readers.containsValue(reader));
    }

    static ColumnReader source(EntityExpressionBuilder query, TableAvailable table, String property) {
        if (table == null || property == null) return null;
        if (query != null) {
            EntityTableExpressionBuilder owner = tables(query).filter(candidate -> candidate.getEntityTable().equals(table)).findFirst().orElse(null);
            String columnName = table.getColumnName(property);
            if (owner instanceof ProExpressionBuilderFactory.Relation) {
                ProExpressionBuilderFactory.Relation relation = (ProExpressionBuilderFactory.Relation) owner;
                ColumnReader reader = relation.readers().get(columnName);
                if (reader != null) return reader;
            } else if (owner instanceof AnonymousEntityTableExpressionBuilder) {
                AnonymousEntityTableExpressionBuilder anonymous = (AnonymousEntityTableExpressionBuilder) owner;
                ColumnReader reader = snapshot(anonymous.getEntityQueryExpressionBuilder(), table.getEntityMetadata()).get(columnName);
                if (reader != null) return reader;
            } else if (owner != null && query.getExpressionContext().hasDeclareExpressions()) {
                EntityTableSQLExpression reference = owner.toExpression();
                String name = reference.getTableNameAs() == null ? table.getTableName() : reference.getTableNameAs().apply(table.getTableName());
                for (ExpressionBuilder declared : query.getExpressionContext().getDeclareExpressions()) {
                    if (declared instanceof ProExpressionBuilderFactory.Cte && ((ProExpressionBuilderFactory.Cte) declared).getCteTableName().equals(name)
                            && ((ProExpressionBuilderFactory.Cte) declared).cteTableClass() == table.getEntityClass()) {
                        ProExpressionBuilderFactory.Cte cte = (ProExpressionBuilderFactory.Cte) declared;
                        ColumnReader reader = cte.readers.get(columnName);
                        if (reader != null) return reader;
                    }
                }
            }
        }
        ColumnMetadata column = table.getEntityMetadata().getColumnOrNull(property);
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
        if (query instanceof SQLAnonymousUnionEntityQueryExpressionBuilder) {
            SQLAnonymousUnionEntityQueryExpressionBuilder union = (SQLAnonymousUnionEntityQueryExpressionBuilder) query;
            Map<String, ColumnReader> first = null;
            for (EntityQueryExpressionBuilder branch : union.getEntityQueryExpressionBuilders()) {
                Map<String, ColumnReader> current = snapshot(branch, result);
                if (first == null) first = current;
                else requireCompatibleUnion(first, current);
            }
            return first == null ? Collections.emptyMap() : first;
        }
        List<SQLSegment> columns = query.getProjects().getSQLSegments();
        // 未显式投影的单个派生关系会输出 SELECT *，其读取规则仍属于内层，不能得到一个空快照。
        if (columns.isEmpty() && !query.hasGroup() && query.getTables().size() == 1) {
            EntityTableExpressionBuilder table = query.getTable(0);
            if (table instanceof ProExpressionBuilderFactory.Relation) return ((ProExpressionBuilderFactory.Relation) table).readers();
            if (table instanceof AnonymousEntityTableExpressionBuilder)
                return snapshot(((AnonymousEntityTableExpressionBuilder) table).getEntityQueryExpressionBuilder(), result);
        }
        ResultColumnMetadata[] types = DraftResult.class.isAssignableFrom(result.getEntityClass()) ? query.getExpressionContext().getResultPropTypes() : null;
        LinkedHashMap<String, ColumnReader> readers = new LinkedHashMap<String, ColumnReader>();
        for (int i = 0; i < columns.size(); i++) {
            SQLSegment segment = columns.get(i);
            if (!(segment instanceof SQLEntityAliasSegment)) continue;
            SQLEntityAliasSegment aliased = (SQLEntityAliasSegment) segment;
            ColumnMetadata column = segment instanceof ColumnSegment ? ((ColumnSegment) segment).getColumnMetadata() : null;
            String name = aliased.getAlias() != null ? aliased.getAlias() : column == null ? null : column.getName();
            if (name == null) continue;
            String property = result.getPropertyNameOrNull(name);
            if (property == null) property = name;
            ColumnReader reader = registered == null ? null : registered.get(property);
            if (reader == null && !usesSourceReaders(result) && result.getEntityMetadataType() == EntityMetadataTypeEnum.BEAN) {
                ColumnMetadata destination = result.getColumnOrNull(property);
                if (destination != null) reader = column(destination);
            }
            if (reader == null && types != null && i < types.length && types[i] != null) {
                if (types[i] instanceof EntityResultColumnMetadata) {
                    EntityResultColumnMetadata entity = (EntityResultColumnMetadata) types[i];
                    reader = column(entity.getColumnMetadata());
                    // 小计输出沿用原实体 converter，但其结果槽已经允许 NULL，不能退回 primitive 默认值。
                    if (entity.getColumnMetadata().getPropertyType().isPrimitive() && !entity.getJdbcProperty().isPrimitive())
                        reader = nullable(reader);
                } else {
                    Class<?> type = types[i].getPropertyType();
                    JdbcTypeHandler handler = query.getRuntimeContext().getJdbcTypeHandlerManager().getHandler(type);
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
     * 窗口空 frame 和分组小计可能生成 SQL NULL，原实体 primitive 的默认 0/false 不适用于这个结果。
     * 仅调整已知 handler 的 JDBC 可空读取，再保留原业务转换；不反射或猜测不透明 reader 的 NULL 契约。
     */
    static ColumnReader nullable(ColumnReader reader) {
        if (!(reader instanceof BoundReader)) return reader;
        BoundReader bound = (BoundReader) reader;
        if (bound.key instanceof BasicKey && ((BasicKey) bound.key).type.isPrimitive()) {
            BasicKey key = (BasicKey) bound.key;
            Class<?> boxed = EasyClassUtil.getObjectTypeWhenPrimitive(key.type);
            return new BoundReader(new BasicKey(boxed, key.handler), (index, rows) -> key.handler.getValue(new BasicJdbcProperty(index, boxed), rows));
        }
        if (bound.key instanceof ColumnMetadata && ((ColumnMetadata) bound.key).getPropertyType().isPrimitive()) {
            ColumnMetadata column = (ColumnMetadata) bound.key;
            Class<?> boxed = EasyClassUtil.getObjectTypeWhenPrimitive(column.getPropertyType());
            return new BoundReader(new NullableColumnKey(column), (index, rows) -> {
                EntityResultColumnMetadata metadata = new EntityResultColumnMetadata(index, column.getEntityMetadata(), column);
                Object value = column.getJdbcTypeHandler().getValue(new BasicJdbcProperty(index, boxed), rows);
                return EasyJdbcExecutorUtil.fromValue(metadata, value);
            });
        }
        return reader;
    }

    static ResultColumnMetadata scalarMetadata(int index, ColumnReader reader) {
        // 原 reader 已完成解密/转换，Basic metadata 不能再挂同一转换器，避免二次转换。
        return new BasicResultColumnMetadata(Object.class, new ReaderHandler(reader), new BasicJdbcProperty(index, Object.class));
    }

    static void prepareNullableDraft(ProxyEntity<?, ?> projection) {
        if (!(projection instanceof DraftProxy)) return;
        DraftProxy draft = (DraftProxy) projection;
        if (projection.getTable() != null)
            throw new IllegalArgumentException("Expanded grouping requires a fresh Select.DRAFT.of(...) projection or an explicit DTO");
        ResultColumnMetadata[] types = draft.getDraftPropTypes();
        for (int index = 0; index < types.length; index++) {
            ResultColumnMetadata type = types[index];
            if (type == null || !type.getPropertyType().isPrimitive()) continue;
            Class<?> boxed = EasyClassUtil.getObjectTypeWhenPrimitive(type.getPropertyType());
            types[index] = type instanceof EntityResultColumnMetadata
                    ? new NullableEntityMetadata(index, ((EntityResultColumnMetadata) type), boxed)
                    : new BasicResultColumnMetadata(boxed, type.getJdbcTypeHandler(), new BasicJdbcProperty(index, boxed));
        }
    }

    /**
     * 保留 Entity metadata 身份，DraftStreamIterator 才会继续调用列 converter/encryption。
     * 只替换 JDBC 读取时的可空类型；不修改共享实体 ColumnMetadata 或源代理的属性类型。
     */
    private static class NullableEntityMetadata extends EntityResultColumnMetadata {
        private final JdbcProperty nullableProperty;

        private NullableEntityMetadata(int index, EntityResultColumnMetadata source, Class<?> boxed) {
            super(index, source.getColumnMetadata().getEntityMetadata(), source.getColumnMetadata());
            nullableProperty = new BasicJdbcProperty(index, boxed);
        }

        @Override
        public Class<?> getPropertyType() {
            return nullableProperty.getPropertyType();
        }

        @Override
        public JdbcProperty getJdbcProperty() {
            return nullableProperty;
        }
    }

    private static final class ReaderHandler implements JdbcTypeHandler {
        private final ColumnReader reader;

        private ReaderHandler(ColumnReader reader) {
            this.reader = reader;
        }
        @Override
        public Object getValue(JdbcProperty property, StreamResultSet rows) throws SQLException {
            // JDBC 属性使用一基索引，ColumnReader 使用零基索引；只在桥接处转换一次。
            return reader.read(property.getJdbcIndex() - 1, rows);
        }

        @Override
        public void setParameter(EasyParameter parameter) {
            throw new UnsupportedOperationException("A projected reader is only used for result mapping");
        }

        public ColumnReader reader() {
            return reader;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ReaderHandler)) return false;
            ReaderHandler other = (ReaderHandler) o;
            return Objects.equals(reader, other.reader);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reader);
        }

        @Override
        public String toString() {
            return "ReaderHandler[reader=" + reader + "]";
        }
    }

    private static void requireCompatibleUnion(Map<String, ColumnReader> left, Map<String, ColumnReader> right) {
        if (left.size() != right.size())
            throw new IllegalArgumentException("UNION branches have incompatible projected readers");
        Iterator<ColumnReader> a = left.values().iterator();
        Iterator<ColumnReader> b = right.values().iterator();
        while (a.hasNext()) {
            Object x = readerKey(a.next());
            Object y = readerKey(b.next());
            boolean compatible = x == y || (x instanceof BasicKey || x instanceof NullableColumnKey) && x.equals(y);
            if (!compatible)
                throw new IllegalArgumentException("UNION branches require compatible column mappings; project an explicit common scalar first");
        }
    }

    private static Object readerKey(ColumnReader reader) {
        return reader instanceof BoundReader ? ((BoundReader) reader).key : reader;
    }

    /**
     * primitive 与包装类型的 NULL 行为不同，比较 UNION 契约时不能先装箱后视为等价。
     */
    private static final class BasicKey {
        private final Class<?> type;
        private final JdbcTypeHandler handler;

        private BasicKey(Class<?> type, JdbcTypeHandler handler) {
            this.type = type;
            this.handler = handler;
        }

        public Class<?> type() {
            return type;
        }

        public JdbcTypeHandler handler() {
            return handler;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BasicKey)) return false;
            BasicKey other = (BasicKey) o;
            return Objects.equals(type, other.type)
                    && Objects.equals(handler, other.handler);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, handler);
        }

        @Override
        public String toString() {
            return "BasicKey[type=" + type + ", handler=" + handler + "]";
        }
    }

    private static final class NullableColumnKey {
        private final ColumnMetadata column;

        private NullableColumnKey(ColumnMetadata column) {
            this.column = column;
        }

        public ColumnMetadata column() {
            return column;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof NullableColumnKey)) return false;
            NullableColumnKey other = (NullableColumnKey) o;
            return Objects.equals(column, other.column);
        }

        @Override
        public int hashCode() {
            return Objects.hash(column);
        }

        @Override
        public String toString() {
            return "NullableColumnKey[column=" + column + "]";
        }
    }

    private static final class BoundReader implements ColumnReader {
        private final Object key;
        private final ColumnReader delegate;

        private BoundReader(Object key, ColumnReader delegate) {
            this.key = key;
            this.delegate = delegate;
        }
        @Override
        public Object read(int index, StreamResultSet rows) throws SQLException {
            return delegate.read(index, rows);
        }

        public Object key() {
            return key;
        }

        public ColumnReader delegate() {
            return delegate;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BoundReader)) return false;
            BoundReader other = (BoundReader) o;
            return Objects.equals(key, other.key)
                    && Objects.equals(delegate, other.delegate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, delegate);
        }

        @Override
        public String toString() {
            return "BoundReader[key=" + key + ", delegate=" + delegate + "]";
        }
    }
}
