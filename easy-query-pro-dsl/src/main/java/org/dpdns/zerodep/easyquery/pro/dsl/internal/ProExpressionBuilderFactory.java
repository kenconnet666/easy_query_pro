package org.dpdns.zerodep.easyquery.pro.dsl.internal;

import com.easy.query.core.basic.extension.conversion.ColumnReader;
import com.easy.query.core.enums.MultiTableTypeEnum;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.sql.builder.EntityQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.EntityTableExpressionBuilder;
import com.easy.query.core.expression.sql.builder.ExpressionContext;
import com.easy.query.core.expression.sql.builder.SQLAnonymousUnionEntityQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.factory.DefaultEasyExpressionBuilderFactory;
import com.easy.query.core.expression.sql.builder.impl.AnonymousCteTableQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.impl.AnonymousDefaultTableExpressionBuilder;

import java.util.Map;
import java.util.Collections;
import java.util.HashMap;

/**
 * Keeps result-reader snapshots at relation boundaries; SQL generation remains in the upstream builders.
 */
public class ProExpressionBuilderFactory extends DefaultEasyExpressionBuilderFactory {
    @Override
    public EntityTableExpressionBuilder createAnonymousEntityTableExpressionBuilder(TableAvailable table, MultiTableTypeEnum kind, EntityQueryExpressionBuilder source) {
        return new Relation(table, kind, source);
    }

    @Override
    public EntityQueryExpressionBuilder createAnonymousWithTableQueryExpressionBuilder(String name, EntityQueryExpressionBuilder source, ExpressionContext context, Class<?> resultClass) {
        return new Cte(name, source, context, resultClass, ColumnReaders.snapshot(source, context.getRuntimeContext().getEntityMetadataManager().getEntityMetadata(resultClass)));
    }

    static final class Relation extends AnonymousDefaultTableExpressionBuilder {
        private final Map<String, ColumnReader> registered;
        private Map<String, ColumnReader> readers;

        private Relation(TableAvailable table, MultiTableTypeEnum kind, EntityQueryExpressionBuilder source) {
            super(table, kind, source);
            // Outer select callbacks share this context and register valueConvert before the selector is created.
            var current = source.getExpressionContext().getResultValueConverterMap(false);
            registered = current == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(current));
            if (source instanceof SQLAnonymousUnionEntityQueryExpressionBuilder)
                readers = ColumnReaders.snapshot(source, getEntityMetadata());
        }

        Map<String, ColumnReader> readers() {
            // Draft result types are assigned immediately after relation creation; reader registrations are already final.
            if (readers == null)
                readers = ColumnReaders.snapshot(entityQueryExpressionBuilder, getEntityMetadata(), registered);
            return readers;
        }

        @Override
        public EntityTableExpressionBuilder copyEntityTableExpressionBuilder() {
            var copy = super.copyEntityTableExpressionBuilder();
            if (copy instanceof Relation relation) relation.readers = readers();
            return copy;
        }
    }

    static final class Cte extends AnonymousCteTableQueryExpressionBuilder {
        private final EntityQueryExpressionBuilder source;
        final Map<String, ColumnReader> readers;

        private Cte(String name, EntityQueryExpressionBuilder source, ExpressionContext context, Class<?> resultClass, Map<String, ColumnReader> readers) {
            super(name, source, context, resultClass);
            this.source = source;
            this.readers = readers;
        }

        @Override
        public EntityQueryExpressionBuilder cloneEntityExpressionBuilder() {
            return new Cte(getCteTableName(), source, getExpressionContext(), getQueryClass(), readers);
        }
    }
}
