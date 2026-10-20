package org.dpdns.zerodep.easyquery.pro.dsl.spi;

import com.easy.query.core.basic.extension.conversion.ColumnReader;
import com.easy.query.core.enums.MultiTableTypeEnum;
import com.easy.query.core.enums.SQLUnionEnum;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.sql.builder.EntityQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.EntityTableExpressionBuilder;
import com.easy.query.core.expression.sql.builder.ExpressionContext;
import com.easy.query.core.expression.sql.builder.SQLAnonymousUnionEntityQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.factory.DefaultEasyExpressionBuilderFactory;
import com.easy.query.core.expression.sql.builder.impl.AnonymousCteTableQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.impl.AnonymousDefaultTableExpressionBuilder;
import com.easy.query.core.expression.sql.builder.impl.AnonymousUnionQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.impl.AnonymousTreeCTERECURSIVEQueryExpressionBuilder;
import com.easy.query.core.expression.sql.builder.impl.QueryExpressionBuilder;
import com.easy.query.core.expression.sql.expression.EntityQuerySQLExpression;
import com.easy.query.core.expression.visitor.TableVisitor;

import java.util.Map;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;

/**
 * 在派生关系和 CTE 边界保存结果读取快照，并检查最终分片查询中的服务器运算。
 * SQL 构造继续委托上游 builder 与应用安装的原生 ExpressionFactory。
 * 应用可覆写受保护创建钩子扩展关系行为；复制时仍须保留已有 reader 快照与查询上下文。
 */
public class ProExpressionBuilderFactory extends DefaultEasyExpressionBuilderFactory {
    @Override
    public EntityQueryExpressionBuilder createEntityQueryExpressionBuilder(ExpressionContext context, Class<?> resultClass) {
        return createQuery(context, resultClass);
    }

    @Override
    public EntityQueryExpressionBuilder createAnonymousUnionQueryExpressionBuilder(List<EntityQueryExpressionBuilder> branches,
                                                                                   ExpressionContext context, Class<?> resultClass, SQLUnionEnum union) {
        return createUnion(branches, context, resultClass, union);
    }

    @Override
    public EntityQueryExpressionBuilder createAnonymousCTEQueryExpressionBuilder(String name, List<String> columns,
                                                                                 EntityQueryExpressionBuilder source, ExpressionContext context, Class<?> resultClass) {
        return createTreeCte(name, columns, source, context, resultClass);
    }

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

    protected Query createQuery(ExpressionContext context, Class<?> resultClass) {
        return new Query(context, resultClass);
    }

    protected Union createUnion(List<EntityQueryExpressionBuilder> branches, ExpressionContext context, Class<?> resultClass, SQLUnionEnum union) {
        return new Union(branches, context, resultClass, union);
    }

    protected TreeCte createTreeCte(String name, List<String> columns, EntityQueryExpressionBuilder source, ExpressionContext context, Class<?> resultClass) {
        return new TreeCte(name, columns, source, context, resultClass);
    }

    protected Cte createCte(String name, EntityQueryExpressionBuilder source, ExpressionContext context, Class<?> resultClass, Map<String, ColumnReader> readers) {
        return new Cte(this, name, source, context, resultClass, readers);
    }

    protected static class Query extends QueryExpressionBuilder {
        protected Query(ExpressionContext context, Class<?> resultClass) {
            super(context, resultClass);
        }

        @Override
        public EntityQuerySQLExpression toExpression() {
            var expression = super.toExpression();
            ServerEvaluationVisitor.validate(this, expression);
            return expression;
        }

        @Override
        public void accept(TableVisitor visitor) {
            if (visitor instanceof ServerEvaluationVisitor server) server.query(this, () -> super.accept(visitor));
            else super.accept(visitor);
        }
    }

    protected static class Union extends AnonymousUnionQueryExpressionBuilder {
        protected Union(List<EntityQueryExpressionBuilder> branches, ExpressionContext context, Class<?> resultClass, SQLUnionEnum union) {
            super(branches, context, resultClass, union);
        }

        @Override
        public void accept(TableVisitor visitor) {
            if (visitor instanceof ServerEvaluationVisitor server)
                server.query(this, () -> getEntityQueryExpressionBuilders().forEach(branch -> branch.accept(visitor)));
            else super.accept(visitor);
        }
    }

    protected static class TreeCte extends AnonymousTreeCTERECURSIVEQueryExpressionBuilder {
        private final EntityQueryExpressionBuilder source;

        protected TreeCte(String name, List<String> columns, EntityQueryExpressionBuilder source, ExpressionContext context, Class<?> resultClass) {
            super(name, columns, source, context, resultClass);
            this.source = source;
        }

        @Override
        public void accept(TableVisitor visitor) {
            if (visitor instanceof ServerEvaluationVisitor server) server.query(this, () -> source.accept(visitor));
            else super.accept(visitor);
        }
    }

    /**
     * 派生关系采用两阶段快照，两个时机不能合并。
     *
     * <p>内外层可能共享 ExpressionContext，外层 valueConvert 又会先于 selector 写入同一 map，
     * 因此 registered 在关系创建时立即复制，防止外层同名别名污染内层。
     * Draft 的输出类型却是在关系创建之后才补入上下文，所以最终 readers 需要延迟解析。
     * 快照冻结的是映射关系，不复制 reader 内部的业务服务，也不持有 JDBC 资源。
     */
    protected static class Relation extends AnonymousDefaultTableExpressionBuilder {
        private final Map<String, ColumnReader> registered;
        private Map<String, ColumnReader> readers;

        protected Relation(TableAvailable table, MultiTableTypeEnum kind, EntityQueryExpressionBuilder source) {
            super(table, kind, source);
            // 先冻结注册来源；此时 Draft 的位置类型可能尚未补齐。
            var current = source.getExpressionContext().getResultValueConverterMap(false);
            registered = current == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(current));
            if (source instanceof SQLAnonymousUnionEntityQueryExpressionBuilder)
                readers = ColumnReaders.snapshot(source, getEntityMetadata());
        }

        protected Map<String, ColumnReader> readers() {
            // 延迟读取位置类型，但只使用关系创建时冻结的注册项。
            if (readers == null)
                readers = ColumnReaders.snapshot(entityQueryExpressionBuilder, getEntityMetadata(), registered);
            return readers;
        }

        @Override
        public EntityTableExpressionBuilder copyEntityTableExpressionBuilder() {
            var copy = super.copyEntityTableExpressionBuilder();
            // 上游仍经 runtime factory 创建副本，再沿用已解析快照，避免重新读取被外层修改的 map。
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
        public void accept(TableVisitor visitor) {
            if (visitor instanceof ServerEvaluationVisitor server) server.query(this, () -> source.accept(visitor));
            else super.accept(visitor);
        }

        @Override
        public EntityQueryExpressionBuilder cloneEntityExpressionBuilder() {
            // 回到原工厂钩子，保留应用安装的 CTE 子类；沿用快照，不重新推导 reader。
            return factory.createCte(getCteTableName(), source, getExpressionContext(), getQueryClass(), readers);
        }
    }
}
