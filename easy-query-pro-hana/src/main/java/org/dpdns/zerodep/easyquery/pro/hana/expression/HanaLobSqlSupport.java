package org.dpdns.zerodep.easyquery.pro.hana.expression;

import com.easy.query.core.basic.jdbc.parameter.ToSQLContext;
import com.easy.query.core.expression.func.AggregationType;
import com.easy.query.core.expression.parser.core.available.TableAvailable;
import com.easy.query.core.expression.parser.core.base.scec.core.SQLNativeChainExpressionContext;
import com.easy.query.core.expression.segment.SQLSegment;
import com.easy.query.core.expression.segment.core.TableSQLSegment;
import com.easy.query.core.expression.segment.scec.context.core.SQLNativeExpression;
import com.easy.query.core.expression.segment.scec.expression.SQLSegmentParamExpression;
import com.easy.query.core.expression.visitor.TableVisitor;
import com.easy.query.core.func.SQLFunction;
import com.easy.query.core.func.SQLFunctionTranslateImpl;
import com.easy.query.core.util.EasySQLSegmentUtil;

import java.util.Objects;

/**
 * Carries a known LOB result through the public native-segment factory without inspecting rendered SQL.
 */
public final class HanaLobSqlSupport {
    private HanaLobSqlSupport() {
    }

    public static IllegalArgumentException unsupportedClause(String clause) {
        return new IllegalArgumentException("HANA " + clause + " cannot use a LOB value; explicitly convert it to a scalar first");
    }

    public static SQLFunction lobResult(SQLFunction delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new SQLFunction() {
            @Override
            public String sqlSegment(TableAvailable table) {
                return "{0}";
            }

            @Override
            public int paramMarks() {
                return 1;
            }

            @Override
            public AggregationType getAggregationType() {
                return delegate.getAggregationType();
            }

            @Override
            public void consume(SQLNativeChainExpressionContext context) {
                var segment = new SQLFunctionTranslateImpl(delegate).toSQLSegment(context.getExpressionContext(), context.getDefaultTable(), context.getRuntimeContext(), null);
                context.sql(new LobValue(context.getDefaultTable(), segment));
            }
        };
    }

    static boolean isLobReturn(String template, SQLNativeExpression expression) {
        var arguments = expression.getExpressions();
        boolean identity = template.equals("{0}") || template.equals("{0} ASC") || template.equals("{0} DESC");
        return identity && arguments.size() == 1 && arguments.get(0) instanceof SQLSegmentParamExpression value
                && (value.getSQLSegment() instanceof LobValue || HanaSQLSegmentFactory.hasLobResult(value.getSQLSegment()));
    }

    private record LobValue(TableAvailable table, SQLSegment delegate) implements TableSQLSegment {
        @Override
        public TableAvailable getTable() {
            return table;
        }

        @Override
        public LobValue cloneSQLColumnSegment() {
            return this;
        }

        @Override
        public String toSQL(ToSQLContext context) {
            return delegate.toSQL(context);
        }

        @Override
        public void accept(TableVisitor visitor) {
            EasySQLSegmentUtil.sqlSegmentTableVisit(delegate, visitor);
        }
    }
}
