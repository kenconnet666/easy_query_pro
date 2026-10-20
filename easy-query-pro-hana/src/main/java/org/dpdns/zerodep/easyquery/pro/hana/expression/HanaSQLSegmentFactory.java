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

/**
 * 通过已知表达式的返回标记维护 HANA LOB 在投影、排序和分组中的限制。
 * 标记可穿过透明包装，但不解析最终 SQL，也不承诺推断任意用户原生 SQL 的返回类型。
 */
public class HanaSQLSegmentFactory extends DefaultSQLSegmentFactory {
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

    /**
     * 检查当前表达式的返回值，而非“任一参数是否含 LOB”。例如 LENGTH(NCLOB) 返回标量，
     * 不能因内部访问过 NCLOB 就误拒绝其排序或分组。
     */
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
            // 返回类型 visitor 到此读取本节点标记并停止；普通表 visitor 仍遍历参数以保留表归属分析。
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
