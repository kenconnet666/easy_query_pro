package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.expression.segment.GroupByColumnSegment;
import com.easy.query.core.expression.segment.core.TableSQLSegment;
import com.easy.query.core.expression.segment.OrderBySegment;
import com.easy.query.core.expression.segment.SQLNativeSegment;
import com.easy.query.core.expression.segment.SQLSegment;
import com.easy.query.core.expression.segment.factory.DefaultSQLSegmentFactory;
import com.easy.query.core.expression.segment.impl.SQLNativeSegmentImpl;
import com.easy.query.core.expression.segment.scec.context.core.SQLNativeExpression;
import com.easy.query.core.expression.sql.builder.ExpressionContext;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.visitor.TableVisitor;

import java.util.Set;

import java.util.function.Function;

public final class HanaSQLSegmentFactory extends DefaultSQLSegmentFactory {
    @Override
    public SQLNativeSegment createSQLNativeSegment(ExpressionContext context, String template, SQLNativeExpression expression) {
        return new NativeProjection(context, template, expression, HanaLobSqlSupport.isLobReturn(template, expression));
    }

    @Override
    public GroupByColumnSegment createGroupBySQLNativeSegment(ExpressionContext context, String template, SQLNativeExpression expression) {
        if (HanaLobSqlSupport.isLobReturn(template, expression)) throw unsupported("GROUP BY");
        return super.createGroupBySQLNativeSegment(context, template, expression);
    }

    @Override
    public OrderBySegment createOrderBySQLNativeSegment(ExpressionContext context, String template, SQLNativeExpression expression, boolean ascending) {
        if (HanaLobSqlSupport.isLobReturn(template, expression)) throw unsupported("ORDER BY");
        return super.createOrderBySQLNativeSegment(context, template, expression, ascending);
    }

    @Override
    public OrderBySegment createOrderBySQLNativeSegment2(ExpressionContext context, SQLSegment segment, Function<String, String> format, SQLNativeExpression expression, boolean ascending) {
        if (hasLobResult(segment)) throw unsupported("ORDER BY");
        return super.createOrderBySQLNativeSegment2(context, segment, format, expression, ascending);
    }

    static IllegalArgumentException unsupported(String clause) {
        return HanaLobSqlSupport.unsupportedClause(clause);
    }

    static boolean hasLobResult(SQLSegment segment) {
        if (segment instanceof NativeProjection nativeSegment) return nativeSegment.lob;
        if (segment instanceof TableSQLSegment function) {
            var visitor = new ResultTypeVisitor();
            function.accept(visitor);
            return visitor.lob;
        }
        return false;
    }

    private static final class NativeProjection extends SQLNativeSegmentImpl {
        private final boolean lob;

        private NativeProjection(ExpressionContext context, String template, SQLNativeExpression expression, boolean lob) {
            super(context, template, expression);
            this.lob = lob;
        }

        @Override
        public SQLNativeSegment cloneSQLColumnSegment() {
            var copy = new NativeProjection(expressionContext, sqlSegment, sqlNativeExpression, lob);
            copy.setAlias(getAlias());
            return copy;
        }

        @Override
        public void accept(TableVisitor visitor) {
            // Function wrappers expose their nested segment via this public visitor. Stop at its return type, not its operands.
            if (visitor instanceof ResultTypeVisitor result) result.lob = lob;
            else super.accept(visitor);
        }
    }

    private static final class ResultTypeVisitor implements TableVisitor {
        private boolean lob;

        @Override
        public Set<TableAvailable> getTables() {
            return Set.of();
        }

        @Override
        public void visit(TableAvailable table) {
        }

        @Override
        public void addAll(TableVisitor visitor) {
        }

        @Override
        public boolean containsTable(TableAvailable table) {
            return false;
        }
    }
}
