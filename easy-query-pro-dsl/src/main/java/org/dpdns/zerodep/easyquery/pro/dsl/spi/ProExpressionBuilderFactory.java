package org.dpdns.zerodep.easyquery.pro.dsl.spi;

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
        return createRelation(table, kind, source);
    }

    @Override
    public EntityQueryExpressionBuilder createAnonymousWithTableQueryExpressionBuilder(String name, EntityQueryExpressionBuilder source, ExpressionContext context, Class<?> resultClass) {
        return createCte(name, source, context, resultClass, ColumnReaders.snapshot(source, context.getRuntimeContext().getEntityMetadataManager().getEntityMetadata(resultClass)));
    }

    protected Relation createRelation(TableAvailable table, MultiTableTypeEnum kind, EntityQueryExpressionBuilder source) {
        return new Relation(table, kind, source);
    }

    protected Cte createCte(String name, EntityQueryExpressionBuilder source, ExpressionContext context, Class<?> resultClass, Map<String, ColumnReader> readers) {
        return new Cte(this, name, source, context, resultClass, readers);
    }

    protected static class Relation extends AnonymousDefaultTableExpressionBuilder {
        private final Map<String, ColumnReader> registered;
        private Map<String, ColumnReader> readers;

        protected Relation(TableAvailable table, MultiTableTypeEnum kind, EntityQueryExpressionBuilder source) {
            super(table, kind, source);
            // Outer select callbacks share this context and register valueConvert before the selector is created.
            var current = source.getExpressionContext().getResultValueConverterMap(false);
            registered = current == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(current));
            if (source instanceof SQLAnonymousUnionEntityQueryExpressionBuilder)
                readers = ColumnReaders.snapshot(source, getEntityMetadata());
        }

        protected Map<String, ColumnReader> readers() {
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

    protected static class Cte extends AnonymousCteTableQueryExpressionBuilder {
        private final ProExpressionBuilderFactory factory;
        private final EntityQueryExpressionBuilder source;
        final Map<String, ColumnReader> readers;

        protected Cte(ProExpressionBuilderFactory factory, String name, EntityQueryExpressionBuilder source, ExpressionContext context, Class<?> resultClass, Map<String, ColumnReader> readers) {
            super(name, source, context, resultClass);
            this.factory = factory;
            this.source = source;
            this.readers = readers;
        }

        @Override
        public EntityQueryExpressionBuilder cloneEntityExpressionBuilder() {
            return factory.createCte(getCteTableName(), source, getExpressionContext(), getQueryClass(), readers);
        }
    }
}
